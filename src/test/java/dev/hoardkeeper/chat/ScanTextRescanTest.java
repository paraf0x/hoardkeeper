package dev.hoardkeeper.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ScanText.rescanOffer}/{@code rescanLabel}, mirroring {@code resumeOffer}/{@code
 * resumeLabel} down to the split between statement and clickable label — see
 * {@code ScanChat.rescanOffer}, which is where the {@code ClickEvent.RunCommand} that split exists
 * for gets attached.
 */
class ScanTextRescanTest {

    @Test
    void rescanOfferNamesTheDays() {
        // The number of days is the entire content of the offer; a player reading "measured X days
        // ago" with the wrong X either underplays a genuinely stale storage or cries wolf about a
        // fresh one.
        String line = ScanText.rescanOffer(23);

        assertTrue(line.contains("23"), line);
    }

    @Test
    void rescanLabelIsBracketedRescan() {
        // The clickable label {@code ScanChat.rescanOffer} appends. Bracketed like every other
        // clickable label this mod prints ([Resume], [Open]), so it reads as a button rather than
        // part of the sentence.
        String label = ScanText.rescanLabel();

        assertTrue(label.contains("[Rescan]"), label);
    }

    @Test
    void rescanOfferReadsAsAnOfferNotAnInstruction() {
        // "This storage was measured N days ago" is a fact about the world; "Rescan this storage"
        // would be a command aimed at the player. The line by itself, before the clickable label is
        // appended, must be the former — the label is what carries the invitation to act, exactly as
        // resumeOffer's own text never says "resume" and leaves that word to resumeLabel().
        String line = ScanText.rescanOffer(23).toLowerCase();

        assertTrue(line.contains("measured") && line.contains("ago"), line);
        assertFalse(line.contains("rescan"), line);
    }

    @Test
    void rescanOfferDoesNotCarryItsOwnClickableLabel() {
        // Mirrors resumeOffer's shape exactly: the offer text and the clickable label are two
        // separate strings, joined only by ScanChat, so the label's ClickEvent can be attached to
        // it alone rather than to the whole line.
        String line = ScanText.rescanOffer(23);

        assertFalse(line.contains("[Rescan]"), line);
    }
}
