package dev.hoardkeeper.scan;

/** Lifecycle state of a single {@link ContainerCandidate} within a scan. */
public enum ContainerStatus {
    PENDING,
    SCANNED,
    FAILED
}
