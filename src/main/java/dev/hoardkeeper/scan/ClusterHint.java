package dev.hoardkeeper.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups still-pending {@link ContainerCandidate}s into coarse grid cells and reports the
 * cell nearest the player's eye, so the HUD can point at "the nearest cluster of containers" rather
 * than a single block far away.
 */
public final class ClusterHint {

    private ClusterHint() {
    }

    /** A group of nearby pending candidates: its centroid, how many it contains, and its distance from the eye. */
    public record Cluster(double[] centre, int count, double distance) {
    }

    /**
     * Buckets pending candidates into {@code gridSize} cubes and returns the cluster whose
     * centroid is nearest {@code eye}, or {@code null} when nothing is pending.
     */
    public static Cluster nearest(List<ContainerCandidate> candidates, double[] eye, int gridSize) {
        Map<String, List<int[]>> buckets = new LinkedHashMap<>();

        for (ContainerCandidate c : candidates) {
            if (c.status != ContainerStatus.PENDING) {
                continue;
            }
            int bx = Math.floorDiv(c.pos[0], gridSize);
            int by = Math.floorDiv(c.pos[1], gridSize);
            int bz = Math.floorDiv(c.pos[2], gridSize);
            String key = bx + "," + by + "," + bz;
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(c.pos);
        }

        Cluster best = null;
        double bestDist = Double.MAX_VALUE;

        for (List<int[]> positions : buckets.values()) {
            double cx = 0, cy = 0, cz = 0;
            for (int[] p : positions) {
                cx += p[0] + 0.5;
                cy += p[1] + 0.5;
                cz += p[2] + 0.5;
            }
            int n = positions.size();
            cx /= n;
            cy /= n;
            cz /= n;

            double dx = cx - eye[0];
            double dy = cy - eye[1];
            double dz = cz - eye[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist < bestDist) {
                bestDist = dist;
                best = new Cluster(new double[]{cx, cy, cz}, n, dist);
            }
        }

        return best;
    }
}
