package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.chat.ScanText;
import dev.hoardkeeper.gametest.ChatCapture;
import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.measured.MeasuredMapFiles;
import dev.hoardkeeper.measured.MeasuredMapProjector;
import dev.hoardkeeper.measured.MeasuredMapState;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.store.AtomicJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The rescan nudge -- {@code scan.RescanNudge}, commits 9e1b879 and e881869, re-grounded on the
 * area's own {@code measuredAt} by fix round 2 (findings C-1/I-1) -- from a real client: scan the
 * base, make its map look eight days old by rewriting {@code measured.json} directly (there is no
 * production seam for this; see {@link #ageTheMap}), then walk out of the cluster and back in and
 * watch the line actually land in chat, word for word against {@code ScanText}. A second walk in
 * proves it is not repeated (clause 5).
 *
 * <p><b>Chat is observed through {@code mixin.ChatComponentMixin} / {@link ChatCapture}</b>, built
 * for this scenario rather than through {@code SiteScenario}'s approach -- that scenario does not
 * observe chat at all, it asserts against the HTTP stub the nudge has no equivalent of. The mixin
 * injects into {@code ChatComponent}'s private four-argument {@code addMessage}, the one method
 * every public "show this in chat" entry point funnels into (verified with {@code javap} against
 * the 26.2 deobfuscated jar -- see the mixin's own javadoc), so it catches
 * {@code ScanChat.rescanOffer} the same way it would catch any other line the chat window ever
 * shows.
 *
 * <p><b>{@code /hoard clear} is not in the brief's own prose but is not optional.</b>
 * {@code ScanController.stop()} deliberately leaves the finished session in memory (so
 * {@code status}/{@code export} still work), and {@code checkRescanNudge}'s clause 4 refuses to do
 * anything at all while a session is loaded -- see that method's own guard. Without this, the nudge
 * could never fire after {@link SearchScenario#scanTheBase}, aged map or not.
 *
 * <p><b>Why the map is aged only after a settle wait, not immediately.</b> {@code
 * previousNudgeClusterId} defaults to {@code null} ("previously in none") and is never written while
 * a session is loaded, so the very first real check to run once the session clears would see the
 * player already standing in the base's cluster as a "fresh entry" against whatever the map already
 * says. Aging the map before that first check would make it fire right there, without the walk this
 * scenario exists to prove ever happening. Waiting {@link #SETTLE_TICKS} (comfortably past
 * {@code RESCAN_NUDGE_CHECK_INTERVAL_TICKS}'s one-second throttle) forces that first check to land
 * while the map is still fresh -- it sets {@code previousNudgeClusterId} to this cluster and finds
 * nothing old enough to mention -- so aging the map afterwards changes what the next entry will say
 * without retroactively being one itself. The same settle wait is used again after each teleport to
 * {@link Fixtures#FAR}, so {@code previousNudgeClusterId} is reliably {@code null} again before the
 * walk back in, rather than hoping the framework's own teleport bookkeeping happened to take long
 * enough on its own.
 *
 * <p><b>This is also I-1's regression test, not just C-1's.</b> After aging the area, the scenario
 * opens {@link Fixtures#NEAREST_CHEST} by hand -- passive observation (on by default) stamps that
 * row's {@code scannedAt} to "now" the same way it would for any player sorting chests. If the nudge
 * ever went back to reading rows instead of areas, this row would read as "just measured" and the
 * walk-in check below would time out exactly the way it did before fix round 2, for the same reason.
 */
public final class NudgeScenario implements Scenario {

    /** The map is made this many days old; {@code rescanSuggestAfterDays} is set well below it. */
    private static final int AGE_DAYS = 8;
    private static final int THRESHOLD_DAYS = 1;
    /**
     * {@code ScanController.RESCAN_NUDGE_CHECK_INTERVAL_TICKS} is 20 (one second) and private;
     * this is comfortably past it so a wait of this length always spans at least one real check.
     */
    private static final int SETTLE_TICKS = Harness.SECOND + 10;
    /** Unique to {@code ScanText.rescanOffer}'s wording -- nothing else this scenario sends contains it. */
    private static final String NUDGE_MARKER = "was measured";

    @Override
    public String name() {
        return "nudge";
    }

    @Override
    public void run(Harness h) throws Exception {
        SearchScenario.scanTheBase(h);
        h.command("hoard clear");

        int originalThresholdDays = h.onClient(mc -> HoardkeeperMod.config().rescanSuggestAfterDays);
        h.setConfig(c -> c.rescanSuggestAfterDays = THRESHOLD_DAYS);

        Path dir = mapDir(h);
        Path stateFile = MeasuredMapFiles.state(dir);
        String originalStateContent = Files.readString(stateFile);
        try {
            // Let the first real check land against the still-fresh map -- see the class javadoc.
            h.ctx().waitTicks(SETTLE_TICKS);
            h.check("no nudge yet -- the map has not been aged", h.chatLinesContaining(NUDGE_MARKER).isEmpty(),
                    h.chatLinesContaining(NUDGE_MARKER));

            ageTheMap(dir);

            // I-1's setup: a hand-opened chest must not undo the aging above. Passive observation
            // (on by default) stamps this row's scannedAt to "now" via ContainerCapture, exactly
            // like any other chest a player opens while playing -- and exactly the row a nudge that
            // (wrongly) folded rows instead of areas would have picked as "newest". The mark is
            // taken immediately before the open so the check below proves THIS open touched THIS
            // chest's row -- not merely that some row in the map happens to be recent, which every
            // row already is right after scanTheBase and would stay true even with the open below
            // deleted entirely (the re-review's finding: the original version of this check passed
            // regardless of whether openAndClose did anything at all).
            Instant beforeOpen = Instant.now();
            h.openAndClose(Fixtures.NEAREST_CHEST);
            MeasuredMapStore.awaitPendingWritesForTests();
            List<ScannedContainer> rows = h.onClient(mc -> new MeasuredMapStore(MeasuredMapProjector.dirFor(mc)).rows());
            ScannedContainer nearestChestRow = rows.stream()
                    .filter(row -> row.pos != null && row.pos.length == 3
                            && row.pos[0] == Fixtures.NEAREST_CHEST.getX()
                            && row.pos[1] == Fixtures.NEAREST_CHEST.getY()
                            && row.pos[2] == Fixtures.NEAREST_CHEST.getZ())
                    .findFirst().orElse(null);
            boolean thisChestsRowWasTouchedByThisOpen = nearestChestRow != null && nearestChestRow.scannedAt != null
                    && Instant.parse(nearestChestRow.scannedAt).isAfter(beforeOpen);
            h.check("the hand-opened chest's own row was touched by this open -- I-1's setup",
                    thisChestsRowWasTouchedByThisOpen,
                    nearestChestRow == null ? "no row at " + Fixtures.NEAREST_CHEST
                            : nearestChestRow.scannedAt + " vs the mark taken before opening it, " + beforeOpen);

            // Clause 1: the map going stale while the player never left must not be a nudge either --
            // only entering a cluster is.
            boolean staysQuietStandingStill = h.stays(
                    mc -> !ChatCapture.linesContaining(NUDGE_MARKER).isEmpty(), 2 * Harness.SECOND);
            h.check("the map going stale while standing still does not trigger a nudge on its own",
                    staysQuietStandingStill, h.chatLinesContaining(NUDGE_MARKER));

            // ---- Walk out of the cluster and back in: a fresh entry over a genuinely old area,
            // with a row that was touched seconds ago sitting right beside it (I-1). ----
            h.teleport(Fixtures.FAR);
            h.ctx().waitTicks(SETTLE_TICKS);
            h.teleport(Harness.ORIGIN);
            h.awaitChatContaining(NUDGE_MARKER, 5 * Harness.SECOND);
            h.check("the nudge still fires even though a row was touched seconds ago (I-1)",
                    !h.chatLinesContaining(NUDGE_MARKER).isEmpty(), h.chatLinesContaining(NUDGE_MARKER));

            // "Exactly once" over a window, not the instant awaitChatContaining returns: that call
            // already guarantees at least one line landed, so checking size() == 1 immediately after
            // it returns could only ever fail on a duplicate arriving in that very same tick as the
            // first. Waiting here instead gives a duplicate arriving a tick or two *later in this
            // same entry* -- e.g. previousNudgeClusterId failing to update, so clause 1 never engages
            // and the throttle alone is left standing between the player and a line every second --
            // room to land before the count is read. This is genuinely distinct from the no-repeat
            // check below: that one never re-checks until the player has left the cluster and walked
            // back in, so it cannot see a duplicate that lands while the player never moves; this one
            // never leaves the cluster at all, so it cannot see a duplicate that only shows up on a
            // later, separate entry -- which is exactly what forgetting
            // ScanController.checkRescanNudge's nudgedClusterIds.add(currentClusterId) produces, since
            // clause 1 (previousNudgeClusterId) already blocks a repeat for as long as the player never
            // leaves.
            boolean staysAtOneThisEntry = h.stays(
                    mc -> ChatCapture.linesContaining(NUDGE_MARKER).size() > 1, 2 * Harness.SECOND);
            h.check("the rescan nudge reaches chat exactly once on entering the stale cluster",
                    staysAtOneThisEntry, h.chatLinesContaining(NUDGE_MARKER));

            List<String> firstEntry = h.chatLinesContaining(NUDGE_MARKER);
            String expected = ScanText.rescanOffer(AGE_DAYS) + ScanText.rescanLabel();
            h.checkEquals("the nudge names the storage's real age, word for word against ScanText",
                    expected, firstEntry.get(0));

            // ---- Walking out and back in again must not repeat it (clause 5). ----
            h.teleport(Fixtures.FAR);
            h.ctx().waitTicks(SETTLE_TICKS);
            h.teleport(Harness.ORIGIN);
            // Catches what the check above cannot: a nudge that fires again only on a later, separate
            // entry into the same cluster -- exactly what forgetting nudgedClusterIds.add produces,
            // since clause 1 (previousNudgeClusterId) resets to null the moment the player leaves.
            boolean staysOne = h.stays(mc -> ChatCapture.linesContaining(NUDGE_MARKER).size() >= 2,
                    3 * Harness.SECOND);
            h.check("a second entry into the same cluster does not repeat the nudge", staysOne,
                    h.chatLinesContaining(NUDGE_MARKER));
        } finally {
            // Leave no state behind: the aged state file, and the threshold this scenario's own
            // setConfig changed. Nothing else needs undoing -- nudgedClusterIds, previousNudgeClusterId
            // and offerResumePrinted are connection-scoped and are already cleared by
            // ScanController.onDisconnect() when this scenario's Harness closes its connection,
            // exactly like the search highlight and RealmKey beside them.
            Files.writeString(stateFile, originalStateContent);
            h.setConfig(c -> c.rescanSuggestAfterDays = originalThresholdDays);
        }
    }

    private static Path mapDir(Harness h) {
        return h.onClient(MeasuredMapProjector::dirFor);
    }

    /**
     * Rewrites every area's {@code measuredAt} in {@code measured.json} to {@link #AGE_DAYS} days
     * back -- the value {@code checkRescanNudge} reads since fix round 2 (findings C-1/I-1).
     * {@code MeasuredMapStore} has no seam for backdating an area -- production only ever writes
     * "now" ({@code MeasuredMapStore.recordArea}, called only from an actual scan projection) -- so
     * the state file is rewritten directly from the test, the same way {@code PeekScenario} mutates
     * a chest's contents directly with a server command instead of going through the scan.
     *
     * <p><b>Deliberately never touches {@code containers.jsonl}.</b> Before fix round 2 this method
     * aged the rows' {@code scannedAt} instead, which is exactly the bug the fix removed: passive
     * observation refreshes a row's {@code scannedAt} on every hand-opened chest, so ageing rows
     * answered "last touched", not "last measured" -- and after the fix, rows have no bearing on the
     * nudge at all. Leaving them alone (and {@link #run} deliberately touching one by hand
     * afterwards) is what makes this scenario the regression test for I-1: if a future change
     * reverts the fix and starts reading rows again, this scenario times out the same way it did
     * before, for the same reason.
     */
    private static void ageTheMap(Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        MeasuredMapState state = store.state();
        String staleTimestamp = DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().minusSeconds(AGE_DAYS * 24L * 60 * 60));
        for (MeasuredMapState.Area area : state.areas) {
            area.measuredAt = staleTimestamp;
        }
        AtomicJson.write(MeasuredMapFiles.state(dir), state);
    }
}
