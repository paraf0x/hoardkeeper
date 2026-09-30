package dev.hoardkeeper.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The live result of one {@code /hoard search}: the containers still to visit, and how long
 * they stay lit.
 *
 * <p>A highlight retires one container at a time. Opening a highlighted chest takes it off the
 * list — that container's question is answered, and leaving it glowing afterwards is noise — while
 * the rest stay lit. The whole highlight ends when the last one is opened or when the countdown
 * runs out, whichever comes first.
 *
 * <p><b>Opening a container restarts the countdown.</b> The window is there so a forgotten
 * highlight does not glow for the rest of the session, not to race the player: somebody walking
 * between the six chests that hold redstone is visibly still using the result, and having it wink
 * out mid-walk would mean retyping the command every half minute. So the clock measures time since
 * the player last did something with the highlight, not time since the search.
 *
 * <p><b>Pure.</b> Time is a parameter on every method that needs it, positions are {@code int[]},
 * and nothing here reads the world or the clock — so the whole lifecycle is exercised in tests
 * without a running game. {@link SearchService} owns the real clock and the real block positions.
 */
public final class SearchHighlight {

    private static final long MILLIS_PER_SECOND = 1000L;

    private final String itemId;
    private final int totalMatches;
    private final int lit;
    private final long windowMillis;
    private final List<ContainerHit> remaining;

    private long expiresAtMillis;

    private SearchHighlight(String itemId, int totalMatches, int lit, long windowMillis,
                             long expiresAtMillis, List<ContainerHit> remaining) {
        this.itemId = itemId;
        this.totalMatches = totalMatches;
        this.lit = lit;
        this.windowMillis = windowMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.remaining = remaining;
    }

    /**
     * Lights up the {@code cap} containers nearest {@code eye} out of everything holding
     * {@code itemId}.
     *
     * <p>Capping happens once, here, rather than per frame in the renderer, and it is capped by
     * <em>distance from the player at the moment of the search</em>. A common item can sit in three
     * hundred containers; drawing all of them is an fps problem and reading them is impossible, and
     * the ones worth walking to are the near ones. Doing it once also keeps every later number
     * honest: what the action bar counts down is exactly what is lit.
     */
    public static SearchHighlight of(String itemId, List<ContainerHit> hits, double[] eye, int cap,
                                      long nowMillis, int windowSeconds) {
        List<ContainerHit> nearest = new ArrayList<>(hits);
        nearest.sort(Comparator.comparingDouble(hit -> hit.distanceSquaredFrom(eye)));
        if (cap >= 0 && nearest.size() > cap) {
            nearest = new ArrayList<>(nearest.subList(0, cap));
        }
        long window = Math.max(0L, windowSeconds) * MILLIS_PER_SECOND;
        return new SearchHighlight(itemId, hits.size(), nearest.size(), window, nowMillis + window,
                nearest);
    }

    /**
     * Retires the container at {@code (x, y, z)} — either half of a double chest — and restarts the
     * countdown. Returns whether anything was actually lit there, so the caller can stay silent
     * about the chests the player opens that have nothing to do with the search.
     */
    public boolean dismiss(int x, int y, int z, long nowMillis) {
        boolean removed = remaining.removeIf(hit -> hit.covers(x, y, z));
        if (removed) {
            expiresAtMillis = nowMillis + windowMillis;
        }
        return removed;
    }

    /**
     * Puts out every lit container that {@code gone} says is no longer there, and returns how many
     * went. Unlike {@link #dismiss} this does <em>not</em> restart the countdown: a container that
     * vanished is the world changing under the result, not the player using it.
     *
     * <p>The predicate carries all the world knowledge, which is what keeps this class free of it —
     * {@link SearchService} is the one that knows what a block is and whether its chunk is even
     * loaded.
     */
    public int removeIf(Predicate<ContainerHit> gone) {
        int before = remaining.size();
        remaining.removeIf(gone);
        return before - remaining.size();
    }

    public boolean expired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    /**
     * Whole seconds left, rounded up — so a highlight with any time at all on it reads as at least
     * {@code 1s} and reaches {@code 0s} only when it is genuinely over.
     */
    public int secondsLeft(long nowMillis) {
        long left = expiresAtMillis - nowMillis;
        if (left <= 0) {
            return 0;
        }
        return (int) ((left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND);
    }

    /** The containers still lit, nearest first as of the moment of the search. */
    public List<ContainerHit> remaining() {
        return Collections.unmodifiableList(remaining);
    }

    public boolean isEmpty() {
        return remaining.isEmpty();
    }

    public String itemId() {
        return itemId;
    }

    /** How many of the item sit in the containers still lit. */
    public long itemsRemaining() {
        long total = 0;
        for (ContainerHit hit : remaining) {
            total += hit.count();
        }
        return total;
    }

    /** Every container in the scan holding this item, including the ones the cap left dark. */
    public int totalMatches() {
        return totalMatches;
    }

    /**
     * How many containers this highlight lit up to begin with — {@link #totalMatches} once the cap
     * has been applied, and the number the "that was the last one" line counts. Deliberately not
     * {@code remaining().size()}, which shrinks as the player works through them.
     */
    public int lit() {
        return lit;
    }

    /** Whether the cap left some matching containers unlit — the player is told when it did. */
    public boolean capped() {
        return lit < totalMatches;
    }
}
