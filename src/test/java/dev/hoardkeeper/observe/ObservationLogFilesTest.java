package dev.hoardkeeper.observe;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationLogFilesTest {

    @Test
    void putsALogUnderObservedKeyedByRealmAndDimension() {
        Path dir = ObservationLogFiles.dir(Path.of("/game"), "play_example_com",
                "seed_1d333b8d147874e9", "minecraft_overworld");
        assertEquals(Path.of("/game/hoardkeeper/play_example_com/observed/"
                + "seed_1d333b8d147874e9/minecraft_overworld"), dir);
    }

    @Test
    void anUnknownRealmStillGetsItsOwnDirectoryRatherThanTheServerRoot() {
        Path dir = ObservationLogFiles.dir(Path.of("/game"), "play_example_com",
                "unknown-realm", "minecraft_overworld");
        assertTrue(dir.toString().contains("unknown-realm"));
    }

    @Test
    void theLogIsNotASessionDirectory() {
        // The whole safety property of §4.1: no session.json means every session enumerator
        // skips it. Assert the names so a future rename cannot quietly break that.
        Path dir = ObservationLogFiles.dir(Path.of("/game"), "s", "r", "d");
        assertEquals("containers.jsonl", ObservationLogFiles.containers(dir).getFileName().toString());
        assertEquals("log.json", ObservationLogFiles.state(dir).getFileName().toString());
    }
}
