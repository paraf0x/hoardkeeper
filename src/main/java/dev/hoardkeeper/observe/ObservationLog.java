package dev.hoardkeeper.observe;

import dev.hoardkeeper.model.ScannedContainer;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arithmetic of an append-only observation log: how many rows still owe an upload, when the
 * file may be rewritten to drop superseded rows, and where the upload watermark lands afterwards.
 * Design spec §4.3 and §4.4. No Minecraft imports and no file handling here — this class takes
 * lists and ints and returns lists and ints; the file itself is a later task's problem.
 *
 * <p><b>The watermark is a row count, not a timestamp.</b> {@code uploadedRows} means "this many
 * rows from the start of the file are known to have reached the server". A clock that jumps
 * backwards would strand rows forever under a timestamp watermark; a count cannot, because it
 * only ever moves by the number of rows actually confirmed.
 *
 * <p><b>Compaction may only run when the watermark sits at one of its two ends</b> — everything
 * sent ({@code uploadedRows == totalRows}) or nothing sent ({@code uploadedRows == 0}). In
 * between, rewriting the file would reorder rows under a count, leaving the watermark pointing at
 * rows that were never uploaded. That is why {@link #mayCompact} is a separate question from
 * "would compaction help".
 */
public final class ObservationLog {

    private ObservationLog() {
    }

    /**
     * How many rows past the watermark still owe an upload. Never negative: a watermark left
     * pointing past the end of a truncated file (a stale {@code log.json} next to a shorter log)
     * clamps to zero rather than going negative.
     */
    public static int pendingRows(int totalRows, int uploadedRows) {
        return Math.max(0, totalRows - uploadedRows);
    }

    /**
     * How many whole seconds ago the last observation was, or {@code -1} when there has not been
     * one since this client started ({@code lastMillis == 0}) — the status line's clock, spec §7.
     *
     * <p>The negative answer is load bearing. {@code PassiveObserver} stamps a separate clock on
     * every join with {@code passiveObserve} on, purely to arm the upload debounce (spec §5.1),
     * and a join is not an observation; the line must therefore be able to say "nothing observed
     * yet this session" rather than "last 0s ago", which would claim something that did not
     * happen.
     *
     * <p>Clamped at zero at the other end: a wall clock that stepped backwards between the
     * observation and the command would otherwise produce a negative age, which this method's own
     * contract reads as "never observed" — silently dropping an observation that did happen.
     */
    public static long secondsSinceLast(long lastMillis, long nowMillis) {
        if (lastMillis == 0) {
            return -1;
        }
        return Math.max(0, (nowMillis - lastMillis) / 1000);
    }

    /**
     * Whether the file may be compacted right now: only when nothing is in flight and the
     * watermark sits at one of its two ends (everything sent, or nothing sent). Anywhere in
     * between, rewriting the file would reorder rows under a count that has not moved to match,
     * leaving it pointing at the wrong rows.
     */
    public static boolean mayCompact(int totalRows, int uploadedRows, boolean uploadInFlight) {
        if (uploadInFlight) {
            return false;
        }
        return uploadedRows == totalRows || uploadedRows == 0;
    }

    /**
     * Keeps the newest row per canonical position, dropping the oldest past {@code maxRows}.
     * Identity is {@code pos} alone — {@code secondaryPos} is ignored, exactly as {@code
     * PositionIndex} does. A row whose {@code pos} is {@code null} is dropped outright: it has no
     * canonical position to key on.
     *
     * <p>Input order is otherwise preserved among the rows kept, where a position's order is that
     * of whichever physical row survives for it — its newest occurrence, not its first. A
     * rescanned container is freshly relevant again; treating it as "oldest" because it first
     * appeared early in the file would let the cap below drop a position that was just touched in
     * favour of one that has not been seen since.
     *
     * <p>"Newest" compares {@code scannedAt} as an ISO instant; an unparseable value counts as
     * older than every parseable one, so a corrupt row never wins over a good one at the same
     * position.
     */
    public static List<ScannedContainer> compact(List<ScannedContainer> rows, int maxRows) {
        Map<PosKey, ScannedContainer> newestByPos = new LinkedHashMap<>();
        for (ScannedContainer row : rows) {
            if (row.pos == null) {
                continue;
            }
            PosKey key = new PosKey(row.pos);
            ScannedContainer existing = newestByPos.get(key);
            if (existing == null) {
                newestByPos.put(key, row);
            } else if (isNewer(row, existing)) {
                // Move the position to the end: a rescanned container is freshly relevant again,
                // so it must not be treated as stale just because its first occurrence was early.
                newestByPos.remove(key);
                newestByPos.put(key, row);
            }
        }

        List<ScannedContainer> kept = new ArrayList<>(newestByPos.values());
        if (kept.size() > maxRows) {
            kept = kept.subList(kept.size() - maxRows, kept.size());
        }
        return kept;
    }

    /** Where the watermark lands after compaction: it follows whichever end it was at. */
    public static int watermarkAfterCompaction(int uploadedRowsBefore, int keptRows) {
        return uploadedRowsBefore == 0 ? 0 : keptRows;
    }

    private static boolean isNewer(ScannedContainer candidate, ScannedContainer current) {
        return parseInstant(candidate.scannedAt).compareTo(parseInstant(current.scannedAt)) > 0;
    }

    /** An unparseable {@code scannedAt} sorts as older than every parseable one. */
    private static Instant parseInstant(String scannedAt) {
        if (scannedAt == null) {
            return Instant.MIN;
        }
        try {
            return Instant.parse(scannedAt);
        } catch (DateTimeParseException e) {
            return Instant.MIN;
        }
    }

    /** Identity for compaction: {@code pos} alone, compared by value rather than array reference. */
    private record PosKey(int x, int y, int z) {
        PosKey(int[] pos) {
            this(pos[0], pos[1], pos[2]);
        }
    }
}
