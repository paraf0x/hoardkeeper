package dev.hoardkeeper.store;

import com.google.gson.Gson;
import dev.hoardkeeper.model.SessionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLookupTest {

    private static final Gson GSON = new Gson();

    private static final String DIMENSION = "minecraft:overworld";
    private static final String REALM = "seed:abc123";
    private static final Instant CUTOFF = Instant.parse("2026-09-08T10:00:00Z");

    private static Path writeSession(Path serverDir, String dirName, SessionSnapshot snapshot) throws IOException {
        Path dir = serverDir.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("session.json"), GSON.toJson(snapshot));
        return dir;
    }

    private static SessionSnapshot snapshot(String state, String dimension, String realm, String updatedAt,
                                             Integer pending) {
        SessionSnapshot s = new SessionSnapshot();
        s.state = state;
        s.dimension = dimension;
        s.realm = realm;
        s.updatedAt = updatedAt;
        if (pending != null) {
            s.stats = new SessionSnapshot.Stats();
            s.stats.pending = pending;
        }
        return s;
    }

    private static SessionSnapshot resumableCandidate() {
        return snapshot("INTERRUPTED", DIMENSION, REALM, "2026-09-08T10:05:00Z", 5);
    }

    // =========================================================================
    // newest
    // =========================================================================

    @Test
    void newestPicksTheNewestAcceptedSessionNotTheNewestOnDisk(@TempDir Path dir) throws IOException {
        // Resume and export must land on the session actually worth continuing/exporting, not merely
        // the one most recently touched. If the newest-on-disk session won regardless of the filter, a
        // rejected session (here, the wrong dimension) would shadow a real resumable one and the
        // player would see no resume offer at all, even though one genuinely exists.
        Path serverDir = dir.resolve("server");
        Files.createDirectories(serverDir);
        writeSession(serverDir, "s1", snapshot("INTERRUPTED", "minecraft:overworld", null,
                "2026-09-08T09:00:00Z", 1));
        writeSession(serverDir, "s2", snapshot("INTERRUPTED", "minecraft:overworld", null,
                "2026-09-08T10:00:00Z", 1));
        writeSession(serverDir, "s3", snapshot("INTERRUPTED", "minecraft:the_nether", null,
                "2026-09-08T11:00:00Z", 1));

        Predicate<SessionSnapshot> overworldOnly = s -> "minecraft:overworld".equals(s.dimension);
        SessionLookup.Found found = SessionLookup.newest(serverDir, overworldOnly);

        assertNotNull(found);
        assertEquals(serverDir.resolve("s2"), found.dir());
        assertEquals("2026-09-08T10:00:00Z", found.snapshot().updatedAt);
    }

    @Test
    void newestSkipsADirectoryWithNoSessionJson(@TempDir Path dir) throws IOException {
        // measured/ and observed/ live beside session directories under the same serverDir and
        // deliberately carry no session.json, precisely so every enumerator here treats them as
        // nothing (see the measured-map design). If this ever picked one up, it would either crash on
        // a null snapshot or -- worse -- silently mistake the measured map's own files for a scan
        // session, corrupting them the next time something writes through this "session".
        Path serverDir = dir.resolve("server");
        Files.createDirectories(serverDir.resolve("measured"));
        writeSession(serverDir, "s1", snapshot("INTERRUPTED", "minecraft:overworld", null,
                "2026-09-08T09:00:00Z", 1));

        SessionLookup.Found found = SessionLookup.newest(serverDir, s -> true);

        assertNotNull(found);
        assertEquals(serverDir.resolve("s1"), found.dir());
    }

    @Test
    void newestOnAMissingServerDirIsNull(@TempDir Path dir) {
        // The first join to a server has no hoardkeeper/<slug>/ directory at all yet -- offerResume
        // runs unconditionally on every join, so this must answer "nothing" rather than throw and take
        // the join down with it.
        Path serverDir = dir.resolve("does-not-exist");
        assertNull(SessionLookup.newest(serverDir, s -> true));
    }

    @Test
    void newestWithNoSessionsIsNull(@TempDir Path dir) throws IOException {
        // A server visited before, but with every session already retention-cleaned, must read as
        // "nothing to resume" -- not an error, and not a crash on an empty listing.
        Path serverDir = dir.resolve("server");
        Files.createDirectories(serverDir);
        assertNull(SessionLookup.newest(serverDir, s -> true));
    }

    @Test
    void newestWithAnUnparseableUpdatedAtStillWinsAsTheOnlyCandidate(@TempDir Path dir) throws IOException {
        // Pinning current behaviour: parseInstant returns null for a garbage timestamp, and the
        // `best == null` short-circuit in newest's comparison lets the very first (and here, only)
        // candidate become "best" unconditionally, regardless of whether its date could be read at
        // all. A session.json hand-edited or written by a future schema with an unreadable updatedAt
        // must still resolve to a real, usable session, not silently vanish from resume/export.
        Path serverDir = dir.resolve("server");
        Files.createDirectories(serverDir);
        writeSession(serverDir, "s1", snapshot("INTERRUPTED", "minecraft:overworld", null,
                "not-a-timestamp", 1));

        SessionLookup.Found found = SessionLookup.newest(serverDir, s -> true);

        assertNotNull(found);
        assertEquals(serverDir.resolve("s1"), found.dir());
    }

    @Test
    void newestWithAnUnparseableUpdatedAtLosesToAnyDatedRivalRegardlessOfListingOrder(@TempDir Path dir)
            throws IOException {
        // Pinning current behaviour, the other half of the case above: isAfter(null, null) is false,
        // but isAfter(<a real instant>, null) is true, so once a dated candidate is compared against
        // the undated one, the dated one always wins -- independent of Files.list's unspecified
        // iteration order (verified in both directory-name orders here). A session whose recency
        // cannot be judged must never outrank one that can.
        Path serverDir = dir.resolve("server");
        Files.createDirectories(serverDir);
        writeSession(serverDir, "z-garbage", snapshot("INTERRUPTED", "minecraft:overworld", null,
                "not-a-timestamp", 1));
        writeSession(serverDir, "a-dated", snapshot("INTERRUPTED", "minecraft:overworld", null,
                "2026-09-08T09:00:00Z", 1));

        SessionLookup.Found found = SessionLookup.newest(serverDir, s -> true);

        assertNotNull(found);
        assertEquals(serverDir.resolve("a-dated"), found.dir());
    }

    // =========================================================================
    // resumable
    // =========================================================================

    @Test
    void resumableAcceptsAnInterruptedSessionWithPendingWorkTheRightDimensionAndARecentTimestamp() {
        // The baseline: everything offerResume/resume need to be true at once before a [Resume] button
        // ever appears. Every refusal test below flips exactly one of these five clauses.
        Predicate<SessionSnapshot> filter = SessionLookup.resumable(DIMENSION, REALM, CUTOFF);
        assertTrue(filter.test(resumableCandidate()));
    }

    @Test
    void resumableRefusesAFinishedSession() {
        // A finished session is a closed record, already exported or on its way to being uploaded.
        // Offering to resume it would reopen containers.jsonl in append mode and let a second scan
        // write duplicate rows into a report that is supposed to be done -- exactly the double-count
        // bug applyAlreadyWritten exists to prevent, reintroduced from the other direction.
        SessionSnapshot s = resumableCandidate();
        s.state = "FINISHED";

        assertFalse(SessionLookup.resumable(DIMENSION, REALM, CUTOFF).test(s));
    }

    @Test
    void resumableRefusesAMismatchedDimension() {
        // The player is standing in a different dimension than the stored session. Resuming across
        // dimensions would attach a nether sweep's containers to an overworld session's candidate list
        // and origin, corrupting both the resumed session and whatever report is later exported from it.
        SessionSnapshot s = resumableCandidate();
        s.dimension = "minecraft:the_nether";

        assertFalse(SessionLookup.resumable(DIMENSION, REALM, CUTOFF).test(s));
    }

    @Test
    void resumableRefusesANonMatchingRealm() {
        // Behind a proxy every backend shares one address and a minecraft:overworld dimension id, so
        // only the realm tells them apart (see RealmMatch). Without this check, stepping from one
        // backend to another would offer to resume -- and then scan into -- the backend just left,
        // uploading its containers under the wrong realm.
        SessionSnapshot s = resumableCandidate();
        s.realm = "seed:zzz999";

        assertFalse(SessionLookup.resumable(DIMENSION, REALM, CUTOFF).test(s));
    }

    @Test
    void resumableRefusesWhenThereIsNothingLeftToScan() {
        // Both stats == null (a snapshot from before the stats field existed) and pending == 0 (a
        // session that finished scanning everything it knew about but was never marked FINISHED) must
        // refuse -- resuming either would leave TargetSelector looking for a PENDING candidate that
        // does not exist, with nothing left for the resumed scan to ever do.
        SessionSnapshot noStats = resumableCandidate();
        noStats.stats = null;
        SessionSnapshot zeroPending = resumableCandidate();
        zeroPending.stats.pending = 0;

        Predicate<SessionSnapshot> filter = SessionLookup.resumable(DIMENSION, REALM, CUTOFF);
        assertFalse(filter.test(noStats));
        assertFalse(filter.test(zeroPending));
    }

    @Test
    void resumableRefusesASessionOlderThanTheCutoff() {
        // resumeMaxAgeMinutes exists so a session abandoned long ago is not silently continued -- the
        // world has likely moved on (chests placed, removed, or looted by someone else since), and its
        // candidate list can no longer be trusted to describe what is actually there.
        SessionSnapshot s = resumableCandidate();
        s.updatedAt = "2026-09-08T09:00:00Z";

        assertFalse(SessionLookup.resumable(DIMENSION, REALM, CUTOFF).test(s));
    }

    // =========================================================================
    // exportable
    // =========================================================================

    // The baseline the two refusal tests below flip: a snapshot honestly recorded for the
    // dimension the player is standing in must pass, or export()'s per-dimension pass could never
    // find a session to export, even one that just finished scanning right here.
    @Test
    void exportableAcceptsASnapshotForTheGivenDimension() {
        SessionSnapshot s = snapshot("FINISHED", "minecraft:overworld", null, null, null);
        assertTrue(SessionLookup.exportable("minecraft:overworld").test(s));
    }

    @Test
    void exportableRefusesADifferentDimension() {
        // export()'s per-dimension pass looks for "the newest session for the dimension I am standing
        // in now" -- a nether session must not surface here before ScanController's separate
        // newest-overall fallback runs, or a nether scan followed by an overworld export would export
        // the wrong dimension entirely.
        SessionSnapshot s = snapshot("FINISHED", "minecraft:the_end", null, null, null);
        assertFalse(SessionLookup.exportable("minecraft:overworld").test(s));
    }

    @Test
    void exportableRefusesASnapshotWithNoDimensionRecorded() {
        // Pinning current behaviour: String.equals(null) is false rather than throwing, so a snapshot
        // with no recorded dimension (predating the field, or hand-edited) is quietly excluded from
        // the per-dimension pass instead of crashing the export fallback with a NullPointerException.
        SessionSnapshot s = snapshot("FINISHED", null, null, null, null);
        assertFalse(SessionLookup.exportable("minecraft:overworld").test(s));
    }
}
