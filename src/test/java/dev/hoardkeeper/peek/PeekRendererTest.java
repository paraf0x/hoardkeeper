package dev.hoardkeeper.peek;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PeekRenderer#clampToScreen}, the M-7 fix: {@code peekMaxItems} clamps to 54, and at 1080p
 * with GUI scale 3 a 54-item card draws about 40 lines above the top of the screen because
 * {@code render} had no floor on {@code y}. The numbers below mirror {@code PeekRenderer}'s own
 * private constants — {@code LINE_STEP_PX = 10}, {@code PADDING_PX = 3},
 * {@code GAP_ABOVE_CROSSHAIR_PX = 16} — rather than reaching them by reflection, so a test failure
 * here means the visible behaviour changed, not just an internal number.
 */
class PeekRendererTest {

    private static List<String> lines(int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add("line" + i);
        }
        return lines;
    }

    @Test
    void aCardThatAlreadyFitsIsReturnedUnchanged() {
        // guiHeight 1000 -> bottom = 484, room for 47 lines. Three lines is nowhere near that.
        List<String> input = List.of("title", "diamond  5", "1/27 slots");

        assertEquals(input, PeekRenderer.clampToScreen(input, 1000));
    }

    @Test
    void theExactCountThatJustFitsIsReturnedUnchanged() {
        // guiHeight 132 -> bottom = 50, maxLines = (50 - 6) / 10 = 4. Exactly 4 lines must not be
        // touched -- clamping only when the count is strictly more than what fits.
        List<String> input = lines(4);

        assertEquals(input, PeekRenderer.clampToScreen(input, 132));
    }

    @Test
    void aCardTallerThanTheScreenIsCutToWhatFitsPlusAMoreLine() {
        // Same 132px window (maxLines = 4): a 54-item-shaped card (56 lines) keeps the first three
        // -- title and the top of the list -- and folds the other 53 into one "+53 more" line,
        // rather than drawing any of them off the top of the screen.
        List<String> input = lines(56);

        List<String> result = PeekRenderer.clampToScreen(input, 132);

        assertEquals(4, result.size(), "the clamped card must itself fit within maxLines");
        assertEquals(List.of("line0", "line1", "line2"), result.subList(0, 3),
                "the top of the card -- what a short window can still show -- must survive untouched");
        assertEquals("+53 more", result.get(3));
    }

    @Test
    void aWindowTooShortForEvenOneLineDrawsNothing() {
        // guiHeight 32 -> bottom = 0, maxLines = 0: not even one line and its own "+N more" trailer
        // fit. Drawing nothing is the honest answer -- the same one an empty cachedLines produces.
        List<String> input = lines(10);

        assertEquals(List.of(), PeekRenderer.clampToScreen(input, 32));
    }

    @Test
    void anEmptyCardStaysEmptyRegardlessOfScreenSize() {
        assertEquals(List.of(), PeekRenderer.clampToScreen(List.of(), 32));
        assertEquals(List.of(), PeekRenderer.clampToScreen(List.of(), 1000));
    }

    @Test
    void theClampedCardsTopEdgeNeverGoesAboveScreenRowZero() {
        // The actual bug (M-7): reconstruct render()'s own y/height arithmetic over the clamped
        // result and assert the backdrop's top edge (y - PADDING_PX) is never negative, across a
        // spread of window heights including the 1080p/scale-3 case the review measured
        // (guiHeight ~= 360) with peekMaxItems at its documented maximum (54 items + title + slots
        // line = 56 lines).
        int lineStepPx = 10;
        int paddingPx = 3;
        int gapAboveCrosshairPx = 16;
        List<String> fiftySixLines = lines(56);

        for (int guiHeight : new int[]{32, 100, 132, 200, 360, 720, 2000}) {
            List<String> drawn = PeekRenderer.clampToScreen(fiftySixLines, guiHeight);
            if (drawn.isEmpty()) {
                continue;
            }
            int height = drawn.size() * lineStepPx + paddingPx;
            int bottom = guiHeight / 2 - gapAboveCrosshairPx;
            int y = bottom - height;
            assertTrue(y - paddingPx >= 0,
                    "guiHeight=" + guiHeight + " drew " + drawn.size()
                            + " lines with top edge at " + (y - paddingPx));
        }
    }
}
