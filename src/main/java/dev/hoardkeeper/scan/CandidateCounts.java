package dev.hoardkeeper.scan;

import java.util.List;

/**
 * A single counting shape shared by every caller that needs "how many candidates are in this
 * status" — {@code ScanController} (for its progress/summary numbers) and the HUD renderer (once
 * per tick, not once per frame). Kept as a tiny free function rather than duplicated per caller.
 */
public final class CandidateCounts {

    private CandidateCounts() {
    }

    public static int count(List<ContainerCandidate> candidates, ContainerStatus status) {
        int n = 0;
        for (ContainerCandidate candidate : candidates) {
            if (candidate.status == status) {
                n++;
            }
        }
        return n;
    }
}
