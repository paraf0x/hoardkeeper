package dev.hoardkeeper.scan;

/** Why a {@link ContainerCandidate} ended up in {@link ContainerStatus#FAILED}. */
public enum FailReason {
    /** We sent the interaction and the server never opened anything back in time. */
    NO_RESPONSE,
    /** The menu opened, but its content packet never followed in time. */
    NO_CONTENT,
    /** A screen opened, but it wasn't the menu type we expected for this container kind. */
    UNEXPECTED_MENU,
    /** The menu opened but its shape/slot layout didn't match what we expected. */
    BAD_MENU,
    /** We chose not to attempt this container at all (e.g. it's in a protected region). */
    BLOCKED,
    /** The block at this position is no longer the container we expected (removed/changed). */
    GONE;

    /**
     * True for {@link #BLOCKED} and {@link #GONE}.
     * <p>
     * Both of those are decided entirely on the client, before any open-container packet is ever
     * sent to the server — they mean "skip this container", not "the server refused us". Every
     * other reason implies a round trip actually happened (or was attempted) and the server (or
     * the absence of a timely reply) is the one saying no.
     * <p>
     * This distinction matters because {@code ScanController} counts consecutive failures to
     * decide when to back off or abort a scan as unhealthy. Local skips must NOT count toward
     * that counter: in an early spike, {@code isLocalSkip} reasons were folded into the same
     * consecutive-failure counter as server failures, and a run through 52 already-known-empty or
     * protected containers (all harmless local skips) tripped the emergency stop and killed an
     * otherwise healthy scan. Only failures where a packet was actually sent — and the server (or
     * time) failed to answer usefully — should ever trip that guard.
     */
    public boolean isLocalSkip() {
        return this == BLOCKED || this == GONE;
    }
}
