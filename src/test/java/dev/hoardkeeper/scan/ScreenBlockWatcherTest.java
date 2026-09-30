package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenBlockWatcherTest {

    @Test
    void doesNotAnnounceBeforeTheThresholdIsReached() {
        // A player checking their own inventory for a couple of seconds mid-scan must see nothing.
        ScreenBlockWatcher w = new ScreenBlockWatcher(60);

        for (int tick = 0; tick < 60; tick++) {
            assertFalse(w.update(true, tick), "announced too early at tick " + tick);
        }
    }

    @Test
    void announcesExactlyOnceWhenTheThresholdIsReached() {
        ScreenBlockWatcher w = new ScreenBlockWatcher(60);

        for (int tick = 0; tick < 60; tick++) {
            w.update(true, tick);
        }

        assertTrue(w.update(true, 60), "should announce on the tick the threshold is reached");
        assertFalse(w.update(true, 61), "must not repeat for the same continuous block");
        assertFalse(w.update(true, 200), "still must not repeat, however long the block persists");
    }

    @Test
    void clearingTheBlockResetsBothTheClockAndTheAnnouncedFlag() {
        ScreenBlockWatcher w = new ScreenBlockWatcher(60);
        for (int tick = 0; tick < 61; tick++) {
            w.update(true, tick);
        }
        assertFalse(w.update(false, 61), "clearing never announces");

        // A second, later block must be timed from its own start, not the first block's.
        for (int tick = 200; tick < 260; tick++) {
            assertFalse(w.update(true, tick), "announced too early on the second block at tick " + tick);
        }
        assertTrue(w.update(true, 260), "second continuous block is reported again");
    }

    @Test
    void aBriefInterruptionRestartsTheClock() {
        // Regression: if the clock were not reset on every gap, two short blocks separated by one
        // clear tick could add up to the threshold and announce for something that was never
        // continuously blocked.
        ScreenBlockWatcher w = new ScreenBlockWatcher(10);
        for (int tick = 0; tick < 9; tick++) {
            w.update(true, tick);
        }
        w.update(false, 9);

        for (int tick = 10; tick < 20; tick++) {
            assertFalse(w.update(true, tick), "restarted block must not inherit the earlier progress");
        }
        assertTrue(w.update(true, 20));
    }

    @Test
    void zeroThresholdAnnouncesOnTheFirstBlockedTick() {
        ScreenBlockWatcher w = new ScreenBlockWatcher(0);

        assertTrue(w.update(true, 5));
        assertFalse(w.update(true, 6), "not repeated on the very next tick");
    }
}
