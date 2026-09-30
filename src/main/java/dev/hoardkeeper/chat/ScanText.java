package dev.hoardkeeper.chat;

import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.FailReason;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every user-facing chat/action-bar string {@link ScanChat} sends, pure: strings in, a string (or
 * a list of them) out, and no Minecraft classes anywhere in this file — so any line a player reads
 * while scanning can be pinned by a plain unit test, exactly like {@code site.SiteText} and
 * {@code search.SearchText} already do for their own features.
 * <p>
 * {@code ScanChat} decides whether and where a line goes — chat, the action bar, throttled or not;
 * this class decides only what it says. Everything here is English, matching the rest of the mod.
 */
public final class ScanText {

    private ScanText() {
    }

    // =========================================================================
    // Scan lifecycle wording
    // =========================================================================

    public static String started(String areaDescription, int found) {
        return "§a[Hoard] §fStart: " + areaDescription + ", §e" + found + "§f containers found.";
    }

    public static String notRunning() {
        return "§7[Hoard] §fNo scan is running.";
    }

    public static String requeued(int count) {
        return "§7[Hoard] §e" + count + "§f containers queued again.";
    }

    /** The counts and per-reason breakdown for {@link ScanChat#allResolved}. */
    public static String allResolved(int total, int scanned, int failed, Map<FailReason, Integer> byReason) {
        return "§e[Hoard] §fAll §e" + total + "§f known containers are done: §a" + scanned
                + "§f scanned, §c" + failed + "§f failed." + breakdown(byReason);
    }

    /** Printed once per continuous block a screen has stayed open — see {@code ScreenBlockWatcher}. */
    public static String screenBlocked() {
        return "§e[Hoard] §fScan paused: a screen is open. §7Close it and the scan carries"
                + " on.";
    }

    // =========================================================================
    // Per-container progress
    // =========================================================================

    /** The one line for a just-scanned container, for both the action bar and chat. */
    public static String progress(int scanned, int known, ContainerCandidate candidate, int slots) {
        return "§7[" + scanned + "/" + known + "] §f" + candidate.kind.id() + " §7"
                + describe(candidate) + " §8(" + slots + " slots)";
    }

    // =========================================================================
    // Failures — verbatim for the first few, then throttled to a roll-up
    // =========================================================================

    /** One failure, named and placed — sent verbatim for the first {@code FAILURE_CHAT_VERBATIM} of them. */
    public static String failure(ContainerCandidate candidate, FailReason reason) {
        return "§c[!] §f" + candidate.kind.id() + " §7" + describe(candidate) + " §c" + reason;
    }

    /** The roll-up line {@link ScanChat#failure} falls back to once the verbatim budget is spent. */
    public static String failureRollup(int failuresSinceRollup, FailReason reason, int remoteFailures) {
        return "§c[Hoard] §7… §e" + failuresSinceRollup + "§7 more failures (most recent §c"
                + reason + "§7). Total: §c" + remoteFailures;
    }

    // =========================================================================
    // End-of-scan summary
    // =========================================================================

    /** The scanned/failed/pending line for {@link ScanChat#summary}, plus the per-reason breakdown. */
    public static String summary(String reason, int scanned, int failed, int pending, long millis,
                                  double perSecond, Map<FailReason, Integer> byReason) {
        return "§a[Hoard] §fEnd (" + reason + "): §e" + scanned + "§f scanned, §c" + failed
                + "§f failed, §7" + pending + "§f pending §8("
                + String.format(Locale.ROOT, "%.2f", perSecond) + "/s, "
                + String.format(Locale.ROOT, "%.1f", millis / 1000.0) + "s)" + breakdown(byReason);
    }

    /** The text before the {@link #openLabel()} link in {@link ScanChat#filePath}. */
    public static String filePath(Path path) {
        return "§b[Hoard] §fSaved to §7" + path + " ";
    }

    /**
     * The export's own line: which session was exported, from which dimension, and where it landed.
     * Names the session explicitly because an export without a live session falls back to the newest
     * one on disk — the player must never have to infer which scan they just got.
     */
    public static String exported(Path report, String sessionId, String dimension) {
        return "§b[Hoard] §fReport for §e" + sessionId + " §8(" + dimension + ")§f: §7" + report + " ";
    }

    /** The clickable label shared by {@link ScanChat#filePath} and {@link ScanChat#exported}. */
    public static String openLabel() {
        return "§9§n[Open]";
    }

    // =========================================================================
    // Resume
    // =========================================================================

    /** The text before the {@link #resumeLabel()} link in {@link ScanChat#resumeOffer}. */
    public static String resumeOffer(int scanned, int known) {
        return "§e[Hoard] §fInterrupted scan found §7(" + scanned + "/" + known + ") ";
    }

    /** The clickable {@code [Resume]} label. */
    public static String resumeLabel() {
        return "§a§n[Resume]";
    }

    public static String resumed(int scanned, int known, int pending) {
        return "§a[Hoard] §fResumed: §a" + scanned + "§f of §e" + known + "§f already scanned, §7"
                + pending + "§f pending.";
    }

    // =========================================================================
    // Rescan nudge — design spec §7, re-grounded on the measured map; see RescanNudge
    // =========================================================================

    /**
     * The text before the {@link #rescanLabel()} link in {@link ScanChat#rescanOffer}, mirroring
     * {@link #resumeOffer} exactly: a statement of fact ("this storage was measured N days ago"),
     * never an instruction — the clickable label carries the only word that invites an action.
     */
    public static String rescanOffer(long days) {
        return "§e[Hoard] §fThis storage was measured §7" + days + "§f days ago. ";
    }

    /** The clickable {@code [Rescan]} label — {@link ScanChat#rescanOffer} gives it a {@code RunCommand}. */
    public static String rescanLabel() {
        return "§a§n[Rescan]";
    }

    // =========================================================================
    // Upload — /hoard upload, see the storage-upload design §6 and §9
    // =========================================================================

    /** Action bar, once per batch sent: {@code "[Upload] 3/12 batches"}. */
    /** Segments in the countdown bar. Twenty at a 30 s timeout is one segment per 1.5 s. */
    private static final int AUTO_FINISH_BAR_SEGMENTS = 20;

    /** The emptying bar and seconds-left text for {@link ScanChat#autoFinishBar}. */
    public static String autoFinishBar(double fraction, int secondsLeft) {
        int filled = (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * AUTO_FINISH_BAR_SEGMENTS);
        StringBuilder bar = new StringBuilder("§e[Hoard] §a");
        bar.append("\u2588".repeat(filled));
        bar.append("§8").append("\u2591".repeat(AUTO_FINISH_BAR_SEGMENTS - filled));
        bar.append(" §f").append(secondsLeft).append("s §7— finishing");
        return bar.toString();
    }

    /** Said when the countdown ran out, so the summary above it isn't a mystery. */
    public static String autoFinished(int quietSeconds) {
        return "§e[Hoard] §fNo containers for §e" + quietSeconds
                + "§f seconds — scan finished automatically. §7/hoard resume §fpicks it back up.";
    }

    /**
     * The scan ended because it was done, not because the clock ran out — worth its own wording:
     * "no containers for 30 seconds" describes waiting, and somebody who reads it after an instant
     * scan of one chest is being told something that did not happen.
     */
    public static String autoFinishedComplete(int scanned, int chunks) {
        return "§a[Hoard] §fEverything in reach is scanned §7(" + scanned + " in " + chunks
                + " chunks)§f — scan finished. §7/hoard resume §fpicks it back up.";
    }

    // =========================================================================
    // Command feedback — text only, sent by the command leaf via sendFeedback
    // =========================================================================

    /**
     * The answer to a toggle command. Names the command that undoes what just happened: a switch a
     * player cannot find again is a trap, and they are most likely to want it back right after
     * turning it off.
     */
    private static String toggledText(String feature, boolean on, String command) {
        return "§b[Hoard] §f" + feature + ": " + (on ? "§aon" : "§7off")
                + " §8(" + command + (on ? " off" : " on") + " to change it back)";
    }

    public static String peekToggledText(boolean on) {
        return toggledText("Chest peek", on, "/hoard peek");
    }

    public static String reminderToggledText(boolean on) {
        return toggledText("Rescan reminder", on, "/hoard reminder");
    }

    /**
     * The {@code /hoard} help block, one entry per line.
     *
     * <p>The radius line spells out that the radius is a horizontal XZ cylinder rather than a
     * sphere, because that is not what a player assumes a "radius" means: the chunk grid is
     * horizontal, and filtering spherically would arbitrarily drop containers near the top and
     * bottom of loaded chunks that are otherwise squarely in range.
     */
    public static List<String> help(int defaultChunkRadius, int maxChunkRadius,
                                    int defaultRadius, int maxRadius, int autoFinishSeconds) {
        return List.of(
                "§a=== ScanStorage §7— take stock of the containers around you §a===",
                "§f/hoard §7— start scanning; it finishes itself after §f"
                        + autoFinishSeconds + "§7 quiet seconds",
                "§f/hoard start §7— scan the §f" + (2 * defaultChunkRadius + 1) + "x"
                        + (2 * defaultChunkRadius + 1) + "§7 chunks around you (the quick-deposit pattern)",
                "§f/hoard start chunks <n> §7— §fn§7 rings of chunks instead (max §f" + maxChunkRadius + "§7)",
                "§f/hoard start <radius> §7— a block radius instead (default §f" + defaultRadius
                        + "§7, max §f" + maxRadius + "§7)",
                "§8    Radius = a horizontal XZ cylinder around you, over the full height of the"
                        + " loaded chunks — not a sphere.",
                "§f/hoard stop §7— end the scan, close the files, print a summary",
                "§f/hoard status §7— progress, rate and chunks",
                "§f/hoard export §7— build §freport.json§7 from §fcontainers.jsonl§7",
                "§f/hoard retry-failed §7— queue failed containers again"
                        + " §8(local skips stay skipped)",
                "§f/hoard resume §7— continue an interrupted scan",
                "§f/hoard clear §7— drop the session from memory §8(writes nothing)",
                "§f/hoard search <item> §7— light up every container in the last scan holding it"
                        + " §8(tab-complete the item)",
                "§8    Or bind a key under Controls → Hoardkeeper and press it to search for"
                        + " whatever you are holding — no typing.",
                "§8    The boxes go out one by one as you open them, or all at once after"
                        + " a quiet spell. §f/hoard search§8 with no item clears them now.",
                "§f/hoard peek [on|off] §7— the card you get crouching at a chest"
                        + " §8(no argument: toggle)",
                "§f/hoard reminder [on|off] §7— the offer to rescan a storage measured"
                        + " a while ago §8(no argument: toggle)",
                "§f/hoard allow §7/ §fdisallow §7— let the scanner open containers on this server,"
                        + " or stop it §8(singleplayer is always allowed)",
                "§f/hoard deposit [on|off] §7— singleplayer: put away what you carry into the"
                        + " containers that already hold it §8(or sneak-right-click one with an empty hand)");
    }

    /** A scan asked for on a server the player has not allowed (split spec §4). */
    public static String scanNotAllowedText(String server) {
        return "§e[Hoard] §fScanning is off on §e" + server + "§f.§7 Opening containers automatically"
                + " breaks the rules of many servers. If this one allows it, §f/hoard allow§7 turns"
                + " it on here. Search and tooltips work anyway, from the containers you open by hand.";
    }

    /** {@code /hoard allow} added the server. */
    public static String allowedText(String host) {
        return "§a[Hoard] §fScanning allowed on §e" + host + "§f.§7 Make sure the server's rules permit"
                + " automatic container opening. §f/hoard disallow§7 takes it back.";
    }

    public static String alreadyAllowedText(String server) {
        return "§7[Hoard] Scanning is already allowed on " + server + ".";
    }

    public static String singleplayerAlwaysAllowedText() {
        return "§7[Hoard] Singleplayer is always allowed; there is nothing to add.";
    }

    /** {@code /hoard disallow} removed {@code removed} (the entries that covered this server). */
    public static String disallowedText(List<String> removed) {
        return "§a[Hoard] §fScanning is off here again §7(removed §f" + String.join("§7, §f", removed)
                + "§7).";
    }

    public static String notAllowedAnywayText(String server) {
        return "§7[Hoard] Scanning was not allowed on " + server + ".";
    }

    /** One line of live progress for {@code /hoard status}. */
    public static String statusText(boolean running, int scanned, int known, int failed, int pending,
                                     double perSecond, int chunksLoaded, int chunksInRadius) {
        return "§b[Hoard] " + (running ? "§arunning" : "§7stopped") + "§f: §a" + scanned + "§7/§f" + known
                + "§f scanned, §c" + failed + "§f failed, §7" + pending + "§f pending §8("
                + String.format(Locale.ROOT, "%.2f", perSecond) + "/s, Chunks " + chunksLoaded + "/"
                + chunksInRadius + ")";
    }

    /**
     * The one line passive observation shows in {@code /hoard status} -- design spec §7,
     * and the only place the feature is visible at all, since it otherwise runs with no chat, no
     * action bar and no sound. Plain text, no {@code §} colour codes: the caller decides how it
     * sits next to {@link #statusText}, not this pure builder.
     *
     * <p>{@code secondsSinceLast} is negative when no observation has actually happened yet this
     * session -- including right after joining, when {@code PassiveObserver} stamps its clock
     * purely to arm the upload debounce (see that class's {@code onJoin}), not because anything
     * was observed. The "last …s ago" clause is left out rather than printed as "last 0s ago": a
     * status line for a silent feature must never claim an observation that did not happen.
     */
    public static String observationStatusText(int rows, int pending, long secondsSinceLast) {
        if (rows == 0) {
            return "Observed: nothing yet";
        }
        String line = "Observed: " + rows + " containers · "
                + (pending == 0 ? "all uploaded" : pending + " pending upload");
        if (secondsSinceLast >= 0) {
            line += " · last " + secondsSinceLast + "s ago";
        }
        return line;
    }

    /**
     * The scan was stopped because the proxy moved the player to another backend.
     *
     * <p>Deliberately does not offer a resume: continuing here is precisely the thing that would
     * scan this realm's containers into the other realm's session. The containers already read are
     * kept and remain uploadable, which is the half the player needs to hear first.
     */
    public static String realmChangedText() {
        return "§e[Hoard] §fScan stopped: the server moved you to another realm."
                + " §7Everything scanned so far is saved and can still be uploaded."
                + " §fStart a new scan here with §7/hoard§f.";
    }

    public static String noSessionText() {
        return "§7[Hoard] §fNo session in memory. §7Start one with §f/hoard start§7.";
    }

    public static String alreadyRunningText() {
        return "§e[Hoard] §fA scan is already running. §7Stop it with §f/hoard stop§7.";
    }

    /**
     * {@code retry-failed} after the scan is over. Deliberately does not offer
     * {@code /hoard resume}: a finished session is filtered out by
     * {@code ScanController.findResumable}, so that would be the second dead end in a row.
     */
    public static String retryNotRunningText() {
        return "§e[Hoard] §fThe scan has finished, so nothing would be sent."
                + " §7Retry those containers with a new §f/hoard start§7.";
    }

    public static String clearedText() {
        return "§7[Hoard] §fSession discarded §8(the files on disk stay).";
    }

    public static String exportFailedText() {
        return "§c[Hoard] §fExport failed — nothing to export, or the file is not"
                + " writable. §7The log has the details.";
    }

    public static String sessionInMemoryText() {
        return "§e[Hoard] §fThere is still a session in memory. §7Discard it with"
                + " §f/hoard clear§7 before resuming another one.";
    }

    public static String noResumableText() {
        return "§7[Hoard] §fNo resumable scan found for this server and this dimension.";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** {@code " §7[NO_RESPONSE ×12, BLOCKED ×3]"}, or empty when nothing failed. */
    private static String breakdown(Map<FailReason, Integer> byReason) {
        if (byReason == null || byReason.isEmpty()) {
            return "";
        }
        Map<FailReason, Integer> sorted = new EnumMap<>(byReason);
        StringBuilder out = new StringBuilder(" §7[");
        boolean first = true;
        for (Map.Entry<FailReason, Integer> entry : sorted.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(" ×").append(entry.getValue());
            first = false;
        }
        return out.append("]").toString();
    }

    /**
     * The block coordinates of a candidate, formatted for chat/log lines. Public because
     * {@code ScanController}'s own log lines want the identical text — presentation belongs here,
     * not duplicated in the controller.
     */
    public static String describe(ContainerCandidate candidate) {
        return candidate.pos[0] + " " + candidate.pos[1] + " " + candidate.pos[2];
    }
}
