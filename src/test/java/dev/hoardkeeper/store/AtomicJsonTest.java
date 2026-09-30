package dev.hoardkeeper.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicJsonTest {
    record Sample(String name, int count) {}

    @TempDir Path dir;

    @Test
    void writesAndReplacesAtomically() throws Exception {
        Path file = dir.resolve("out.json");
        assertTrue(AtomicJson.write(file, new Sample("first", 1)));
        assertTrue(Files.readString(file).contains("first"));

        assertTrue(AtomicJson.write(file, new Sample("second", 2)));
        assertTrue(Files.readString(file).contains("second"));
        assertFalse(Files.exists(dir.resolve("out.json.tmp")), "the temp file must not survive");
    }

    @Test
    void returnsFalseRatherThanThrowingWhenThePathIsUnwritable() {
        // A directory where the file should be: the write cannot succeed and must not blow up
        // on the caller's thread, which is a writer thread with a queue behind it.
        Path file = dir.resolve("blocked");
        assertDoesNotThrow(() -> file.toFile().mkdirs());
        assertFalse(AtomicJson.write(file, new Sample("x", 1)));
    }
}
