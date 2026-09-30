package dev.hoardkeeper.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanChatRetryTest {

    @Test
    void saysTheScanIsOver() {
        // The player typed retry-failed after the scan had already ended. Without this sentence the
        // command looked like it worked: it printed "N containers queued again" and did nothing.
        String line = ScanText.retryNotRunningText().toLowerCase();
        assertTrue(line.contains("finished") || line.contains("no scan is running"), line);
    }

    @Test
    void namesTheCommandThatActuallyRetries() {
        // A refusal that does not say what to do instead is half a message. A finished scan cannot
        // be resumed, so the only way to retry those containers is a new scan.
        assertTrue(ScanText.retryNotRunningText().contains("/hoard start"),
                ScanText.retryNotRunningText());
    }

    @Test
    void doesNotOfferToResume() {
        // ScanController.findResumable refuses a snapshot whose state is FINISHED, so pointing the
        // player at /hoard resume here would be the second lie in a row.
        assertFalse(ScanText.retryNotRunningText().toLowerCase().contains("resume"),
                ScanText.retryNotRunningText());
    }
}
