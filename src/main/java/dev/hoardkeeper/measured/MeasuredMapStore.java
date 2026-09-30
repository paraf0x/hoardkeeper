package dev.hoardkeeper.measured;

import com.google.gson.Gson;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.store.AtomicJson;
import dev.hoardkeeper.store.ScanStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The measured map on disk: read it, project a scan onto it, append a content update, compact it.
 * Design spec §3.2 and §4.4.
 *
 * <p><b>Two write paths, deliberately different.</b> A scan projection is the only operation that
 * removes rows, so it rewrites the whole file and publishes by rename — a crash mid-projection
 * leaves the previous map intact and the session still on disk to project again. A content update
 * appends one line, because a chest opened by hand must not cost a rewrite of a file holding
 * thousands of rows.
 *
 * <p><b>Compaction is unconditional</b>, unlike {@code ObservationLog.mayCompact}. That refuses
 * unless the upload watermark sits at one of its ends, because reordering rows under a row-count
 * watermark would strand rows that were never sent. This map has no watermark and is never
 * uploaded — nothing reads it by row index — so it may be folded down whenever it has grown.
 *
 * <p><b>Every write to {@code containers.jsonl} — from either path — runs on one background daemon
 * thread</b>, {@link #WRITE_EXECUTOR}, so the two paths above can never interleave with each other:
 * a scan's rewrite-and-rename can never land mid-append, and an append can never land in a file a
 * rewrite is about to replace out from under it.
 * <ul>
 *   <li>{@link #appendContentUpdate} is reached from {@code PassiveObserver.append}, which runs on
 *       Minecraft's client thread as part of closing a chest's screen — exactly the path spec §6
 *       promises stays free of disk I/O. So, the same way {@code ContainerJsonlWriter} keeps the
 *       observation log off that thread, it submits its write (and the compaction it may trigger,
 *       from inside that same task rather than a second one) and returns immediately; a failure is
 *       logged where it happens and never surfaces anywhere else, since nothing ever reads the
 *       {@link Future} the submit produces.
 *   <li>{@link #projectScan} runs once, at scan end, not on the chest-close path — its caller
 *       depends on the map being current the instant the call returns (spec §6's "the search is
 *       correct immediately"). So it also submits its whole body to {@link #WRITE_EXECUTOR}, for
 *       the ordering guarantee above, but then blocks on the resulting {@link Future} (bounded, like
 *       {@code SessionStateStore.flush}) rather than returning early — a synchronous caller on an
 *       asynchronous executor, deliberately, because the property that matters here is "serialised
 *       with every append", not "off the caller's thread".
 * </ul>
 *
 * <p>A {@code MeasuredMapStore} is otherwise a stateless wrapper created fresh per call (see
 * {@code MeasuredMapProjector.storeFor}), so {@link #WRITE_EXECUTOR} is a static, class-wide daemon
 * shared by every instance rather than one spun up — and leaked — per chest closed or scan ended.
 */
public final class MeasuredMapStore {

    private static final Gson GSON = new Gson();

    /**
     * The single background thread every {@code MeasuredMapStore} instance writes
     * {@code containers.jsonl} through. Daemon and shared, not per-instance: instances are created
     * ad hoc (one per {@code storeFor} call), so an instance-owned executor would start a new
     * unshut-down thread on every observation instead of reusing one for the life of the client.
     */
    private static final ExecutorService WRITE_EXECUTOR =
            Executors.newSingleThreadExecutor(daemonThreadFactory("hoardkeeper-measured-map"));

    /** Bound on {@link #projectScan}'s and {@link #awaitPendingWritesForTests}'s wait for the executor. */
    private static final long AWAIT_SECONDS = 10;

    /**
     * Rows beyond one per position past which an append folds the file down. The surplus, not the
     * total: compaction can only remove duplicates, so a map of 10 000 distinct containers has
     * nothing to gain from it and must not be rewritten on every observation — the cost spec §3.2
     * exists to prevent.
     */
    private static final int COMPACT_SURPLUS_ROWS = 2000;

    /** Whether a file of {@code rawRows} lines holding {@code distinctRows} positions is worth folding. */
    static boolean shouldCompact(int rawRows, int distinctRows) {
        return rawRows - distinctRows >= COMPACT_SURPLUS_ROWS;
    }

    /**
     * The size and modification time each map's {@code containers.jsonl} had immediately after this
     * client's own last <em>content-update append</em> to it — the one write to the file that spec
     * §4.2 guarantees changes no position, only the contents at one.
     *
     * <p>Exists for {@link dev.hoardkeeper.observe.RegisteredContainerIndex}, which caches the
     * map's positions against exactly that pair. Every observation appends here, so without this
     * every chest closed would invalidate the cache the next one needs and put a full parse of the
     * whole map on the client thread — the cost spec §6 promises will not happen. A stat that
     * matches an entry below is one this client produced by appending, and the positions behind it
     * are therefore unchanged; anything else — a scan's rewrite, a compaction, another client, a
     * hand edit — does not match and is re-parsed as before.
     *
     * <p>One tiny entry per map directory visited, written on the writer thread and read on the
     * client thread, hence a concurrent map. Never cleared: a client sees a handful of maps in its
     * lifetime, and a stale entry can only ever be a stat that will never occur again.
     */
    private static final Map<Path, long[]> LAST_OWN_APPEND = new ConcurrentHashMap<>();

    /**
     * Whether {@code containers.jsonl} being at exactly {@code size}/{@code modified} is the result
     * of one of this client's own content-update appends. See {@link #LAST_OWN_APPEND}.
     */
    public static boolean isOwnContentUpdate(Path containers, long size, long modified) {
        long[] stat = LAST_OWN_APPEND.get(containers);
        return stat != null && stat[0] == size && stat[1] == modified;
    }

    private final Path dir;

    public MeasuredMapStore(Path dir) {
        this.dir = dir;
    }

    /** Every row in the file, newest per position already applied. Never throws. */
    public List<ScannedContainer> rows() {
        List<ScannedContainer> raw = ScanStorage.readContainers(MeasuredMapFiles.containers(dir));
        // Folding on read is what lets an append be one line: the file may hold several rows for a
        // position until the next compaction, and only the newest of them is the map.
        return MeasuredMap.project(List.of(), raw, null, false, MeasuredMap.Source.SCAN);
    }

    /** The swept areas, or an empty list when there is no readable state (spec §11). */
    public List<ScanArea> areas() {
        List<ScanArea> areas = new ArrayList<>();
        for (MeasuredMapState.Area area : state().areas) {
            areas.add(ScanArea.fromSnapshot(area.areaMode, area.origin, area.radius, area.chunkRadius));
        }
        return areas;
    }

    /**
     * One swept area, paired with when it was last measured — the half {@link #areas()} throws away.
     * {@code measuredAt} is written only by {@link #recordArea}, itself called only from
     * {@link #projectScanNow} — i.e. only by an actual scan projection, never by an observation. See
     * {@code ScanController.checkRescanNudge}, the caller this exists for: "when was this storage last
     * measured" has to come from here, not from a row, because {@code PassiveObserver} keeps rows'
     * {@code scannedAt} fresh on every hand-opened chest and this must not.
     */
    public record MeasuredArea(ScanArea area, String measuredAt) {
    }

    /**
     * {@link #areas()}, paired with each one's {@link MeasuredMapState.Area#measuredAt} rather than
     * discarding it. Same source, same "no readable state" fallback (spec §11) — an empty list, never
     * a throw.
     */
    public List<MeasuredArea> measuredAreas() {
        List<MeasuredArea> areas = new ArrayList<>();
        for (MeasuredMapState.Area area : state().areas) {
            areas.add(new MeasuredArea(
                    ScanArea.fromSnapshot(area.areaMode, area.origin, area.radius, area.chunkRadius),
                    area.measuredAt));
        }
        return areas;
    }

    /** Whether this session has already been folded in — see {@code projectedSessions}. */
    public boolean hasProjected(String sessionId) {
        return sessionId != null && state().projectedSessions.contains(sessionId);
    }

    /**
     * Projects one scan and rewrites the map, then blocks (up to {@value #AWAIT_SECONDS} seconds)
     * until that has actually happened on {@link #WRITE_EXECUTOR}, so the caller's own synchronous
     * contract holds: {@code ScanController.stop} needs the map current the moment this returns.
     * Submitting the whole body to the same executor {@link #appendContentUpdate} uses — rather than
     * running it on the caller's thread directly — is what keeps this rewrite from ever landing at
     * the same instant as a content update's append; see the class javadoc.
     *
     * <p>Returns whether it was written; a failure, including a timed-out wait, keeps the previous
     * map and the session, and says so at WARN. A timeout is swallowed here exactly like any other
     * failure — the scan this is called from still ends normally either way, only the map does not
     * pick up its rows until whatever is ahead of this in the queue clears.
     */
    public boolean projectScan(List<ScannedContainer> rows, ScanArea area, boolean complete,
                               String sessionId, String server, String realm, String dimension) {
        try {
            Future<Boolean> future = WRITE_EXECUTOR.submit(
                    () -> projectScanNow(rows, area, complete, sessionId, server, realm, dimension));
            return future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            HoardkeeperMod.LOGGER.warn("Timed out projecting a scan onto the measured map at {}", dir, e);
            return false;
        }
    }

    /** The actual work {@link #projectScan} submits to {@link #WRITE_EXECUTOR} and waits on. */
    private boolean projectScanNow(List<ScannedContainer> rows, ScanArea area, boolean complete,
                                    String sessionId, String server, String realm, String dimension) {
        List<ScannedContainer> projected =
                MeasuredMap.project(rows(), rows, area, complete, MeasuredMap.Source.SCAN);
        if (!writeRows(projected)) {
            return false;
        }

        MeasuredMapState state = state();
        state.server = server;
        state.realm = realm;
        state.dimension = dimension;
        if (area != null) {
            recordArea(state.areas, area, complete);
        }
        if (sessionId != null && !state.projectedSessions.contains(sessionId)) {
            state.projectedSessions.add(sessionId);
        }
        state.updatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        return AtomicJson.write(MeasuredMapFiles.state(dir), state);
    }

    /**
     * Records that {@code area} has been swept, per spec §3.3: <b>merged</b> into an entry of
     * identical geometry, and appended otherwise.
     *
     * <p>Appending unconditionally would be one entry per scan, and re-scanning one room is the
     * ordinary case — every run of it would add a line to {@code measured.json}, which
     * {@link StorageClusters} walks pairwise and {@code PassiveObserver} re-reads twice a second
     * while a player sorts chests. Geometry, not the timestamp, is the identity: an entry stands for
     * one measured place, and {@code measuredAt}/{@code complete} describe the latest sweep of it —
     * a "complete" left over from a sweep that has since been re-run and abandoned would be a claim
     * about the map that is no longer true.
     *
     * <p>Package-private and static for the same reason {@link #shouldCompact} is: it is a pure
     * decision over plain data, and its test should not need a file.
     */
    static void recordArea(List<MeasuredMapState.Area> areas, ScanArea area, boolean complete) {
        String measuredAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        for (MeasuredMapState.Area existing : areas) {
            if (sameGeometry(existing, area)) {
                existing.measuredAt = measuredAt;
                existing.complete = complete;
                return;
            }
        }
        MeasuredMapState.Area recorded = new MeasuredMapState.Area();
        recorded.areaMode = area.mode().name();
        recorded.origin = new int[]{area.originX(), 0, area.originZ()};
        recorded.radius = area.radius();
        recorded.chunkRadius = area.chunkRadius();
        recorded.measuredAt = measuredAt;
        recorded.complete = complete;
        areas.add(recorded);
    }

    /**
     * Whether a recorded entry covers exactly the ground {@code area} does. Compared through
     * {@link ScanArea#fromSnapshot} rather than field by field, so an entry written by an older
     * version — a {@code null} {@code areaMode}, say — is judged by the area it actually rebuilds
     * into, which is the only thing anything else reads it as.
     */
    private static boolean sameGeometry(MeasuredMapState.Area recorded, ScanArea area) {
        return area.equals(ScanArea.fromSnapshot(recorded.areaMode, recorded.origin,
                recorded.radius, recorded.chunkRadius));
    }

    /**
     * Queues one content update to be appended on the background writer thread and returns
     * immediately. The caller has already established that the map holds this position —
     * {@code ObservationGate} refuses anything else — so the append cannot create a container,
     * which is spec §4.2's rule enforced one layer up.
     *
     * <p>A write that fails is logged at WARN from inside the background task and swallowed there;
     * there is no result for the caller to check; see the class javadoc for why.
     */
    public void appendContentUpdate(ScannedContainer row) {
        WRITE_EXECUTOR.execute(() -> {
            Path containers = MeasuredMapFiles.containers(dir);
            try {
                Files.createDirectories(dir);
                Files.writeString(containers, GSON.toJson(row) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LAST_OWN_APPEND.remove(containers);
                HoardkeeperMod.LOGGER.warn("Could not append a content update to {}", dir, e);
                return;
            }
            noteOwnAppend(containers);
            compactIfGrown();
        });
    }

    /**
     * Records what the file looks like now that this append has landed — after the write and before
     * any compaction, the only moment at which the pair describes what an append left behind. A
     * stat that cannot be read simply drops the note and the reader re-parses, which is what it did
     * before this existed; it is not a reason to report the append itself as failed.
     */
    private static void noteOwnAppend(Path containers) {
        try {
            LAST_OWN_APPEND.put(containers, new long[]{Files.size(containers),
                    Files.getLastModifiedTime(containers).toMillis()});
        } catch (IOException e) {
            LAST_OWN_APPEND.remove(containers);
            HoardkeeperMod.LOGGER.debug("Could not stat {} after appending to it", containers, e);
        }
    }

    /**
     * Folds appended rows down to one per position once the file has grown past the threshold.
     * Only ever called from inside the task {@link #appendContentUpdate} submits to
     * {@link #WRITE_EXECUTOR} — never on the caller's thread — so a compaction can never interleave
     * with the append that triggered it, or with any other append to this file.
     */
    private void compactIfGrown() {
        List<ScannedContainer> raw =
                ScanStorage.readContainers(MeasuredMapFiles.containers(dir));
        List<ScannedContainer> folded = MeasuredMap.project(List.of(), raw, null, false, MeasuredMap.Source.SCAN);
        if (shouldCompact(raw.size(), folded.size())) {
            writeRows(folded);
        }
    }

    private boolean writeRows(List<ScannedContainer> rows) {
        StringBuilder out = new StringBuilder();
        for (ScannedContainer row : rows) {
            out.append(GSON.toJson(row)).append('\n');
        }
        Path file = MeasuredMapFiles.containers(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        // A rewrite is the one write that may remove positions, so whatever an earlier append noted
        // about this file stops being true here — dropped before the rename rather than after, so a
        // reader can never see the new file next to the old note.
        LAST_OWN_APPEND.remove(file);
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            HoardkeeperMod.LOGGER.warn("Could not write the measured map at {}", dir, e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Best-effort cleanup; do not mask the original failure
            }
            return false;
        }
    }

    /** Reads {@code measured.json}; anything unreadable is "nothing measured yet" (spec §11). */
    public MeasuredMapState state() {
        Path file = MeasuredMapFiles.state(dir);
        if (Files.isRegularFile(file)) {
            try {
                MeasuredMapState state = GSON.fromJson(Files.readString(file), MeasuredMapState.class);
                if (state != null) {
                    if (state.areas == null) {
                        state.areas = new ArrayList<>();
                    }
                    if (state.projectedSessions == null) {
                        state.projectedSessions = new ArrayList<>();
                    }
                    return state;
                }
            } catch (Exception e) {
                HoardkeeperMod.LOGGER.warn("Could not read {} — treating the map as unmeasured",
                        file, e);
            }
        }
        return new MeasuredMapState();
    }

    /**
     * Test seam: blocks (up to {@value #AWAIT_SECONDS} seconds) until every
     * {@link #appendContentUpdate} submitted so far — across every {@code MeasuredMapStore}
     * instance, since they share one writer thread — has finished. No production caller needs this:
     * the whole point of {@link #appendContentUpdate} is that it does not block, and the map is read
     * back through {@link #rows()} on its own schedule, never synchronously after an append.
     * {@link #projectScan} needs no equivalent — it already waits.
     *
     * <p>Public rather than package-private because {@code RegisteredContainerIndexTest} needs it
     * too: what it measures is precisely that an append this class made does not cost that index a
     * re-parse, which it cannot assert until the append has physically happened.
     */
    public static void awaitPendingWritesForTests() {
        try {
            WRITE_EXECUTOR.submit(() -> {
            }).get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            HoardkeeperMod.LOGGER.error("Failed to drain the measured map writer", e);
        }
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
