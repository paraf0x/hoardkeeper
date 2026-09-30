package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Arrays;

/** The same search, started by the key binding while holding a diamond. */
public final class SearchKeyScenario implements Scenario {
    static final String KEY_NAME = "key.hoardkeeper.search-held";

    @Override
    public String name() {
        return "search-key";
    }

    @Override
    public void run(Harness h) throws Exception {
        SearchScenario.scanTheBase(h);

        h.serverCommand("give " + Harness.PLAYER + " minecraft:diamond 1");
        h.waitFor("diamond in the hotbar", mc -> hotbarSlotOf(mc, Items.DIAMOND) >= 0, 5 * Harness.SECOND);
        h.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(hotbarSlotOf(mc, Items.DIAMOND)));
        KeyMapping mapping = h.onClient(mc -> Arrays.stream(mc.options.keyMappings)
                .filter(k -> KEY_NAME.equals(k.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(KEY_NAME + " is not registered")));

        h.ctx().getInput().pressKey(mapping);
        h.waitFor("eight containers lit from the key", mc -> SearchScenario.lit(mc) == Fixtures.WITH_DIAMONDS,
                10 * Harness.SECOND);
        h.checkEquals("lit after key", Fixtures.WITH_DIAMONDS, h.onClient(SearchScenario::lit));
        h.screenshot("lit");

        h.openAndClose(Fixtures.NEAREST_CHEST);
        h.waitFor("one box dismissed", mc -> SearchScenario.lit(mc) == Fixtures.WITH_DIAMONDS - 1,
                5 * Harness.SECOND);
        h.checkEquals("lit after opening one by hand", Fixtures.WITH_DIAMONDS - 1, h.onClient(SearchScenario::lit));
    }

    static int hotbarSlotOf(Minecraft mc, Item item) {
        Inventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }
}
