package dev.hoardkeeper.gametest;

/** One proof, one client launch. The name is what -Pscenario=<name> selects. */
public interface Scenario {
    String name();

    void run(Harness h) throws Exception;
}
