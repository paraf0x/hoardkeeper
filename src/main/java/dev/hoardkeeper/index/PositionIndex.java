package dev.hoardkeeper.index;

import dev.hoardkeeper.capture.Totals;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.store.SessionSnapshotCodec;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "What is in that container?" — the same rows {@link dev.hoardkeeper.search.SearchIndex} inverts
 * into item → containers, inverted the other way: canonical position → one {@link ContainerView}.
 *
 * <p>The peek card this feeds is drawn every frame the player crouches in front of a container, so
 * the lookup itself must be nothing but a map read. All the work — parsing timestamps, deciding
 * which of two rows at one spot wins, aliasing a double chest's two halves — happens once, up front,
 * in {@link #of}.
 *
 * <p><b>Pure.</b> No Minecraft import, no file handling, no static mutable state: built from an
 * already-parsed row list and never looks at the world, which is what makes it unit-testable without
 * a running game — the same discipline {@code SearchIndex} follows for the other direction.
 *
 * <p>Three rules, each matched to what the rest of the mod already does with these exact rows rather
 * than invented fresh:
 * <ul>
 *   <li><b>No usable {@code pos}, no entry.</b> Dropped exactly as {@code SearchIndex.of} drops it —
 *       a container with no coordinates is not a place and cannot be looked at.</li>
 *   <li><b>Both halves of a double chest are one card.</b> {@code secondaryPos}, when usable, is
 *       aliased to the very same {@link ContainerView} object as {@code pos}. This mirrors
 *       {@link dev.hoardkeeper.search.ContainerHit#covers}, which already treats a double chest as
 *       one hit for the search — crouching in front of either half must show one card, not a card
 *       that quietly differs depending on which half is nearer.</li>
 *   <li><b>Two rows at one position: the newer {@code scannedAt} wins.</b> A resumed scan,
 *       {@code /hoard retry-failed}, and — later — passive observation can all write the same
 *       position twice. The comparison is done with {@link SessionSnapshotCodec#parseInstant} and
 *       {@link SessionSnapshotCodec#isAfter}, unchanged, exactly as {@code SessionLookup.newest}
 *       already picks a winner among session snapshots: an unparseable timestamp reads as "unknown,
 *       not now" rather than "now", so a row with a real timestamp always outranks one without,
 *       regardless of scan order or list order, and the very first row seen at a position still wins
 *       something even when its own timestamp is garbage — there being nothing yet to lose to.</li>
 * </ul>
 */
public final class PositionIndex {

    private final Map<Long, ContainerView> byPos;

    private PositionIndex(Map<Long, ContainerView> byPos) {
        this.byPos = byPos;
    }

    /**
     * Builds the position → view lookup from one scan's containers.
     *
     * <p>A {@code null} or empty list is not an error — it is what a session with nothing scanned
     * yet looks like — and yields an index whose every {@link #view} call answers {@code null}. That
     * is the same shape a fully built index answers outside every scanned position, so callers never
     * need to ask which kind of "nothing" they got.
     */
    public static PositionIndex of(List<ScannedContainer> rows) {
        Map<Long, ContainerView> views = new HashMap<>();
        Map<Long, Instant> scannedAt = new HashMap<>();
        if (rows != null) {
            for (ScannedContainer row : rows) {
                if (row == null || !SessionSnapshotCodec.isPos(row.pos)) {
                    continue;
                }
                ContainerView view = new ContainerView(row.pos, row.kind, row.title, row.scannedAt,
                        row.slotCount, row.usedSlots, Totals.of(row.items));
                Instant when = SessionSnapshotCodec.parseInstant(row.scannedAt);
                register(views, scannedAt, row.pos, view, when);
                if (SessionSnapshotCodec.isPos(row.secondaryPos)) {
                    register(views, scannedAt, row.secondaryPos, view, when);
                }
            }
        }
        return new PositionIndex(Map.copyOf(views));
    }

    /**
     * Registers {@code view} under {@code pos}'s key when the key is empty or {@code when} is newer
     * than whatever is already there — the one place the "newest row wins" rule is decided, so both
     * a row's primary and secondary position go through it identically.
     */
    private static void register(Map<Long, ContainerView> views, Map<Long, Instant> scannedAt,
                                  int[] pos, ContainerView view, Instant when) {
        long key = packed(pos);
        if (!views.containsKey(key) || SessionSnapshotCodec.isAfter(when, scannedAt.get(key))) {
            views.put(key, view);
            scannedAt.put(key, when);
        }
    }

    /** The container at {@code (x, y, z)}, or {@code null} when nothing scanned landed there. */
    public ContainerView view(int x, int y, int z) {
        return byPos.get(packed(x, y, z));
    }

    // =========================================================================
    // Position packing
    // =========================================================================

    /**
     * Packs a block position into one {@code long}, bit-for-bit the same scheme
     * {@code net.minecraft.core.BlockPos.asLong} uses and {@code SessionSnapshotCodec.packed} already
     * keys candidates by: {@code x} in the top 26 bits, {@code z} in the next 26, {@code y} in the
     * bottom 12 — read off the 26.2 deobfuscated jar with {@code javap} rather than assumed, and
     * confirmed against a live {@code BlockPos.asLong} call before this file was written.
     *
     * <p>Duplicated rather than called, on purpose: {@code SessionSnapshotCodec.packed} is
     * package-private in {@code store}, and reaching it would mean either widening that visibility
     * for one caller outside its package, or importing {@code BlockPos} to redo its call locally —
     * and this class may import neither, since it has to stay provably Minecraft-free to stay
     * unit-testable. Using the same numeric scheme, even duplicated, is what keeps a position keyed
     * here consistent with one keyed anywhere else in the mod, should the two ever need comparing.
     */
    private static long packed(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) | ((long) z & 0x3FFFFFFL) << 12;
    }

    private static long packed(int[] pos) {
        return packed(pos[0], pos[1], pos[2]);
    }
}
