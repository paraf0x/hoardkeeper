package dev.hoardkeeper.store;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.model.CandidateState;
import dev.hoardkeeper.model.SessionSnapshot;
import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.ContainerKind;
import dev.hoardkeeper.scan.ContainerStatus;
import dev.hoardkeeper.scan.FailReason;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.scan.ScanSession;
import net.minecraft.core.BlockPos;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The reader for what {@link SessionStateStore} writes: turns a {@link SessionSnapshot} back into
 * a live {@link ScanSession}, and the small parsing helpers that make that safe against a
 * snapshot from another version. Pure — no Minecraft classes, no client — so it can be tested
 * without a running game.
 */
public final class SessionSnapshotCodec {

    private SessionSnapshotCodec() {
    }

    /**
     * Rebuilds a live {@link ScanSession} from a snapshot. Candidate rows that cannot be understood
     * (no position, an unknown kind) are dropped rather than guessed at: a candidate the scanner
     * cannot classify is one it could not open anyway, and inventing a kind for it would risk
     * reading a container with the wrong expected slot count.
     */
    public static ScanSession toSession(SessionSnapshot snapshot) {
        // The realm comes from the snapshot, never from the live connection: a resumed session
        // belongs to the realm it started on, even when the resume happens somewhere else.
        ScanSession restored = new ScanSession(snapshot.sessionId, snapshot.server, snapshot.dimension,
                snapshot.origin,
                ScanArea.fromSnapshot(snapshot.areaMode, snapshot.origin, snapshot.radius, snapshot.chunkRadius),
                snapshot.realm);
        // Without this a resumed site scan would upload itself on finish, which is the one thing
        // the purpose exists to prevent.
        restored.purpose = snapshot.purpose == null ? ScanSession.PURPOSE_STORAGE : snapshot.purpose;
        if (snapshot.containers == null) {
            return restored;
        }
        for (CandidateState state : snapshot.containers) {
            ContainerKind kind = parseKind(state.kind);
            if (!isPos(state.pos) || kind == null) {
                HoardkeeperMod.LOGGER.warn("Dropping unreadable candidate from snapshot (kind={})", state.kind);
                continue;
            }
            ContainerCandidate candidate = new ContainerCandidate(state.pos,
                    isPos(state.secondaryPos) ? state.secondaryPos : null, kind);
            candidate.status = parseStatus(state.status);
            candidate.failReason = parseReason(state.failReason);
            candidate.attempts = state.attempts;

            restored.candidates.add(candidate);
            // Both halves, exactly as ContainerDiscovery registers them — that identity is what keeps
            // a re-discovered double chest from becoming a second candidate.
            restored.byPackedPos.put(packed(candidate.pos), candidate);
            if (candidate.secondaryPos != null) {
                restored.byPackedPos.put(packed(candidate.secondaryPos), candidate);
            }
        }
        return restored;
    }

    // ---- Snapshot parsing. Every one of these treats junk as "unknown", never as a crash: a
    // resume index is an optimisation, and a corrupt one must not take the client with it. ----

    static ContainerKind parseKind(String id) {
        if (id == null) {
            return null;
        }
        try {
            return ContainerKind.valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Anything unrecognised becomes {@code PENDING} — an extra scan costs a packet, a wrong skip costs data. */
    static ContainerStatus parseStatus(String name) {
        if (name == null) {
            return ContainerStatus.PENDING;
        }
        try {
            return ContainerStatus.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ContainerStatus.PENDING;
        }
    }

    public static FailReason parseReason(String name) {
        if (name == null) {
            return null;
        }
        try {
            return FailReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Instant parseInstant(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code true} when {@code instant} is known and strictly newer than {@code other}. */
    public static boolean isAfter(Instant instant, Instant other) {
        return instant != null && (other == null || instant.isAfter(other));
    }

    public static boolean isPos(int[] pos) {
        return pos != null && pos.length == 3;
    }

    static long packed(int[] pos) {
        return BlockPos.asLong(pos[0], pos[1], pos[2]);
    }
}
