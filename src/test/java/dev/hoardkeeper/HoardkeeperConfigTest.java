package dev.hoardkeeper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoardkeeperConfigTest {

    @Test
    void writesDefaultsWhenTheFileIsMissing(@TempDir Path dir) {
        Path file = dir.resolve("hoardkeeper.json");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(30, c.autoFinishSeconds,
                "a scan that is never stopped is a scan that is never uploaded");
        assertTrue(c.chunkFrames, "which chunks are missing is the question the HUD count cannot answer");
        assertEquals(1, c.defaultChunkRadius,
                "1 ring = the 3x3 chunks the server's quick-deposit plugin works in");
        assertEquals(48, c.defaultRadius);
        assertEquals(4, c.minTicksBetweenOpens);
        assertTrue(Files.exists(file), "load() must persist the defaults");
    }

    @Test
    void roundTripsChangedValues(@TempDir Path dir) {
        Path file = dir.resolve("hoardkeeper.json");
        HoardkeeperConfig c = HoardkeeperConfig.load(file);
        c.minTicksBetweenOpens = 1;
        c.chatEveryN = 100;
        c.save(file);

        HoardkeeperConfig back = HoardkeeperConfig.load(file);

        assertEquals(1, back.minTicksBetweenOpens);
        assertEquals(100, back.chatEveryN);
    }

    @Test
    void keepsDefaultsForKeysMissingFromAnOlderFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"defaultRadius\": 128}");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(128, c.defaultRadius);
        assertEquals(4, c.minTicksBetweenOpens, "unknown-to-the-file keys keep their default");
    }

    @Test
    void clampsAHandEditedNegativePacingValue(@TempDir Path dir) throws Exception {
        // A negative gap means "send on every tick" to RateLimiter. On a foreign server that is the
        // difference between polite and abusive, and nothing used to stop it.
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"minTicksBetweenOpens\": -5, \"discoverySweepIntervalTicks\": 0,"
                + " \"maxAttemptsPerContainer\": 0, \"openTimeoutTicks\": -1}");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(0, c.minTicksBetweenOpens);
        assertEquals(1, c.discoverySweepIntervalTicks);
        assertEquals(1, c.maxAttemptsPerContainer);
        assertEquals(1, c.openTimeoutTicks);
    }

    @Test
    void clampsRadiusAndKeepsTheDefaultWithinTheMaximum(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"maxRadius\": 32, \"defaultRadius\": 4096}");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(32, c.maxRadius);
        assertEquals(32, c.defaultRadius, "a default radius above the maximum is not a radius");
    }

    @Test
    void neverLetsTheBackoffCapPaceFasterThanTheNormalGate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"minTicksBetweenOpens\": 10, \"maxBackoffTicks\": 2}");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(10, c.maxBackoffTicks, "a backoff that is faster than normal pacing is not a backoff");
    }

    @Test
    void clampsAnOutOfRangeReachMargin(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"reachMargin\": -3.5}");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(0.0, c.reachMargin, 0.0001);
    }

    @Test
    void leavesSaneValuesExactlyAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"minTicksBetweenOpens\": 0, \"defaultRadius\": 96,"
                + " \"reachMargin\": 0.4, \"maxGizmos\": 256}");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(0, c.minTicksBetweenOpens, "0 is legal: the self-test harness measures with it");
        assertEquals(96, c.defaultRadius);
        assertEquals(0.4, c.reachMargin, 0.0001);
        assertEquals(256, c.maxGizmos);
    }

    @Test
    void theScanChimeIsOnByDefaultAndQuiet(@TempDir Path dir) {
        HoardkeeperConfig c = HoardkeeperConfig.load(dir.resolve("hoardkeeper.json"));

        assertTrue(c.scanSoundEnabled, "the chime is the point of the feature — default on");
        assertTrue(c.scanSoundVolume > 0.0f && c.scanSoundVolume <= 0.5f,
                "it fires ~5x a second for minutes; a loud default is why a player switches it off");
        assertEquals(1500, c.scanSoundRunResetMillis);
    }

    @Test
    void clampsAnOutOfRangeChimeVolume(@TempDir Path dir) throws Exception {
        // Above 1.0 Minecraft reads volume as an attenuation distance, so a hand-edited 10.0 would
        // carry the chime across the whole base rather than making it louder.
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"scanSoundVolume\": 10.0, \"scanSoundRunResetMillis\": -20}");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(1.0f, c.scanSoundVolume, 0.0001f);
        assertEquals(0, c.scanSoundRunResetMillis);
    }

    @Test
    void clampsAChunkRadiusAboveItsOwnMaximum(@TempDir Path dir) throws Exception {
        // Same trap as the radius pair: a default above the maximum is not a default, and a 200-ring
        // scan would sweep 160,801 chunks before opening a single container.
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"maxChunkRadius\": 3, \"defaultChunkRadius\": 200}");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(3, c.maxChunkRadius);
        assertEquals(3, c.defaultChunkRadius);
    }

    @Test
    void anAutoFinishOfZeroSurvivesAsSwitchedOff(@TempDir Path dir) throws Exception {
        // 0 is the off switch, and a "must be at least 1" clamp would quietly turn it back on --
        // which for this setting means scans start uploading themselves against the owner's wish.
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"autoFinishSeconds\": 0}");

        assertEquals(0, HoardkeeperConfig.load(file).autoFinishSeconds);
    }

    @Test
    void aChunkRadiusOfZeroSurvivesAsThisChunkOnly(@TempDir Path dir) throws Exception {
        // 0 is a real thing to want -- one storage room -- and the only chunk count that a "must be
        // at least 1" clamp would silently turn into something else.
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"defaultChunkRadius\": 0}");

        assertEquals(0, HoardkeeperConfig.load(file).defaultChunkRadius);
    }

    @Test
    void fallsBackToDefaultsOnCorruptJson(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{ not json at all");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(48, c.defaultRadius);
    }

    @Test
    void aConfigWrittenBeforeThisFeatureBehavesExactlyAsItDidBefore(@TempDir Path dir) throws Exception {
        // Gson only overwrites the fields it finds, so the absent keys keep their in-code
        // defaults -- and those defaults must be the behaviour that file already had.
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{ \"defaultRadius\": 32 }");

        HoardkeeperConfig c = HoardkeeperConfig.load(file);

        assertEquals(32, c.defaultRadius);
    }

    @Test
    void aFlippedFlagSurvivesSaveAndLoad(@TempDir Path dir) throws Exception {
        // The command writes the config back; this is the proof that "off" is still off tomorrow.
        Path file = dir.resolve("hoardkeeper.json");
        HoardkeeperConfig written = new HoardkeeperConfig();
        written.peekEnabled = false;
        written.rescanReminder = false;
        written.save(file);

        HoardkeeperConfig reloaded = HoardkeeperConfig.load(file);
        assertFalse(reloaded.peekEnabled);
        assertFalse(reloaded.rescanReminder);
    }

    @Test
    void saveFlagKeepsAHandEditMadeAfterStartup(@TempDir Path dir) throws Exception {
        // saveFlag must merge into the file as it is on disk, not overwrite it from the in-memory
        // startup snapshot -- without that, a player who hand-edits hoardkeeper.json after the
        // client started loading loses that edit the next time they type /hoard peek off.
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"chatEveryN\": 77, \"peekEnabled\": true}");

        HoardkeeperConfig staleSnapshot = new HoardkeeperConfig(); // stands in for startup's copy
        staleSnapshot.saveFlag(file, "peekEnabled", false);

        HoardkeeperConfig reloaded = HoardkeeperConfig.load(file);
        assertFalse(reloaded.peekEnabled);
        assertEquals(77, reloaded.chatEveryN,
                "a hand edit made after the client started must survive a toggle");
    }

    @Test
    void saveFlagKeepsAKeyThisVersionDoesNotModel(@TempDir Path dir) throws Exception {
        // A JSON key no current field matches must not be dropped on the next toggle -- a plain
        // GSON.toJson(this) rewrite (what save() does) would silently discard it. Checked against
        // the raw JSON, not through load(), since load() has nowhere to put an unmodelled key back.
        Path file = dir.resolve("hoardkeeper.json");
        Files.writeString(file, "{\"peekEnabled\": true, \"aKeyFromANewerVersion\": 42}");

        new HoardkeeperConfig().saveFlag(file, "peekEnabled", false);

        JsonObject raw = new Gson().fromJson(Files.readString(file), JsonObject.class);
        assertTrue(raw.has("aKeyFromANewerVersion"),
                "a key this version does not model must survive a toggle");
        assertEquals(42, raw.get("aKeyFromANewerVersion").getAsInt());
    }

    @Test
    void saveFlagWritesTheKeyItIsToldTo(@TempDir Path dir) throws Exception {
        // The key is a plain string, not a field reference -- a typo here would silently write a
        // key nothing reads while the field it was meant to flip stays wherever it already was.
        Path peekFile = dir.resolve("peek.json");
        HoardkeeperConfig.load(peekFile); // seed a real file on disk, as at startup
        new HoardkeeperConfig().saveFlag(peekFile, "peekEnabled", false);
        assertFalse(HoardkeeperConfig.load(peekFile).peekEnabled);

        Path reminderFile = dir.resolve("reminder.json");
        HoardkeeperConfig.load(reminderFile);
        new HoardkeeperConfig().saveFlag(reminderFile, "rescanReminder", false);
        assertFalse(HoardkeeperConfig.load(reminderFile).rescanReminder);
    }

    @Test
    void saveFlagFallsBackToAFullWriteWhenTheFileIsMissing(@TempDir Path dir) {
        // Nothing on disk to merge into -- saveFlag must fall back to writing this object whole,
        // the same as save() does, rather than leaving the file unwritten or throwing.
        Path file = dir.resolve("hoardkeeper.json");

        new HoardkeeperConfig().saveFlag(file, "peekEnabled", false);

        assertTrue(Files.exists(file));
        assertFalse(HoardkeeperConfig.load(file).peekEnabled);
    }
}
