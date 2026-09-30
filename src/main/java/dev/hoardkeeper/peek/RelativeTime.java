package dev.hoardkeeper.peek;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * "2h ago" from two instants: design spec §6, §10.
 *
 * <p>Pure — no Minecraft import — for the same reason {@code index.PositionIndex} is: the card
 * this feeds is drawn every frame the player crouches at a container, so the class that turns a
 * timestamp into words has to be exercisable without a client.
 *
 * <p><b>An invented freshness is worse than an absent one</b> (spec §10). A {@code null} or
 * unparseable {@code scannedAtIso} returns {@code null} rather than a string that claims to know an
 * age — the caller ({@code peek.PeekModel}) omits the age from the card entirely rather than ever
 * printing "0s ago" for a container it cannot actually date.
 *
 * <p><b>A future timestamp reads as "just now", never as a negative age.</b> {@code scannedAt} is
 * written by the server that ran the scan; {@code nowMillis} comes from whatever client is drawing
 * the card. The two clocks disagreeing by a few seconds is normal, not a fault in the data, and
 * "-3s ago" would read as a bug where "just now" reads as a rounding nobody needs to think about.
 */
public final class RelativeTime {

    private static final long SECONDS_PER_MINUTE = 60;
    private static final long SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE;
    private static final long SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR;

    private RelativeTime() {
    }

    /**
     * {@code scannedAtIso} as an age relative to {@code nowMillis}: {@code "just now"} under a
     * minute (and for any timestamp at or after {@code nowMillis} — clock skew, never a negative
     * number), then whole minutes, whole hours, then whole days. {@code null} in and {@code null}
     * out for a missing or unparseable timestamp.
     *
     * <p>The boundaries are the ones a person actually speaks in: nobody says "97 minutes ago" or
     * "3661 seconds ago". Each unit is used up to (but not including) the point where the next one
     * reads more naturally — 59 minutes, then "1h ago" rather than "60m ago".
     */
    public static String since(String scannedAtIso, long nowMillis) {
        if (scannedAtIso == null) {
            return null;
        }
        Instant scannedAt;
        try {
            scannedAt = Instant.parse(scannedAtIso);
        } catch (DateTimeParseException e) {
            return null;
        }

        long seconds = (nowMillis - scannedAt.toEpochMilli()) / 1000;
        if (seconds < SECONDS_PER_MINUTE) {
            // Covers 0..59s and, just as importantly, every negative value a clock-skewed or
            // future-dated row can produce.
            return "just now";
        }
        if (seconds < SECONDS_PER_HOUR) {
            return (seconds / SECONDS_PER_MINUTE) + "m ago";
        }
        if (seconds < SECONDS_PER_DAY) {
            return (seconds / SECONDS_PER_HOUR) + "h ago";
        }
        return (seconds / SECONDS_PER_DAY) + "d ago";
    }
}
