package dev.hoardkeeper.capture;

import dev.hoardkeeper.model.ScannedItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotalsTest {

    private static ScannedItem item(String id, int count, ScannedItem... nested) {
        ScannedItem i = new ScannedItem();
        i.id = id;
        i.count = count;
        if (nested.length > 0) {
            i.contents = List.of(nested);
        }
        return i;
    }

    @Test
    void sumsRepeatedIds() {
        Map<String, Long> t = Totals.of(List.of(
                item("minecraft:iron_ingot", 64),
                item("minecraft:iron_ingot", 32)));

        assertEquals(96L, t.get("minecraft:iron_ingot"));
    }

    @Test
    void countsNestedShulkerContentsAndTheShulkerItself() {
        Map<String, Long> t = Totals.of(List.of(
                item("minecraft:red_shulker_box", 1,
                        item("minecraft:raw_iron", 64),
                        item("minecraft:raw_copper", 48))));

        assertEquals(1L, t.get("minecraft:red_shulker_box"));
        assertEquals(64L, t.get("minecraft:raw_iron"));
        assertEquals(48L, t.get("minecraft:raw_copper"));
    }

    @Test
    void handlesAnEmptyContainer() {
        assertTrue(Totals.of(List.of()).isEmpty());
    }

    @Test
    void toleratesNullItemList() {
        assertTrue(Totals.of(null).isEmpty());
    }
}
