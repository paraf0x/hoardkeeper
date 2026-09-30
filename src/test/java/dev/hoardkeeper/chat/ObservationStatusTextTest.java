package dev.hoardkeeper.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ScanText#observationStatusText}, the one line that makes passive observation
 * visible in {@code /hoard status} (design spec section 7). Pure string builder, no
 * Minecraft, exactly like {@link ScanChatRealmChangeTest} exercises {@code realmChangedText()}
 * directly.
 */
class ObservationStatusTextTest {

    @Test
    void observationStatusReadsAsOneLine() {
        assertEquals("Observed: 412 containers · 3 pending upload · last 27s ago",
                ScanText.observationStatusText(412, 3, 27));
    }

    @Test
    void nothingPendingSaysSoRatherThanShowingAZero() {
        assertEquals("Observed: 412 containers · all uploaded · last 27s ago",
                ScanText.observationStatusText(412, 0, 27));
    }

    @Test
    void anEmptyLogIsStillWorthALineSoTheFeatureIsVisiblyOn() {
        assertEquals("Observed: nothing yet", ScanText.observationStatusText(0, 0, -1));
    }

    /**
     * Task 8's join-delay fix stamps PassiveObserver.lastObservationMillis on every join with
     * passiveObserve on, purely to arm the upload debounce, not because anything was observed. A
     * negative secondsSinceLast says that no real observation has happened yet this session, and
     * the line must not claim one that did not happen: it drops the last-seen clause instead of
     * reporting "last 0s ago" right after joining a world where nothing has been looked at yet.
     */
    @Test
    void suppressesTheLastClauseWhenNothingWasObservedThisSession() {
        assertEquals("Observed: 412 containers · 3 pending upload",
                ScanText.observationStatusText(412, 3, -1));
    }

    @Test
    void suppressesTheLastClauseEvenWhenNothingIsPendingEither() {
        assertEquals("Observed: 412 containers · all uploaded",
                ScanText.observationStatusText(412, 0, -1));
    }
}
