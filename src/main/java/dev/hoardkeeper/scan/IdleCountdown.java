package dev.hoardkeeper.scan;

/**
 * Counts down the quiet time since the scanner last finished with a container, so a scan can end
 * itself instead of waiting for a player who has already walked away.
 *
 * <p><b>Why this exists.</b> Scanning is one intention that costs three commands, and the two that
 * matter — stop, upload — come after the interesting part is over, which is exactly when attention
 * leaves. A timer hung on the thing the player actually does, namely stop finding containers, is a
 * better signal for "this is finished" than hoping they remember.
 *
 * <p><b>Paused time is not idle time.</b> {@link #update} takes a {@code paused} flag and lets the
 * clock move while the countdown does not. The scanner already refuses to act while a screen is
 * open, while sneaking, while the use key is held or while the connection is stalled — none of which
 * mean "nothing left to scan". Someone who opens a chest and spends a minute sorting it would
 * otherwise come back to a scan that had finished and uploaded itself underneath them.
 *
 * <p>Pure: no Minecraft types, time arrives as a parameter. The whole point is that "does this end
 * the scan?" is answerable in a unit test rather than only by standing in a base for half a minute.
 */
public final class IdleCountdown {

    /**
     * Longest gap between two {@link #update} calls that is treated as elapsed idle time. Updates
     * arrive every client tick, so a gap of seconds means the game itself stalled — a chunk-loading
     * hitch, an alt-tab, a garbage-collection pause. That time passed for the world but the player
     * was not idle through it in any sense they would recognise, and counting it would end scans
     * during exactly the stutters a big base causes.
     */
    private static final long MAX_CREDITED_STEP_MILLIS = 2_000;

    private final long timeoutMillis;

    private long remainingMillis;
    private long lastMillis;
    private boolean running;

    public IdleCountdown(long timeoutMillis) {
        this.timeoutMillis = Math.max(0, timeoutMillis);
    }

    /** Whether the countdown does anything at all. A timeout of zero switches the feature off. */
    public boolean enabled() {
        return timeoutMillis > 0;
    }

    /** Starts, or restarts, the full countdown — called when a scan begins and on every container. */
    public void reset(long nowMillis) {
        remainingMillis = timeoutMillis;
        lastMillis = nowMillis;
        running = true;
    }

    /** Stops the countdown entirely, so {@link #expired()} stays false until the next reset. */
    public void stop() {
        running = false;
        remainingMillis = timeoutMillis;
    }

    /**
     * Advances the countdown to {@code nowMillis}. While {@code paused}, time is absorbed rather
     * than credited: the clock reference moves so the pause itself is never charged retroactively,
     * but nothing is deducted.
     */
    public void update(long nowMillis, boolean paused) {
        if (!running || !enabled()) {
            return;
        }
        long step = nowMillis - lastMillis;
        lastMillis = nowMillis;
        if (step <= 0) {
            // A non-advancing or backwards clock deducts nothing. Two updates in the same
            // millisecond are normal; a backwards jump is not, and guessing at it would be worse
            // than ignoring it.
            return;
        }
        if (paused) {
            return;
        }
        remainingMillis = Math.max(0, remainingMillis - Math.min(step, MAX_CREDITED_STEP_MILLIS));
    }

    /** True once the quiet period has run out and the scan should finish itself. */
    public boolean expired() {
        return running && enabled() && remainingMillis <= 0;
    }

    public long remainingMillis() {
        return remainingMillis;
    }

    /** Whole seconds left, rounded up — so a bar showing "1s" never sits there on 0.4 seconds. */
    public int remainingSeconds() {
        return (int) ((remainingMillis + 999) / 1000);
    }

    /** How full the bar is: 1.0 right after a container, 0.0 when the scan is about to end. */
    public double fraction() {
        if (!enabled()) {
            return 1.0;
        }
        return (double) remainingMillis / timeoutMillis;
    }

    /** How long the countdown has been quiet, which is what decides whether the bar is shown yet. */
    public long elapsedMillis() {
        return timeoutMillis - remainingMillis;
    }
}
