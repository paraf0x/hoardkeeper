package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.measured.MeasuredMapProjector;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.scan.ScanController;

/** A corner rescan does not cost the map the rest, and the search still finds outside the corner. */
public final class MeasuredScenario implements Scenario {
    @Override
    public String name() {
        return "measured";
    }

    @Override
    public void run(Harness h) throws Exception {
        h.openServer();
        h.setConfig(c -> {
            c.minTicksBetweenOpens = 4;
            c.autoFinishSeconds = 3;
            c.searchHighlightSeconds = 120;
        });

        h.command("hoard start 8");
        h.waitFor("first scan running", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        h.waitForScanEnd();
        MeasuredMapStore.awaitPendingWritesForTests();
        int before = h.onClient(mc -> MeasuredMapProjector.storeFor(mc).rows().size());
        h.checkEquals("map rows after the first scan", Fixtures.CONTAINERS, before);

        h.teleport(Fixtures.CORNER);
        h.command("hoard start 2");
        h.waitFor("corner scan running", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        h.waitForScanEnd();
        MeasuredMapStore.awaitPendingWritesForTests();
        int after = h.onClient(mc -> MeasuredMapProjector.storeFor(mc).rows().size());
        h.check("the map did not shrink", after >= before, before + " -> " + after);

        h.command("hoard search diamond");
        h.waitFor("eight lit after the corner rescan", mc -> SearchScenario.lit(mc) == Fixtures.WITH_DIAMONDS,
                10 * Harness.SECOND);
        h.checkEquals("lit after the corner rescan", Fixtures.WITH_DIAMONDS, h.onClient(SearchScenario::lit));
        h.screenshot("after-corner-rescan");
        h.number("rowsBefore", before);
        h.number("rowsAfter", after);
    }
}
