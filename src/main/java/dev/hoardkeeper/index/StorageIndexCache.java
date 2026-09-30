package dev.hoardkeeper.index;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.measured.MeasuredMapFiles;
import dev.hoardkeeper.measured.MeasuredMapProjector;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.measured.StorageClusters;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.scan.ScanArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The measured map, read once and cached for whoever needs the storage the player is standing in
 * right now. Design spec §4.3, §4.4.
 *
 * <p><b>Lifted out of {@code SearchService}</b>, which used to be the map's only reader: it stats
 * {@code containers.jsonl}, parses it on a background thread when the size or modification time
 * changed, and inverts the storage cluster the player stands in into an index. That exact stat, that
 * exact cache key, the 500&nbsp;ms recheck window, the cluster comparison and the background parse
 * all move here unchanged — the only two things that differ from what {@code SearchService} used to
 * do itself are that the inversion now builds a {@link StorageIndex} (both directions) instead of a
 * bare {@code SearchIndex}, and that the cache is reachable by more than one caller. A tooltip line
 * and a peek card are about to want the same rows read the other way (position → contents), and
 * building a second cache over the same file for them would mean two policies deciding when to
 * re-read one thing. This class is now the one place that decides that; {@code SearchService}
 * becomes its first consumer rather than its owner, and its tests staying green unedited is the
 * proof this was a move.
 *
 * <p><b>Four ways in, matched to who is asking and how often.</b> {@link #tick} is the staleness
 * check — stat the file and, if it changed, hand a background thread the parse — called once per
 * client tick, which is where that check belongs now that something other than a keystroke or a
 * typed command has to trigger it. {@link #current} is the entire cost the render path pays: a
 * lookup, never a stat, never a {@code join}, never a parse. A tooltip or a peek card asking before
 * anything has been built yet gets {@code null} back, not a wait. {@link #indexFuture} and
 * {@link #indexFutureNow} are the other two, and the reason there are two rather than one is a
 * lesson learned the hard way (see below): tab completion asks ten times a second and must stay
 * behind the 500&nbsp;ms window; a search asks once and must see the file as of that ask, not as of
 * whenever the window last happened to open.
 *
 * <p><b>Why the window has two different meanings depending on who drives the check, and why that
 * needed two methods instead of one.</b> Before this class existed, {@code SearchService.index}
 * ran the same stat-and-window logic below, but nothing ever called it except a demand — a typed
 * command or a tab-completion keystroke. That made the window purely a burst filter: it only ever
 * mattered for calls close together in time, which only ever happened when the player was typing,
 * and any call arriving after a real gap started from a cold or long-stale cache and got a genuinely
 * fresh stat. Once {@link #tick} started calling the same check on its own, twenty times a second,
 * the cache is warm essentially all the time, and the window's clock is now driven by that
 * unrelated ticker rather than by anything the player did. A search that happens to land inside the
 * ticker's current 500&nbsp;ms window — which is likely, not rare, when the search follows a file
 * change by less than half a second, exactly what a scan finishing and immediately being searched
 * does — would silently get a snapshot from before the change, with no retry to correct it. Tab
 * completion asking through the same throttled path is exactly what the window is for and must stay
 * that way. So the check gained a {@code force} flag: {@link #tick} and {@link #indexFuture} keep
 * asking for the throttled read tab completion needs; {@link #indexFutureNow}, which only
 * {@code SearchService}'s actual search path calls, asks for the unconditional one — the same
 * guarantee a demand call always had before anything else was polling this cache.
 *
 * <p><b>Client thread only</b>, exactly like {@code SearchService} and {@code ScanController} —
 * every entry point below runs on it. The one exception is the background parse itself, which comes
 * back through the {@link CompletableFuture} rather than ever touching a field from another thread.
 */
public final class StorageIndexCache {

    /**
     * How long the index cache may go without re-checking the file behind it. Tab completion asks
     * on every keystroke, and answering each one by listing the server's session directory and
     * parsing every {@code session.json} in it would put a directory walk on the client thread per
     * character typed. Half a second is far below human typing granularity and far above the cost
     * of the check.
     */
    private static final long INDEX_RECHECK_MILLIS = 500;

    private static final StorageIndexCache INSTANCE = new StorageIndexCache();

    public static StorageIndexCache get() {
        return INSTANCE;
    }

    private StorageIndexCache() {
    }

    /**
     * One dimension's measured map as it was last read: every row and every swept area, with no
     * position baked into it. Which of those rows a caller can see is decided per query, from where
     * the player is standing at that moment — see {@link #indexFuture}, {@link #indexFutureNow} and
     * {@link #current}.
     */
    private record Measured(List<ScannedContainer> rows, List<ScanArea> areas) {
    }

    // ---- Map-read cache. Keyed on the measured map file's size and modification time, so either a
    // ---- scan just projected or a chest closed a second ago -- both of which touch that one file --
    // ---- re-reads. Deliberately holds nothing about the player: see indexFuture(), indexFutureNow()
    // ---- and current().
    private CompletableFuture<Measured> measured;
    private Path indexedFile;
    private long indexedSize;
    private long indexedModified;
    private long lastCheckedMillis;

    // ---- The last cluster's inverted index, so that asking twice from the same spot costs one
    // ---- inversion. Client thread only: written and read from indexFuture(), indexFutureNow() and
    // ---- current(), and only on the branch where the map read has already finished. ----
    private Measured viewOf;
    private List<ScanArea> viewCluster;
    private StorageIndex view;

    // =========================================================================
    // Entry points
    // =========================================================================

    /**
     * The staleness check — call once per client tick, before anything that reads {@link #current}
     * this same tick needs a fresh answer. Does the file stat (inside its own 500&nbsp;ms window, so
     * a tick every 50&nbsp;ms does not turn into twenty stats a second) and, when the file changed,
     * starts the background parse; never blocks and never itself builds a {@link StorageIndex}.
     */
    public void tick(Minecraft mc) {
        map(mc, false);
    }

    /**
     * The built index for the storage the player is standing in right now, or {@code null} — never
     * anything else — for a tooltip or a peek card to read on the render path.
     *
     * <p><b>Never blocks.</b> No {@code join}, no {@code get}, no file operation: only a peek at a
     * future that {@link #tick} is responsible for keeping fresh, and the same in-memory cluster
     * comparison {@link #indexFuture} uses.
     *
     * <p>{@code null} in exactly three cases, all of which mean "say nothing" to a caller that draws
     * every frame: the background parse is still running, the player stands in no measured area at
     * all, and there is no measured map on disk for this client to read. Unlike {@link #indexFuture},
     * which the search uses and which falls back to the whole dimension outside a measured area
     * (design spec §2), this method never does — silence is the right answer for a line or a card
     * that appears without being asked for.
     */
    public StorageIndex current(Minecraft mc) {
        LocalPlayer player = mc == null ? null : mc.player;
        if (player == null || measured == null || measured.isCompletedExceptionally()) {
            return null;
        }
        Measured read = measured.getNow(null);
        if (read == null) {
            // The background parse has not finished yet. Say nothing this frame rather than wait.
            return null;
        }
        BlockPos pos = player.blockPosition();
        List<ScanArea> cluster = StorageClusters.covering(read.areas(), pos.getX(), pos.getZ());
        if (cluster.isEmpty()) {
            // Outside every measured area. The search falls back to the whole dimension here; a
            // tooltip or a peek card must not -- see the class javadoc and design spec §2 and §3.
            return null;
        }
        return viewFor(read, pos.getX(), pos.getZ());
    }

    /**
     * The index over the measured map for the storage the player is standing in right now (design
     * spec §5.1), for tab completion — {@code SearchSuggestions} is this method's one caller, asking
     * once per keystroke.
     *
     * <p><b>Throttled by the same 500&nbsp;ms window {@link #tick} uses.</b> That is exactly right
     * for a caller firing ten times a second: reusing the last-known answer between keystrokes is
     * the entire reason the window exists (see the class javadoc). It is exactly wrong for a caller
     * that asks once and needs the file as of that ask — see {@link #indexFutureNow} for that case,
     * used by the search itself and nothing else.
     *
     * <p><b>Two stages, and the split is the point.</b> Reading and parsing the map is expensive and
     * is cached, keyed on the map file's path, size and modification time — a scan projecting onto
     * the map (a rewrite) and a chest closed a second ago (spec §6, an append) are the same file
     * changing, so one freshness check catches both. Choosing which of those rows the player can see
     * is cheap — a coordinate test over a list already in memory — and is redone on every call, from
     * the position the player is standing at when they ask.
     *
     * <p><b>The position must not go into the cache key.</b> The map is one file per dimension, so
     * walking from a base to a mine changes nothing about it: a cached index built at the base would
     * be handed back at the mine and would light containers hundreds of blocks away. The cache cannot
     * notice, because nothing it is keyed on has moved.
     *
     * <p>{@code null} when there is no map for this client to read at all — no level ({@code null}
     * from {@link MeasuredMapProjector#dirFor}) or no player to take a position from.
     */
    public CompletableFuture<StorageIndex> indexFuture(Minecraft mc) {
        return indexFuture(mc, false);
    }

    /**
     * {@link #indexFuture}'s exact counterpart for a caller that asks once and needs the answer to
     * reflect the file as of this call, not as of whenever {@link #tick}'s own 500&nbsp;ms window
     * last happened to open — {@code SearchService}'s search path is this method's one caller.
     *
     * <p><b>Why this exists as a second method rather than a flag on the caller's side.</b> Before
     * {@link #tick} started polling this cache on its own, a demand call was always either the first
     * one in a long while (a cold, un-throttled read) or one of a keystroke burst sharing the window
     * on purpose. Now the cache is warm essentially all the time because of {@link #tick}, so a
     * search sharing {@link #indexFuture}'s window would inherit staleness from an unrelated ticker
     * rather than from anything the player did — exactly the failure this method exists to rule out.
     * It does precisely what {@link #indexFuture} does, with one difference: the file stat underneath
     * it is never skipped by the window.
     */
    public CompletableFuture<StorageIndex> indexFutureNow(Minecraft mc) {
        return indexFuture(mc, true);
    }

    private CompletableFuture<StorageIndex> indexFuture(Minecraft mc, boolean force) {
        LocalPlayer player = mc == null ? null : mc.player;
        if (player == null) {
            return null;
        }
        CompletableFuture<Measured> map = map(mc, force);
        if (map == null) {
            return null;
        }
        // Read here, on the client thread, and carried as plain ints: nothing Minecraft-typed may
        // cross into the continuation below — the rule ObservationUploader states for itself.
        BlockPos playerPos = player.blockPosition();
        int playerX = playerPos.getX();
        int playerZ = playerPos.getZ();
        if (map.isDone() && !map.isCompletedExceptionally()) {
            // Already read, so the answer is due now rather than a frame later. Handing it back
            // through the same future type keeps one code path for both cases, and the caller's
            // isDone() check is what decides whether the player is told anything is being read.
            return CompletableFuture.completedFuture(viewFor(map.join(), playerX, playerZ));
        }
        return map.thenApply(read -> StorageIndex.of(
                StorageClusters.rowsCovering(read.rows(), read.areas(), playerX, playerZ)));
    }

    /**
     * Drops the cached read and the cached cluster view — called from {@code SearchService.onDisconnect}:
     * a cached map read belongs to one server, exactly like the highlight coordinates that live beside
     * it there, and neither may survive a disconnect. {@code indexedSize} and {@code indexedModified}
     * are deliberately left as they were: {@code indexedFile} going to {@code null} already forces the
     * next {@link #map} to treat the next dimension's map as unread, since the cache-hit check there
     * requires {@code file.equals(indexedFile)} to pass before it ever compares size or modified time.
     */
    public void onDisconnect() {
        measured = null;
        indexedFile = null;
        lastCheckedMillis = 0;
        viewOf = null;
        viewCluster = null;
        view = null;
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * The index over the storage covering {@code (playerX, playerZ)}, reusing the last one when the
     * player is still in the same storage and the map has not been re-read.
     *
     * <p>The cluster itself is recomputed every call — that is the coordinate test that has to stay
     * current, and it is a walk of a few dozen areas. What is kept is only the <em>inversion</em>
     * behind it, which is proportional to the containers in the storage and which tab completion
     * would otherwise redo on every keystroke. Reading the map is what
     * {@link CompletableFuture#supplyAsync} keeps off this thread; this is what keeps the work that
     * remains on it from being paid for more than once per storage.
     */
    private StorageIndex viewFor(Measured read, int playerX, int playerZ) {
        List<ScanArea> cluster = StorageClusters.covering(read.areas(), playerX, playerZ);
        if (view == null || viewOf != read || !cluster.equals(viewCluster)) {
            viewOf = read;
            viewCluster = cluster;
            view = StorageIndex.of(StorageClusters.rowsCovering(read.rows(), read.areas(), playerX, playerZ));
        }
        return view;
    }

    /**
     * The dimension's whole measured map, re-read whenever the file behind it changes — which is how
     * a search run right after a scan sees the containers that scan just projected, and how searching
     * twice in a row costs one read.
     *
     * <p>{@code force} skips the 500&nbsp;ms window's shortcut below and always stats the file, for
     * {@link #indexFutureNow}'s sake — see its javadoc and the class javadoc for why the window
     * cannot be trusted to speak for a caller that only asks once. {@link #tick} and
     * {@link #indexFuture} both pass {@code false}: the window is exactly what keeps a client tick or
     * a keystroke from statting the file more than twice a second.
     */
    private CompletableFuture<Measured> map(Minecraft mc, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && measured != null && now - lastCheckedMillis < INDEX_RECHECK_MILLIS) {
            return measured;
        }
        lastCheckedMillis = now;

        Path mapDir = MeasuredMapProjector.dirFor(mc);
        if (mapDir == null) {
            return null;
        }
        Path file = MeasuredMapFiles.containers(mapDir);
        long size = 0;
        long modified = 0;
        try {
            if (Files.isRegularFile(file)) {
                size = Files.size(file);
                modified = Files.getLastModifiedTime(file).toMillis();
            }
        } catch (IOException e) {
            // A file we cannot stat is one we will simply re-read: treating this as fatal would turn
            // a transient filesystem hiccup into "this dimension has no measured map".
            HoardkeeperMod.LOGGER.warn("Could not stat {} for the search index", file, e);
        }

        if (measured != null && file.equals(indexedFile) && size == indexedSize
                && modified == indexedModified) {
            return measured;
        }
        indexedFile = file;
        indexedSize = size;
        indexedModified = modified;

        measured = CompletableFuture.supplyAsync(() -> {
            MeasuredMapStore store = new MeasuredMapStore(file.getParent());
            return new Measured(store.rows(), store.areas());
        });
        return measured;
    }
}
