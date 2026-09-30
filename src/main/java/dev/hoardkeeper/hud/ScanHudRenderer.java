package dev.hoardkeeper.hud;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.scan.CandidateCounts;
import dev.hoardkeeper.scan.ClusterHint;
import dev.hoardkeeper.scan.ContainerStatus;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.scan.ScanSession;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A small always-on HUD panel showing live scan progress: how many containers are done, where the
 * nearest cluster of unscanned ones is, and the current rate/ETA. Attached just before the chat
 * HUD element so it never overlaps it.
 *
 * <p><b>Recomputes at tick rate, draws at frame rate.</b> {@link #render} is invoked by the game
 * loop every frame — 60 to 300+ times a second — but the scan's numbers cannot change faster than
 * once per client tick (20/s). Rebuilding {@link ClusterHint#nearest} and the line list on every
 * frame would allocate a bucket map over every pending candidate dozens of times a second for no
 * reason. So {@link #render} only ever draws {@link #cachedLines}; a fresh set is computed at most
 * once per tick, gated on {@link ScanController#tick()} (and on the session identity, so a brand
 * new scan is never drawn with the previous session's stale lines).
 */
public final class ScanHudRenderer {

    /** Cell size (in blocks) {@link ClusterHint#nearest} buckets pending candidates into. */
    private static final int CLUSTER_GRID_SIZE = 16;
    private static final int LINE_STEP_PX = 10;
    private static final int PADDING_PX = 3;
    private static final int BACKDROP_ARGB = 0x88000000;
    private static final int TEXT_ARGB = 0xFFFFFFFF;

    // ---- Per-tick cache. Frame rendering never touches ClusterHint/HudTextModel directly. ----
    private static int cachedTick = ScanController.NEVER;
    private static ScanSession cachedSession;
    private static List<String> cachedLines = List.of();

    private ScanHudRenderer() {
    }

    /** Registers the HUD element. Called once at client init — by Task 13, not by this class. */
    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(HoardkeeperMod.MOD_ID, "scan_hud"),
                ScanHudRenderer::render);
    }

    private static void render(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        HoardkeeperConfig config = HoardkeeperMod.config();
        ScanController controller = ScanController.get();
        if (!config.hudEnabled || !controller.isRunning()) {
            forget();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ScanSession session = controller.session();
        if (player == null || session == null || mc.level == null) {
            forget();
            return;
        }

        int tick = controller.tick();
        if (session != cachedSession || tick != cachedTick) {
            cachedLines = computeLines(session, player);
            cachedSession = session;
            cachedTick = tick;
        }

        List<String> lines = cachedLines;
        if (lines.isEmpty()) {
            return;
        }

        Font font = mc.font;
        int x = config.hudX;
        int y = config.hudY;

        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int height = lines.size() * LINE_STEP_PX + PADDING_PX;

        context.fill(x - PADDING_PX, y - PADDING_PX, x + width + PADDING_PX, y + height, BACKDROP_ARGB);

        int lineY = y;
        for (String line : lines) {
            context.text(font, line, x, lineY, TEXT_ARGB, true);
            lineY += LINE_STEP_PX;
        }
    }

    /**
     * Drops the cache. These are {@code static} fields, so without this a finished scan's
     * {@link ScanSession} — and therefore its entire candidate list — stayed reachable for the rest
     * of the game session, long after anything could draw it. Called from every path that decides
     * there is nothing to render, and from {@code ScanController.reset()} so that a scan ending
     * while the HUD is not being drawn at all (disconnected to the main menu) still releases it.
     */
    public static void forget() {
        cachedSession = null;
        cachedLines = List.of();
        cachedTick = ScanController.NEVER;
    }

    /**
     * Everything {@code HudTextModel.lines(...)} needs, computed once for this tick. All wording
     * and number formatting stays inside {@code HudTextModel} — this only gathers its inputs.
     */
    private static List<String> computeLines(ScanSession session, LocalPlayer player) {
        Vec3 eyeVec = player.getEyePosition();
        double[] eye = {eyeVec.x, eyeVec.y, eyeVec.z};

        int scanned = CandidateCounts.count(session.candidates, ContainerStatus.SCANNED);
        int failed = CandidateCounts.count(session.candidates, ContainerStatus.FAILED);
        int known = session.candidates.size();

        ClusterHint.Cluster cluster = ClusterHint.nearest(session.candidates, eye, CLUSTER_GRID_SIZE);
        double relativeYaw = cluster == null ? 0.0
                : HudTextModel.relativeYaw(eye, cluster.centre(), player.getYRot());

        // The rate comes from the controller rather than being recomputed here: it is the one place
        // that knows a resumed session must not credit its predecessor's containers to this leg.
        double perSecond = ScanController.get().perSecond();

        return HudTextModel.lines(scanned, known, failed, cluster, relativeYaw, perSecond,
                session.chunksLoaded, session.chunksInRadius);
    }
}
