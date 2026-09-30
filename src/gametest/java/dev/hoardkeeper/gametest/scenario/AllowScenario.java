package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.index.StorageIndex;
import dev.hoardkeeper.index.StorageIndexCache;
import dev.hoardkeeper.observe.PassiveObserver;
import dev.hoardkeeper.scan.ScanController;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where the scanner may act — spec 2026-09-30-hoardkeeper-split-design.md §4.
 *
 * <ol>
 *   <li>On a server not in {@code scanServers}, {@code /hoard start} refuses and no scan runs.
 *   <li>There, a container opened by hand is still recorded, and lands in the map where search and
 *       tooltips read — no scan measured the area, so the "inside a scanned area" rule could never
 *       hold (owner's decision, 2026-09-30).
 *   <li>{@code /hoard allow} adds the server; a scan then starts.
 *   <li>{@code /hoard disallow} takes it back.
 * </ol>
 *
 * The harness is a dedicated server reached as localhost; ConfigGuard lists it, so this scenario
 * starts by taking it off the list.
 */
public final class AllowScenario implements Scenario {

    @Override
    public String name() {
        return "allow";
    }

    @Override
    public void run(Harness h) throws Exception {
        h.openServer();
        h.setConfig(c -> {
            c.scanServers = List.of();
            c.passiveObserve = true;
            c.passiveObserveAnywhere = false;
        });

        // ---- 1. Refused. ----
        h.command("hoard start 8");
        h.awaitChatContaining("Scanning is off on", 5 * Harness.SECOND);
        h.check("no scan runs on a server that is not allowed",
                h.stays(mc -> ScanController.get().isRunning(), 2 * Harness.SECOND), "running");

        // ---- 2. Observed by hand anyway. ----
        Path log = h.onClient(PassiveObserver::currentLogContainersFile);
        long base = lines(log);
        h.openAndClose(Fixtures.NEAREST_CHEST);
        h.waitFor("the hand-opened chest is recorded", mc -> lines(log) > base, 10 * Harness.SECOND);
        // ... and not only in the upload log: in the map, where search and tooltips read.
        BlockPos chest = Fixtures.NEAREST_CHEST;
        h.waitFor("the hand-opened chest is in the index", mc -> {
            StorageIndex index = StorageIndexCache.get().current(mc);
            return index != null && index.search().hits("minecraft:diamond").stream()
                    .anyMatch(hit -> hit.covers(chest.getX(), chest.getY(), chest.getZ()));
        }, 10 * Harness.SECOND);

        // ---- 3. Allowed. ----
        h.command("hoard allow");
        h.awaitChatContaining("Scanning allowed on", 5 * Harness.SECOND);
        h.check("localhost is now on the list",
                h.onClient(mc -> HoardkeeperMod.config().scanServers.contains("localhost")),
                h.onClient(mc -> HoardkeeperMod.config().scanServers));
        h.command("hoard start 8");
        h.waitFor("a scan runs once allowed", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        h.command("hoard stop");
        h.waitForScanEnd();

        // ---- 4. Disallowed again. ----
        h.command("hoard disallow");
        h.awaitChatContaining("Scanning is off here again", 5 * Harness.SECOND);
        h.check("the list is empty again", h.onClient(mc -> HoardkeeperMod.config().scanServers.isEmpty()),
                h.onClient(mc -> HoardkeeperMod.config().scanServers));
        // Leave the file as ConfigGuard wrote it for the scenarios after this one.
        h.command("hoard allow");
        h.awaitChatContaining("Scanning allowed on", 5 * Harness.SECOND);
    }

    private static long lines(Path file) {
        try {
            return file == null || !Files.exists(file) ? 0 : Files.readAllLines(file).size();
        } catch (java.io.IOException e) {
            return -1;
        }
    }
}
