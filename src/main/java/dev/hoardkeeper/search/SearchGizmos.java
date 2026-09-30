package dev.hoardkeeper.search;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;

import java.util.List;

/**
 * Draws the item search's world overlay: a filled, outlined box on every container still holding
 * what was asked for, with how much of it that container holds floating above.
 *
 * <p><b>Called from {@code ClientTickEvents.END_CLIENT_TICK}</b>, for the same reason and with the
 * same guarantees as {@code render.ScanGizmos} — see that class for why a tick hook, and not a
 * render event, is the collector scope whose gizmos actually reach the frame.
 *
 * <p>Filled rather than stroked, unlike the scan's own boxes. The scan draws hundreds of outlines
 * at once and an outline is what keeps that readable; a search lights a handful of specific
 * containers that the player is trying to spot across a room, and a translucent fill is what makes
 * one visible from the other end of a storage hall. The count over the block is the other half of
 * the answer: with several chests lit, "which one has the 900?" is the next question, and it is one
 * the player would otherwise have to open all of them to answer.
 *
 * <p>No cap and no distance culling here: {@link SearchHighlight} already kept only the nearest
 * {@code searchMaxHighlights} containers at the moment of the search, so what is lit is bounded
 * before it ever reaches this class — and what is lit is exactly what the action bar counts.
 */
public final class SearchGizmos {

    /** How far above the block the count floats, in the units {@code billboardTextOverBlock} takes. */
    private static final int LABEL_OFFSET = 0;
    private static final float LABEL_SCALE = 0.7f;
    /** Stroke width, in the units {@code GizmoStyle} takes. Heavier than the scan's default. */
    private static final float STROKE_WIDTH = 2.0f;

    private SearchGizmos() {
    }

    /** Emits this tick's search gizmos, or does nothing when no highlight is lit. */
    public static void emit(Minecraft mc) {
        HoardkeeperConfig config = HoardkeeperMod.config();
        if (!config.gizmosEnabled || mc.level == null) {
            return;
        }
        List<ContainerHit> lit = SearchService.get().lit();
        if (lit.isEmpty()) {
            return;
        }

        GizmoStyle style = GizmoStyle.strokeAndFill(config.colorSearch, STROKE_WIDTH,
                config.colorSearchFill);
        for (ContainerHit hit : lit) {
            // Both halves of a double chest get a box, or half of every double chest looks unlit —
            // and the player would walk to the dark half and find it is the one they were sent to.
            drawBox(hit.pos(), style);
            if (hit.secondaryPos() != null) {
                drawBox(hit.secondaryPos(), style);
            }
            Gizmos.billboardTextOverBlock(SearchText.billboard(hit.count()),
                            blockPos(hit.pos()), LABEL_OFFSET, config.colorSearch, LABEL_SCALE)
                    .setAlwaysOnTop();
        }
    }

    private static void drawBox(int[] pos, GizmoStyle style) {
        Gizmos.cuboid(blockPos(pos), style).setAlwaysOnTop();
    }

    private static BlockPos blockPos(int[] pos) {
        return new BlockPos(pos[0], pos[1], pos[2]);
    }
}
