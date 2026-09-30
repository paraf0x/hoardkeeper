package dev.hoardkeeper.index;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.ScannedItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PositionIndexTest {

    @Test
    void aSingleChestResolvesFromItsOwnPositionWithEveryFieldACardNeeds() {
        // If any of these fields were dropped on the way in, the card built from them in a later
        // task would show a blank title, a wrong age, or "?/? slots" for a chest that was scanned
        // just fine.
        ScannedContainer row = container(new int[]{0, 64, 0}, null, "chest", "Iron Stash",
                "2026-09-08T10:00:00Z", 27, 5,
                List.of(item("minecraft:iron_ingot", 64), item("minecraft:coal", 32)));

        PositionIndex index = PositionIndex.of(List.of(row));
        ContainerView view = index.view(0, 64, 0);

        assertEquals("chest", view.kind());
        assertEquals("Iron Stash", view.title());
        assertEquals("2026-09-08T10:00:00Z", view.scannedAt());
        assertEquals(27, view.slotCount());
        assertEquals(5, view.usedSlots());
        assertEquals(Map.of("minecraft:iron_ingot", 64L, "minecraft:coal", 32L), view.counts());
    }

    @Test
    void aDoubleChestResolvesToTheSameViewObjectFromEitherHalf() {
        // If the two halves resolved to two different (even if equal-looking) view objects, a card
        // shown while crouching at one half and a highlight computed from the other could disagree
        // about which container is being looked at, and a double chest would flicker between two
        // cards as the crosshair drifts from one half to the other.
        ScannedContainer row = container(new int[]{0, 64, 0}, new int[]{1, 64, 0}, "chest_double",
                null, "2026-09-08T10:00:00Z", 54, 10, List.of(item("minecraft:diamond", 9)));

        PositionIndex index = PositionIndex.of(List.of(row));

        assertSame(index.view(0, 64, 0), index.view(1, 64, 0));
    }

    @Test
    void theNewerRowWinsWhenItIsScannedSecond() {
        // A resumed scan or /hoard retry-failed rewrites a container's row. If the older row
        // won, the card would show stale contents even though a fresher scan of the exact same chest
        // sits right next to it in the file.
        ScannedContainer older = container(new int[]{0, 64, 0}, null, "chest", null,
                "2026-09-08T09:00:00Z", 27, 1, List.of(item("minecraft:dirt", 1)));
        ScannedContainer newer = container(new int[]{0, 64, 0}, null, "chest", null,
                "2026-09-08T10:00:00Z", 27, 1, List.of(item("minecraft:diamond", 1)));

        PositionIndex index = PositionIndex.of(List.of(older, newer));

        assertEquals(Map.of("minecraft:diamond", 1L), index.view(0, 64, 0).counts());
    }

    @Test
    void theNewerRowWinsEvenWhenItIsScannedFirst() {
        // Same rule as above, opposite list order: the winner must be decided by scannedAt, not by
        // which row a directory walk happened to read first. If list order mattered, the card would
        // flip depending on filesystem iteration order alone.
        ScannedContainer newer = container(new int[]{0, 64, 0}, null, "chest", null,
                "2026-09-08T10:00:00Z", 27, 1, List.of(item("minecraft:diamond", 1)));
        ScannedContainer older = container(new int[]{0, 64, 0}, null, "chest", null,
                "2026-09-08T09:00:00Z", 27, 1, List.of(item("minecraft:dirt", 1)));

        PositionIndex index = PositionIndex.of(List.of(newer, older));

        assertEquals(Map.of("minecraft:diamond", 1L), index.view(0, 64, 0).counts());
    }

    @Test
    void aParseableTimestampBeatsAGarbageOneRegardlessOfOrder() {
        // Mirrors SessionSnapshotCodec.parseInstant/isAfter: a row nobody can date reads as "unknown,
        // not now", never as "now". If garbage ever won, a hand-edited or half-written row could
        // permanently shadow a real, dated scan of the same chest.
        ScannedContainer garbage = container(new int[]{0, 64, 0}, null, "chest", null,
                "not-a-timestamp", 27, 1, List.of(item("minecraft:dirt", 1)));
        ScannedContainer dated = container(new int[]{0, 64, 0}, null, "chest", null,
                "2026-09-08T10:00:00Z", 27, 1, List.of(item("minecraft:diamond", 1)));

        assertEquals(Map.of("minecraft:diamond", 1L),
                PositionIndex.of(List.of(garbage, dated)).view(0, 64, 0).counts());
        assertEquals(Map.of("minecraft:diamond", 1L),
                PositionIndex.of(List.of(dated, garbage)).view(0, 64, 0).counts());
    }

    @Test
    void aRowWithNoPositionIsDroppedAndDoesNotThrow() {
        // A container with no coordinates is not a place that can be crouched in front of. Throwing
        // here would take the whole peek feature down over one bad row in a hand-edited file; silently
        // keeping it would let a position-less row collide with a real chest at packed key 0.
        ScannedContainer noPos = container(null, null, "chest", null, "2026-09-08T10:00:00Z", 27, 1,
                List.of(item("minecraft:diamond", 1)));

        PositionIndex index = PositionIndex.of(List.of(noPos));

        assertNull(index.view(0, 0, 0));
    }

    @Test
    void anEmptyListGivesAnIndexWhoseEveryLookupIsNull() {
        // The peek feature must treat "no rows yet" identically to "not scanned here" — both are
        // silence, never a crash from an index built over nothing.
        PositionIndex index = PositionIndex.of(List.of());

        assertNull(index.view(0, 64, 0));
        assertNull(index.view(-500, 12, 999));
    }

    @Test
    void countsComeFromTotalsSoAShulkerBoxInAChestListsBothTheBoxAndItsContents() {
        // This is the exact case the peek card exists for: opening the chest shows a shulker box
        // *and* the iron inside it, not one or the other. If counts were built by hand instead of
        // through Totals, it would be easy to silently drop the nested contents.
        ScannedItem shulker = item("minecraft:shulker_box", 1);
        shulker.contents = new ArrayList<>(List.of(item("minecraft:iron_ingot", 64)));
        ScannedContainer row = container(new int[]{0, 64, 0}, null, "chest", null,
                "2026-09-08T10:00:00Z", 27, 1, List.of(shulker));

        ContainerView view = PositionIndex.of(List.of(row)).view(0, 64, 0);

        assertEquals(Map.of("minecraft:shulker_box", 1L, "minecraft:iron_ingot", 64L), view.counts());
    }

    // ---- helpers -------------------------------------------------------------------------

    private static ScannedContainer container(int[] pos, int[] secondaryPos, String kind, String title,
                                               String scannedAt, int slotCount, int usedSlots,
                                               List<ScannedItem> items) {
        ScannedContainer container = new ScannedContainer();
        container.pos = pos;
        container.secondaryPos = secondaryPos;
        container.kind = kind;
        container.title = title;
        container.scannedAt = scannedAt;
        container.slotCount = slotCount;
        container.usedSlots = usedSlots;
        container.items = items;
        return container;
    }

    private static ScannedItem item(String id, int count) {
        ScannedItem item = new ScannedItem();
        item.id = id;
        item.count = count;
        return item;
    }
}
