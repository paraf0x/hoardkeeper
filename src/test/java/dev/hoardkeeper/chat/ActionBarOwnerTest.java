package dev.hoardkeeper.chat;

import dev.hoardkeeper.chat.ActionBarOwner.Writer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionBarOwnerTest {

    @Test
    void nobodyWantsItAndNobodyGetsIt() {
        assertNull(ActionBarOwner.resolve(false, false, false, false));
    }

    @Test
    void theSearchBeatsEveryoneElse() {
        // Transient, explicitly asked for, and it carries a countdown the player is reading.
        assertEquals(Writer.SEARCH, ActionBarOwner.resolve(true, true, true, true));
    }

    @Test
    void theAutoFinishBarBeatsTheSiteAndTheProgressLine() {
        // The one signal that a scan is about to end by itself.
        assertEquals(Writer.AUTO_FINISH, ActionBarOwner.resolve(false, true, true, true));
    }

    @Test
    void theSiteBeatsTheProgressLine() {
        // Those numbers are on the HUD panel anyway; the gathering percentage is nowhere else.
        assertEquals(Writer.SITE, ActionBarOwner.resolve(false, false, true, true));
    }

    @Test
    void theProgressLineGetsItWhenNothingElseWantsIt() {
        assertEquals(Writer.SCAN_PROGRESS, ActionBarOwner.resolve(false, false, false, true));
    }

    @Test
    void aWriterThatDoesNotWantTheBarNeverWinsIt() {
        assertEquals(Writer.SITE, ActionBarOwner.resolve(false, false, true, false));
    }

    @Test
    void mayWriteAnswersForOneWriterWithoutItHavingToKnowTheOrder() {
        assertTrue(ActionBarOwner.mayWrite(Writer.SITE, false, false, true, true));
        assertFalse(ActionBarOwner.mayWrite(Writer.SCAN_PROGRESS, false, false, true, true));
        // Asking on behalf of a writer that is not asking for the bar is always no.
        assertFalse(ActionBarOwner.mayWrite(Writer.SITE, false, false, false, true));
    }
}
