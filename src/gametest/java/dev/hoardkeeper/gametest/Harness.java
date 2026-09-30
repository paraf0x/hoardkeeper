package dev.hoardkeeper.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.scan.ScanController;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * One scenario's world. Owns the in-process dedicated server, the client's connection to it,
 * the config the scenario installs, the checks it records and the result.json it leaves.
 *
 * <p>Checks do not throw where they are made: they are recorded, so a scenario runs to its end
 * and result.json shows every check. {@link #close()} throws once if anything failed, and that
 * is what fails the Gradle task.
 */
public final class Harness {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    /** Loom passes --username Tester (fabricApi.configureTests { username }). */
    public static final String PLAYER = "Tester";
    /** Flat world: bedrock at -64, grass at -61, so the player stands at -60. */
    public static final BlockPos ORIGIN = new BlockPos(0, -60, 0);
    public static final int SECOND = 20;

    private final ClientGameTestContext ctx;
    private final Result result = new Result();
    private final long startedNanos = System.nanoTime();
    private Throwable error;
    private TestDedicatedServerContext server;
    private TestDedicatedServerConnection connection;

    Harness(ClientGameTestContext ctx, String scenario, boolean chained) {
        this.ctx = ctx;
        result.scenario = scenario;
        result.chained = chained;
        applyQuietOptions();
    }

    /**
     * The framework's own {@code ClientGameTestContext.restoreDefaultGameOptions()} runs before
     * the scenario body ({@code FabricClientGameTestRunner.setupInitialGameTestState}), and it
     * undoes whatever {@link GameTestClientInit} set at {@code ClientLifecycleEvents.CLIENT_STARTED}
     * -- that only covers the title-screen phase before a test starts, not what happens after the
     * framework resets options for this scenario. This re-applies the same two settings here, after
     * that restore, so every scenario runs muted and without pause-on-lost-focus regardless of what
     * ran in between.
     */
    private void applyQuietOptions() {
        ctx.runOnClient(mc -> {
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc.options.pauseOnLostFocus = false;
            // Every scenario's own boot, chained or not: ChatCapture is a static list, and a
            // chained run's scenario N+1 must never read a line scenario N's chat left behind.
            // Client thread, alongside the two lines above, for the same reason ChatCapture's own
            // javadoc gives -- the mixin that feeds it fires on this thread too.
            ChatCapture.reset();
            // I-3: the same backstop, for a runtime toggle instead of a launch option. Task 9 made
            // /hoard peek off/on persist to config/hoardkeeper.json, and GameTestReset's
            // config reload (chained runs only, and only between scenarios) restores whatever is on
            // disk -- which is exactly "off" if a check or a wait between PeekScenario's off and on
            // commands ever fails there. PeekScenario now restores its own flag on every exit path
            // (see its own javadoc), but this runs for every scenario, chained or not, first or not,
            // so no scenario, however an earlier one failed, inherits the card switched off.
            HoardkeeperMod.config().peekEnabled = true;
        });
    }

    public ClientGameTestContext ctx() {
        return ctx;
    }

    public String scenario() {
        return result.scenario;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Flat world, the player op'd and standing at ORIGIN with the chunks around loaded. */
    public void openServer() {
        guardConfig();
        Properties props = new Properties();
        props.setProperty("level-type", "minecraft:flat");
        props.setProperty("spawn-protection", "0");
        props.setProperty("view-distance", "6");
        props.setProperty("simulation-distance", "6");
        props.setProperty("gamemode", "creative");
        server = ctx.worldBuilder().createServer(props);
        connection = server.connect();
        serverCommand("op " + PLAYER);
        teleport(ORIGIN);
        Fixtures.testBase(this);
        connection.waitForClientboundPackets();
        // waitForClientboundPackets only waits for packets already in flight; it does not guarantee
        // the client has turned them into level state yet. A scan whose sweep finds nothing finishes
        // itself within a tick (ScanCompletion.nothingLeftToScan), so a scenario that starts a scan
        // right after this method returns can find the client's level still missing fixture block
        // entities, sweep zero candidates, and time out waiting for a scan that already ended. Wait
        // here, once, for the client to actually see everything this method just placed.
        waitFor("the client sees all " + Fixtures.CONTAINERS + " fixture containers",
                Fixtures::allVisibleTo, 20 * SECOND);
    }

    // HoardkeeperMod.config() is read off the client thread here (openServer runs before any
    // ctx.runOnClient dispatch). That is only safe because this call happens before a scenario
    // ever calls setConfig().
    private static void guardConfig() {
        if (!HoardkeeperMod.config().scanServers.contains("localhost")) {
            throw new IllegalStateException("refusing to run: scanServers does not name localhost."
                    + " Did ConfigGuard run?");
        }
    }

    /** Edits the live config on the client thread and re-installs it, the way a reload would. */
    public void setConfig(Consumer<HoardkeeperConfig> edit) {
        ctx.runOnClient(mc -> {
            HoardkeeperConfig config = HoardkeeperMod.config();
            edit.accept(config);
            HoardkeeperMod.setConfig(config);
        });
    }

    // ------------------------------------------------------------------ driving

    /** A player command without the leading slash, through the same path typed chat takes. */
    public void command(String line) {
        ctx.runOnClient(mc -> mc.player.connection.sendCommand(line));
        ctx.waitTick();
    }

    /** A console command on the in-process server (permission level 4). */
    public void serverCommand(String line) {
        server.runCommand(line);
    }

    public void teleport(BlockPos pos) {
        serverCommand("tp " + PLAYER + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        waitFor("player at " + pos, mc -> mc.player != null
                && mc.player.blockPosition().distManhattan(pos) <= 1, 5 * SECOND);
        connection.waitForChunksDownload();
        connection.waitForClientboundPackets();
    }

    /** ctx.waitFor with a name in the failure, so a timeout says what it waited for. */
    public int waitFor(String what, Predicate<Minecraft> condition, int ticks) {
        try {
            return ctx.waitFor(condition, ticks);
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError("timed out after " + ticks + " ticks waiting for: " + what, e);
        }
    }

    /** True if {@code violation} never became true within {@code ticks}. The "+0" checks. */
    public boolean stays(Predicate<Minecraft> violation, int ticks) {
        try {
            ctx.waitFor(violation, ticks);
            return false;
        } catch (AssertionError | RuntimeException timeout) {
            return true;
        }
    }

    public <T> T onClient(Function<Minecraft, T> f) {
        return ctx.computeOnClient(f::apply);
    }

    public void runOnClient(Consumer<Minecraft> c) {
        ctx.runOnClient(c::accept);
    }

    /** A real right-click: MouseHandler, startUseItem, useItemOn, UseBlockCallback. */
    public void rightClick(BlockPos pos) {
        ctx.getInput().lookAt(pos);
        ctx.waitTicks(2);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        ctx.waitTick();
    }

    /** Opens a container by hand and closes it with Escape, the way a player does. */
    public void openAndClose(BlockPos pos) {
        rightClick(pos);
        ctx.waitForScreen(AbstractContainerScreen.class);
        closeScreen();
    }

    public void closeScreen() {
        ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
        waitFor("screen closed", mc -> mc.gui.screen() == null, 2 * SECOND);
    }

    public void waitForScanEnd() {
        waitFor("scan end", mc -> !ScanController.get().isRunning(), 60 * SECOND);
    }

    /** Waits until at least one recorded chat line contains {@code substring} -- see {@link ChatCapture}. */
    public void awaitChatContaining(String substring, int ticks) {
        waitFor("a chat line containing '" + substring + "'",
                mc -> !ChatCapture.linesContaining(substring).isEmpty(), ticks);
    }

    /**
     * The recorded chat lines containing {@code substring}, read on the client thread right now --
     * for a check after a wait (like {@link #awaitChatContaining}) has already returned. A predicate
     * passed to {@link #waitFor} or {@link #stays} should call {@link ChatCapture#linesContaining}
     * directly instead, the same way {@code PeekScenario}'s own predicates call {@code
     * PeekRenderer.currentLinesForTest()} directly rather than through an {@link #onClient} hop: the
     * framework already runs that predicate on the client thread, and nesting another {@code
     * computeOnClient} call inside it would ask the client thread to wait on itself.
     */
    public List<String> chatLinesContaining(String substring) {
        return onClient(mc -> ChatCapture.linesContaining(substring));
    }

    /** Drops the connection the way a relog does; the DISCONNECT event fires on the client. */
    public void disconnect() {
        connection.close();
        connection = null;
        waitFor("client left the level", mc -> mc.level == null, 5 * SECOND);
    }

    public void reconnect() {
        connection = server.connect();
        connection.waitForChunksDownload();
    }

    // ------------------------------------------------------------------ evidence

    public void check(String name, boolean pass, Object detail) {
        result.checks.add(new Result.Check(name, pass, String.valueOf(detail)));
        HoardkeeperMod.LOGGER.warn("GAMETEST {} {} {} -- {}", result.scenario,
                pass ? "PASS" : "FAIL", name, detail);
    }

    public void checkEquals(String name, Object expected, Object actual) {
        check(name, Objects.equals(expected, actual), "expected " + expected + ", got " + actual);
    }

    public void number(String key, Object value) {
        result.numbers.put(key, value);
    }

    public Path screenshot(String name) {
        Path file = ctx.takeScreenshot(result.scenario + "-" + name);
        result.screenshots.add(file.getFileName().toString());
        return file;
    }

    /** Called by GameTestEntry when the scenario body threw. */
    void fail(Throwable t) {
        if (error == null) {
            error = t;
        }
    }

    /** Closes everything, writes result.json, then throws once if the scenario did not pass. */
    void close() {
        Throwable closeError = null;
        try {
            if (connection != null) {
                connection.close();
            }
            if (server != null) {
                server.close();
            }
        } catch (Throwable t) {
            closeError = t;
        }
        result.durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        result.error = error == null ? null : Result.ErrorInfo.of(error);
        result.pass = error == null && result.checks.stream().allMatch(c -> c.pass);
        if (closeError != null && error == null) {
            result.error = Result.ErrorInfo.of(closeError);
            result.pass = false;
        }
        writeResult();
        if (error != null) {
            throw new AssertionError("scenario '" + result.scenario + "' threw: " + error, error);
        }
        if (!result.pass) {
            throw new AssertionError("scenario '" + result.scenario + "' failed checks: "
                    + result.checks.stream().filter(c -> !c.pass).map(c -> c.name)
                    .collect(Collectors.joining(", ")));
        }
        if (closeError != null) {
            throw new AssertionError("scenario '" + result.scenario + "' could not close cleanly", closeError);
        }
    }

    private void writeResult() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("gametest");
        try {
            Files.createDirectories(dir);
            String json = GSON.toJson(result);
            Files.writeString(dir.resolve("result.json"), json);
            Files.writeString(dir.resolve("result-" + result.scenario + ".json"), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HoardkeeperMod.LOGGER.warn("GAMETEST {} {} -> {}", result.scenario,
                result.pass ? "PASS" : "FAIL", dir.resolve("result.json"));
    }
}
