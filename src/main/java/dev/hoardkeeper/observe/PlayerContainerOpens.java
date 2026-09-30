package dev.hoardkeeper.observe;

/**
 * Pairs a right-click with the menu it opens, for anyone who needs to know "the player opened that
 * container" rather than just "a container screen opened".
 *
 * <p>That fact is not one event on the client. The intent to open something is reported by a
 * right-click on a block — a chest with a block on top of it, or one a plugin refuses, never
 * actually opens, so the click alone is not proof. The outcome — a menu actually opened — arrives
 * separately as a container-screen packet, and that packet carries no position at all. The only way
 * to answer "which container did the player just open" is to remember the click and confirm it
 * against a menu that follows soon after: {@link #clicked} records the position and the tick, and
 * {@link #confirm} hands that position back only if a menu opens within {@link #CLICK_WINDOW_TICKS}
 * of it.
 *
 * <p>Deliberately ignorant of who is clicking or why: a caller whose own silent interactions should
 * not count as a real click (the scanner opening a chest to read it, say) is responsible for not
 * calling {@link #clicked} for those in the first place. This class only ever pairs what it is told
 * about.
 */
public final class PlayerContainerOpens {

    /**
     * How long a remembered right-click stays eligible to be confirmed by a container screen — two
     * seconds at 20 ticks/second. Long enough for a server round trip on a bad connection, short
     * enough that a click on one container cannot be credited to a menu opened somewhere else
     * afterwards.
     */
    public static final int CLICK_WINDOW_TICKS = 40;

    private int[] pendingClick;
    private int pendingClickTick;

    /** A block was right-clicked at {@code (x, y, z)} on {@code tick}. Remembered, not yet credited. */
    public void clicked(int x, int y, int z, int tick) {
        pendingClick = new int[]{x, y, z};
        pendingClickTick = tick;
    }

    /**
     * A container menu just opened on {@code tick}. Returns the remembered click's position if one
     * is pending and still inside {@link #CLICK_WINDOW_TICKS}, otherwise {@code null} — either
     * because there was no pending click (a server-opened GUI with no right-click behind it) or
     * because too much time passed. Consumes the pending click either way: a menu is credited to at
     * most one click.
     */
    public int[] confirm(int tick) {
        if (pendingClick == null) {
            return null;
        }
        int[] clicked = pendingClick;
        pendingClick = null;
        if (tick - pendingClickTick > CLICK_WINDOW_TICKS) {
            return null;
        }
        return clicked;
    }

    /** Drops a pending click once it is older than {@link #CLICK_WINDOW_TICKS}. Called from the tick. */
    public void expire(int tick) {
        if (pendingClick != null && tick - pendingClickTick > CLICK_WINDOW_TICKS) {
            pendingClick = null;
        }
    }

    /**
     * Forgets a pending click outright. {@code PassiveObserver} holds one of these as a final
     * field, so it cannot simply replace it with a fresh instance the way {@code SearchService}
     * does in its own {@code forget()} — this is the equivalent for a caller that cannot reassign.
     */
    public void reset() {
        pendingClick = null;
    }
}
