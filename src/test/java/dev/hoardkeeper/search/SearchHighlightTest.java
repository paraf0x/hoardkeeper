package dev.hoardkeeper.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchHighlightTest {

    private static final double[] ORIGIN = {0, 0, 0};

    @Test
    void keepsTheNearestHitsWhenThereAreMoreThanTheCap() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond", List.of(
                hit(100, 0, 0, 1),
                hit(2, 0, 0, 1),
                hit(50, 0, 0, 1)), ORIGIN, 2, 0L, 30);

        assertEquals(2, highlight.remaining().size());
        assertEquals(2, highlight.remaining().get(0).pos()[0]);
        assertEquals(50, highlight.remaining().get(1).pos()[0]);
        assertEquals(3, highlight.totalMatches());
        assertTrue(highlight.capped());
    }

    @Test
    void isNotCappedWhenEveryHitFits() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond",
                List.of(hit(1, 0, 0, 5)), ORIGIN, 128, 0L, 30);

        assertFalse(highlight.capped());
        assertEquals(1, highlight.totalMatches());
        assertEquals(5, highlight.itemsRemaining());
    }

    @Test
    void openingAHighlightedContainerRemovesItAndOnlyIt() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond", List.of(
                hit(1, 64, 1, 10),
                hit(2, 64, 2, 20)), ORIGIN, 128, 0L, 30);

        assertTrue(highlight.dismiss(1, 64, 1, 1000L));
        assertEquals(1, highlight.remaining().size());
        assertEquals(20, highlight.itemsRemaining());
        // A second open of the same block changes nothing — it is already gone.
        assertFalse(highlight.dismiss(1, 64, 1, 1000L));
    }

    @Test
    void openingEitherHalfOfADoubleChestRemovesTheWholeHit() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond",
                List.of(doubleHit(1, 64, 1, 2, 64, 1, 10)), ORIGIN, 128, 0L, 30);

        assertTrue(highlight.dismiss(2, 64, 1, 500L));
        assertTrue(highlight.isEmpty());
    }

    @Test
    void openingAContainerThatIsNotHighlightedIsIgnored() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond",
                List.of(hit(1, 64, 1, 10)), ORIGIN, 128, 0L, 30);

        assertFalse(highlight.dismiss(9, 9, 9, 500L));
        assertEquals(1, highlight.remaining().size());
    }

    @Test
    void countsDownAndExpiresAfterTheConfiguredWindow() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond",
                List.of(hit(1, 64, 1, 10)), ORIGIN, 128, 1_000L, 30);

        assertEquals(30, highlight.secondsLeft(1_000L));
        assertEquals(29, highlight.secondsLeft(2_000L));
        assertFalse(highlight.expired(30_999L));
        assertTrue(highlight.expired(31_000L));
        assertEquals(0, highlight.secondsLeft(31_000L));
    }

    @Test
    void secondsLeftRoundsUpSoTheBarNeverShowsZeroWhileTimeRemains() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond",
                List.of(hit(1, 64, 1, 10)), ORIGIN, 128, 0L, 30);

        assertEquals(1, highlight.secondsLeft(29_999L));
        assertEquals(0, highlight.secondsLeft(30_000L));
    }

    @Test
    void openingOneOfSeveralContainersRestartsTheCountdown() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond", List.of(
                hit(1, 64, 1, 10),
                hit(2, 64, 2, 20)), ORIGIN, 128, 0L, 30);

        highlight.dismiss(1, 64, 1, 25_000L);

        // Without the refresh the highlight would die 5 seconds later, mid-walk to the second chest.
        assertFalse(highlight.expired(40_000L));
        assertEquals(30, highlight.secondsLeft(25_000L));
    }

    @Test
    void cappedStaysAnswerAboutTheCapEvenAfterContainersAreOpened() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond", List.of(
                hit(1, 64, 1, 10),
                hit(2, 64, 2, 20)), ORIGIN, 128, 0L, 30);

        highlight.dismiss(1, 64, 1, 100L);

        assertEquals(2, highlight.lit());
        assertFalse(highlight.capped());
    }

    @Test
    void removeIfPutsOutTheContainersThatAreGoneAndReportsHowMany() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond", List.of(
                hit(1, 64, 1, 10),
                hit(2, 64, 2, 20),
                hit(3, 64, 3, 30)), ORIGIN, 128, 0L, 30);

        int removed = highlight.removeIf(h -> h.pos()[0] != 2);

        assertEquals(2, removed);
        assertEquals(1, highlight.remaining().size());
        assertEquals(20, highlight.itemsRemaining());
    }

    @Test
    void aVanishedContainerDoesNotRestartTheCountdown() {
        SearchHighlight highlight = SearchHighlight.of("minecraft:diamond", List.of(
                hit(1, 64, 1, 10),
                hit(2, 64, 2, 20)), ORIGIN, 128, 0L, 30);

        // Somebody broke a chest at t=25s. That is the world changing, not the player using the
        // highlight, so unlike dismiss() it must not buy another thirty seconds.
        highlight.removeIf(h -> h.pos()[0] == 1);

        assertTrue(highlight.expired(30_000L));
    }

    @Test
    void aHighlightWithNoHitsIsEmptyFromTheStart() {
        assertTrue(SearchHighlight.of("minecraft:diamond", List.of(), ORIGIN, 128, 0L, 30).isEmpty());
    }

    private static ContainerHit hit(int x, int y, int z, long count) {
        return new ContainerHit(new int[]{x, y, z}, null, "chest", count);
    }

    private static ContainerHit doubleHit(int x, int y, int z, int x2, int y2, int z2, long count) {
        return new ContainerHit(new int[]{x, y, z}, new int[]{x2, y2, z2}, "chest_double", count);
    }
}
