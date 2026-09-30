package dev.hoardkeeper.search;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.chat.ScanChat;
import dev.hoardkeeper.index.StorageIndex;
import dev.hoardkeeper.index.StorageIndexCache;
import dev.hoardkeeper.observe.PlayerContainerOpens;
import dev.hoardkeeper.scan.ContainerDiscovery;
import dev.hoardkeeper.scan.InteractionSender;
import dev.hoardkeeper.scan.ScanController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the item search: the index over the last scan, the highlight it produces, and the two ways
 * that highlight ends.
 *
 * <p>Two ways in, one machine behind them. {@code /hoard search <item>} resolves a typed
 * guess loosely; {@link SearchKey}'s key binding hands over the exact registry id of whatever is in
 * the player's hand. Everything after the lookup — the nearest-N cap, the boxes, the countdown, the
 * dismissal on open — is the same for both, which is the reason the key is a second door into this
 * class rather than a second feature beside it.
 *
 * <p><b>Client thread only</b>, exactly like {@code ScanController} — every entry point below is
 * called from a client tick, a command, a Fabric interaction event or a packet callback, all of
 * which run on it. The one thing that does not is reading {@code containers.jsonl}, which happens
 * on a background thread and comes back through {@code Minecraft.execute} (see {@link #index}).
 * Turning what was read into an item index still runs once per storage the player walks into —
 * that is the price of the answer describing where they are standing rather than where they were
 * when the file was last read; see {@link dev.hoardkeeper.index.StorageIndexCache#indexFuture}.
 * Reading and inverting the map used to happen here; both now live in that cache, and this class is
 * its first consumer rather than its owner.
 *
 * <p><b>How a highlight ends.</b> Three ways, and they are all the same act of getting out of the
 * way: the player opens each lit container (that container's question is answered), the countdown
 * runs out (the highlight was forgotten), or a new search replaces it. Leaving boxes glowing in the
 * world after any of those would make the feature something to turn off.
 *
 * <p><b>Why an opened container needs two signals.</b> "The player opened that chest" is not one
 * event on the client. {@code UseBlockCallback} says a block was right-clicked, which is intent and
 * not outcome — a chest with a block on top, or one a plugin refuses, never opens. The container
 * screen packet says a menu opened, but carries no position. So the click is remembered
 * ({@link #onPlayerUsedBlock}) and only confirmed when a menu actually follows soon after
 * ({@link #onContainerScreenOpened}) — that pairing itself lives in
 * {@link dev.hoardkeeper.observe.PlayerContainerOpens}, shared with the passive observer that
 * needs the identical fact. A click that opens nothing leaves the highlight alone, which is the
 * correct answer: the player still has not seen inside.
 */
public final class SearchService {

    /**
     * How often the action bar is rewritten while a highlight is lit. The vanilla action bar fades
     * on its own after about 60 ticks, so this has to be well under that; five ticks is four
     * refreshes a second, which is smooth for a countdown that only changes once a second and far
     * cheaper than rewriting it every tick.
     */
    private static final int ACTION_BAR_EVERY_TICKS = 5;

    /**
     * How often the lit containers are re-checked against the world. Twice a second is instant to a
     * player who just broke a chest, and it keeps the check off nineteen ticks in twenty — at the
     * default cap that is 128 block lookups every ten ticks rather than every one.
     */
    private static final int PRUNE_EVERY_TICKS = 10;

    /**
     * Past this many blocks, the nearest lit container is said to be somewhere else. Comfortably
     * wider than any single storage scan — the default 3×3 chunks is 48 blocks across and the
     * explicit block-radius default reaches 96 — so this fires for a scan of another place and not
     * merely for the far end of the room the player is standing in.
     */
    private static final int ELSEWHERE_BLOCKS = 128;

    private static final SearchService INSTANCE = new SearchService();

    public static SearchService get() {
        return INSTANCE;
    }

    private SearchService() {
    }

    // ---- The live highlight. ----
    private SearchHighlight highlight;
    /** The dimension the highlight's coordinates belong to — see {@link #onClientTick}. */
    private String highlightDimension;

    private int tick;
    private int lastBarTick = ScanController.NEVER;
    private int lastPruneTick = ScanController.NEVER;

    // ---- The right-click awaiting a menu. Reassigned, rather than reset field by field, wherever
    // ---- the highlight it belongs to goes away — see forget() and apply(). ----
    private PlayerContainerOpens containerOpens = new PlayerContainerOpens();

    // =========================================================================
    // Command entry points
    // =========================================================================

    /**
     * Looks {@code query} up in the last scan and lights the containers holding it.
     *
     * <p>Never blocks. If the index is not built yet the player gets one "reading" line and the
     * result follows on the client thread as soon as the file is parsed — which is the whole reason
     * the outcome is handled in a callback rather than by waiting on the future: a scan of a large
     * base is megabytes of JSON, and the client thread is the thread drawing the game.
     */
    public void search(Minecraft mc, String query) {
        // Brigadier hands a greedy string "" for `/hoard search ` with a trailing space, and
        // a blank query matches everything in the index by design (it is what fills the completion
        // box before the first keystroke). Lighting up whatever the base holds most of because
        // somebody hit enter one space early is not what they asked for; the no-argument form is.
        if (query == null || query.isBlank()) {
            clear();
            return;
        }
        run(mc, query, false);
    }

    /**
     * The key binding's form: light up the containers holding exactly {@code itemId}.
     *
     * <p><b>Not routed through {@link SearchIndex#resolve}</b>, and that is the whole difference. A
     * typed query is a guess, and resolving it loosely is what makes the command usable — nobody
     * who types {@code diamond} means "no such item". A registry id taken off the player's own
     * hotbar is not a guess: answering "you have no oak_log here" by lighting up the chests full of
     * {@code stripped_oak_log} would be a confidently wrong answer to a question that had no typo
     * in it.
     */
    public void searchHeld(Minecraft mc, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        run(mc, itemId, true);
    }

    private void run(Minecraft mc, String query, boolean exact) {
        CompletableFuture<SearchIndex> pending = indexNow(mc);
        if (pending == null) {
            ScanChat.info(SearchText.noScan());
            return;
        }
        if (!pending.isDone()) {
            ScanChat.info(SearchText.reading());
        }
        pending.whenComplete((index, error) ->
                mc.execute(() -> apply(mc, query, exact, index, error)));
    }

    /** Drops the current highlight, telling the player which one went. */
    public void clear() {
        if (highlight == null) {
            ScanChat.info(SearchText.nothingToClear());
            return;
        }
        String itemId = highlight.itemId();
        forget();
        ScanChat.info(SearchText.cleared(itemId));
    }

    /**
     * The index over what the player has measured where they are standing, for tab completion, or
     * {@code null} when there is no map to read. The future may still be running; callers that
     * cannot wait should check {@link CompletableFuture#isDone()} first.
     */
    public CompletableFuture<SearchIndex> indexFuture(Minecraft mc) {
        return index(mc);
    }

    // =========================================================================
    // Game hooks
    // =========================================================================

    /** Called once per client tick, after {@code ScanController}. */
    public void onClientTick(Minecraft mc) {
        tick++;
        if (highlight == null) {
            return;
        }

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            forget();
            return;
        }
        // The coordinates in a highlight belong to one world. Carrying them through a nether portal
        // would light up whatever happens to stand at the same x/y/z on the other side, which is a
        // confidently wrong answer rather than a missing one.
        if (!mc.level.dimension().identifier().toString().equals(highlightDimension)) {
            forget();
            return;
        }

        long now = System.currentTimeMillis();
        if (highlight.expired(now)) {
            // Silent on purpose: the action bar has been counting this down for thirty seconds, so
            // a chat line saying it reached zero is telling the player what they just watched.
            forget();
            return;
        }
        containerOpens.expire(tick);
        if (tick - lastPruneTick >= PRUNE_EVERY_TICKS) {
            lastPruneTick = tick;
            if (pruneVanished(mc.level)) {
                return;
            }
        }
        if (tick - lastBarTick >= ACTION_BAR_EVERY_TICKS) {
            lastBarTick = tick;
            ScanChat.actionBar(SearchText.actionBar(highlight.itemId(), highlight.remaining().size(),
                    highlight.itemsRemaining(), highlight.secondsLeft(now)));
        }
    }

    /**
     * A block was right-clicked. Remembered rather than acted on — see the class javadoc for why a
     * click alone is not "the player opened that chest".
     *
     * <p>The scanner's own silent opens go through the same Fabric event and are ignored here:
     * dismissing a highlight because the scan happened to read that chest would delete a result the
     * player never got to use.
     */
    public void onPlayerUsedBlock(BlockPos pos) {
        if (highlight == null || pos == null || InteractionSender.isSendingOwnInteraction()) {
            return;
        }
        containerOpens.clicked(pos.getX(), pos.getY(), pos.getZ(), tick);
    }

    /**
     * A container menu opened that the scanner did not swallow, i.e. the player's own. Confirms the
     * remembered click and retires that container from the highlight.
     */
    public void onContainerScreenOpened() {
        if (highlight == null) {
            return;
        }
        int[] clicked = containerOpens.confirm(tick);
        if (clicked == null) {
            return;
        }
        if (!highlight.dismiss(clicked[0], clicked[1], clicked[2], System.currentTimeMillis())) {
            // A chest that was never lit. The player is doing something else; say nothing.
            return;
        }
        if (highlight.isEmpty()) {
            String itemId = highlight.itemId();
            int lit = highlight.lit();
            forget();
            ScanChat.info(SearchText.allVisited(itemId, lit));
        }
    }

    /** Called on disconnect: the highlight's coordinates mean nothing on the next server. */
    public void onDisconnect() {
        forget();
        StorageIndexCache.get().onDisconnect();
    }

    /**
     * Returns this singleton to just-initialised state, for the chained client gametest runner
     * ({@code dev.hoardkeeper.gametest.GameTestReset}). Never called from the shipped mod.
     *
     * <p>{@link #onDisconnect} already clears everything a real disconnect must — the highlight,
     * the map-read cache, the cluster-index cache — because a highlight's coordinates and a cached
     * map read both belong to one server. What it deliberately leaves alone is the tick counters:
     * a live client disconnecting and reconnecting mid-session has no reason to rewind them, the
     * same reasoning {@code ScanController.tick} documents for itself. A fresh scenario in a
     * chained run is not a reconnect, though — it is the closest thing to a new JVM this mode has —
     * so this also puts those back at their field-declaration defaults.
     */
    public void resetForGameTest() {
        onDisconnect();
        tick = 0;
        lastBarTick = ScanController.NEVER;
        lastPruneTick = ScanController.NEVER;
    }

    // =========================================================================
    // State the renderer and the scanner ask about
    // =========================================================================

    /** The containers to draw this frame, or empty when nothing is lit. */
    public List<ContainerHit> lit() {
        return highlight == null ? List.of() : highlight.remaining();
    }

    /**
     * Whether the search is currently writing the action bar, which it does for as long as a
     * highlight is lit.
     *
     * <p>The scan's own progress line and auto-finish countdown stand down while this is true (see
     * {@code ScanController.progress}). Both would otherwise write the same one-line overlay several
     * times a second and the two would flicker over each other. The search wins the tie because it
     * is the transient one — thirty seconds, explicitly asked for, and carrying a countdown that is
     * useless if it cannot be read — while the scan's numbers are also on the HUD panel throughout.
     */
    public boolean ownsActionBar() {
        return highlight != null;
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /** Applies a completed index lookup on the client thread. */
    private void apply(Minecraft mc, String query, boolean exact, SearchIndex index, Throwable error) {
        if (error != null || index == null) {
            HoardkeeperMod.LOGGER.error("Failed to index the measured map for search", error);
            ScanChat.info(SearchText.noScan());
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (index.isEmpty()) {
            ScanChat.info(SearchText.emptyScan());
            return;
        }
        String itemId = exact
                ? (index.hits(query).isEmpty() ? null : query)
                : index.resolve(query);
        if (itemId == null) {
            ScanChat.info(exact ? SearchText.notHere(query) : SearchText.notFound(query));
            return;
        }

        HoardkeeperConfig config = HoardkeeperMod.config();
        Vec3 eyeVec = player.getEyePosition();
        double[] eye = {eyeVec.x, eyeVec.y, eyeVec.z};
        List<ContainerHit> hits = index.hits(itemId);
        highlight = SearchHighlight.of(itemId, hits, eye, config.searchMaxHighlights,
                System.currentTimeMillis(), config.searchHighlightSeconds);
        highlightDimension = mc.level.dimension().identifier().toString();
        lastBarTick = ScanController.NEVER;
        lastPruneTick = ScanController.NEVER;
        containerOpens = new PlayerContainerOpens();

        ScanChat.info(SearchText.found(itemId, index.total(itemId), hits.size(), highlight.lit()));

        // remaining() is sorted nearest-first and is non-empty here: resolve() only ever returns an
        // id the index holds hits for, and the cap keeps at least one.
        double nearest = Math.sqrt(highlight.remaining().getFirst().distanceSquaredFrom(eye));
        if (nearest > ELSEWHERE_BLOCKS) {
            ScanChat.info(SearchText.elsewhere((int) Math.round(nearest)));
        }
    }

    /**
     * The index over the measured map for the storage the player is standing in right now (design
     * spec §5.1), read through {@link StorageIndexCache}, for tab completion — see
     * {@link #indexFuture}, this method's only caller. Throttled by
     * {@link StorageIndexCache#indexFuture}'s 500&nbsp;ms window, which is exactly right for a
     * caller asking once a keystroke: reusing the last answer between keystrokes is the entire
     * reason that window exists.
     *
     * <p>The stat, the size/mtime cache key, the 500&nbsp;ms recheck window and the cluster-scoped
     * inversion this used to do directly all live in the cache now — see
     * {@link StorageIndexCache#indexFuture} for the two-stage reasoning behind them, unchanged. This
     * method is left doing exactly one thing of its own: projecting the cache's {@link StorageIndex}
     * down to the {@link SearchIndex} half of it, which is all the search itself ever reads.
     *
     * <p><b>Not what an actual search runs through</b> — see {@link #indexNow} for why a single ask
     * cannot share this method's window without risking a stale answer nothing then retries.
     */
    private CompletableFuture<SearchIndex> index(Minecraft mc) {
        CompletableFuture<StorageIndex> pending = StorageIndexCache.get().indexFuture(mc);
        return pending == null ? null : pending.thenApply(StorageIndex::search);
    }

    /**
     * {@link #index}'s counterpart for {@link #run}, the one place an actual search asks: the same
     * lookup, through {@link StorageIndexCache#indexFutureNow} instead of
     * {@link StorageIndexCache#indexFuture}, so the file is always stated fresh for this ask rather
     * than possibly answered from whatever {@link StorageIndexCache#tick} last cached up to
     * 500&nbsp;ms ago.
     *
     * <p><b>Why a search cannot share tab completion's window.</b> A search asks once, and
     * {@link #apply} acts on whatever answer comes back with no retry — so a single stale read
     * permanently reports "nothing here" for that search. Before {@link StorageIndexCache#tick}
     * existed, every demand call started from a cache that was cold or long stale, so this was never
     * visible: the first ask after any real gap always got a genuinely fresh stat. Now that the cache
     * is kept warm by a client tick running independently of anyone asking anything, a search that
     * follows a file change by less than the window's 500&nbsp;ms — exactly what happens when a scan
     * finishes and is searched right away — can land inside a window the ticker opened before the
     * change and get an answer from before it. This method exists so the search keeps the guarantee
     * it always had, and {@link #indexFuture} keeps the throttling tab completion always needed;
     * see {@link StorageIndexCache}'s class javadoc for the full account.
     */
    private CompletableFuture<SearchIndex> indexNow(Minecraft mc) {
        CompletableFuture<StorageIndex> pending = StorageIndexCache.get().indexFutureNow(mc);
        return pending == null ? null : pending.thenApply(StorageIndex::search);
    }

    /**
     * Puts out the boxes standing over containers that are not there any more — somebody broke the
     * chest, or the shulker box was picked up.
     *
     * <p>A search answers from a file, so everything it says is as old as the scan: a chest emptied
     * by hand still shows the count it had. That is inherent and the timestamp in the result line is
     * how the player is told. A chest that is <em>gone</em>, though, is not the same kind of stale —
     * a glowing box hanging in the air over nothing is not an out-of-date answer, it is a landmark
     * pointing at a place that no longer exists, and unlike the count it costs one block lookup to
     * check. So this is the one thing about a highlight that is verified live.
     *
     * <p><b>Only a loaded chunk can say "gone".</b> {@code ClientLevel.getBlockState} answers air
     * for a position in a chunk the client does not hold, so without the chunk check, walking far
     * enough away would silently put out every box in the highlight — the same trap
     * {@code ScanController.trySend} documents for retiring candidates as {@code GONE}. Unknown is
     * not gone: an unchecked half stays lit.
     *
     * <p>Both halves of a double chest are consulted, and one surviving half keeps the hit lit. It
     * still holds items and it is still somewhere to walk to; only its count is now wrong, which is
     * what every count here already is.
     *
     * @return whether the highlight ended here, so the caller stops using it this tick
     */
    private boolean pruneVanished(ClientLevel level) {
        // Built per prune rather than held: it is a thin wrapper over the live config, and reading
        // the config fresh is what keeps this agreeing with the scanner about what a container is.
        ContainerDiscovery discovery = new ContainerDiscovery(HoardkeeperMod.config());
        int removed = highlight.removeIf(hit -> isGone(level, discovery, hit));
        if (removed == 0 || !highlight.isEmpty()) {
            return false;
        }
        String itemId = highlight.itemId();
        forget();
        ScanChat.info(SearchText.allGone(itemId));
        return true;
    }

    /** True only when every half of {@code hit} sits in a loaded chunk and is no longer a container. */
    private static boolean isGone(ClientLevel level, ContainerDiscovery discovery, ContainerHit hit) {
        return isGone(level, discovery, hit.pos()) && isGone(level, discovery, hit.secondaryPos());
    }

    private static boolean isGone(ClientLevel level, ContainerDiscovery discovery, int[] pos) {
        if (pos == null) {
            // The absent half of a single container. Nothing to keep the hit alive, nothing to kill
            // it either — the other half decides.
            return true;
        }
        BlockPos block = new BlockPos(pos[0], pos[1], pos[2]);
        if (level.getChunkSource().getChunk(block.getX() >> 4, block.getZ() >> 4, ChunkStatus.FULL, false) == null) {
            return false;
        }
        return discovery.kindOf(level.getBlockState(block)) == null;
    }

    /** Drops the highlight and everything that only makes sense while one is lit. */
    private void forget() {
        highlight = null;
        highlightDimension = null;
        containerOpens = new PlayerContainerOpens();
    }
}
