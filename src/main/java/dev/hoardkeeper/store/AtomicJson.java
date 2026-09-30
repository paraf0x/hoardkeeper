package dev.hoardkeeper.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.hoardkeeper.HoardkeeperMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishes a JSON file by writing a sibling {@code .tmp} file and renaming it over the target, so a
 * crash or power loss mid-write can never leave a half-written file on disk: the target is always
 * either the previous complete file or the new complete one, never a truncated mix of both.
 *
 * <p>Lifted out of {@link SessionStateStore}, whose {@code writeNow}/{@code writeOnce} were the first
 * two callers to need exactly this publication — a resumable session snapshot and, later, other JSON
 * state a background writer thread needs to land on disk without ever taking the thread down with it.
 *
 * <p>{@link #write} takes {@code Object} rather than a specific DTO type because its callers are not
 * all writing the same shape of state, and it is deliberately never allowed to throw: callers run on a
 * writer thread with a queue of further work behind them, so an exception escaping here would take
 * that thread — and everything still queued on it — down with it.
 */
public final class AtomicJson {

    private static final Gson GSON = new GsonBuilder().create();

    private AtomicJson() {
    }

    /**
     * Serialises {@code dto} with Gson and publishes it to {@code file} via a sibling {@code .tmp}
     * file and an atomic rename (falling back to a non-atomic replace if the filesystem doesn't
     * support {@code ATOMIC_MOVE}). Creates {@code file}'s parent directories if needed.
     *
     * <p>Returns whether the write succeeded. Never throws: any failure — an unwritable path, a
     * filesystem error, anything else — is caught, logged at WARN, and reported as {@code false}.
     */
    public static boolean write(Path file, Object dto) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, GSON.toJson(dto));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            HoardkeeperMod.LOGGER.warn("Failed to write JSON file: {}", file, e);
            return false;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // Best-effort cleanup; a leftover .tmp file is harmless and will be overwritten
                // by the next successful write.
            }
        }
    }
}
