package dev.hoardkeeper.scan;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Picks which {@link ContainerCandidate} to open next: the nearest reachable, still-pending
 * candidate. Reachability is supplied by the caller as a predicate over a position, rather than
 * computed here, so this class stays testable without a running game.
 */
public final class TargetSelector {

    private TargetSelector() {
    }

    /**
     * The chosen candidate together with the half of it that {@link #pickWithHalf} found to be in
     * reach — so the caller never has to recompute what the selection already worked out.
     */
    public record Selection(ContainerCandidate candidate, int[] half) {
    }

    /**
     * Returns the reachable {@code PENDING} candidate (cooldown expired) whose reachable half is
     * nearest to {@code eye}, or {@code null} if none qualify.
     */
    public static ContainerCandidate pick(List<ContainerCandidate> candidates, double[] eye, int tick,
                                           BiPredicate<ContainerCandidate, int[]> inReach) {
        Selection selection = pickWithHalf(candidates, eye, tick, inReach);
        return selection == null ? null : selection.candidate();
    }

    /**
     * As {@link #pick}, but also hands back <em>which</em> half was in reach.
     * <p>
     * A double chest is one candidate with two block positions, and the caller has to interact with
     * the specific half it can actually reach — the same half this method already had to identify to
     * measure the distance. Returning it keeps that determination in one place instead of having the
     * caller repeat the reach test and risk disagreeing with the selection.
     */
    public static Selection pickWithHalf(List<ContainerCandidate> candidates, double[] eye, int tick,
                                          BiPredicate<ContainerCandidate, int[]> inReach) {
        Selection best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (ContainerCandidate c : candidates) {
            if (c.status != ContainerStatus.PENDING) {
                continue;
            }
            if (tick < c.cooldownUntilTick) {
                continue;
            }
            int[] half = reachableHalf(c, p -> inReach.test(c, p));
            if (half == null) {
                continue;
            }
            double distSq = distSq(eye, half);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = new Selection(c, half);
            }
        }
        return best;
    }

    /** Returns the half of {@code c} that is in reach ({@code pos} first, then {@code secondaryPos}), or {@code null}. */
    public static int[] reachableHalf(ContainerCandidate c, Predicate<int[]> inReach) {
        if (inReach.test(c.pos)) {
            return c.pos;
        }
        if (c.secondaryPos != null && inReach.test(c.secondaryPos)) {
            return c.secondaryPos;
        }
        return null;
    }

    private static double distSq(double[] eye, int[] block) {
        double dx = (block[0] + 0.5) - eye[0];
        double dy = (block[1] + 0.5) - eye[1];
        double dz = (block[2] + 0.5) - eye[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
