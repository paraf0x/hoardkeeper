package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.sounds.SoundSource;

/** The pipeline itself: mod loaded, server up, client joined, command registered, JVM exits. */
public final class BootScenario implements Scenario {
    @Override
    public String name() {
        return "boot";
    }

    @Override
    public void run(Harness h) {
        h.check("mod loaded", FabricLoader.getInstance().isModLoaded("hoardkeeper"), "hoardkeeper");
        h.check("no add-on in the core's own run",
                FabricLoader.getInstance().getEntrypointContainers("hoardkeeper:addon", Object.class).isEmpty(),
                "hoardkeeper:addon");
        h.check("gametest api loaded",
                FabricLoader.getInstance().isModLoaded("fabric-client-gametest-api-v1"), "6.x");
        h.check("config lets the scanner act on the test server",
                HoardkeeperMod.config().scanServers.contains("localhost"), HoardkeeperMod.config().scanServers);

        h.openServer();

        h.checkEquals("master volume during the scenario", 0.0,
                h.onClient(mc -> (double) mc.options.getSoundSourceVolume(SoundSource.MASTER)));
        h.check("pauseOnLostFocus off during the scenario", !h.onClient(mc -> mc.options.pauseOnLostFocus),
                "options.pauseOnLostFocus");

        boolean registered = h.onClient(mc ->
                mc.player.connection.getCommands().getRoot().getChild("hoard") != null);
        h.check("hoard in the client dispatcher after join", registered, "getCommands().getRoot()");
        h.screenshot("joined");
    }
}
