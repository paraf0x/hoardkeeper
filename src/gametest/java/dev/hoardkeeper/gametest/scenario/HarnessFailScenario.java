package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;

/** Always fails. Proves a failing check reaches the Gradle exit code. Hidden from "all". */
public final class HarnessFailScenario implements Scenario {
    @Override
    public String name() {
        return "harness-fail";
    }

    @Override
    public void run(Harness h) {
        h.check("this check always fails", false, "harness-fail exists to prove failures propagate");
    }
}
