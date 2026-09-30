package dev.hoardkeeper.scan;

import dev.hoardkeeper.model.CandidateState;
import dev.hoardkeeper.model.SessionSnapshot;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mutable state of one in-progress (or resumed) scan: where it is scanning, what it has found
 * so far, and simple progress counters.
 * <p>
 * A data holder, not an encapsulation boundary — {@link ContainerDiscovery} and other scan-package
 * collaborators read {@code sessionId}, {@code server}, {@code dimension}, {@code origin} and
 * {@code area} directly. Every geometric question goes through {@link ScanArea} rather than through
 * arithmetic spelled out at the call site.
 */
public final class ScanSession {

    public static final String PURPOSE_STORAGE = "storage";
    public static final String PURPOSE_SITE = "site";

    public final String sessionId;
    public final String server;
    /**
     * The realm this scan started on, or {@code null} when the client could not tell. Sits next to
     * {@code server} rather than replacing it: the address says where the player connected, the
     * realm says which of the backends behind that address these coordinates belong to. See
     * {@link dev.hoardkeeper.realm.RealmKey}.
     */
    public final String realm;
    public final String dimension;
    public final int[] origin;
    public final ScanArea area;

    public final List<ContainerCandidate> candidates = new ArrayList<>();
    /**
     * Every packed block position ({@link net.minecraft.core.BlockPos#asLong(int, int, int)})
     * known to belong to a candidate, mapped to that candidate. Both halves of a double chest map
     * to the same candidate instance, so re-discovering the second half is a no-op lookup rather
     * than a new candidate.
     */
    public final Map<Long, ContainerCandidate> byPackedPos = new LinkedHashMap<>();

    /**
     * Why this scan is happening: {@code "storage"} or {@code "site"}.
     *
     * <p>The one field that decides whether a finished scan files its chests into the shared
     * catalogue. A site scan does not: the chests at a build site hold material already committed
     * to that build, and uploading them would offer the group glass that is spoken for and count it
     * twice in every total. Mutable and defaulted to {@code "storage"}, so that every session ever
     * written — including every one already on disk — means what it has always meant.
     */
    public String purpose = PURPOSE_STORAGE;

    public int chunksInRadius;
    public int chunksLoaded;
    public long startedNanos;
    public long lastScanNanos;

    public ScanSession(String sessionId, String server, String dimension, int[] origin, ScanArea area) {
        this(sessionId, server, dimension, origin, area, null);
    }

    public ScanSession(String sessionId, String server, String dimension, int[] origin, ScanArea area,
                       String realm) {
        this.sessionId = sessionId;
        this.server = server;
        this.dimension = dimension;
        this.origin = origin;
        this.area = area;
        this.realm = realm;
    }

    /** Builds a resumable snapshot of this session's current state, tagged with {@code state}. */
    /** Whether this scan keeps its chests out of the shared pool. */
    public boolean isSiteScan() {
        return PURPOSE_SITE.equals(purpose);
    }

    public SessionSnapshot toSnapshot(String state) {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.sessionId = sessionId;
        snapshot.server = server;
        snapshot.realm = realm;
        snapshot.dimension = dimension;
        snapshot.origin = origin;
        snapshot.radius = area.equivalentBlockRadius();
        snapshot.areaMode = area.mode().name();
        snapshot.chunkRadius = area.chunkRadius();
        snapshot.state = state;
        snapshot.purpose = purpose;

        SessionSnapshot.Stats stats = new SessionSnapshot.Stats();
        stats.known = candidates.size();
        stats.chunksInRadius = chunksInRadius;
        stats.chunksLoaded = chunksLoaded;

        List<CandidateState> states = new ArrayList<>(candidates.size());
        for (ContainerCandidate candidate : candidates) {
            switch (candidate.status) {
                case SCANNED -> stats.scanned++;
                case FAILED -> stats.failed++;
                case PENDING -> stats.pending++;
            }
            states.add(toCandidateState(candidate));
        }
        snapshot.stats = stats;
        snapshot.containers = states;

        return snapshot;
    }

    private static CandidateState toCandidateState(ContainerCandidate candidate) {
        CandidateState state = new CandidateState();
        state.pos = candidate.pos;
        state.secondaryPos = candidate.secondaryPos;
        state.kind = candidate.kind.id();
        state.status = candidate.status.name();
        state.attempts = candidate.attempts;
        state.failReason = candidate.failReason == null ? null : candidate.failReason.name();
        state.observedAt = candidate.observedAt > 0
                ? DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(candidate.observedAt))
                : null;
        return state;
    }
}
