package dev.hoardkeeper.scan;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.api.Addon;
import dev.hoardkeeper.api.Addons;
import dev.hoardkeeper.capture.ContainerCapture;
import dev.hoardkeeper.capture.MenuSlotCounts;
import dev.hoardkeeper.chat.ScanChat;
import dev.hoardkeeper.chat.ScanText;
import dev.hoardkeeper.hud.ScanHudRenderer;
import dev.hoardkeeper.measured.MeasuredMapProjector;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.measured.StorageClusters;
import dev.hoardkeeper.model.ScanReport;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.SessionSnapshot;
import dev.hoardkeeper.realm.RealmKey;
import dev.hoardkeeper.search.SearchService;
import dev.hoardkeeper.chat.ActionBarOwner;
import dev.hoardkeeper.sound.ScanChime;
import dev.hoardkeeper.store.ContainerJsonlWriter;
import dev.hoardkeeper.store.ScanStorage;
import dev.hoardkeeper.store.SessionExporter;
import dev.hoardkeeper.store.SessionLookup;
import dev.hoardkeeper.store.SessionSnapshotCodec;
import dev.hoardkeeper.store.SessionStateStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The scan state machine: picks the next container, sends the interaction, consumes the two
 * packets the mixin hands over, and closes the menu again.
 *
 * <p><b>Threading.</b> Every field here is client-thread-only and carries no synchronisation.
 * That is safe precisely because of the injection point {@code ClientPacketListenerMixin} uses:
 * {@code INVOKE} + {@code Shift.AFTER} on {@code PacketUtils.ensureRunningOnSameThread} is
 * unreachable on the netty I/O thread, so {@link #onOpenScreen} and {@link #onContainerContent}
 * arrive on the client thread exactly like {@link #onClientTick}. Nothing may call into this class
 * from anywhere else.
 *
 * <p><b>Strictly serial.</b> The server allows exactly one open menu per player, so there is only
 * ever one in-flight request: send, {@code ClientboundOpenScreenPacket}, {@code
 * ClientboundContainerSetContentPacket}, {@code ServerboundContainerClosePacket}. Measured against
 * Purpur at 4.90 containers/s with the default {@code minTicksBetweenOpens=4}, and at 19.0/s
 * (~53 ms per round trip, i.e. one client tick) with the pacing gate opened right up — see
 * {@code docs/SPIKE.md}. The bottleneck is walking, not the network.
 */
public final class ScanController {

    /**
     * Sentinel for "this has never happened". Deliberately NOT {@code Integer.MIN_VALUE}:
     * {@code tick - Integer.MIN_VALUE} overflows to a negative number, which wedges every
     * "has enough time passed" gate permanently shut. That bug shipped in the spike prototype and
     * cost a whole measurement run — see {@link RateLimiter} for the same sentinel and the same
     * reason.
     *
     * <p>Public so {@code ScanChat}'s own tick-gap throttle (the failure roll-up) can share the
     * exact same sentinel instead of keeping a second copy that could quietly drift into
     * {@code Integer.MIN_VALUE} and reintroduce that bug.
     */
    public static final int NEVER = -10_000;

    private static final DateTimeFormatter SESSION_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final ScanController INSTANCE = new ScanController();

    public static ScanController get() {
        return INSTANCE;
    }

    private ScanController() {
    }

    // ---- Collaborators, rebuilt per session so a config reload takes effect on the next start ----

    private HoardkeeperConfig config = HoardkeeperMod.config();
    private ContainerDiscovery discovery = new ContainerDiscovery(config);
    private RateLimiter rateLimiter = newRateLimiter(config);
    private ContainerCapture containerCapture = new ContainerCapture(config);
    /**
     * Rebuilt alongside the other collaborators on start and on resume, so a config edit to the
     * chime (volume, run length, off) takes effect on the next scan rather than needing a client
     * restart.
     */
    private ScanChime chime = new ScanChime(config);

    // ---- Session state ----

    private boolean running;
    private ScanSession session;
    private Path sessionDir;
    private ContainerJsonlWriter writer;
    private SessionStateStore stateStore;
    private String startedAtIso;

    /**
     * Monotonic client-tick counter. Deliberately NEVER reset — {@code cooldownUntilTick} and the
     * rate limiter both store absolute ticks, and rewinding the clock underneath them would make
     * cooldowns in the future unreachable.
     */
    private int tick;
    private int lastSweepTick = NEVER;

    // ---- The single in-flight request ----

    private ContainerCandidate target;
    private int sentTick;
    private int containerId = -1;
    private String menuTypeId;
    private String menuTitle;
    private boolean opened;

    /**
     * Whether the most recent {@link #onOpenScreen} let a menu through because it could not tell
     * that menu from the player's own — see {@link #abandonedLastMenu()}.
     */
    private boolean abandonedLastMenu;

    // ---- Diagnostics and chat throttling ----

    /**
     * How long the scan must be quiet before the countdown bar appears. Below this the normal
     * per-container progress line owns the action bar; at the default pace a container lands every
     * 200 ms, so anything shorter would strobe.
     */
    private static final long AUTO_FINISH_BAR_DELAY_MILLIS = 3_000;

    private String lastGateReason;
    /**
     * Ends the scan when nothing has been resolved for a while. Rebuilt on every start so a config
     * reload between scans takes effect, like the chime and the rate limiter.
     */
    private IdleCountdown idle = new IdleCountdown(0);
    private int lastGateLogTick = NEVER;

    /**
     * How long the gate must be continuously blocked by an open screen before the player is told
     * in chat. Only the open-screen reason gets this treatment — see {@link #trySend} for why
     * sneaking and use-key-held are deliberately left as silent, debug-only gate reasons.
     *
     * <p>60 ticks = 3 s at 20 tps. Must clear a normal interaction: a player who opens their own
     * inventory to check something mid-scan does so for a second or two, and seeing a chat line
     * for that would be noise, not help. Three seconds is comfortably past that while still being
     * well short of "the player forgot the scan is running".
     */
    private static final int SCREEN_BLOCKED_ANNOUNCE_TICKS = 60;
    private final ScreenBlockWatcher screenBlockWatcher = new ScreenBlockWatcher(SCREEN_BLOCKED_ANNOUNCE_TICKS);

    private boolean allResolvedAnnounced;

    /**
     * Every block position whose contents are already in this session's {@code containers.jsonl}.
     *
     * <p><b>Live, not resume-only.</b> On resume it is seeded from the file itself — see
     * {@link #resume} for why the file, not the snapshot, is the authority. But it is also appended
     * to by {@link #onContainerContent} as each container is written, so a <em>fresh</em> scan is
     * protected by exactly the same reconcile as a resumed one. It used to start empty and stay
     * empty during a fresh scan, which left the live path with no defence at all against the
     * re-pairing duplicate described on {@link #applyAlreadyWritten}.
     */
    private WrittenPositions alreadyWritten = new WrittenPositions();

    /**
     * How many containers were already scanned when this session was resumed. Zero for a fresh scan.
     *
     * <p>{@link #startedNanos} restarts at the resume, so crediting the pre-interruption containers
     * to the resumed leg's clock prints something like 250/s for a scan that has sent two packets.
     * The rate is the number a player uses to judge "am I hammering this server?" — being wrong by
     * two orders of magnitude there invites exactly the behaviour this mod is built to avoid.
     */
    private int scannedAtResume;

    // ---- Rescan nudge (design spec §7, re-grounded on the measured map — see RescanNudge) ----
    // Connection-scoped, not session-scoped: cleared on onDisconnect() beside RealmKey and the
    // search highlight, never by reset(), because reset() also runs at the start of every scan and
    // resume mid-connection, and none of the three fields below mean anything different because a
    // scan happened to start in between.

    /** How often {@link #checkRescanNudge} may do real work — one second at 20 tps (design spec §7). */
    private static final int RESCAN_NUDGE_CHECK_INTERVAL_TICKS = 20;

    private int lastNudgeCheckTick = NEVER;

    /**
     * The cluster id ({@link RescanNudge#clusterId}) the player stood in on the previous check, or
     * {@code null} for "in none" — what {@link RescanNudge#decide} compares the current tick's id
     * against to tell a fresh entry from standing still.
     *
     * <p>Package-private, not {@code private}: {@code ScanControllerRealmChangeTest} (fix round 1,
     * finding I-2) needs to seed and read this and the two fields below without a live Minecraft
     * client, and {@code onRealmChanged}'s {@code session == null} branch is genuinely reachable
     * without one — see that test's own javadoc.
     */
    String previousNudgeClusterId;

    /**
     * Cluster ids already nudged this connection (clause 5) — walking in and out of the same hall
     * four times is one line, not four. Cleared on disconnect, never on a mid-connection reset().
     */
    final Set<String> nudgedClusterIds = new LinkedHashSet<>();

    /**
     * Whether {@link #offerResume} actually printed an offer on this join (clause 6) — set the
     * instant it does, read by {@link #checkRescanNudge} on every check thereafter. Two clickable
     * offers competing for one click is worse than the later one waiting for the next session, and
     * resume is the more urgent of the two: it still has containers pending.
     */
    boolean offerResumePrinted;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Starts a fresh scan of radius {@code radius} centred on the player, replacing any session
     * already in memory. Opens the session's output files immediately, so a crash mid-scan still
     * leaves every container scanned up to that point on disk.
     */
    public void start(Minecraft mc, ScanArea.Request request) {
        start(mc, request, ScanSession.PURPOSE_STORAGE);
    }

    /**
     * Starts a scan with a stated purpose.
     *
     * <p>{@code purpose} decides one thing, and it is decided here rather than at the end: whether
     * the finished scan files its chests into the shared catalogue. A site scan does not — see
     * {@link ScanSession#purpose} — and settling it at the start means a scan that is interrupted,
     * resumed or crashed out of still knows what it was for.
     */
    public void start(Minecraft mc, ScanArea.Request request, String purpose) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            HoardkeeperMod.LOGGER.warn("Cannot start a scan without a player and a level");
            return;
        }
        if (!mayActHere(mc)) {
            ScanChat.info(ScanText.scanNotAllowedText(serverAddress(mc)));
            return;
        }

        reset();

        this.config = HoardkeeperMod.config();
        this.discovery = new ContainerDiscovery(config);
        this.rateLimiter = newRateLimiter(config);
        this.containerCapture = new ContainerCapture(config);
        this.chime = new ScanChime(config);

        String server = serverAddress(mc);
        // Stamped now, not at upload time: `/hoard upload` can run days later from a
        // different backend, and resolving the realm then would file this base under wherever the
        // player happens to be standing.
        String realm = RealmKey.get().current();
        String dimension = level.dimension().identifier().toString();
        String sessionId = ZonedDateTime.now(ZoneOffset.UTC).format(SESSION_STAMP);
        BlockPos origin = player.blockPosition();

        ScanArea area = request.at(origin.getX(), origin.getZ());
        this.session = new ScanSession(sessionId, server, dimension,
                new int[]{origin.getX(), origin.getY(), origin.getZ()}, area, realm);
        this.session.purpose = purpose;
        this.session.startedNanos = System.nanoTime();
        this.idle = new IdleCountdown(config.autoFinishSeconds * 1000L);
        this.idle.reset(System.currentTimeMillis());
        this.startedAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        // The slug is what the directory is named after; session.server keeps the raw address so
        // a report says where it came from. slug() is idempotent, so Task 13 can re-derive the
        // directory from a snapshot without keeping a second field around.
        this.sessionDir = ScanStorage.sessionDir(gameDir(), ScanStorage.slug(server), sessionId);
        this.writer = new ContainerJsonlWriter(sessionDir.resolve("containers.jsonl"));
        this.stateStore = new SessionStateStore(sessionDir.resolve("session.json"));

        this.running = true;
        int found = discovery.sweep(level, session);
        this.lastSweepTick = tick;
        saveState("RUNNING");

        HoardkeeperMod.LOGGER.info("Scan started: area={} origin={} dim={} candidates={} dir={}",
                area.describe(), origin, dimension, found, sessionDir);
        ScanChat.started(area.describe(), found);
    }

    /**
     * Ends the running scan, aborts anything in flight, marks the session {@code FINISHED}, closes
     * both output files and projects what was measured onto the measured map. The session itself
     * stays in memory so {@code status} and {@code export} still work afterwards — only
     * {@link #reset()} drops it.
     *
     * <p>The projection is unconditional ({@code projectFinishedScan}, not {@code
     * projectSessionOnce}): a resumed scan carries the id its interrupted half was already folded
     * in under, and honouring that guard here would silently drop everything found after the
     * resume.
     */
    public void stop(Minecraft mc, String reason) {
        if (!running) {
            ScanChat.notRunning();
            return;
        }
        running = false;
        idle.stop();
        abortInFlight(mc);

        int scanned = count(ContainerStatus.SCANNED);
        int failed = count(ContainerStatus.FAILED);
        int pending = count(ContainerStatus.PENDING);
        // The summary always carries the complete picture, however much chat was throttled.
        Map<FailReason, Integer> byReason = failureCounts();
        long millis = elapsedMillis();
        double perSecond = perSecond(scanned, millis);

        saveState("FINISHED");
        closeStores();

        HoardkeeperMod.LOGGER.info("Scan stopped ({}): scanned={} failed={} pending={} durationMs={} failures={}",
                reason, scanned, failed, pending, millis, byReason);
        ScanChat.summary(reason, scanned, failed, pending, millis, perSecond, byReason, sessionDir);
        // The session is still in hand here, which is the only place an add-on can still read it —
        // an upload add-on's site check takes its last measurement now.
        Addons.onScanStopped(mc, session);

        // Spec §6: the map is the search's source of truth, so it must be right the moment the
        // scan ends — not when the upload happens, which may be minutes later or never. Reads the
        // session back off disk rather than from memory so what lands in the map is exactly what
        // was persisted; closeStores() above already flushed containers.jsonl and session.json, so
        // that read sees the scan's very last rows.
        MeasuredMapProjector.projectFinishedScan(mc, sessionDir);
    }

    /**
     * Drops the in-memory session entirely and closes its files. Safe to call at any time; safe to
     * call twice. Does not reset {@link #tick} — see that field.
     */
    public void reset() {
        running = false;
        clearRequest();
        closeStores();
        session = null;
        sessionDir = null;
        startedAtIso = null;
        lastSweepTick = NEVER;
        lastGateReason = null;
        lastGateLogTick = NEVER;
        screenBlockWatcher.reset();
        // Otherwise the next scan's very first container inherits whatever note the last one
        // stopped on, which sounds like the previous run never ended.
        chime.reset();
        allResolvedAnnounced = false;
        alreadyWritten = new WrittenPositions();
        scannedAtResume = 0;
        ScanChat.resetFailureThrottle();
        // The HUD's per-tick cache is static and holds the session it last drew. Without this, a
        // finished scan's whole candidate list stays reachable until another scan replaces it.
        ScanHudRenderer.forget();
    }

    /**
     * Flushes everything to disk and forgets the session, leaving the snapshot marked
     * {@code INTERRUPTED} so Task 13's resume prompt can find it.
     *
     * <p>Sends no packets: the connection is already gone by the time this runs.
     */
    public void onDisconnect() {
        PhantomMenu.forget();
        if (session != null) {
            saveState(running ? "INTERRUPTED" : "FINISHED");
        }
        // reset() closes both stores; SessionStateStore.close() flushes the snapshot above
        // durably before its thread goes away.
        reset();
        clearNudgeState();
    }

    /**
     * The proxy moved us to a different backend while a scan was loaded.
     *
     * <p><b>Why this is not just "keep going".</b> A backend switch reuses the socket, so no
     * disconnect fires and nothing else in the mod notices. A scan left running would go on
     * discovering containers — in the new world — and write them into a session stamped with the
     * old realm, which then uploads as the old realm's data. Wrong rows under a correct-looking
     * name, and nothing on screen to suggest it happened.
     *
     * <p>So the session is flushed and forgotten, exactly as a disconnect would. What is already
     * on disk stays valid: those containers really were read on the realm the snapshot names.
     *
     * <p><b>The rescan nudge's connection-scoped state is cleared unconditionally, before the
     * {@code session == null} guard below, not after it.</b> {@link RescanNudge#clusterId} carries
     * no realm or server component — geometry and dimension only, since fix round 2 (finding I-2)
     * added the latter — so two different realms whose spawn-area scan geometry happens to coincide
     * (plausible on a network that mirrors its spawn build across backends) would otherwise make a
     * genuinely new storage on the new realm read as "already nudged" from the old one, permanently,
     * for the rest of the connection. That risk exists precisely when no session is loaded — the
     * exact state the nudge is active in (clause 4) — so clearing it only on the branch below, which
     * a bare realm switch with nothing running never reaches, would fix nothing.
     */
    public void onRealmChanged(String newRealm) {
        clearNudgeState();
        if (session == null) {
            return;
        }
        boolean wasRunning = running;
        saveState(wasRunning ? "INTERRUPTED" : "FINISHED");
        reset();
        HoardkeeperMod.LOGGER.info("Realm changed to {} — the loaded scan was stopped and saved", newRealm);
        if (wasRunning) {
            ScanChat.info(ScanText.realmChangedText());
        }
    }

    /**
     * The rescan nudge's own connection-scoped state, shared by {@link #onDisconnect} and
     * {@link #onRealmChanged}: none of it means anything on the next server or realm this client
     * finds itself on, exactly like the search highlight and {@code RealmKey} cleared alongside
     * {@link #onDisconnect} in {@code HoardkeeperMod}. Deliberately not folded into {@link #reset()}
     * — see the field javadocs on {@link #nudgedClusterIds} and {@link #offerResumePrinted} for why
     * a mid-connection scan starting or resuming must not clear these.
     */
    private void clearNudgeState() {
        nudgedClusterIds.clear();
        offerResumePrinted = false;
        previousNudgeClusterId = null;
    }

    // =========================================================================
    // Resume
    // =========================================================================

    /**
     * Prints a clickable offer to continue an interrupted scan, if there is one for this server and
     * dimension that is younger than {@code resumeMaxAgeMinutes} and still has pending containers.
     *
     * <p><b>Offers, never resumes.</b> This runs right after joining a server. Starting to send
     * interaction packets the moment somebody logs in is precisely the behaviour that gets an
     * account actioned, so the decision — and the packets — wait for a deliberate click on
     * {@code [Resume]}.
     */
    public void offerResume() {
        Minecraft mc = Minecraft.getInstance();
        if (!mayActHere(mc)) {
            // Not an offer anybody could take here — the click would only be refused.
            return;
        }
        if (running || session != null) {
            // Something is already loaded in memory. Offering to replace it would be offering to
            // throw it away.
            return;
        }
        SessionLookup.Found resumable = findResumable(mc);
        if (resumable == null) {
            return;
        }

        SessionSnapshot snapshot = resumable.snapshot();
        int scanned = snapshot.stats == null ? 0 : snapshot.stats.scanned;
        int known = snapshot.stats == null ? 0 : snapshot.stats.known;
        HoardkeeperMod.LOGGER.info("Resumable session found: {} ({} of {} scanned, state={}, updatedAt={})",
                resumable.dir(), scanned, known, snapshot.state, snapshot.updatedAt);
        ScanChat.resumeOffer(scanned, known);
        // Clause 6 of the rescan nudge (design spec §7): two clickable offers competing for one
        // click is worse than the later one waiting for the next session, and this offer is the more
        // urgent of the two — it still has containers pending. See checkRescanNudge.
        offerResumePrinted = true;
    }

    /**
     * Rebuilds the newest resumable session for this server and dimension and continues scanning it.
     * Returns {@code false} when there is nothing to resume (or no world to resume into), leaving
     * every existing state untouched.
     *
     * <p><b>The JSONL is append-only, and a resumed session reopens the very same file.</b> A
     * container written twice would be counted twice in every total the report produces — a wrong
     * answer with no signal, which for this mod is worse than a missing one. Three things stop that,
     * in order:
     * <ol>
     *   <li>{@code SCANNED} candidates are restored as {@code SCANNED}, and {@code TargetSelector}
     *       only ever picks {@code PENDING} ones — so a scanned container is never re-opened.</li>
     *   <li>Every restored candidate is registered in {@code byPackedPos} under both of its block
     *       positions, so re-discovery (the sweep below, and every {@code CHUNK_LOAD} after it)
     *       recognises it instead of adding a second, fresh {@code PENDING} candidate for the same
     *       chest.</li>
     *   <li>{@link #applyAlreadyWritten} reconciles every candidate — restored or freshly
     *       re-discovered — against the file itself. {@code session.json} is written through a
     *       2-second debounce, so a hard crash (as opposed to a clean disconnect, which flushes)
     *       can leave a container present in {@code containers.jsonl} but still {@code PENDING} in
     *       the snapshot. The file is the authority on what has already been written to it, and
     *       {@link #onChunkLoaded} re-applies it to containers that only come into view later.</li>
     * </ol>
     */
    public boolean resume(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            return false;
        }
        SessionLookup.Found resumable = findResumable(mc);
        if (resumable == null) {
            return false;
        }

        reset();

        this.config = HoardkeeperMod.config();
        this.discovery = new ContainerDiscovery(config);
        this.rateLimiter = newRateLimiter(config);
        this.containerCapture = new ContainerCapture(config);
        this.chime = new ScanChime(config);

        SessionSnapshot snapshot = resumable.snapshot();
        this.session = SessionSnapshotCodec.toSession(snapshot);
        this.session.startedNanos = System.nanoTime();
        this.startedAtIso = snapshot.startedAt != null
                ? snapshot.startedAt
                : DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        this.sessionDir = resumable.dir();

        // Same file, opened in append mode: everything scanned before the interruption stays, and
        // this session's remaining containers are appended after it.
        Path jsonl = sessionDir.resolve("containers.jsonl");
        this.alreadyWritten = WrittenPositions.of(ScanStorage.readContainers(jsonl));
        this.writer = new ContainerJsonlWriter(jsonl);
        this.stateStore = new SessionStateStore(sessionDir.resolve("session.json"));

        // Sweep first, reconcile second: the sweep can re-discover a container the snapshot never
        // heard about (discovered and scanned inside the state file's 2-second debounce, then lost
        // to a crash), and that one is in the JSONL too. Reconciling afterwards catches both it and
        // the candidates restored above.
        discovery.sweep(mc.level, session);
        applyAlreadyWritten();

        this.running = true;
        this.lastSweepTick = tick;
        this.scannedAtResume = count(ContainerStatus.SCANNED);
        saveState("RUNNING");

        int scanned = scannedAtResume;
        int pending = count(ContainerStatus.PENDING);
        HoardkeeperMod.LOGGER.info("Scan resumed from {}: known={} scanned={} pending={} failed={}",
                sessionDir, session.candidates.size(), scanned, pending, count(ContainerStatus.FAILED));
        ScanChat.resumed(scanned, session.candidates.size(), pending);
        return true;
    }

    /**
     * Marks every candidate whose position already appears in this session's
     * {@code containers.jsonl} as {@code SCANNED}, so it can never be opened — and therefore never
     * appended — a second time.
     *
     * <p><b>Every caller must call this unconditionally, never gated on the candidate count having
     * grown.</b> {@code ContainerDiscovery.tryPairDoubleChest} adds a candidate and
     * {@code discardOrphan} removes one in the same breath, so the list size can be identical
     * before and after while a brand-new {@code PENDING} candidate now covers an already-written
     * position. Neither the discarded candidate's status nor the {@code byPackedPos} identity can
     * help — the candidate that carried them is the one that was just discarded. Only this
     * reconcile, keyed on the positions actually written and matching either half of a double
     * chest, catches it.
     *
     * <p><b>It applies to a fresh scan exactly as it does to a resumed one.</b> Two paths reach the
     * same duplicate:
     * <ol>
     *   <li><i>Resume:</i> a single chest is scanned and written; the session is interrupted;
     *       somebody places the adjacent chest; on resume, pairing discards the restored
     *       {@code SCANNED} single and registers a fresh {@code PENDING} double over both
     *       positions.</li>
     *   <li><i>Live:</i> the same thing without the interruption — a single chest at A is scanned
     *       and written, someone places a chest at B mid-scan, and the next sweep pairs A+B,
     *       discards the {@code SCANNED} single, and registers a fresh {@code PENDING} double. It
     *       gets scanned and a second row containing A's 27 slots is appended, so
     *       {@code report.totals} counts A's contents twice. Silently: the two rows differ, so
     *       {@code sort | uniq -d} over the file cannot find it.</li>
     * </ol>
     * The resume path was hardened against this; the live path was not, because
     * {@link #alreadyWritten} was only ever populated in {@link #resume}. It is now appended to as
     * each container is written ({@link #onContainerContent}), which makes the two paths identical.
     *
     * <p>The accepted trade-off is unchanged: a container that becomes part of a newly-paired
     * double <em>after</em> its own contents were recorded is left unscanned rather than
     * double-counted. A visible gap beats an invisible duplicate.
     *
     * @return how many candidates this actually changed
     */
    private int applyAlreadyWritten() {
        if (session == null) {
            return 0;
        }
        int marked = 0;
        for (ContainerCandidate candidate : session.candidates) {
            if (candidate.status == ContainerStatus.SCANNED || !alreadyWritten.covers(candidate)) {
                continue;
            }
            candidate.status = ContainerStatus.SCANNED;
            candidate.failReason = null;
            marked++;
        }
        if (marked > 0) {
            HoardkeeperMod.LOGGER.warn("{} candidate(s) were already present in containers.jsonl but not "
                    + "marked SCANNED — marked now, so they are not written (and counted) twice", marked);
        }
        return marked;
    }

    /**
     * The newest session on disk for this server and dimension that is worth continuing: not
     * {@code FINISHED}, updated within {@code resumeMaxAgeMinutes}, and with at least one pending
     * container left to scan.
     */
    private static SessionLookup.Found findResumable(Minecraft mc) {
        if (mc == null || mc.level == null) {
            return null;
        }
        String dimension = mc.level.dimension().identifier().toString();
        Instant cutoff = Instant.now().minusSeconds(
                Math.max(0L, HoardkeeperMod.config().resumeMaxAgeMinutes) * 60L);
        String realm = RealmKey.get().current();

        Path serverDir = ScanStorage.serverDir(gameDir(), ScanStorage.slug(serverAddress(mc)));
        return SessionLookup.newest(serverDir, SessionLookup.resumable(dimension, realm, cutoff));
    }

    // =========================================================================
    // Rescan nudge — design spec §7, re-grounded on the measured map; see RescanNudge
    // =========================================================================

    /**
     * Clauses 4 and 6 of {@code RescanNudge.decide} (spec §8's table: this class owns 4 and 6, the
     * pure class owns 1, 3 and 5), then the pure decision itself. Throttled to
     * {@link #RESCAN_NUDGE_CHECK_INTERVAL_TICKS} because the work below still reads {@code
     * measured.json} once per check — deliberately not routed through {@code StorageIndexCache},
     * whose cache exists for callers asking many times a second (tab completion, the tooltip and peek
     * card's render path); this one asks once a second, which is cheap enough on its own not to be
     * worth sharing a cache with them.
     *
     * <p><b>Fix round 2 (finding C-1/I-1): {@code containers.jsonl} — the whole measured map's rows —
     * is never read here at all any more.</b> The age this method answers with is the newest {@code
     * measuredAt} among the player's cluster's <em>areas</em> ({@link MeasuredMapStore#measuredAreas}),
     * never the newest {@code scannedAt} among its <em>rows</em>. The two used to be conflated, and
     * the conflation was both slow and wrong: {@code store.rows()} parses every row in the map with
     * Gson on this thread — 8 ms/s at 500 containers, 60–80 ms/s at 8 000, measured once a second
     * forever, whether or not the player is even inside a cluster — and passive observation (on by
     * default) stamps a fresh {@code scannedAt} on every hand-opened chest, so folding rows answered
     * "last touched", not "last measured": one hand-opened chest silenced the nudge for that cluster
     * permanently. {@code measuredAt} is written only by an actual scan projection
     * ({@code MeasuredMapStore.recordArea}, called only from {@code projectScanNow}), so it is immune
     * to both problems — and it comes from the same {@code measured.json} this method already has to
     * read to build {@code areas} in the first place, so nothing extra is read to fix either one.
     */
    private void checkRescanNudge(Minecraft mc) {
        if (tick - lastNudgeCheckTick < RESCAN_NUDGE_CHECK_INTERVAL_TICKS) {
            return;
        }
        lastNudgeCheckTick = tick;

        if (!mayActHere(mc)) {
            // An offer to scan where scanning is not allowed would only be refused on click.
            return;
        }
        if (running || session != null || offerResumePrinted) {
            // Clause 4: offering to start a scan over live state is offering to throw it away — the
            // same guard offerResume uses. Clause 6: offerResume already spoke this join; two
            // clickable offers competing for one click is worse than the later one waiting.
            return;
        }
        int thresholdDays = config.rescanSuggestAfterDays;
        if (!RescanNudge.enabled(config.rescanReminder, thresholdDays) || mc.player == null || mc.level == null) {
            return;
        }

        MeasuredMapStore store = MeasuredMapProjector.storeFor(mc);
        if (store == null) {
            return;
        }
        BlockPos pos = mc.player.blockPosition();
        List<MeasuredMapStore.MeasuredArea> measuredAreas = store.measuredAreas();
        List<ScanArea> areas = measuredAreas.stream().map(MeasuredMapStore.MeasuredArea::area).toList();
        List<ScanArea> cluster = StorageClusters.covering(areas, pos.getX(), pos.getZ());
        // Fix round 2, finding I-2: geometry alone collides an Overworld base with a Nether hub
        // scanned around the same chunk — see RescanNudge.clusterId's own javadoc.
        String dimension = mc.level.dimension().identifier().toString();
        String currentClusterId = RescanNudge.clusterId(cluster, dimension);

        // currentClusterId == null already means "nothing to nudge about" to RescanNudge.decide, so
        // there is nothing to fold in that case either — not because folding would cost a disk read
        // (measuredAreas() above already paid for measured.json this check, whichever way this comes
        // out), but because an empty cluster has no areas to take a newest measuredAt from.
        String newestMeasuredAtIso = currentClusterId == null ? null
                : newestMeasuredAt(measuredAreas, cluster);

        OptionalLong days = RescanNudge.decide(previousNudgeClusterId, currentClusterId, newestMeasuredAtIso,
                System.currentTimeMillis(), thresholdDays, nudgedClusterIds);
        previousNudgeClusterId = currentClusterId;

        if (days.isPresent()) {
            nudgedClusterIds.add(currentClusterId);
            ScanChat.rescanOffer(days.getAsLong());
        }
    }

    /**
     * The newest {@code measuredAt} among the {@code measuredAreas} entries whose area belongs to
     * {@code cluster}, or {@code null} when none of them parse — the composition
     * {@link #checkRescanNudge} feeds {@link RescanNudge#newestScannedAt}. {@link ScanArea} is a
     * record with value equality, so membership in {@code cluster} (itself built from the same
     * {@code measuredAreas} list by {@link StorageClusters#covering}) is exact.
     *
     * <p>Package-private and static so {@code RescanNudgeAgeSourceTest} can pin fix round 2's whole
     * point — a cluster touched seconds ago but measured weeks ago still offers a rescan — without a
     * running game: it calls this directly on a real {@code MeasuredMapStore}, rather than on
     * {@link #checkRescanNudge}, which needs a live {@code Minecraft} this class has no seam to fake.
     */
    static String newestMeasuredAt(List<MeasuredMapStore.MeasuredArea> measuredAreas, List<ScanArea> cluster) {
        List<String> timestamps = measuredAreas.stream()
                .filter(measured -> cluster.contains(measured.area()))
                .map(MeasuredMapStore.MeasuredArea::measuredAt)
                .toList();
        return RescanNudge.newestScannedAt(timestamps);
    }

    // =========================================================================
    // Report export
    // =========================================================================

    /** A written report, plus which session it describes — the command line names both. */
    public record ExportResult(Path report, String sessionId, String dimension) {
    }

    /**
     * Aggregates a session's {@code containers.jsonl} into {@code report.json} beside it and returns
     * it together with the identity of the session it came from, or {@code null} when there is
     * nothing to export.
     *
     * <p>Exports the in-memory session when there is one (running or stopped — {@link #stop} keeps
     * it). Otherwise it takes the newest session on disk <em>for the current dimension</em>, falling
     * back to the newest overall when this dimension has none — so a nether scan followed by an
     * overworld one exports the overworld from the overworld. The result names the session and its
     * dimension either way: a player must never have to infer which scan they just exported from a
     * timestamp in a path.
     *
     * <p><b>The failure list never comes from the JSONL.</b> That file holds scan output — only
     * containers that were successfully read. Failures come from the session's candidates (or the
     * snapshot's, when exporting from disk), and local skips are kept out of {@code failed}:
     * {@code BLOCKED} and {@code GONE} are decided on the client before a packet is ever sent, so
     * listing them among the server's refusals would bury the failures that actually are.
     *
     * <p><b>They are still reported, in their own list.</b> Keeping local skips out of the failure
     * <em>guard</em> is right; keeping them out of the <em>report</em> is not. A verification run
     * that found 122 containers, read 121 and skipped one wrote {@code {"scanned":121,"failed":0}},
     * and nothing in the file the player actually reads said a container had gone unopened. So
     * {@code known} and {@code skipped} are exported alongside, from the same candidates.
     */
    public ExportResult export(Minecraft mc) {
        Path dir;
        SessionExporter.Header header;
        List<ScanReport.FailureEntry> failures;
        List<ScanReport.FailureEntry> skipped;
        int known;

        if (session != null && sessionDir != null) {
            if (writer != null) {
                // Drain queued appends first: a report that silently omits the last container or two
                // of a running scan is exactly the kind of quiet wrongness this mod must not produce.
                writer.flush();
            }
            dir = sessionDir;
            header = SessionExporter.Header.of(session);
            failures = SessionExporter.failuresOf(session.candidates);
            skipped = SessionExporter.skippedOf(session.candidates);
            known = session.candidates.size();
        } else {
            SessionLookup.Found newest = newestForExport(mc);
            if (newest == null) {
                HoardkeeperMod.LOGGER.warn("Nothing to export: no session in memory and none on disk");
                return null;
            }
            dir = newest.dir();
            header = SessionExporter.Header.of(newest.snapshot());
            failures = SessionExporter.failuresOf(newest.snapshot());
            skipped = SessionExporter.skippedOf(newest.snapshot());
            known = SessionExporter.knownOf(newest.snapshot());
        }

        List<ScannedContainer> containers = ScanStorage.readContainers(dir.resolve("containers.jsonl"));
        Path out = SessionExporter.write(dir, header, known, containers, failures, skipped,
                maxStackSizes(containers));
        return out == null ? null : new ExportResult(out, header.sessionId(), header.dimension());
    }

    /**
     * The session an export without a live session should use: the newest one recorded for the
     * dimension the player is standing in, or — when this dimension has none — the newest recorded
     * for this server at all.
     */
    private static SessionLookup.Found newestForExport(Minecraft mc) {
        if (mc != null && mc.level != null) {
            String dimension = mc.level.dimension().identifier().toString();
            Path serverDir = ScanStorage.serverDir(gameDir(), ScanStorage.slug(serverAddress(mc)));
            SessionLookup.Found here = SessionLookup.newest(serverDir, SessionLookup.exportable(dimension));
            if (here != null) {
                return here;
            }
        }
        Path serverDir = ScanStorage.serverDir(gameDir(), ScanStorage.slug(serverAddress(mc)));
        return SessionLookup.newest(serverDir, snapshot -> true);
    }

    /**
     * Maximum stack size per item id, for the ids this scan actually saw.
     *
     * <p>{@code ReportBuilder} deliberately takes this as a parameter instead of reaching into the
     * item registry itself — that is what keeps it pure and unit-testable without a running game.
     * Ids the registry does not know are left out entirely and default to 64 inside the builder, so
     * an item from a mod that is no longer installed degrades the "stacks" figure rather than
     * failing the export.
     */
    private static Map<String, Integer> maxStackSizes(List<ScannedContainer> containers) {
        Set<String> ids = new LinkedHashSet<>();
        for (ScannedContainer container : containers) {
            SessionExporter.collectItemIds(container.items, ids);
        }

        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (String id : ids) {
            Identifier key = Identifier.tryParse(id);
            if (key == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(key);
            // ITEM is a DefaultedRegistry: an unknown id hands back AIR rather than null, so the
            // round trip through getKey is what actually distinguishes "resolved" from "defaulted".
            if (item == null || !key.equals(BuiltInRegistries.ITEM.getKey(item))) {
                continue;
            }
            sizes.put(id, item.getDefaultMaxStackSize());
        }
        return sizes;
    }

    // =========================================================================
    // Tick loop
    // =========================================================================

    /** Called once per client tick from {@code ClientTickEvents.END_CLIENT_TICK}. */
    public void onClientTick(Minecraft mc) {
        tick++;
        // Runs whether or not a scan is loaded — clause 4 below is exactly "no scan running and no
        // session in memory", the opposite of the guard the rest of this method is behind.
        checkRescanNudge(mc);
        if (!running || session == null) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (target != null) {
            checkTimeout(mc);
        }

        if (tickIdleCountdown(mc)) {
            return;   // the scan just finished itself; nothing below applies to it any more
        }

        if (tick - lastSweepTick >= config.discoverySweepIntervalTicks) {
            lastSweepTick = tick;
            int before = session.candidates.size();
            discovery.sweep(mc.level, session);
            if (session.candidates.size() > before) {
                allResolvedAnnounced = false;   // there is work again; the next lull is worth saying
            }
            // Unconditional, NOT gated on the count: discovery can replace one candidate with
            // another (see applyAlreadyWritten) and leave the size unchanged. This is what protects
            // a fresh scan from the live re-pairing duplicate, not just a resumed one.
            applyAlreadyWritten();
        }

        if (target == null) {
            trySend(mc);
        }
    }

    /**
     * Two separate deadlines: {@code openTimeoutTicks} covers "we sent an interaction and the
     * server never opened anything", {@code contentTimeoutTicks} covers "the menu opened but the
     * contents never followed". The second case owes the server a close packet; the first does not,
     * because no menu id is known and none was opened.
     */
    private void checkTimeout(Minecraft mc) {
        int limit = opened ? config.contentTimeoutTicks : config.openTimeoutTicks;
        if (tick - sentTick <= limit) {
            return;
        }
        ContainerCandidate candidate = target;
        boolean wasOpen = opened;
        int id = containerId;
        clearRequest();

        if (wasOpen) {
            closeMenu(mc.player, id);
        }
        fail(candidate, wasOpen ? FailReason.NO_CONTENT : FailReason.NO_RESPONSE);
    }

    /**
     * Everything that must hold before an interaction may be sent, followed by target selection
     * and a last re-read of the world.
     */
    private void trySend(Minecraft mc) {
        String blocked = gate(mc);
        // Observes the gate's own screen check without changing it (see gate()'s javadoc): a
        // ClientboundOpenScreenPacket that arrives after the in-flight request already timed out is
        // let through by onOpenScreen and opens a real container GUI, and the gate is what pauses
        // the scanner while it is open. That pause is correct but was silent — the player had no
        // indication the scan had stopped, or why. Only the open-screen reason gets this treatment;
        // sneaking and use-key-held stay silent because the player already knows what they are doing.
        if (screenBlockWatcher.update(mc.gui.screen() != null, tick)) {
            ScanChat.screenBlocked();
        }
        if (blocked != null) {
            gateBlocked(blocked);
            return;
        }
        // Normal pacing, deliberately not routed through gateBlocked: "we sent one four ticks ago"
        // is the scanner working as designed, not a condition anybody needs to read about.
        if (!rateLimiter.ready(tick)) {
            return;
        }

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        Vec3 eyeVec = player.getEyePosition();
        double[] eye = {eyeVec.x, eyeVec.y, eyeVec.z};

        // pickWithHalf hands back WHICH half of a double chest was in reach, so the reach test that
        // chose this candidate and the position we actually interact with can never disagree.
        TargetSelector.Selection selection = TargetSelector.pickWithHalf(session.candidates, eye, tick,
                (c, p) -> inReach(player, p));
        if (selection == null) {
            gateBlocked("nothing pending in reach");
            return;
        }
        ContainerCandidate candidate = selection.candidate();
        int[] half = selection.half();

        // The world may have changed since discovery: the chest may be gone, or something may have
        // been placed on top of it. Both are decided here, before a single packet is spent.
        BlockPos pos = new BlockPos(half[0], half[1], half[2]);

        // "The block is not there" and "the chunk holding the block is not loaded" are different
        // facts, and only the first justifies retiring a candidate. ClientLevel.getBlockState
        // answers air for a position in an unloaded chunk, so without this check kindOf() below
        // returns null and the candidate is retired as GONE — a local skip, which is deliberately
        // never retried and deliberately not counted as a failure in the report. A resumed session
        // restores every candidate at once while its chunks are still streaming in, so that path
        // dropped real containers and presented the result as a clean scan. Leaving the candidate
        // PENDING and skipping it this tick costs nothing: a block in an unloaded chunk cannot be
        // within interaction range anyway, and the next tick re-evaluates it once the chunk lands.
        if (!isChunkLoaded(level, pos)) {
            gateBlocked("chunk not loaded at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            return;
        }

        BlockState state = level.getBlockState(pos);
        if (discovery.kindOf(state) == null) {
            fail(candidate, FailReason.GONE);
            return;
        }
        if (state.getBlock() instanceof ChestBlock && ChestBlock.isChestBlockedAt(level, pos)) {
            fail(candidate, FailReason.BLOCKED);
            return;
        }

        target = candidate;
        sentTick = tick;
        containerId = -1;
        menuTypeId = null;
        menuTitle = null;
        opened = false;
        lastGateReason = null;

        rateLimiter.onSend(tick);
        InteractionSender.open(mc, player, pos, config.swingArmOnOpen);
    }

    /**
     * Returns a human-readable reason why no interaction may be sent right now, or {@code null}
     * when the way is clear.
     *
     * <p>The screen and sneaking checks are correctness requirements, NOT preferences, and are
     * enforced regardless of config: {@code ClientPacketListener.handleContainerClose} checks no
     * container id and blindly calls {@code clientSideCloseContainer()}, so the server closing our
     * phantom menu would slam shut whatever screen the player has open; and
     * {@code isSecondaryUseActive()} diverts {@code performUseItemOn} into the item-use branch,
     * where the client predicts placing whatever block is in hand.
     */
    private String gate(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            return "no player/level/gameMode";
        }
        // MC 26.2 moved the current screen off Minecraft: 26.1.2 had a public field `mc.screen`,
        // 26.2 has a private field on Gui reached through the accessor `mc.gui.screen()`. Same
        // value, same null-means-no-screen contract — only the path to it changed. This is the
        // canonical open-screen predicate; trySend's watcher must read it the same way, or it
        // would announce pauses that are not happening (see its call site).
        Screen screen = mc.gui.screen();
        if (screen != null) {
            return "screen open: " + screen.getClass().getSimpleName();
        }
        if (player.isShiftKeyDown() || player.isSecondaryUseActive()) {
            return "sneaking";
        }
        if (mc.options.keyUse.isDown()) {
            return "use key held";
        }
        if (!player.connection.isAcceptingMessages()) {
            return "connection not accepting";
        }
        return null;
    }

    /**
     * Whether the client actually holds the chunk containing {@code pos}. Uses the same
     * {@code getChunk(cx, cz, FULL, false)} probe as {@link ContainerDiscovery#sweep}, so
     * "loaded" means exactly the same thing on the discovery side and on the send side.
     */
    private static boolean isChunkLoaded(ClientLevel level, BlockPos pos) {
        return level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null;
    }

    private boolean inReach(LocalPlayer player, int[] p) {
        // Negative padding shrinks OUR reach: the server grants +1.0 of slack, and the scanner
        // deliberately stays well inside it rather than living on that tolerance.
        return player.isWithinBlockInteractionRange(new BlockPos(p[0], p[1], p[2]), -config.reachMargin);
    }

    /**
     * Advances the quiet-time countdown and, when it runs out, finishes the scan on the player's
     * behalf. Returns true when it did, so the caller stops touching a session that no longer exists.
     *
     * <p>The countdown is paused by exactly the conditions {@link #gate} reports. That is not a
     * coincidence to be maintained by hand: those are the states in which the scanner is forbidden to
     * act at all — a screen open, sneaking, the use key held, the connection stalled — and none of
     * them mean there is nothing left to scan. Reading the same predicate is what keeps "the scanner
     * is paused" and "the countdown is paused" from drifting apart.
     */
    private boolean tickIdleCountdown(Minecraft mc) {
        // Done is done, whatever the clock says. Deliberately checked before `idle.enabled()` and
        // before the gate: neither has anything to say here. A countdown that is switched off does
        // not make an empty area unfinished, and a gate means the scanner may not *act* -- opening
        // a chest, sneaking -- while finishing acts on nothing. Waiting out the quiet time with an
        // answer already in hand is the mod making somebody stand around for it.
        if (session != null && ScanCompletion.nothingLeftToScan(count(ContainerStatus.PENDING),
                session.chunksLoaded, session.chunksInRadius)) {
            return finishNow(mc, true);
        }
        if (!idle.enabled()) {
            return false;
        }
        idle.update(System.currentTimeMillis(), gate(mc) != null);

        if (!idle.expired()) {
            // Held back for the first seconds of quiet so the bar does not flash between containers
            // at the normal five-a-second pace. When it does appear, its appearance is the message.
            if (config.actionBarProgress && idle.elapsedMillis() >= AUTO_FINISH_BAR_DELAY_MILLIS
                    && mayWriteBar(ActionBarOwner.Writer.AUTO_FINISH, true)) {
                ScanChat.autoFinishBar(idle.fraction(), idle.remainingSeconds());
            }
            return false;
        }

        return finishNow(mc, false);
    }

    /**
     * Ends the scan and does what a finished scan does: say so, and let the add-ons act on it.
     * {@code complete} distinguishes the two ways to get here, which read differently to the
     * player — "everything in reach is scanned" against "no containers for 30 seconds".
     */
    private boolean finishNow(Minecraft mc, boolean complete) {
        String sessionId = session.sessionId;
        int scanned = count(ContainerStatus.SCANNED);
        int chunks = session.chunksInRadius;
        // Captured before stop(), which lets go of the session.
        String purpose = session.purpose;
        stop(mc, complete ? "complete" : "auto-finish");
        if (complete) {
            ScanChat.autoFinishedComplete(scanned, chunks);
        } else {
            ScanChat.autoFinished(config.autoFinishSeconds);
        }

        // What happens next — an upload, a site summary — is an add-on's business (spec
        // 2026-09-30 §6). The core has said what it has to say.
        Addons.onScanAutoFinished(mc, new Addon.ScanFinished(sessionId, scanned, purpose));
        return true;
    }

    /** Logs a gate reason once when it changes, then at most every five seconds while it persists. */
    private void gateBlocked(String why) {
        if (!why.equals(lastGateReason)) {
            lastGateReason = why;
            lastGateLogTick = tick;
            HoardkeeperMod.LOGGER.debug("scan idle: {}", why);
        } else if (tick - lastGateLogTick >= 100) {
            lastGateLogTick = tick;
            HoardkeeperMod.LOGGER.debug("scan still idle: {}", why);
        }
    }

    // =========================================================================
    // Packet callbacks — client thread, see the class javadoc
    // =========================================================================

    /**
     * @return {@code true} to cancel the packet, which suppresses the screen entirely because the
     *         injection sits ahead of {@code MenuScreens.create(...)}.
     */
    public boolean onOpenScreen(int id, MenuType<?> type, Component title) {
        // Answers for this menu only, and every path out of this method is a fresh answer — the
        // caller reads it immediately after, on the same thread, before another packet can arrive.
        abandonedLastMenu = false;

        // Container id 0 is the player's own inventory. Never match it, never cancel it.
        if (id == 0 || target == null || opened) {
            return false;
        }
        if (tick - sentTick > config.openTimeoutTicks) {
            // Stale: onClientTick is about to time this request out (or already did). Let the
            // screen through rather than attributing a late menu to a request we gave up on.
            return false;
        }
        if (playerUsedSomethingRecently()) {
            // This menu may well be the PLAYER'S, not ours, and there is nothing in the packet that
            // says which. Swallowing it would write their chest's contents under our target's
            // coordinates and mark our target SCANNED — a wrong answer with no signal, which is
            // strictly worse than a missing one for a mod whose whole job is "what do I have?".
            // So: hand the screen to the player, give up our request, and leave the candidate
            // PENDING for a later pass. See playerUsedSomethingRecently() for why this is exact.
            HoardkeeperMod.LOGGER.info(
                    "Player right-clicked within the last 4 ticks (rightClickDelay={}) — not swallowing "
                            + "menu '{}'; abandoning the in-flight request for {}",
                    Minecraft.getInstance().rightClickDelay, menuTypeId(type), ScanText.describe(target));
            clearRequest();
            abandonedLastMenu = true;
            return false;
        }

        String typeId = menuTypeId(type);
        if (MenuSlotCounts.expectedFor(typeId) <= 0) {
            // Something opened that is not a container we know how to read — most likely a plugin
            // GUI triggered by the same right-click. Give the request up and let the player's own
            // client show it, exactly as it would without this mod.
            HoardkeeperMod.LOGGER.warn("Unexpected menu '{}' (title '{}') while scanning {} — letting it through",
                    typeId, title.getString(), ScanText.describe(target));
            ContainerCandidate candidate = target;
            clearRequest();
            fail(candidate, FailReason.UNEXPECTED_MENU);
            return false;
        }

        this.containerId = id;
        this.menuTypeId = typeId;
        this.menuTitle = title.getString();
        this.opened = true;
        return true;
    }

    /**
     * Whether the menu {@link #onOpenScreen} just let through was one this controller gave up an
     * in-flight request for, because the player had right-clicked within the last four ticks and
     * nothing in the packet says whose menu it is.
     *
     * <p>The scanner's own answer to that ambiguity is to abandon its request and leave the
     * candidate {@code PENDING} — a missing answer rather than a wrong one. Anyone else reading the
     * same menu owes the same caution: the passive observer would otherwise pair this menu with the
     * player's pending click and file <em>this</em> container's contents under <em>that</em>
     * container's position, which then travels to the shared catalogue as the newest word on it.
     *
     * <p>Valid only immediately after {@link #onOpenScreen} returned {@code false}, on the client
     * thread, before the next packet is handled.
     */
    public boolean abandonedLastMenu() {
        return abandonedLastMenu;
    }

    /**
     * @return {@code true} to cancel the packet, so the contents never reach a menu that was never
     *         built.
     */
    public boolean onContainerContent(int id, List<ItemStack> items) {
        if (id == 0 || !opened || id != this.containerId) {
            return false;
        }

        // opened == true implies target != null: onOpenScreen is the only writer of both, it sets
        // them together, and clearRequest() clears them together. No null check is reachable here.
        LocalPlayer player = Minecraft.getInstance().player;
        ContainerCandidate candidate = target;

        int slots = MenuSlotCounts.resolve(items.size());
        PhantomMenu.opened(id, slots);
        if (slots <= 0) {
            HoardkeeperMod.LOGGER.warn("Menu '{}' carried {} items — cannot derive a slot count",
                    menuTypeId, items.size());
            closeMenu(player, id);
            clearRequest();
            fail(candidate, FailReason.BAD_MENU);
            return true;
        }

        if (MenuSlotCounts.mismatches(menuTypeId, slots)) {
            // The derived count wins on purpose — a plugin can claim any menu type id it likes but
            // cannot misrepresent the packet's actual size. Saying so out loud is what turns a
            // silently mis-sized container into something a player can go and look at.
            HoardkeeperMod.LOGGER.warn("Menu '{}' claims {} slots but carried {} — trusting the "
                            + "derived count for {}", menuTypeId, MenuSlotCounts.expectedFor(menuTypeId),
                    slots, ScanText.describe(candidate));
        }

        ScannedContainer scanned = capture(candidate, slots, items);
        if (writer != null) {
            writer.append(scanned);
        }
        // Record both halves the moment the row is written, so the very next sweep's reconcile can
        // see it. This is the whole of the live path's duplicate defence — see applyAlreadyWritten.
        alreadyWritten.add(candidate.pos, candidate.secondaryPos);
        closeMenu(player, id);

        candidate.status = ContainerStatus.SCANNED;
        candidate.failReason = null;
        candidate.observedAt = System.currentTimeMillis();
        idle.reset(System.currentTimeMillis());
        rateLimiter.onSuccess();
        session.lastScanNanos = System.nanoTime();
        clearRequest();
        saveState("RUNNING");
        progress(candidate, slots);
        // Only here, on the path where the contents are already written: an `unread` or `gone`
        // container must stay silent, or a scan that is quietly failing sounds exactly like one
        // that is working. Local, so nobody else on the server hears it — see ScanChime.
        chime.onScanned(Minecraft.getInstance().level, candidate);
        announceIfAllResolved();
        return true;
    }

    /**
     * Builds the on-disk record for a scanned container.
     */
    private ScannedContainer capture(ContainerCandidate candidate, int slots, List<ItemStack> items) {
        return containerCapture.capture(items, slots, candidate, session.dimension, menuTypeId, menuTitle);
    }

    // =========================================================================
    // Discovery hook
    // =========================================================================

    /**
     * Picks up containers in a chunk that finished loading mid-scan, so walking into new territory
     * does not have to wait for the next periodic sweep.
     */
    public void onChunkLoaded(ClientLevel level, LevelChunk chunk) {
        if (!running || session == null || level == null || chunk == null) {
            return;
        }
        if (!session.dimension.equals(level.dimension().identifier().toString())) {
            return;
        }
        int before = session.candidates.size();
        discovery.scanChunk(level, chunk, session);
        if (session.candidates.size() > before) {
            allResolvedAnnounced = false;
        }
        // A loading chunk can complete a double chest whose other half this scan already wrote —
        // or, in a resumed session, bring an already-scanned container back into view. Re-scanning
        // either would append a second line for contents already on disk.
        applyAlreadyWritten();
    }

    // =========================================================================
    // Failure classification
    // =========================================================================

    /**
     * Records a failed attempt and decides whether the candidate gets another go.
     *
     * <p>The branch on {@link FailReason#isLocalSkip()} is the important part. {@code BLOCKED} and
     * {@code GONE} are decided on the client before any packet is sent — they mean "skip this
     * one", not "the server refused us". Folding them into the same counter as real remote
     * failures is what killed a spike run: 52 harmless local skips (chests buried under solid
     * blocks) tripped the failure guard and aborted an otherwise perfect scan.
     */
    private void fail(ContainerCandidate candidate, FailReason reason) {
        candidate.attempts++;
        candidate.failReason = reason;
        candidate.observedAt = System.currentTimeMillis();
        // A failure restarts the quiet period just as a success does. The countdown asks "is the
        // scanner still working through containers?", and a container it tried and could not read
        // is work. Counting only successes would end a scan standing in front of a row of chests
        // a protection plugin keeps refusing -- the one situation where the player most wants to
        // still be there.
        idle.reset(System.currentTimeMillis());

        if (reason.isLocalSkip()) {
            candidate.status = ContainerStatus.FAILED;   // silent: no chat, no backoff, no retry
            HoardkeeperMod.LOGGER.debug("skip {} — {}", ScanText.describe(candidate), reason);
            // Silent about the skip itself, but a skip still retires a candidate — and if it was
            // the last PENDING one, "nothing left to do" is exactly the moment worth announcing.
            announceIfAllResolved();
            return;
        }

        rateLimiter.onRemoteFailure();
        if (candidate.attempts < config.maxAttemptsPerContainer) {
            candidate.status = ContainerStatus.PENDING;
            candidate.cooldownUntilTick = tick + config.retryCooldownTicks;
        } else {
            candidate.status = ContainerStatus.FAILED;
        }

        // Every failure is logged and lands in the session state and the report regardless of what
        // chat ends up showing.
        HoardkeeperMod.LOGGER.info("Container {} failed: {} (attempt {}/{}, {} consecutive)",
                ScanText.describe(candidate), reason, candidate.attempts, config.maxAttemptsPerContainer,
                rateLimiter.consecutiveFailures());
        ScanChat.failure(tick, config.chatOnFailure, candidate, reason);
        saveState("RUNNING");
        announceIfAllResolved();
    }

    /**
     * Says once, when the last {@code PENDING} candidate is retired, that there is nothing left to
     * do — otherwise a run in which everything failed simply stops sending packets, which looks
     * exactly like a run that finished cleanly. The scan deliberately keeps running: newly loaded
     * chunks may still bring work, and any of those re-arms this message.
     */
    private void announceIfAllResolved() {
        if (allResolvedAnnounced || !running || session == null || session.candidates.isEmpty()) {
            return;
        }
        if (count(ContainerStatus.PENDING) > 0) {
            return;
        }
        allResolvedAnnounced = true;

        int scanned = count(ContainerStatus.SCANNED);
        int failed = count(ContainerStatus.FAILED);
        HoardkeeperMod.LOGGER.info("All {} known candidates resolved: scanned={} failed={}",
                session.candidates.size(), scanned, failed);
        ScanChat.allResolved(session.candidates.size(), scanned, failed, failureCounts());
    }

    /** Failed candidates grouped by {@link FailReason}, for {@code ScanChat}'s breakdown line. */
    private Map<FailReason, Integer> failureCounts() {
        Map<FailReason, Integer> byReason = new EnumMap<>(FailReason.class);
        for (ContainerCandidate candidate : session.candidates) {
            if (candidate.status == ContainerStatus.FAILED && candidate.failReason != null) {
                byReason.merge(candidate.failReason, 1, Integer::sum);
            }
        }
        return byReason;
    }

    /**
     * Re-queues every container that failed for a reason worth another try. Local skips
     * ({@code BLOCKED}, {@code GONE}) stay failed: nothing about re-sending would change them, and
     * the next periodic sweep re-evaluates the world anyway.
     */
    public void retryFailed() {
        if (session == null) {
            return;
        }
        // A finished scan keeps its session in memory but nothing that could act on it: stop() set
        // running = false, so onClientTick returns at its first line, and closeStores() dropped the
        // writer. Re-queueing here used to flip FAILED to PENDING, announce "N containers queued
        // again", and then never send anything — leaving those containers pending for good, with
        // their failReason cleared on the way out, so /hoard status could no longer say what
        // had gone wrong. A finished session is not resumable either (findResumable skips state
        // FINISHED), which is why the message names a new scan and not resume.
        if (!running) {
            ScanChat.info(ScanText.retryNotRunningText());
            return;
        }
        int requeued = 0;
        for (ContainerCandidate candidate : session.candidates) {
            if (candidate.status != ContainerStatus.FAILED) {
                continue;
            }
            if (candidate.failReason != null && candidate.failReason.isLocalSkip()) {
                continue;
            }
            candidate.status = ContainerStatus.PENDING;
            candidate.failReason = null;
            candidate.attempts = 0;
            candidate.cooldownUntilTick = 0;
            requeued++;
        }
        if (requeued > 0) {
            allResolvedAnnounced = false;   // there is work again
        }
        HoardkeeperMod.LOGGER.info("retry-failed: re-queued {} candidates", requeued);
        ScanChat.requeued(requeued);
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public boolean isRunning() {
        return running;
    }

    /**
     * One line of progress for {@code /hoard status}: counts, rate and chunk coverage, or
     * "no session" when nothing has been started. Gathers the numbers here — the wording lives in
     * {@code ScanText} like every other user-facing string.
     */
    public String statusText() {
        if (session == null) {
            return ScanText.noSessionText();
        }
        int scanned = count(ContainerStatus.SCANNED);
        return ScanText.statusText(running, scanned, session.candidates.size(),
                count(ContainerStatus.FAILED), count(ContainerStatus.PENDING),
                perSecond(scanned, elapsedMillis()), session.chunksLoaded, session.chunksInRadius);
    }

    /** The current session, or {@code null} when none has been started (or it was reset). */
    public ScanSession session() {
        return session;
    }

    /** The container currently being opened, or {@code null} when nothing is in flight. */
    public ContainerCandidate currentTarget() {
        return target;
    }

    /** Where this session writes {@code containers.jsonl} and {@code session.json}. */
    public Path sessionDir() {
        return sessionDir;
    }

    /**
     * The monotonic client-tick counter — see the field javadoc for why it never resets. Exposed
     * so a per-frame renderer (e.g. {@code ScanHudRenderer}) can tell whether a tick has actually
     * elapsed since it last recomputed anything, instead of redoing that work every frame.
     */
    public int tick() {
        return tick;
    }

    /** Milliseconds since this leg of the session began — since the start, or since the resume. */
    private long elapsedMillis() {
        return session == null ? 0L : (System.nanoTime() - session.startedNanos) / 1_000_000L;
    }

    /**
     * Containers per second over this leg. The single definition of the scan rate: the summary, the
     * status line and the HUD all come through here, so they can never disagree about it.
     *
     * <p>Containers scanned before a resume are not credited to the resumed leg's clock — see
     * {@link #scannedAtResume}. Clamped at zero because discovery can retire a {@code SCANNED}
     * candidate (a scanned single chest replaced by the double it just became part of), which would
     * otherwise briefly push the count below the baseline.
     */
    private double perSecond(int scanned, long millis) {
        int inThisLeg = Math.max(0, scanned - scannedAtResume);
        return millis > 0 ? inThisLeg * 1000.0 / millis : 0.0;
    }

    /**
     * The current scan rate in containers per second, for callers that do not already have the
     * scanned count to hand (the HUD). Zero when there is no session.
     */
    public double perSecond() {
        return session == null ? 0.0 : perSecond(count(ContainerStatus.SCANNED), elapsedMillis());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Forgets the in-flight request without touching the candidate's status. */
    private void clearRequest() {
        target = null;
        sentTick = 0;
        containerId = -1;
        menuTypeId = null;
        menuTitle = null;
        opened = false;
    }

    /** Closes an in-flight menu (if one is open) and forgets the request. */
    private void abortInFlight(Minecraft mc) {
        if (opened) {
            closeMenu(mc == null ? null : mc.player, containerId);
        }
        clearRequest();
    }

    /**
     * Sends a close for a menu we opened, if there is anything to close it with. Container id 0 is
     * the player's own inventory and is never ours to close.
     */
    private static void closeMenu(LocalPlayer player, int id) {
        if (player != null && id > 0) {
            InteractionSender.close(player, id);
        }
    }

    /**
     * True when the player themselves right-clicked within the last four ticks.
     *
     * <p>{@code Minecraft.startUseItem()} writes a constant {@code 4} into
     * {@code Minecraft.rightClickDelay} and {@code Minecraft.tick()} decrements it. Verified against
     * the 26.2 bytecode (and, before the retarget, 26.1.2 — the field and its four accesses are
     * unchanged between them): {@code Minecraft.class} is the ONLY class in the entire client tree
     * that so much as names that field, {@code startUseItem} is reachable only from
     * {@code handleKeybinds()} (i.e. only from real input), and our own
     * {@code MultiPlayerGameMode.useItemOn(...)} call bypasses that path completely. So a non-zero
     * value means "the player used something very recently" and never "the scanner did".
     *
     * <p>That is what makes it a sound tie-breaker for a menu arriving while a scan request is in
     * flight — a determination the {@code keyUse} gate cannot make on its own, because the gate is
     * sampled when we send and the ambiguous click can land after that, inside the ~2-tick round
     * trip.
     */
    private static boolean playerUsedSomethingRecently() {
        return Minecraft.getInstance().rightClickDelay > 0;
    }

    /**
     * Writes a snapshot through the debounced state store. Cheap to call often — the store keeps
     * at most one write per two seconds and does it on its own thread.
     */
    private void saveState(String state) {
        if (stateStore == null || session == null) {
            return;
        }
        SessionSnapshot snapshot = session.toSnapshot(state);
        snapshot.startedAt = startedAtIso;
        snapshot.updatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        stateStore.save(snapshot);
    }

    /** Closes both output files. Null-guarded so it is safe to call twice. */
    private void closeStores() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
        if (stateStore != null) {
            stateStore.close();
            stateStore = null;
        }
    }

    private int count(ContainerStatus status) {
        if (session == null) {
            return 0;
        }
        return CandidateCounts.count(session.candidates, status);
    }

    /**
     * Whether {@code who} may write the action bar right now.
     *
     * <p>{@code autoFinishWants} is passed in rather than read here because only the caller inside
     * the countdown knows whether the countdown has anything to say yet — and a writer that is
     * silent must not hold the bar away from the one below it.
     */
    private boolean mayWriteBar(ActionBarOwner.Writer who, boolean autoFinishWants) {
        return ActionBarOwner.mayWrite(who,
                SearchService.get().ownsActionBar(),
                autoFinishWants,
                Addons.ownsActionBar(),
                true);
    }

    /**
     * The scan's own action-bar line stands down for every other writer — see
     * {@link ActionBarOwner}, which holds the order in one place so this class does not have to
     * know who else exists. Chat is untouched: only the one-line overlay is contested, and the HUD
     * panel keeps showing the scan's numbers throughout.
     */
    private void progress(ContainerCandidate candidate, int slots) {
        int scanned = count(ContainerStatus.SCANNED);
        ScanChat.progress(scanned, session.candidates.size(), candidate, slots,
                config.actionBarProgress && mayWriteBar(ActionBarOwner.Writer.SCAN_PROGRESS, false),
                config.chatPerContainer, config.chatEveryN);
        HoardkeeperMod.LOGGER.debug("scanned {} ({} slots)", ScanText.describe(candidate), slots);
    }

    private static String menuTypeId(MenuType<?> type) {
        Identifier key = type == null ? null : BuiltInRegistries.MENU.getKey(type);
        return key == null ? "?" : key.toString();
    }

    private static RateLimiter newRateLimiter(HoardkeeperConfig config) {
        return new RateLimiter(config.minTicksBetweenOpens, config.backoffAfterConsecutiveFailures,
                config.maxBackoffTicks);
    }

    /**
     * The server address a session's directory slug is derived from ({@link ScanStorage#slug}).
     * Public so {@code upload.SessionUploader} can resolve the exact same {@code hoardkeeper/
     * <slug>/} directory this class writes into, instead of duplicating (and risking drifting from)
     * the address-resolution rule.
     */
    /**
     * Whether the scanner may open containers by itself here — spec 2026-09-30-hoardkeeper-split-
     * design.md §4. Singleplayer (and a world hosted on LAN) always; a server only when
     * {@code scanServers} names it. Everything that observes rather than acts works everywhere.
     */
    public static boolean mayActHere(Minecraft mc) {
        return mc.hasSingleplayerServer()
                || ServerList.listed(serverAddress(mc), HoardkeeperMod.config().scanServers);
    }

    public static String serverAddress(Minecraft mc) {
        if (mc.player != null) {
            ServerData data = mc.player.connection.getServerData();
            if (data != null && data.ip != null && !data.ip.isBlank()) {
                return data.ip;
            }
        }
        return "singleplayer";
    }

    private static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
}
