package dev.hoardkeeper.capture;

import dev.hoardkeeper.model.ScannedItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sums item counts by id across a flat or nested (shulker-in-container) list of items.
 */
public final class Totals {

    private Totals() {
    }

    /**
     * Sums {@code count} by item id across {@code items}, recursing into each item's
     * {@code contents} (e.g. items stored inside a scanned shulker box). Both the outer item and
     * its nested contents are counted. Insertion-ordered so output is deterministic. Null-safe:
     * a null item list, or a null {@code contents} list on any item, is treated as empty.
     */
    public static Map<String, Long> of(List<ScannedItem> items) {
        Map<String, Long> totals = new LinkedHashMap<>();
        accumulate(items, totals);
        return totals;
    }

    private static void accumulate(List<ScannedItem> items, Map<String, Long> totals) {
        if (items == null) {
            return;
        }
        for (ScannedItem item : items) {
            totals.merge(item.id, (long) item.count, Long::sum);
            accumulate(item.contents, totals);
        }
    }
}
