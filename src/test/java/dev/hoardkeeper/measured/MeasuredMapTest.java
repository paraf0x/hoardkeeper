package dev.hoardkeeper.measured;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.scan.ScanArea;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeasuredMapTest {

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

    private static List<int[]> positions(List<ScannedContainer> rows) {
        return rows.stream().map(r -> r.pos).toList();
    }

    @Test
    void aScanCreatesContainersTheMapDidNotHave() {
        List<ScannedContainer> out = MeasuredMap.project(List.of(), List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)),
                AREA, true, MeasuredMap.Source.SCAN);
        assertEquals(1, out.size());
        assertEquals(3, out.get(0).usedSlots);
    }

    @Test
    void aNewerRowReplacesTheEntryAtThatPosition() {
        List<ScannedContainer> map = List.of(row(1, 1, "2026-09-08T10:00:00Z", 3));
        List<ScannedContainer> out = MeasuredMap.project(map, List.of(row(1, 1, "2026-09-08T11:00:00Z", 9)),
                AREA, false, MeasuredMap.Source.SCAN);
        assertEquals(1, out.size());
        assertEquals(9, out.get(0).usedSlots);
    }

    @Test
    void anOlderRowIsIgnored() {
        List<ScannedContainer> map = List.of(row(1, 1, "2026-09-08T11:00:00Z", 9));
        List<ScannedContainer> out = MeasuredMap.project(map, List.of(row(1, 1, "2026-09-08T10:00:00Z", 3)),
                AREA, false, MeasuredMap.Source.SCAN);
        assertEquals(9, out.get(0).usedSlots);
    }

    /**
     * Spec §4.2. Without this, a shulker box set down for one trip would register itself through
     * the map and defeat ObservationGate.NOT_A_REGISTERED_CONTAINER, which exists to exclude it.
     */
    @Test
    void aContentUpdateNeverCreatesAContainer() {
        List<ScannedContainer> out = MeasuredMap.project(List.of(),
                List.of(row(5, 5, "2026-09-08T10:00:00Z", 1)), AREA, false,
                MeasuredMap.Source.CONTENT_UPDATE);
        assertEquals(List.of(), out);
    }

    @Test
    void aContentUpdateStillUpdatesOneTheMapAlreadyHas() {
        List<ScannedContainer> map = List.of(row(5, 5, "2026-09-08T10:00:00Z", 3));
        List<ScannedContainer> out = MeasuredMap.project(map,
                List.of(row(5, 5, "2026-09-08T11:00:00Z", 0)), AREA, false,
                MeasuredMap.Source.CONTENT_UPDATE);
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).usedSlots);
    }

    /** Spec §4.3: the scan looked there and found nothing, so it is gone. */
    @Test
    void aCompleteScanRemovesWhatItDidNotFindInsideItsArea() {
        List<ScannedContainer> map = List.of(row(1, 1, "2026-09-08T10:00:00Z", 3),
                row(2, 2, "2026-09-08T10:00:00Z", 3));
        List<ScannedContainer> out = MeasuredMap.project(map,
                List.of(row(1, 1, "2026-09-08T11:00:00Z", 3)), AREA, true, MeasuredMap.Source.SCAN);
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).pos[0]);
    }

    /** The loss this whole design exists to prevent, reintroduced from the other end. */
    @Test
    void anIncompleteScanRemovesNothing() {
        List<ScannedContainer> map = List.of(row(1, 1, "2026-09-08T10:00:00Z", 3),
                row(2, 2, "2026-09-08T10:00:00Z", 3));
        List<ScannedContainer> out = MeasuredMap.project(map,
                List.of(row(1, 1, "2026-09-08T11:00:00Z", 3)), AREA, false, MeasuredMap.Source.SCAN);
        assertEquals(2, out.size());
    }

    /** A scan is authoritative for its own area and for nothing outside it. */
    @Test
    void aCompleteScanLeavesEntriesOutsideItsAreaAlone() {
        List<ScannedContainer> map = List.of(row(1000, 1000, "2026-09-08T10:00:00Z", 3));
        List<ScannedContainer> out = MeasuredMap.project(map, List.of(), AREA, true,
                MeasuredMap.Source.SCAN);
        assertEquals(1, out.size());
    }

    /** A corrupt timestamp must never displace a good row — the guard the overlay already applies. */
    @Test
    void anUnparseableTimestampSortsOlderThanEveryRealOne() {
        List<ScannedContainer> map = List.of(row(1, 1, "2026-09-08T10:00:00Z", 3));
        List<ScannedContainer> out = MeasuredMap.project(map, List.of(row(1, 1, "not a date", 9)),
                AREA, false, MeasuredMap.Source.SCAN);
        assertEquals(3, out.get(0).usedSlots);
    }

    @Test
    void rowsWithoutAUsablePositionAreSkippedOnBothSides() {
        ScannedContainer bad = new ScannedContainer();
        bad.pos = new int[]{1, 2};
        List<ScannedContainer> out = MeasuredMap.project(List.of(bad), List.of(bad), AREA, false,
                MeasuredMap.Source.SCAN);
        assertEquals(List.of(), out);
    }

    @Test
    void theOrderOfTheMapIsPreservedAcrossAProjection() {
        List<ScannedContainer> map = List.of(row(1, 1, "2026-09-08T10:00:00Z", 1),
                row(2, 2, "2026-09-08T10:00:00Z", 2));
        List<ScannedContainer> out = MeasuredMap.project(map,
                List.of(row(1, 1, "2026-09-08T11:00:00Z", 7)), AREA, false, MeasuredMap.Source.SCAN);
        assertEquals(1, positions(out).get(0)[0]);
        assertEquals(2, positions(out).get(1)[0]);
    }
}
