package dev.hoardkeeper.store;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.SessionSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes the on-disk layout for scan output: {@code <gameDir>/hoardkeeper/<serverSlug>/<sessionId>/},
 * and reads the two files that live in it back.
 *
 * <p>{@link #slug} is a security boundary, not cosmetics. The server address is attacker-controlled
 * input (a player can join any server, or type any address into the "direct connect" screen) and it
 * becomes a directory name on disk. Every character outside the safe set {@code [A-Za-z0-9_-]} is
 * replaced, the result is capped at 64 characters so a malicious or absurd address cannot build an
 * unbounded path, and a result that is blank, {@code "."} or {@code ".."} is rejected in favour of a
 * fixed fallback so it can never resolve to the parent directory or something indistinguishable from
 * "no subdirectory at all".
 */
public final class ScanStorage {

    private static final String FALLBACK_SLUG = "singleplayer";
    private static final int MAX_SLUG_LENGTH = 64;

    /** Reads {@code containers.jsonl} back; matches the compact form {@code ContainerJsonlWriter} writes. */
    private static final Gson GSON = new Gson();

    private ScanStorage() {
    }

    /**
     * Turns a server address (or {@code null}/blank for singleplayer) into a safe directory name.
     */
    public static String slug(String serverAddressOrNull) {
        if (serverAddressOrNull == null || serverAddressOrNull.isEmpty()) {
            return FALLBACK_SLUG;
        }

        String sanitised = serverAddressOrNull.replaceAll("[^A-Za-z0-9_-]", "_");
        if (sanitised.isBlank() || sanitised.equals(".") || sanitised.equals("..")) {
            return FALLBACK_SLUG;
        }

        if (sanitised.length() > MAX_SLUG_LENGTH) {
            sanitised = sanitised.substring(0, MAX_SLUG_LENGTH);
        }
        return sanitised;
    }

    /**
     * The directory holding every scan session recorded against one server. Its children are the
     * per-session directories {@link #sessionDir} builds — which is how resume and export find an
     * earlier session without having been told its id.
     */
    public static Path serverDir(Path gameDir, String serverSlug) {
        return gameDir.resolve("hoardkeeper").resolve(serverSlug);
    }

    /**
     * The directory a single scan session writes its output into.
     */
    public static Path sessionDir(Path gameDir, String serverSlug, String sessionId) {
        return serverDir(gameDir, serverSlug).resolve(sessionId);
    }

    /**
     * Reads a {@code containers.jsonl} file. A malformed line is skipped and logged rather than
     * failing the whole read: the bulk file is append-only and a torn last line after a crash must
     * not cost the player every container before it.
     *
     * <p>Used by {@code upload.SessionUploader}, which reads the very same files (including one
     * still being appended to by a live scan) and must tolerate a torn line exactly as export
     * already does — a stricter parser here would fail an upload on a line export would happily
     * skip.
     */
    public static List<ScannedContainer> readContainers(Path jsonl) {
        List<ScannedContainer> containers = new ArrayList<>();
        if (!Files.isRegularFile(jsonl)) {
            return containers;
        }
        int malformed = 0;
        try (BufferedReader reader = Files.newBufferedReader(jsonl)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    ScannedContainer container = GSON.fromJson(line, ScannedContainer.class);
                    if (container != null) {
                        containers.add(container);
                    }
                } catch (JsonParseException e) {
                    malformed++;
                }
            }
        } catch (IOException e) {
            HoardkeeperMod.LOGGER.error("Failed to read {}", jsonl, e);
            return containers;
        }
        if (malformed > 0) {
            HoardkeeperMod.LOGGER.warn("Skipped {} malformed line(s) in {}", malformed, jsonl);
        }
        return containers;
    }

    /**
     * Narrow accessor for {@code measured.MeasuredMapProjector}, which has to read a session it did
     * not start. Delegates to the loader the resume path already uses rather than parsing a second
     * time, so the two can never drift apart. Answers {@code null} for anything unreadable — a
     * session that cannot be parsed is one the map simply does not fold in.
     */
    public static SessionSnapshot readSnapshot(Path sessionDir) {
        return SessionStateStore.load(sessionDir.resolve("session.json")).orElse(null);
    }
}
