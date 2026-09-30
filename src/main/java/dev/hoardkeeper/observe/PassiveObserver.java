package dev.hoardkeeper.observe;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.capture.ContainerCapture;
import dev.hoardkeeper.capture.MenuSlotCounts;
import dev.hoardkeeper.measured.MeasuredMapFiles;
import dev.hoardkeeper.measured.MeasuredMapProjector;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.realm.RealmKey;
import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.ContainerDiscovery;
import dev.hoardkeeper.scan.ContainerKind;
import dev.hoardkeeper.scan.InteractionSender;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.store.ScanStorage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * Records what a player leaves in a container they opened by hand. Design spec §3 and §4.
 *
 * <p>Nothing appears on screen and no extra packet reaches the game server: the contents are
 * already in the menu the player opened, and this only keeps them. A refusal is a DEBUG line, a
 * write failure a WARN one, and neither is ever chat.
 *
 * <p><b>Observed at close, not at open.</b> The contents when the GUI opens are what the container
 * held <em>before</em> the player touched it; the contents when it closes are what it holds now,
 * which is the whole reason to observe at all. The client's menu carries the slots as the server
 * last confirmed them, so reading it as the screen goes away costs nothing.
 *
 * <p><b>Three signals, in order</b>, because "the player opened that chest" is not one event:
 *
 * <ol>
 *   <li>{@code UseBlockCallback} — a right-click at a position, remembered by
 *       {@link PlayerContainerOpens}. Intent, not outcome: a chest with a block on top opens
 *       nothing. The scanner's own silent opens go through this same event and are excluded by
 *       {@link InteractionSender#isSendingOwnInteraction()} — they are already scans.
 *   <li>An open-screen packet the scanner did <em>not</em> swallow, with container id ≠ 0 (0 is the
 *       player's own inventory), handed here by {@code ClientPacketListenerMixin}. That confirms
 *       the click and carries the menu type id.
 *   <li>The screen actually appearing, and then being removed. The observation is bound to the
 *       screen <em>instance</em> at {@code ScreenEvents.AFTER_INIT}, so a menu that never produced
 *       a screen can never be credited to the next one that does — which would otherwise file the
 *       player's own inventory under a chest's coordinates.
 * </ol>
 *
 * <p><b>Client thread only</b>, exactly like {@code ScanController} and {@code SearchService}:
 * every entry point below is a Fabric event or a packet callback that runs on it. Two things do
 * not, and no Minecraft type crosses to either — the {@link ItemStack} to
 * {@link ScannedContainer} conversion happens here, on the client thread, before anything is
 * handed on:
 *
 * <ul>
 *   <li>the append, on the writer thread {@code ContainerJsonlWriter} owns;
 *   <li>log maintenance (compaction, {@code log.json}), on each log's own {@link ObservationLogStore}
 *       — see that class for why a compaction and an append can never land on the same file at once.
 * </ul>
 *
 * <p><b>The file-handling half lives in {@link ObservationLogStore}</b>, one per {@link Log}, held
 * in {@link #stores}. This class decides <em>whether</em> something is an observation — the click,
 * the screen, the menu, the area and registration checks below — and the store decides <em>how</em>
 * it reaches disk: the append, the writer, the compaction, the watermark. Splitting it out this way
 * mirrors {@code measured.MeasuredMapStore}, which already has the same shape for the measured map.
 */
public final class PassiveObserver {

    private static final PassiveObserver INSTANCE = new PassiveObserver();

    /**
     * How long the "is this position inside a scanned storage" answer is reused. Answering it means
     * reading the measured map's {@code measured.json}, which must not land on the path a player's
     * chest open takes — spec §3.3 asks for exactly this, and half a second is the policy
     * {@code SearchService} uses for its index. The registration question just below it
     * ({@link #registeredContainer}) reuses this same window, over a different file, for the same
     * reason.
     */
    private static final long AREA_RECHECK_MILLIS = 500;

    /** The realm slug for a client that cannot tell which realm it is on — see spec §4.1. */
    private static final String UNKNOWN_REALM = "unknown-realm";


    public static PassiveObserver get() {
        return INSTANCE;
    }

    private PassiveObserver() {
    }

    /**
     * One log's identity: where it lives, and what a row of it belongs to. Package-private, not
     * private: {@code ObservationUploader#onClientTick} reads it through {@link #currentLog} so the
     * upload's tick check names the same log this observer would append the next row to, rather
     * than re-deriving that identity a second time.
     */
    public record Log(Path dir, String server, String realm, String dimension) {
    }

    /** A screen that is expected to become an observation when it goes away. */
    private record Pending(Screen screen, int[] pos, String menuTypeId) {
    }

    // ---- Client thread only. ----
    private final PlayerContainerOpens opens = new PlayerContainerOpens();
    private int tick;

    /** The click a menu was credited to, between the open-screen packet and the screen appearing. */
    private int[] clickedPos;
    private String clickedMenuType;

    /** The open container screen this observer intends to read at close, or {@code null}. */
    private Pending pending;

    /**
     * The realm and dimension whose scan sessions have already been folded into their measured map
     * on this connection, or {@code null} before the first. See {@link #migrateOnArrival}.
     */
    private String migratedDimension;
    private String migratedRealm;

    /**
     * One {@link ObservationLogStore} per log this client has visited this session, created on
     * first use and never evicted — a client sees a handful of realms and dimensions in its
     * lifetime, the same reasoning {@code MeasuredMapStore.LAST_OWN_APPEND} gives for never
     * clearing itself. Keeping the same instance for the life of a log matters, not just for
     * caching: an in-flight compaction's completion closure ({@link #onJoin}) captures the exact
     * store it was submitted from, and a reconnect to the very same server/realm/dimension must
     * resume the same generation counter and the same guard against a stale completion, not start
     * over at zero.
     */
    private final Map<Log, ObservationLogStore> stores = new HashMap<>();

    /**
     * Whichever log's store currently owns the open writer, or {@code null}. Compared against in
     * {@link #activate} so that switching logs closes the previous store's writer immediately — the
     * same moment the pre-extraction {@code writerFor} did — since a store never closes another
     * store's writer on its own: it only knows its own directory.
     */
    private Log activeLog;

    // ---- The cached §3.3 answer. ----
    private List<ScanArea> areas;
    private String areasDimension;
    private String areasRealm;
    private long areasCheckedMillis;

    /**
     * The containers the scans on disk registered, and the same half-second freshness the area
     * answer above gets. Asked only after the area answer already said yes, so the cost lands on
     * chests opened inside a storage and nowhere else.
     */
    private final RegisteredContainerIndex registeredIndex = new RegisteredContainerIndex();
    private Set<Long> registered;
    private String registeredDimension;
    private String registeredRealm;
    private long registeredCheckedMillis;

    /**
     * When the last observation was made, epoch millis, or 0 for none this run — see
     * {@link #lastObservationMillis()}.
     */
    private long lastObservationMillis;

    /**
     * When a real observation was last recorded by {@link #append}, epoch millis, or 0 if none
     * was made since this client started. Unlike {@link #lastObservationMillis}, {@link #onJoin}
     * never touches this one: that method stamps the other clock on every join with
     * {@code passiveObserve} on, purely to arm the upload debounce (spec §5.1), which is not an
     * observation. The status line (spec §7) reads from here instead, precisely so it never
     * reports "last 0s ago" for a join that observed nothing.
     */
    private long lastRealObservationMillis;

    /**
     * When the most recent observation was made, as epoch millis, or 0 if none was made since this
     * client started. The quiet period the upload waits out (spec §5.1) is measured from here.
     *
     * <p><b>Not what the status line (spec §7) reports.</b> {@link #onJoin} stamps this clock on
     * every join to arm the debounce above, whether or not anything was actually observed — see
     * {@link #lastRealObservationMillis()} and {@code ScanText.observationStatusText} for the one
     * that stays truthful.
     */
    public long lastObservationMillis() {
        return lastObservationMillis;
    }

    /**
     * When an observation was actually recorded, epoch millis, or 0 for none this run — the clock
     * {@link Status} reports, and the only one that never counts a bare join. See
     * {@link #lastRealObservationMillis}.
     */
    public long lastRealObservationMillis() {
        return lastRealObservationMillis;
    }

    /**
     * What the one status line (spec §7) says about the current realm's log: how many rows it
     * holds, how many of those have yet to reach the upload server, and how many seconds ago the last
     * observation was — negative when none has happened this session, which is what keeps the line
     * from claiming an observation that did not occur.
     */
    public record Status(int rows, int pending, long secondsSinceLast) {
    }

    /**
     * Reads the current realm's log for {@code /hoard status}, or {@code null} when there is
     * no log to read yet (no level, so no realm and no dimension to name one).
     *
     * <p><b>Client thread only, and deliberately not free.</b> It flushes the writer and then parses
     * the row file, which is what {@code ObservationUploader#onClientTick} does before it computes
     * the very same numbers — so the "N pending upload" here is the count that upload would
     * actually send, not an estimate that drifts from it. That cost is fine on a path a player
     * reaches by typing a command, and would not be on the tick path: the flush is why a chest
     * closed one tick ago is already counted, rather than sitting invisibly in the writer's queue.
     */
    public Status status(Minecraft mc) {
        Log log = currentLog(mc);
        if (log == null) {
            return null;
        }
        ObservationLogStore store = storeFor(log);
        store.flushWriter();
        // Rows held back behind a compaction of this log are already observed and will be appended
        // when it finishes; leaving them out would make the count fall as the player watches. A
        // store's own deferredRowCount() is already scoped to this log, so nothing further is
        // needed to exclude a different log's in-flight compaction the way the pre-extraction code
        // had to check compactingDir for by hand.
        int rows = store.rawRowCount() + store.deferredRowCount();
        int uploadedRows = Math.max(0, store.state().uploadedRows);
        long secondsSinceLast =
                ObservationLog.secondsSinceLast(lastRealObservationMillis, System.currentTimeMillis());
        return new Status(rows, ObservationLog.pendingRows(rows, uploadedRows), secondsSinceLast);
    }

    /**
     * Subscribes the observer to the events it needs. Called once from client init.
     *
     * <p>The fourth signal, the open-screen packet, cannot be subscribed to: only
     * {@code ClientPacketListenerMixin} knows whether the scanner swallowed it, so that class calls
     * {@link #onContainerScreenOpened} directly — the same way it already tells {@code SearchService}.
     */
    public static void register() {
        // Fires for the scanner's own silent opens too, which is why isSendingOwnInteraction is
        // asked below. PASS always: this listens, it never decides.
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide()) {
                INSTANCE.onPlayerUsedBlock(hitResult.getBlockPos());
            }
            return InteractionResult.PASS;
        });

        // ScreenEvents.remove() is an event per screen instance, so it cannot be registered once
        // here: the handler is registered from AFTER_INIT, for the screen that just appeared.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                INSTANCE.onScreenInit(client, screen));

        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onClientTick);

        // Deferred by one tick so the level, the player and the realm key are certainly in place —
        // the login packet that sets the realm is handled around the same event.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> {
                    // Unconditional, unlike onJoin below: a scan ScanController.onDisconnect or
                    // onRealmChanged cut short never gets to project itself, whether or not passive
                    // observation is even on, so the migration has to run every join regardless of
                    // that config flag or those rows are lost for good. See
                    // MeasuredMapProjector.migrate for why this has to happen on every join and not
                    // only the first ever.
                    INSTANCE.migrateOnArrival(client);
                    INSTANCE.onJoin(client);
                }));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.onDisconnect());
    }

    // =========================================================================
    // Game hooks
    // =========================================================================

    /** Advances the clock {@link PlayerContainerOpens} measures its window in, and expires a stale click. */
    public void onClientTick(Minecraft mc) {
        tick++;
        opens.expire(tick);
        migrateOnArrival(mc);
    }

    /**
     * Folds this dimension's scan sessions into its measured map, once per dimension the player
     * arrives in — the join, and every dimension change after it.
     *
     * <p><b>Why the join alone is not enough.</b> {@code MeasuredMapProjector.migrate} only ever
     * writes the map of the dimension the client is standing in, and vanilla fires no join event
     * for a nether portal. So a Nether scan that a disconnect or a realm change cut short — the
     * case that migration exists for — would sit unprojected until the player happened to log in
     * from the Nether. Meanwhile {@code /hoard upload all} is dimension-agnostic: it will
     * upload that session from the Overworld, and once uploaded it is deleted. The rows would be
     * gone without ever having reached a map.
     *
     * <p>The realm is watched alongside the dimension for the same reason the two caches below are
     * keyed on both: a map lives under a realm as well as a dimension, and a proxy backend switch
     * moves the realm without moving the dimension, the address or the connection. It fires no join
     * event either.
     *
     * <p>The signal is the one this class already reads both values through everywhere else (see
     * {@link #currentLog}); this only remembers the last answer. Once per arrival, not per tick:
     * {@code migrate} lists the server's session directories and parses each {@code session.json},
     * which is a fine cost for walking through a portal and not one for every tick after it.
     */
    private void migrateOnArrival(Minecraft mc) {
        ClientLevel level = mc == null ? null : mc.level;
        if (level == null) {
            return;
        }
        String dimension = level.dimension().identifier().toString();
        String realm = RealmKey.get().current();
        if (dimension.equals(migratedDimension) && Objects.equals(realm, migratedRealm)) {
            return;
        }
        migratedDimension = dimension;
        migratedRealm = realm;
        MeasuredMapProjector.migrate(mc);
    }

    /**
     * A block was right-clicked. Remembered, not acted on: the click is intent, and only a menu
     * that actually follows says the container opened.
     */
    private void onPlayerUsedBlock(BlockPos pos) {
        if (pos == null || !HoardkeeperMod.config().passiveObserve
                || InteractionSender.isSendingOwnInteraction()) {
            return;
        }
        opens.clicked(pos.getX(), pos.getY(), pos.getZ(), tick);
    }

    /**
     * A container menu opened that the scanner did not swallow, i.e. the player's own. Called from
     * {@code ClientPacketListenerMixin} on the client thread.
     *
     * <p>Whatever this leaves behind replaces any earlier unconsumed pairing, including with
     * {@code null}: a menu whose screen was never built (a menu type with no screen) must not have
     * its position credited to the next menu that does open one.
     *
     * <p><b>A menu the scanner abandoned is never observed</b>, and this is the one place the spec
     * is knowingly not followed — §7 blesses the case, and it is wrong. When
     * {@code ScanController.onOpenScreen} lets a menu through because the player right-clicked
     * within the last four ticks, it does so precisely because nothing in the packet says whether
     * the menu is the scanner's or theirs. The scanner answers that ambiguity by giving up its
     * request; pairing the same menu with the player's pending click here would file the scanner's
     * target's contents under the player's chest's position, kind and secondary half — and the
     * server's ageing rule would then prefer that row over the truth. A lost observation is cheap.
     *
     * <p>The click itself is deliberately <em>not</em> consumed: the click lives in
     * {@link PlayerContainerOpens}, not in the two fields below, and the player's own right-click
     * genuinely still has a menu in flight — it arrives a tick or two later as an ordinary
     * unswallowed one, and that menu is theirs beyond doubt. Clearing the fields refuses this menu;
     * leaving the click confirmable is what keeps the next one observable.
     */
    public void onContainerScreenOpened(int containerId, MenuType<?> type) {
        if (containerId == 0 || !HoardkeeperMod.config().passiveObserve) {
            return;
        }
        if (ScanController.get().abandonedLastMenu()) {
            // Still an answer for this menu, and the answer is "none": a pairing left over from an
            // earlier menu whose screen was never built must not be handed to the screen this one
            // is about to build. Two chest menus share a menu type, so the gate's slot-count clause
            // would not catch that swap.
            clickedPos = null;
            clickedMenuType = null;
            HoardkeeperMod.LOGGER.debug(
                    "Not observing menu '{}': the scanner abandoned a request for it and neither of "
                            + "us can tell whose menu it is", menuTypeId(type));
            return;
        }
        clickedPos = opens.confirm(tick);
        clickedMenuType = clickedPos == null ? null : menuTypeId(type);
    }

    /**
     * A screen appeared. Binds the pending click-and-menu pairing to this screen instance and asks
     * to hear when it goes away.
     *
     * <p>Also fires again when the window is resized, which re-runs {@code Screen.init} and drops
     * every per-screen listener with it — hence the re-arm branch, without which closing a chest
     * after a resize would record nothing.
     */
    private void onScreenInit(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        if (pending != null && pending.screen() == screen) {
            ScreenEvents.remove(screen).register(closing -> onScreenRemoved(mc, closing));
            return;
        }

        int[] pos = clickedPos;
        String menuType = clickedMenuType;
        clickedPos = null;
        clickedMenuType = null;
        if (pos == null || container.getMenu().containerId == 0) {
            return;
        }
        pending = new Pending(screen, pos, menuType);
        ScreenEvents.remove(screen).register(closing -> onScreenRemoved(mc, closing));
    }

    /** The screen went away: this is the moment the container's contents are worth recording. */
    private void onScreenRemoved(Minecraft mc, Screen closing) {
        Pending observation = pending;
        if (observation == null || observation.screen() != closing
                || !(closing instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        pending = null;
        try {
            observe(mc, container, observation);
        } catch (RuntimeException e) {
            // This runs inside Minecraft's own setScreen call, so anything escaping here would take
            // the game down over a feature whose entire promise is that it costs the player
            // nothing. One lost observation and one WARN is the right price.
            HoardkeeperMod.LOGGER.warn("Failed to record what was left in the container at {}",
                    describe(observation.pos()), e);
        }
    }

    /**
     * Compacts this connection's log, once, a tick after joining (spec §4.4).
     *
     * <p>Only this realm and dimension's log: a log the player never returns to is compacted the
     * next time they are standing in it, and the row cap is what bounds it meanwhile.
     */
    private void onJoin(Minecraft mc) {
        HoardkeeperConfig config = HoardkeeperMod.config();
        if (!config.passiveObserve) {
            // Spec §13: the feature off means the log on disk is left exactly as it is.
            return;
        }
        // Spec §5.1: rows left pending by a previous run are retried "after the same delay" a
        // fresh observation would wait out — not instantly. Resetting the clock here rather than
        // leaving it at whatever it was (0, on a fresh client start) is what makes that delay real:
        // without it, ObservationUploader reads an elapsed time measured from the epoch, finds it
        // enormous, and considers anything pending instantly due — racing the very compaction this
        // join is about to submit below, with none of the head start spec §4.4 counts on ("at join
        // the debounce keeps the two apart in practice").
        lastObservationMillis = System.currentTimeMillis();
        Log log = currentLog(mc);
        if (log == null) {
            return;
        }

        // storeFor, not activate: this only ever compacts the log just joined, on its own store,
        // which closes its own writer internally (compactIfDue) if there is anything to compact.
        // No other store's writer is affected -- that coordination is activate()'s job, and only
        // append() needs it.
        ObservationLogStore store = storeFor(log);
        ObservationLogStore.CompactionAttempt attempt = store.compactIfDue(config.passiveMaxRows);
        attempt.future().whenComplete(
                (ignored, error) -> mc.execute(() -> store.finishCompaction(attempt.generation())));
    }

    /**
     * The connection is gone. Drops the pending observation (spec §3.5 — one lost observation is
     * cheaper than an ordering problem between two shutdown paths) and closes the log.
     */
    public void onDisconnect() {
        pending = null;
        clickedPos = null;
        clickedMenuType = null;
        // The next connection may be another server entirely, so its dimensions have to be folded
        // in again however familiar their ids look.
        migratedDimension = null;
        migratedRealm = null;
        areas = null;
        areasDimension = null;
        areasRealm = null;
        areasCheckedMillis = 0;
        // The next server's measured map is a different file; nothing parsed here is worth keeping.
        registeredIndex.forget();
        registered = null;
        registeredDimension = null;
        registeredRealm = null;
        registeredCheckedMillis = 0;
        // Every store, not just the active one: a dimension hop mid-compaction can leave a
        // *different* log's store holding deferred rows or a compaction guard while this one was
        // the active writer -- see ObservationLogStore.close for what each cleans up. A store with
        // nothing to clean up treats this as a no-op.
        for (ObservationLogStore store : stores.values()) {
            store.close();
        }
        activeLog = null;
    }

    // =========================================================================
    // Test seams
    // =========================================================================

    /**
     * Returns this singleton to just-initialised state, for the chained client gametest runner
     * ({@code dev.hoardkeeper.gametest.GameTestReset}). Never called from the shipped mod —
     * every production entry point into this class is a Fabric event or a packet callback, none
     * of which reach here.
     *
     * <p>Goes further than {@link #onDisconnect}, which this reuses: a real disconnect leaves
     * every {@link ObservationLogStore} this client has ever visited sitting in {@link #stores},
     * closed but still keyed by its {@link Log} — correct for a client that might reconnect to the
     * very same log later in the same session. A gametest scenario boundary is not that: the next
     * scenario gets a wiped {@code hoardkeeper/} directory (see {@code GameTestReset}), so a
     * reused store's {@code compactionGeneration}/{@code dirtyGeneration} counters would no longer
     * describe anything on disk. Clearing the map is what makes the next scenario's first append
     * build a fresh store against the fresh directory, exactly as a brand-new client would.
     *
     * <p>Drains every store's pending writes first — the maintenance chain a compaction runs on is
     * a single thread shared for the JVM's whole life and must not be shut down, but a rewrite
     * still in flight when the directory underneath it is deleted is a write racing a delete, not
     * an abandoned one.
     */
    public void resetForGameTest() {
        for (ObservationLogStore store : stores.values()) {
            store.awaitPendingWritesForTests();
        }
        onDisconnect();
        stores.clear();
        opens.reset();
        tick = 0;
        lastObservationMillis = 0;
        lastRealObservationMillis = 0;
    }

    // =========================================================================
    // The observation itself
    // =========================================================================

    /**
     * Reads the closed menu and, if §3 allows it, appends one row.
     *
     * <p>{@code ContainerDiscovery} and {@code ContainerCapture} are built per observation rather
     * than held: both are thin wrappers over the live config, and building them fresh is what keeps
     * this agreeing with the scanner about what a container is and how deep an item is flattened —
     * the same reason {@code SearchService} builds a discovery per prune.
     */
    private void observe(Minecraft mc, AbstractContainerScreen<?> container, Pending observation) {
        observeContents(mc, observation.pos(), container.getMenu().getItems(), observation.menuTypeId(),
                container.getTitle().getString());
    }

    /**
     * What the singleplayer deposit mode left in a container it just filled — the same row, under
     * the same §3 rules, that closing the container by hand would have produced. The deposit opens
     * containers without a screen, so the screen hook above never sees them; without this the map,
     * the search and the tooltips would keep showing the container as it was before.
     *
     * <p>{@code items} is the whole menu, player inventory included, exactly as the screen path
     * passes it: the slot count is derived from its size the same way.
     */
    public void recordDeposit(Minecraft mc, int[] pos, List<ItemStack> items, String menuTypeId, String title) {
        observeContents(mc, pos, items, menuTypeId, title);
    }

    private void observeContents(Minecraft mc, int[] at, List<ItemStack> items, String menuTypeId, String title) {
        HoardkeeperConfig config = HoardkeeperMod.config();
        ClientLevel level = mc.level;
        if (mc.getConnection() == null || level == null) {
            HoardkeeperMod.LOGGER.debug("Not observing {}: the client is disconnecting", describe(at));
            return;
        }

        Log log = currentLog(mc);
        if (log == null) {
            return;
        }

        int slotCount = MenuSlotCounts.resolve(items.size());
        BlockPos pos = new BlockPos(at[0], at[1], at[2]);
        ContainerCandidate candidate = new ContainerDiscovery(config).candidateAt(level, pos);

        // Asked only for a block this mod actually scans: the answer costs a (cached) walk of this
        // server's session directory, and the gate refuses a furnace at an earlier clause anyway.
        boolean insideStorageArea = candidate != null && insideStorageArea(mc, log, pos);
        boolean registeredContainer = insideStorageArea && registeredContainer(mc, log, pos);

        ObservationGate.Decision decision = ObservationGate.decide(
                candidate == null ? null : candidate.kind,
                MenuSlotCounts.expectedFor(menuTypeId),
                slotCount,
                insideStorageArea,
                registeredContainer,
                config.passiveObserve,
                // Where the scanner may not act, no scan ever measures an area, so "inside a
                // scanned area" would never hold and nothing would be recorded. There every
                // container opened by hand counts (split spec §4, owner's decision 2026-09-30).
                config.passiveObserveAnywhere || !ScanController.mayActHere(mc));
        if (!decision.allowed()) {
            HoardkeeperMod.LOGGER.debug("Not observing {}: {}", pos, decision.reason());
            return;
        }

        ScannedContainer row = new ContainerCapture(config).capture(items, slotCount,
                filedAs(candidate, decision.resolvedKind()), log.dimension(), menuTypeId, title);
        append(log, row, registeredContainer);
        if (!registeredContainer && !ScanController.mayActHere(mc)) {
            // Where no scan will ever run, the hand-opened container is the only measurement there
            // is: file it into the map so search, tooltips and the peek card can answer (§4).
            MeasuredMapProjector.projectHandObservation(mc, row);
        }
    }

    /**
     * The candidate the row is filed under. Spec §3.4: the slot count derived from the menu at
     * close outranks what the block state claimed, so a block still claiming a double chest whose
     * menu delivered 27 container slots is filed as the single kind — and its {@code secondaryPos}
     * goes with the half the menu says is not there.
     *
     * <p>The position stays the canonical half {@code ContainerDiscovery} chose, which is the
     * identity a scan of the same chest files it under and the one the search index keys on. The
     * ordinary "the other half was broken while the GUI was open" case does not even reach here:
     * breaking a half turns the survivor's block state {@code SINGLE}, so {@code candidateAt},
     * which reads the world at close, already answers with the single kind at the surviving half.
     * What is left for the downgrade is a block state and a menu that disagree — a plugin, or a
     * desync — and filing that at a position no scan would use is the worse of the two answers.
     */
    private static ContainerCandidate filedAs(ContainerCandidate found, ContainerKind resolved) {
        return new ContainerCandidate(found.pos, isDouble(resolved) ? found.secondaryPos : null, resolved);
    }

    private static boolean isDouble(ContainerKind kind) {
        return kind == ContainerKind.CHEST_DOUBLE || kind == ContainerKind.TRAPPED_CHEST_DOUBLE;
    }

    /**
     * Whether {@code pos} lies inside the area of some storage scan this client holds for the
     * dimension and realm it is on. Spec §3.3.
     *
     * <p>Reads the measured map's own areas ({@link MeasuredMapStore#areas()}) rather than walking
     * scan sessions on disk. A session is deleted once it has uploaded (spec §6), but an area the
     * storage was actually measured in does not stop existing just because the session that measured
     * it was uploaded and cleared — the same correction {@link #registeredContainer} makes for the
     * registration question, for the same reason, and for the same failure it prevents: once the
     * first upload happened, a session-backed answer here would find nothing and refuse every
     * container with {@code OUTSIDE_SCANNED_AREA}, silently ending passive observation altogether.
     *
     * <p>State and age do not matter: an interrupted scan and a scan from last month both say "this
     * place is tracked". The answer is cached for half a second, and keyed on the realm as well as
     * the dimension: a proxy backend switch changes which scans count, and it changes neither the
     * address nor the dimension.
     */
    private boolean insideStorageArea(Minecraft mc, Log log, BlockPos pos) {
        Path mapDir = MeasuredMapProjector.dirFor(mc);
        if (mapDir == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (areas == null || !log.dimension().equals(areasDimension)
                || !Objects.equals(log.realm(), areasRealm)
                || now - areasCheckedMillis >= AREA_RECHECK_MILLIS) {
            areas = new MeasuredMapStore(mapDir).areas();
            areasDimension = log.dimension();
            areasRealm = log.realm();
            areasCheckedMillis = now;
        }
        for (ScanArea area : areas) {
            if (area.coversBlock(pos.getX(), pos.getZ())) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // The log
    // =========================================================================

    /**
     * Queues the row for its log, or holds it if that log is being compacted right now, and —
     * unless {@code registeredContainer} says otherwise — projects it onto the measured map too.
     *
     * @param registeredContainer whether the gate's own registration check passed, as opposed to
     *                            being bypassed by {@code passiveObserveAnywhere}. The observation
     *                            log gets the row either way — that flag's whole promise is "record
     *                            this container", and the upload server still must see it. But the map is
     *                            what answers "does this container belong to the storage" (spec
     *                            §4.2), and a row let through only because the player lifted the
     *                            location rules is not evidence of that: projecting it would enrol
     *                            an unregistered container permanently, since a folded content
     *                            update is indistinguishable from a scanned one on every later read.
     */
    private void append(Log log, ScannedContainer row, boolean registeredContainer) {
        lastObservationMillis = System.currentTimeMillis();
        // A real observation, unlike onJoin's stamp of the field above: this is the clock the
        // status line (spec §7) actually reads. See lastRealObservationMillis's javadoc.
        lastRealObservationMillis = lastObservationMillis;
        activate(log).append(row);
        if (registeredContainer) {
            // Spec §6: the row goes to two places — the upload queue above, and the map the search
            // reads. Two appended lines per observed container, accepted because the two files
            // answer different questions and are emptied by different events.
            Minecraft mc = Minecraft.getInstance();
            MeasuredMapProjector.projectObservation(mc, row);
            // The append just queued will move the map file's size and mtime, which is the pair the
            // registration cache is keyed on. Telling it the position now is what keeps the next
            // chest closed in this storage from paying for a full re-parse of the map.
            Path mapDir = MeasuredMapProjector.dirFor(mc);
            if (mapDir != null) {
                registeredIndex.projected(MeasuredMapFiles.containers(mapDir), row.pos);
            }
        }
    }

    /**
     * The store for {@code log}, created on first use — see {@link #stores}. A pure lookup with no
     * side effects: {@code ObservationUploader#onClientTick} calls this too, every tick, purely to
     * read a log's watermark, and must not have that merely-looking tick close anything.
     *
     * <p>Public for an add-on (split spec §6): {@code ObservationUploader} reaches the exact same store
     * instance this class appends through, for the same log-identity reason {@link #currentLog} is
     * package-private.
     */
    public ObservationLogStore storeFor(Log log) {
        return stores.computeIfAbsent(log,
                l -> new ObservationLogStore(l.dir(), l.server(), l.realm(), l.dimension()));
    }

    /**
     * The store for {@code log}, made the active one — the one write path that may actually open a
     * writer, so it is the one place a log switch has to close the *previous* store's writer, the
     * same moment the pre-extraction {@code writerFor}'s directory-mismatch branch did. A store
     * never does this on its own: it only ever knows its own directory, so this cross-store
     * coordination has to live here, one level up.
     */
    private ObservationLogStore activate(Log log) {
        ObservationLogStore store = storeFor(log);
        if (!log.equals(activeLog)) {
            if (activeLog != null) {
                ObservationLogStore previous = stores.get(activeLog);
                if (previous != null) {
                    previous.closeWriter();
                }
            }
            activeLog = log;
        }
        return store;
    }

    // =========================================================================
    // Identity
    // =========================================================================

    /**
     * Which log the observations made right now belong to: this server, the realm in force, and the
     * dimension the player is standing in. Keyed by realm on purpose — the scan directories are
     * not, and a new layout should not inherit that.
     *
     * <p>Public for an add-on (split spec §6): {@code ObservationUploader}'s tick check calls this so it
     * names the current log exactly as this class would, without a second copy of the resolution
     * logic — see {@link Log}'s javadoc.
     */
    public static Log currentLog(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            return null;
        }
        String server = ScanController.serverAddress(mc);
        String realm = RealmKey.get().current();
        String dimension = level.dimension().identifier().toString();
        Path dir = ObservationLogFiles.dir(gameDir(), ScanStorage.slug(server),
                realm == null ? UNKNOWN_REALM : ScanStorage.slug(realm), ScanStorage.slug(dimension));
        return new Log(dir, server, realm, dimension);
    }

    /**
     * The {@code containers.jsonl} this observer would append to right now, or {@code null} when
     * there is no level to name one.
     *
     * <p>Exists for the {@code passive} client gametest scenario, which has to read back the rows a
     * real right-click produced. It asks <em>this</em> class rather than re-deriving the path from
     * server, realm and dimension itself: a harness that computed its own path would keep passing if
     * the observer ever started writing somewhere else, which is precisely the failure it is meant
     * to catch.
     */
    public static Path currentLogContainersFile(Minecraft mc) {
        Log log = currentLog(mc);
        return log == null ? null : ObservationLogFiles.containers(log.dir());
    }

    /**
     * Whether some scan of this storage filed a container at {@code pos} — spec §3.3a, the rule
     * that separates a content update from inventing a container. See {@link RegisteredContainers}
     * for why a position and not an area, and {@link RegisteredContainerIndex} for why the parse is
     * cached.
     */
    private boolean registeredContainer(Minecraft mc, Log log, BlockPos pos) {
        Path mapDir = MeasuredMapProjector.dirFor(mc);
        if (mapDir == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (registered == null || !log.dimension().equals(registeredDimension)
                || !Objects.equals(log.realm(), registeredRealm)
                || now - registeredCheckedMillis >= AREA_RECHECK_MILLIS) {
            registered = registeredIndex.positions(MeasuredMapFiles.containers(mapDir));
            registeredDimension = log.dimension();
            registeredRealm = log.realm();
            registeredCheckedMillis = now;
        }
        return RegisteredContainers.holds(registered, new int[]{pos.getX(), pos.getY(), pos.getZ()});
    }

    /** The registry id of a menu type, as the row and {@code MenuSlotCounts} name it. */
    private static String menuTypeId(MenuType<?> type) {
        Identifier key = type == null ? null : BuiltInRegistries.MENU.getKey(type);
        return key == null ? "?" : key.toString();
    }

    private static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    private static String describe(int[] pos) {
        return "(" + pos[0] + ", " + pos[1] + ", " + pos[2] + ")";
    }
}
