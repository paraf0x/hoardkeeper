package dev.hoardkeeper.observe;

import dev.hoardkeeper.model.ScannedContainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationLogTest {

    private static ScannedContainer row(int x, String scannedAt) {
        ScannedContainer c = new ScannedContainer();
        c.pos = new int[]{x, 64, 0};
        c.scannedAt = scannedAt;
        return c;
    }

    @Test
    void pendingIsEverythingPastTheWatermark() {
        assertEquals(3, ObservationLog.pendingRows(10, 7));
        assertEquals(0, ObservationLog.pendingRows(10, 10));
    }

    @Test
    void aWatermarkPastTheEndIsClampedRatherThanNegative() {
        // A truncated file under a stale log.json must not produce a negative pending count.
        assertEquals(0, ObservationLog.pendingRows(3, 9));
    }

    @Test
    void compactsOnlyAtEitherEndOfTheWatermark() {
        assertTrue(ObservationLog.mayCompact(10, 10, false), "everything sent");
        assertTrue(ObservationLog.mayCompact(10, 0, false), "nothing sent");
        assertFalse(ObservationLog.mayCompact(10, 4, false), "rows pending under the watermark");
    }

    @Test
    void neverCompactsWhileAnUploadIsInFlight() {
        assertFalse(ObservationLog.mayCompact(10, 10, true));
    }

    @Test
    void compactionKeepsTheNewestRowPerPosition() {
        List<ScannedContainer> kept = ObservationLog.compact(List.of(
                row(1, "2026-09-05T10:00:00Z"),
                row(2, "2026-09-05T10:01:00Z"),
                row(1, "2026-09-05T10:02:00Z")), 100);
        assertEquals(2, kept.size());
        assertEquals("2026-09-05T10:02:00Z",
                kept.stream().filter(c -> c.pos[0] == 1).findFirst().orElseThrow().scannedAt);
    }

    @Test
    void compactionDropsTheOldestPastTheCap() {
        List<ScannedContainer> kept = ObservationLog.compact(List.of(
                row(1, "2026-09-05T10:00:00Z"),
                row(2, "2026-09-05T10:01:00Z"),
                row(3, "2026-09-05T10:02:00Z")), 2);
        assertEquals(2, kept.size());
        assertTrue(kept.stream().noneMatch(c -> c.pos[0] == 1), "the oldest goes first");
    }

    @Test
    void compactionIgnoresSecondaryPosForIdentity() {
        // Two rows that share a canonical pos but disagree on secondaryPos are still one position.
        ScannedContainer older = row(1, "2026-09-05T10:00:00Z");
        older.secondaryPos = new int[]{9, 64, 9};
        ScannedContainer newer = row(1, "2026-09-05T10:01:00Z");
        newer.secondaryPos = new int[]{2, 64, 2};

        List<ScannedContainer> kept = ObservationLog.compact(List.of(older, newer), 100);
        assertEquals(1, kept.size(), "secondaryPos must not split one position into two");
        assertEquals("2026-09-05T10:01:00Z", kept.get(0).scannedAt);
    }

    @Test
    void compactionDropsARowWithNoCanonicalPosition() {
        ScannedContainer noPos = new ScannedContainer();
        noPos.scannedAt = "2026-09-05T10:00:00Z";

        List<ScannedContainer> kept = ObservationLog.compact(List.of(
                noPos, row(1, "2026-09-05T10:01:00Z")), 100);
        assertEquals(1, kept.size());
        assertEquals(1, kept.get(0).pos[0]);
    }

    @Test
    void anUnparseableOrNullScannedAtNeverOutranksAParseableOne() {
        List<ScannedContainer> keptWhenGoodComesFirst = ObservationLog.compact(List.of(
                row(1, "2026-09-05T10:00:00Z"),
                row(1, "not-a-timestamp")), 100);
        assertEquals("2026-09-05T10:00:00Z", keptWhenGoodComesFirst.get(0).scannedAt,
                "an unparseable timestamp must not overwrite an earlier good one");

        List<ScannedContainer> keptWhenGoodComesSecond = ObservationLog.compact(List.of(
                row(2, null),
                row(2, "2026-09-05T10:00:00Z")), 100);
        assertEquals("2026-09-05T10:00:00Z", keptWhenGoodComesSecond.get(0).scannedAt,
                "a good timestamp must win over an earlier null one");
    }

    @Test
    void maxRowsCapEvictsTheLeastRecentlyTouchedPositionNotTheFirstObserved() {
        // Position 1 is re-observed last, so it must survive the cap; position 2 has not been
        // touched since and must be the one evicted — not position 1, despite appearing first.
        List<ScannedContainer> kept = ObservationLog.compact(List.of(
                row(1, "2026-09-05T10:00:00Z"),
                row(2, "2026-09-05T10:01:00Z"),
                row(3, "2026-09-05T10:02:00Z"),
                row(4, "2026-09-05T10:03:00Z"),
                row(1, "2026-09-05T10:04:00Z")), 3);
        assertEquals(3, kept.size());
        assertTrue(kept.stream().anyMatch(c -> c.pos[0] == 1), "position 1 was just re-observed");
        assertTrue(kept.stream().noneMatch(c -> c.pos[0] == 2), "position 2 has gone untouched since");
    }

    @Test
    void theWatermarkFollowsCompactionToWhicheverEndItWasAt() {
        assertEquals(4, ObservationLog.watermarkAfterCompaction(9, 4), "everything was sent");
        assertEquals(0, ObservationLog.watermarkAfterCompaction(0, 4), "nothing was sent");
    }

    // ---- secondsSinceLast: the status line's clock (spec section 7) ----

    @Test
    void secondsSinceLastCountsWholeSecondsFromTheLastObservation() {
        assertEquals(27, ObservationLog.secondsSinceLast(100_000L, 127_400L));
    }

    /**
     * The clock is 0 only when nothing was observed since this client started. PassiveObserver
     * stamps its *other* clock on every join, purely to arm the upload debounce; this one stays 0
     * until a row is actually appended, and the negative answer is what stops the status line from
     * reporting "last 0s ago" for a join that observed nothing.
     */
    @Test
    void neverObservedIsNegativeRatherThanZeroSecondsAgo() {
        assertEquals(-1, ObservationLog.secondsSinceLast(0L, 127_400L));
    }

    /**
     * A clock that jumped backwards between the observation and the status command must not read
     * as a negative age -- that value means "never observed" to the caller, and an observation
     * that did happen would be silently dropped from the line.
     */
    @Test
    void aClockThatWentBackwardsReadsAsJustNowNotAsNever() {
        assertEquals(0, ObservationLog.secondsSinceLast(200_000L, 100_000L));
    }
}
