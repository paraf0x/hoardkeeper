package dev.hoardkeeper.scan;

/**
 * A single container the scanner intends to (or has tried to) open.
 * <p>
 * Uses {@code int[]} for positions rather than Minecraft's {@code BlockPos} so this class — and
 * {@code TargetSelector}/{@code ClusterHint} built on it — stays constructible and unit-testable
 * without a running game.
 */
public final class ContainerCandidate {
    public final int[] pos;
    /** Position of the other half of a double chest, or {@code null} if this is not one. */
    public final int[] secondaryPos;
    public final ContainerKind kind;

    public ContainerStatus status = ContainerStatus.PENDING;
    public FailReason failReason;
    public int attempts;
    public int cooldownUntilTick;
    /**
     * Epoch <strong>milliseconds</strong> at which this candidate was last observed — scanned or
     * failed — or {@code 0} if it has never been observed. This is deliberately per-candidate
     * rather than borrowed from the session: a container observed missing at the start of a long
     * scan must not be stamped with the time the session was last saved, which would make a stale
     * observation look newer than it is and could let it overwrite another player's more recent
     * scan of the same container once uploaded.
     */
    public long observedAt;

    public ContainerCandidate(int[] pos, int[] secondaryPos, ContainerKind kind) {
        this.pos = pos;
        this.secondaryPos = secondaryPos;
        this.kind = kind;
    }
}
