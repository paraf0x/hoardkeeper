package dev.hoardkeeper.scan;

/**
 * Decides when a screen blocking {@link ScanController#gate} has lasted long enough to be worth
 * telling the player about — pure tick arithmetic, with no Minecraft dependency, so it can be
 * unit-tested directly.
 *
 * <p>This does not change what the gate decides. Pausing while a screen is open (in MC 26.2,
 * {@code mc.gui.screen() != null}; the field was {@code mc.screen} on Minecraft in 26.1.2) is a
 * correctness requirement (see {@code gate}'s javadoc): a stale {@code ClientboundOpenScreenPacket}
 * that arrives after the in-flight request already timed out is let through by
 * {@code onOpenScreen}, opens a real container GUI, and the gate is what stops the scanner from
 * sending further interactions — or worse, from having its own close-packet handling slam that GUI
 * shut — while it is open. What was missing is that the pause was silent: a player who did not open
 * that screen themselves had no indication the scan had stopped making progress, or why. This class
 * only observes how long the block has lasted and says once, per continuous block, whether that
 * point has been reached.
 */
public final class ScreenBlockWatcher {

    /** Sentinel meaning "not currently blocked". */
    private static final int NOT_BLOCKED = -1;

    private final int announceAfterTicks;

    private int blockedSinceTick = NOT_BLOCKED;
    private boolean announced;

    public ScreenBlockWatcher(int announceAfterTicks) {
        this.announceAfterTicks = announceAfterTicks;
    }

    /**
     * Call once per tick with whether the gate is currently blocked by an open screen.
     *
     * @return {@code true} exactly once per continuous block, on the tick it first reaches
     *         {@code announceAfterTicks} — the caller should tell the player on that tick, and not
     *         before. A block that clears and later recurs is reported again, because
     *         {@code screenOpen == false} resets both the clock and the "already told them" flag.
     */
    /** Drops any in-progress block, so a fresh scan starts with a clean slate. */
    public void reset() {
        blockedSinceTick = NOT_BLOCKED;
        announced = false;
    }

    public boolean update(boolean screenOpen, int tick) {
        if (!screenOpen) {
            blockedSinceTick = NOT_BLOCKED;
            announced = false;
            return false;
        }
        if (blockedSinceTick == NOT_BLOCKED) {
            blockedSinceTick = tick;
        }
        if (!announced && tick - blockedSinceTick >= announceAfterTicks) {
            announced = true;
            return true;
        }
        return false;
    }
}
