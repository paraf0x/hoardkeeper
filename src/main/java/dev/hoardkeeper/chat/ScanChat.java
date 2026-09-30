package dev.hoardkeeper.chat;

import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.FailReason;
import dev.hoardkeeper.scan.ScanController;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Sends every chat/action-bar line the scanner shows the player, and owns the throttling that
 * keeps a bad server from burying the chat in failure lines. The wording itself lives in
 * {@link ScanText}, which takes no Minecraft classes and so can be pinned by a plain unit test;
 * this class hands {@code ScanText}'s strings to {@link #info} / {@link #actionBar} / {@link #linked}.
 */
public final class ScanChat {

    /** How many remote failures get their own chat line before the roll-up takes over. */
    private static final int FAILURE_CHAT_VERBATIM = 3;
    /** Minimum gap between roll-up lines once the verbatim budget is spent (200 ticks = 10 s). */
    private static final int FAILURE_ROLLUP_TICKS = 200;

    // ---- Failure throttle state, session-lifetime. Reset from ScanController.reset(). ----
    private static int remoteFailures;
    private static int failuresSinceRollup;
    // Shares ScanController.NEVER rather than keeping a second copy of the sentinel — see that
    // constant's javadoc for why it must never become Integer.MIN_VALUE.
    private static int lastFailureChatTick = ScanController.NEVER;

    private ScanChat() {
    }

    /** Clears the failure throttle so a new scan starts with a clean budget of verbatim lines. */
    public static void resetFailureThrottle() {
        remoteFailures = 0;
        failuresSinceRollup = 0;
        lastFailureChatTick = ScanController.NEVER;
    }

    /** Sends a fully-formatted (already {@code §}-coloured) line as a system chat message. */
    public static void info(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(message));
        }
    }

    /** Sends a fully-formatted line to the action bar (the overlay above the hotbar). */
    public static void actionBar(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal(message));
        }
    }

    public static void started(String areaDescription, int found) {
        info(ScanText.started(areaDescription, found));
    }

    public static void notRunning() {
        info(ScanText.notRunning());
    }

    public static void requeued(int count) {
        info(ScanText.requeued(count));
    }

    public static void allResolved(int total, int scanned, int failed, Map<FailReason, Integer> byReason) {
        info(ScanText.allResolved(total, scanned, failed, byReason));
    }

    public static void screenBlocked() {
        info(ScanText.screenBlocked());
    }

    /** To the action bar when {@code actionBarProgress}; to chat per {@code chatPerContainer}/{@code chatEveryN}. */
    public static void progress(int scanned, int known, ContainerCandidate candidate, int slots,
                                 boolean actionBarProgress, boolean chatPerContainer, int chatEveryN) {
        String line = ScanText.progress(scanned, known, candidate, slots);

        if (actionBarProgress) {
            actionBar(line);
        }
        if (chatPerContainer || (chatEveryN > 0 && scanned % chatEveryN == 0)) {
            info(line);
        }
    }

    /**
     * A container failed for a real remote reason, never a local skip — filter
     * {@link FailReason#isLocalSkip()} out before calling this. The first few failures get a line
     * each; after that it collapses to at most one roll-up line per ten seconds, since a server
     * refusing everything would otherwise bury the summary in scrollback.
     */
    public static void failure(int tick, boolean chatOnFailure, ContainerCandidate candidate, FailReason reason) {
        if (!chatOnFailure) {
            return;
        }
        remoteFailures++;

        if (remoteFailures <= FAILURE_CHAT_VERBATIM) {
            info(ScanText.failure(candidate, reason));
            failuresSinceRollup = 0;
            lastFailureChatTick = tick;
            return;
        }

        failuresSinceRollup++;
        if (tick - lastFailureChatTick >= FAILURE_ROLLUP_TICKS) {
            info(ScanText.failureRollup(failuresSinceRollup, reason, remoteFailures));
            failuresSinceRollup = 0;
            lastFailureChatTick = tick;
        }
    }

    /** The end-of-scan report, plus (when {@code sessionDir} is not null) a link to where it landed. */
    public static void summary(String reason, int scanned, int failed, int pending, long millis,
                                double perSecond, Map<FailReason, Integer> byReason, Path sessionDir) {
        info(ScanText.summary(reason, scanned, failed, pending, millis, perSecond, byReason));
        if (sessionDir != null) {
            filePath(sessionDir);
        }
    }

    public static void filePath(Path path) {
        linked(ScanText.filePath(path), ScanText.openLabel(), new ClickEvent.OpenFile(path.toString()));
    }

    public static void exported(Path report, String sessionId, String dimension) {
        linked(ScanText.exported(report, sessionId, dimension), ScanText.openLabel(),
                new ClickEvent.OpenFile(report.toString()));
    }

    public static void resumeOffer(int scanned, int known) {
        linked(ScanText.resumeOffer(scanned, known), ScanText.resumeLabel(),
                new ClickEvent.RunCommand("/hoard resume"));
    }

    /**
     * The rescan nudge (design spec §7): the storage the player just walked into was last measured
     * {@code days} days ago. {@code RunCommand}, not {@code suggestCommand} — mirroring
     * {@link #resumeOffer} exactly, because an offer that behaves differently from the offer beside
     * it is a small trap. Offers only: see {@code RescanNudge}'s own javadoc for why nothing here
     * ever sends a scan on its own.
     */
    public static void rescanOffer(long days) {
        linked(ScanText.rescanOffer(days), ScanText.rescanLabel(),
                new ClickEvent.RunCommand("/hoard start"));
    }

    public static void resumed(int scanned, int known, int pending) {
        info(ScanText.resumed(scanned, known, pending));
    }

    public static void autoFinishBar(double fraction, int secondsLeft) {
        actionBar(ScanText.autoFinishBar(fraction, secondsLeft));
    }

    public static void autoFinished(int quietSeconds) {
        info(ScanText.autoFinished(quietSeconds));
    }

    public static void autoFinishedComplete(int scanned, int chunks) {
        info(ScanText.autoFinishedComplete(scanned, chunks));
    }

    /** Shared by {@link #uploadSummary} and {@link #uploadFailed}: one line per rejected container. */
    /** One clickable label and the command it runs. */
    public record Choice(String label, String command) {
    }

    /** Sends {@code text} followed by clickable labels, each running its own command. */
    public static void choices(String text, List<Choice> choices) {
        MutableComponent line = Component.literal(text);
        for (int i = 0; i < choices.size(); i++) {
            if (i > 0) {
                line.append(Component.literal("§7  ·  "));
            }
            Choice choice = choices.get(i);
            line.append(Component.literal(choice.label())
                    .setStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand(choice.command()))));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(line);
        }
    }

    /** Sends {@code text} followed by a clickable {@code label} that copies {@code payload}. */
    public static void copyable(String text, String label, String payload) {
        linked(text, label, new ClickEvent.CopyToClipboard(payload));
    }

    /** Sends {@code text} followed by a clickable {@code label} carrying {@code event}. */
    private static void linked(String text, String label, ClickEvent event) {
        MutableComponent line = Component.literal(text);
        line.append(Component.literal(label).setStyle(Style.EMPTY.withClickEvent(event)));

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(line);
        }
    }
}
