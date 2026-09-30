package dev.hoardkeeper.model;

import java.util.List;
import java.util.Map;

/**
 * The final, human- and tool-consumable summary of a completed scan: counts, aggregated item
 * totals, the containers that failed, and the containers that were deliberately skipped.
 * {@link #schemaVersion} lets a future format change detect and migrate old reports.
 *
 * <p>Plain data holder for JSON (de)serialisation — no behaviour, no Minecraft imports.
 */
public class ScanReport {
    public int schemaVersion = 2;
    public String sessionId;
    public String server;
    public String dimension;
    public String generatedAt;
    /** Half-width in blocks of the scanned area — the true radius for a radius scan, 24 for a 3×3. */
    public int radius;
    /** {@code "RADIUS"} or {@code "CHUNKS"}: which shape {@link #radius} is an approximation of. */
    public String areaMode;
    /** Rings of chunks around the origin's chunk when {@link #areaMode} is {@code CHUNKS}. */
    public int chunkRadius;
    public int[] origin;
    public Counts containers;
    public Map<String, Long> totals;
    public List<ItemTotal> totalsByItem;
    public List<FailureEntry> failed;
    /**
     * Containers this scan knew about but never opened because it decided locally not to try:
     * {@code BLOCKED} (something is in the way) or {@code GONE} (the block is no longer there).
     *
     * <p>These are correctly excluded from {@link #failed} and from the consecutive-failure guard —
     * no packet was ever sent, so the server refused nothing. But excluding them from the report
     * too would defeat the whole point of the artifact: a run that found 122 containers and read
     * 121 of them must not print {@code {"scanned":121,"failed":0}} and leave the missing one
     * invisible. The honest answer to "what do I actually have" includes "and this one I could not
     * look inside".
     */
    public List<FailureEntry> skipped;

    public ScanReport() {
    }

    /** Container-level counts for the completed scan. */
    public static class Counts {
        /**
         * Every container this scan discovered, whatever became of it — the denominator
         * {@link #scanned}, {@link #failed} and {@link #skipped} are fractions of. A gap between
         * {@code known} and the other three is a scan that stopped with work left over.
         */
        public int known;
        public int scanned;
        public int failed;
        public int skipped;
        public Map<String, Integer> byKind;

        public Counts() {
        }
    }

    /** Aggregated total for a single item id across the whole scan. */
    public static class ItemTotal {
        public String id;
        public long count;
        public double stacks;
        public double doubleChests;

        public ItemTotal() {
        }
    }

    /** One container that could not be scanned, and why. */
    public static class FailureEntry {
        public int[] pos;
        public String kind;
        public String reason;

        public FailureEntry() {
        }
    }
}
