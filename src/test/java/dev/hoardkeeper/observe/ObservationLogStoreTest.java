package dev.hoardkeeper.observe;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.store.AtomicJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservationLogStoreTest {

    private static final String SERVER = "localhost:25565";
    private static final String REALM = "seed:1d333b8d";
    private static final String DIMENSION = "minecraft:overworld";

    private static ObservationLogStore store(Path dir) {
        return new ObservationLogStore(dir, SERVER, REALM, DIMENSION);
    }

    private static ScannedContainer row(int x, String scannedAt) {
        ScannedContainer c = new ScannedContainer();
        c.pos = new int[]{x, 64, 0};
        c.kind = "chest";
        c.slotCount = 27;
        c.scannedAt = scannedAt;
        return c;
    }

    private static List<Integer> positions(ObservationLogStore store) {
        return store.rows().stream().map(c -> c.pos[0]).sorted().toList();
    }

    // ---- append / rows(): does the row really reach the file? ------------------------------

    /**
     * If an appended row never actually reaches disk, a chest a player closed is gone the moment
     * they log back in -- the whole promise of passive observation. A fresh store instance over
     * the same directory (standing in for the client reconnecting) has to see it too.
     */
    @Test
    void anAppendedRowIsThereAndSurvivesAFreshStoreOverTheSameDirectory(@TempDir Path dir) {
        ObservationLogStore store = store(dir);
        store.append(row(1, "2026-09-08T10:00:00Z"));
        store.awaitPendingWritesForTests();

        assertEquals(List.of(1), positions(store));
        assertEquals(List.of(1), positions(store(dir)));
    }

    /**
     * If rows() did not fold duplicates the way the uploader and the map readers do, a chest
     * re-sorted twice would look like two different containers to anything reading this log before
     * the next compaction -- and if the raw file lost either line early, the upload watermark
     * (a row count) would drift from what is actually on disk. Both must hold at once.
     */
    @Test
    void twoAppendsForTheSamePositionFoldOnReadButBothLinesStayUntilCompaction(@TempDir Path dir) {
        ObservationLogStore store = store(dir);
        store.append(row(1, "2026-09-08T10:00:00Z"));
        store.append(row(1, "2026-09-08T10:01:00Z"));
        store.awaitPendingWritesForTests();

        assertEquals(2, store.rawRowCount(), "no compaction has run -- both observations are still on disk");
        assertEquals(1, store.rows().size(), "rows() applies newest-per-position, the same fold a reader gets");
        assertEquals("2026-09-08T10:01:00Z", store.rows().get(0).scannedAt);
    }

    // ---- compactIfDue: at and below the row cap -------------------------------------------

    /**
     * If compaction rewrote a file that had nothing to gain from it, every join would pay for a
     * pointless rewrite of a large log -- exactly the cost spec §3.2's counterpart in
     * MeasuredMapStore exists to avoid. At the cap, with no superseded rows, nothing should move.
     */
    @Test
    void atTheRowCapWithNoDuplicatesCompactionChangesNothing(@TempDir Path dir) {
        ObservationLogStore store = store(dir);
        store.append(row(1, "2026-09-08T10:00:00Z"));
        store.append(row(2, "2026-09-08T10:01:00Z"));
        store.awaitPendingWritesForTests();

        ObservationLogStore.CompactionAttempt attempt = store.compactIfDue(2);
        attempt.future().join();
        store.finishCompaction(attempt.generation());

        assertEquals(2, store.rawRowCount());
        assertEquals(List.of(1, 2), positions(store));
        assertEquals(0, store.state().uploadedRows);
    }

    /**
     * If the row cap gated *whether* folding happens rather than only how much surplus survives
     * it, a log far below its cap would accumulate every superseded observation forever, growing
     * without bound between the rare joins that actually hit the cap. A stale duplicate must be
     * folded away long before the cap is ever in sight.
     */
    @Test
    void belowTheRowCapCompactionStillFoldsASupersededRow(@TempDir Path dir) {
        ObservationLogStore store = store(dir);
        store.append(row(1, "2026-09-08T10:00:00Z"));
        store.append(row(1, "2026-09-08T10:01:00Z"));
        store.awaitPendingWritesForTests();
        assertEquals(2, store.rawRowCount());

        ObservationLogStore.CompactionAttempt attempt = store.compactIfDue(100);
        attempt.future().join();
        store.finishCompaction(attempt.generation());

        assertEquals(1, store.rawRowCount(), "the stale duplicate is gone though the cap was never near");
        assertEquals("2026-09-08T10:01:00Z", store.rows().get(0).scannedAt);
    }

    /**
     * If the watermark did not follow compaction to whichever end it started from, an upload that
     * had already sent everything would either resend the whole log again (the "nothing sent" end,
     * wrongly applied) or, worse, believe rows it never sent had already gone out. This is
     * ObservationLog.watermarkAfterCompaction's own arithmetic; this test pins that the store
     * actually applies it rather than some other value.
     */
    @Test
    void compactionAdvancesAnAlreadyFullWatermarkToMatchTheFoldedCount(@TempDir Path dir) {
        ObservationLogStore store = store(dir);
        store.append(row(1, "2026-09-08T10:00:00Z"));
        store.append(row(1, "2026-09-08T10:01:00Z"));
        store.append(row(2, "2026-09-08T10:02:00Z"));
        store.awaitPendingWritesForTests();
        assertEquals(3, store.rawRowCount());

        // Simulate everything having already reached the upload server -- the "uploadedRows == totalRows"
        // end mayCompact allows -- without going through the uploader itself.
        ObservationLogState uploaded = new ObservationLogState();
        uploaded.uploadedRows = 3;
        AtomicJson.write(ObservationLogFiles.state(dir), uploaded);

        ObservationLogStore.CompactionAttempt attempt = store.compactIfDue(100);
        attempt.future().join();
        store.finishCompaction(attempt.generation());

        assertEquals(2, store.rawRowCount());
        assertEquals(ObservationLog.watermarkAfterCompaction(3, 2), store.state().uploadedRows);
    }

    // ---- the deferral path: the one with the historic bug ----------------------------------

    /**
     * The bug three commits (b7829e4, 643419e, 2207066) were spent on: a chest closed in the
     * instant between a compaction's rewrite finishing and the client thread getting around to
     * reopening the writer must not vanish. If this regresses, a player who happens to close a
     * chest right as they join a familiar server silently loses that one observation forever.
     *
     * <p>The row is appended immediately after {@code compactIfDue} returns, before its future is
     * even joined -- {@code compactingDir} is set synchronously by {@code compactIfDue} before it
     * returns, so whether the background rewrite has started, finished, or not yet run makes no
     * difference to whether {@code append} defers: the guard is checked on this thread, against a
     * field this same thread just set, not something that only shows up if real timing races. The
     * first two appends duplicate one position so the compaction below is a genuine rewrite (a
     * no-op compaction would let a broken guard through unnoticed).
     */
    @Test
    void aRowAppendedWhileACompactionIsStillHeldOpenIsNotLost(@TempDir Path dir) {
        ObservationLogStore store = store(dir);
        store.append(row(1, "2026-09-08T10:00:00Z"));
        store.append(row(1, "2026-09-08T10:01:00Z"));
        store.awaitPendingWritesForTests();

        ObservationLogStore.CompactionAttempt attempt = store.compactIfDue(100);
        // Deterministic, not a race: compactingDir was already set by the call above, on this
        // same thread, before append() ever runs.
        store.append(row(2, "2026-09-08T10:02:00Z"));
        assertEquals(1, store.deferredRowCount(),
                "a row landing while compactingDir is set must be held, not written straight "
                        + "through to a file the background rewrite may be about to replace");

        attempt.future().join();
        store.finishCompaction(attempt.generation());
        store.awaitPendingWritesForTests();

        assertEquals(List.of(1, 2), positions(store), "the held row must survive to the final file");
    }

    /**
     * The generation counter's own reason to exist: a completion arriving late must not be able to
     * release a guard a *later* compaction of this same log is holding. Without this, "every
     * subsequent row that session would go to a file nothing reads" -- ObservationLogStore's own
     * javadoc on compactionGeneration -- because the stale completion would reopen the writer (or
     * hand deferred rows to it) while the real, still-current rewrite has not necessarily finished
     * replacing the file underneath it.
     */
    @Test
    void aStaleCompactionCompletionMayNotReleaseAGuardALaterCompactionStillHolds(@TempDir Path dir) {
        ObservationLogStore store = store(dir);
        store.append(row(1, "2026-09-08T10:00:00Z"));
        store.awaitPendingWritesForTests();

        ObservationLogStore.CompactionAttempt first = store.compactIfDue(100);
        first.future().join();

        // A second compaction of the same log claims the file before the first attempt's own
        // completion callback got around to running -- "reconnect to the same server twice", per
        // compactingDir's own javadoc.
        ObservationLogStore.CompactionAttempt second = store.compactIfDue(100);
        second.future().join();

        store.append(row(2, "2026-09-08T10:01:00Z"));
        assertEquals(1, store.deferredRowCount());

        // The stale completion from the FIRST attempt must be refused outright.
        store.finishCompaction(first.generation());
        assertEquals(1, store.deferredRowCount(), "a stale completion must not touch the guard or deferred");

        // The real, current completion still works.
        store.finishCompaction(second.generation());
        assertEquals(0, store.deferredRowCount());
        store.awaitPendingWritesForTests();

        assertEquals(List.of(1, 2), positions(store));
    }

    /**
     * A log with nothing on disk yet has nothing to compact, so compactIfDue must not hand back a
     * generation some later, real compaction's completion could ever be confused with. -1 can
     * never equal compactionGeneration, which only ever counts up from 0.
     */
    @Test
    void compactIfDueOnALogWithNoFileYetReturnsAGenerationNothingCanEverMatch(@TempDir Path dir) {
        ObservationLogStore store = store(dir);

        ObservationLogStore.CompactionAttempt attempt = store.compactIfDue(100);
        assertDoesNotThrow(attempt.future()::join);
        assertDoesNotThrow(() -> store.finishCompaction(attempt.generation()));
        assertEquals(0, store.deferredRowCount());
    }

    // ---- state() ----------------------------------------------------------------------------

    /**
     * If a log with no state file yet answered anything but "never uploaded", spec §10's fallback
     * would misreport a brand-new log's watermark, and pendingRows would compute against a bogus
     * uploadedRows instead of the whole file.
     */
    @Test
    void stateOnADirectoryWithNoStateFileReadsAsNeverUploaded(@TempDir Path dir) {
        assertEquals(0, store(dir).state().uploadedRows);
    }

    /**
     * If log.json never actually gained the log's own identity, ObservationUploader would not
     * know which server, realm or dimension the rows it eventually reads belong to when it builds
     * the synthetic snapshot -- every upload for this log would carry the wrong provenance.
     */
    @Test
    void stateAfterAWriteCarriesTheLogsIdentity(@TempDir Path dir) {
        ObservationLogStore store = store(dir);
        store.append(row(1, "2026-09-08T10:00:00Z"));
        store.awaitPendingWritesForTests();

        ObservationLogState state = store.state();
        assertEquals(SERVER, state.server);
        assertEquals(REALM, state.realm);
        assertEquals(DIMENSION, state.dimension);
    }

    // ---- close() ------------------------------------------------------------------------------

    /**
     * If closing twice threw, the defensive close PassiveObserver.onJoin makes "so the invariant
     * does not depend on [disconnect] having happened" would crash the client on an ordinary
     * reconnect. If a store used after close corrupted the file instead of reopening cleanly, a
     * relog followed by one more chest would wreck the whole log rather than just append to it.
     */
    @Test
    void closingTwiceDoesNotThrowAndAStoreUsedAfterwardReopensCleanly(@TempDir Path dir) {
        ObservationLogStore store = store(dir);
        store.append(row(1, "2026-09-08T10:00:00Z"));
        store.awaitPendingWritesForTests();

        store.close();
        assertDoesNotThrow(store::close);

        store.append(row(2, "2026-09-08T10:01:00Z"));
        store.awaitPendingWritesForTests();

        assertEquals(List.of(1, 2), positions(store));
    }
}
