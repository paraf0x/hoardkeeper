package dev.hoardkeeper.store;

import dev.hoardkeeper.model.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionPruningTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-30T12:00:00Z");

    private static SessionSnapshot finished(String id, String updatedAt) {
        SessionSnapshot s = new SessionSnapshot();
        s.sessionId = id;
        s.state = "FINISHED";
        s.purpose = "storage";
        s.updatedAt = updatedAt;
        return s;
    }

    private static SessionSnapshot old() {
        return finished("a", "2026-09-30T10:00:00Z");
    }

    @Test
    void aFinishedStorageScanTheMapHoldsGoesOnceItIsOldEnough() {
        assertTrue(SessionPruning.prunable(old(), true, false, false, null, CUTOFF));
        assertTrue(SessionPruning.prunable(finished("a", "2026-09-30T12:00:00Z"), true, false, false, null, CUTOFF));
    }

    @Test
    void aRecentOneStaysSoTheLastScanCanStillBeExported() {
        assertFalse(SessionPruning.prunable(finished("a", "2026-09-30T12:00:01Z"),
                true, false, false, null, CUTOFF));
    }

    @Test
    void theMapMustHoldIt() {
        assertFalse(SessionPruning.prunable(old(), false, false, false, null, CUTOFF));
    }

    @Test
    void anAddOnClaimKeepsIt() {
        assertFalse(SessionPruning.prunable(old(), true, true, false, null, CUTOFF));
    }

    @Test
    void keepSessionsKeepsEverything() {
        assertFalse(SessionPruning.prunable(old(), true, false, true, null, CUTOFF));
    }

    @Test
    void anUnfinishedScanMayStillBeResumed() {
        SessionSnapshot s = old();
        s.state = "RUNNING";
        assertFalse(SessionPruning.prunable(s, true, false, false, null, CUTOFF));
    }

    @Test
    void aSiteScanIsTheAddOnsBusiness() {
        SessionSnapshot s = old();
        s.purpose = "site";
        assertFalse(SessionPruning.prunable(s, true, false, false, null, CUTOFF));
    }

    @Test
    void theSessionInMemoryStays() {
        assertFalse(SessionPruning.prunable(old(), true, false, false, "a", CUTOFF));
        assertTrue(SessionPruning.prunable(old(), true, false, false, "b", CUTOFF));
    }

    @Test
    void anUnreadableTimeOrNoSnapshotStays() {
        assertFalse(SessionPruning.prunable(finished("a", "garbage"), true, false, false, null, CUTOFF));
        assertFalse(SessionPruning.prunable(finished("a", null), true, false, false, null, CUTOFF));
        assertFalse(SessionPruning.prunable(null, true, false, false, null, CUTOFF));
    }
}
