package dev.hoardkeeper;

import dev.hoardkeeper.api.Addons;
import dev.hoardkeeper.command.HoardCommand;
import dev.hoardkeeper.deposit.DepositService;
import dev.hoardkeeper.hud.ScanHudRenderer;
import dev.hoardkeeper.index.StorageIndexCache;
import dev.hoardkeeper.observe.PassiveObserver;
import dev.hoardkeeper.peek.PeekRenderer;
import dev.hoardkeeper.realm.RealmKey;
import dev.hoardkeeper.render.ScanGizmos;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.search.SearchGizmos;
import dev.hoardkeeper.search.SearchKey;
import dev.hoardkeeper.search.SearchService;
import dev.hoardkeeper.tooltip.StorageTooltip;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Client entrypoint: loads the config and hooks the scanner up to the game loop.
 *
 * <p>Everything the mod does hangs off the five registrations below — nothing here decides
 * anything, it only connects {@link ScanController} to the events it needs.
 */
public class HoardkeeperMod implements ClientModInitializer {
    public static final String MOD_ID = "hoardkeeper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * The active config. Starts as in-code defaults so anything constructed before (or without)
     * {@link #onInitializeClient()} — unit tests, a mixin firing early — still has usable values;
     * {@link #onInitializeClient()} replaces it with what is on disk.
     */
    private static HoardkeeperConfig config = new HoardkeeperConfig();

    /**
     * Where {@link #config()} was loaded from, so a runtime toggle can write it back. Null until the
     * client entrypoint runs — a unit test or an early mixin gets in-code defaults and no file, and
     * {@link #saveFlag(String, boolean)} then does nothing rather than inventing a path.
     */
    private static Path configFile;

    public static HoardkeeperConfig config() {
        return config;
    }

    /** Client thread only, called from the entrypoint (and by a future config-reload command). */
    public static void setConfig(HoardkeeperConfig loaded) {
        config = loaded;
    }

    /** Client thread only. Persists one flag, merged into the config file as it is on disk. */
    public static void saveList(String key, java.util.List<String> value) {
        if (configFile != null) {
            config.saveList(configFile, key, value);
        }
    }

    public static void saveFlag(String key, boolean value) {
        if (configFile != null) {
            config.saveFlag(configFile, key, value);
        }
    }

    @Override
    public void onInitializeClient() {
        // Force ClientPacketListener to load now. Mixins apply at class-load time, so this
        // turns a broken injection point into a loud crash at startup instead of a silent
        // no-op that only shows up at the first chest.
        LOGGER.info("Mixin target loaded: {}", ClientPacketListener.class.getName());

        // Once, for players coming from storage-scanner: its config and its data carry over.
        LegacyMigration.run(FabricLoader.getInstance().getConfigDir(), FabricLoader.getInstance().getGameDir());
        configFile = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
        setConfig(HoardkeeperConfig.load(configFile));
        // Add-ons see the core's config once it is loaded — an upload add-on seeds scanServers here.
        Addons.onCoreConfigLoaded(config);

        ClientCommandRegistrationCallback.EVENT.register(HoardCommand::register);

        // The key that searches for whatever is in the player's hand. Registered before the first
        // tick, which is where Fabric expects key mappings to appear.
        SearchKey.register();

        // A proxy backend switch fires no disconnect and no join of its own -- the only signal is a
        // fresh login packet, which RealmKey already sees. Without this the scan would carry on in
        // the new world and file its containers under the realm it started in.
        RealmKey.get().setListener(realm -> ScanController.get().onRealmChanged(realm));

        // One tick hook for both: the state machine advances, then this tick's gizmos are emitted.
        // Gizmos must be emitted from inside Minecraft.tick() — see ScanGizmos for why that is the
        // collector scope that actually gets submitted for the frame.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ScanController.get().onClientTick(client);
            ScanGizmos.emit(client);
            // The measured-map cache's staleness check runs before the search's own tick, so a
            // search started this same tick sees a freshly rechecked index rather than last tick's.
            StorageIndexCache.get().tick(client);
            // The peek card's block-state read -- the one thing that tells "not scanned yet" from
            // "not a container" -- needs the client thread, so it rides this same tick rather than
            // the frame; it runs after the cache above so it sees this tick's freshly rechecked
            // index rather than the previous one's.
            PeekRenderer.tick(client);
            // The search's countdown and its boxes ride the same tick, and for the same reason: a
            // gizmo only reaches the frame when it is emitted from inside Minecraft.tick().
            SearchService.get().onClientTick(client);
            // Polled here because that is where consumeClick is meant to be read, and because the
            // search it starts is the same one the command starts.
            SearchKey.onClientTick(client);
            SearchGizmos.emit(client);
            // The singleplayer deposit: after the search, whose lit containers it yields to.
            DepositService.get().onClientTick(client);
            DepositService.get().emitGizmos(client);
            // Add-ons last: an upload add-on's site check and observation upload ride here (spec
            // 2026-09-30 §6), after everything they might read has had its tick.
            Addons.onClientTick(client);
        });

        // How the item search learns that the player opened a highlighted chest. This fires for the
        // scanner's own silent opens too -- MultiPlayerGameMode.useItemOn is where Fabric raises it,
        // and that is exactly the method InteractionSender calls -- which is why SearchService asks
        // InteractionSender whose interaction this is. PASS always: this listens, it never decides.
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            // The singleplayer deposit's sneak-right-click with an empty hand (spec 2026-09-28 §8a):
            // FAIL cancels the ordinary open and sends nothing; the deposit opens it without a screen.
            if (level.isClientSide()
                    && DepositService.get().onUseBlock(Minecraft.getInstance(), hand, hitResult.getBlockPos())) {
                return InteractionResult.FAIL;
            }
            if (level.isClientSide()) {
                SearchService.get().onPlayerUsedBlock(hitResult.getBlockPos());
                DepositService.get().onPlayerUsedBlock(hitResult.getBlockPos());
            }
            return InteractionResult.PASS;
        });

        // Walking into new territory picks up containers immediately instead of waiting for the
        // next periodic sweep.
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ScanController.get().onChunkLoaded(level, chunk));

        // Offer to continue an interrupted scan — an offer only, never an automatic resume: packets
        // must not start flying the moment somebody logs in. Deferred by one tick via execute() so
        // the level and player are certainly in place when the offer looks for a matching session.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(ScanController.get()::offerResume));

        // Flushes containers.jsonl and session.json and marks the snapshot INTERRUPTED. A relog
        // mid-scan must not lose the last containers, and the snapshot it leaves behind is exactly
        // what JOIN looks for above.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ScanController.get().onDisconnect();
            // A highlight is a set of coordinates in one world, and the index is one server's scan.
            // Neither means anything on the next server joined.
            SearchService.get().onDisconnect();
            Addons.onDisconnect();
            DepositService.get().onDisconnect();
            // A realm key from the server just left is worse than none: the next scan would be
            // stamped with it. See RealmKey.
            RealmKey.get().onDisconnect();
        });

        // Records what a player leaves in a container they opened by hand, silently, into a log of
        // its own. Subscribes itself: its screen hook is per screen instance and has to be
        // registered from inside another event, so a single call here is the honest shape.
        PassiveObserver.register();

        ScanHudRenderer.register();

        // The container card shown while crouching in front of a chest, barrel or shulker box.
        // Computed on the tick registered above, drawn from that cache here. See PeekRenderer.
        PeekRenderer.register();

        // The tooltip line: one lookup on the measured map's index per hovered stack, silent outside
        // a scanned area exactly like the HUD panel above. See StorageTooltip and TooltipText.
        StorageTooltip.register();

        LOGGER.info("Hoardkeeper ready — /hoard (default radius {}, max {})",
                config.defaultRadius, config.maxRadius);
    }
}
