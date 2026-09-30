package dev.hoardkeeper.observe;

import com.google.gson.Gson;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.store.AtomicJson;
import dev.hoardkeeper.store.ContainerJsonlWriter;
import dev.hoardkeeper.store.ScanStorage;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One realm/dimension's observation log on disk: append a row, read it back, compact it, and keep
 * its upload watermark and dirty/clean generation beside it. Design spec §4.1, §4.3 and §4.4.
 *
 * <p>The file-handling half of what {@code PassiveObserver} used to do end to end, split out the
 * same way {@code measured.MeasuredMapStore} already is for the measured map: {@code PassiveObserver}
 * keeps the decision of <em>what</em> is an observation (the click, the screen, the menu, the area
 * and registration checks) and holds one {@code ObservationLogStore} per {@code PassiveObserver.Log}
 * for <em>how the file is written, compacted and watermarked</em>.
 *
 * <p><b>Why compaction is safe against an append</b> (spec §4.4 asks for both to be serialised).
 * A compaction rewrites {@code containers.jsonl} through a {@code .tmp} and a rename, so an append
 * landing on the old inode in between would be lost. This store is the only opener of its own
 * writer and the only submitter of its own compaction, and it keeps the two apart: the writer is
 * closed before a compaction is submitted, no writer is opened for this log while one is running,
 * and the observations made meanwhile are held in {@link #deferred} and appended when it finishes.
 * The file therefore has exactly one owner at any moment.
 *
 * <p><b>One store, one log, one background chain.</b> Every task that touches this log's files —
 * an append through the writer, a compaction, an upload's read-through-write span — either runs on
 * the writer's own thread ({@link ContainerJsonlWriter}) or is chained onto {@link #maintenance},
 * which runs on {@link #MAINTENANCE_EXECUTOR}: one thread, shared by every {@code ObservationLogStore}
 * instance rather than one spun up per log, so that a compaction and an upload check for the *same*
 * log can never overlap and a client that visits several dimensions in a session does not accumulate
 * a maintenance thread per dimension. The chain itself ({@link #maintenance}) is per store, because
 * the invariant that matters — a log's own compaction and its own upload never overlap — is a
 * per-log invariant; two different logs' maintenance work was never required to serialise against
 * each other, and sharing one executor already makes that true regardless of how the chains are
 * grouped.
 */
public final class ObservationLogStore {

    private static final Gson GSON = new Gson();

    /**
     * The single background thread every {@code ObservationLogStore} instance chains its maintenance
     * work through.
     *
     * <p>Named explicitly rather than left to {@code thenRunAsync}'s bare default
     * ({@code ForkJoinPool.commonPool()}), the same reason {@code SessionUploader} and
     * {@code ContainerJsonlWriter} each name their own single-thread daemon executor: an upload
     * chained here can block on HTTP for a long time (retries and backoff can add up to roughly a
     * couple of minutes per batch), and that has no business quietly occupying a JVM-wide shared
     * pool, nor should it depend on that pool happening to be a daemon by implementation detail
     * rather than by this class's own declaration.
     *
     * <p>Daemon and shared, not per-instance: {@code PassiveObserver} creates one store per log it
     * has ever seen this session, so an instance-owned executor would start a new unshut-down
     * thread per dimension visited instead of reusing one for the life of the client — exactly the
     * reasoning {@code MeasuredMapStore.WRITE_EXECUTOR} already documents for its own static,
     * class-wide executor.
     */
    private static final ExecutorService MAINTENANCE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "hoardkeeper-observation-maintenance");
                thread.setDaemon(true);
                return thread;
            });

    private final Path dir;
    private final String server;
    private final String realm;
    private final String dimension;

    /**
     * This log's own open writer, or {@code null}. Unlike the pre-extraction field of the same name,
     * there is no companion "which directory is it for" field: a store only ever opens a writer over
     * its own {@link #dir}, so the answer is always {@code dir} whenever this is non-null.
     */
    private ContainerJsonlWriter writer;

    /** The log being rewritten right now; no writer may be open for it. */
    private Path compactingDir;
    /**
     * Which compaction {@link #compactingDir} belongs to. A completion can arrive after a later
     * compaction of the same log has already claimed the file — reconnect to the same server twice
     * and both submissions name the same directory — so the path alone cannot tell "mine" from
     * "the next one's", and releasing the guard for a rewrite still in progress would open an
     * appending writer on an inode about to be unlinked. Every subsequent row that session would go
     * to a file nothing reads.
     */
    private long compactionGeneration;
    /** Observations made while {@link #compactingDir} is being rewritten, appended afterwards. */
    private final List<ScannedContainer> deferred = new ArrayList<>();

    /** Every background task that touches this log's files, chained so they never overlap. */
    private CompletableFuture<Void> maintenance = CompletableFuture.completedFuture(null);

    /**
     * Spec §4.4's dirty bit, as a per-log counter bumped by {@link #markDirty}: a row landed — even
     * one only held in {@link #deferred}, not yet physically written — so this log's on-disk state
     * may no longer match what {@code ObservationUploader} last confirmed uploaded, and its next
     * tick check must actually look rather than trust a stale "nothing pending" answer. Read only
     * from the client thread, same as every write to it ({@link #append} and
     * {@link #finishCompaction} both run there), so a plain {@code long} is enough — no volatile,
     * no atomics.
     */
    private long dirtyGeneration;

    /**
     * Whether this log's files are being read and re-watermarked by an upload right now. Set by
     * {@link #tryMarkUploadInFlight()} on the client thread and cleared by
     * {@link #clearUploadInFlight()} from inside the upload's own maintenance task, so — unlike
     * {@link #dirtyGeneration} — this one genuinely crosses threads and needs an atomic, not a bare
     * field.
     */
    private final AtomicBoolean uploadInFlight = new AtomicBoolean(false);

    public ObservationLogStore(Path dir, String server, String realm, String dimension) {
        this.dir = dir;
        this.server = server;
        this.realm = realm;
        this.dimension = dimension;
    }

    // =========================================================================
    // Reading
    // =========================================================================

    /**
     * Every row in the file, newest per position already applied — the same fold
     * {@code PayloadBuilder.build} applies before it ever sends a row, so a caller asking "what does
     * this log currently say" sees the same answer an upload would. The file itself keeps every row
     * until the next compaction; only the read is folded. Never throws.
     */
    public List<ScannedContainer> rows() {
        return ObservationLog.compact(ScanStorage.readContainers(ObservationLogFiles.containers(dir)),
                Integer.MAX_VALUE);
    }

    /**
     * How many physical lines {@code containers.jsonl} holds right now, duplicates included — the
     * count the upload watermark in {@link #state()} is measured against, and what
     * {@code PassiveObserver.status} reports rather than {@link #rows()}'s folded count: a chest
     * re-observed twice is one row on the map but two rows still owing an upload until the next
     * compaction.
     */
    public int rawRowCount() {
        return ScanStorage.readContainers(ObservationLogFiles.containers(dir)).size();
    }

    /**
     * How many observations are currently held behind an in-progress compaction of this log, or
     * {@code 0} at every other time — {@link #deferred} is only ever non-empty in that window. Rows
     * held here are already observed and will be appended the moment {@link #finishCompaction}
     * runs; a caller counting "how many rows does this log have" (spec §7's status line) must not
     * drop them just because they have not physically reached the file yet.
     */
    public int deferredRowCount() {
        return deferred.size();
    }

    /**
     * Reads {@code log.json}. A file that is missing, empty or unparseable reads as "never
     * uploaded" (spec §10): everything re-uploads, which the server's upsert makes harmless, and
     * the rows themselves are never deleted on the strength of a file nobody could read.
     */
    public ObservationLogState state() {
        return readState();
    }

    // =========================================================================
    // Writing
    // =========================================================================

    /**
     * Queues {@code row} for this log, or holds it if this log is being compacted right now.
     *
     * <p>Marks this log dirty first, unconditionally — even a row that only reaches {@link #deferred}
     * changes what "is this log clean" ({@code ObservationUploader.stillClean}) must answer, since it
     * is real, observed data that is not yet reflected in a confirmed-clean read.
     */
    public void append(ScannedContainer row) {
        markDirty();
        if (compactingDir != null) {
            deferred.add(row);
        } else {
            ContainerJsonlWriter target = writerFor();
            if (target != null) {
                target.append(row);
            }
        }
    }

    /**
     * The writer for this log, opened on first use. Unlike the pre-extraction version, this store
     * never has to notice a directory change and close a writer for a *different* log — it only ever
     * manages its own — so switching which log currently owns the client's attention is
     * {@code PassiveObserver}'s job (closing the previously active store's writer), not this method's.
     */
    private ContainerJsonlWriter writerFor() {
        if (writer != null) {
            return writer;
        }
        try {
            writer = new ContainerJsonlWriter(ObservationLogFiles.containers(dir));
        } catch (RuntimeException e) {
            // Spec §10: an observation is lost, the client is not. The next one tries again.
            HoardkeeperMod.LOGGER.warn("Could not open the observation log at {}", dir, e);
            writer = null;
            return null;
        }
        // So the log carries its own identity from its first row onward, which is what the upload
        // reads to know which realm and dimension these containers belong to.
        submit(this::ensureState);
        return writer;
    }

    /** Closes this store's writer, if one is open. Idempotent: closing twice is a no-op. */
    public void closeWriter() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    /**
     * Blocks until every observation queued so far for this log's writer — if one happens to be open
     * right now — has actually reached the file. A no-op when no writer is open (nothing queued to
     * drain), which is always the case while a compaction of this log is in progress: the writer is
     * closed before a compaction is submitted, per this class's own invariant, so there is never a
     * writer here for {@link #compact} to race.
     *
     * <p>Called by {@code ObservationUploader.onClientTick}, on the client thread, immediately before
     * it captures this log's dirty generation and hands a read of the same file to the maintenance
     * chain. {@link ContainerJsonlWriter#append} queues onto its own executor and returns; a
     * generation bump alone (spec §4.4's dirty bit) only proves a row was <em>queued</em>, not that
     * it is readable yet — the exact gap {@link ContainerJsonlWriter#flush}'s own javadoc names for
     * the report export, closed here the same way and for the identical reason.
     *
     * <p><b>The drain is best-effort, and this cannot tell the caller when it was not.</b>
     * {@link ContainerJsonlWriter#flush} waits ten seconds and, on timeout, logs an ERROR and
     * returns anyway rather than throwing — so this method returns normally whether the queue
     * actually drained or not, and the {@code generationAtStart} the uploader captures on the next
     * line silently weakens from "everything up to here is durable on disk" back to "everything up
     * to here was queued". A row still sitting in the queue at that point can be missed by the
     * upload's read and then covered by a "confirmed clean" mark. Nothing is lost — the row is in
     * {@code containers.jsonl} either way, still pending by row count, and the next
     * {@link #markDirty} for this log makes that mark stop matching and forces a fresh look — but
     * its upload is delayed until then. Left as is deliberately: a writer thread stalled for ten
     * seconds is a far larger problem than a delayed passive upload, and it already says so at
     * ERROR.
     */
    public void flushWriter() {
        if (writer != null) {
            writer.flush();
        }
    }

    // =========================================================================
    // Compaction
    // =========================================================================

    /**
     * What {@link #compactIfDue} started: the generation this attempt runs under — pass it to
     * {@link #finishCompaction} once {@link #future} completes — and the future itself.
     *
     * <p>A small record instead of handing back {@code CompletableFuture<Long>} on purpose: the
     * generation is only meaningful on the happy path, and threading it through the future's own
     * value would mean {@code finishCompaction} could be handed a {@code null} unboxed to a
     * primitive {@code long} on the (practically unreachable, since {@link #submit} already catches
     * every {@link Exception}) exceptional path. Capturing it as a plain field sidesteps that
     * entirely, the same way the pre-extraction code captured {@code generation} as a local variable
     * closed over by its completion callback.
     */
    public record CompactionAttempt(long generation, CompletableFuture<Void> future) {
    }

    /**
     * The generation {@link #compactIfDue} hands back when there was nothing to compact and
     * {@link #compactionGeneration} was never bumped at all. Never a value the counter could ever
     * actually hold — it only ever counts up from {@code 0} — so a {@link #finishCompaction} call
     * carrying it can never be mistaken for a real compaction's completion, the same reason a
     * mismatched real generation is refused. Before this existed, the no-op path handed back
     * whatever {@link #compactionGeneration} happened to already be, which was harmless only
     * because {@link #compactingDir} being {@code null} caught it too — a second, independent
     * reason to refuse, not something this sentinel should have to lean on.
     */
    private static final long NO_COMPACTION = -1;

    /**
     * Compacts this log's file on the maintenance thread, if there is one to compact — a log that
     * has never had a row written has no {@code containers.jsonl}, and there is nothing to close a
     * writer over or watermark. Runs only when the watermark sits at one of its two ends — see
     * {@link ObservationLog#mayCompact}, checked from inside {@link #compact} itself.
     *
     * <p>Deliberately does not chain {@link #finishCompaction} itself: that method mutates
     * {@link #compactingDir} and {@link #deferred}, which {@link #append} also reads and writes, and
     * in production those two must never run at once without synchronising them by hand — which is
     * exactly what routing {@link #finishCompaction} back through the client thread already gives
     * for free, since {@link #append} only ever runs there too. A caller with no such thread (a
     * test) may simply call {@link #finishCompaction} right after the returned future completes,
     * immediately on the same thread — nothing about the guard cares which thread
     * {@link #finishCompaction} runs on, only that it never overlaps an {@link #append} call for
     * this same store.
     */
    public CompactionAttempt compactIfDue(int maxRows) {
        if (!Files.isRegularFile(ObservationLogFiles.containers(dir))) {
            return new CompactionAttempt(NO_COMPACTION, CompletableFuture.completedFuture(null));
        }
        // Nothing may hold the file while it is rewritten. Normally already closed by the
        // disconnect that preceded this join; closed here too so the invariant does not depend on
        // that having happened.
        closeWriter();
        compactingDir = dir;
        long generation = ++compactionGeneration;
        return new CompactionAttempt(generation, submit(() -> compact(maxRows)));
    }

    /**
     * Back on the client thread after a compaction: the log has one owner again — but only if the
     * compaction that just finished is still the one holding it. A completion that lost the race to
     * a later compaction of this log, or to a disconnect ({@link #close}), releases nothing and
     * appends nothing.
     */
    public void finishCompaction(long generation) {
        if (generation != compactionGeneration || compactingDir == null) {
            return;
        }
        compactingDir = null;
        if (deferred.isEmpty()) {
            return;
        }
        List<ScannedContainer> held = List.copyOf(deferred);
        deferred.clear();
        ContainerJsonlWriter target = writerFor();
        if (target == null) {
            HoardkeeperMod.LOGGER.warn("Lost {} observation(s) held behind a log compaction", held.size());
            return;
        }
        // These rows were already marked dirty once, back when append() first deferred them — but
        // that marked a generation an upload's read may have started from BEFORE this flush
        // physically lands them in the file. Marking again, now, is what an upload racing this
        // exact moment needs to see: its own generation snapshot, taken before this line runs,
        // will no longer match, so it will not write a stale "confirmed clean" over rows that
        // just arrived. Calling target.append(...) directly here (not this.append) is deliberate --
        // these are not new observations, so nothing about PassiveObserver's own bookkeeping should
        // move -- but the dirty mark itself must still happen.
        markDirty();
        for (ScannedContainer row : held) {
            target.append(row);
        }
    }

    /**
     * Rewrites {@code containers.jsonl} keeping the newest row per position, on the maintenance
     * thread. Runs only when the watermark sits at one of its two ends — see
     * {@link ObservationLog#mayCompact}.
     */
    private void compact(int maxRows) {
        Path file = ObservationLogFiles.containers(dir);
        if (!Files.isRegularFile(file)) {
            return;
        }
        ObservationLogState state = readState();
        List<ScannedContainer> rows = ScanStorage.readContainers(file);

        // The uploader (spec §5) may be reading and re-watermarking this exact log right now -- a
        // relog while a previous debounce is still in flight, for instance. Reading its flag here,
        // rather than keeping a second notion of "is an upload happening", is what spec §4.4 means
        // by the two never disagreeing about how many rows the file has.
        if (!ObservationLog.mayCompact(rows.size(), state.uploadedRows, isUploadInFlight())) {
            HoardkeeperMod.LOGGER.debug("Not compacting {}: {} of {} rows uploaded",
                    file, state.uploadedRows, rows.size());
            return;
        }

        List<ScannedContainer> kept = ObservationLog.compact(rows, maxRows);
        if (kept.size() == rows.size()) {
            // Nothing was superseded and nothing hit the cap, so the file already is what a
            // rewrite would produce.
            return;
        }
        if (!rewrite(kept)) {
            return;
        }
        state.uploadedRows = ObservationLog.watermarkAfterCompaction(state.uploadedRows, kept.size());
        writeState(state);
        HoardkeeperMod.LOGGER.debug("Compacted {} from {} to {} row(s)", file, rows.size(), kept.size());
    }

    /**
     * Publishes {@code rows} over {@code containers.jsonl} through a sibling {@code .tmp} and a
     * rename, so the log is always either the file that was there or the compacted one — never a
     * half-written mix.
     */
    private boolean rewrite(List<ScannedContainer> rows) {
        Path file = ObservationLogFiles.containers(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(tmp)) {
                for (ScannedContainer row : rows) {
                    out.write(GSON.toJson(row));
                    out.write("\n");
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            HoardkeeperMod.LOGGER.warn("Failed to compact {}", file, e);
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // Best-effort cleanup; a leftover .tmp is harmless and the next compaction
                // overwrites it.
            }
            return false;
        }
    }

    // =========================================================================
    // State (log.json)
    // =========================================================================

    /** Writes {@code log.json} for a log that has none yet, so it carries its identity from row one. */
    private void ensureState() {
        if (Files.isRegularFile(ObservationLogFiles.state(dir))) {
            return;
        }
        writeState(new ObservationLogState());
    }

    /**
     * Reads {@code log.json}. A file that is missing, empty or unparseable reads as "never
     * uploaded" (spec §10): everything re-uploads, which the server's upsert makes harmless, and
     * the rows themselves are never deleted on the strength of a file nobody could read.
     */
    private ObservationLogState readState() {
        Path file = ObservationLogFiles.state(dir);
        if (Files.isRegularFile(file)) {
            try {
                ObservationLogState state = GSON.fromJson(Files.readString(file), ObservationLogState.class);
                if (state != null) {
                    return state;
                }
            } catch (Exception e) {
                HoardkeeperMod.LOGGER.warn("Could not read {} — treating the log as never uploaded",
                        file, e);
            }
        }
        return new ObservationLogState();
    }

    private void writeState(ObservationLogState state) {
        state.server = server;
        state.realm = realm;
        state.dimension = dimension;
        state.updatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        AtomicJson.write(ObservationLogFiles.state(dir), state);
    }

    // =========================================================================
    // The watermark: this store's dirty/clean generation and in-flight flag
    // =========================================================================

    /**
     * Marks this log as possibly having something pending again: bumps its dirty generation.
     * Called by {@link #append} whenever a row lands — including one only held in
     * {@link #deferred} — and again by {@link #finishCompaction} when such a held row is actually
     * flushed, so {@code ObservationUploader}'s next tick's check actually looks rather than
     * trusting a stale "nothing pending" answer, and so an upload racing either moment can tell it
     * happened (see {@code ObservationUploader.stillClean}).
     */
    private void markDirty() {
        dirtyGeneration++;
    }

    /**
     * This log's current dirty generation — see {@link #markDirty}. Package-private: only
     * {@code ObservationUploader} reads it, to compare against the generation it last confirmed
     * clean.
     */
    long currentGeneration() {
        return dirtyGeneration;
    }

    /**
     * Whether this log's files are being read and re-watermarked by an upload right now.
     * {@link #compact} calls this instead of inventing a second scheme — spec §4.4's whole point is
     * that the two must never disagree about how many rows the file has.
     */
    boolean isUploadInFlight() {
        return uploadInFlight.get();
    }

    /**
     * Claims the in-flight flag for an upload attempt about to start, returning {@code false} if
     * another attempt already holds it — {@code ObservationUploader.onClientTick} bails out on that,
     * rather than starting a second read-through-write span for the same log.
     */
    boolean tryMarkUploadInFlight() {
        return uploadInFlight.compareAndSet(false, true);
    }

    /**
     * Releases the in-flight flag. Called only once an upload attempt is completely finished,
     * successfully or not — never on any earlier return — from inside the maintenance task itself,
     * which is why the flag is an {@link AtomicBoolean} rather than a plain field: this call and
     * {@link #tryMarkUploadInFlight} run on different threads.
     */
    void clearUploadInFlight() {
        uploadInFlight.set(false);
    }

    // =========================================================================
    // Background work
    // =========================================================================

    /**
     * Runs {@code task} on the background thread, after every maintenance task submitted before it
     * for this log. One chain rather than one thread: two tasks writing {@code log.json} for this
     * log at once would be a lost update, and a task that throws must not stop the next one — so
     * each is wrapped.
     *
     * <p>Package-private, not private: {@code ObservationUploader} calls this directly to chain an
     * upload's entire read-through-write span onto this exact maintenance sequence, the same one
     * {@link #compact} runs on. That is what makes the two structurally unable to overlap for this
     * log — whichever was submitted first simply runs to completion before the other starts —
     * rather than relying only on a flag that has to be set in time to matter (spec §4.4). The
     * in-flight flag stays as a second, independent guard rather than being removed now that it is
     * redundant for this one call site; nothing about it assumes this stays the only caller.
     */
    CompletableFuture<Void> submit(Runnable task) {
        maintenance = maintenance
                .handle((ignored, error) -> null)
                .thenRunAsync(() -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        HoardkeeperMod.LOGGER.warn("Observation log maintenance failed", e);
                    }
                }, MAINTENANCE_EXECUTOR);
        return maintenance;
    }

    // =========================================================================
    // Test seams
    // =========================================================================

    /**
     * Blocks until every observation queued so far has actually reached this log's files. Test seam
     * only: production code never needs this, since every public method here is already either
     * synchronous (reads) or explicitly asynchronous by design (appends, compaction).
     *
     * <p>Two queues, both drained, because an append reaches the file two different ways depending
     * on what it landed in: a normal {@link #append} queues onto the open writer's own executor
     * ({@link #flushWriter}), while {@link #ensureState} and {@link #compact} run on this store's
     * {@link #maintenance} chain instead. A row {@link #finishCompaction} flushes out of
     * {@link #deferred} goes through the writer too, so flushing it first and draining the
     * maintenance chain second — the order does not matter, since neither chain feeds the other —
     * still leaves both empty by the time this returns.
     */
    public void awaitPendingWritesForTests() {
        flushWriter();
        try {
            submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            HoardkeeperMod.LOGGER.error("Failed to drain the observation log maintenance chain", e);
        }
    }

    /**
     * Closes this store: the writer, if open, and anything held behind an in-progress compaction of
     * this log. Called on disconnect, since the next connection may be another server entirely and
     * nothing held here is worth keeping across it. Idempotent — closing twice does not throw, and a
     * store used again afterward simply reopens its writer on the next {@link #append}, exactly as
     * a store that has never been closed would on its first.
     */
    public void close() {
        closeWriter();
        compactingDir = null;
        if (!deferred.isEmpty()) {
            HoardkeeperMod.LOGGER.debug("Dropping {} observation(s) held behind a log compaction",
                    deferred.size());
            deferred.clear();
        }
    }
}
