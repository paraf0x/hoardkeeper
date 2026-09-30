package dev.hoardkeeper.deposit;

import dev.hoardkeeper.search.SearchText;

import java.util.List;

/**
 * Every line the deposit mode shows, pure: strings in, a string out, no Minecraft — so what a player
 * reads is tested without a client, the same split {@code ScanText} and {@code SearchText} make.
 */
public final class DepositText {

    private static final String PREFIX = "§b[Hoard] ";

    private DepositText() {
    }

    public static String on() {
        return PREFIX + "§fDeposit mode on.§7 Walk past the lit containers and what you carry goes in."
                + " Sneak-right-click a container with an empty hand to fill just that one.";
    }

    /** Said once when the mode turns on where no scan covers the spot, so nothing can be lit. */
    public static String noScanHere() {
        return PREFIX + "§7No scan covers this spot, so nothing is lit — containers in reach are still"
                + " filled. §f/hoard start§7 here lights them.";
    }

    /** Said once when the mode turns on with nothing in the main inventory that could move. */
    public static String nothingToPutAway() {
        return PREFIX + "§7Nothing in your inventory to put away §8(the hotbar, tools, armour and bundles"
                + " always stay)§7.";
    }

    public static String off(int moved) {
        return PREFIX + "§fDeposit mode off §7— §e" + moved + "§7 " + stacks(moved) + " put away.";
    }

    public static String alreadyOn() {
        return PREFIX + "§7Deposit mode is already on.";
    }

    public static String alreadyOff() {
        return PREFIX + "§7Deposit mode is already off.";
    }

    /** Servers usually have their own way of putting things away — Beacoland's is /depositall. */
    public static String notSingleplayer() {
        return PREFIX + "§fDeposit mode is for singleplayer only.§7 Servers usually have a command of their"
                + " own for this, like §f/depositall§7.";
    }

    /** The action bar while the mode is on. {@code nearestMeters} below zero leaves the distance out. */
    public static String bar(int moved, int lit, int nearestMeters) {
        String line = "§bDeposit §7— §e" + moved + "§f " + stacks(moved) + " moved §7· §e" + lit + "§f lit";
        if (lit > 0 && nearestMeters >= 0) {
            line += " §7· nearest §f" + nearestMeters + " m";
        }
        return line;
    }

    /** In the mode, right after one container took something. */
    public static String depositedInto(int moved, int total, int lit) {
        return "§bDeposit §7— §a+" + moved + "§f " + stacks(moved) + " into this container §7· §e" + total
                + "§f moved" + (lit > 0 ? " §7· §e" + lit + "§f lit" : "");
    }

    /** In the mode, when the last lit container has taken what it could. */
    public static String allPutAway(int total) {
        return "§bDeposit §7— §aall put away§7 · §e" + total + "§f " + stacks(total) + " moved";
    }

    /** The billboard over a container that just took something. */
    public static String flash(int moved) {
        return "+" + moved;
    }

    /** After a sneak-right-click on one container. */
    public static String clicked(int moved) {
        return moved > 0
                ? "§bDeposit §7— §e" + moved + "§f " + stacks(moved) + " into this container"
                : "§bDeposit §7— §fnothing you carry goes into this container";
    }

    public static String clickedOutOfReach() {
        return "§bDeposit §7— §fthat container is out of reach now";
    }

    /**
     * The label over a lit container: the first thing that goes there with how many stacks of it
     * the player carries, and how many more kinds go there too.
     */
    public static String label(List<String> ids, int firstStacks) {
        if (ids.isEmpty()) {
            return "";
        }
        String first = SearchText.shortId(ids.get(0)) + (firstStacks > 1 ? " ×" + firstStacks : "");
        return ids.size() == 1 ? first : first + " +" + (ids.size() - 1);
    }

    private static String stacks(int n) {
        return n == 1 ? "stack" : "stacks";
    }
}
