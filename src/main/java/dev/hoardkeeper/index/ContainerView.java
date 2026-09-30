package dev.hoardkeeper.index;

import java.util.Map;

/**
 * One scanned container the way a peek card describes it: what it is, what it is called, when it
 * was last seen, how full it is, and what it holds.
 *
 * <p>{@code counts} comes straight from {@link dev.hoardkeeper.capture.Totals}, unchanged, which
 * means it counts <em>through</em> a shulker box lying inside the container: a chest holding one
 * shulker box full of iron lists both the shulker box itself and the iron. That is not a special
 * case to work around here — it is what the player sees the moment they open the chest, so a card
 * standing in for opening it has to agree, not offer a tidier but wrong number.
 *
 * <p>Pure — no Minecraft import, no file access — for the same reason {@link PositionIndex}, which
 * builds these, has to be: a peek card is drawn every frame the player crouches at a container, and
 * that path may never touch a file or a caching policy, only read what was already built.
 *
 * <p>{@code pos} is kept as the {@code int[]} the scan file already uses rather than a richer
 * position type, for the same reason {@link dev.hoardkeeper.search.ContainerHit} does: this
 * record travels from JSON through pure, game-free code. Note that the generated {@code equals} and
 * {@code hashCode} compare {@code pos} by array identity (arrays do not override {@code equals}) but
 * compare {@code counts} structurally, since {@code Map} does — an inconsistency nothing here relies
 * on, because a view is only ever reached by position through {@link PositionIndex#view}, never
 * compared against another view.
 */
public record ContainerView(int[] pos, String kind, String title, String scannedAt, int slotCount,
                             int usedSlots, Map<String, Long> counts) {
}
