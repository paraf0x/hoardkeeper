package dev.hoardkeeper.gametest.scenario;

import com.google.gson.Gson;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.model.ScanReport;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.ScannedItem;
import dev.hoardkeeper.scan.CandidateCounts;
import dev.hoardkeeper.scan.ContainerStatus;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.scan.ScanSession;
import dev.hoardkeeper.store.ScanStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * A full scan of the base through /hoard start, with the numbers SelfTest.finish used to
 * print, and the one thing the whole design rests on: no screen ever opens while it runs.
 */
public final class ScanScenario implements Scenario {
    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "scan";
    }

    @Override
    public void run(Harness h) throws Exception {
        h.openServer();
        h.setConfig(c -> {
            c.minTicksBetweenOpens = 4;
            c.autoFinishSeconds = 3;
        });

        h.command("hoard start 8");
        h.waitFor("scan running", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        ScanSession session = h.onClient(mc -> ScanController.get().session());

        // One stick a tick while the scan runs: most of those land while the server has one of the
        // scanner's screenless menus open and arrive under its id (see PhantomMenu). Before that
        // was handled, the client lost every one of them for good.
        int[] screensSeen = {0};
        int sticks = 0;
        for (int tick = 0; tick < 60 * Harness.SECOND; tick++) {
            if (h.onClient(mc -> mc.gui.screen() != null)) {
                screensSeen[0]++;
            }
            if (!h.onClient(mc -> ScanController.get().isRunning())) {
                break;
            }
            h.serverCommand("give " + Harness.PLAYER + " minecraft:stick 1");
            sticks++;
            h.ctx().waitTick();
        }
        h.check("scan end", !h.onClient(mc -> ScanController.get().isRunning()), "still running");
        h.screenshot("done");
        int given = sticks;
        try {
            h.waitFor("every stick given during the scan reached the client",
                    mc -> sticksOf(mc) == given, 5 * Harness.SECOND);
        } catch (AssertionError timeout) {
            h.number("sticks given", given);
            h.number("sticks on the client", h.onClient(ScanScenario::sticksOf));
            throw timeout;
        }
        h.checkEquals("sticks given during the scan, on the client", given, h.onClient(ScanScenario::sticksOf));

        int known = h.onClient(mc -> session.candidates.size());
        int scanned = h.onClient(mc -> CandidateCounts.count(session.candidates, ContainerStatus.SCANNED));
        int failed = h.onClient(mc -> CandidateCounts.count(session.candidates, ContainerStatus.FAILED));
        int pending = h.onClient(mc -> CandidateCounts.count(session.candidates, ContainerStatus.PENDING));
        h.checkEquals("known (double chest counts once)", Fixtures.CONTAINERS, known);
        h.checkEquals("scanned", Fixtures.CONTAINERS, scanned);
        h.checkEquals("failed", 0, failed);
        h.checkEquals("pending", 0, pending);
        h.checkEquals("ticks with a screen open during the scan", 0, screensSeen[0]);

        ScanController.ExportResult exported = h.onClient(mc -> ScanController.get().export(mc));
        h.check("export wrote report.json", exported != null && Files.exists(exported.report()),
                exported == null ? "export returned null" : exported.report());
        if (exported != null) {
            ScanReport report = GSON.fromJson(Files.readString(exported.report()), ScanReport.class);
            h.checkEquals("report.containers.scanned", Fixtures.CONTAINERS, report.containers.scanned);
            h.checkEquals("report diamonds", (long) Fixtures.DIAMONDS_TOTAL, total(report, "minecraft:diamond"));

            Path jsonl = exported.report().resolveSibling("containers.jsonl");
            List<ScannedContainer> rows = ScanStorage.readContainers(jsonl);
            long nestedGold = rows.stream()
                    .flatMap(r -> r.items == null ? Stream.<ScannedItem>empty() : r.items.stream())
                    .flatMap(i -> i.contents == null ? Stream.<ScannedItem>empty() : i.contents.stream())
                    .filter(c -> "minecraft:gold_ingot".equals(c.id))
                    .mapToLong(c -> c.count)
                    .sum();
            h.checkEquals("gold inside the shulker box, read without a second round trip",
                    (long) Fixtures.NESTED_GOLD, nestedGold);
        }

        long[] nanos = h.onClient(mc -> new long[]{session.startedNanos, session.lastScanNanos});
        long wallMs = (System.nanoTime() - nanos[0]) / 1_000_000L;
        long activeMs = nanos[1] > 0 ? Math.max(0L, (nanos[1] - nanos[0]) / 1_000_000L) : 0L;
        h.number("known", known);
        h.number("scanned", scanned);
        h.number("failed", failed);
        h.number("pending", pending);
        h.number("wallMs", wallMs);
        h.number("activeMs", activeMs);
        h.number("activePerSec", activeMs > 0 ? Math.round(scanned * 10_000.0 / activeMs) / 10.0 : 0.0);
        h.number("mintick", h.onClient(mc -> HoardkeeperMod.config().minTicksBetweenOpens));
    }

    private static long total(ScanReport report, String id) {
        if (report.totalsByItem == null) {
            return 0L;
        }
        return report.totalsByItem.stream().filter(t -> id.equals(t.id)).mapToLong(t -> t.count).sum();
    }

    private static int sticksOf(net.minecraft.client.Minecraft mc) {
        int n = 0;
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).is(net.minecraft.world.item.Items.STICK)) {
                n += inventory.getItem(slot).getCount();
            }
        }
        return n;
    }
}
