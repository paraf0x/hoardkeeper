package dev.hoardkeeper.api;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.hoardkeeper.scan.ScanSession;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * What an add-on may hook into — spec 2026-09-30-hoardkeeper-split-design.md §6. The core calls
 * every add-on registered under the {@value Addons#ENTRYPOINT} entrypoint and never names one, so
 * everything that talks to a server of its own (an upload add-on) lives behind this.
 *
 * <p>Every method has a do-nothing default, is called on the client thread, and is guarded by
 * {@link Addons}: an exception in an add-on is logged and never breaks the core.
 */
public interface Addon {

    /**
     * A scan has stopped, for whatever reason — its files are closed, the session is still in hand.
     * Called before the session is folded into the measured map.
     */
    default void onScanStopped(Minecraft mc, ScanSession session) {
    }

    /**
     * A scan finished on its own (everything in reach done, or the quiet countdown ran out), after
     * {@link #onScanStopped} and after the core said so in chat. The session itself has been let go
     * of; {@code finished} carries what is left to know.
     */
    default void onScanAutoFinished(Minecraft mc, ScanFinished finished) {
    }

    /**
     * The core's config has been loaded at startup. An add-on may adjust it here once — and save
     * what it changed with {@code HoardkeeperMod.saveList} or {@code saveFlag}.
     */
    default void onCoreConfigLoaded(dev.hoardkeeper.HoardkeeperConfig config) {
    }

    /** Every client tick, after the core's own services. */
    default void onClientTick(Minecraft mc) {
    }

    /** The player left the server or the world. */
    default void onDisconnect() {
    }

    /** Whether this add-on has a line in the action bar that outranks the scan's progress. */
    default boolean ownsActionBar() {
        return false;
    }

    /** Adds this add-on's subcommands to the core's root command. */
    default void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root) {
    }

    /**
     * A finished scan session the measured map already holds is about to be deleted from disk
     * (spec §7). Returning {@code true} keeps it — an upload add-on does, until it has uploaded.
     * Called on the client thread while the player is on the server the session belongs to.
     */
    default boolean claimsSession(Minecraft mc, dev.hoardkeeper.model.SessionSnapshot snapshot) {
        return false;
    }

    /** Lines appended to the core's help, one per subcommand this add-on adds. */
    default List<String> helpLines() {
        return List.of();
    }

    /**
     * What is left of a scan that finished on its own.
     *
     * @param sessionId the session's id, the name of its directory
     * @param scanned   containers read
     * @param purpose   {@link ScanSession#purpose}, opaque to the core
     */
    record ScanFinished(String sessionId, int scanned, String purpose) {
    }
}
