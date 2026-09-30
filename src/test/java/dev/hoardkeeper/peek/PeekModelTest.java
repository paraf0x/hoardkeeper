package dev.hoardkeeper.peek;

import dev.hoardkeeper.index.ContainerView;
import dev.hoardkeeper.scan.ContainerKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeekModelTest {

    private static final long NOW = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli();
    private static final String TWO_HOURS_AGO = Instant.ofEpochMilli(NOW - 2 * 60 * 60 * 1000).toString();

    private static ContainerView view(String kind, String title, String scannedAt, int slotCount,
                                       int usedSlots, Map<String, Long> counts) {
        return new ContainerView(new int[]{0, 64, 0}, kind, title, scannedAt, slotCount, usedSlots, counts);
    }

    // ---- the three states of spec §6 ------------------------------------------------

    @Test
    void contentsStateOpensWithKindAndAgeThenItemsThenSlots() {
        // The exact figures from design spec §6's own mock-up card, so this test is also the proof
        // that PeekModel reproduces it rather than something merely similar.
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("minecraft:iron_ingot", 192L);
        counts.put("minecraft:coal", 64L);
        counts.put("minecraft:redstone", 31L);
        counts.put("minecraft:a", 5L);
        counts.put("minecraft:b", 4L);
        counts.put("minecraft:c", 3L);
        counts.put("minecraft:d", 2L);
        ContainerView chest = view("chest", null, TWO_HOURS_AGO, 27, 18, counts);

        List<String> lines = PeekModel.lines(chest, ContainerKind.CHEST, 3, NOW);

        assertEquals(6, lines.size(), lines.toString());
        assertEquals("Chest · seen 2h ago", lines.get(0));
        assertTrue(lines.get(1).contains("iron_ingot") && lines.get(1).contains("192"), lines.get(1));
        assertTrue(lines.get(2).contains("coal") && lines.get(2).contains("64"), lines.get(2));
        assertTrue(lines.get(3).contains("redstone") && lines.get(3).contains("31"), lines.get(3));
        assertEquals("+4 more", lines.get(4));
        assertEquals("18/27 slots", lines.get(5));
    }

    @Test
    void emptyStateSaysEmptyAndNothingElse() {
        // Deliberately gives the view a title and slots it could otherwise report, to prove the
        // empty card really is the one word and not that plus a title line nobody asked for.
        ContainerView emptyChest = view("chest", "Iron Stash", TWO_HOURS_AGO, 27, 0, Map.of());

        List<String> lines = PeekModel.lines(emptyChest, ContainerKind.CHEST, 8, NOW);

        assertEquals(List.of("empty"), lines);
    }

    @Test
    void aNullCountsMapIsTreatedAsEmptyRatherThanThrowing() {
        ContainerView noCounts = view("barrel", null, TWO_HOURS_AGO, 27, 0, null);

        assertEquals(List.of("empty"), PeekModel.lines(noCounts, ContainerKind.BARREL, 8, NOW));
    }

    @Test
    void notScannedYetWhenTheBlockIsAContainerButNoRowCoversIt() {
        // What a chest built after the scan looks like: ContainerDiscovery.kindOf says container,
        // PositionIndex.view says nothing. Saying so plainly is the point of the whole feature.
        List<String> lines = PeekModel.lines(null, ContainerKind.CHEST, 8, NOW);

        assertEquals(List.of("not scanned yet"), lines);
    }

    @Test
    void noCardAtAllWhenTheBlockIsNotAContainer() {
        // The player is looking at a wall, not a chest that got missed. Nothing to draw at all --
        // not even "not scanned yet", which would be a false claim about a block that was never a
        // container in the first place.
        List<String> lines = PeekModel.lines(null, null, 8, NOW);

        assertTrue(lines.isEmpty(), lines.toString());
    }

    // ---- the title line ---------------------------------------------------------------

    @Test
    void aTitleFromTheScanIsShownInPlaceOfTheKind() {
        ContainerView named = view("chest", "Iron Stash", TWO_HOURS_AGO, 27, 1, Map.of("minecraft:iron_ingot", 1L));

        List<String> lines = PeekModel.lines(named, ContainerKind.CHEST, 8, NOW);

        assertEquals("Iron Stash · seen 2h ago", lines.get(0));
    }

    @Test
    void noTitleFallsBackToTheKindAlone() {
        ContainerView untitled = view("chest", null, TWO_HOURS_AGO, 27, 1, Map.of("minecraft:iron_ingot", 1L));

        List<String> lines = PeekModel.lines(untitled, ContainerKind.CHEST, 8, NOW);

        assertEquals("Chest · seen 2h ago", lines.get(0));
    }

    @Test
    void aBlankTitleFallsBackToTheKindAloneToo() {
        ContainerView blankTitle = view("chest", "   ", TWO_HOURS_AGO, 27, 1, Map.of("minecraft:iron_ingot", 1L));

        List<String> lines = PeekModel.lines(blankTitle, ContainerKind.CHEST, 8, NOW);

        assertEquals("Chest · seen 2h ago", lines.get(0));
    }

    @Test
    void displayKindTitleCasesAMultiWordKindId() {
        // shulker_box is ContainerKind's only multi-word id (CHEST, CHEST_DOUBLE, TRAPPED_CHEST,
        // TRAPPED_CHEST_DOUBLE, BARREL, SHULKER_BOX) -- every other title-line test above only ever
        // exercises displayKind on the single word "chest", which cannot fail the split-on-'_' step
        // no matter how that step is written.
        ContainerView shulkerBox = view("shulker_box", null, TWO_HOURS_AGO, 27, 1,
                Map.of("minecraft:iron_ingot", 1L));

        List<String> lines = PeekModel.lines(shulkerBox, ContainerKind.SHULKER_BOX, 8, NOW);

        assertEquals("Shulker Box · seen 2h ago", lines.get(0));
    }

    // ---- freshness on the title line, per design spec §10 -----------------------------

    @Test
    void aMissingScannedAtOmitsTheAgeRatherThanInventingOne() {
        ContainerView noTimestamp = view("chest", null, null, 27, 1, Map.of("minecraft:iron_ingot", 1L));

        List<String> lines = PeekModel.lines(noTimestamp, ContainerKind.CHEST, 8, NOW);

        assertEquals("Chest", lines.get(0));
    }

    @Test
    void anUnparseableScannedAtOmitsTheAgeRatherThanInventingOne() {
        ContainerView garbage = view("chest", null, "not-a-timestamp", 27, 1, Map.of("minecraft:iron_ingot", 1L));

        List<String> lines = PeekModel.lines(garbage, ContainerKind.CHEST, 8, NOW);

        assertEquals("Chest", lines.get(0));
    }

    // ---- ordering, the cap, and the slots line -----------------------------------------

    @Test
    void itemsAreOrderedByCountDescendingRegardlessOfMapOrder() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("minecraft:a", 1L);
        counts.put("minecraft:b", 50L);
        counts.put("minecraft:c", 10L);
        ContainerView chest = view("chest", null, TWO_HOURS_AGO, 27, 3, counts);

        List<String> lines = PeekModel.lines(chest, ContainerKind.CHEST, 10, NOW);

        // Title line, then b (50), then c (10), then a (1), then the slots line -- no "+N more"
        // because the cap of 10 comfortably covers all three.
        assertEquals(5, lines.size(), lines.toString());
        assertTrue(lines.get(1).contains("50"), lines.get(1));
        assertTrue(lines.get(2).contains("10"), lines.get(2));
        assertTrue(lines.get(3).endsWith("1"), lines.get(3));
    }

    @Test
    void itemsTiedOnCountBreakTiesByItemIdForAStableOrder() {
        // Two items at the same count sort by value alone under most comparators, and which one a
        // stream happens to visit first is then an accident of map iteration order -- meaning the
        // two rows could swap places from one frame to the next with nothing here to catch it. The
        // comparator's declared tiebreaker (by key) is what keeps the card's rows still while the
        // player looks at it.
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("minecraft:zzz_last", 10L);
        counts.put("minecraft:aaa_first", 10L);
        ContainerView chest = view("chest", null, TWO_HOURS_AGO, 27, 2, counts);

        List<String> lines = PeekModel.lines(chest, ContainerKind.CHEST, 8, NOW);

        assertTrue(lines.get(1).contains("aaa_first"), lines.get(1));
        assertTrue(lines.get(2).contains("zzz_last"), lines.get(2));
    }

    @Test
    void thePlusNMoreCapAtExactlyMaxItemsProducesNoMoreLine() {
        // The boundary the other cap tests miss: they use a cap either comfortably above the item
        // count or several below it. Here the container holds exactly maxItems distinct stacks, so
        // "sorted.size() > shown" must read false rather than off-by-one true -- a "+0 more" line
        // would be a visibly wrong card, not just an ugly one.
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            counts.put("minecraft:item" + i, (long) (4 - i));
        }
        ContainerView chest = view("chest", null, TWO_HOURS_AGO, 27, 4, counts);

        List<String> lines = PeekModel.lines(chest, ContainerKind.CHEST, 4, NOW);

        assertFalse(lines.stream().anyMatch(l -> l.contains("more")), lines.toString());
        // Title + 4 item lines + slots -- no "+N more" line at all.
        assertEquals(6, lines.size(), lines.toString());
    }

    @Test
    void thePlusNMoreCapCountsExactlyWhatWasLeftOut() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            counts.put("minecraft:item" + i, (long) (10 - i));
        }
        ContainerView chest = view("chest", null, TWO_HOURS_AGO, 54, 10, counts);

        List<String> lines = PeekModel.lines(chest, ContainerKind.CHEST, 4, NOW);

        // Title + 4 item lines + "+6 more" + slots.
        assertEquals(7, lines.size(), lines.toString());
        assertEquals("+6 more", lines.get(5));
    }

    @Test
    void noPlusMoreLineWhenEveryItemFitsUnderTheCap() {
        Map<String, Long> counts = Map.of("minecraft:iron_ingot", 1L, "minecraft:coal", 2L);
        ContainerView chest = view("chest", null, TWO_HOURS_AGO, 27, 3, counts);

        List<String> lines = PeekModel.lines(chest, ContainerKind.CHEST, 8, NOW);

        assertFalse(lines.stream().anyMatch(l -> l.contains("more")), lines.toString());
    }

    @Test
    void aZeroSlotCountOmitsTheSlotsLineButStillProducesACard() {
        // slotCount 0 is what a row written by an older mod build looks like: unknown, never
        // pretended to be zero of zero.
        ContainerView oldRow = view("chest", null, TWO_HOURS_AGO, 0, 0, Map.of("minecraft:iron_ingot", 1L));

        List<String> lines = PeekModel.lines(oldRow, ContainerKind.CHEST, 8, NOW);

        assertFalse(lines.stream().anyMatch(l -> l.contains("slots")), lines.toString());
        assertFalse(lines.isEmpty());
    }

    @Test
    void itemRowsLineUpUnderEachOther() {
        // Same alignment discipline as site.SiteText's shortfall rows: the padding is computed over
        // the rows actually shown, so every one of them ends at the same column.
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("minecraft:a", 1L);
        counts.put("minecraft:very_long_item_name", 2L);
        ContainerView chest = view("chest", null, TWO_HOURS_AGO, 27, 2, counts);

        List<String> lines = PeekModel.lines(chest, ContainerKind.CHEST, 8, NOW);

        assertEquals(lines.get(1).length(), lines.get(2).length(), lines.toString());
    }
}
