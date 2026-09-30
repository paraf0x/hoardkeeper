package dev.hoardkeeper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMigrationTest {

    @TempDir
    Path game;

    private Path config() throws IOException {
        return Files.createDirectories(game.resolve("config"));
    }

    @Test
    void theOldConfigIsCopiedSoTheAddOnStillFindsItsFields() throws IOException {
        Path config = config();
        Files.writeString(config.resolve("storage-scanner.json"), "{\"radius\":12}");
        LegacyMigration.run(config, game);
        assertEquals("{\"radius\":12}", Files.readString(config.resolve("hoardkeeper.json")));
        assertTrue(Files.exists(config.resolve("storage-scanner.json")));
    }

    @Test
    void anExistingConfigIsNeverOverwritten() throws IOException {
        Path config = config();
        Files.writeString(config.resolve("storage-scanner.json"), "old");
        Files.writeString(config.resolve("hoardkeeper.json"), "mine");
        LegacyMigration.run(config, game);
        assertEquals("mine", Files.readString(config.resolve("hoardkeeper.json")));
    }

    @Test
    void theDataDirectoryMovesOnce() throws IOException {
        Path config = config();
        Files.createDirectories(game.resolve("storage-scanner/localhost/measured"));
        Files.writeString(game.resolve("storage-scanner/localhost/measured/state.json"), "{}");
        LegacyMigration.run(config, game);
        assertTrue(Files.exists(game.resolve("hoardkeeper/localhost/measured/state.json")));
        assertFalse(Files.exists(game.resolve("storage-scanner")));
    }

    @Test
    void existingDataIsNeverReplaced() throws IOException {
        Path config = config();
        Files.createDirectories(game.resolve("storage-scanner/a"));
        Files.createDirectories(game.resolve("hoardkeeper/b"));
        LegacyMigration.run(config, game);
        assertTrue(Files.exists(game.resolve("storage-scanner/a")));
        assertFalse(Files.exists(game.resolve("hoardkeeper/a")));
    }

    @Test
    void aFreshInstallHasNothingToDo() throws IOException {
        Path config = config();
        LegacyMigration.run(config, game);
        assertFalse(Files.exists(config.resolve("hoardkeeper.json")));
        assertFalse(Files.exists(game.resolve("hoardkeeper")));
    }
}
