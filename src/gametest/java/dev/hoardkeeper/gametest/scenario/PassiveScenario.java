package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.observe.PassiveObserver;
import dev.hoardkeeper.scan.ScanController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * What a player opens by hand is recorded only if the map knows the container: a scanned chest
 * (+1), the same chest emptied (+1, an empty row), a shulker box set down after the scan (+0),
 * a chest 4000 blocks from any scanned area (+0).
 */
public final class PassiveScenario implements Scenario {
    @Override
    public String name() {
        return "passive";
    }

    @Override
    public void run(Harness h) throws Exception {
        h.openServer();
        h.setConfig(c -> {
            c.minTicksBetweenOpens = 4;
            c.autoFinishSeconds = 3;
            c.passiveObserve = true;
        });
        h.command("hoard start 8");
        h.waitFor("scan running", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        h.waitForScanEnd();
        MeasuredMapStore.awaitPendingWritesForTests();

        Path log = h.onClient(PassiveObserver::currentLogContainersFile);
        long base = lines(log);
        h.number("rowsAtStart", base);
        // Everything below this line is relative to base, which would still pass even if this
        // log were not actually empty at the start -- a chained run's GameTestReset is the only
        // thing that guarantees it is. Pin the absolute fact once, here, so a regression in that
        // reset (or in the shared-directory wipe it depends on) fails this scenario outright
        // instead of only ever showing up as a quietly different number.
        h.checkEquals("the observation log starts empty in a fresh (or freshly reset) session", 0L, base);

        h.openAndClose(Fixtures.NEAREST_CHEST);
        h.waitFor("one observation row", mc -> lines(log) == base + 1, 10 * Harness.SECOND);
        h.checkEquals("rows after opening a scanned chest", base + 1, lines(log));

        String at = Fixtures.NEAREST_CHEST.getX() + " " + Fixtures.NEAREST_CHEST.getY() + " "
                + Fixtures.NEAREST_CHEST.getZ();
        h.serverCommand("data merge block " + at + " {Items:[]}");
        h.ctx().waitTicks(5);
        h.openAndClose(Fixtures.NEAREST_CHEST);
        h.waitFor("the emptied chest observed", mc -> lines(log) == base + 2, 10 * Harness.SECOND);
        h.checkEquals("rows after opening the emptied chest", base + 2, lines(log));

        h.serverCommand("setblock " + Fixtures.INTRUDER.getX() + " " + Fixtures.INTRUDER.getY() + " "
                + Fixtures.INTRUDER.getZ() + " minecraft:shulker_box");
        h.ctx().waitTicks(5);
        h.openAndClose(Fixtures.INTRUDER);
        boolean intruderIgnored = h.stays(mc -> lines(log) > base + 2, 5 * Harness.SECOND);
        h.check("a shulker the scan never saw is not recorded", intruderIgnored, "rows=" + lines(log));

        h.teleport(Fixtures.FAR);
        h.serverCommand("setblock " + Fixtures.FAR_CHEST.getX() + " " + Fixtures.FAR_CHEST.getY() + " "
                + Fixtures.FAR_CHEST.getZ() + " minecraft:chest[facing=north,type=single]");
        h.serverCommand("item replace block " + Fixtures.FAR_CHEST.getX() + " " + Fixtures.FAR_CHEST.getY()
                + " " + Fixtures.FAR_CHEST.getZ() + " container.0 with minecraft:diamond 5");
        h.ctx().waitTicks(5);
        h.openAndClose(Fixtures.FAR_CHEST);
        boolean farIgnored = h.stays(mc -> lines(log) > base + 2, 5 * Harness.SECOND);
        h.check("a chest outside every scanned area is not recorded", farIgnored, "rows=" + lines(log));
        h.number("rowsAtEnd", lines(log));
    }

    static long lines(Path file) {
        if (file == null || !Files.exists(file)) {
            return 0L;
        }
        try (Stream<String> s = Files.lines(file)) {
            return s.count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
