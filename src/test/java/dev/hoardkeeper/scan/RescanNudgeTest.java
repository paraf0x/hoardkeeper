package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One test per clause of the design spec's §7, re-grounded on the measured map (see
 * {@link RescanNudge}'s own javadoc for why sessions and {@code purpose} do not appear here). Each
 * test names the one clause it pins and what a nudge would wrongly do — or wrongly not do — without
 * it.
 */
class RescanNudgeTest {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000L;
    /** An arbitrary fixed "now", so every test's math is exact instead of racing the wall clock. */
    private static final long NOW = 1_700_000_000_000L;
    private static final int THRESHOLD_DAYS = 7;

    private static String daysAgo(long days) {
        return Instant.ofEpochMilli(NOW - days * DAY_MILLIS).toString();
    }

    private static Set<String> noneNudgedYet() {
        return new LinkedHashSet<>();
    }

    @Test
    void enteringAnOldClusterFromNoneSaysTheDays() {
        // Clause 1 (re-grounded): the player was in no cluster (previous == null) and just entered
        // one whose newest row is well past the threshold. Without this, a storage nobody has ever
        // stood in front of before never gets its first nudge at all.
        OptionalLong days = RescanNudge.decide(null, "hallA", daysAgo(23), NOW, THRESHOLD_DAYS, noneNudgedYet());

        assertTrue(days.isPresent(), "an old cluster just entered should say something");
        assertEquals(23L, days.getAsLong());
    }

    @Test
    void stayingInTheSameClusterNextTickSaysNothing() {
        // Clause 1's level-trigger: "entered" means previous != current, not "currently standing
        // in". Without this check a player standing still in an old hall would be nudged once a
        // second for as long as they stayed, instead of once for the whole visit.
        OptionalLong days = RescanNudge.decide("hallA", "hallA", daysAgo(23), NOW, THRESHOLD_DAYS, noneNudgedYet());

        assertFalse(days.isPresent(), "the same cluster on the next tick is not a fresh entry");
    }

    @Test
    void aClusterYoungerThanTheThresholdSaysNothing() {
        // Clause 3: a storage measured recently is not stale. Without this every storage, however
        // freshly scanned, would offer a rescan the moment anyone walked in.
        OptionalLong days = RescanNudge.decide(null, "hallA", daysAgo(2), NOW, THRESHOLD_DAYS, noneNudgedYet());

        assertFalse(days.isPresent(), "two days old is younger than the seven-day threshold");
    }

    @Test
    void aClusterAlreadyNudgedThisConnectionSaysNothing() {
        // Clause 5: walking in and out of the same hall four times is one nudge, not four. Without
        // this check every re-entry of an old hall would print the offer again.
        Set<String> alreadyNudged = new LinkedHashSet<>();
        alreadyNudged.add("hallA");

        OptionalLong days = RescanNudge.decide(null, "hallA", daysAgo(23), NOW, THRESHOLD_DAYS, alreadyNudged);

        assertFalse(days.isPresent(), "hallA already got its one nudge this connection");
    }

    @Test
    void anAbsentTimestampNeverNudges() {
        // Clause 3's error handling: a row with no scannedAt at all must never be treated as old
        // just because "unknown" sorts however the arithmetic happens to fall out. An unknown age
        // must read as "say nothing", not as an infinitely old one.
        OptionalLong days = RescanNudge.decide(null, "hallA", null, NOW, THRESHOLD_DAYS, noneNudgedYet());

        assertFalse(days.isPresent(), "no timestamp at all must never be claimed as an old one");
    }

    @Test
    void anUnparseableTimestampNeverNudges() {
        // Same clause, the other half: a corrupted or hand-edited row must fail the same way a
        // missing one does, never crash the tick and never be read as "very old" by accident.
        OptionalLong days = RescanNudge.decide(null, "hallA", "not-a-timestamp", NOW, THRESHOLD_DAYS, noneNudgedYet());

        assertFalse(days.isPresent(), "garbage timestamps must never be claimed as an old one");
    }

    @Test
    void decideTrustsWhicheverNewestScannedAtItIsHandedRatherThanRederivingIt() {
        // decide() itself does not reduce a cluster's rows -- that is newestScannedAt()'s job,
        // covered on its own below. This only pins that decide() takes the single value it is given
        // at face value: a caller-supplied "yesterday" must read as freshly measured, never as
        // overdue because decide() went looking for some other, older row on its own. (Mechanically
        // the same branch as aClusterYoungerThanTheThresholdSaysNothing -- kept separate because it
        // documents a different assumption: that decide() is a pure function of its arguments, not
        // of the cluster's rows.)
        OptionalLong days = RescanNudge.decide(null, "hallA", daysAgo(1), NOW, THRESHOLD_DAYS, noneNudgedYet());

        assertFalse(days.isPresent(), "the newest row in the cluster was measured yesterday");
    }

    @Test
    void standingOutsideEveryMeasuredAreaSaysNothing() {
        // Not one of spec's six clauses by number, but the same "no cluster" case clause 1 starts
        // from: a null current cluster id must never produce a nudge, whatever the other arguments
        // say — there is no storage to be nudged about.
        OptionalLong days = RescanNudge.decide("hallA", null, daysAgo(23), NOW, THRESHOLD_DAYS, noneNudgedYet());

        assertFalse(days.isPresent(), "no cluster at all means nothing to nudge about");
    }

    // =========================================================================
    // clusterId — the stable-across-ticks identity clause 1 rests on
    // =========================================================================

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void clusterIdIsNullForAnEmptyCluster() {
        assertEquals(null, RescanNudge.clusterId(java.util.List.of(), OVERWORLD));
    }

    @Test
    void clusterIdIsStableRegardlessOfListOrder() {
        ScanArea a = ScanArea.ofChunks(16, 32, 1);
        ScanArea b = ScanArea.ofChunks(64, 32, 1);

        String forward = RescanNudge.clusterId(java.util.List.of(a, b), OVERWORLD);
        String backward = RescanNudge.clusterId(java.util.List.of(b, a), OVERWORLD);

        assertEquals(forward, backward, "the same two areas must yield the same id in either order");
    }

    @Test
    void clusterIdDiffersForGeometricallyDifferentAreas() {
        ScanArea a = ScanArea.ofChunks(16, 32, 1);
        ScanArea b = ScanArea.ofChunks(64, 32, 1);

        String idA = RescanNudge.clusterId(java.util.List.of(a), OVERWORLD);
        String idB = RescanNudge.clusterId(java.util.List.of(b), OVERWORLD);

        assertFalse(idA.equals(idB), "two different areas must not collide onto the same id");
    }

    @Test
    void clusterIdDiffersForTheSameGeometryInDifferentDimensions() {
        // Fix round 2, finding I-2: an Overworld base and a Nether hub both scanned around chunk
        // (0,0) must not share an id -- without the dimension folded in, nudging one would
        // permanently suppress the other's nudge for the rest of the connection.
        ScanArea a = ScanArea.ofChunks(0, 0, 1);

        String overworldId = RescanNudge.clusterId(java.util.List.of(a), OVERWORLD);
        String netherId = RescanNudge.clusterId(java.util.List.of(a), NETHER);

        assertFalse(overworldId.equals(netherId),
                "identical geometry in two different dimensions must not collide onto the same id");
    }

    @Test
    void clusterIdStillMatchesForTheSameGeometryInTheSameDimension() {
        // The other half of I-2's fix: folding in the dimension must not turn two genuinely
        // identical clusters (same areas, same dimension) into different ids -- clause 1's "still
        // standing in the same place" level-trigger depends on this staying stable.
        ScanArea a = ScanArea.ofChunks(0, 0, 1);

        String first = RescanNudge.clusterId(java.util.List.of(a), OVERWORLD);
        String second = RescanNudge.clusterId(java.util.List.of(a), OVERWORLD);

        assertEquals(first, second, "the same geometry in the same dimension must still match");
    }

    // =========================================================================
    // newestScannedAt — the reduction "age from the newest area, not just any area" rests on.
    // Fix round 1, finding I-1: decideTrustsWhicheverNewestScannedAtItIsHandedRatherThanRederivingIt
    // above only re-tests decide()'s threshold on an already-reduced value; these are what actually
    // fail if the reduction itself is broken (verified by inverting the winning comparison and
    // watching every test below fail — see the task-6 fix report).
    //
    // Fix round 2, finding C-1/I-1: this method used to fold over each row's scannedAt; it now folds
    // over the cluster's areas' measuredAt instead (see RescanNudge's own javadoc and
    // RescanNudgeAgeSourceTest, which pins why). The five tests below are unchanged in what they
    // prove -- "the newest of several timestamps wins, in whatever order they arrive, skipping
    // whatever does not parse" -- only the input shrank from ScannedContainer rows to plain ISO
    // strings, since that is now literally all the method is handed.
    // =========================================================================

    @Test
    void newestScannedAtPicksTheNewestRowWhenItSitsInTheMiddle() {
        // A cluster's areas are whatever order measured.json happened to record them in -- position
        // in the list must never be what decides the answer.
        List<String> measuredAts = List.of(daysAgo(10), daysAgo(1), daysAgo(20));

        assertEquals(daysAgo(1), RescanNudge.newestScannedAt(measuredAts));
    }

    @Test
    void newestScannedAtPicksTheNewestRowWhenItIsLast() {
        // Oldest-first is the order a scan would naturally record areas in; the reduction must not
        // secretly assume the first value it sees is the newest just because that is the common case.
        List<String> measuredAts = List.of(daysAgo(20), daysAgo(10), daysAgo(1));

        assertEquals(daysAgo(1), RescanNudge.newestScannedAt(measuredAts));
    }

    @Test
    void newestScannedAtOfASingleRowIsItsOwnTimestamp() {
        // The smallest real cluster: one area, nothing to compare it against.
        List<String> measuredAts = List.of(daysAgo(5));

        assertEquals(daysAgo(5), RescanNudge.newestScannedAt(measuredAts));
    }

    @Test
    void newestScannedAtIgnoresAnUnparseableTimestampAmongValidRows() {
        // A hand-edited or corrupt entry must never win the reduction just because a broken
        // comparison treats "cannot parse" as "infinitely new" -- the valid newest value must still
        // win.
        List<String> measuredAts = List.of(daysAgo(10), "not-a-timestamp", daysAgo(1));

        assertEquals(daysAgo(1), RescanNudge.newestScannedAt(measuredAts));
    }

    @Test
    void newestScannedAtOfAnEmptyClusterIsNull() {
        assertNull(RescanNudge.newestScannedAt(List.of()));
    }

    @Test
    void reminderOffSilencesTheOfferAtEveryAge() {
        // The command's whole job. If this passes with the flag ignored, the command does nothing.
        assertFalse(RescanNudge.enabled(false, 7));
        assertFalse(RescanNudge.enabled(false, 0));
    }

    @Test
    void zeroDaysStillDisablesItWithTheReminderOn() {
        // The config's documented off-switch, kept working alongside the new flag.
        assertFalse(RescanNudge.enabled(true, 0));
        assertTrue(RescanNudge.enabled(true, 1));
    }
}
