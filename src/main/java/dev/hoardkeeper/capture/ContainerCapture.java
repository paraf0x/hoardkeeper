package dev.hoardkeeper.capture;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.ScannedItem;
import dev.hoardkeeper.scan.ContainerCandidate;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the raw {@link ItemStack} list from a container-content packet into a plain-Java
 * {@link ScannedContainer}, using {@link ItemFlattener} for each occupied slot.
 *
 * <p>No Minecraft type escapes {@link #capture}: the returned {@link ScannedContainer} is safe to
 * hand to a background thread.
 */
public final class ContainerCapture {

    private final ItemFlattener flattener;

    public ContainerCapture(HoardkeeperConfig config) {
        this.flattener = new ItemFlattener(config);
    }

    /**
     * @param items      the full item list from the packet; only the first {@code slotCount}
     *                   entries belong to the container itself (the rest is the player's
     *                   inventory/hotbar).
     * @param slotCount  number of container slots, as resolved by {@code MenuSlotCounts}.
     * @param candidate  the container this capture belongs to (position, kind).
     * @param dimension  the dimension identifier the container was scanned in.
     * @param menuTypeId the menu type identifier the container opened as.
     * @param title      the menu's title, already converted to plain text.
     */
    public ScannedContainer capture(List<ItemStack> items, int slotCount, ContainerCandidate candidate,
                                     String dimension, String menuTypeId, String title) {
        List<ItemStack> containerSlots = items.subList(0, slotCount);
        List<ScannedItem> scannedItems = new ArrayList<>();
        for (int slot = 0; slot < containerSlots.size(); slot++) {
            ItemStack stack = containerSlots.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            scannedItems.add(flattener.flatten(stack, slot, 0));
        }

        ScannedContainer out = new ScannedContainer();
        out.pos = candidate.pos.clone();
        out.secondaryPos = candidate.secondaryPos == null ? null : candidate.secondaryPos.clone();
        out.kind = candidate.kind.id();
        out.dimension = dimension;
        out.menuType = menuTypeId;
        out.title = title;
        out.scannedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        out.slotCount = slotCount;
        out.usedSlots = scannedItems.size();
        out.items = scannedItems;
        out.totals = Totals.of(scannedItems);
        return out;
    }
}
