package dev.hoardkeeper.gametest;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs before any Minecraft class loads and before HoardkeeperMod reads its config: the
 * test server (localhost) is where the scanner may act, and a scan finishes after 3 quiet seconds.
 * The gametest run directory is wiped before each run, so nothing else is there.
 */
public final class ConfigGuard implements PreLaunchEntrypoint {

    static final String SAFE_CONFIG = """
            {
              "autoFinishSeconds": 3,
              "scanServers": ["localhost"]
            }
            """;

    @Override
    public void onPreLaunch() {
        Path config = FabricLoader.getInstance().getConfigDir().resolve("hoardkeeper.json");
        try {
            Files.createDirectories(config.getParent());
            Files.writeString(config, SAFE_CONFIG);
        } catch (IOException e) {
            throw new UncheckedIOException("ConfigGuard could not write " + config, e);
        }
    }
}
