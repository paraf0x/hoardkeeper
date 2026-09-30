package dev.hoardkeeper.store;

import dev.hoardkeeper.model.SessionSnapshot;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Whether a scan session on disk may go — spec 2026-09-30-hoardkeeper-split-design.md §7. Pure, so
 * the rule is tested without a client; {@code measured.SessionPruner} asks it and deletes.
 *
 * <p>Without an uploader nothing ever deleted a session, so they would pile up for good. The map is
 * where search, tooltips and the peek card read, so once the map holds a session, nothing local
 * still needs it — unless an add-on says it does (an upload add-on, until the session has uploaded).
 */
public final class SessionPruning {

    private SessionPruning() {
    }

    /**
     * @param projected   the map for the session's server, realm and dimension carries its id
     * @param claimed     an add-on wants it kept
     * @param keepAll     {@code keepSessions} is on
     * @param liveId      the session the scanner holds in memory right now, or {@code null} —
     *                    {@code status} and {@code export} still read its directory
     * @param cutoff      sessions updated after this stay, so the last scan can still be exported
     *                    from disk for a while ({@code resumeMaxAgeMinutes} ago)
     */
    public static boolean prunable(SessionSnapshot snapshot, boolean projected, boolean claimed,
                                   boolean keepAll, String liveId, Instant cutoff) {
        if (snapshot == null || keepAll || claimed || !projected) {
            return false;
        }
        // A session that is not FINISHED was cut short and may still be resumed; a site scan (or
        // any purpose an add-on sets) is never on the map and is the add-on's business.
        if (!"FINISHED".equals(snapshot.state) || !"storage".equals(snapshot.purpose)) {
            return false;
        }
        if (snapshot.sessionId != null && snapshot.sessionId.equals(liveId)) {
            return false;
        }
        // An unreadable time is kept: nothing says it is old.
        if (snapshot.updatedAt == null || cutoff == null) {
            return false;
        }
        try {
            return !Instant.parse(snapshot.updatedAt).isAfter(cutoff);
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
