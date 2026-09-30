package dev.hoardkeeper.api;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.hoardkeeper.HoardkeeperMod;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The registered add-ons, and the one place that calls them. Each call is guarded: an add-on that
 * throws is logged once per hook, not per tick, and the core carries on as if it had done nothing.
 */
public final class Addons {

    /** The Fabric entrypoint an add-on registers its {@link Addon} under. */
    public static final String ENTRYPOINT = "hoardkeeper:addon";

    private static List<Addon> addons;
    private static final Set<String> reported = new HashSet<>();

    private Addons() {
    }

    /** Loaded on first use, once. */
    public static List<Addon> all() {
        if (addons == null) {
            List<Addon> found = new ArrayList<>();
            try {
                found.addAll(FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, Addon.class));
            } catch (RuntimeException e) {
                HoardkeeperMod.LOGGER.error("Could not load add-ons", e);
            }
            addons = List.copyOf(found);
            for (Addon addon : addons) {
                HoardkeeperMod.LOGGER.info("Add-on loaded: {}", addon.getClass().getName());
            }
        }
        return addons;
    }

    public static void onScanStopped(Minecraft mc, dev.hoardkeeper.scan.ScanSession session) {
        each("onScanStopped", a -> a.onScanStopped(mc, session));
    }

    public static void onScanAutoFinished(Minecraft mc, Addon.ScanFinished finished) {
        each("onScanAutoFinished", a -> a.onScanAutoFinished(mc, finished));
    }

    public static void onCoreConfigLoaded(dev.hoardkeeper.HoardkeeperConfig config) {
        each("onCoreConfigLoaded", a -> a.onCoreConfigLoaded(config));
    }

    public static void onClientTick(Minecraft mc) {
        each("onClientTick", a -> a.onClientTick(mc));
    }

    public static void onDisconnect() {
        each("onDisconnect", Addon::onDisconnect);
    }

    public static boolean ownsActionBar() {
        for (Addon addon : all()) {
            try {
                if (addon.ownsActionBar()) {
                    return true;
                }
            } catch (RuntimeException e) {
                report(addon, "ownsActionBar", e);
            }
        }
        return false;
    }

    /** Whether any add-on wants this session kept. An add-on that throws keeps it: deleting is the one thing not undone. */
    public static boolean claimsSession(Minecraft mc, dev.hoardkeeper.model.SessionSnapshot snapshot) {
        for (Addon addon : all()) {
            try {
                if (addon.claimsSession(mc, snapshot)) {
                    return true;
                }
            } catch (RuntimeException e) {
                report(addon, "claimsSession", e);
                return true;
            }
        }
        return false;
    }

    public static void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        each("registerCommands", a -> a.registerCommands(root));
    }

    public static List<String> helpLines() {
        List<String> lines = new ArrayList<>();
        each("helpLines", a -> lines.addAll(a.helpLines()));
        return lines;
    }

    private static void each(String hook, Consumer<Addon> call) {
        for (Addon addon : all()) {
            try {
                call.accept(addon);
            } catch (RuntimeException e) {
                report(addon, hook, e);
            }
        }
    }

    private static void report(Addon addon, String hook, RuntimeException e) {
        if (reported.add(addon.getClass().getName() + "#" + hook)) {
            HoardkeeperMod.LOGGER.error("Add-on {} failed in {}; the core carries on",
                    addon.getClass().getName(), hook, e);
        }
    }
}
