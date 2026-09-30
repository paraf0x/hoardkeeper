package dev.hoardkeeper.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanChatRealmChangeTest {

    @Test
    void saysTheScanStoppedAndWhy() {
        // "The scan stopped" without "because the server moved you" reads like a bug in the mod.
        String line = ScanText.realmChangedText();
        assertTrue(line.toLowerCase().contains("stopped"), line);
        assertTrue(line.toLowerCase().contains("server"), line);
    }

    @Test
    void promisesTheScannedContainersAreKept() {
        // The player just lost a scan in progress. The one thing they need to know immediately is
        // that the containers already read are still on disk and still uploadable.
        assertTrue(ScanText.realmChangedText().toLowerCase().contains("saved"));
    }

    @Test
    void doesNotOfferToResumeHere() {
        // Resuming is exactly what must not happen on the new realm -- see RealmMatch.
        assertFalse(ScanText.realmChangedText().toLowerCase().contains("resume"));
    }
}
