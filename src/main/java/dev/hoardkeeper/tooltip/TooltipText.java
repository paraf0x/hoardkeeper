package dev.hoardkeeper.tooltip;

import dev.hoardkeeper.search.SearchText;

/**
 * The one line a tooltip gains for an item the measured map knows something about: design spec §5.
 *
 * <p><b>Pure</b>, like {@code chat.ScanText}, {@code site.SiteText} and {@code search.SearchText}:
 * plain numbers in, a {@link String} out, no Minecraft types. That is what lets the three line
 * shapes below be exercised without a client, and it is why this class lives on its own rather than
 * inside {@link dev.hoardkeeper.tooltip.StorageTooltip}, which is the thing that actually talks
 * to Minecraft.
 *
 * <p>Thousands are grouped through {@link SearchText#count}, the same helper the search result line
 * uses — the tooltip's numbers and the search's numbers must read identically, and formatting them a
 * second way here would be the one way to make that silently stop being true.
 *
 * <p><b>No age.</b> {@code total} is a sum across every container the measured map knows holds this
 * item, each one written at whatever moment it was last scanned or observed. Six chests measured at
 * six different times have no single age between them, and stamping the line with one anyway — the
 * newest? the oldest? an average? — would be a number this method invented rather than one it read.
 * A card about a single container ({@code peek.PeekModel}, a separate feature) can honestly say
 * "seen 2h ago" because it is about one {@code scannedAt}; a total across many can't, so it says
 * nothing about time at all.
 */
public final class TooltipText {

    private static final String LABEL = "§7Storage: ";

    private TooltipText() {
    }

    /**
     * The line appended under an item's tooltip: how much of it the storage the player is standing
     * in holds, and in how many containers.
     *
     * <pre>
     * Storage: 3,104 in 6 chests
     * Storage: 192 in 1 chest
     * Storage: none
     * </pre>
     *
     * <p>{@code total <= 0} reads as {@code none} — the storage is known and holds zero of the item,
     * which is a real fact worth a real word, not the digit {@code 0} and not "not measured". The
     * second case never reaches this method at all: {@link StorageTooltip} says nothing rather than
     * calling this when there is no storage to ask, which is the only place "I do not know" is
     * decided.
     */
    public static String line(long total, int containers) {
        if (total <= 0) {
            return LABEL + "§fnone";
        }
        return LABEL + "§f" + SearchText.count(total) + " in " + containers + " "
                + plural(containers, "chest", "chests");
    }

    private static String plural(int n, String one, String many) {
        return n == 1 ? one : many;
    }
}
