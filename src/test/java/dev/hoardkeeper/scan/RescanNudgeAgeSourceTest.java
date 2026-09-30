package dev.hoardkeeper.scan;

import dev.hoardkeeper.measured.MeasuredMapFiles;
import dev.hoardkeeper.measured.MeasuredMapState;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.measured.StorageClusters;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.store.AtomicJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fix round 2, findings C-1 and I-1, exercised end to end through a real {@code MeasuredMapStore}:
 * a cluster whose only row was touched by a hand-opened chest seconds ago must still offer a rescan
 * when its area was actually measured three weeks ago. {@code RescanNudgeTest} already pins
 * {@code RescanNudge.newestScannedAt}'s reduction in isolation; this pins <em>which</em> timestamps
 * {@code ScanController} now feeds it — the area's {@code measuredAt}, never the row's
 * {@code scannedAt} — by computing both from the same store and showing only one gives the honest
 * answer. This is the regression the whole fix exists for: run it against the old
 * {@code RescanNudge.newestScannedAt(store.rows())} composition and {@code decidedFromRows} below is
 * what {@code checkRescanNudge} used to answer with — silence, permanently, for the storage a player
 * actually uses.
 *
 * <p><b>Reachable without a running game.</b> {@link ScanController#newestMeasuredAt} is the pure
 * composition {@code checkRescanNudge} uses once it already has a {@code MeasuredMapStore} and a
 * player position, widened to package-private for exactly this seam (see its own javadoc). Nothing
 * here touches {@code Minecraft.getInstance()}.
 */
class RescanNudgeAgeSourceTest {

    private static final ScanArea AREA = ScanArea.ofRadius(0, 0, 16);

    private static ScannedContainer row(int x, int z, String scannedAt) {
        ScannedContainer c = new ScannedContainer();
        c.pos = new int[]{x, 64, z};
        c.kind = "chest";
        c.slotCount = 27;
        c.usedSlots = 1;
        c.scannedAt = scannedAt;
        return c;
    }

    /**
     * There is no production seam for backdating an area's {@code measuredAt} — production only ever
     * writes "now" ({@code MeasuredMapStore.recordArea}) — so this rewrites {@code measured.json}
     * directly, the same way {@code NudgeScenario.ageTheMap} rewrites {@code containers.jsonl}
     * directly for the gametest equivalent of this scenario.
     */
    private static void backdateArea(Path dir, String measuredAt) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        MeasuredMapState state = store.state();
        state.areas.get(0).measuredAt = measuredAt;
        assertTrue(AtomicJson.write(MeasuredMapFiles.state(dir), state));
    }

    @Test
    void aClusterTouchedSecondsAgoButMeasuredWeeksAgoStillOffersARescan(@TempDir Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        String weeksAgo = Instant.now().minusSeconds(21L * 24 * 60 * 60).toString();
        assertTrue(store.projectScan(List.of(row(1, 1, weeksAgo)), AREA, true, "s1",
                "localhost:25565", null, "minecraft:overworld"));
        backdateArea(dir, weeksAgo);

        // The part of I-1's story that must not matter: a chest inside this exact area gets opened by
        // hand and its row's scannedAt jumps to "now" -- exactly what PassiveObserver.append does on
        // every chest close, and exactly the row a naive "fold the rows" reduction would pick.
        store.appendContentUpdate(row(1, 1, Instant.now().toString()));
        MeasuredMapStore.awaitPendingWritesForTests();

        List<ScannedContainer> rows = store.rows();
        assertEquals(1, rows.size());
        // Documents the premise: the row really was touched seconds ago, not weeks ago.
        assertTrue(Instant.parse(rows.get(0).scannedAt).isAfter(Instant.now().minusSeconds(60)),
                "the row's scannedAt must be fresh -- that is the whole scenario this test is about");

        List<MeasuredMapStore.MeasuredArea> measuredAreas = store.measuredAreas();
        List<ScanArea> areas = measuredAreas.stream().map(MeasuredMapStore.MeasuredArea::area).toList();
        List<ScanArea> cluster = StorageClusters.covering(areas, 1, 1);
        String clusterId = RescanNudge.clusterId(cluster, "minecraft:overworld");

        // The bug this fix removes, made concrete: folding the ROWS' scannedAt reads this cluster as
        // "just measured", so the nudge never fires -- even though the area itself is three weeks old.
        String fromRows = RescanNudge.newestScannedAt(rows.stream().map(r -> r.scannedAt).toList());
        OptionalLong decidedFromRows = RescanNudge.decide(null, clusterId, fromRows,
                System.currentTimeMillis(), 7, new LinkedHashSet<>());
        assertFalse(decidedFromRows.isPresent(),
                "documents the bug: folding rows' scannedAt lets a hand-opened chest silence the nudge");

        // The fix: ScanController.newestMeasuredAt folds the AREAS' measuredAt instead, which no
        // observation ever touches -- so the three-week-old area still reads as three weeks old.
        String fromAreas = ScanController.newestMeasuredAt(measuredAreas, cluster);
        OptionalLong decidedFromAreas = RescanNudge.decide(null, clusterId, fromAreas,
                System.currentTimeMillis(), 7, new LinkedHashSet<>());
        assertTrue(decidedFromAreas.isPresent(),
                "a storage measured three weeks ago must still offer a rescan, even though a "
                        + "hand-opened chest touched one of its rows seconds ago");
    }
}
