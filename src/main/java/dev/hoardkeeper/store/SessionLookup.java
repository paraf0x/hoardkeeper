package dev.hoardkeeper.store;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.model.SessionSnapshot;
import dev.hoardkeeper.realm.RealmMatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Finds "the session on disk we should continue or export": the directory walk under
 * {@code <gameDir>/hoardkeeper/<serverSlug>/}, the {@code session.json} read, and the
 * newest-wins comparison, plus the two filters {@code dev.hoardkeeper.scan.ScanController} uses
 * to decide which sessions qualify. Pure — no Minecraft classes, no client — so it can be tested
 * without relogging into a real game.
 */
public final class SessionLookup {

    private SessionLookup() {
    }

    /** One session directory and the snapshot read out of it. */
    public record Found(Path dir, SessionSnapshot snapshot) {
    }

    /**
     * Scans {@code serverDir} for session directories whose {@code session.json} passes
     * {@code filter}, and returns the one with the newest {@code updatedAt}. Never throws: a
     * missing or unreadable directory simply means "nothing".
     */
    public static Found newest(Path serverDir, Predicate<SessionSnapshot> filter) {
        if (!Files.isDirectory(serverDir)) {
            return null;
        }

        Found best = null;
        Instant bestUpdated = null;
        try (Stream<Path> dirs = Files.list(serverDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                SessionSnapshot snapshot = ScanStorage.readSnapshot(dir);
                if (snapshot == null || !filter.test(snapshot)) {
                    continue;
                }
                Instant updated = SessionSnapshotCodec.parseInstant(snapshot.updatedAt);
                if (best == null || SessionSnapshotCodec.isAfter(updated, bestUpdated)) {
                    best = new Found(dir, snapshot);
                    bestUpdated = updated;
                }
            }
        } catch (IOException e) {
            HoardkeeperMod.LOGGER.warn("Failed to list scan sessions in {}", serverDir, e);
            return null;
        }
        return best;
    }

    /**
     * The newest session for this server and dimension that is worth continuing: not
     * {@code FINISHED}, updated within {@code resumeMaxAgeMinutes}, and with at least one pending
     * container left to scan. The filter {@code offerResume} and {@code resume} use.
     */
    public static Predicate<SessionSnapshot> resumable(String dimension, String realm, Instant cutoff) {
        // The realm check is not redundant with the dimension check: behind a proxy every backend
        // has a minecraft:overworld and one shared address, so the dimension decides nothing. See
        // RealmMatch for why an unknown realm on either side still matches.
        return snapshot -> !"FINISHED".equals(snapshot.state)
                && dimension.equals(snapshot.dimension)
                && RealmMatch.resumable(realm, snapshot.realm)
                && snapshot.stats != null && snapshot.stats.pending > 0
                && SessionSnapshotCodec.isAfter(SessionSnapshotCodec.parseInstant(snapshot.updatedAt), cutoff);
    }

    /** The filter {@code export} falls back to when nothing is in memory. */
    public static Predicate<SessionSnapshot> exportable(String dimension) {
        return snapshot -> dimension.equals(snapshot.dimension);
    }
}
