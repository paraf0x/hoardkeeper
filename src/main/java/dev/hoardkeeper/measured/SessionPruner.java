package dev.hoardkeeper.measured;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.api.Addons;
import dev.hoardkeeper.model.SessionSnapshot;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.scan.ScanSession;
import dev.hoardkeeper.store.ScanStorage;
import dev.hoardkeeper.store.SessionPruning;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes the finished scan sessions the measured map already holds — spec
 * 2026-09-30-hoardkeeper-split-design.md §7. The rule is {@link SessionPruning}; this reads what it
 * needs off the client and disk. Runs where {@link MeasuredMapProjector#migrate} runs, right after
 * it: on every arrival in a dimension, for that dimension's sessions.
 */
final class SessionPruner {

    private SessionPruner() {
    }

    static void pruneIfDone(Minecraft mc, MeasuredMapStore store, Path sessionDir) {
        SessionSnapshot snapshot = ScanStorage.readSnapshot(sessionDir);
        if (snapshot == null) {
            return;
        }
        if (snapshot.sessionId == null) {
            snapshot.sessionId = String.valueOf(sessionDir.getFileName());
        }
        var config = HoardkeeperMod.config();
        ScanSession live = ScanController.get().session();
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(Math.max(0, config.resumeMaxAgeMinutes)));
        // Cheapest questions first; the add-on is asked last, and only about a session that would go.
        if (!SessionPruning.prunable(snapshot, store.hasProjected(snapshot.sessionId), false,
                config.keepSessions, live == null ? null : live.sessionId, cutoff)) {
            return;
        }
        if (Addons.claimsSession(mc, snapshot)) {
            return;
        }
        if (deleteDirectory(sessionDir)) {
            HoardkeeperMod.LOGGER.info("Removed scan session {}: the measured map holds it",
                    sessionDir.getFileName());
        }
    }

    /**
     * Deletes {@code dir} and the files directly in it — a session directory holds nothing else.
     * Never throws: a session that cannot be deleted is harmless, the map already holds it.
     */
    private static boolean deleteDirectory(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                Files.deleteIfExists(entry);
            }
            Files.delete(dir);
            return true;
        } catch (IOException e) {
            HoardkeeperMod.LOGGER.warn("Could not remove scan session {}", dir, e);
            return false;
        }
    }
}
