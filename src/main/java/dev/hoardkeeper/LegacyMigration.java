package dev.hoardkeeper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Carries a storage-scanner install over to Hoardkeeper, once — split spec §3. Hoardkeeper is what
 * storage-scanner became; its players should find their config and everything they measured.
 *
 * <ul>
 *   <li>{@code config/storage-scanner.json} is <em>copied</em> to {@code config/hoardkeeper.json}
 *       when the latter does not exist. Copied, not moved: an add-on may read its own
 *       old fields from the same file. Keys Hoardkeeper does not know are ignored on load.</li>
 *   <li>{@code <game dir>/storage-scanner/} (sessions, the measured map, the observation log) is
 *       <em>moved</em> to {@code <game dir>/hoardkeeper/} when the latter does not exist.</li>
 * </ul>
 *
 * <p>Never throws: a migration that fails leaves the old files where they are and Hoardkeeper
 * starts fresh, which is what a first install looks like anyway.
 */
public final class LegacyMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger("hoardkeeper");

    static final String OLD_CONFIG = "storage-scanner.json";
    static final String NEW_CONFIG = "hoardkeeper.json";
    static final String OLD_DATA = "storage-scanner";
    static final String NEW_DATA = "hoardkeeper";

    private LegacyMigration() {
    }

    public static void run(Path configDir, Path gameDir) {
        Path oldConfig = configDir.resolve(OLD_CONFIG);
        Path newConfig = configDir.resolve(NEW_CONFIG);
        if (Files.isRegularFile(oldConfig) && !Files.exists(newConfig)) {
            try {
                Files.copy(oldConfig, newConfig);
                LOGGER.info("Carried {} over to {}", oldConfig, newConfig);
            } catch (IOException e) {
                LOGGER.warn("Could not carry {} over to {}", oldConfig, newConfig, e);
            }
        }
        Path oldData = gameDir.resolve(OLD_DATA);
        Path newData = gameDir.resolve(NEW_DATA);
        if (Files.isDirectory(oldData) && !Files.exists(newData)) {
            try {
                Files.move(oldData, newData);
                LOGGER.info("Moved {} to {}", oldData, newData);
            } catch (IOException e) {
                LOGGER.warn("Could not move {} to {}", oldData, newData, e);
            }
        }
    }
}
