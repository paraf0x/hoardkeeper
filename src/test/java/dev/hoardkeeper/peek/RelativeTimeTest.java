package dev.hoardkeeper.peek;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RelativeTimeTest {

    private static final long NOW = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli();

    private static String isoSecondsBefore(long seconds) {
        return Instant.ofEpochMilli(NOW - seconds * 1000).toString();
    }

    @Test
    void anythingUnderAMinuteReadsAsJustNow() {
        assertEquals("just now", RelativeTime.since(isoSecondsBefore(0), NOW));
        assertEquals("just now", RelativeTime.since(isoSecondsBefore(1), NOW));
        assertEquals("just now", RelativeTime.since(isoSecondsBefore(59), NOW));
    }

    @Test
    void aMinuteOrMoreReadsInWholeMinutesUntilAnHour() {
        assertEquals("1m ago", RelativeTime.since(isoSecondsBefore(60), NOW));
        assertEquals("1m ago", RelativeTime.since(isoSecondsBefore(119), NOW));
        assertEquals("59m ago", RelativeTime.since(isoSecondsBefore(59 * 60), NOW));
    }

    @Test
    void anHourOrMoreReadsInWholeHoursUntilADay() {
        assertEquals("1h ago", RelativeTime.since(isoSecondsBefore(60 * 60), NOW));
        assertEquals("2h ago", RelativeTime.since(isoSecondsBefore(2 * 60 * 60), NOW));
        assertEquals("23h ago", RelativeTime.since(isoSecondsBefore(23 * 60 * 60), NOW));
    }

    @Test
    void aDayOrMoreReadsInWholeDays() {
        assertEquals("1d ago", RelativeTime.since(isoSecondsBefore(24 * 60 * 60), NOW));
        assertEquals("2d ago", RelativeTime.since(isoSecondsBefore(2 * 24 * 60 * 60), NOW));
    }

    @Test
    void aTimestampInTheFutureReadsAsJustNowNeverNegative() {
        // Clock skew between the server that wrote scannedAt and this client must never surface as
        // "-3s ago" -- that reads as a bug, not as a harmless disagreement about the current time.
        String futureIso = Instant.ofEpochMilli(NOW + 120_000).toString();

        assertEquals("just now", RelativeTime.since(futureIso, NOW));
    }

    @Test
    void aNullTimestampReturnsNullRatherThanAZeroAge() {
        // A caller must be able to tell "no age known" from "0s ago" -- an invented freshness is
        // worse than an absent one, so this returns null rather than a string that claims to know.
        assertNull(RelativeTime.since(null, NOW));
    }

    @Test
    void anUnparseableTimestampReturnsNull() {
        assertNull(RelativeTime.since("not-a-timestamp", NOW));
        assertNull(RelativeTime.since("", NOW));
    }
}
