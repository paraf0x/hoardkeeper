package dev.hoardkeeper.store;

import dev.hoardkeeper.model.ScanReport;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.ScannedItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportBuilderTest {

    private static ScannedContainer container(String kind, ScannedItem... items) {
        ScannedContainer c = new ScannedContainer();
        c.kind = kind;
        c.pos = new int[]{0, 0, 0};
        c.items = List.of(items);
        return c;
    }

    private static ScannedItem item(String id, int count) {
        ScannedItem i = new ScannedItem();
        i.id = id;
        i.count = count;
        return i;
    }

    private static ScanReport.FailureEntry entry(int[] pos, String kind, String reason) {
        ScanReport.FailureEntry e = new ScanReport.FailureEntry();
        e.pos = pos;
        e.kind = kind;
        e.reason = reason;
        return e;
    }

    private static ScanReport build(List<ScannedContainer> containers) {
        return ReportBuilder.build("sid", "srv", "minecraft:overworld", new int[]{0, 0, 0}, ScanArea.ofRadius(0, 0, 64),
                "2026-09-02T14:31:07Z", containers.size(), containers, List.of(), List.of(),
                Map.of("minecraft:iron_ingot", 64, "minecraft:diamond_pickaxe", 1));
    }

    @Test
    void countsContainersByKind() {
        ScanReport r = build(List.of(
                container("barrel"), container("barrel"), container("chest_double")));

        assertEquals(3, r.containers.scanned);
        assertEquals(2, r.containers.byKind.get("barrel"));
        assertEquals(1, r.containers.byKind.get("chest_double"));
    }

    @Test
    void sortsTotalsByItemDescendingSoTheBigPilesComeFirst() {
        ScanReport r = build(List.of(
                container("barrel", item("minecraft:diamond_pickaxe", 1)),
                container("barrel", item("minecraft:iron_ingot", 12480))));

        assertEquals("minecraft:iron_ingot", r.totalsByItem.get(0).id);
        assertEquals(12480L, r.totalsByItem.get(0).count);
        assertEquals("minecraft:diamond_pickaxe", r.totalsByItem.get(1).id);
    }

    @Test
    void expressesTotalsInStacksAndDoubleChests() {
        ScanReport r = build(List.of(container("barrel", item("minecraft:iron_ingot", 12480))));

        ScanReport.ItemTotal iron = r.totalsByItem.get(0);
        assertEquals(195.0, iron.stacks, 0.01);          // 12480 / 64
        assertEquals(195.0 / 54.0, iron.doubleChests, 0.01);
    }

    @Test
    void usesTheRealMaxStackSizeForUnstackableItems() {
        ScanReport r = build(List.of(container("barrel", item("minecraft:diamond_pickaxe", 3))));

        assertEquals(3.0, r.totalsByItem.get(0).stacks, 0.01);   // max stack 1
    }

    @Test
    void assumes64ForItemsMissingFromTheStackSizeMap() {
        ScanReport r = build(List.of(container("barrel", item("modded:widget", 128))));

        assertEquals(2.0, r.totalsByItem.get(0).stacks, 0.01);
    }

    @Test
    void carriesFailuresThrough() {
        ScanReport.FailureEntry f = new ScanReport.FailureEntry();
        f.pos = new int[]{110, 64, -210};
        f.kind = "barrel";
        f.reason = "NO_RESPONSE";

        ScanReport r = ReportBuilder.build("sid", "srv", "minecraft:overworld", new int[]{0, 0, 0},
                ScanArea.ofRadius(0, 0, 64), "2026-09-02T14:31:07Z", 1, List.of(), List.of(f), List.of(), Map.of());

        assertEquals(1, r.containers.failed);
        assertEquals("NO_RESPONSE", r.failed.get(0).reason);
        assertTrue(r.totalsByItem.isEmpty());
    }

    @Test
    void reportsLocallySkippedContainersSeparatelyFromFailures() {
        // The verification run: 122 known, 121 read, one BLOCKED chest. The report used to say
        // {"scanned":121,"failed":0} and never mention that a container had gone unopened.
        ScanReport r = ReportBuilder.build("sid", "srv", "minecraft:overworld", new int[]{0, 0, 0},
                ScanArea.ofRadius(0, 0, 64), "2026-09-02T14:31:07Z", 3,
                List.of(container("barrel"), container("chest")),
                List.of(),
                List.of(entry(new int[]{8004, 100, 8000}, "chest", "BLOCKED")),
                Map.of());

        assertEquals(3, r.containers.known);
        assertEquals(2, r.containers.scanned);
        assertEquals(0, r.containers.failed);
        assertEquals(1, r.containers.skipped);
        assertEquals("BLOCKED", r.skipped.get(0).reason);
        assertArrayEquals(new int[]{8004, 100, 8000}, r.skipped.get(0).pos);
        assertTrue(r.failed.isEmpty(), "a local skip is not the server refusing us");
    }

    @Test
    void knownIsNotDerivedSoAnInterruptedScanStillShowsItsLeftoverContainers() {
        // A scan stopped with work outstanding: 10 discovered, 2 read, nothing failed or skipped.
        // Deriving known from scanned+failed+skipped would print 2 and hide the other eight.
        ScanReport r = ReportBuilder.build("sid", "srv", "minecraft:overworld", new int[]{0, 0, 0},
                ScanArea.ofRadius(0, 0, 64), "2026-09-02T14:31:07Z", 10,
                List.of(container("barrel"), container("barrel")), List.of(), List.of(), Map.of());

        assertEquals(10, r.containers.known);
        assertEquals(2, r.containers.scanned);
    }

    @Test
    void carriesAnEmptySkipListRatherThanNull() {
        ScanReport r = build(List.of(container("barrel")));

        assertEquals(0, r.containers.skipped);
        assertTrue(r.skipped.isEmpty(), "an absent skip list must serialise as [], never as null");
    }
}
