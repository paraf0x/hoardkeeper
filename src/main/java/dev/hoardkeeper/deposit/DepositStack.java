package dev.hoardkeeper.deposit;

import java.util.List;

/**
 * What the deposit rules need to know about one stack, without Minecraft.
 *
 * <p>{@code key} stands for "the same item": two stacks are the same item exactly when their keys
 * are {@code equals}. The client builds it so that this matches
 * {@code ItemStack.isSameItemSameComponents} — same item, same components, so an enchanted pickaxe
 * is not a plain one and a renamed diamond is not a diamond. The rules never look inside it, which
 * is what lets them be tested with plain strings.
 *
 * @param key          identity of the item, equal exactly when the items are the same (see above)
 * @param count        how many are in the stack
 * @param maxStackSize how many the stack could hold
 * @param damageable   the item has durability (tools, weapons, armour, elytra, shields, …)
 * @param shulkerBox   the item is a shulker box, of any colour
 * @param contents     for a shulker box, the non-empty stacks inside it; empty otherwise
 * @param otherContents the item carries contents but is not a shulker box (a bundle, for one)
 */
public record DepositStack<K>(K key, int count, int maxStackSize, boolean damageable,
                              boolean shulkerBox, List<DepositStack<K>> contents, boolean otherContents) {

    public DepositStack {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    /** A plain stack: not damageable, no contents. */
    public static <K> DepositStack<K> item(K key, int count, int maxStackSize) {
        return new DepositStack<>(key, count, maxStackSize, false, false, List.of(), false);
    }

    /** A damageable item — a tool, a weapon, armour carried loose. */
    public static <K> DepositStack<K> damageable(K key) {
        return new DepositStack<>(key, 1, 1, true, false, List.of(), false);
    }

    /** A shulker box holding {@code contents}. */
    public static <K> DepositStack<K> shulkerBox(K key, List<DepositStack<K>> contents) {
        return new DepositStack<>(key, 1, 1, false, true, contents, false);
    }

    /** An item that carries contents without being a shulker box — a bundle. */
    public static <K> DepositStack<K> withContents(K key) {
        return new DepositStack<>(key, 1, 1, false, false, List.of(), true);
    }
}
