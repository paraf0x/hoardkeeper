package dev.hoardkeeper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod configuration: scan area, container types, pacing, retry, rendering and HUD options.
 * <p>
 * Kept free of Minecraft imports so it can be constructed and round-tripped in unit tests with
 * a plain {@link Path}. The mod entrypoint resolves the real config file path (under the Fabric
 * config directory) and calls {@link #load(Path)} with it.
 */
public class HoardkeeperConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("hoardkeeper");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Rings of chunks around the player's own chunk that {@code /hoard start} covers when no
     * argument is given: 1 means the 3×3 block of chunks. That is the default because the server's
     * quick-deposit plugin offers containers in exactly that pattern, so the scan answers "what can I
     * quick-deposit into from here?" — and because a chunk-aligned area is snapped to the grid, which
     * makes two scans of the same spot the same area rather than merely a similar one.
     */
    public int defaultChunkRadius = 1;
    public int maxChunkRadius = 8;
    /** Used by {@code /hoard start <n>}, the explicit block-radius form. */
    public int defaultRadius = 48;
    public int maxRadius = 256;
    public boolean scanChests = true;
    public boolean scanTrappedChests = true;
    public boolean scanBarrels = true;
    public boolean scanShulkerBoxes = true;

    public int minTicksBetweenOpens = 4;
    public int openTimeoutTicks = 40;
    public int contentTimeoutTicks = 40;
    public int maxAttemptsPerContainer = 3;
    public int retryCooldownTicks = 200;
    public int backoffAfterConsecutiveFailures = 3;
    public int maxBackoffTicks = 40;
    public double reachMargin = 0.4;
    public boolean swingArmOnOpen = false;

    public int discoverySweepIntervalTicks = 40;

    public String componentDetail = "lean";   // none | lean
    public int maxNestingDepth = 3;
    public boolean recurseBundles = true;

    public boolean chatPerContainer = false;
    public int chatEveryN = 25;
    public boolean actionBarProgress = true;
    public boolean chatOnFailure = true;

    /**
     * Play a short chime at the container each time one is read successfully. Failures and skips
     * stay silent — see {@link dev.hoardkeeper.sound.ScanChime}.
     */
    public boolean scanSoundEnabled = true;
    /**
     * Volume of that chime. Deliberately well under 1.0: at the default pacing this fires about five
     * times a second for the whole length of a scan, and a full-volume chime at that rate is the
     * reason a player turns the feature off instead of enjoying it.
     */
    public float scanSoundVolume = 0.3f;
    /**
     * Milliseconds without a scanned container after which the chime's rising run restarts at its
     * lowest note. Must stay comfortably above the normal gap between two containers (~200 ms at
     * the default pacing) or the run resets constantly and the pitch never rises at all;
     * 1500 ms is short enough that walking to the next cluster of chests re-arms it.
     * {@link dev.hoardkeeper.sound.ChimePitch} explains the design.
     */
    public int scanSoundRunResetMillis = 1500;

    /**
     * Seconds without a single container resolved after which a running scan stops and uploads
     * itself. 0 switches it off and puts {@code /hoard stop} back in charge.
     *
     * <p>Exists because finishing is a separate act from scanning, and it comes after the part that
     * held anyone's attention. The cost of it firing early is one {@code /hoard resume}: a
     * finished session is resumable, so a scan cut short between two clusters of chests loses
     * nothing but a command. The countdown never runs while the scanner is gated — see
     * {@link dev.hoardkeeper.scan.IdleCountdown}.
     */
    public int autoFinishSeconds = 30;

    /**
     * How long {@code /hoard search} keeps a container lit, in seconds. The clock restarts
     * every time the player opens one of the lit containers — see
     * {@link dev.hoardkeeper.search.SearchHighlight} — so this bounds how long a <em>forgotten</em>
     * highlight lasts, not how long the player has to walk a storage hall.
     */
    public int searchHighlightSeconds = 30;
    /**
     * The most containers one search lights at once, nearest to the player first. A common item can
     * sit in several hundred containers; drawing all of them is an fps problem and reading them is
     * hopeless, and the ones worth walking to are the near ones. The result line says when the cap
     * bit, so the number on screen never quietly means something narrower than it says.
     */
    public int searchMaxHighlights = 128;

    /**
     * Append a "Storage: 3,104 in 6 chests" line to an item's tooltip, read from the measured map
     * for whatever storage the player is currently standing in. See
     * {@link dev.hoardkeeper.tooltip.StorageTooltip} and
     * {@link dev.hoardkeeper.tooltip.TooltipText}.
     *
     * <p>On by default: the line is silent everywhere outside a scanned area (design spec §3), so a
     * player who has never scanned sees no change. Off restores today's behaviour exactly — no line,
     * nothing else touched about the tooltip.
     */
    public boolean tooltipStorageCounts = true;

    /**
     * Show the container card while crouching in front of a chest, barrel or shulker box, read from
     * the same measured map the tooltip and the search use. See
     * {@link dev.hoardkeeper.peek.PeekRenderer} and {@link dev.hoardkeeper.peek.PeekModel}.
     *
     * <p>On by default, and silent everywhere outside a scanned area exactly like
     * {@link #tooltipStorageCounts} (design spec §3), so a player who has never scanned sees no
     * change. Off restores today's behaviour exactly — no card, nothing else touched.
     */
    public boolean peekEnabled = true;
    /**
     * Item lines the peek card shows before folding the rest into {@code "+N more"}. Clamped
     * {@code 1..54} in {@link #clampToSaneRanges} — a double chest holds 54 distinct stacks, and a
     * cap that cannot reach them would silently hide the last of them from anyone who raised it.
     *
     * <p>Raising this all the way to 54 no longer risks drawing off the top of the screen (M-7):
     * {@code peek.PeekRenderer.clampToScreen} bounds what actually draws to however many lines fit
     * above screen row 0, folding whatever a short window cannot show into the same
     * {@code "+N more"} line this cap already uses for its own overflow.
     */
    public int peekMaxItems = 8;

    public boolean gizmosEnabled = true;
    public boolean showScannedBoxes = false;
    public int maxGizmos = 256;
    public int colorPending = 0xFFFF3030;
    public int colorScanned = 0xFF30FF60;
    public int colorFailed = 0xFFFFA000;
    public int colorTarget = 0xFFFFFF00;
    /**
     * Outline the chunks the scan covers that the server has not sent the client yet. The HUD's
     * {@code Chunks 7/9} says how many are missing; only this says which.
     *
     * <p>Loaded chunks are deliberately not outlined — see
     * {@link dev.hoardkeeper.render.ScanGizmos}. The default scan is nine chunks the client
     * already has, so drawing those meant the overlay's resting state was nine boxes reporting
     * nothing. What is left is an overlay that is empty until something is actually wrong.
     */
    public boolean chunkFrames = true;
    public int colorChunkMissing = 0xA0FF3030;
    /**
     * Outline and count colour for a searched container, and the translucent fill inside it. Cyan
     * because nothing else the mod draws is: the scan's own boxes are red, green, orange and yellow,
     * and a search result has to be findable in a room that may already be full of them.
     */
    public int colorSearch = 0xFF00E5FF;
    public int colorSearchFill = 0x5000E5FF;
    public boolean hudEnabled = true;
    public int hudX = 4;
    public int hudY = 4;

    public int resumeMaxAgeMinutes = 60;

    /**
     * Keep every finished scan session on disk. Off by default: once the measured map holds a
     * session (and no add-on still needs it, like an upload add-on before its upload) it is deleted
     * on the next arrival, {@link #resumeMaxAgeMinutes} after it finished — the map is what search,
     * tooltips and the peek card read. Spec 2026-09-30-hoardkeeper-split-design.md §7.
     */
    public boolean keepSessions = false;

    /**
     * Days a storage may go without a rescan before {@code RescanNudge} offers one, the moment the
     * player walks into it (design spec §7). Age is counted from the newest {@code scannedAt} among
     * the storage's rows, not from any one session's start.
     *
     * <p>{@code 0} disables the nudge entirely — {@link dev.hoardkeeper.scan.ScanController}
     * never even reads the measured map for it. Clamped {@code 0..365} in
     * {@link #clampToSaneRanges}.
     */
    public int rescanSuggestAfterDays = 7;

    /**
     * Whether the scanner may offer a rescan at all. Kept separate from
     * {@link #rescanSuggestAfterDays} so {@code /hoard reminder off} silences the offer
     * without forgetting how old "a while ago" is for this player.
     */
    public boolean rescanReminder = true;

    // ---- Build-site check --------------------------------------------------------------------
    // See docs/superpowers/specs/2026-09-04-site-materials-design.md §10. Every default below is
    // the behaviour a config file written before this feature already had, so an existing file
    // keeps behaving exactly as it did.

    // ---- Passive container observation -------------------------------------------------------
    // See docs/superpowers/specs/2026-09-04-passive-observation-design.md §9. Every default below
    // is either the behaviour a config file written before this feature already had, or — for
    // passiveObserve itself — a feature that sends nothing to the game server and records only
    // inside areas this client has already scanned.

    /**
     * Record what the player leaves in a container they opened by hand. Nothing appears on screen
     * and no extra packet reaches the game server: the contents are already in the menu the player
     * opened, and this only keeps them.
     *
     * <p>On by default because the feature exists to work without being thought about, and because
     * {@link #passiveObserveAnywhere} being off is what keeps it to places the group has scanned.
     * Off restores today's behaviour exactly, with any log already on disk left untouched.
     */
    public boolean passiveObserve = true;
    /**
     * Record every container the player opens, whatever it is and wherever it stands — both the
     * "inside a scanned area" rule and the "a scan registered this container" rule are lifted.
     *
     * <p>Off by default, and both halves matter. A chest opened while looting a bastion is not
     * information the group wants; neither is the shulker box somebody set down for one trip inside
     * the storage, which no scan ever registered. A marker page filling up with containers that are
     * not part of any storage is how a good feature gets turned off.
     */
    public boolean passiveObserveAnywhere = false;
    /**
     * Rows one log keeps across compactions — roughly 300 bytes each, so the default is about six
     * megabytes. Past it the oldest rows go; for a log that uploads they are on the server already,
     * and the local search only ever needs the newest row per position.
     */
    public int passiveMaxRows = 20000;

    /**
     * The singleplayer deposit's sounds: a click when a sneak-right-click is taken, a bundle insert at
     * each container that takes something, the bundle's refusal when a clicked one takes nothing, and
     * a soft level-up once everything lit is put away. See {@code deposit.DepositSounds}.
     */
    public boolean depositSounds = true;

    /**
     * The multiplayer servers on which the scanner may open containers by itself — a scan, a resume,
     * the rescan offer. Empty by default: automatic container opening is exactly what many servers
     * forbid, and a player who installs this mod cannot be expected to know. Singleplayer (and a
     * world hosted on LAN) is always allowed. {@code /hoard allow} adds the current server,
     * {@code disallow} removes it. Hosts, not strings: case, port and a trailing dot are ignored, an
     * entry covers its subdomains, an IP only itself (see {@code scan.ServerList}).
     *
     * <p>Everywhere else the mod still observes: search, tooltips and the peek card work from the
     * containers opened by hand, which are then recorded wherever they stand. Spec
     * 2026-09-30-hoardkeeper-split-design.md §4.
     */
    public List<String> scanServers = new ArrayList<>();
    /** Volume of those sounds. They happen once per container, not five times a second. */
    public float depositSoundVolume = 0.6f;

    /**
     * Loads the config from {@code configFile}. If the file is missing, unreadable or contains
     * invalid JSON, a fresh default config is written to {@code configFile} and returned. Keys
     * absent from an on-disk file (e.g. written by an older version of the mod) simply keep their
     * in-code default because Gson only overwrites fields it finds in the JSON.
     */
    public static HoardkeeperConfig load(Path configFile) {
        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                JsonObject raw = GSON.fromJson(json, JsonObject.class);
                HoardkeeperConfig config = GSON.fromJson(raw, HoardkeeperConfig.class);
                if (config != null) {
                    config.clampToSaneRanges();
                    if (config.scanServers == null) {
                        config.scanServers = new ArrayList<>();
                    }
                    return config;
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.error("Failed to load config from {}, falling back to defaults", configFile, e);
            }
        }

        HoardkeeperConfig config = new HoardkeeperConfig();
        config.save(configFile);
        return config;
    }

    /**
     * Forces the pacing and area fields into ranges the scanner can honour, logging every value it
     * has to change.
     *
     * <p>This file is hand-edited by design, and Gson will happily deserialise anything numeric into
     * it. A negative {@code minTicksBetweenOpens} means "send on every tick" to
     * {@link dev.hoardkeeper.scan.RateLimiter}, and on a foreign server — where this mod has no
     * plugin access and no permission to be a nuisance — that is the whole difference between
     * polite and abusive. A zero {@code discoverySweepIntervalTicks} re-sweeps every chunk in range
     * every tick; a zero {@code maxAttemptsPerContainer} scans nothing at all and says nothing about
     * why. None of these are worth refusing to start over, but all of them are worth saying out loud.
     */
    void clampToSaneRanges() {
        maxRadius = clamp("maxRadius", maxRadius, 1, 1024);
        defaultRadius = clamp("defaultRadius", defaultRadius, 1, maxRadius);
        // 0 is legal and means "this chunk only" -- a real thing to want when standing in a single
        // storage room, and the one chunk radius that cannot be expressed as a ring count above zero.
        // 0 is off. The ceiling is an hour, which is far past useful but keeps a hand-edited
        // absurdity from producing a countdown that silently never fires.
        autoFinishSeconds = clamp("autoFinishSeconds", autoFinishSeconds, 0, 3600);
        maxChunkRadius = clamp("maxChunkRadius", maxChunkRadius, 0, 32);

        defaultChunkRadius = clamp("defaultChunkRadius", defaultChunkRadius, 0, maxChunkRadius);

        // A double chest holds 54 distinct stacks; the ceiling is exactly that, not a round number
        // above it, because a cap that cannot reach 54 would silently hide the last of them from
        // anyone who raised it thinking they had removed the limit.
        peekMaxItems = clamp("peekMaxItems", peekMaxItems, 1, 54);

        // A hundred rows is enough for one storage hall's worth of positions, and below it
        // compaction would start throwing away containers the search still needs. The ceiling only
        // stops a hand-edited absurdity from turning the cap off in all but name.
        passiveMaxRows = clamp("passiveMaxRows", passiveMaxRows, 100, 1_000_000);

        // 0 is legal and deliberate — the self-test harness measures with it — but it is the floor.
        minTicksBetweenOpens = clamp("minTicksBetweenOpens", minTicksBetweenOpens, 0, 200);
        // Never below minTicksBetweenOpens: a backoff that paces *faster* than the normal gate is
        // not a backoff.
        maxBackoffTicks = clamp("maxBackoffTicks", maxBackoffTicks, Math.max(1, minTicksBetweenOpens), 1200);
        backoffAfterConsecutiveFailures =
                clamp("backoffAfterConsecutiveFailures", backoffAfterConsecutiveFailures, 1, 1000);
        openTimeoutTicks = clamp("openTimeoutTicks", openTimeoutTicks, 1, 1200);
        contentTimeoutTicks = clamp("contentTimeoutTicks", contentTimeoutTicks, 1, 1200);
        maxAttemptsPerContainer = clamp("maxAttemptsPerContainer", maxAttemptsPerContainer, 1, 20);
        retryCooldownTicks = clamp("retryCooldownTicks", retryCooldownTicks, 0, 72_000);
        discoverySweepIntervalTicks = clamp("discoverySweepIntervalTicks", discoverySweepIntervalTicks, 1, 1200);
        resumeMaxAgeMinutes = clamp("resumeMaxAgeMinutes", resumeMaxAgeMinutes, 0, 10_080);
        // 0 is legal and means "the nudge is off". A year is far past useful but keeps a hand-edited
        // absurdity from turning "old" into a threshold nothing on a normal server ever reaches.
        rescanSuggestAfterDays = clamp("rescanSuggestAfterDays", rescanSuggestAfterDays, 0, 365);

        maxNestingDepth = clamp("maxNestingDepth", maxNestingDepth, 0, 16);
        chatEveryN = clamp("chatEveryN", chatEveryN, 1, 100_000);
        maxGizmos = clamp("maxGizmos", maxGizmos, 0, 8192);
        // 1 second is the floor rather than 0: a highlight that expires the tick it appears is not
        // a shorter highlight, it is a command that silently does nothing. Turning the feature off
        // is what gizmosEnabled is for. The ceiling is an hour, past which "temporary" is a lie.
        searchHighlightSeconds = clamp("searchHighlightSeconds", searchHighlightSeconds, 1, 3600);
        searchMaxHighlights = clamp("searchMaxHighlights", searchMaxHighlights, 1, 4096);
        // 0 is legal and means "no rising run" — every chime is then the base note. The ceiling
        // only stops an absurd hand-edit from making the run effectively never reset.
        scanSoundRunResetMillis = clamp("scanSoundRunResetMillis", scanSoundRunResetMillis, 0, 600_000);

        // Minecraft treats a volume above 1.0 as an attenuation *distance* multiplier rather than
        // as loudness, so a hand-edited 10.0 would not make the chime ten times louder — it would
        // make it audible from ten times as far, across the whole base. Clamped rather than
        // honoured, because nothing about this feature wants that.
        scanSoundVolume = clampVolume("scanSoundVolume", scanSoundVolume, 0.3f);

        double clampedReach = Math.min(4.0, Math.max(0.0, reachMargin));
        if (!Double.isFinite(reachMargin)) {
            clampedReach = 0.4;
        }
        if (clampedReach != reachMargin) {
            LOGGER.warn("Config reachMargin={} is outside [0.0, 4.0] — clamped to {}", reachMargin, clampedReach);
            reachMargin = clampedReach;
        }
    }

    /**
     * A sound volume, held inside [0.0, 1.0].
     *
     * <p>Minecraft treats a volume above 1.0 as an attenuation <em>distance</em> multiplier rather
     * than as loudness, so a hand-edited 10.0 would not make a chime ten times louder — it would
     * make it audible from ten times as far, across the whole base. Clamped rather than honoured,
     * because nothing about these sounds wants that. A non-finite value falls back to the default
     * rather than to zero: {@code NaN} in a config file is a typo, and silently muting the feature
     * is the least diagnosable way to react to one.
     */
    private static float clampVolume(String name, float value, float fallback) {
        float clamped = Math.min(1.0f, Math.max(0.0f, value));
        if (!Float.isFinite(value)) {
            clamped = fallback;
        }
        if (clamped != value) {
            LOGGER.warn("Config {}={} is outside [0.0, 1.0] — clamped to {}", name, value, clamped);
        }
        return clamped;
    }

    private static int clamp(String name, int value, int min, int max) {
        int clamped = Math.min(max, Math.max(min, value));
        if (clamped != value) {
            LOGGER.warn("Config {}={} is outside [{}, {}] — clamped to {}", name, value, min, max, clamped);
        }
        return clamped;
    }

    /**
     * Writes this config as pretty-printed JSON to {@code configFile}.
     */
    public void save(Path configFile) {
        try {
            Files.writeString(configFile, GSON.toJson(this));
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", configFile, e);
        }
    }

    /**
     * Writes one boolean key into {@code configFile}, merged into whatever is on disk right now
     * rather than serialised from this object. A runtime toggle must not throw away edits the player
     * made in the file since the client started, nor keys a later version models and this one does
     * not — {@link #applyLegacyKeys} reads some of those. Falls back to a full write of this object
     * only when the file is missing or unreadable, where there is nothing on disk to preserve.
     */
    /** Like {@link #saveFlag}, for a list of strings. */
    public void saveList(Path configFile, String key, List<String> value) {
        JsonObject raw = readRaw(configFile);
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        value.forEach(array::add);
        raw.add(key, array);
        writeRaw(configFile, raw);
    }

    private JsonObject readRaw(Path configFile) {
        JsonObject raw = null;
        if (Files.exists(configFile)) {
            try {
                raw = GSON.fromJson(Files.readString(configFile), JsonObject.class);
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.error("Failed to load config from {}, falling back to defaults", configFile, e);
            }
        }
        return raw != null ? raw : GSON.toJsonTree(this).getAsJsonObject();
    }

    private static void writeRaw(Path configFile, JsonObject raw) {
        try {
            Files.writeString(configFile, GSON.toJson(raw));
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", configFile, e);
        }
    }

    public void saveFlag(Path configFile, String key, boolean value) {
        JsonObject raw = null;
        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                raw = GSON.fromJson(json, JsonObject.class);
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.error("Failed to load config from {}, falling back to defaults", configFile, e);
            }
        }
        if (raw == null) {
            raw = GSON.toJsonTree(this).getAsJsonObject();
        }
        raw.addProperty(key, value);

        try {
            Files.writeString(configFile, GSON.toJson(raw));
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", configFile, e);
        }
    }
}
