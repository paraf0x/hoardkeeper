package dev.hoardkeeper.scan;

/**
 * The last menu this mod opened without a screen — the scanner's, or the deposit's — so the
 * player's own inventory updates the server sends under its id are not lost. Pure; the client
 * packet hook asks it.
 *
 * <p><b>Why this exists.</b> While the server has a chest menu open for the player, every change to
 * the player's inventory (a pickup, a {@code /give}) goes out as a slot update <em>for that
 * menu</em>. The client drops an update whose id is not its own {@code containerMenu}, and the
 * scanner never installs its menu. When the close arrives, the server copies what it believes the
 * client has into the inventory menu ({@code transferState}), so it never sends the item again: the
 * client simply does not have it until something forces a full resync. Found by a gametest whose
 * {@code /give} landed in the one tick between the scan's last container and its close.
 *
 * <p>Kept until the next menu opens or the player leaves: the server only sends under this id until
 * it has processed the close, which is always before it opens another menu.
 */
public final class PhantomMenu {

    /** Main inventory (27) and hotbar (9), after the container's own slots in every menu the mod opens. */
    private static final int MAIN = 27;
    private static final int HOTBAR = 9;

    private static int id = -1;
    private static int containerSlots = -1;

    private PhantomMenu() {
    }

    /** A menu with {@code containerSlots} slots of its own was opened under {@code id} without a screen. */
    public static void opened(int menuId, int slots) {
        if (menuId > 0 && slots > 0) {
            id = menuId;
            containerSlots = slots;
        }
    }

    /** Another menu opened, or the player left. */
    public static void forget() {
        id = -1;
        containerSlots = -1;
    }

    /**
     * The player {@code Inventory} index a slot update for {@code menuId}/{@code menuSlot} stands
     * for, or {@code -1} when it is not a player slot of the remembered menu.
     */
    public static int inventorySlot(int menuId, int menuSlot) {
        if (id <= 0 || menuId != id) {
            return -1;
        }
        return inventoryIndex(containerSlots, menuSlot);
    }

    /** Menu slot to {@code Inventory} index: the 27 main slots are 9–35 there, the hotbar 0–8. */
    static int inventoryIndex(int containerSlots, int menuSlot) {
        int i = menuSlot - containerSlots;
        if (containerSlots <= 0 || i < 0 || i >= MAIN + HOTBAR) {
            return -1;
        }
        return i < MAIN ? HOTBAR + i : i - MAIN;
    }
}
