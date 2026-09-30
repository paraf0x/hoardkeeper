package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.search.SearchService;

/**
 * The search lights eight containers; opening one of them by hand -- a real right-click through
 * MouseHandler, so UseBlockCallback, the server, the screen mixin and SearchService all meet --
 * puts its box out.
 */
public final class SearchScenario implements Scenario {
    @Override
    public String name() {
        return "search";
    }

    @Override
    public void run(Harness h) throws Exception {
        scanTheBase(h);

        h.command("hoard search diamond");
        h.waitFor("eight containers lit", mc -> lit(mc) == Fixtures.WITH_DIAMONDS, 10 * Harness.SECOND);
        h.checkEquals("lit after search", Fixtures.WITH_DIAMONDS, h.onClient(SearchScenario::lit));
        h.screenshot("lit");

        h.openAndClose(Fixtures.NEAREST_CHEST);
        h.waitFor("one box dismissed", mc -> lit(mc) == Fixtures.WITH_DIAMONDS - 1, 5 * Harness.SECOND);
        h.checkEquals("lit after opening one by hand", Fixtures.WITH_DIAMONDS - 1, h.onClient(SearchScenario::lit));
        h.screenshot("dismissed");
    }

    static void scanTheBase(Harness h) {
        h.openServer();
        h.setConfig(c -> {
            c.minTicksBetweenOpens = 4;
            c.autoFinishSeconds = 3;
            c.searchHighlightSeconds = 120;   // the default 30 would expire mid-scenario
        });
        h.command("hoard start 8");
        h.waitFor("scan running", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        h.waitForScanEnd();
        MeasuredMapStore.awaitPendingWritesForTests();
    }

    static int lit(net.minecraft.client.Minecraft mc) {
        return SearchService.get().lit().size();
    }
}
