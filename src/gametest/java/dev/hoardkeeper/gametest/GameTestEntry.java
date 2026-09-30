package dev.hoardkeeper.gametest;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.gametest.scenario.AllowScenario;
import dev.hoardkeeper.gametest.scenario.BootScenario;
import dev.hoardkeeper.gametest.scenario.DepositScenario;
import dev.hoardkeeper.gametest.scenario.HarnessFailScenario;
import dev.hoardkeeper.gametest.scenario.MeasuredScenario;
import dev.hoardkeeper.gametest.scenario.NudgeScenario;
import dev.hoardkeeper.gametest.scenario.PassiveScenario;
import dev.hoardkeeper.gametest.scenario.PeekScenario;
import dev.hoardkeeper.gametest.scenario.ResumeScenario;
import dev.hoardkeeper.gametest.scenario.RetryScenario;
import dev.hoardkeeper.gametest.scenario.ScanScenario;
import dev.hoardkeeper.gametest.scenario.SearchKeyScenario;
import dev.hoardkeeper.gametest.scenario.SearchScenario;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The fabric-client-gametest entrypoint. Reads -Dhoardkeeper.gametest.scenario (set by
 * build.gradle from -Pscenario) and runs that scenario; "all" runs every scenario in ALL in one
 * JVM instead of ten. That used to be leaky -- the mod's five singletons and a handful of static
 * fields carry state from one scenario into the next, and they all resolve to the very same
 * on-disk directory besides -- which is why {@link GameTestReset} runs between each pair of
 * scenarios here, and only here: never before the first (nothing to inherit yet) and never in
 * single-scenario mode (nothing after it to protect). Still: a hang or a crash in this mode takes
 * every scenario queued after it, since nothing catches a thrown AssertionError between
 * iterations -- see scripts/gametest.py's default, which stays one launch per scenario for that
 * reason and is what evidence runs use.
 */
public final class GameTestEntry implements FabricClientGameTest {
    public static final String SCENARIO_PROPERTY = "hoardkeeper.gametest.scenario";

    /** In the order of the design's §6.2. Grows with every task. */
    static final List<Scenario> ALL = List.of(
            new BootScenario(),
            new ScanScenario(),
            new SearchScenario(),
            new SearchKeyScenario(),
            new RetryScenario(),
            new ResumeScenario(),
            new PassiveScenario(),
            new MeasuredScenario(),
            new PeekScenario(),
            new NudgeScenario(),
            new AllowScenario(),
            // Last, while it is new: in a chained run a hang here costs no other scenario.
            new DepositScenario());

    /** Runnable by name, never part of "all". */
    static final List<Scenario> HIDDEN = List.of(
            new HarnessFailScenario());

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String wanted = System.getProperty(SCENARIO_PROPERTY, "all");
        List<Scenario> selected = select(wanted);
        boolean chained = selected.size() > 1;
        HoardkeeperMod.LOGGER.warn("GAMETEST selected={} chained={}", names(selected), chained);
        for (int i = 0; i < selected.size(); i++) {
            // Never before the first scenario -- there is nothing yet for it to inherit -- and
            // never at all outside a chained run, where GameTestReset would just be extra work on
            // a JVM that is about to exit anyway.
            if (chained && i > 0) {
                GameTestReset.run(ctx);
            }
            Scenario scenario = selected.get(i);
            Harness h = new Harness(ctx, scenario.name(), chained);
            try {
                scenario.run(h);
            } catch (Throwable t) {
                h.fail(t);
            } finally {
                h.close();
            }
        }
    }

    static List<Scenario> select(String wanted) {
        if ("all".equals(wanted)) {
            return ALL;
        }
        return Stream.concat(ALL.stream(), HIDDEN.stream())
                .filter(s -> s.name().equals(wanted))
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException("unknown scenario '" + wanted
                        + "'; known: " + names(ALL) + " (hidden: " + names(HIDDEN) + ")"));
    }

    static String names(List<Scenario> scenarios) {
        return scenarios.stream().map(Scenario::name).collect(Collectors.joining(","));
    }
}
