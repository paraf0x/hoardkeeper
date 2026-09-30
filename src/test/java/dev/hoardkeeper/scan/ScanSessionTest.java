package dev.hoardkeeper.scan;

import dev.hoardkeeper.model.CandidateState;
import dev.hoardkeeper.model.SessionSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ScanSession#toSnapshot}, and specifically the one conversion in it that nothing
 * else can catch: {@code observedAt} is epoch <em>milliseconds</em> on {@link ContainerCandidate}
 * and an ISO-8601 instant string in the snapshot.
 *
 * <p>Worth its own test because getting the unit wrong is both easy and invisible. Reading those
 * millis as seconds still produces a valid, well-formed instant — one roughly 54,000 years in the
 * future — which then travels intact through {@code session.json}, through
 * {@code PayloadBuilder.parseSeenAt} (which parses it happily) and out to the server, where the
 * ageing rule compares it against everything else and concludes it is the freshest observation
 * that has ever existed. Every {@code unread} and {@code gone} row would then permanently overwrite
 * live scans of the same container, and nothing anywhere would report an error. Only the exact
 * string is evidence.
 */
class ScanSessionTest {

    /** 2026-09-02T14:31:07Z, chosen with nonzero seconds so a truncation could not pass unseen. */
    private static final long OBSERVED_MILLIS = 1_788_359_467_000L;

    private static ScanSession session() {
        return new ScanSession("2026-09-02_14-30-00", "mc.example.com", "minecraft:overworld",
                new int[]{0, 64, 0}, ScanArea.ofRadius(0, 0, 4));
    }

    private static ContainerCandidate candidate(long observedAtMillis, ContainerStatus status) {
        ContainerCandidate c = new ContainerCandidate(new int[]{1, 2, 3}, null, ContainerKind.BARREL);
        c.status = status;
        c.observedAt = observedAtMillis;
        return c;
    }

    @Test
    void observedAtMillisBecomeTheMatchingIsoInstant() {
        ScanSession session = session();
        session.candidates.add(candidate(OBSERVED_MILLIS, ContainerStatus.SCANNED));

        SessionSnapshot snapshot = session.toSnapshot("running");

        // The literal string, not a round-trip through Instant: a round-trip would apply the same
        // unit assumption on both sides and agree with itself no matter which unit was wrong.
        assertEquals("2026-09-02T14:31:07Z", snapshot.containers.get(0).observedAt);
    }

    @Test
    void aCandidateNeverObservedHasNoTimestampRatherThanTheEpoch() {
        ScanSession session = session();
        session.candidates.add(candidate(0L, ContainerStatus.PENDING));

        SessionSnapshot snapshot = session.toSnapshot("running");

        // null, not "1970-01-01T00:00:00Z": PayloadBuilder skips a container it cannot date, but
        // would happily send a 1970 timestamp, which the server's ageing rule silently discards.
        assertNull(snapshot.containers.get(0).observedAt);
    }

    @Test
    void subSecondMillisAreCarriedIntoTheIsoInstant() {
        ScanSession session = session();
        session.candidates.add(candidate(OBSERVED_MILLIS + 250, ContainerStatus.FAILED));

        SessionSnapshot snapshot = session.toSnapshot("running");

        assertEquals("2026-09-02T14:31:07.250Z", snapshot.containers.get(0).observedAt);
    }

    @Test
    void statusCountsAndFieldsCarryThroughToTheSnapshot() {
        ScanSession session = session();
        ContainerCandidate scanned = candidate(OBSERVED_MILLIS, ContainerStatus.SCANNED);
        ContainerCandidate failed = candidate(OBSERVED_MILLIS, ContainerStatus.FAILED);
        failed.failReason = FailReason.GONE;
        failed.attempts = 2;
        session.candidates.add(scanned);
        session.candidates.add(failed);
        session.candidates.add(candidate(0L, ContainerStatus.PENDING));

        SessionSnapshot snapshot = session.toSnapshot("finished");

        assertEquals(3, snapshot.stats.known);
        assertEquals(1, snapshot.stats.scanned);
        assertEquals(1, snapshot.stats.failed);
        assertEquals(1, snapshot.stats.pending);
        assertEquals("finished", snapshot.state);

        CandidateState goneState = snapshot.containers.get(1);
        assertEquals("FAILED", goneState.status);
        assertEquals("GONE", goneState.failReason);
        assertEquals(2, goneState.attempts);
        assertEquals("barrel", goneState.kind);
    }

    @Test
    void theSnapshotKeepsTheRealmTheScanStartedOn() {
        // The realm is stamped at scan time and travels with the session, because `/hoard
        // upload` can run days later from a different backend -- resolving it at upload time would
        // file a survival base under whatever realm the player happens to be standing on.
        ScanSession session = new ScanSession("s1", "play.example.com", "minecraft:overworld",
                new int[]{0, 64, 0}, ScanArea.ofRadius(0, 0, 16), "seed:1d333b8d147874e9");

        assertEquals("seed:1d333b8d147874e9", session.toSnapshot("running").realm);
    }

    @Test
    void aScanIsAStorageScanUnlessItIsToldOtherwise() {
        // Every session written before this feature existed was one, and reads back as one.
        assertFalse(session().isSiteScan());
        assertEquals("storage", session().toSnapshot("running").purpose);
    }

    @Test
    void aSiteScanSaysSoInItsSnapshotSoAResumeDoesNotUploadIt() {
        // The purpose is what decides whether a finished scan files its chests into the shared
        // pool. Losing it across a restart would upload a build site's chests as stock.
        ScanSession session = session();
        session.purpose = "site";

        assertTrue(session.isSiteScan());
        assertEquals("site", session.toSnapshot("running").purpose);
    }

    @Test
    void aSnapshotFromBeforeThePurposeExistedIsAStorageScan() {
        SessionSnapshot old = new com.google.gson.Gson().fromJson(
                "{\"sessionId\":\"s1\",\"state\":\"finished\"}", SessionSnapshot.class);
        assertEquals("storage", old.purpose);
    }
}
