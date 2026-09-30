package dev.hoardkeeper.capture;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.model.ScannedItem;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a single Minecraft {@link ItemStack} — plus, when it is a shulker box or bundle, the
 * items nested inside it — into a plain-Java {@link ScannedItem} tree.
 *
 * <p><b>No Minecraft type escapes {@link #flatten}.</b> {@code ItemStack}, {@code Component} and
 * every registry object are read and converted here; the returned {@link ScannedItem} is safe to
 * hand to a background thread (see {@code ContainerJsonlWriter}).
 */
public final class ItemFlattener {

    private final HoardkeeperConfig config;

    public ItemFlattener(HoardkeeperConfig config) {
        this.config = config;
    }

    /**
     * Flattens {@code stack} into a {@link ScannedItem}. {@code slot} is the container slot index,
     * or {@code null} for items nested inside a shulker box or bundle. {@code depth} is the current
     * nesting depth (0 for a top-level container slot); recursion stops once
     * {@code depth >= config.maxNestingDepth}.
     */
    public ScannedItem flatten(ItemStack stack, Integer slot, int depth) {
        ScannedItem out = new ScannedItem();
        out.slot = slot;
        out.id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        out.count = stack.getCount();

        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            out.name = name.getString();
        }

        if (!"none".equals(config.componentDetail)) {
            Integer dmg = stack.get(DataComponents.DAMAGE);
            if (dmg != null && dmg > 0) {
                out.dmg = dmg;
                out.maxDmg = stack.get(DataComponents.MAX_DAMAGE);
            }
            out.ench = enchantments(stack.get(DataComponents.ENCHANTMENTS));
            out.storedEnch = enchantments(stack.get(DataComponents.STORED_ENCHANTMENTS));

            PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
            if (potion != null) {
                out.potion = potion.potion().flatMap(Holder::unwrapKey)
                        .map(k -> k.identifier().toString()).orElse(null);
            }
        }

        if (depth < config.maxNestingDepth) {
            List<ScannedItem> contents = null;

            ItemContainerContents container = stack.get(DataComponents.CONTAINER);
            if (container != null) {
                List<ScannedItem> containerContents = new ArrayList<>();
                container.nonEmptyItemCopyStream()
                        .forEach(nested -> containerContents.add(flatten(nested, null, depth + 1)));
                contents = containerContents;
            }

            if (config.recurseBundles) {
                BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null) {
                    if (contents == null) {
                        contents = new ArrayList<>();
                    }
                    List<ScannedItem> bundleContents = contents;
                    bundle.itemCopies()
                            .forEach(nested -> bundleContents.add(flatten(nested, null, depth + 1)));
                }
            }

            if (contents != null && !contents.isEmpty()) {
                out.contents = contents;
            }
        }

        return out;
    }

    /**
     * @return {@code null} when {@code e} is null or empty (so Gson omits the field entirely
     *         instead of writing an empty {@code {}} on every item), otherwise an
     *         insertion-ordered map of enchantment identifier to level. An enchantment holder that
     *         cannot resolve to a registry key (should not normally happen) maps to {@code "unknown"}
     *         rather than throwing.
     */
    private Map<String, Integer> enchantments(ItemEnchantments e) {
        if (e == null || e.isEmpty()) {
            return null;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        e.entrySet().forEach(entry -> out.put(
                entry.getKey().unwrapKey().map(k -> k.identifier().toString()).orElse("unknown"),
                entry.getIntValue()));
        return out;
    }
}
