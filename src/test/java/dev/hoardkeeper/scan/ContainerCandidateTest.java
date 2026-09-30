package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerCandidateTest {

    private static ContainerCandidate at(int x, int y, int z) {
        return new ContainerCandidate(new int[]{x, y, z}, null, ContainerKind.BARREL);
    }

    @Test
    void startsWithNoObservationTime() {
        assertEquals(0L, at(1, 2, 3).observedAt);
    }

    @Test
    void carriesTheTimeItWasLastObserved() {
        ContainerCandidate c = at(1, 2, 3);
        c.observedAt = 1_788_355_200_000L;
        assertEquals(1_788_355_200_000L, c.observedAt);
    }
}
