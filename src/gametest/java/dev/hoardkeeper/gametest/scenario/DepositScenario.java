package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.deposit.DepositService;
import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.index.StorageIndexCache;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;

/**
 * The singleplayer deposit, spec 2026-09-28-singleplayer-deposit-design.md §9.
 *
 * <p>The harness is a dedicated server reached as localhost, not singleplayer, so the scenario opens
 * the deposit's singleplayer gate with its test seam — everything behind the gate is what runs. The
 * world is creative, which matters for the sneak-click: one left-click breaks a chest there, so
 * "the chest is still standing" is a real check, not a formality.
 *
 * <ol>
 *   <li>With the base scanned and the player out of reach, the mode lights exactly the containers
 *       holding diamonds (§7a).
 *   <li>Walking back into reach puts away the loose diamonds, the box full of dirt (to the box of
 *       dirt) and the box full of coal (to the loose coal), and leaves the hotbar diamonds, the
 *       pickaxe and the box of mixed items where they are, while the loose dirt goes beside the box of dirt
 *       (§3, §4). The lights go out, and no screen was ever shown.
 *   <li>With the mode off, a sneak-right-click with an empty hand on the nearest chest fills just
 *       that chest, without opening its screen (§8a).
 * </ol>
 */
public final class DepositScenario implements Scenario {

    /**
     * Out of reach of every fixture and still inside the radius-8 area the base was scanned in —
     * the lit set comes from the index for the storage the player stands in, and outside it there
     * is none. z=8 is on the circle's edge, which counts ({@code ScanArea.coversBlock} is {@code <=}).
     * The nearest containers, the chests at z=2, are then 5.56 blocks from the eye; the world is
     * creative, where reach is 5.0, less the 0.4 margin. (z=7 would be 4.57: in reach.)
     */
    private static final BlockPos AWAY = new BlockPos(0, -60, 8);
    /** The chest holding a shulker box full of dirt — the target the full dirt box must find. */
    private static final BlockPos DIRT_BOX_CHEST = Fixtures.INTRUDER;

    /** A chest of loose coal, placed after the scan, in reach of the origin and next to nothing. */
    private static final BlockPos COAL_CHEST = new BlockPos(-2, -60, -4);

    private static final int MAIN_DIAMONDS = 9;
    private static final int PICKAXE = 10;
    private static final int LOOSE_DIRT = 11;
    private static final int FULL_BOX = 12;
    private static final int MIXED_BOX = 13;
    private static final int COAL_BOX = 14;

    @Override
    public String name() {
        return "deposit";
    }

    @Override
    public void run(Harness h) throws Exception {
        SearchScenario.scanTheBase(h);
        h.setConfig(c -> c.passiveObserve = true);
        h.runOnClient(mc -> DepositService.allowAnyServerForGameTest(true));
        try {
            steps(h);
        } finally {
            h.runOnClient(mc -> DepositService.allowAnyServerForGameTest(false));
        }
    }

    private void steps(Harness h) {

        h.serverCommand("setblock " + at(DIRT_BOX_CHEST) + " minecraft:chest");
        h.serverCommand("item replace block " + at(DIRT_BOX_CHEST) + " container.0 with "
                + "minecraft:blue_shulker_box" + boxOf(27, "minecraft:dirt", 0, null));

        h.serverCommand("setblock " + at(COAL_CHEST) + " minecraft:chest");
        h.serverCommand("item replace block " + at(COAL_CHEST) + " container.0 with minecraft:coal 5");

        h.teleport(AWAY);
        give(h, "container." + MAIN_DIAMONDS, "minecraft:diamond 10");
        give(h, "hotbar.0", "minecraft:diamond 3");
        give(h, "container." + PICKAXE, "minecraft:iron_pickaxe");
        give(h, "container." + LOOSE_DIRT, "minecraft:dirt 64");
        give(h, "container." + FULL_BOX, "minecraft:red_shulker_box" + boxOf(27, "minecraft:dirt", 0, null));
        give(h, "container." + MIXED_BOX, "minecraft:red_shulker_box" + boxOf(26, "minecraft:dirt", 26, "minecraft:sand"));
        give(h, "container." + COAL_BOX, "minecraft:shulker_box" + boxOf(27, "minecraft:coal", 0, null));
        h.waitFor("the inventory arrived", mc -> !slot(mc, MIXED_BOX).isEmpty() && !slot(mc, MAIN_DIAMONDS).isEmpty(),
                5 * Harness.SECOND);

        // ---- 1. Lit, nothing in reach. ----
        h.command("hoard deposit");
        try {
            h.waitFor("the diamond containers lit", mc -> DepositService.get().litCount() == Fixtures.WITH_DIAMONDS,
                    10 * Harness.SECOND);
        } catch (AssertionError timeout) {
            // Say what was there instead: a timeout alone does not tell "no index" from "wrong count"
            // from "already deposited because a chest was in reach after all".
            h.number("lit", h.onClient(mc -> DepositService.get().litCount()));
            h.number("index", h.onClient(mc -> StorageIndexCache.get().current(mc) != null));
            h.number("main diamonds", h.onClient(mc -> slot(mc, MAIN_DIAMONDS).getCount()));
            h.number("player", h.onClient(mc -> mc.player.blockPosition().toShortString()));
            throw timeout;
        }
        h.checkEquals("lit from out of reach", Fixtures.WITH_DIAMONDS, h.onClient(mc -> DepositService.get().litCount()));
        h.checkEquals("nothing moved out of reach", 10, h.onClient(mc -> slot(mc, MAIN_DIAMONDS).getCount()));
        h.screenshot("lit");

        // ---- 2. Walk into reach. ----
        h.teleport(Harness.ORIGIN);
        h.waitFor("loose diamonds put away", mc -> slot(mc, MAIN_DIAMONDS).isEmpty(), 20 * Harness.SECOND);
        h.waitFor("full dirt box put away", mc -> slot(mc, FULL_BOX).isEmpty(), 20 * Harness.SECOND);
        h.waitFor("full coal box put away with the loose coal", mc -> slot(mc, COAL_BOX).isEmpty(), 20 * Harness.SECOND);
        // Give the mode time to do anything it should not.
        h.ctx().waitTicks(2 * Harness.SECOND);
        h.checkEquals("hotbar diamonds stay", 3, h.onClient(mc -> mc.player.getInventory().getItem(0).getCount()));
        h.check("pickaxe stays", h.onClient(mc -> slot(mc, PICKAXE).is(Items.IRON_PICKAXE)), "slot " + PICKAXE);
        // Owner, 2026-09-29: loose items may go beside a box holding only them, so the loose dirt
        // follows the box of dirt into its chest.
        h.waitFor("loose dirt beside the box of dirt", mc -> slot(mc, LOOSE_DIRT).isEmpty(), 10 * Harness.SECOND);
        h.check("mixed box stays", h.onClient(mc -> !slot(mc, MIXED_BOX).isEmpty()), "slot " + MIXED_BOX);
        h.checkEquals("the lights go out", 0, h.onClient(mc -> DepositService.get().litCount()));
        h.check("no screen was shown", h.onClient(mc -> mc.gui.screen() == null), "screen");

        h.command("hoard deposit off");
        h.awaitChatContaining("Deposit mode off", 5 * Harness.SECOND);

        // ---- 3. Sneak-right-click with an empty hand, mode off. ----
        give(h, "container." + MAIN_DIAMONDS, "minecraft:diamond 7");
        h.waitFor("diamonds back", mc -> slot(mc, MAIN_DIAMONDS).getCount() == 7, 5 * Harness.SECOND);
        h.ctx().getInput().pressKey(options -> options.keyHotbarSlots[1]);   // an empty hotbar slot
        h.waitFor("empty hand", mc -> mc.player.getMainHandItem().isEmpty(), 2 * Harness.SECOND);
        h.ctx().getInput().holdKey(options -> options.keyShift);
        try {
            h.ctx().waitTicks(2);
            h.rightClick(Fixtures.NEAREST_CHEST);
            h.waitFor("diamonds into the clicked chest", mc -> slot(mc, MAIN_DIAMONDS).isEmpty(), 10 * Harness.SECOND);
            h.check("no screen for a sneak-right-click", h.onClient(mc -> mc.gui.screen() == null), "screen");
        } finally {
            h.ctx().getInput().releaseKey(options -> options.keyShift);
        }
        h.check("the chest is still there", h.onClient(mc -> isChest(mc, Fixtures.NEAREST_CHEST)), Fixtures.NEAREST_CHEST);
        h.checkEquals("hotbar diamonds still stay", 3, h.onClient(mc -> mc.player.getInventory().getItem(0).getCount()));
    }

    private static ItemStack slot(Minecraft mc, int index) {
        return mc.player.getInventory().getItem(index);
    }

    private static boolean isChest(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() instanceof ChestBlock;
    }

    private static void give(Harness h, String slot, String item) {
        h.serverCommand("item replace entity " + Harness.PLAYER + " " + slot + " with " + item);
    }

    private static String at(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** A shulker box component: {@code n} full stacks of {@code id}, then one of {@code other} at {@code otherSlot}. */
    private static String boxOf(int n, String id, int otherSlot, String other) {
        StringBuilder sb = new StringBuilder("[container=[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{slot:").append(i).append(",item:{id:\"").append(id).append("\",count:64}}");
        }
        if (other != null) {
            sb.append(",{slot:").append(otherSlot).append(",item:{id:\"").append(other).append("\",count:64}}");
        }
        return sb.append("]]").toString();
    }
}
