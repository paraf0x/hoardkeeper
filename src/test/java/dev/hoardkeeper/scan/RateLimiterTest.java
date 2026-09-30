package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void isReadyBeforeTheFirstSendEvenAtTickZero() {
        // Regression: a sentinel of Integer.MIN_VALUE makes (tick - last) overflow negative
        // and wedges the gate shut forever. This is the single most expensive bug of the spike.
        RateLimiter r = new RateLimiter(4, 3, 40);

        assertTrue(r.ready(0));
        assertTrue(r.ready(1));
    }

    @Test
    void enforcesTheMinimumGapBetweenSends() {
        RateLimiter r = new RateLimiter(4, 3, 40);
        r.onSend(100);

        assertFalse(r.ready(101));
        assertFalse(r.ready(103));
        assertTrue(r.ready(104));
    }

    @Test
    void aGapOfZeroAllowsEveryTick() {
        RateLimiter r = new RateLimiter(0, 3, 40);
        r.onSend(50);

        assertTrue(r.ready(50));
    }

    @Test
    void backsOffOnlyAfterEnoughRemoteFailures() {
        RateLimiter r = new RateLimiter(4, 3, 40);
        r.onSend(0);
        r.onRemoteFailure();
        r.onRemoteFailure();

        assertTrue(r.ready(4), "two failures is below the threshold, normal pacing applies");

        r.onRemoteFailure();

        assertFalse(r.ready(4), "third failure starts backing off");
        assertTrue(r.ready(8));
    }

    @Test
    void backoffGrowsButIsCapped() {
        RateLimiter r = new RateLimiter(4, 1, 16);
        r.onSend(0);
        for (int i = 0; i < 10; i++) {
            r.onRemoteFailure();
        }

        assertFalse(r.ready(15));
        assertTrue(r.ready(16), "capped at maxBackoffTicks");
    }

    @Test
    void backoffStaysCappedWhenTheShiftDistanceWouldWrap() {
        // Regression: Java masks long shift distances to 6 bits, so `base << 64` yields `base`
        // again. With the defaults the shift reaches 64 at 66 consecutive failures, and the gap
        // collapsed from the 40-tick cap back to 4 ticks — the backoff defeating itself exactly
        // when the server was refusing hardest. An anticheat that swallows UseItemOn produces
        // three NO_RESPONSE failures per container, so this streak arrives after ~22 containers.
        RateLimiter r = new RateLimiter(4, 3, 40);
        r.onSend(0);
        for (int i = 0; i < 66; i++) {
            r.onRemoteFailure();
        }

        assertFalse(r.ready(39), "still capped at maxBackoffTicks, not back down to 4");
        assertTrue(r.ready(40));
    }

    @Test
    void backoffStaysCappedAcrossTheWholeWrapCycle() {
        // Every failure count from the first backoff well past two full 64-shift cycles must give
        // a gap of at least the cap — no count may ever be *less* strict than a smaller one.
        RateLimiter r = new RateLimiter(4, 3, 40);
        r.onSend(0);
        for (int i = 0; i < 200; i++) {
            r.onRemoteFailure();
            if (r.consecutiveFailures() >= 3 + 4) {   // past 4<<4 == 64 > cap, so the cap is in force
                assertFalse(r.ready(39), "gap shrank below the cap at " + r.consecutiveFailures()
                        + " consecutive failures");
            }
        }
    }

    @Test
    void successResetsTheFailureStreak() {
        RateLimiter r = new RateLimiter(4, 3, 40);
        r.onRemoteFailure();
        r.onRemoteFailure();
        r.onSuccess();

        assertEquals(0, r.consecutiveFailures());
    }
}
