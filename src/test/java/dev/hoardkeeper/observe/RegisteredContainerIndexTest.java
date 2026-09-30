package dev.hoardkeeper.observe;

import dev.hoardkeeper.measured.MeasuredMapFiles;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.scan.ScanArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading the registered containers off the measured map, and not re-reading what has not changed. */
class RegisteredContainerIndexTest {

    private static String row(int x, int y, int z) {
        return "{\"pos\":[" + x + "," + y + "," + z + "],\"kind\":\"chest\",\"slotCount\":27}";
    }

    private static ScannedContainer container(int x, int y, int z) {
        ScannedContainer c = new ScannedContainer();
        c.pos = new int[]{x, y, z};
        c.kind = "chest";
        c.slotCount = 27;
        c.scannedAt = "2026-09-08T10:00:00Z";
        return c;
    }

    private static Path map(Path root, String... rows) throws IOException {
        Path file = root.resolve("containers.jsonl");
        Files.writeString(file, String.join("\n", rows) + "\n");
        return file;
    }

    @Test
    void everyContainerInTheMapIsRegistered(@TempDir Path root) throws IOException {
        Set<Long> known = new RegisteredContainerIndex().positions(map(root, row(1, 2, 3), row(4, 5, 6)));
        assertTrue(RegisteredContainers.holds(known, new int[]{1, 2, 3}));
        assertTrue(RegisteredContainers.holds(known, new int[]{4, 5, 6}));
        assertFalse(RegisteredContainers.holds(known, new int[]{1, 2, 4}));
    }

    @Test
    void aMissingMapRegistersNothing(@TempDir Path root) {
        assertTrue(new RegisteredContainerIndex().positions(root.resolve("containers.jsonl")).isEmpty());
    }

    @Test
    void aRewrittenMapIsReadAgain(@TempDir Path root) throws IOException {
        Path file = map(root, row(1, 2, 3));
        RegisteredContainerIndex index = new RegisteredContainerIndex();
        assertFalse(RegisteredContainers.holds(index.positions(file), new int[]{9, 9, 9}));

        Files.writeString(file, row(1, 2, 3) + "\n" + row(9, 9, 9) + "\n");
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertTrue(RegisteredContainers.holds(index.positions(file), new int[]{9, 9, 9}));
    }

    /**
     * The cost this cache exists to avoid: every observation appends to the very file the cache is
     * keyed on, so without an answer for "that append was mine" each chest closed inside a storage
     * would throw away the parse the next one needs and re-read the whole map on the client thread.
     *
     * <p>Observed through a row the map does not hold — which the append could never legitimately
     * introduce (spec §4.2) — because a set that is right either way cannot tell a kept parse from a
     * fresh one.
     */
    @Test
    void anAppendThisClientMadeItselfDoesNotDiscardTheParse(@TempDir Path root) {
        MeasuredMapStore store = new MeasuredMapStore(root);
        store.projectScan(List.of(container(1, 2, 3)), ScanArea.ofRadius(0, 0, 16), true, "s1",
                "localhost:25565", null, "minecraft:overworld");
        Path file = MeasuredMapFiles.containers(root);

        RegisteredContainerIndex index = new RegisteredContainerIndex();
        assertTrue(RegisteredContainers.holds(index.positions(file), new int[]{1, 2, 3}));

        store.appendContentUpdate(container(9, 9, 9));
        MeasuredMapStore.awaitPendingWritesForTests();

        assertFalse(RegisteredContainers.holds(index.positions(file), new int[]{9, 9, 9}),
                "the cached parse was kept, so the append cost no re-read");
    }

    /** And a position handed over with the append is in the answer from that moment on. */
    @Test
    void aProjectedObservationJoinsTheCachedSet(@TempDir Path root) throws IOException {
        Path file = map(root, row(1, 2, 3));
        RegisteredContainerIndex index = new RegisteredContainerIndex();
        index.positions(file);

        index.projected(file, new int[]{7, 8, 9});

        assertTrue(RegisteredContainers.holds(index.positions(file), new int[]{7, 8, 9}));
    }

    @Test
    void aProjectedObservationForAFileNothingHasReadIsHarmless(@TempDir Path root) {
        RegisteredContainerIndex index = new RegisteredContainerIndex();
        Path file = root.resolve("containers.jsonl");
        index.projected(file, new int[]{7, 8, 9});
        assertTrue(index.positions(file).isEmpty());
    }

    @Test
    void aCorruptLineCostsOnlyItself(@TempDir Path root) throws IOException {
        Set<Long> known = new RegisteredContainerIndex()
                .positions(map(root, row(1, 2, 3), "{not json", row(4, 5, 6)));
        assertTrue(RegisteredContainers.holds(known, new int[]{1, 2, 3}));
        assertTrue(RegisteredContainers.holds(known, new int[]{4, 5, 6}));
    }
}
