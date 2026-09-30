package dev.hoardkeeper.scan;

/**
 * Paces container-open requests: enforces a minimum gap between sends, and backs off
 * exponentially (capped) after a run of consecutive remote failures.
 */
public final class RateLimiter {

    /**
     * Sentinel meaning "no send has happened yet". Deliberately NOT {@code Integer.MIN_VALUE}:
     * {@code ready(tick)} computes {@code tick - lastSendTick}, and subtracting
     * {@code Integer.MIN_VALUE} from any non-negative tick overflows to a negative number,
     * which makes {@code ready()} return {@code false} forever — the scanner would silently
     * never open a single container. This exact bug shipped in the spike prototype.
     */
    private static final int NEVER = -10_000;

    private final int minTicks;
    private final int backoffAfter;
    private final int maxBackoffTicks;

    private int lastSendTick = NEVER;
    private int consecutiveFailures;

    public RateLimiter(int minTicksBetweenOpens, int backoffAfterConsecutiveFailures, int maxBackoffTicks) {
        this.minTicks = minTicksBetweenOpens;
        this.backoffAfter = backoffAfterConsecutiveFailures;
        this.maxBackoffTicks = maxBackoffTicks;
    }

    public boolean ready(int tick) {
        return tick - lastSendTick >= currentGap();
    }

    public void onSend(int tick) {
        this.lastSendTick = tick;
    }

    public void onSuccess() {
        this.consecutiveFailures = 0;
    }

    public void onRemoteFailure() {
        this.consecutiveFailures++;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * Largest shift distance we ever apply. Java masks the right-hand operand of {@code <<} to the
     * low 6 bits for a {@code long} ({@code x << 64} is {@code x}, not {@code 0}), so an unclamped
     * shift does not saturate — it <em>wraps</em>. With the defaults ({@code minTicks=4},
     * {@code backoffAfter=3}) consecutive failures 66..69 produced shifts 64..67 and therefore gaps
     * of 4/8/16/32 ticks instead of the 40-tick cap: the backoff silently defeated itself and the
     * client started pushing harder against a server that was already refusing it. That streak is
     * ordinary in the exact scenario the backoff exists for — an anticheat swallowing
     * {@code UseItemOn} yields three {@code NO_RESPONSE} failures per container, so 66 consecutive
     * failures arrive after roughly 22 containers.
     *
     * <p>31 is far past the point where {@code base << shift} exceeds any sane
     * {@code maxBackoffTicks}, so clamping here cannot change the value that is actually returned
     * for any reachable smaller streak.
     */
    private static final int MAX_SHIFT = 31;

    private int currentGap() {
        if (consecutiveFailures < backoffAfter) {
            return minTicks;
        }
        int base = Math.max(1, minTicks);
        int shift = consecutiveFailures - backoffAfter + 1;
        if (shift >= MAX_SHIFT) {
            return maxBackoffTicks;
        }
        long grown = (long) base << shift;
        return (int) Math.min(maxBackoffTicks, grown);
    }
}
