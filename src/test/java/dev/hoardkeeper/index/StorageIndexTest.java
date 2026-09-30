package dev.hoardkeeper.index;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.ScannedItem;
import dev.hoardkeeper.search.ContainerHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SearchIndex} and {@link PositionIndex} are each already proven correct on their own
 * ({@code SearchIndexTest}, {@code PositionIndexTest}). What is new here, and the only thing worth
 * testing, is that {@link StorageIndex#of} builds both from the <em>same</em> row list rather than
 * two independent reads that could quietly drift apart — so both tests below reach into one
 * {@code StorageIndex} from both directions and check the two answers agree.
 */
class StorageIndexTest {

    @Test
    void theContainerSearchFindsIsTheContainerPositionsDescribesAtThatSpot() {
        // If search() and positions() were built from two separate calls rather than one shared row
        // list, a bug in wiring them up could still pass each half's own tests while quietly handing
        // the item search a position that positions() knows nothing about.
        ScannedContainer row = container(new int[]{10, 64, -3}, null, "chest", "Iron Stash",
                "2026-09-08T10:00:00Z", 27, 1, List.of(item("minecraft:iron_ingot", 64)));

        StorageIndex index = StorageIndex.of(List.of(row));

        ContainerHit hit = index.search().hits("minecraft:iron_ingot").getFirst();
        ContainerView view = index.positions().view(hit.pos()[0], hit.pos()[1], hit.pos()[2]);

        assertEquals("Iron Stash", view.title());
        assertEquals(Map.of("minecraft:iron_ingot", 64L), view.counts());
    }

    @Test
    void aDoubleChestHitCoversTheSamePositionsThatResolveToOneSharedView() {
        // The search treats a double chest as one hit that covers both halves (ContainerHit.covers);
        // PositionIndex aliases both halves to one ContainerView object. Proving those two decisions
        // land on the exact same pair of coordinates, from one StorageIndex built over one row list,
        // is what would break first if the composition ever read the halves apart.
        ScannedContainer row = container(new int[]{0, 64, 0}, new int[]{1, 64, 0}, "chest_double",
                null, "2026-09-08T10:00:00Z", 54, 10, List.of(item("minecraft:diamond", 9)));

        StorageIndex index = StorageIndex.of(List.of(row));

        ContainerHit hit = index.search().hits("minecraft:diamond").getFirst();
        assertTrue(hit.covers(0, 64, 0));
        assertTrue(hit.covers(1, 64, 0));
        assertSame(index.positions().view(0, 64, 0), index.positions().view(1, 64, 0));
        assertEquals(Map.of("minecraft:diamond", 9L), index.positions().view(0, 64, 0).counts());
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
