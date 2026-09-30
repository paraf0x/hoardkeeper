package dev.hoardkeeper.scan;

/**
 * Whether a scan has anything left to do, independent of the clock.
 *
 * <p>The quiet-time countdown exists because containers keep appearing: chunks stream in while the
 * player stands still, and a chest in one of them is discovered seconds after the scan began.
 * Waiting is the right answer to that uncertainty — but only while the uncertainty exists. Once the
 * whole area is loaded and swept and nothing is still waiting to be opened, there is nothing left
 * for the countdown to wait for, and every remaining second is the mod making somebody stand around
 * for a result it already has.
 *
 * <p>Pure, so the rule can be exercised without a running game: {@code ScanController} owns the
 * counters and this owns the decision.
 */
public final class ScanCompletion {

    private ScanCompletion() {
    }

    /**
     * True when the scan can be finished now rather than waited out.
     *
     * @param pending        candidates in {@link ContainerStatus#PENDING} — the one being opened
     *                       right now and every one awaiting a retry. The selector picks nothing
     *                       else, so this is the whole of "still to do"; a {@code FAILED} candidate
     *                       has been given up on and only {@code /hoard retry-failed}
     *                       revives it.
     * @param chunksLoaded   chunks of the area the client held at the last discovery sweep
     * @param chunksInRadius chunks the area covers, as counted by that same sweep
     */
    public static boolean nothingLeftToScan(int pending, int chunksLoaded, int chunksInRadius) {
        if (pending > 0) {
            return false;
        }
        // Both counters are zero until the first sweep has run. Without this, a scan would be
        // "complete" on the tick it started, before anything had been looked for at all.
        if (chunksInRadius <= 0) {
            return false;
        }
        // A chunk the client does not hold may contain a chest nobody has seen. Finishing while
        // any is missing would report a base as fully scanned that was not -- and unlike a slow
        // finish, that error is invisible.
        return chunksLoaded >= chunksInRadius;
    }
}
