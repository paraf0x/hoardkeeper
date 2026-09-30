package dev.hoardkeeper.observe;

import dev.hoardkeeper.model.ScannedContainer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which block positions a storage actually registered as containers — the list behind "does this
 * chest belong here at all?" (design spec §3.3a).
 *
 * <p><b>Why a position list and not just the scanned area.</b> The area says where the storage is;
 * it cannot say what belongs to it. A shulker box set down for one trip, or a chest somebody added
 * after the last scan, stands squarely inside the area — and an observation of it is not a content
 * update for a container the storage knows, it invents one. Only a scan registers containers, and
 * a passive observation may only ever update what a scan already found.
 *
 * <p>Positions come from the scans' own {@code containers.jsonl} rows, so "registered" means
 * exactly "some scan of this storage filed a container here".
 *
 * <p><b>{@code pos} alone is the identity</b>, not {@code pos} plus {@code secondaryPos}:
 * {@code ContainerDiscovery.candidateAt} canonicalises a double chest to the same half whether the
 * player clicked the left block or the right one, which is the identity a scan files it under, the
 * one {@code ObservationLog} compacts on, and the one the search index keys on. Adding the second
 * half here would widen the rule to a position no scan and no observation ever uses.
 *
 * <p>No Minecraft imports and no file handling — this takes rows and returns a set, so the rule is
 * testable without a game. Reading and caching the files is {@code PassiveObserver}'s problem.
 */
public final class RegisteredContainers {

    private RegisteredContainers() {
    }

    /**
     * Every position {@code rows} filed a container under. A row whose {@code pos} is not exactly
     * three elements is skipped rather than fatal — a hand-edited or older-version
     * {@code containers.jsonl} must not cost the whole list, the same guard the search index and
     * {@code MeasuredMap} already apply.
     */
    public static Set<Long> positionsOf(List<ScannedContainer> rows) {
        Set<Long> positions = new HashSet<>();
        for (ScannedContainer row : rows) {
            if (row != null && isPos(row.pos)) {
                positions.add(key(row.pos[0], row.pos[1], row.pos[2]));
            }
        }
        return positions;
    }

    /**
     * {@code known} plus {@code pos} — how a just-projected observation joins a set that was parsed
     * before the append behind it landed. Returns a new set rather than mutating: the sets this
     * class produces are handed out and cached, and a position that is not a position is no change
     * at all rather than a silently widened rule.
     */
    public static Set<Long> with(Set<Long> known, int[] pos) {
        if (!isPos(pos)) {
            return known;
        }
        Set<Long> extended = new HashSet<>(known);
        extended.add(key(pos[0], pos[1], pos[2]));
        return extended;
    }

    /** Whether {@code pos} names one of the containers in {@code known}. */
    public static boolean holds(Set<Long> known, int[] pos) {
        return isPos(pos) && known.contains(key(pos[0], pos[1], pos[2]));
    }

    private static boolean isPos(int[] pos) {
        return pos != null && pos.length == 3;
    }

    /**
     * One block position as a long: 26 bits of X, 26 of Z, 12 of Y — the same packing vanilla's
     * {@code BlockPos.asLong} uses, reimplemented here only so this class stays free of Minecraft.
     * Negative coordinates survive by two's-complement truncation, and the ranges are far wider
     * than any world: ±33.5M horizontally against a ±30M border, ±2048 vertically against a build
     * range of 384.
     */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FF_FFFF) << 38)
                | ((long) (z & 0x3FF_FFFF) << 12)
                | (y & 0xFFFL);
    }
}
