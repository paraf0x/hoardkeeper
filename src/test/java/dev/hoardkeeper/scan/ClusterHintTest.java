package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterHintTest {

    private static ContainerCandidate at(int x, int y, int z) {
        return new ContainerCandidate(new int[]{x, y, z}, null, ContainerKind.BARREL);
    }

    @Test
    void groupsNearbyPendingContainersIntoOneCluster() {
        List<ContainerCandidate> cs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            cs.add(at(100 + i, 64, 100));
        }

        ClusterHint.Cluster c = ClusterHint.nearest(cs, new double[]{0, 64, 0}, 16);

        assertEquals(7, c.count());
    }

    @Test
    void prefersTheClosestClusterNotTheBiggest() {
        List<ContainerCandidate> cs = new ArrayList<>();
        cs.add(at(10, 64, 0));
        for (int i = 0; i < 20; i++) {
            cs.add(at(500 + i, 64, 0));
        }

        ClusterHint.Cluster c = ClusterHint.nearest(cs, new double[]{0, 64, 0}, 16);

        assertEquals(1, c.count());
        assertTrue(c.distance() < 20);
    }

    @Test
    void ignoresContainersThatAreDone() {
        ContainerCandidate done = at(5, 64, 0);
        done.status = ContainerStatus.SCANNED;

        assertNull(ClusterHint.nearest(List.of(done), new double[]{0, 64, 0}, 16));
    }

    @Test
    void returnsNullWhenThereIsNothingPending() {
        assertNull(ClusterHint.nearest(List.of(), new double[]{0, 64, 0}, 16));
    }
}
