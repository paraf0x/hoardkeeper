package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TargetSelectorTest {

    /** Everything within 4 blocks of the eye (block centre) counts as reachable. */
    private static final BiPredicate<ContainerCandidate, int[]> WITHIN_4 = (c, p) -> {
        double dx = (p[0] + 0.5) - 0.5;
        double dy = (p[1] + 0.5) - 0.5;
        double dz = (p[2] + 0.5) - 0.5;
        return dx * dx + dy * dy + dz * dz <= 16.0;
    };

    private static ContainerCandidate at(int x, int y, int z) {
        return new ContainerCandidate(new int[]{x, y, z}, null, ContainerKind.BARREL);
    }

    @Test
    void picksTheNearestReachablePendingCandidate() {
        ContainerCandidate near = at(1, 0, 0);
        ContainerCandidate far = at(3, 0, 0);

        assertSame(near, TargetSelector.pick(List.of(far, near), new double[]{0.5, 0.5, 0.5}, 0, WITHIN_4));
    }

    @Test
    void ignoresCandidatesThatAreAlreadyDone() {
        ContainerCandidate scanned = at(1, 0, 0);
        scanned.status = ContainerStatus.SCANNED;
        ContainerCandidate pending = at(3, 0, 0);

        assertSame(pending, TargetSelector.pick(List.of(scanned, pending), new double[]{0.5, 0.5, 0.5}, 0, WITHIN_4));
    }

    @Test
    void ignoresCandidatesStillInRetryCooldown() {
        ContainerCandidate cooling = at(1, 0, 0);
        cooling.status = ContainerStatus.PENDING;
        cooling.cooldownUntilTick = 500;
        ContainerCandidate ready = at(3, 0, 0);

        assertSame(ready, TargetSelector.pick(List.of(cooling, ready), new double[]{0.5, 0.5, 0.5}, 100, WITHIN_4));
        assertSame(cooling, TargetSelector.pick(List.of(cooling, ready), new double[]{0.5, 0.5, 0.5}, 500, WITHIN_4));
    }

    @Test
    void returnsNullWhenNothingIsInReach() {
        assertNull(TargetSelector.pick(List.of(at(50, 0, 0)), new double[]{0.5, 0.5, 0.5}, 0, WITHIN_4));
    }

    @Test
    void reachesADoubleChestThroughEitherHalf() {
        ContainerCandidate dbl = new ContainerCandidate(
                new int[]{50, 0, 0}, new int[]{2, 0, 0}, ContainerKind.CHEST_DOUBLE);

        assertSame(dbl, TargetSelector.pick(List.of(dbl), new double[]{0.5, 0.5, 0.5}, 0, WITHIN_4));
        assertArrayEquals(new int[]{2, 0, 0},
                TargetSelector.reachableHalf(dbl, p -> WITHIN_4.test(dbl, p)));
    }

    @Test
    void returnsNullFromReachableHalfWhenNeitherHalfIsClose() {
        ContainerCandidate dbl = new ContainerCandidate(
                new int[]{50, 0, 0}, new int[]{51, 0, 0}, ContainerKind.CHEST_DOUBLE);

        assertNull(TargetSelector.reachableHalf(dbl, p -> WITHIN_4.test(dbl, p)));
    }
}
