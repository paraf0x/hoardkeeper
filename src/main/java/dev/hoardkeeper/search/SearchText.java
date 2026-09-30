package dev.hoardkeeper.search;

import java.util.Locale;

/**
 * Every string the search feature puts on screen, built from plain numbers and ids.
 *
 * <p>Split out of {@code ScanChat} for the same reason {@code hud.HudTextModel} is: {@code ScanChat}
 * imports Minecraft to send a line, so nothing in it can be exercised in a unit test. This class
 * takes no Minecraft types and only returns strings; {@link SearchService} hands them to
 * {@code ScanChat.info} / {@code ScanChat.actionBar}, which stay the only things that talk to the
 * player. Everything here is English, matching the rest of the mod.
 */
public final class SearchText {

    private static final String PREFIX = "§b[Hoard] ";
    private static final String QUIET_PREFIX = "§7[Hoard] ";
    private static final String MINECRAFT_NAMESPACE = "minecraft:";

    private SearchText() {
    }

    /**
     * An item id as a player reads it: {@code minecraft:diamond} is "diamond", but a modded
     * {@code create:andesite_alloy} keeps its namespace, because there the namespace is the part
     * that says which mod put it in the chest.
     */
    public static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.startsWith(MINECRAFT_NAMESPACE) ? id.substring(MINECRAFT_NAMESPACE.length()) : id;
    }

    /** Thousands-grouped, because a storage system's answers routinely run to five digits. */
    public static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * The one line the player watches while walking: what is lit, how much of it is left, and how
     * long the highlight has. Refreshed a few times a second by {@link SearchService}, both to
     * count down and because the vanilla action bar fades on its own after about three seconds.
     */
    public static String actionBar(String itemId, int containers, long items, int secondsLeft) {
        return "§bSearch §f" + shortId(itemId)
                + " §7— §e" + containers + "§f " + plural(containers, "container", "containers")
                + " §7(§f" + count(items) + "§7) — §e" + secondsLeft + "s";
    }

    /** The count drawn over each lit container: how much of the item that one holds. */
    public static String billboard(long items) {
        return count(items);
    }

    /**
     * The result line for a search that found something. {@code shown} is how many containers are
     * actually lit, which is smaller than {@code containers} when the nearest-N cap bit — and
     * saying so is the difference between "37 containers" meaning what it says and quietly meaning
     * something else.
     *
     * <p><b>No scan is named.</b> The search reads the measured map — everything the player has ever
     * measured in this storage, from however many scans and chest opens — so there is no single
     * session to point at, and the line that used to print one was printing the dimension's
     * directory name instead. What it says instead is where the answer came from: what the player
     * has measured here.
     */
    public static String found(String itemId, long items, int containers, int shown) {
        String line = PREFIX + "§e" + count(items) + "§f × §b" + shortId(itemId)
                + " §7in §e" + containers + "§f " + plural(containers, "container", "containers")
                + " §8(from what you have measured here)";
        if (shown < containers) {
            line += " §8— lighting the " + shown + " nearest";
        }
        return line;
    }

    /**
     * The held item is not in the scan. Said differently from {@link #notFound}, which answers a
     * typed guess: a registry id off the player's own hotbar cannot have a typo in it, so there is
     * nothing to suggest and nothing to tab-complete — only the plain fact.
     */
    public static String notHere(String itemId) {
        return QUIET_PREFIX + "§fNo §b" + shortId(itemId) + "§f in the last scan here.";
    }

    /** The key was pressed with an empty hand — the one thing that makes it do nothing. */
    public static String emptyHand() {
        return QUIET_PREFIX + "§fHold an item to search for it.";
    }

    /** The scan exists and was read, and holds nothing resembling what was typed. */
    public static String notFound(String query) {
        return QUIET_PREFIX + "§fNothing like §e" + query + "§f in the last scan. §8Tab-complete to see"
                + " what is in there.";
    }

    /** No session has ever been recorded for this server. */
    public static String noScan() {
        return QUIET_PREFIX + "§fNo scan on disk for this server yet — run §f/hoard§7 first.";
    }

    /** The map for this storage was read and holds no items at all (or no container was read). */
    public static String emptyScan() {
        return QUIET_PREFIX + "§fNothing you have measured here holds any items.";
    }

    /** The file is being read; the result follows a moment later on its own line. */
    public static String reading() {
        return QUIET_PREFIX + "§fReading the last scan…";
    }

    public static String cleared(String itemId) {
        return QUIET_PREFIX + "§fHighlight for §b" + shortId(itemId) + "§f cleared.";
    }

    public static String nothingToClear() {
        return QUIET_PREFIX + "§fNo highlight is active.";
    }

    /** Printed when the player has opened the last lit container — the search is finished. */
    public static String allVisited(String itemId, int containers) {
        return "§a[Hoard] §fThat was the last of §e" + containers + "§f "
                + plural(containers, "container", "containers") + " holding §b" + shortId(itemId) + "§f.";
    }

    /**
     * Printed under the result when the nearest lit container is far enough away that the player is
     * plainly not standing in the storage it came from.
     *
     * <p>Without it, that case looks exactly like a bug: a result line saying "37 containers", a
     * countdown running in the action bar, and an empty room. This says out loud that the answer is
     * real but somewhere else — which is also the one hint that makes the fix ({@code walk there},
     * or {@code /hoard} to measure where you are) obvious.
     */
    public static String elsewhere(int blocks) {
        return "§8    …the nearest one is " + count(blocks) + " blocks away — that scan is of"
                + " somewhere else. Walk over, or run §7/hoard§8 to measure where you are.";
    }

    /**
     * Printed when the last lit container stopped being a container — somebody broke it. Said out
     * loud because the highlight ends here without the player having opened anything, and silence
     * would be indistinguishable from the countdown quietly expiring early.
     */
    public static String allGone(String itemId) {
        return QUIET_PREFIX + "§fNo container holding §b" + shortId(itemId) + "§f is still standing.";
    }

    /**
     * The grey line beside a tab-completion entry. Drawn by the completion box, which renders no
     * {@code §} codes, so this is plain text.
     */
    public static String suggestionTooltip(long items, int containers) {
        return count(items) + " in " + containers + " " + plural(containers, "container", "containers");
    }

    private static String plural(int n, String one, String many) {
        return n == 1 ? one : many;
    }
}
