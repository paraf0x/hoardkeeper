package dev.hoardkeeper.gametest;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.chat.ScanChat;
import dev.hoardkeeper.deposit.DepositService;
import dev.hoardkeeper.hud.ScanHudRenderer;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.observe.PassiveObserver;
import dev.hoardkeeper.realm.RealmKey;
import dev.hoardkeeper.scan.InteractionSender;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.search.SearchService;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Returns every mod singleton, static cache and shared on-disk directory to just-launched state,
 * between scenarios in a chained ({@code -Pscenario=all}) run. Called from {@link GameTestEntry}
 * only, between two scenarios, never before the first and never in single-scenario mode.
 *
 * <p><b>Why this exists.</b> The mod's five client-thread singletons (see each one's own
 * {@code resetForGameTest} / {@code reset}) are built once per JVM, exactly the way the shipped mod
 * runs. Ten scenarios sharing one launch is precisely the thing {@link GameTestEntry}'s own javadoc
 * calls leaky: without a reset, scenario 2 inherits scenario 1's config edits, its half-finished
 * search highlight, its site-check throttle, and — the part that is not obvious from the singletons
 * alone — the exact same on-disk directory. See {@link #wipeSharedDirectory} for why.
 *
 * <p><b>Never reachable from a normal game.</b> Every method this calls is either already public
 * production API ({@code ScanController.reset}, {@code ScanChat.resetFailureThrottle}, {@code
 * ScanHudRenderer.forget}) or a {@code resetForGameTest}-shaped test seam that nothing in
 * {@code src/main} calls. This class itself lives only in the gametest source set, so it cannot
 * ship in the mod jar at all.
 */
final class GameTestReset {
    private GameTestReset() {
    }

    /**
     * Runs on the client thread, like everything else the five singletons touch. Order matters
     * only where noted: the directory wipe runs last, after every background write the scenario
     * that just ended could still have in flight has been drained by the resets above it.
     */
    static void run(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            ScanController.get().reset();
            PassiveObserver.get().resetForGameTest();
            SearchService.get().resetForGameTest();
            DepositService.get().reset();
            DepositService.allowAnyServerForGameTest(false);
            RealmKey.get().onDisconnect();

            // Undoes whatever the scenario that just ended did through Harness.setConfig — that
            // mutates the one live HoardkeeperConfig object in place, so nothing here would ever
            // clear it on its own. Reloading from disk gets back exactly what ConfigGuard wrote
            // before this JVM ever started a scenario, the same object every scenario has always
            // started from in the (unchained) one-launch-per-scenario mode.
            HoardkeeperMod.setConfig(HoardkeeperConfig.load(
                    FabricLoader.getInstance().getConfigDir().resolve(HoardkeeperMod.MOD_ID + ".json")));
            ScanChat.resetFailureThrottle();
            ScanHudRenderer.forget();
            InteractionSender.resetForGameTest();

            // Drains the measured map's own shared writer before the directory holding it is
            // deleted below. ScanController.reset() does not do this itself -- only stop() blocks on
            // a projection, and a scenario that leaves a scan running when its Harness closes (see
            // ScanController.onDisconnect) never gets to project at all -- so nothing above already
            // guarantees this queue is empty.
            MeasuredMapStore.awaitPendingWritesForTests();

            wipeSharedDirectory();
        });
    }

    /**
     * Deletes {@code <gameDir>/hoardkeeper/} — every scan session, the measured map and the
     * observation log for every server, realm and dimension this client has ever seen.
     *
     * <p><b>Measured, not assumed.</b> The mod keys all three of those under
     * {@code <serverSlug>/}, {@code <serverSlug>/measured/<realmSlug>/<dimensionSlug>/} and
     * {@code <serverSlug>/observed/<realmSlug>/<dimensionSlug>/} (see {@code ScanStorage.slug},
     * {@code MeasuredMapFiles} and {@code ObservationLogFiles}). Disassembling the exact
     * fabric-client-gametest-api build this project pins shows both slugs are identical for every
     * scenario in one chained run:
     * <ul>
     *   <li>{@code TestWorldBuilderImpl}'s default "consistent settings" (never overridden by
     *       {@link Harness#openServer}) call {@code WorldCreationUiState.setSeed("1")} — the same
     *       literal seed for every scenario's dedicated server, so {@code RealmKey}'s hashed-seed
     *       value never changes.</li>
     *   <li>{@code TestDedicatedServerContextImpl.getConnectionAddress()} returns
     *       {@code "localhost:" + server.getPort()}, and {@code DedicatedServerImplUtil} never sets
     *       {@code server-port} in the properties it writes — so every scenario's in-process server
     *       binds vanilla's default port, and {@code ScanController.serverAddress} (which reads
     *       {@code mc.player.connection.getServerData().ip}, non-null here — the connection is a
     *       real {@code ServerData}, not the "singleplayer" fallback) returns the exact same string
     *       every time.</li>
     * </ul>
     * With the dimension always {@code minecraft:overworld} too, every scenario resolves to the
     * same {@code hoardkeeper/<slug>/} tree. Without this wipe, a later scenario's scan
     * projects onto a measured map (and, if it right-clicks a container, an observation log) that
     * an earlier scenario already wrote rows into.
     */
    private static void wipeSharedDirectory() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("hoardkeeper");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(GameTestReset::deleteQuietly);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not clear " + dir + " between chained scenarios", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + path + " between chained scenarios", e);
        }
    }
}
