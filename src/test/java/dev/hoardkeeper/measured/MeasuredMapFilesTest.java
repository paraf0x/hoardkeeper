package dev.hoardkeeper.measured;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeasuredMapFilesTest {

    private static final Path GAME = Path.of("/game");

    @Test
    void theMapLivesUnderMeasuredKeyedByRealmAndDimension() {
        Path dir = MeasuredMapFiles.dir(GAME, "localhost_25565", "seed_abc", "minecraft_overworld");
        assertEquals(Path.of("/game/hoardkeeper/localhost_25565/measured/seed_abc/minecraft_overworld"),
                dir);
    }

    /**
     * Load bearing, not cosmetic: every session enumerator reads a directory's session.json, so a
     * map named that would be enrolled in resume, export, upload all and retention.
     */
    @Test
    void theStateFileIsNeverNamedSessionJson() {
        Path dir = MeasuredMapFiles.dir(GAME, "s", "r", "d");
        assertEquals("measured.json", MeasuredMapFiles.state(dir).getFileName().toString());
        assertEquals("containers.jsonl", MeasuredMapFiles.containers(dir).getFileName().toString());
    }
}
