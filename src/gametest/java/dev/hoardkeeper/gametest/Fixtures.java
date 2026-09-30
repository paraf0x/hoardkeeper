package dev.hoardkeeper.gametest;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * The test base: eleven containers, all within four blocks of ORIGIN, because
 * ScanController.inReach only sends an open for what the player could open by hand.
 *
 * <pre>
 *   z=2  : C(-3) C(-1) C(1) C(3)          chests, 5 diamonds each
 *   z=0  : C(-3) S(-1) . L(1) R(2)        C diamonds; S chest holding a shulker box; L+R one double chest
 *   z=-2 : C(-3) C(-1) B(1) B(3)          B barrels, 12 iron each
 * </pre>
 * The player stands at (0, -60, 0). Every single chest says type=single outright; the double
 * chest is left at (1,0) and right at (2,0): for a north-facing chest the left half's partner
 * lies to its east (ChestBlock.getConnectedDirection: LEFT -> facing.getClockWise()).
 */
public final class Fixtures {
    public static final int CONTAINERS = 11;
    public static final int WITH_DIAMONDS = 8;
    public static final int DIAMONDS_TOTAL = 40;
    public static final int NESTED_GOLD = 7;

    /** Two blocks in front of the player; the chest the click scenarios open and retry locks. */
    public static final BlockPos NEAREST_CHEST = new BlockPos(-1, -60, 2);
    /** Free spot inside the scanned area where passive places a shulker the scan never saw. */
    public static final BlockPos INTRUDER = new BlockPos(0, -60, -2);
    /** Where measured stands for its radius-2 rescan of the north-east corner. */
    public static final BlockPos CORNER = new BlockPos(3, -60, 1);
    /** Well outside every scanned area. */
    public static final BlockPos FAR = new BlockPos(4000, -60, 4000);
    public static final BlockPos FAR_CHEST = new BlockPos(4000, -60, 4002);

    private static final BlockPos[] DIAMOND_CHESTS = {
            pos(-3, 2), pos(-1, 2), pos(1, 2), pos(3, 2), pos(-3, -2), pos(-1, -2), pos(-3, 0)};
    /** Left half of the double chest; the right half is one block east (see class javadoc). */
    private static final BlockPos DOUBLE_CHEST_LEFT = pos(1, 0);
    private static final BlockPos DOUBLE_CHEST_RIGHT = pos(2, 0);
    private static final BlockPos BARREL_1 = pos(1, -2);
    private static final BlockPos BARREL_2 = pos(3, -2);
    /** The chest that holds a shulker box, itself holding gold. */
    private static final BlockPos SHULKER_CHEST = pos(-1, 0);

    /** Every position testBase places a block entity at; the double chest contributes both halves. */
    private static final BlockPos[] ALL_FIXTURES = Stream.concat(Arrays.stream(DIAMOND_CHESTS),
            Stream.of(DOUBLE_CHEST_LEFT, DOUBLE_CHEST_RIGHT, BARREL_1, BARREL_2, SHULKER_CHEST))
            .toArray(BlockPos[]::new);

    private Fixtures() {
    }

    private static BlockPos pos(int x, int z) {
        return new BlockPos(x, -60, z);
    }

    static void testBase(Harness h) {
        for (BlockPos p : DIAMOND_CHESTS) {
            chest(h, p, "single");
            h.serverCommand("item replace block " + p.getX() + " " + p.getY() + " " + p.getZ()
                    + " container.0 with minecraft:diamond 5");
        }
        chest(h, DOUBLE_CHEST_LEFT, "left");
        chest(h, DOUBLE_CHEST_RIGHT, "right");
        h.serverCommand("item replace block " + DOUBLE_CHEST_LEFT.getX() + " " + DOUBLE_CHEST_LEFT.getY()
                + " " + DOUBLE_CHEST_LEFT.getZ() + " container.0 with minecraft:diamond 5");

        h.serverCommand("setblock " + BARREL_1.getX() + " " + BARREL_1.getY() + " " + BARREL_1.getZ()
                + " minecraft:barrel[facing=up]");
        h.serverCommand("item replace block " + BARREL_1.getX() + " " + BARREL_1.getY() + " " + BARREL_1.getZ()
                + " container.0 with minecraft:iron_ingot 12");
        h.serverCommand("setblock " + BARREL_2.getX() + " " + BARREL_2.getY() + " " + BARREL_2.getZ()
                + " minecraft:barrel[facing=up]");
        h.serverCommand("item replace block " + BARREL_2.getX() + " " + BARREL_2.getY() + " " + BARREL_2.getZ()
                + " container.0 with minecraft:iron_ingot 12");

        chest(h, SHULKER_CHEST, "single");
        h.serverCommand("item replace block " + SHULKER_CHEST.getX() + " " + SHULKER_CHEST.getY() + " "
                + SHULKER_CHEST.getZ() + " container.0 with "
                + "minecraft:shulker_box[container=[{slot:0,item:{id:\"minecraft:gold_ingot\",count:"
                + NESTED_GOLD + "}}]]");
    }

    /** Every block entity the test base places, in client-side terms. The double chest is two. */
    public static boolean allVisibleTo(Minecraft mc) {
        if (mc.level == null) {
            return false;
        }
        for (BlockPos p : ALL_FIXTURES) {
            if (mc.level.getBlockEntity(p) == null) {
                return false;
            }
        }
        return true;
    }

    private static void chest(Harness h, BlockPos pos, String type) {
        h.serverCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " minecraft:chest[facing=north,type=" + type + "]");
    }
}
