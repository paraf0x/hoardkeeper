package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanCompletionTest {

    @Test
    void everythingScannedAndTheWholeAreaSweptMeansDone() {
        assertTrue(ScanCompletion.nothingLeftToScan(0, 9, 9));
    }

    @Test
    void aContainerStillWaitingIsNotDone() {
        // PENDING covers the one being opened right now and the ones awaiting a retry: the
        // selector picks nothing else, so it is the whole of "still to do".
        assertFalse(ScanCompletion.nothingLeftToScan(1, 9, 9));
    }

    @Test
    void chunksStillStreamingInAreNotDone() {
        // Two chunks of the area are missing, and a chest in either would be discovered the
        // moment they arrive. Finishing here would report a base as fully scanned that was not.
        assertFalse(ScanCompletion.nothingLeftToScan(0, 7, 9));
    }

    @Test
    void aScanThatHasNotSweptYetIsNotDone() {
        // Both counters are zero until the first discovery sweep runs. Without this guard a scan
        // would end on the tick it started, before anything had been looked for.
        assertFalse(ScanCompletion.nothingLeftToScan(0, 0, 0));
    }

    @Test
    void moreLoadedThanExpectedStillCounts() {
        // The counters come from the last sweep and are not a promise about each other; a
        // mismatch must not be the thing that keeps a finished scan running forever.
        assertTrue(ScanCompletion.nothingLeftToScan(0, 10, 9));
    }
}
