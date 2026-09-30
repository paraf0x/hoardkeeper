package dev.hoardkeeper.peek;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.index.ContainerView;
import dev.hoardkeeper.index.StorageIndex;
import dev.hoardkeeper.index.StorageIndexCache;
import dev.hoardkeeper.scan.ContainerDiscovery;
import dev.hoardkeeper.scan.ContainerKind;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The container card shown while crouching in front of a chest, barrel or shulker box: design spec
 * §6. Attached just before the chat HUD element, exactly as {@code hud.ScanHudRenderer} attaches
 * its own panel, so the two never overlap each other.
 *
 * <p><b>Computed on tick, drawn on frame</b> — the same split {@code ScanHudRenderer} uses, for a
 * stronger reason here than there: telling "a container is there but was never scanned" from "that
 * is a wall" needs {@link ContainerDiscovery#kindOf}, a block-state read, which may only ever happen
 * on the client thread. {@link #tick} is that read, called once per client tick from
 * {@code HoardkeeperMod}; {@link #render} — the method actually registered as the
 * {@code HudElement} — only ever reads {@link #cachedLines}, the field {@link #tick} last wrote.
 * Unlike {@code ScanHudRenderer}, which gates its own recompute inside {@code render()} against a
 * tick counter (because nothing else calls it once per tick), this class does not need to: it is
 * {@link #tick} itself, called externally exactly once per client tick, that is the whole gate.
 *
 * <p><b>Five ways to end up drawing nothing</b> (spec's "Global constraints"), all decided in
 * {@link #tick}, never in {@link #render}: {@code peekEnabled} off (folded together with "no player"
 * and "no level", since neither means anything to draw either), not crouching, no block under the
 * crosshair, {@link StorageIndexCache#current} answering {@code null}, and {@link PeekModel#lines}
 * itself answering an empty list (the block is not a container at all). {@link #render} does not
 * repeat any of that — it draws {@link #cachedLines} when it is non-empty and nothing when it is
 * empty, full stop.
 */
public final class PeekRenderer {

    private static final int LINE_STEP_PX = 10;
    private static final int PADDING_PX = 3;
    private static final int BACKDROP_ARGB = 0x88000000;
    private static final int TEXT_ARGB = 0xFFFFFFFF;

    /**
     * Vertical clearance, in pixels, between the card's bottom edge and the exact centre of the
     * screen, where the vanilla crosshair is drawn as a 15×15 icon (so its own top edge sits
     * {@code 7}–{@code 8}px above centre). Comfortably above that keeps the card off the crosshair
     * itself with room to spare; because the card sits at screen centre while the hotbar and the
     * vanilla held-item name both live near the bottom of the screen, the same margin keeps it off
     * them too on any window worth playing in.
     */
    private static final int GAP_ABOVE_CROSSHAIR_PX = 16;

    // ---- Per-tick cache. render() never touches the index, the config, a BlockPos or a BlockState
    // ---- directly -- see the class javadoc. Written only by tick(), read only by render(). ----
    private static List<String> cachedLines = List.of();

    private PeekRenderer() {
    }

    /** Registers the HUD element. Called once at client init — by {@code HoardkeeperMod}. */
    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(HoardkeeperMod.MOD_ID, "peek_card"),
                PeekRenderer::render);
    }

    /**
     * The lines {@link #render} would draw right now, as of the last {@link #tick}. Test-only: the
     * gametest scenario for this card ({@code gametest.scenario.PeekScenario}) lives in a different
     * package from this class, so a package-private accessor could not reach it — public is the
     * least-bad option, kept obviously test-shaped by name so nothing in {@code src/main} is tempted
     * to call it instead of going through {@link #tick}/{@link #render} the normal way.
     */
    public static List<String> currentLinesForTest() {
        return cachedLines;
    }

    /**
     * Recomputes {@link #cachedLines}. Called once per client tick from {@code HoardkeeperMod},
     * after {@link StorageIndexCache#tick} so that this tick's lookup sees a freshly rechecked index
     * rather than last tick's.
     */
    public static void tick(Minecraft mc) {
        HoardkeeperConfig config = HoardkeeperMod.config();
        LocalPlayer player = mc == null ? null : mc.player;
        ClientLevel level = mc == null ? null : mc.level;
        if (!config.peekEnabled || player == null || level == null) {
            cachedLines = List.of();
            return;
        }
        if (!player.isShiftKeyDown()) {
            cachedLines = List.of();
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            cachedLines = List.of();
            return;
        }

        StorageIndex index = StorageIndexCache.get().current(mc);
        if (index == null) {
            // Building, outside every scanned area, or no measured map on disk -- StorageIndexCache
            // treats all three as "say nothing", and so does this.
            cachedLines = List.of();
            return;
        }

        BlockPos pos = blockHit.getBlockPos();
        ContainerView view = index.positions().view(pos.getX(), pos.getY(), pos.getZ());
        // Only asked when the position index misses: this is the block-state read that must happen
        // on the client thread, and there is no reason to pay for it when view != null already
        // answers the question.
        ContainerKind kindAtPosition = view != null
                ? null
                : new ContainerDiscovery(config).kindOf(level.getBlockState(pos));

        cachedLines = PeekModel.lines(view, kindAtPosition, config.peekMaxItems, System.currentTimeMillis());
    }

    /**
     * Draws {@link #cachedLines}, centred horizontally with the card's bottom edge
     * {@link #GAP_ABOVE_CROSSHAIR_PX} pixels above the exact centre of the screen — see that
     * constant's javadoc for why that keeps the crosshair, the hotbar and the vanilla held-item name
     * all clear. Nothing here reads a file, a block state or the index: every input is either
     * {@link #cachedLines} or a value the game hands the HUD element to draw with.
     *
     * <p><b>M-7:</b> {@link #clampToScreen} runs first, so {@code lines} below is already whatever
     * fits — the card's top edge (including its padding) never draws above screen row 0, whatever
     * {@code peekMaxItems} is set to.
     */
    private static void render(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        List<String> lines = clampToScreen(cachedLines, context.guiHeight());
        if (lines.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;

        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int height = lines.size() * LINE_STEP_PX + PADDING_PX;

        int x = (context.guiWidth() - width) / 2;
        int bottom = context.guiHeight() / 2 - GAP_ABOVE_CROSSHAIR_PX;
        int y = bottom - height;

        context.fill(x - PADDING_PX, y - PADDING_PX, x + width + PADDING_PX, y + height, BACKDROP_ARGB);

        int lineY = y;
        for (String line : lines) {
            context.text(font, line, x, lineY, TEXT_ARGB, true);
            lineY += LINE_STEP_PX;
        }
    }

    /**
     * {@code lines}, cut down to however many fit above screen row 0 when the card is drawn
     * bottom-up from {@link #GAP_ABOVE_CROSSHAIR_PX} pixels above the vertical centre of a
     * {@code guiHeight}-px window, with whatever was cut replaced by one {@code "+N more"} line —
     * the same idiom {@link dev.hoardkeeper.peek.PeekModel} already uses for "there is more than
     * {@code peekMaxItems} allows", applied here to "more than a short window can show" instead.
     *
     * <p><b>M-7.</b> {@code peekMaxItems} clamps to 54 — the value the clamp exists to permit — but
     * at 1080p and GUI scale 3 a 54-item card is about 563px tall against a ~360px-tall
     * {@code guiHeight()}, so without this the card's top third would draw above the top of the
     * screen with no floor on {@code y}. Lines are dropped from the bottom, keeping the title and as
     * many item lines as fit, because that is what already reads as "more" to a player — the same
     * direction {@link PeekModel#lines} itself trims in.
     *
     * <p>Pure arithmetic over a line count and pixel sizes — no {@link Font}, no
     * {@code GuiGraphicsExtractor} — so a unit test can pin it without a client. Package-private for
     * that test; no other caller needs it below {@link #render}.
     */
    static List<String> clampToScreen(List<String> lines, int guiHeight) {
        if (lines.isEmpty()) {
            return lines;
        }
        int bottom = guiHeight / 2 - GAP_ABOVE_CROSSHAIR_PX;
        // The most lines whose card fits with its top edge (backdrop padding included) at or below
        // row 0: bottom - (n * LINE_STEP_PX + PADDING_PX) - PADDING_PX >= 0.
        int maxLines = Math.max(0, (bottom - 2 * PADDING_PX) / LINE_STEP_PX);
        if (lines.size() <= maxLines) {
            return lines;
        }
        if (maxLines == 0) {
            // Not even one line and its own "+N more" trailer fit -- draw nothing rather than a
            // card with no content of its own, the same way an empty cachedLines already draws
            // nothing above.
            return List.of();
        }
        List<String> clamped = new ArrayList<>(lines.subList(0, maxLines - 1));
        clamped.add("+" + (lines.size() - (maxLines - 1)) + " more");
        return clamped;
    }
}
