package dev.hoardkeeper.search;

import dev.hoardkeeper.capture.Totals;
import dev.hoardkeeper.model.ScannedContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Which containers hold this item, and how much?" — one scan's contents turned inside out.
 *
 * <p>A {@code containers.jsonl} is a list of containers each carrying a list of items, which is the
 * right shape for writing a scan and the wrong one for asking about a single item. This class
 * inverts it once, into item id → the containers holding it, and answers both questions the search
 * command asks: what to offer as tab completion while the player types, and where to send them once
 * they pick something.
 *
 * <p><b>Pure.</b> No Minecraft imports, no file handling, no caching policy — it is built from a
 * list of already-parsed containers and never looks at the world. {@link SearchService} owns
 * reading the file and deciding when this is stale.
 *
 * <p>Counts come from each container's precomputed {@code totals} when it has them and are summed
 * from its items otherwise, which is what keeps a file written by an older version of the mod
 * searchable. Either way the number includes items nested inside a shulker box inside the container
 * — see {@link Totals} — because "I have 8 diamonds in that chest" is the true answer even when
 * five of them are in a shulker on the bottom row.
 */
public final class SearchIndex {

    /** No matching id. Deliberately not a magic number anywhere else in this file. */
    private static final int NO_MATCH = -1;

    private static final SearchIndex EMPTY = new SearchIndex(Map.of(), Map.of(), List.of(), 0);

    private final Map<String, List<ContainerHit>> hitsByItem;
    private final Map<String, Long> totalsByItem;
    private final List<String> ids;
    private final int scannedContainers;

    private SearchIndex(Map<String, List<ContainerHit>> hitsByItem, Map<String, Long> totalsByItem,
                         List<String> ids, int scannedContainers) {
        this.hitsByItem = hitsByItem;
        this.totalsByItem = totalsByItem;
        this.ids = ids;
        this.scannedContainers = scannedContainers;
    }

    /** An index over nothing — what a server with no scan on disk gets. */
    public static SearchIndex empty() {
        return EMPTY;
    }

    /**
     * Inverts one scan's containers into an item index. Containers without a usable position are
     * dropped: the whole point of a hit is a place to walk to, and one without coordinates cannot
     * be highlighted, cannot be dismissed by opening it, and would only inflate the counts the
     * player is told.
     */
    public static SearchIndex of(List<ScannedContainer> containers) {
        Map<String, List<ContainerHit>> hits = new HashMap<>();
        Map<String, Long> totals = new HashMap<>();
        int counted = 0;

        for (ScannedContainer container : containers) {
            if (container == null || !isPos(container.pos)) {
                continue;
            }
            counted++;
            for (Map.Entry<String, Long> entry : totalsOf(container).entrySet()) {
                String id = entry.getKey();
                long count = entry.getValue() == null ? 0L : entry.getValue();
                // A zero (or, from a hand-edited file, negative) count is not a place worth walking
                // to, and highlighting a chest that holds none of what was asked for is a lie.
                if (id == null || count <= 0) {
                    continue;
                }
                hits.computeIfAbsent(id, key -> new ArrayList<>()).add(new ContainerHit(
                        container.pos, container.secondaryPos, container.kind, count));
                totals.merge(id, count, Long::sum);
            }
        }

        // Biggest pile first, within an item and across items. The player looking for diamonds wants
        // the chest with 900 of them before the one with 3, and the tab completion for an empty
        // query is far more useful showing what the base is full of than showing it alphabetically.
        for (List<ContainerHit> list : hits.values()) {
            list.sort(Comparator.comparingLong(ContainerHit::count).reversed());
        }
        List<String> ids = new ArrayList<>(totals.keySet());
        ids.sort(byTotalDescendingThenId(totals));

        Map<String, List<ContainerHit>> frozenHits = new LinkedHashMap<>();
        for (String id : ids) {
            frozenHits.put(id, List.copyOf(hits.get(id)));
        }
        return new SearchIndex(frozenHits, Map.copyOf(totals), List.copyOf(ids), counted);
    }

    private static Map<String, Long> totalsOf(ScannedContainer container) {
        if (container.totals != null && !container.totals.isEmpty()) {
            return container.totals;
        }
        return Totals.of(container.items);
    }

    // =========================================================================
    // Queries
    // =========================================================================

    /** Every item id in this scan, biggest total first, then alphabetically. */
    public List<String> ids() {
        return ids;
    }

    /** How many of {@code id} the whole scan holds, or 0 for an id it never saw. */
    public long total(String id) {
        return totalsByItem.getOrDefault(id, 0L);
    }

    /** The containers holding {@code id}, biggest pile first. Empty for an unknown id. */
    public List<ContainerHit> hits(String id) {
        return hitsByItem.getOrDefault(id, List.of());
    }

    /** How many containers of the scan carried at least one item at all. */
    public int scannedContainers() {
        return scannedContainers;
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    /**
     * The ids to offer for what has been typed so far, best match first, at most {@code limit} of
     * them. A blank query lists the scan's biggest piles, which is the most useful thing an empty
     * completion box can show.
     */
    public List<String> suggest(String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        record Ranked(String id, int rank) {
        }
        List<Ranked> matches = new ArrayList<>();
        for (String id : ids) {
            int rank = rank(query, id);
            if (rank != NO_MATCH) {
                matches.add(new Ranked(id, rank));
            }
        }
        // ids is already sorted biggest-total-first, and List.sort is stable — so ranking by match
        // quality here keeps "more of it" as the tie-break without re-reading the totals map.
        matches.sort(Comparator.comparingInt(Ranked::rank));

        List<String> result = new ArrayList<>(Math.min(limit, matches.size()));
        for (Ranked match : matches.subList(0, Math.min(limit, matches.size()))) {
            result.add(match.id());
        }
        return result;
    }

    /**
     * The single id a typed query means: itself when the player tab-completed a real id, otherwise
     * the best suggestion for it, or {@code null} when this scan holds nothing like it.
     *
     * <p>Resolving loosely is deliberate. Tab completion offers full ids, but nobody who types
     * {@code diamond} and hits enter means "no such item" — and the alternative, refusing anything
     * that is not an exact id, would make the command feel broken exactly when it is closest to
     * working.
     */
    public String resolve(String query) {
        if (query != null && totalsByItem.containsKey(query.trim())) {
            return query.trim();
        }
        List<String> best = suggest(query, 1);
        return best.isEmpty() ? null : best.getFirst();
    }

    // =========================================================================
    // Matching
    // =========================================================================

    /**
     * How well {@code query} matches {@code id}: lower is better, {@link #NO_MATCH} for no match.
     *
     * <p>Four tiers, in the order somebody typing expects them: the exact item (0), an id whose
     * path — or whole {@code namespace:path}, so that typing {@code create:} narrows to one mod —
     * starts with what was typed (1), an id whose path contains it (2), and one where only the full
     * {@code namespace:path} contains it (3). Case, surrounding space and a leading
     * {@code minecraft:} are all ignored, so {@code DIAMOND }, {@code diamond} and
     * {@code minecraft:diamond} are one query — while a foreign namespace survives, because the
     * namespace is only stripped from the <em>query</em> when it is Minecraft's own.
     *
     * <p>Deliberately not Brigadier's own prefix filter, which compares against the whole id and so
     * would offer nothing at all for {@code diam} — every vanilla id starts with {@code minecraft:}.
     */
    public static int rank(String query, String id) {
        if (id == null) {
            return NO_MATCH;
        }
        String needle = normalise(query);
        if (needle.isEmpty()) {
            return 0;
        }
        String full = id.toLowerCase(Locale.ROOT);
        String path = pathOf(full);

        if (path.equals(needle) || full.equals(needle)) {
            return 0;
        }
        if (path.startsWith(needle) || full.startsWith(needle)) {
            return 1;
        }
        if (path.contains(needle)) {
            return 2;
        }
        return full.contains(needle) ? 3 : NO_MATCH;
    }

    /** Lowercased, trimmed, and with Minecraft's own namespace dropped — see {@link #rank}. */
    private static String normalise(String query) {
        if (query == null) {
            return "";
        }
        String trimmed = query.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("minecraft:") ? trimmed.substring("minecraft:".length()) : trimmed;
    }

    private static String pathOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static Comparator<String> byTotalDescendingThenId(Map<String, Long> totals) {
        return Comparator.<String>comparingLong(id -> totals.getOrDefault(id, 0L)).reversed()
                .thenComparing(Comparator.naturalOrder());
    }

    private static boolean isPos(int[] pos) {
        return pos != null && pos.length == 3;
    }
}
