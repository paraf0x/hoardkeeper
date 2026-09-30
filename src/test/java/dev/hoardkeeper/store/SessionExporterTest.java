package dev.hoardkeeper.store;

import com.google.gson.Gson;
import dev.hoardkeeper.model.CandidateState;
import dev.hoardkeeper.model.ScanReport;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.ScannedItem;
import dev.hoardkeeper.model.SessionSnapshot;
import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.ContainerKind;
import dev.hoardkeeper.scan.ContainerStatus;
import dev.hoardkeeper.scan.FailReason;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.scan.ScanSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionExporterTest {

    private static final Gson GSON = new Gson();

    private static ContainerCandidate candidate(int[] pos, ContainerKind kind, ContainerStatus status,
                                                 FailReason failReason) {
        ContainerCandidate c = new ContainerCandidate(pos, null, kind);
        c.status = status;
        c.failReason = failReason;
        return c;
    }

    private static CandidateState candidateState(int[] pos, String kind, String status, String failReason) {
        CandidateState state = new CandidateState();
        state.pos = pos;
        state.kind = kind;
        state.status = status;
        state.failReason = failReason;
        return state;
    }

    private static SessionSnapshot snapshotWith(CandidateState... states) {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.sessionId = "sid";
        snapshot.server = "srv";
        snapshot.dimension = "minecraft:overworld";
        snapshot.containers = List.of(states);
        return snapshot;
    }

    private static ScannedContainer container(String kind, ScannedItem... items) {
        ScannedContainer c = new ScannedContainer();
        c.kind = kind;
        c.pos = new int[]{0, 64, 0};
        c.items = List.of(items);
        return c;
    }

    private static ScannedItem item(String id, int count) {
        ScannedItem i = new ScannedItem();
        i.id = id;
        i.count = count;
        return i;
    }

    private static ScanReport.FailureEntry entry(int[] pos, String kind, String reason) {
        ScanReport.FailureEntry e = new ScanReport.FailureEntry();
        e.pos = pos;
        e.kind = kind;
        e.reason = reason;
        return e;
    }

    // =========================================================================
    // failuresOf(candidates)
    // =========================================================================

    @Test
    void failuresOfIncludesAFailedCandidateWithARemoteReason() {
        // A container the server actually refused must reach the report's failed list with its
        // position, kind and reason -- otherwise a player retrying failures has nothing to retry.
        ContainerCandidate failed = candidate(new int[]{10, 64, -20}, ContainerKind.BARREL,
                ContainerStatus.FAILED, FailReason.NO_RESPONSE);

        List<ScanReport.FailureEntry> failures = SessionExporter.failuresOf(List.of(failed));

        assertEquals(1, failures.size());
        assertArrayEquals(new int[]{10, 64, -20}, failures.get(0).pos);
        assertEquals("barrel", failures.get(0).kind);
        assertEquals("NO_RESPONSE", failures.get(0).reason);
    }

    @Test
    void failuresOfExcludesAScannedCandidate() {
        // A successfully scanned container showing up as a failure would send a player back to
        // retry a chest that was already read.
        ContainerCandidate scanned = candidate(new int[]{1, 64, 1}, ContainerKind.CHEST,
                ContainerStatus.SCANNED, null);

        assertTrue(SessionExporter.failuresOf(List.of(scanned)).isEmpty());
    }

    @Test
    void failuresOfExcludesALocalSkip() {
        // BLOCKED/GONE candidates never had a packet sent to the server. Filing them as failures
        // would bury the failures the server actually caused inside a pile of harmless local skips.
        ContainerCandidate blocked = candidate(new int[]{2, 64, 2}, ContainerKind.CHEST,
                ContainerStatus.FAILED, FailReason.BLOCKED);

        assertTrue(SessionExporter.failuresOf(List.of(blocked)).isEmpty());
    }

    // =========================================================================
    // skippedOf(candidates)
    // =========================================================================

    @Test
    void skippedOfIncludesALocalSkip() {
        // The mirror of failuresOf: a container this scan decided locally not to open must still be
        // named in the report, or a run that read 121 of 122 containers would look complete.
        ContainerCandidate gone = candidate(new int[]{5, 70, 5}, ContainerKind.SHULKER_BOX,
                ContainerStatus.FAILED, FailReason.GONE);

        List<ScanReport.FailureEntry> skipped = SessionExporter.skippedOf(List.of(gone));

        assertEquals(1, skipped.size());
        assertArrayEquals(new int[]{5, 70, 5}, skipped.get(0).pos);
        assertEquals("shulker_box", skipped.get(0).kind);
        assertEquals("GONE", skipped.get(0).reason);
    }

    @Test
    void skippedOfExcludesAScannedCandidate() {
        ContainerCandidate scanned = candidate(new int[]{1, 64, 1}, ContainerKind.CHEST,
                ContainerStatus.SCANNED, null);

        assertTrue(SessionExporter.skippedOf(List.of(scanned)).isEmpty());
    }

    @Test
    void skippedOfExcludesARemoteFailure() {
        // The two lists must partition FAILED candidates exactly: a remote failure counted as a
        // skip too would double it in the report's on-disk counts.
        ContainerCandidate failed = candidate(new int[]{3, 64, 3}, ContainerKind.CHEST,
                ContainerStatus.FAILED, FailReason.NO_CONTENT);

        assertTrue(SessionExporter.skippedOf(List.of(failed)).isEmpty());
    }

    // =========================================================================
    // failuresOf(snapshot)
    // =========================================================================

    @Test
    void failuresOfSnapshotIncludesARemoteFailure() {
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{8, 64, 8}, "chest", "FAILED", "NO_RESPONSE"));

        List<ScanReport.FailureEntry> failures = SessionExporter.failuresOf(snapshot);

        assertEquals(1, failures.size());
        assertArrayEquals(new int[]{8, 64, 8}, failures.get(0).pos);
        assertEquals("NO_RESPONSE", failures.get(0).reason);
    }

    @Test
    void failuresOfSnapshotExcludesAScannedState() {
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{1, 64, 1}, "chest", "SCANNED", null));

        assertTrue(SessionExporter.failuresOf(snapshot).isEmpty());
    }

    @Test
    void failuresOfSnapshotExcludesALocalSkip() {
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{1, 64, 1}, "chest", "FAILED", "BLOCKED"));

        assertTrue(SessionExporter.failuresOf(snapshot).isEmpty());
    }

    @Test
    void failuresOfSnapshotDropsACandidateWithNoPosition() {
        // A snapshot row this unreadable cannot be placed anywhere in a report; the same guard
        // toSession uses to drop it from a resumed session applies here too.
        SessionSnapshot snapshot = snapshotWith(
                candidateState(null, "chest", "FAILED", "NO_RESPONSE"));

        assertTrue(SessionExporter.failuresOf(snapshot).isEmpty());
    }

    @Test
    void failuresOfSnapshotReportsAnUnknownReasonAsARealFailure() {
        // A reason string this build cannot parse is never known to be a local skip, so it is
        // reported as a real failure rather than silently dropped or miscategorised as a skip.
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{1, 64, 1}, "chest", "FAILED", "MYSTERY_REASON"));

        List<ScanReport.FailureEntry> failures = SessionExporter.failuresOf(snapshot);

        assertEquals(1, failures.size());
        assertEquals("MYSTERY_REASON", failures.get(0).reason);
    }

    // =========================================================================
    // skippedOf(snapshot)
    // =========================================================================

    @Test
    void skippedOfSnapshotIncludesALocalSkip() {
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{2, 64, 2}, "barrel", "FAILED", "GONE"));

        List<ScanReport.FailureEntry> skipped = SessionExporter.skippedOf(snapshot);

        assertEquals(1, skipped.size());
        assertArrayEquals(new int[]{2, 64, 2}, skipped.get(0).pos);
        assertEquals("GONE", skipped.get(0).reason);
    }

    @Test
    void skippedOfSnapshotExcludesAScannedState() {
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{1, 64, 1}, "chest", "SCANNED", null));

        assertTrue(SessionExporter.skippedOf(snapshot).isEmpty());
    }

    @Test
    void skippedOfSnapshotExcludesARemoteFailure() {
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{1, 64, 1}, "chest", "FAILED", "NO_RESPONSE"));

        assertTrue(SessionExporter.skippedOf(snapshot).isEmpty());
    }

    @Test
    void skippedOfSnapshotDropsACandidateWithNoPosition() {
        SessionSnapshot snapshot = snapshotWith(
                candidateState(null, "chest", "FAILED", "BLOCKED"));

        assertTrue(SessionExporter.skippedOf(snapshot).isEmpty());
    }

    @Test
    void skippedOfSnapshotDoesNotTreatAnUnknownReasonAsASkip() {
        // Mirror of failuresOfSnapshotReportsAnUnknownReasonAsARealFailure: an unparseable reason
        // must count as a real failure exactly once, never also as a skip.
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{1, 64, 1}, "chest", "FAILED", "MYSTERY_REASON"));

        assertTrue(SessionExporter.skippedOf(snapshot).isEmpty());
    }

    // =========================================================================
    // knownOf(snapshot)
    // =========================================================================

    @Test
    void knownOfPrefersTheRecordedStatOverTheContainerListSize() {
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{1, 64, 1}, "chest", "SCANNED", null),
                candidateState(new int[]{2, 64, 2}, "chest", "SCANNED", null));
        snapshot.stats = new SessionSnapshot.Stats();
        snapshot.stats.known = 122;

        assertEquals(122, SessionExporter.knownOf(snapshot));
    }

    @Test
    void knownOfFallsBackToTheContainerListSizeWhenStatsIsNull() {
        // A snapshot old enough (or torn enough) to be missing stats must still report *something*
        // rather than a hard-coded zero -- the container rows it does have are the next best answer.
        SessionSnapshot snapshot = snapshotWith(
                candidateState(new int[]{1, 64, 1}, "chest", "SCANNED", null),
                candidateState(new int[]{2, 64, 2}, "chest", "SCANNED", null));
        snapshot.stats = null;

        assertEquals(2, SessionExporter.knownOf(snapshot));
    }

    // =========================================================================
    // Header.of
    // =========================================================================

    @Test
    void headerOfSessionAndOfSnapshotAgreeForARadiusScan() {
        // The exported report names the session and area a player just scanned; a live session's
        // header and one rebuilt from the snapshot of that very same session must describe the
        // identical scan, or an interrupted-then-resumed export could show the wrong area.
        ScanSession session = new ScanSession("sid", "srv", "minecraft:overworld",
                new int[]{100, 64, -200}, ScanArea.ofRadius(100, -200, 48));
        SessionSnapshot snapshot = session.toSnapshot("RUNNING");

        SessionExporter.Header fromSession = SessionExporter.Header.of(session);
        SessionExporter.Header fromSnapshot = SessionExporter.Header.of(snapshot);

        assertEquals(fromSession.sessionId(), fromSnapshot.sessionId());
        assertEquals(fromSession.server(), fromSnapshot.server());
        assertEquals(fromSession.dimension(), fromSnapshot.dimension());
        assertArrayEquals(fromSession.origin(), fromSnapshot.origin());
        assertEquals(fromSession.area(), fromSnapshot.area());
        assertEquals(ScanArea.Mode.RADIUS, fromSnapshot.area().mode());
    }

    @Test
    void headerOfSessionAndOfSnapshotAgreeForAChunkScan() {
        ScanSession session = new ScanSession("sid2", "srv", "minecraft:the_nether",
                new int[]{16, 70, 32}, ScanArea.ofChunks(16, 32, 2));
        SessionSnapshot snapshot = session.toSnapshot("RUNNING");

        SessionExporter.Header fromSession = SessionExporter.Header.of(session);
        SessionExporter.Header fromSnapshot = SessionExporter.Header.of(snapshot);

        assertEquals(fromSession.area(), fromSnapshot.area());
        assertEquals(ScanArea.Mode.CHUNKS, fromSnapshot.area().mode());
        assertEquals(2, fromSnapshot.area().chunkRadius());
    }

    // =========================================================================
    // write
    // =========================================================================

    @Test
    void writeProducesAReportJsonThatMatchesTheInputs(@TempDir Path dir) throws Exception {
        SessionExporter.Header header = new SessionExporter.Header("sid", "srv", "minecraft:overworld",
                new int[]{0, 64, 0}, ScanArea.ofRadius(0, 0, 64));
        List<ScannedContainer> containers = List.of(
                container("barrel", item("minecraft:diamond", 10)),
                container("chest", item("minecraft:gold_ingot", 5)));
        List<ScanReport.FailureEntry> failures = List.of(entry(new int[]{5, 64, 5}, "chest", "NO_RESPONSE"));
        List<ScanReport.FailureEntry> skipped = List.of(entry(new int[]{6, 64, 6}, "barrel", "BLOCKED"));
        Map<String, Integer> maxStackSizes = Map.of("minecraft:diamond", 64, "minecraft:gold_ingot", 64);

        Path out = SessionExporter.write(dir, header, 5, containers, failures, skipped, maxStackSizes);

        assertEquals(dir.resolve("report.json"), out);
        assertTrue(Files.isRegularFile(out));

        ScanReport report = GSON.fromJson(Files.readString(out), ScanReport.class);
        assertEquals(5, report.containers.known);
        assertEquals(2, report.containers.scanned);
        assertEquals(1, report.containers.failed);
        assertEquals(1, report.containers.skipped);
        assertEquals("NO_RESPONSE", report.failed.get(0).reason);
        assertEquals("BLOCKED", report.skipped.get(0).reason);

        Map<String, Long> totals = report.totalsByItem.stream()
                .collect(Collectors.toMap(t -> t.id, t -> t.count));
        assertEquals(10L, totals.get("minecraft:diamond"));
        assertEquals(5L, totals.get("minecraft:gold_ingot"));
    }

    @Test
    void writeReturnsNullAndWritesNothingWhenThereIsNothingToExport(@TempDir Path dir) {
        // A session with zero containers, zero failures and zero skips is exactly "nothing
        // happened yet" -- writing an empty report.json would tell a player a scan finished when
        // it never produced anything to look at.
        SessionExporter.Header header = new SessionExporter.Header("sid", "srv", "minecraft:overworld",
                new int[]{0, 64, 0}, ScanArea.ofRadius(0, 0, 64));

        Path out = SessionExporter.write(dir, header, 0, List.of(), List.of(), List.of(), Map.of());

        assertNull(out);
        assertFalse(Files.exists(dir.resolve("report.json")));
    }
}
