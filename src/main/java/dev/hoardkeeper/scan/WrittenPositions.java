package dev.hoardkeeper.scan;

import dev.hoardkeeper.model.ScannedContainer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of block positions whose contents have already been appended to a session's
 * {@code containers.jsonl} — the scanner's single defence against writing the same chest twice.
 *
 * <p><b>Why this is its own class.</b> The rule it enforces is the most consequential pure logic in
 * the mod: a duplicate row silently doubles an item's total in {@code report.json}, and because the
 * two rows differ (different {@code scannedAt}, possibly a different {@code kind} and slot count)
 * no {@code sort | uniq -d} over the file can find it. It used to live inside {@code ScanController},
 * which is Minecraft-coupled and therefore untestable, and it was wrong there — the live-scan path
 * was never protected at all. This class has <b>zero Minecraft imports</b> precisely so the rule can
 * be unit-tested on its own.
 *
 * <p><b>Both halves matter.</b> A double chest is one candidate covering two block positions, and
 * either half is enough to identify it. Recording both, and matching on either, is what catches the
 * case where a chest is written as a single and then re-discovered as half of a newly-placed double:
 * the fresh {@code PENDING} double is recognised as already covered and never opened again. The
 * accepted trade-off is a visible gap (the new half is never read) rather than an invisible
 * duplicate.
 */
public final class WrittenPositions {

    /**
     * Packed positions. The packing is this class's own business — these longs are only ever
     * compared against other longs packed here — but it mirrors {@code BlockPos.asLong}'s layout
     * (26 bits x, 12 bits y, 26 bits z) so the numbers mean the same thing in a debugger as the
     * ones in {@code ScanSession.byPackedPos}.
     */
    private final Set<Long> packed = new HashSet<>();

    /** An empty set: nothing has been written yet. */
    public WrittenPositions() {
    }

    /**
     * Every position recorded in an existing {@code containers.jsonl}, for resuming an interrupted
     * session. Rows without a usable position are ignored rather than guessed at.
     */
    public static WrittenPositions of(List<ScannedContainer> containers) {
        WrittenPositions positions = new WrittenPositions();
        for (ScannedContainer container : containers) {
            positions.add(container.pos, container.secondaryPos);
        }
        return positions;
    }

    /**
     * Records one container's positions. {@code secondaryPos} may be {@code null} (anything that is
     * not half of a double chest); either argument is ignored if it is not a 3-element position.
     */
    public void add(int[] pos, int[] secondaryPos) {
        addOne(pos);
        addOne(secondaryPos);
    }

    /**
     * True when this candidate's contents are already on disk — matching on <em>either</em> half, so
     * a double chest is recognised no matter which half was recorded or which one is canonical.
     */
    public boolean covers(ContainerCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return contains(candidate.pos)
                || (candidate.secondaryPos != null && contains(candidate.secondaryPos));
    }

    /** True when exactly this block position has been written. */
    public boolean contains(int[] pos) {
        return isPos(pos) && packed.contains(pack(pos));
    }

    public boolean isEmpty() {
        return packed.isEmpty();
    }

    public int size() {
        return packed.size();
    }

    private void addOne(int[] pos) {
        if (isPos(pos)) {
            packed.add(pack(pos));
        }
    }

    private static boolean isPos(int[] pos) {
        return pos != null && pos.length == 3;
    }

    private static long pack(int[] pos) {
        return ((long) (pos[0] & 0x3FF_FFFF) << 38)
                | ((long) (pos[2] & 0x3FF_FFFF) << 12)
                | (pos[1] & 0xFFFL);
    }
}
