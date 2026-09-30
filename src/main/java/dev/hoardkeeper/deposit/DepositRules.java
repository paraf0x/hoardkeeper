package dev.hoardkeeper.deposit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What goes where — spec 2026-09-28-singleplayer-deposit-design.md §3 and §4, as pure functions.
 *
 * <p>A player stack moves into a container when the container already holds the same item. "The
 * same item" is a {@link Signature}: for a loose stack its own key; for a shulker box that is full of
 * one item, that item's key marked as a box. A full box goes where a box of the same item is, or
 * where that item lies loose ({@link #accepts}); a loose item goes where it lies loose, or where a
 * box holding only that item is ({@link #held}).
 *
 * <p>Pure: no Minecraft, no world, no clock. The client turns {@code ItemStack}s into
 * {@link DepositStack}s and clicks the slots {@link #slotsToMove} names.
 */
public final class DepositRules {

    /** Slots in a shulker box. A box is full only when every one of them holds a full stack. */
    public static final int SHULKER_SLOTS = 27;

    private DepositRules() {
    }

    /** What a container has to hold already for a stack to go in. */
    public record Signature<K>(K key, boolean shulkerBox) {
        public static <K> Signature<K> item(K key) {
            return new Signature<>(key, false);
        }

        public static <K> Signature<K> boxOf(K key) {
            return new Signature<>(key, true);
        }
    }

    /**
     * The signature a player stack deposits under, or empty when it never moves (§4): anything
     * damageable, anything carrying contents other than a qualifying shulker box, and a shulker box
     * that is not full of one item.
     */
    public static <K> Optional<Signature<K>> movable(DepositStack<K> stack) {
        if (stack == null || stack.count() <= 0 || stack.damageable() || stack.otherContents()) {
            return Optional.empty();
        }
        if (stack.shulkerBox()) {
            return fullOfOne(stack).map(Signature::boxOf);
        }
        return Optional.of(Signature.item(stack.key()));
    }

    /**
     * The signatures a container's contents answer to. A loose stack answers to its own item —
     * damageable or not, since whether the container holds a pickaxe only matters for a pickaxe,
     * and pickaxes never move. A shulker box answers only when it is full of one item (§3's
     * content signature); any other box answers to nothing.
     */
    public static <K> Set<Signature<K>> held(List<DepositStack<K>> containerStacks) {
        Set<Signature<K>> held = new HashSet<>();
        for (DepositStack<K> stack : containerStacks) {
            if (stack == null || stack.count() <= 0) {
                continue;
            }
            if (stack.shulkerBox()) {
                // A box full of one item is a target for a box like it; a box holding only one
                // item, full or not, is a target for that item loose (owner, 2026-09-29).
                fullOfOne(stack).ifPresent(key -> held.add(Signature.boxOf(key)));
                onlyOne(stack).ifPresent(key -> held.add(Signature.item(key)));
            } else if (!stack.otherContents()) {
                held.add(Signature.item(stack.key()));
            }
        }
        return held;
    }

    /**
     * Which of the player's main-inventory slots to quick-move into a container holding
     * {@code held}. {@code mainInventory} is the 27 main slots only — the hotbar is never passed in,
     * which is how §4's "the hotbar never moves" holds. {@code null} entries are empty slots.
     * Indices are into {@code mainInventory}, in order.
     */
    public static <K> List<Integer> slotsToMove(List<DepositStack<K>> mainInventory, Set<Signature<K>> held) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < mainInventory.size(); i++) {
            Optional<Signature<K>> signature = movable(mainInventory.get(i));
            if (signature.isPresent() && accepts(held, signature.get())) {
                slots.add(i);
            }
        }
        return slots;
    }

    /**
     * Whether a container holding {@code held} takes a stack deposited under {@code signature}: the
     * same signature, or — for a full box of one item — that item lying loose. A box of coal goes
     * where coal is kept. The reverse is {@link #held}'s: a box of only coal answers to loose coal.
     */
    public static <K> boolean accepts(Set<Signature<K>> held, Signature<K> signature) {
        return held.contains(signature)
                || (signature.shulkerBox() && held.contains(Signature.item(signature.key())));
    }

    /** Every signature the player could deposit somewhere — the question the lit containers answer. */
    public static <K> Set<Signature<K>> carried(List<DepositStack<K>> mainInventory) {
        Set<Signature<K>> carried = new HashSet<>();
        for (DepositStack<K> stack : mainInventory) {
            movable(stack).ifPresent(carried::add);
        }
        return carried;
    }

    /** The one item a shulker box holds, full or not; empty for an empty or a mixed box. */
    static <K> Optional<K> onlyOne(DepositStack<K> box) {
        K key = null;
        for (DepositStack<K> stack : box.contents()) {
            if (stack == null || stack.count() <= 0) {
                continue;
            }
            if (key == null) {
                key = stack.key();
            } else if (!key.equals(stack.key())) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(key);
    }

    /**
     * The one item a shulker box is full of: all {@link #SHULKER_SLOTS} slots filled, every stack
     * the same item, every stack full. Empty for any other box.
     */
    static <K> Optional<K> fullOfOne(DepositStack<K> box) {
        List<DepositStack<K>> contents = box.contents();
        if (contents.size() != SHULKER_SLOTS) {
            return Optional.empty();
        }
        if (contents.get(0) == null) {
            return Optional.empty();
        }
        K key = contents.get(0).key();
        for (DepositStack<K> stack : contents) {
            if (stack == null || key == null || !key.equals(stack.key())
                    || stack.count() != stack.maxStackSize() || stack.count() <= 0) {
                return Optional.empty();
            }
        }
        return Optional.of(key);
    }
}
