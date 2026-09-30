package dev.hoardkeeper.tooltip;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.index.StorageIndex;
import dev.hoardkeeper.index.StorageIndexCache;
import dev.hoardkeeper.search.SearchIndex;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Appends {@link TooltipText#line} to every item tooltip the game draws, from the same measured map
 * the search reads: design spec §5.
 *
 * <p><b>This fires per frame while a stack is hovered</b> — the inventory, an open container, the
 * creative search, anything the vanilla tooltip renderer touches. So its whole body below is exactly
 * a null check, two map lookups on an index someone else already built, and one string build: no
 * file access, no {@code join()} or {@code get()} on a future, no allocation beyond the line itself.
 * {@link StorageIndexCache#current} is what makes that possible — it never blocks and never reads a
 * file, because {@link StorageIndexCache#tick} already did that work off this call, once per client
 * tick rather than once per frame.
 *
 * <p><b>No caching of the rendered line, deliberately.</b> The cost above is small enough that
 * memoising it is not worth the bug surface of a stale cache key. If a profile ever says otherwise,
 * memoising on (item id, index identity) is the next step, and it needs no design change: the index
 * this reads is already a stable, whole object per {@link StorageIndexCache#tick}.
 */
public final class StorageTooltip {

    private StorageTooltip() {
    }

    /** Subscribes to {@link ItemTooltipCallback#EVENT}. Call once, from client init. */
    public static void register() {
        ItemTooltipCallback.EVENT.register(StorageTooltip::append);
    }

    private static void append(ItemStack stack, Item.TooltipContext context, TooltipFlag flag,
                                List<Component> lines) {
        if (!HoardkeeperMod.config().tooltipStorageCounts) {
            return;
        }
        StorageIndex index = StorageIndexCache.get().current(Minecraft.getInstance());
        if (index == null) {
            // Building, outside every scanned area, or no measured map on disk -- three cases
            // StorageIndexCache.current() treats identically, and so does this: say nothing.
            return;
        }
        if (stack.isEmpty()) {
            return;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return;
        }
        SearchIndex search = index.search();
        String itemId = id.toString();
        long total = search.total(itemId);
        int containers = search.hits(itemId).size();
        lines.add(Component.literal(TooltipText.line(total, containers)));
    }
}
