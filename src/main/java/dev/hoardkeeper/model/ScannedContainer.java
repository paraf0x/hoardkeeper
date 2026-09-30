package dev.hoardkeeper.model;

import java.util.List;
import java.util.Map;

/**
 * A single scanned container (chest, barrel, shulker box, ...) and its full contents.
 *
 * <p>Plain data holder for JSON (de)serialisation — no behaviour, no Minecraft imports.
 */
public class ScannedContainer {
    public int[] pos;
    public int[] secondaryPos;
    public String kind;
    public String dimension;
    public String menuType;
    public String title;
    public String scannedAt;
    public int slotCount;
    public int usedSlots;
    public List<ScannedItem> items;
    public Map<String, Long> totals;

    public ScannedContainer() {
    }
}
