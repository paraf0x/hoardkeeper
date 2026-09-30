package dev.hoardkeeper.measured;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.scan.ScanArea;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageClustersTest {

    @Test
    void aPlayerInNoAreaGetsNoCluster() {
        List<ScanArea> areas = List.of(ScanArea.ofRadius(0, 0, 8));
        assertTrue(StorageClusters.covering(areas, 5000, 5000).isEmpty());
    }

    @Test
    void theAreaCoveringThePlayerIsTheCluster() {
        ScanArea a = ScanArea.ofRadius(0, 0, 8);
        assertEquals(List.of(a), StorageClusters.covering(List.of(a), 1, 1));
    }

    /** Two overlapping scans of one base are one storage. */
    @Test
    void twoOverlappingAreasAreOneStorage() {
        ScanArea a = ScanArea.ofRadius(0, 0, 16);
        ScanArea b = ScanArea.ofRadius(20, 0, 16);
        assertEquals(2, StorageClusters.covering(List.of(a, b), 1, 1).size());
    }

    /** An outpost far away is a different storage and must not be dragged in. */
    @Test
    void aDisjointAreaIsADifferentStorage() {
        ScanArea a = ScanArea.ofRadius(0, 0, 16);
        ScanArea far = ScanArea.ofRadius(4000, 4000, 16);
        assertEquals(List.of(a), StorageClusters.covering(List.of(a, far), 1, 1));
    }

    /** The transitive case: a third scan bridging two separate ones makes all three one storage. */
    @Test
    void anAreaBridgingTwoOthersMergesAllThree() {
        ScanArea left = ScanArea.ofRadius(0, 0, 16);
        ScanArea right = ScanArea.ofRadius(60, 0, 16);
        ScanArea bridge = ScanArea.ofRadius(30, 0, 16);
        assertEquals(3, StorageClusters.covering(List.of(left, right, bridge), 1, 1).size());
    }

    /** Standing where two storages overlap means standing in one storage, by definition. */
    @Test
    void twoAreasCoveringThePlayerAreOneClusterNotTwo() {
        ScanArea a = ScanArea.ofRadius(0, 0, 16);
        ScanArea b = ScanArea.ofRadius(4, 4, 16);
        assertEquals(2, StorageClusters.covering(List.of(a, b), 1, 1).size());
    }

    private static ScannedContainer at(int x, int z) {
        ScannedContainer row = new ScannedContainer();
        row.pos = new int[]{x, 64, z};
        return row;
    }

    private static List<String> positions(List<ScannedContainer> rows) {
        return rows.stream().map(row -> row.pos[0] + "," + row.pos[2]).sorted().toList();
    }

    /**
     * The defect this method exists to prevent: one map, two places, two answers. Filtering when
     * the map was read instead would hand the base's containers to a player standing in the mine.
     */
    @Test
    void oneMapAnswersDifferentlyFromTwoStorages() {
        ScanArea base = ScanArea.ofRadius(0, 0, 16);
        ScanArea mine = ScanArea.ofRadius(800, 0, 16);
        List<ScanArea> areas = List.of(base, mine);
        List<ScannedContainer> map = List.of(at(2, 2), at(800, 0));

        assertEquals(List.of("2,2"), positions(StorageClusters.rowsCovering(map, areas, 1, 1)));
        assertEquals(List.of("800,0"), positions(StorageClusters.rowsCovering(map, areas, 800, 0)));
    }

    /** Spec §5.1: standing in no measured area at all searches the whole dimension. */
    @Test
    void aPlayerInNoAreaSeesTheWholeMap() {
        List<ScannedContainer> map = List.of(at(2, 2), at(800, 0));
        assertEquals(map, StorageClusters.rowsCovering(map, List.of(ScanArea.ofRadius(0, 0, 16)),
                90_000, 90_000));
    }

    /** A row a bridging scan pulled in is part of the same storage, transitively. */
    @Test
    void aBridgedStorageAnswersWithEveryPartOfIt() {
        List<ScanArea> areas = List.of(ScanArea.ofRadius(0, 0, 16), ScanArea.ofRadius(60, 0, 16),
                ScanArea.ofRadius(30, 0, 16));
        List<ScannedContainer> map = List.of(at(0, 0), at(60, 0), at(4000, 0));

        assertEquals(List.of("0,0", "60,0"), positions(StorageClusters.rowsCovering(map, areas, 1, 1)));
    }

    /** A row with no usable position cannot be walked to, so it is not part of any answer. */
    @Test
    void aRowWithoutAPositionIsDropped() {
        ScannedContainer noPos = new ScannedContainer();
        List<ScannedContainer> map = List.of(noPos, at(0, 0));
        assertEquals(List.of("0,0"), positions(
                StorageClusters.rowsCovering(map, List.of(ScanArea.ofRadius(0, 0, 16)), 1, 1)));
    }
}
