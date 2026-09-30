package dev.hoardkeeper.deposit;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;

/**
 * "The same item" for the deposit rules: equal exactly when {@code ItemStack.isSameItemSameComponents}
 * says so, so an enchanted pickaxe is not a plain one and a renamed diamond is not a diamond.
 *
 * <p>Also carries the registry id, which is all the measured map indexes by — the highlight asks
 * the index by id, the deposit decides by key.
 */
public final class ItemKey {

    private final ItemStack stack;
    private final String id;

    private ItemKey(ItemStack stack) {
        this.stack = stack.copyWithCount(1);
        this.id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static ItemKey of(ItemStack stack) {
        return new ItemKey(stack);
    }

    /** The registry id, the same derivation {@code ItemFlattener} writes into a scan. */
    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemKey other && ItemStack.isSameItemSameComponents(stack, other.stack);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashItemAndComponents(stack);
    }

    @Override
    public String toString() {
        return id;
    }

    /** {@code null} for an empty stack. */
    public static DepositStack<ItemKey> describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        boolean shulker = Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (shulker) {
            List<DepositStack<ItemKey>> contents = container == null ? List.of()
                    : container.nonEmptyItemCopyStream().map(ItemKey::describe).toList();
            return DepositStack.shulkerBox(of(stack), contents);
        }
        boolean otherContents = (container != null && container.nonEmptyItemCopyStream().findAny().isPresent())
                || bundle != null;  // every bundle, empty or not — spec §4
        return new DepositStack<>(of(stack), stack.getCount(), stack.getMaxStackSize(),
                stack.isDamageableItem(), false, List.of(), otherContents);
    }

    /**
     * The registry id a signature is looked up under in the index: the item's own for a loose
     * stack, the content's for a box — the index counts a box's contents into its container.
     */
    public static String indexId(DepositRules.Signature<ItemKey> signature) {
        return signature.key().id();
    }
}
