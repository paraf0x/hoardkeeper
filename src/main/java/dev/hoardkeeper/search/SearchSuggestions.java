package dev.hoardkeeper.search;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.hoardkeeper.HoardkeeperMod;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Tab completion for {@code /hoard search}, offering the items the last scan actually found.
 *
 * <p>This is the half of the feature that makes the other half usable. Nobody remembers whether the
 * chest room holds {@code deepslate_redstone_ore} or {@code redstone_ore}, and a search that only
 * accepts exact ids is a search you have to already know the answer to. Completing from the scan —
 * rather than from the item registry — also means every suggestion is guaranteed to find something,
 * and the tooltip beside it says how much and in how many containers before the player commits.
 *
 * <p><b>Asynchronous by design.</b> The index is built off-thread from a file that can run to
 * megabytes, and this returns the future rather than waiting on it: Minecraft's completion box is
 * built to fill in late (it normally waits on a server round trip), so a first completion that
 * appears a moment after the player stops typing is exactly the right failure mode. Blocking here
 * would freeze the client mid-keystroke instead.
 */
public final class SearchSuggestions {

    /**
     * How many ids the completion box is offered at once. Minecraft shows ten at a time and scrolls;
     * this is the number worth ranking, not the number worth showing, and a base with a thousand
     * distinct items should not send all of them for the sake of a list nobody scrolls to the end of.
     */
    private static final int MAX_SUGGESTIONS = 50;

    private SearchSuggestions() {
    }

    /** Suggestion provider for the {@code item} argument. */
    public static CompletableFuture<Suggestions> forLastScan(CommandContext<FabricClientCommandSource> ctx,
                                                              SuggestionsBuilder builder) {
        CompletableFuture<SearchIndex> pending =
                SearchService.get().indexFuture(ctx.getSource().getClient());
        if (pending == null) {
            // No map on disk for this server. Silence is right here: the command itself says so
            // when it is actually run, and a completion box is not a place to explain anything.
            return Suggestions.empty();
        }
        return pending.handle((index, error) -> {
            if (error != null || index == null) {
                HoardkeeperMod.LOGGER.warn("Could not build search suggestions", error);
                return builder.build();
            }
            return build(index, builder);
        });
    }

    private static Suggestions build(SearchIndex index, SuggestionsBuilder builder) {
        for (String id : index.suggest(builder.getRemaining(), MAX_SUGGESTIONS)) {
            builder.suggest(id, Component.literal(
                    SearchText.suggestionTooltip(index.total(id), index.hits(id).size())));
        }
        return builder.build();
    }
}
