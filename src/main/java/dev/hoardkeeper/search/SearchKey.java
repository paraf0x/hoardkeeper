package dev.hoardkeeper.search;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.chat.ScanChat;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * One key: light up the containers holding whatever is in the player's hand.
 *
 * <p><b>Why a key and not typing.</b> Sorting a delivery into a storage hall is a loop — pick up a
 * stack, remember where that item lives, walk there, put it in, pick up the next — and the
 * remembering is the slow part. {@code /hoard search <item>} already answers it, but only
 * after the item's name has been typed, and nobody types forty item names while sorting forty
 * stacks. The hand already holds the question; this makes asking it one keypress.
 *
 * <p><b>It is the search, not a second feature.</b> Everything downstream — the nearest-N cap, the
 * boxes and their counts, the thirty-second window, a box going out when its container is opened —
 * is {@link SearchService} doing exactly what the command makes it do. The only thing that differs
 * is the lookup: {@link SearchService#searchHeld} takes the id exactly, because an id off the
 * hotbar is not a guess that needs resolving.
 *
 * <p><b>Client thread only.</b> {@link #onClientTick} is called from the client tick, which is also
 * where {@code consumeClick} is meant to be polled.
 */
public final class SearchKey {

    /** Translation key of the binding; see {@code assets/hoardkeeper/lang/en_us.json}. */
    private static final String KEY_NAME = "key.hoardkeeper.search-held";

    /** Path of this mod's key category. Its label key is {@code key.category.hoardkeeper.main}. */
    private static final String KEY_CATEGORY_PATH = "main";

    /** Null until {@link #register()} runs — i.e. in any test that touches this class. */
    private static KeyMapping key;

    private SearchKey() {
    }

    /**
     * Registers the binding. Called once, from the client entrypoint, before the first tick — which
     * is where Fabric expects key mappings to appear.
     *
     * <p>The category is this mod's own, so the binding is findable in Controls under a heading that
     * says which mod put it there. {@code KeyMapping.Category.register} throws if the same id is
     * registered twice, and a client that will not launch because of a keybind category would be a
     * bad trade — so the failure falls back to {@code MISC}, which is where an uncategorised binding
     * belongs anyway.
     */
    public static void register() {
        KeyMapping.Category category;
        try {
            category = KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(HoardkeeperMod.MOD_ID, KEY_CATEGORY_PATH));
        } catch (RuntimeException e) {
            HoardkeeperMod.LOGGER.warn("Could not register the key category; using MISC", e);
            category = KeyMapping.Category.MISC;
        }
        // V: unused by vanilla, and reachable by the left hand without leaving WASD, which is how
        // this key is actually used -- pressed mid-walk, between stacks, not reached for. H would
        // have been the obvious pick, but it collides with the advanced-tooltips binding and with
        // more than one popular mod. Rebindable in Controls like any other key.
        key = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(KEY_NAME, InputConstants.KEY_V, category));
    }

    /**
     * Drains the key's press queue once per client tick. Several presses inside one tick are nobody's
     * intention, so they collapse into one search rather than into several identical ones.
     */
    public static void onClientTick(Minecraft mc) {
        if (key == null) {
            return;
        }
        boolean pressed = false;
        while (key.consumeClick()) {
            pressed = true;
        }
        if (pressed) {
            searchHeld(mc);
        }
    }

    /**
     * Searches for the main hand's item. Public because this is the whole of what the key does, and
     * the self-test harness has no keyboard to press it with.
     *
     * <p>The main hand, not the off-hand: that is where the shulker box or the torch lives while
     * sorting, and the hotbar selection is the one thing the player is already changing between
     * stacks.
     *
     * <p>The id is the item's registry id and nothing more — the same derivation
     * {@code ItemFlattener} uses when writing a scan, so an id here is an id in the index, byte for
     * byte. A potion therefore lights every chest with potions, an enchanted book every chest with
     * enchanted books, and a red shulker box only the red ones. That is what the scan recorded, and
     * the typed command behaves the same way for the same reason.
     */
    public static void searchHeld(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            // The one press that can do nothing. Said out loud, because silence here is
            // indistinguishable from a key that is not bound to anything.
            ScanChat.info(SearchText.emptyHand());
            return;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return;
        }
        SearchService.get().searchHeld(mc, id.toString());
    }
}
