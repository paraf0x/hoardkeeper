package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This class decides when a scan ends itself and uploads without being asked, so every test here is
 * about it not doing that at the wrong moment.
 */
class IdleCountdownTest {

    private static final long THIRTY_SECONDS = 30_000;

    private static IdleCountdown started(long timeout) {
        IdleCountdown c = new IdleCountdown(timeout);
        c.reset(0);
        return c;
    }

    /** Feeds updates in tick-sized steps, which is how the real caller drives it. */
    private static void run(IdleCountdown c, long fromMillis, long toMillis, boolean paused) {
        for (long t = fromMillis + 50; t <= toMillis; t += 50) {
            c.update(t, paused);
        }
    }

    @Test
    void expiresAfterTheQuietPeriod() {
        IdleCountdown c = started(THIRTY_SECONDS);

        run(c, 0, 29_950, false);
        assertFalse(c.expired(), "still a moment left");

        run(c, 29_950, 30_000, false);
        assertTrue(c.expired());
    }

    @Test
    void aContainerRestartsTheWholeQuietPeriod() {
        IdleCountdown c = started(THIRTY_SECONDS);

        run(c, 0, 29_000, false);
        c.reset(29_000);

        assertEquals(30, c.remainingSeconds(), "back to the full thirty");
        run(c, 29_000, 58_000, false);
        assertFalse(c.expired(), "29 seconds after the reset, not 58 after the start");
    }

    @Test
    void pausedTimeIsNotIdleTime() {
        // The player opened a chest and is sorting it. The scanner is gated, not finished.
        IdleCountdown c = started(THIRTY_SECONDS);

        run(c, 0, 120_000, true);

        assertFalse(c.expired(), "two minutes in a GUI must not end the scan");
        assertEquals(30, c.remainingSeconds());
    }

    @Test
    void aPauseDoesNotGetChargedRetroactivelyWhenItEnds() {
        // The dangerous shape: absorb the pause but keep a stale clock reference, and the first
        // update after it deducts the entire pause at once.
        IdleCountdown c = started(THIRTY_SECONDS);

        run(c, 0, 300_000, true);
        c.update(300_050, false);

        assertFalse(c.expired());
        assertEquals(30, c.remainingSeconds(), "one tick after a five-minute pause costs one tick");
    }

    @Test
    void aStalledGameDoesNotCountAsIdleTime() {
        // A single update covering 60 seconds means the client froze, not that the player stood
        // still for a minute. Crediting it in full would end scans during exactly the chunk-loading
        // hitches a large base causes.
        IdleCountdown c = started(THIRTY_SECONDS);

        c.update(60_000, false);

        assertFalse(c.expired(), "the freeze is credited as at most one step, not as a minute");
        assertEquals(28, c.remainingSeconds());
    }

    @Test
    void aTimeoutOfZeroSwitchesTheFeatureOff() {
        IdleCountdown c = started(0);

        run(c, 0, 600_000, false);

        assertFalse(c.enabled());
        assertFalse(c.expired(), "no timeout means no scan ever ends itself");
    }

    @Test
    void anUnstartedCountdownNeverExpires() {
        IdleCountdown c = new IdleCountdown(THIRTY_SECONDS);

        run(c, 0, 600_000, false);

        assertFalse(c.expired());
    }

    @Test
    void stoppingHoldsItUntilTheNextReset() {
        IdleCountdown c = started(THIRTY_SECONDS);
        run(c, 0, 29_000, false);

        c.stop();
        run(c, 29_000, 600_000, false);

        assertFalse(c.expired(), "a stopped scan does not finish itself a second time");
    }

    @Test
    void aBackwardsClockDeductsNothing() {
        IdleCountdown c = started(THIRTY_SECONDS);

        c.update(-5_000, false);

        assertEquals(30, c.remainingSeconds());
    }

    @Test
    void theBarEmptiesFromFullToZero() {
        IdleCountdown c = started(THIRTY_SECONDS);

        assertEquals(1.0, c.fraction(), 0.001);
        run(c, 0, 15_000, false);
        assertEquals(0.5, c.fraction(), 0.01, "half the quiet period, half the bar");
        run(c, 15_000, 30_000, false);
        assertEquals(0.0, c.fraction(), 0.001);
    }

    @Test
    void secondsRoundUpSoTheBarNeverSitsOnZeroWhileRunning() {
        IdleCountdown c = started(THIRTY_SECONDS);

        run(c, 0, 29_600, false);

        assertTrue(c.remainingMillis() > 0);
        assertEquals(1, c.remainingSeconds(), "400 ms left reads as 1s, not 0s");
    }
}
