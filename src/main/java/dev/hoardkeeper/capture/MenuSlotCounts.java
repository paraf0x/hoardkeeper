package dev.hoardkeeper.capture;

import java.util.Map;

/**
 * Resolves how many container slots a scanned menu had.
 *
 * <p>{@link #resolve} trusts the slot count DERIVED from the click-packet size
 * ({@code totalItemsInPacket - 36}, subtracting the fixed 27-slot player inventory plus 9-slot
 * hotbar) over the count implied by the menu type id, whenever the two disagree. A server-side
 * plugin can open a GUI while claiming any vanilla (or bogus) menu type id it likes; trusting that
 * claim instead of the actual wire size would silently corrupt a scan by mis-sizing the container.
 * The packet size is the one thing the server cannot lie about without breaking the protocol.
 */
public final class MenuSlotCounts {

    private static final Map<String, Integer> EXPECTED = Map.of(
            "minecraft:generic_9x1", 9,
            "minecraft:generic_9x2", 18,
            "minecraft:generic_9x3", 27,
            "minecraft:generic_9x4", 36,
            "minecraft:generic_9x5", 45,
            "minecraft:generic_9x6", 54,
            "minecraft:shulker_box", 27,
            "minecraft:generic_3x3", 9,
            "minecraft:hopper", 5
    );

    /** Fixed size of a player's own inventory (27 main + 9 hotbar) included in every menu packet. */
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    private MenuSlotCounts() {
    }

    /**
     * Returns the vanilla slot count for a known menu type id, or {@code -1} if unknown.
     *
     * <p>{@code null} is "unknown", not an error: {@code EXPECTED} comes from {@code Map.of}, whose
     * {@code getOrDefault} throws on a null key rather than returning the default.
     */
    public static int expectedFor(String menuTypeId) {
        if (menuTypeId == null) {
            return -1;
        }
        return EXPECTED.getOrDefault(menuTypeId, -1);
    }

    /**
     * Returns the container's slot count, derived from the total item count carried in the menu
     * packet minus the player's own inventory. Returns {@code -1} if the packet is too small to
     * even contain a player inventory (i.e. not a real container menu).
     *
     * <p>See the class javadoc: the derived count always wins over {@link #expectedFor}, because a
     * plugin GUI can misrepresent its menu type but cannot misrepresent the packet's actual size.
     * The menu type id is therefore deliberately not an input here — it used to be a parameter this
     * method ignored entirely, which read like an oversight rather than the decision it is. Ask
     * {@link #mismatches} separately when you want to know that the two disagreed.
     */
    public static int resolve(int totalItemsInPacket) {
        int derived = totalItemsInPacket - PLAYER_INVENTORY_SLOTS;
        if (derived <= 0) {
            return -1;
        }
        return derived;
    }

    /**
     * True when {@code menuTypeId} is a menu type we know the vanilla slot count for and that count
     * differs from {@code resolvedSlots}.
     *
     * <p>{@link #resolve} silently wins such a disagreement, which is correct — but silence is not.
     * A mismatch means the container was read at a size other than the one its menu type claims,
     * and that is worth a WARN from the caller so a player can go and look at it. Reported rather
     * than logged here so this class keeps its only dependency on {@code java.util}.
     */
    public static boolean mismatches(String menuTypeId, int resolvedSlots) {
        int expected = expectedFor(menuTypeId);
        return expected > 0 && expected != resolvedSlots;
    }
}
