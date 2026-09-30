package dev.hoardkeeper.store;

import dev.hoardkeeper.model.ScannedContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContainerJsonlWriterTest {

    private static ScannedContainer container(String kind) {
        ScannedContainer c = new ScannedContainer();
        c.kind = kind;
        c.pos = new int[]{1, 2, 3};
        c.items = List.of();
        return c;
    }

    @Test
    void writesOneLinePerContainer(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("containers.jsonl");
        ContainerJsonlWriter w = new ContainerJsonlWriter(file);
        w.append(container("barrel"));
        w.append(container("chest_double"));
        w.close();

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        for (String line : lines) {
            assertFalse(line.isBlank());
            assertFalse(line.contains("\n"));
        }
    }

    @Test
    void appendsRatherThanRewriting(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("containers.jsonl");
        ContainerJsonlWriter first = new ContainerJsonlWriter(file);
        first.append(container("barrel"));
        first.close();

        ContainerJsonlWriter second = new ContainerJsonlWriter(file);
        second.append(container("barrel"));
        second.close();

        assertEquals(2, Files.readAllLines(file).size());
    }

    @Test
    void createsMissingParentDirectories(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a/b/c/containers.jsonl");
        ContainerJsonlWriter w = new ContainerJsonlWriter(file);
        w.append(container("barrel"));
        w.close();

        assertEquals(1, Files.readAllLines(file).size());
    }
}
