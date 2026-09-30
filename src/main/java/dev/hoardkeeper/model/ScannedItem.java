package dev.hoardkeeper.model;

import java.util.List;
import java.util.Map;

/**
 * One item stack found in a scanned container, or nested one level inside a shulker box.
 *
 * <p>Plain data holder for JSON (de)serialisation — no behaviour, no Minecraft imports. Fields are
 * public and left null when absent so Gson omits them from the output, keeping the bulk file small.
 */
public class ScannedItem {
    public Integer slot;
    public String id;
    public int count;
    public String name;
    public Integer dmg;
    public Integer maxDmg;
    public Map<String, Integer> ench;
    public Map<String, Integer> storedEnch;
    public String potion;
    public List<ScannedItem> contents;

    public ScannedItem() {
    }
}
