package dev.hoardkeeper.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanTextToggleTest {

    @Test
    void peekLineSaysWhichStateItIsIn() {
        // A toggle whose two answers read the same is a toggle you have to try twice to understand.
        assertNotEquals(ScanText.peekToggledText(true), ScanText.peekToggledText(false));
        assertTrue(ScanText.peekToggledText(true).contains("on"), ScanText.peekToggledText(true));
        assertTrue(ScanText.peekToggledText(false).contains("off"), ScanText.peekToggledText(false));
    }

    @Test
    void peekLineNamesTheCommandThatUndoesIt() {
        // Turning something off is the moment you most need to know how to get it back.
        assertTrue(ScanText.peekToggledText(false).contains("/hoard peek on"),
                ScanText.peekToggledText(false));
        assertTrue(ScanText.peekToggledText(true).contains("/hoard peek off"),
                ScanText.peekToggledText(true));
    }

    @Test
    void reminderLineNamesItsOwnCommand() {
        // The two toggles share a formatter; this is the test that catches a copy-paste that left
        // the reminder telling the player to type the peek command.
        assertTrue(ScanText.reminderToggledText(false).contains("/hoard reminder on"),
                ScanText.reminderToggledText(false));
        assertTrue(ScanText.reminderToggledText(true).contains("/hoard reminder off"),
                ScanText.reminderToggledText(true));
    }

    @Test
    void helpListsBothToggles() {
        String help = String.join("\n", ScanText.help(2, 8, 32, 128, 45));
        assertTrue(help.contains("/hoard peek"), help);
        assertTrue(help.contains("/hoard reminder"), help);
    }
}
