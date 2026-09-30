package dev.hoardkeeper.store;

import dev.hoardkeeper.model.CandidateState;
import dev.hoardkeeper.model.SessionSnapshot;
import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.ContainerKind;
import dev.hoardkeeper.scan.ContainerStatus;
import dev.hoardkeeper.scan.FailReason;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.scan.ScanSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSnapshotCodecTest {

    private static CandidateState candidateState(int[] pos, int[] secondaryPos, String kind, String status,
                                                  int attempts, String failReason) {
        CandidateState state = new CandidateState();
        state.pos = pos;
        state.secondaryPos = secondaryPos;
        state.kind = kind;
        state.status = status;
        state.attempts = attempts;
        state.failReason = failReason;
        return state;
    }

    private static SessionSnapshot snapshot() {
        SessionSnapshot s = new SessionSnapshot();
        s.sessionId = "2026-09-08_10-00-00";
        s.server = "mc.example.com";
        s.realm = "seed:1d333b8d147874e9";
        s.dimension = "minecraft:overworld";
        s.origin = new int[]{100, 64, -200};
        s.areaMode = "CHUNKS";
        s.chunkRadius = 2;
        s.purpose = "storage";
        return s;
    }

    // =========================================================================
    // toSession
    // =========================================================================

    @Test
    void toSessionCarriesTheSessionLevelFields() {
        // A resumed scan that lost its own identity would either merge into the wrong session
        // directory or scan the wrong area entirely -- these fields are what `resume` uses to decide
        // both.
        SessionSnapshot s = snapshot();
        s.containers = List.of();

        ScanSession session = SessionSnapshotCodec.toSession(s);

        assertEquals("2026-09-08_10-00-00", session.sessionId);
        assertEquals("mc.example.com", session.server);
        assertEquals("seed:1d333b8d147874e9", session.realm);
        assertEquals("minecraft:overworld", session.dimension);
        assertArrayEquals(new int[]{100, 64, -200}, session.origin);
        assertEquals(ScanArea.Mode.CHUNKS, session.area.mode());
        assertEquals(2, session.area.chunkRadius());
    }

    @Test
    void toSessionCarriesEveryCandidatesPositionKindStatusReasonAndAttempts() {
        // This is the only place a resumed scan learns what it already knows about each container --
        // get any one of these fields wrong and a resumed scan either re-opens a chest it already
        // read (duplicating its contents in containers.jsonl) or silently drops a real failure.
        SessionSnapshot s = snapshot();
        CandidateState pending = candidateState(new int[]{101, 64, -200}, null, "chest", "PENDING", 0, null);
        CandidateState scanned = candidateState(new int[]{110, 64, -205}, null, "barrel", "SCANNED", 1, null);
        CandidateState failed = candidateState(new int[]{120, 70, -210}, new int[]{121, 70, -210},
                "chest_double", "FAILED", 3, "NO_RESPONSE");
        s.containers = List.of(pending, scanned, failed);

        ScanSession session = SessionSnapshotCodec.toSession(s);

        assertEquals(3, session.candidates.size());

        ContainerCandidate c0 = session.candidates.get(0);
        assertArrayEquals(new int[]{101, 64, -200}, c0.pos);
        assertNull(c0.secondaryPos);
        assertEquals(ContainerKind.CHEST, c0.kind);
        assertEquals(ContainerStatus.PENDING, c0.status);
        assertNull(c0.failReason);
        assertEquals(0, c0.attempts);

        ContainerCandidate c1 = session.candidates.get(1);
        assertEquals(ContainerKind.BARREL, c1.kind);
        assertEquals(ContainerStatus.SCANNED, c1.status);
        assertEquals(1, c1.attempts);

        ContainerCandidate c2 = session.candidates.get(2);
        assertArrayEquals(new int[]{120, 70, -210}, c2.pos);
        assertArrayEquals(new int[]{121, 70, -210}, c2.secondaryPos);
        assertEquals(ContainerKind.CHEST_DOUBLE, c2.kind);
        assertEquals(ContainerStatus.FAILED, c2.status);
        assertEquals(FailReason.NO_RESPONSE, c2.failReason);
        assertEquals(3, c2.attempts);

        // Both halves map to the very same candidate instance -- exactly what stops a re-discovered
        // double chest from becoming a second candidate.
        assertSame(c2, session.byPackedPos.get(SessionSnapshotCodec.packed(new int[]{120, 70, -210})));
        assertSame(c2, session.byPackedPos.get(SessionSnapshotCodec.packed(new int[]{121, 70, -210})));
    }

    @Test
    void toSessionWithNoContainersListReturnsAnEmptySessionRatherThanThrowing() {
        // A session.json can be saved before the first sweep ever populates `containers`. Resume must
        // hand back an empty-but-usable session so the sweep that runs right after can fill it in --
        // a thrown NPE here would abort resume entirely and strand the player on a fresh scan.
        SessionSnapshot s = snapshot();
        s.containers = null;

        ScanSession session = SessionSnapshotCodec.toSession(s);

        assertTrue(session.candidates.isEmpty());
    }

    @Test
    void toSessionOnASnapshotMissingOptionalFieldsDoesNotThrowAndUsesTheGamesDefaults() {
        // A snapshot from before the realm field existed (null realm), a session explicitly stripped
        // to its storage default (null purpose), and a candidate row torn or foreign enough that its
        // status/reason don't parse. None of this may crash resume -- it is exactly the "resume index
        // is an optimisation, a corrupt one must not take the client with it" rule the parsing helpers
        // exist for.
        SessionSnapshot s = new SessionSnapshot();
        s.sessionId = "sid";
        s.server = "srv";
        s.dimension = "minecraft:overworld";
        s.origin = new int[]{0, 64, 0};
        s.realm = null;
        s.purpose = null;
        // s.stats is left null, as `new SessionSnapshot()` defaults it -- toSession never reads stats.
        s.containers = List.of(candidateState(new int[]{1, 2, 3}, null, "chest", null, 0, null));

        ScanSession session = SessionSnapshotCodec.toSession(s);

        assertNull(session.realm);
        assertEquals(ScanSession.PURPOSE_STORAGE, session.purpose,
                "a null purpose must read back as a storage scan, or a resumed site scan would upload "
                        + "itself on finish");
        assertEquals(1, session.candidates.size());
        ContainerCandidate c = session.candidates.get(0);
        assertEquals(ContainerStatus.PENDING, c.status,
                "an unreadable status must become PENDING -- an extra scan costs a packet, a wrong skip "
                        + "costs data");
        assertNull(c.failReason);
    }

    // =========================================================================
    // parseKind
    // =========================================================================

    @Test
    void parseKindReturnsNullForAnUnknownValue() {
        // Consequence in toSession: kind == null makes the whole candidate get dropped from the
        // resumed session (never re-added, never scanned again) rather than guessed at.
        assertNull(SessionSnapshotCodec.parseKind("mystery_kind"));
    }

    @Test
    void parseKindReturnsNullForNull() {
        assertNull(SessionSnapshotCodec.parseKind(null));
    }

    // =========================================================================
    // parseStatus
    // =========================================================================

    @Test
    void parseStatusDefaultsToPendingForAnUnknownValue() {
        // Unlike an unknown kind, an unknown status does not drop the candidate -- it comes back as
        // PENDING and gets scanned again. An extra scan costs one packet; silently trusting a status
        // this build has never heard of could instead skip a container that was never really scanned.
        assertEquals(ContainerStatus.PENDING, SessionSnapshotCodec.parseStatus("MYSTERY"));
    }

    @Test
    void parseStatusDefaultsToPendingForNull() {
        assertEquals(ContainerStatus.PENDING, SessionSnapshotCodec.parseStatus(null));
    }

    // =========================================================================
    // parseReason
    // =========================================================================

    @Test
    void parseReasonReturnsNullForAnUnknownValue() {
        // Consequence in ScanController.failuresOf/skippedOf: a null reason is never treated as a
        // local skip (`reason != null && reason.isLocalSkip()`), so a fail reason this build does not
        // recognise is reported as a real failure rather than silently filed as a skip.
        assertNull(SessionSnapshotCodec.parseReason("MYSTERY"));
    }

    @Test
    void parseReasonReturnsNullForNull() {
        assertNull(SessionSnapshotCodec.parseReason(null));
    }

    // =========================================================================
    // parseInstant
    // =========================================================================

    @Test
    void parseInstantParsesAValidIsoInstant() {
        assertEquals(Instant.parse("2026-09-08T10:00:00Z"),
                SessionSnapshotCodec.parseInstant("2026-09-08T10:00:00Z"));
    }

    @Test
    void parseInstantReturnsNullForNull() {
        assertNull(SessionSnapshotCodec.parseInstant(null));
    }

    @Test
    void parseInstantReturnsNullForGarbageRatherThanThrowing() {
        // Consequence: SessionLookup.resumable/SessionLookup.newest feed this straight into isAfter,
        // whose null handling then treats the session as having no known update time at all --
        // garbage in session.json makes a session look unrankable, not a crash during resume.
        assertNull(SessionSnapshotCodec.parseInstant("not-a-timestamp"));
    }

    // =========================================================================
    // isAfter
    // =========================================================================

    @Test
    void isAfterIsFalseWhenTheFirstInstantIsNull() {
        assertFalse(SessionSnapshotCodec.isAfter(null, Instant.parse("2026-09-08T10:00:00Z")));
    }

    @Test
    void isAfterIsTrueWhenTheSecondInstantIsNullAndTheFirstIsKnown() {
        // Consequence in SessionLookup.newest: the first session found with any known updatedAt beats
        // "nothing found yet" (bestUpdated == null), so resume always prefers a dated session over no
        // session rather than getting stuck comparing against a null baseline.
        assertTrue(SessionSnapshotCodec.isAfter(Instant.parse("2026-09-08T10:00:00Z"), null));
    }

    @Test
    void isAfterIsFalseWhenBothInstantsAreNull() {
        assertFalse(SessionSnapshotCodec.isAfter(null, null));
    }

    @Test
    void isAfterComparesTwoKnownInstantsStrictly() {
        // SessionLookup.newest keeps its current best on a tie rather than switching to a same-aged
        // rival -- if this were ">=" instead of a strict "isAfter", the last session listed on disk
        // (an arbitrary filesystem order) would win resume instead of the first one found.
        Instant earlier = Instant.parse("2026-09-08T10:00:00Z");
        Instant later = Instant.parse("2026-09-08T11:00:00Z");

        assertTrue(SessionSnapshotCodec.isAfter(later, earlier));
        assertFalse(SessionSnapshotCodec.isAfter(earlier, later));
        assertFalse(SessionSnapshotCodec.isAfter(earlier, earlier));
    }

    // =========================================================================
    // isPos
    // =========================================================================

    @Test
    void isPosIsFalseForNull() {
        assertFalse(SessionSnapshotCodec.isPos(null));
    }

    @Test
    void isPosIsFalseForAWrongLengthArray() {
        assertFalse(SessionSnapshotCodec.isPos(new int[]{1, 2}));
        assertFalse(SessionSnapshotCodec.isPos(new int[]{1, 2, 3, 4}));
    }

    @Test
    void isPosIsTrueForAValidThreeElementArray() {
        assertTrue(SessionSnapshotCodec.isPos(new int[]{1, 2, 3}));
    }

    // =========================================================================
    // packed
    // =========================================================================

    @Test
    void packedDistinguishesPositionsThatDifferOnlyInY() {
        // ScanSession.byPackedPos and WrittenPositions both key on this value; if two different
        // heights ever packed to the same long, a chest on a different floor would look like an
        // already-written duplicate and never get scanned.
        long lower = SessionSnapshotCodec.packed(new int[]{10, 64, -20});
        long upper = SessionSnapshotCodec.packed(new int[]{10, 65, -20});

        assertNotEquals(lower, upper);
    }

    @Test
    void packedSurvivesNegativeCoordinatesAndStaysDeterministic() {
        long negative = SessionSnapshotCodec.packed(new int[]{-3_000_000, -64, 2_999_999});
        long positive = SessionSnapshotCodec.packed(new int[]{3_000_000, 64, -2_999_999});

        assertNotEquals(negative, positive);
        // The same key must come back for the same position every time -- both byPackedPos and
        // WrittenPositions rely on that stability to recognise a container across separate calls.
        assertEquals(negative, SessionSnapshotCodec.packed(new int[]{-3_000_000, -64, 2_999_999}));
    }
}
