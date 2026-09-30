package dev.hoardkeeper.measured;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.scan.ScanArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeasuredMapStoreTest {

    private static ScannedContainer row(int x, int z, String scannedAt, int used) {
        ScannedContainer c = new ScannedContainer();
        c.pos = new int[]{x, 64, z};
        c.kind = "chest";
        c.slotCount = 27;
        c.usedSlots = used;
        c.scannedAt = scannedAt;
        return c;
    }

    private static final ScanArea AREA = ScanArea.ofRadius(0, 0, 16);

    @Test
    void anEmptyDirectoryReadsAsAnEmptyMap(@TempDir Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        assertEquals(List.of(), store.rows());
        assertEquals(List.of(), store.areas());
    }

    @Test
    void aProjectedScanIsReadBack(@TempDir Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        assertTrue(store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)), AREA, true,
                "s1", "localhost:25565", "seed:abc", "minecraft:overworld"));

        MeasuredMapStore reopened = new MeasuredMapStore(dir);
        assertEquals(1, reopened.rows().size());
        assertEquals(1, reopened.areas().size());
        assertTrue(reopened.hasProjected("s1"));
        assertFalse(reopened.hasProjected("s2"));
    }

    @Test
    void aContentUpdateIsAppendedAndWinsOnRead(@TempDir Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)), AREA, true, "s1",
                "localhost:25565", null, "minecraft:overworld");
        // appendContentUpdate is fire-and-forget (spec §6: never on the chest-close thread), so the
        // test drains the shared writer thread before reading the file back.
        store.appendContentUpdate(row(1, 1, "2026-09-08T11:00:00Z", 0));
        MeasuredMapStore.awaitPendingWritesForTests();

        MeasuredMapStore reopened = new MeasuredMapStore(dir);
        assertEquals(1, reopened.rows().size());
        assertEquals(0, reopened.rows().get(0).usedSlots);
    }

    /**
     * The loss in spec §1, as a test. The second scan covers only a corner; what the first one
     * found outside it must survive. Strengthened to distinguish from a store that removes nothing.
     */
    @Test
    void rescanningACornerKeepsWhatTheFirstScanFoundElsewhere(@TempDir Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3),
                        row(4, 4, "2026-09-08T10:00:00Z", 3),
                        row(60, 60, "2026-09-08T10:00:00Z", 3)),
                ScanArea.ofRadius(0, 0, 128), true, "big", "localhost:25565", null, "minecraft:overworld");

        // The corner rescan reports (1,1) and not (4,4), though both lie inside its area.
        store.projectScan(List.of(row(1, 1, "2026-09-08T11:00:00Z", 9)),
                ScanArea.ofRadius(0, 0, 8), true, "corner", "localhost:25565", null, "minecraft:overworld");

        List<ScannedContainer> rows = new MeasuredMapStore(dir).rows();
        List<String> positions = rows.stream().map(r -> r.pos[0] + "," + r.pos[2]).sorted().toList();
        // (4,4) was inside the rescanned area and not found, so it is gone; (60,60) was outside it
        // and survives. A store that removed nothing would keep all three — which is the loss this
        // whole design exists to prevent, and what the weaker version of this test could not see.
        assertEquals(List.of("1,1", "60,60"), positions);
    }

    @Test
    void aCorruptLineCostsOnlyItself(@TempDir Path dir) throws Exception {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)), AREA, true, "s1",
                "localhost:25565", null, "minecraft:overworld");
        Path file = MeasuredMapFiles.containers(dir);
        java.nio.file.Files.writeString(file, java.nio.file.Files.readString(file) + "{not json\n");

        assertEquals(1, new MeasuredMapStore(dir).rows().size());
    }

    /** An unreadable measured.json means "no areas": degraded, never destructive (spec §11). */
    @Test
    void anUnreadableStateReadsAsNoAreas(@TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(MeasuredMapFiles.state(dir), "{ not json");
        assertEquals(List.of(), new MeasuredMapStore(dir).areas());
    }

    /**
     * Spec §3.3: scanning the same room again merges into the entry that is already there. Without
     * this, measured.json grows a line per run, StorageClusters walks it pairwise, and
     * PassiveObserver re-reads it twice a second while the player sorts chests.
     */
    @Test
    void rescanningTheSameAreaMergesInsteadOfAppending(@TempDir Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)), AREA, true, "s1",
                "localhost:25565", null, "minecraft:overworld");
        store.projectScan(List.of(row(1, 1, "2026-09-08T11:00:00Z", 4)), AREA, true, "s2",
                "localhost:25565", null, "minecraft:overworld");

        assertEquals(List.of(AREA), new MeasuredMapStore(dir).areas());
    }

    /**
     * Fix round 2, finding C-1/I-1: {@code areas()} rebuilds each {@code ScanArea} but throws away
     * {@code measuredAt} in the process. {@code measuredAreas()} is the accessor beside it that keeps
     * the pair together, which is what lets the rescan nudge answer "when was this storage last
     * measured" from the area instead of from a row an observation can touch.
     */
    @Test
    void measuredAreasPairsEachAreaWithWhenItWasMeasured(@TempDir Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)), AREA, true, "s1",
                "localhost:25565", null, "minecraft:overworld");

        List<MeasuredMapStore.MeasuredArea> measuredAreas = new MeasuredMapStore(dir).measuredAreas();

        assertEquals(1, measuredAreas.size());
        assertEquals(AREA, measuredAreas.get(0).area());
        assertEquals(new MeasuredMapStore(dir).state().areas.get(0).measuredAt,
                measuredAreas.get(0).measuredAt());
    }

    @Test
    void anEmptyMapHasNoMeasuredAreasEither(@TempDir Path dir) {
        assertEquals(List.of(), new MeasuredMapStore(dir).measuredAreas());
    }

    @Test
    void aDifferentAreaIsAppendedBesideTheFirst(@TempDir Path dir) {
        ScanArea elsewhere = ScanArea.ofRadius(4000, 4000, 16);
        MeasuredMapStore store = new MeasuredMapStore(dir);
        store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)), AREA, true, "s1",
                "localhost:25565", null, "minecraft:overworld");
        store.projectScan(List.of(row(4000, 4000, "2026-09-08T11:00:00Z", 3)), elsewhere, true, "s2",
                "localhost:25565", null, "minecraft:overworld");

        assertEquals(List.of(AREA, elsewhere), new MeasuredMapStore(dir).areas());
    }

    /** The merged entry describes the latest sweep, so a re-run that stopped early says so. */
    @Test
    void aMergedAreaTakesTheLatestSweepsCompleteness(@TempDir Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)), AREA, true, "s1",
                "localhost:25565", null, "minecraft:overworld");
        store.projectScan(List.of(row(1, 1, "2026-09-08T11:00:00Z", 4)), AREA, false, "s2",
                "localhost:25565", null, "minecraft:overworld");

        List<MeasuredMapState.Area> areas = new MeasuredMapStore(dir).state().areas;
        assertEquals(1, areas.size());
        assertFalse(areas.get(0).complete);
    }

    /**
     * What makes {@code MeasuredMapProjector.projectFinishedScan} safe: a resumed scan carries the
     * id its interrupted half was already folded in under, so the projection at the end of it has
     * to run past the {@code projectedSessions} guard. Re-projecting the same id must therefore add
     * the rows found since and record the session exactly once.
     */
    @Test
    void projectingTheSameSessionAgainFoldsInWhatItFoundSince(@TempDir Path dir) {
        MeasuredMapStore store = new MeasuredMapStore(dir);
        // The interrupted half, folded in by the migration on rejoin: not complete, so it removes
        // nothing, and (4,4) is still unmeasured.
        store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)), AREA, false, "s1",
                "localhost:25565", null, "minecraft:overworld");
        // The whole session after the resume finished it.
        store.projectScan(List.of(row(1, 1, "2026-09-08T10:00:00Z", 3),
                        row(4, 4, "2026-09-08T10:30:00Z", 7)), AREA, true, "s1",
                "localhost:25565", null, "minecraft:overworld");

        MeasuredMapStore reopened = new MeasuredMapStore(dir);
        List<String> positions = reopened.rows().stream()
                .map(r -> r.pos[0] + "," + r.pos[2]).sorted().toList();
        assertEquals(List.of("1,1", "4,4"), positions);
        assertEquals(List.of("s1"), reopened.state().projectedSessions);
    }

    @Test
    void aFileOfDistinctContainersIsNeverWorthFolding() {
        assertFalse(MeasuredMapStore.shouldCompact(10_000, 10_000));
    }

    @Test
    void aFileMostlyMadeOfSupersededRowsIsFolded() {
        assertTrue(MeasuredMapStore.shouldCompact(10_000, 5_000));
    }

    @Test
    void aSmallSurplusIsLeftAlone() {
        assertFalse(MeasuredMapStore.shouldCompact(1_050, 1_000));
    }
}
