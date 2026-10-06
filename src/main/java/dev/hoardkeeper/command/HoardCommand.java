package dev.hoardkeeper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.api.Addons;
import dev.hoardkeeper.chat.ScanChat;
import dev.hoardkeeper.chat.ScanText;
import dev.hoardkeeper.deposit.DepositService;
import dev.hoardkeeper.observe.PassiveObserver;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.scan.ServerList;
import dev.hoardkeeper.search.SearchService;
import dev.hoardkeeper.search.SearchSuggestions;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The client-side {@code /hoard} command tree.
 *
 * <p><b>Deliberately thin.</b> Every leaf does the same three things and nothing else: check that
 * the command makes sense right now, call exactly one {@link ScanController} method, and put one
 * line on screen. No counting, no file handling, no session juggling — the controller owns the
 * session and {@code ScanText} owns the wording, so the brigadier tree stays a keyboard shortcut
 * for controller methods rather than a second place where scan logic can drift.
 *
 * <p>Where the controller already announces an outcome itself ({@code start}, {@code stop},
 * {@code retry-failed}, {@code resume}) the leaf deliberately prints nothing extra, so the player
 * gets one message per command rather than an echo of it.
 */
public final class HoardCommand {

    private HoardCommand() {
    }

    /**
     * Registers the command tree. Signature matches
     * {@code ClientCommandRegistrationCallback#register}, so the entrypoint can pass this as a
     * method reference.
     */
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                 CommandBuildContext buildContext) {
        // Brigadier bakes an argument's bounds in at registration time, i.e. once at startup. The
        // leaf therefore clamps against the live config as well: the upper bound below is only what
        // the client will auto-complete and accept, never the authority on what actually runs.
        int maxRadius = Math.max(1, HoardkeeperMod.config().maxRadius);
        int maxChunkRadius = Math.max(0, HoardkeeperMod.config().maxChunkRadius);

        LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("hoard")
                // The bare command starts a scan rather than printing help. Scanning is one
                // intention that used to cost three commands -- start, stop, upload -- and the two
                // that matter come after the interesting part is over, which is where people
                // stopped. Help moves to `/hoard help`; tab completion still lists the rest.
                .executes(ctx -> start(ctx, ScanArea.Request.ofChunks(
                        HoardkeeperMod.config().defaultChunkRadius)))
                .then(ClientCommands.literal("help")
                        .executes(HoardCommand::help))
                .then(ClientCommands.literal("start")
                        // No argument means the chunk-aligned default: the 3x3 block of chunks the
                        // server's quick-deposit plugin works in. A bare number still means a block
                        // radius, so nobody's muscle memory breaks.
                        .executes(ctx -> start(ctx, ScanArea.Request.ofChunks(
                                HoardkeeperMod.config().defaultChunkRadius)))
                        .then(ClientCommands.literal("chunks")
                                .then(ClientCommands.argument("rings", IntegerArgumentType.integer(0, maxChunkRadius))
                                        .executes(ctx -> start(ctx, ScanArea.Request.ofChunks(
                                                IntegerArgumentType.getInteger(ctx, "rings"))))))
                        .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                                .executes(ctx -> start(ctx, ScanArea.Request.ofRadius(
                                        IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(ClientCommands.literal("stop")
                        .executes(HoardCommand::stop))
                .then(ClientCommands.literal("status")
                        .executes(HoardCommand::status))
                .then(ClientCommands.literal("export")
                        .executes(HoardCommand::export))
                .then(ClientCommands.literal("retry-failed")
                        .executes(HoardCommand::retryFailed))
                .then(ClientCommands.literal("resume")
                        .executes(HoardCommand::resume))
                // Where the scanner may act: spec 2026-09-30-hoardkeeper-split-design.md §4.
                .then(ClientCommands.literal("allow")
                        .executes(HoardCommand::allow))
                .then(ClientCommands.literal("disallow")
                        .executes(HoardCommand::disallow))
                .then(ClientCommands.literal("clear")
                        .executes(HoardCommand::clear))
                // Greedy rather than word(): Brigadier's unquoted-word rule rejects a colon, so
                // `word()` would refuse the very ids tab completion offers. Item ids never contain
                // a space, so taking the rest of the line costs nothing.
                .then(ClientCommands.literal("search")
                        .executes(HoardCommand::clearSearch)
                        .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                                .suggests(SearchSuggestions::forLastScan)
                                .executes(HoardCommand::search)))
                // `site` starts a scan rather than reading one, and that is the whole point of the
                // name: a site scan does not file its chests into the shared catalogue. See
                // The singleplayer deposit mode: docs/superpowers/specs/2026-09-28-singleplayer-deposit-design.md.
                .then(ClientCommands.literal("deposit")
                        .executes(ctx -> deposit(null))
                        .then(ClientCommands.literal("on")
                                .executes(ctx -> deposit(true)))
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> deposit(false))))
                .then(ClientCommands.literal("peek")
                        .executes(ctx -> setPeek(ctx, !HoardkeeperMod.config().peekEnabled))
                        .then(ClientCommands.literal("on")
                                .executes(ctx -> setPeek(ctx, true)))
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> setPeek(ctx, false))))
                .then(ClientCommands.literal("reminder")
                        .executes(ctx -> setReminder(ctx, !HoardkeeperMod.config().rescanReminder))
                        .then(ClientCommands.literal("on")
                                .executes(ctx -> setReminder(ctx, true)))
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> setReminder(ctx, false))));
        // Add-ons hang their own subcommands off the same root (spec 2026-09-30 §6) -- an upload
        // add-on adds `upload` and `site` here; the core names neither.
        Addons.registerCommands(root);
        dispatcher.register(root);
    }

    // =========================================================================
    // Leaves
    // =========================================================================

    private static int help(CommandContext<FabricClientCommandSource> ctx) {
        HoardkeeperConfig config = HoardkeeperMod.config();
        for (String line : ScanText.help(config.defaultChunkRadius, config.maxChunkRadius,
                config.defaultRadius, config.maxRadius, config.autoFinishSeconds)) {
            ctx.getSource().sendFeedback(Component.literal(line));
        }
        for (String line : Addons.helpLines()) {
            ctx.getSource().sendFeedback(Component.literal(line));
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Starts a new scan. Refuses while one is running rather than silently replacing it: a running
     * session holds open output files and hundreds of already-scanned containers, and quietly
     * throwing that away because somebody re-typed the command is not a recoverable mistake.
     */
    private static int start(CommandContext<FabricClientCommandSource> ctx, ScanArea.Request requested) {
        ScanController controller = ScanController.get();
        if (controller.isRunning()) {
            return feedback(ctx, ScanText.alreadyRunningText());
        }
        HoardkeeperConfig config = HoardkeeperMod.config();
        // Clamped again here even though the argument type already bounds it: the command tree is
        // built once at startup from the config as it was then, and a config reload can lower a
        // maximum underneath a tree that still accepts the old one.
        ScanArea.Request bounded = requested.mode() == ScanArea.Mode.CHUNKS
                ? ScanArea.Request.ofChunks(Math.clamp(requested.size(), 0, Math.max(0, config.maxChunkRadius)))
                : ScanArea.Request.ofRadius(Math.clamp(requested.size(), 1, Math.max(1, config.maxRadius)));
        controller.start(client(ctx), bounded);
        return Command.SINGLE_SUCCESS;
    }

    private static int stop(CommandContext<FabricClientCommandSource> ctx) {
        ScanController.get().stop(client(ctx), "command");
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx, ScanController.get().statusText());
        // Spec §7: the one and only place passive observation is visible. Appended as a second
        // line rather than folded into statusText(), and only when the feature is on, so a player
        // who left passiveObserve off sees exactly today's output — the scan's own status text is
        // not this feature's to rewrite.
        if (HoardkeeperMod.config().passiveObserve) {
            PassiveObserver.Status observed = PassiveObserver.get().status(client(ctx));
            if (observed != null) {
                feedback(ctx, ScanText.observationStatusText(
                        observed.rows(), observed.pending(), observed.secondsSinceLast()));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int export(CommandContext<FabricClientCommandSource> ctx) {
        ScanController.ExportResult result = ScanController.get().export(client(ctx));
        if (result == null) {
            return feedback(ctx, ScanText.exportFailedText());
        }
        // Clickable line naming the session and dimension the report actually came from.
        ScanChat.exported(result.report(), result.sessionId(), result.dimension());
        return Command.SINGLE_SUCCESS;
    }

    private static int retryFailed(CommandContext<FabricClientCommandSource> ctx) {
        ScanController controller = ScanController.get();
        if (controller.session() == null) {
            return feedback(ctx, ScanText.noSessionText());
        }
        controller.retryFailed();
        return Command.SINGLE_SUCCESS;
    }

    private static int resume(CommandContext<FabricClientCommandSource> ctx) {
        ScanController controller = ScanController.get();
        if (controller.isRunning()) {
            return feedback(ctx, ScanText.alreadyRunningText());
        }
        if (!ScanController.mayActHere(client(ctx))) {
            return feedback(ctx, ScanText.scanNotAllowedText(ScanController.serverAddress(client(ctx))));
        }
        // A stopped session is still a session: resuming would reset() it away. offerResume refuses
        // for the same reason, and the command must not be the softer path to the same loss.
        if (controller.session() != null) {
            return feedback(ctx, ScanText.sessionInMemoryText());
        }
        if (!controller.resume(client(ctx))) {
            return feedback(ctx, ScanText.noResumableText());
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Lights up every container in the last scan holding one item. Announces its own outcome from
     * {@code SearchService} — including "no scan on disk" and "nothing like that in it" — so the
     * leaf prints nothing extra, exactly like {@code start} and the upload leaves.
     */
    private static int search(CommandContext<FabricClientCommandSource> ctx) {
        SearchService.get().search(client(ctx), StringArgumentType.getString(ctx, "item"));
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /hoard search} with no item: put the highlight out early. */
    private static int clearSearch(CommandContext<FabricClientCommandSource> ctx) {
        SearchService.get().clear();
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /hoard allow} — the current server joins {@code scanServers}. */
    private static int allow(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft mc = client(ctx);
        if (mc.hasSingleplayerServer()) {
            return feedback(ctx, ScanText.singleplayerAlwaysAllowedText());
        }
        String address = ScanController.serverAddress(mc);
        HoardkeeperConfig config = HoardkeeperMod.config();
        if (ServerList.listed(address, config.scanServers)) {
            return feedback(ctx, ScanText.alreadyAllowedText(address));
        }
        String host = ServerList.host(address);
        List<String> servers = new ArrayList<>(config.scanServers);
        servers.add(host);
        config.scanServers = servers;
        HoardkeeperMod.saveList("scanServers", servers);
        return feedback(ctx, ScanText.allowedText(host));
    }

    /**
     * {@code /hoard disallow} — every entry that covers the current server leaves
     * {@code scanServers}, including a parent domain, and the line says which ones went.
     */
    private static int disallow(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft mc = client(ctx);
        String address = ScanController.serverAddress(mc);
        HoardkeeperConfig config = HoardkeeperMod.config();
        List<String> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String entry : config.scanServers) {
            (ServerList.listed(address, List.of(entry)) ? removed : kept).add(entry);
        }
        if (removed.isEmpty()) {
            return feedback(ctx, ScanText.notAllowedAnywayText(address));
        }
        config.scanServers = kept;
        HoardkeeperMod.saveList("scanServers", kept);
        return feedback(ctx, ScanText.disallowedText(removed));
    }

    private static int clear(CommandContext<FabricClientCommandSource> ctx) {
        ScanController.get().reset();
        return feedback(ctx, ScanText.clearedText());
    }

    /**
     * Both toggles write through to disk: a player who turns the card off because it is in the way
     * means it to stay off. The bare command flips whatever the flag is now, so the common case is
     * one word and the explicit forms stay available for a click target or a macro.
     */
    private static int setPeek(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        HoardkeeperMod.config().peekEnabled = on;
        HoardkeeperMod.saveFlag("peekEnabled", on);
        return feedback(ctx, ScanText.peekToggledText(on));
    }

    private static int setReminder(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        HoardkeeperMod.config().rescanReminder = on;
        HoardkeeperMod.saveFlag("rescanReminder", on);
        return feedback(ctx, ScanText.reminderToggledText(on));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Minecraft client(CommandContext<FabricClientCommandSource> ctx) {
        return ctx.getSource().getClient();
    }

    private static int feedback(CommandContext<FabricClientCommandSource> ctx, String line) {
        ctx.getSource().sendFeedback(Component.literal(line));
        return Command.SINGLE_SUCCESS;
    }

    private static int deposit(Boolean on) {
        DepositService.get().set(Minecraft.getInstance(), on);
        return 1;
    }
}
