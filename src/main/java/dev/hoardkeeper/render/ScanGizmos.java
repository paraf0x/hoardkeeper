package dev.hoardkeeper.render;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.ContainerStatus;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.scan.ScanSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Draws the running scan's world-space overlay: coloured boxes over pending/failed (and,
 * optionally, scanned) candidates, a text billboard naming why a failed candidate failed, and an
 * arrow pointing at whatever is currently in flight.
 *
 * <p><b>Called from {@code ClientTickEvents.END_CLIENT_TICK}, not a render event and not a
 * mixin.</b> {@code Minecraft.runTick} wraps {@code tick()} in
 * {@code Gizmos.withCollector(perTickGizmos)}, and Fabric's client-tick hook fires inside that
 * scope — so any {@code Gizmos.*} call made from here is collected for the frame exactly like one
 * made from vanilla debug-rendering code. {@code LevelRenderer} then submits every collected gizmo
 * ungated: no F3, no debug flag. This was verified against 26.1.2 bytecode and confirmed visually
 * (red boxes rendering through solid blocks mid-scan) — see {@code docs/SPIKE.md} — and re-checked
 * on 26.2, where {@code runTick} still wraps {@code tick()} in {@code collectPerTickGizmos()}. Reaching for
 * {@code LevelRenderEvents} or a {@code LevelRenderer} mixin here would be re-deriving a solved
 * problem; {@code WorldRenderEvents} does not exist in this Fabric API version at all.
 */
public final class ScanGizmos {

    /** How far the chunk slab reaches below and above the player's feet. Enough to read, low
     *  enough to see past — a full-height column would hide the containers it is drawn around. */
    private static final double CHUNK_FRAME_BELOW = 0.4;
    private static final double CHUNK_FRAME_ABOVE = 2.4;


    private ScanGizmos() {
    }

    /** Emits this tick's gizmos for the running scan, or does nothing when there is none. */
    public static void emit(Minecraft mc) {
        ScanController controller = ScanController.get();
        HoardkeeperConfig config = HoardkeeperMod.config();
        if (!config.gizmosEnabled || !controller.isRunning()) {
            return;
        }

        ScanSession session = controller.session();
        LocalPlayer player = mc.player;
        if (session == null || player == null || mc.level == null) {
            return;
        }

        Vec3 eye = player.getEyePosition();
        if (config.chunkFrames) {
            drawChunkFrames(mc, session, player);
        }
        for (ContainerCandidate candidate : candidatesToDraw(session, config, eye)) {
            drawCandidate(candidate, config);
        }

        ContainerCandidate target = controller.currentTarget();
        if (target != null) {
            BlockPos pos = new BlockPos(target.pos[0], target.pos[1], target.pos[2]);
            Gizmos.arrow(eye, Vec3.atCenterOf(pos), config.colorTarget).setAlwaysOnTop();
        }
    }

    /**
     * Outlines the chunks the scan covers that the client has <em>not</em> been sent yet.
     *
     * <p>This is information, not decoration, and it answers a question nothing else can. The HUD
     * already says {@code Chunks 7/9}; what it cannot say is <em>which</em> two are missing. A scan
     * that appears to be finding nothing is usually a scan waiting on chunks the server has not sent,
     * and the fix is to walk toward them — which requires knowing where they are.
     *
     * <p><b>Only the missing ones are drawn.</b> Outlining the loaded chunks too was the original
     * design and it was wrong in the case that actually matters: the default scan is the 3×3 block
     * of chunks around the player, every one of them already loaded, so the overlay's normal state
     * was nine green boxes drawn around a player who had no question to ask. A frame that is present
     * whenever there is nothing to report is not a signal. Now the overlay is empty when the scan
     * has everything it needs, and every box on screen is a chunk worth walking toward.
     *
     * <p>Drawn as a low slab around the player's feet rather than a full-height column: a
     * 384-block-tall box per chunk fills the screen and hides the containers the scan is about, and
     * the vertical extent would be a lie anyway, since a scan covers the whole column regardless.
     * The frame marks the footprint, and the footprint is the part that is true.
     */
    private static void drawChunkFrames(Minecraft mc, ScanSession session, LocalPlayer player) {
        ScanArea area = session.area;
        int reach = area.sweepChunkRadius();
        int cx0 = area.originChunkX();
        int cz0 = area.originChunkZ();
        double y = player.getY();

        for (int cx = cx0 - reach; cx <= cx0 + reach; cx++) {
            for (int cz = cz0 - reach; cz <= cz0 + reach; cz++) {
                // sweepChunkRadius over-covers for a radius scan -- it walks one chunk further than
                // the circle reaches, because the origin sits at an arbitrary offset inside its own
                // chunk. Drawing that extra ring would show a frontier the scan does not have.
                if (!area.coversChunk(cx, cz)) {
                    continue;
                }
                if (mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
                    // The client holds this chunk, so there is nothing to say about it.
                    continue;
                }
                AABB box = new AABB(
                        cx * 16.0, y - CHUNK_FRAME_BELOW, cz * 16.0,
                        cx * 16.0 + 16.0, y + CHUNK_FRAME_ABOVE, cz * 16.0 + 16.0);
                Gizmos.cuboid(box, GizmoStyle.stroke(HoardkeeperMod.config().colorChunkMissing))
                        .setAlwaysOnTop();
            }
        }
    }

    /**
     * Candidates worth a box this frame: every pending/failed one, plus scanned ones only when
     * {@code showScannedBoxes} is on (it defaults off — a fully scanned base can mean 5 000 green
     * boxes, which is an fps problem, not a feature).
     *
     * <p>Picks the nearest {@code maxGizmos} without sorting the whole (potentially huge) eligible
     * set: a bounded max-heap keyed on squared distance keeps only the {@code maxGizmos} closest
     * candidates seen so far, evicting its current farthest whenever a closer one turns up. That is
     * {@code O(n log maxGizmos)} instead of the {@code O(n log n)} a full sort would cost — with
     * {@code maxGizmos} capped at a few hundred by default and {@code n} potentially in the
     * thousands, the difference is the whole point. The kept set is sorted once at the very end
     * (cheap, since its size is bounded by {@code maxGizmos}) purely so the draw order is stable
     * nearest-first and doesn't flicker between ticks.
     */
    private static List<ContainerCandidate> candidatesToDraw(ScanSession session, HoardkeeperConfig config,
                                                               Vec3 eye) {
        int cap = config.maxGizmos;
        if (cap <= 0) {
            return List.of();
        }

        // Ordered so peek()/poll() return the FARTHEST of the kept candidates — the one to evict
        // the moment something closer is found.
        PriorityQueue<Scored> kept = new PriorityQueue<>(cap,
                Comparator.comparingDouble((Scored s) -> s.distSq()).reversed());

        for (ContainerCandidate candidate : session.candidates) {
            if (candidate.status == ContainerStatus.SCANNED && !config.showScannedBoxes) {
                continue;
            }
            double distSq = distanceSquared(candidate, eye);
            if (kept.size() < cap) {
                kept.offer(new Scored(candidate, distSq));
            } else if (distSq < kept.peek().distSq()) {
                kept.poll();
                kept.offer(new Scored(candidate, distSq));
            }
        }

        List<Scored> sorted = new ArrayList<>(kept);
        sorted.sort(Comparator.comparingDouble(s -> s.distSq()));

        List<ContainerCandidate> result = new ArrayList<>(sorted.size());
        for (Scored s : sorted) {
            result.add(s.candidate);
        }
        return result;
    }

    /** A candidate with its squared distance from the eye, precomputed once. */
    private record Scored(ContainerCandidate candidate, double distSq) {
    }

    private static double distanceSquared(ContainerCandidate candidate, Vec3 eye) {
        double dx = candidate.pos[0] + 0.5 - eye.x;
        double dy = candidate.pos[1] + 0.5 - eye.y;
        double dz = candidate.pos[2] + 0.5 - eye.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void drawCandidate(ContainerCandidate candidate, HoardkeeperConfig config) {
        int colour = switch (candidate.status) {
            case PENDING -> config.colorPending;
            case SCANNED -> config.colorScanned;
            case FAILED -> config.colorFailed;
        };
        GizmoStyle style = GizmoStyle.stroke(colour);

        // Both halves of a double chest get a box, or half of every double chest looks unhighlighted.
        drawBox(candidate.pos, style);
        if (candidate.secondaryPos != null) {
            drawBox(candidate.secondaryPos, style);
        }

        if (candidate.status == ContainerStatus.FAILED && candidate.failReason != null) {
            BlockPos pos = new BlockPos(candidate.pos[0], candidate.pos[1], candidate.pos[2]);
            Gizmos.billboardTextOverBlock(candidate.failReason.name(), pos, 0, config.colorFailed, 0.6f)
                    .setAlwaysOnTop();
        }
    }

    private static void drawBox(int[] pos, GizmoStyle style) {
        Gizmos.cuboid(new BlockPos(pos[0], pos[1], pos[2]), style).setAlwaysOnTop();
    }
}
