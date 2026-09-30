package dev.hoardkeeper.measured;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.scan.ScanArea;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Which measured areas belong to the storage the player is standing in. Design spec §5.1.
 *
 * <p>Two areas are the same storage when they overlap, transitively: a third scan bridging two
 * previously separate ones makes all three one place. This is the same overlap judgement the old
 * {@code SessionRetention} used to make to decide what to delete — deleting a session is no longer
 * decided by geometry at all now that a session is simply deleted once it has uploaded, so this
 * class is the only place that judgement is still made, and for a strictly lower stake: a wrong
 * answer here costs a search result, never a file.
 *
 * <p>Pure, and no Minecraft — a connected component over {@code ScanArea.overlaps}.
 */
public final class StorageClusters {

    private StorageClusters() {
    }

    /**
     * The areas of the storage covering {@code (blockX, blockZ)}, or an empty list when the player
     * stands in no measured area at all. The caller decides what an empty answer means; the search
     * falls back to the whole dimension (spec §5.1).
     */
    public static List<ScanArea> covering(List<ScanArea> areas, int blockX, int blockZ) {
        List<ScanArea> cluster = new ArrayList<>();
        boolean[] taken = new boolean[areas.size()];
        Deque<Integer> queue = new ArrayDeque<>();

        for (int i = 0; i < areas.size(); i++) {
            if (areas.get(i).coversBlock(blockX, blockZ)) {
                taken[i] = true;
                queue.add(i);
                cluster.add(areas.get(i));
            }
        }

        while (!queue.isEmpty()) {
            ScanArea current = areas.get(queue.removeFirst());
            for (int i = 0; i < areas.size(); i++) {
                if (!taken[i] && current.overlaps(areas.get(i))) {
                    taken[i] = true;
                    queue.add(i);
                    cluster.add(areas.get(i));
                }
            }
        }
        return cluster;
    }

    /**
     * The rows of {@code map} belonging to the storage covering {@code (blockX, blockZ)} — what a
     * search run from there may see, spec §5.1. A player standing in no measured area at all gets
     * the whole map, which is more useful than an empty answer.
     *
     * <p><b>Asked per query, never baked into a cached index.</b> The map is one file per dimension,
     * so walking from a base to a mine changes nothing about the file — a filter applied when the
     * file was read would keep answering with the base's containers hundreds of blocks later, and
     * nothing about the file could tell the cache it had gone wrong. This is a coordinate test over
     * a list already in memory, which is cheap enough to redo every time somebody asks and is then
     * always about where they are now.
     *
     * <p>A row without a usable position is dropped: it cannot be placed in a storage, and it could
     * not be highlighted or walked to even if it were.
     */
    public static List<ScannedContainer> rowsCovering(List<ScannedContainer> map, List<ScanArea> areas,
                                                       int blockX, int blockZ) {
        List<ScanArea> cluster = covering(areas, blockX, blockZ);
        if (cluster.isEmpty()) {
            return map;
        }
        return map.stream()
                .filter(row -> row != null && row.pos != null && row.pos.length == 3)
                .filter(row -> cluster.stream().anyMatch(area -> area.coversBlock(row.pos[0], row.pos[2])))
                .toList();
    }
}
