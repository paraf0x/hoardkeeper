package dev.hoardkeeper.scan;

import java.util.Locale;

/**
 * The kinds of container the scanner knows how to open. Deliberately its own enum rather than a
 * reference to a Minecraft block/item type, so this package stays testable without a game.
 */
public enum ContainerKind {
    CHEST,
    CHEST_DOUBLE,
    TRAPPED_CHEST,
    TRAPPED_CHEST_DOUBLE,
    BARREL,
    SHULKER_BOX;

    /** Lowercase identifier, e.g. {@code "chest_double"}. Used in on-disk/report JSON. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the "double" variant of this kind, for pairing two single-chest halves into one
     * logical double chest. Only chests have a double variant; every other kind maps to itself.
     */
    public ContainerKind asDouble() {
        return switch (this) {
            case CHEST -> CHEST_DOUBLE;
            case TRAPPED_CHEST -> TRAPPED_CHEST_DOUBLE;
            default -> this;
        };
    }
}
