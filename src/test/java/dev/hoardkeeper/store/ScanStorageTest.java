package dev.hoardkeeper.store;

import com.google.gson.Gson;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.ScannedItem;
import dev.hoardkeeper.model.SessionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanStorageTest {

    private static final Gson GSON = new Gson();

    private static ScannedContainer container(int[] pos, String kind, ScannedItem... items) {
        ScannedContainer c = new ScannedContainer();
        c.pos = pos;
        c.kind = kind;
        c.items = List.of(items);
        return c;
    }

    private static ScannedItem item(String id, int count) {
        ScannedItem i = new ScannedItem();
        i.id = id;
        i.count = count;
        return i;
    }

    @Test
    void slugsAHostAndPort() {
        assertEquals("mc_example_net_25565", ScanStorage.slug("mc.example.net:25565"));
    }

    @Test
    void namesSingleplayerWhenThereIsNoServer() {
        assertEquals("singleplayer", ScanStorage.slug(null));
        assertEquals("singleplayer", ScanStorage.slug(""));
    }

    @Test
    void neutralisesPathTraversal() {
        String s = ScanStorage.slug("../../etc/passwd");
        assertFalse(s.contains("/"));
        assertFalse(s.contains(".."));
        assertFalse(s.isEmpty());
    }

    @Test
    void neverProducesADotDirectory() {
        assertFalse(ScanStorage.slug(".").equals("."));
        assertFalse(ScanStorage.slug("..").equals(".."));
    }

    @Test
    void handlesIpv6Literals() {
        String s = ScanStorage.slug("[::1]:25565");
        assertTrue(s.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void truncatesAbsurdlyLongNames() {
        assertTrue(ScanStorage.slug("a".repeat(500)).length() <= 64);
    }

    @Test
    void buildsTheSessionDirectory() {
        Path p = ScanStorage.sessionDir(Path.of("/game"), "srv", "2026-09-02_14-31-07");
        assertEquals(Path.of("/game/hoardkeeper/srv/2026-09-02_14-31-07"), p);
    }

    // =========================================================================
    // readContainers
    // =========================================================================

    @Test
    void readsThreeValidRowsInFileOrderWithPositionsAndItemsIntact(@TempDir Path dir) throws IOException {
        // Every reader in the mod (export, upload, the measured map) trusts this to hand back
        // every container that was scanned, in the order it was scanned, with what was actually
        // in it -- losing a row, reordering one, or dropping its items would silently ship a wrong
        // report or file a base's contents under the wrong chest.
        Path jsonl = dir.resolve("containers.jsonl");
        ScannedContainer a = container(new int[]{1, 64, 1}, "chest", item("minecraft:diamond", 3));
        ScannedContainer b = container(new int[]{2, 64, 2}, "barrel", item("minecraft:gold_ingot", 5));
        ScannedContainer c = container(new int[]{3, 70, -3}, "shulker_box", item("minecraft:iron_ingot", 7));
        Files.writeString(jsonl, GSON.toJson(a) + "\n" + GSON.toJson(b) + "\n" + GSON.toJson(c) + "\n");

        List<ScannedContainer> rows = ScanStorage.readContainers(jsonl);

        assertEquals(3, rows.size());
        assertArrayEquals(new int[]{1, 64, 1}, rows.get(0).pos);
        assertArrayEquals(new int[]{2, 64, 2}, rows.get(1).pos);
        assertArrayEquals(new int[]{3, 70, -3}, rows.get(2).pos);
        assertEquals("minecraft:diamond", rows.get(0).items.get(0).id);
        assertEquals(3, rows.get(0).items.get(0).count);
        assertEquals("minecraft:gold_ingot", rows.get(1).items.get(0).id);
        assertEquals(5, rows.get(1).items.get(0).count);
        assertEquals("minecraft:iron_ingot", rows.get(2).items.get(0).id);
        assertEquals(7, rows.get(2).items.get(0).count);
    }

    @Test
    void skipsAMalformedMiddleLineAndKeepsTheRest(@TempDir Path dir) throws IOException {
        // Pinning current behaviour: a torn or hand-edited line costs only itself. If this instead
        // stopped at the first bad line, a single corrupted row (e.g. a crash mid-write) would cost
        // the player every container scanned before it, not just the one being written.
        Path jsonl = dir.resolve("containers.jsonl");
        ScannedContainer a = container(new int[]{1, 64, 1}, "chest");
        ScannedContainer c = container(new int[]{3, 64, 3}, "barrel");
        Files.writeString(jsonl, GSON.toJson(a) + "\n{not valid json\n" + GSON.toJson(c) + "\n");

        List<ScannedContainer> rows = ScanStorage.readContainers(jsonl);

        assertEquals(2, rows.size());
        assertArrayEquals(new int[]{1, 64, 1}, rows.get(0).pos);
        assertArrayEquals(new int[]{3, 64, 3}, rows.get(1).pos);
    }

    @Test
    void returnsEmptyForAMissingFile(@TempDir Path dir) {
        // A session that has not written a single container yet (or one whose directory was never
        // created) must read back as "nothing" -- export and upload both call this unconditionally,
        // with no existence check of their own.
        List<ScannedContainer> rows = ScanStorage.readContainers(dir.resolve("containers.jsonl"));
        assertTrue(rows.isEmpty());
    }

    @Test
    void returnsEmptyForAnEmptyFile(@TempDir Path dir) throws IOException {
        // A file that exists but was never written to (e.g. created and then the game crashed
        // before the first container landed) must read back the same as no file at all.
        Path jsonl = dir.resolve("containers.jsonl");
        Files.writeString(jsonl, "");

        assertTrue(ScanStorage.readContainers(jsonl).isEmpty());
    }

    @Test
    void aTrailingNewlineAddsNoPhantomRow(@TempDir Path dir) throws IOException {
        // ContainerJsonlWriter appends a newline after every row, so every real file this ever
        // reads ends this way. Turning that final newline into an extra blank or garbage row would
        // corrupt every session's container count by one.
        Path jsonl = dir.resolve("containers.jsonl");
        ScannedContainer a = container(new int[]{1, 64, 1}, "chest");
        Files.writeString(jsonl, GSON.toJson(a) + "\n");

        assertEquals(1, ScanStorage.readContainers(jsonl).size());
    }

    @Test
    void skipsBlankLinesBetweenRows(@TempDir Path dir) throws IOException {
        // A hand-edited or partially compacted file can carry blank lines between rows; they must
        // be silently skipped rather than logged as malformed or turned into a phantom container.
        Path jsonl = dir.resolve("containers.jsonl");
        ScannedContainer a = container(new int[]{1, 64, 1}, "chest");
        ScannedContainer b = container(new int[]{2, 64, 2}, "barrel");
        Files.writeString(jsonl, GSON.toJson(a) + "\n\n" + GSON.toJson(b) + "\n   \n");

        List<ScannedContainer> rows = ScanStorage.readContainers(jsonl);

        assertEquals(2, rows.size());
        assertArrayEquals(new int[]{1, 64, 1}, rows.get(0).pos);
        assertArrayEquals(new int[]{2, 64, 2}, rows.get(1).pos);
    }

    // =========================================================================
    // readSnapshot
    // =========================================================================

    @Test
    void readsTheFieldsCallersActuallyUse(@TempDir Path dir) throws IOException {
        // MeasuredMapProjector reads exactly these fields off a session it did not start. Dropping
        // or misnaming one would silently exclude a finished scan from the measured map, or file it
        // under the wrong dimension or realm.
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.sessionId = "2026-09-08_10-00-00";
        snapshot.server = "mc.example.com";
        snapshot.realm = "seed:1d333b8d147874e9";
        snapshot.dimension = "minecraft:overworld";
        snapshot.state = "FINISHED";
        snapshot.stats = new SessionSnapshot.Stats();
        snapshot.stats.known = 10;
        snapshot.stats.scanned = 9;
        snapshot.stats.failed = 1;
        snapshot.stats.pending = 0;
        Files.writeString(dir.resolve("session.json"), GSON.toJson(snapshot));

        SessionSnapshot read = ScanStorage.readSnapshot(dir);

        assertEquals("2026-09-08_10-00-00", read.sessionId);
        assertEquals("mc.example.com", read.server);
        assertEquals("seed:1d333b8d147874e9", read.realm);
        assertEquals("minecraft:overworld", read.dimension);
        assertEquals("FINISHED", read.state);
        assertEquals(10, read.stats.known);
        assertEquals(9, read.stats.scanned);
        assertEquals(1, read.stats.failed);
        assertEquals(0, read.stats.pending);
    }

    @Test
    void returnsNullWhenThereIsNoSessionFile(@TempDir Path dir) {
        // A directory with no session.json is not a resumable session at all -- offerResume and
        // the measured-map projector must treat it as "nothing here", not throw.
        assertNull(ScanStorage.readSnapshot(dir));
    }

    @Test
    void returnsNullForAMalformedSessionFile(@TempDir Path dir) throws IOException {
        // Pinning current behaviour: SessionStateStore.load swallows every parse failure and
        // answers "no resumable session". A corrupt snapshot (a crash mid-write that the atomic
        // rename somehow still let through, or a hand edit) must not take a join or a scan-end
        // projection down with it.
        Files.writeString(dir.resolve("session.json"), "{not valid json");

        assertNull(ScanStorage.readSnapshot(dir));
    }
}
