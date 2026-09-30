package dev.hoardkeeper.search;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.ScannedItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchIndexTest {

    @Test
    void sumsOneItemAcrossContainersAndKeepsEachContainersOwnCount() {
        SearchIndex index = SearchIndex.of(List.of(
                container(new int[]{0, 64, 0}, null, "chest", Map.of("minecraft:diamond", 12L)),
                container(new int[]{5, 64, 0}, null, "barrel", Map.of("minecraft:diamond", 30L))));

        assertEquals(42L, index.total("minecraft:diamond"));
        assertEquals(2, index.hits("minecraft:diamond").size());
        assertEquals(30L, index.hits("minecraft:diamond").get(0).count());
        assertEquals(12L, index.hits("minecraft:diamond").get(1).count());
    }

    @Test
    void ordersHitsByCountDescendingSoTheBiggestPileIsFirst() {
        SearchIndex index = SearchIndex.of(List.of(
                container(new int[]{0, 64, 0}, null, "chest", Map.of("minecraft:stone", 1L)),
                container(new int[]{1, 64, 0}, null, "chest", Map.of("minecraft:stone", 500L)),
                container(new int[]{2, 64, 0}, null, "chest", Map.of("minecraft:stone", 64L))));

        List<Long> counts = index.hits("minecraft:stone").stream().map(ContainerHit::count).toList();
        assertEquals(List.of(500L, 64L, 1L), counts);
    }

    @Test
    void carriesBothHalvesOfADoubleChestOnOneHit() {
        SearchIndex index = SearchIndex.of(List.of(
                container(new int[]{0, 64, 0}, new int[]{1, 64, 0}, "chest_double",
                        Map.of("minecraft:diamond", 9L))));

        ContainerHit hit = index.hits("minecraft:diamond").getFirst();
        assertTrue(hit.covers(0, 64, 0));
        assertTrue(hit.covers(1, 64, 0));
        assertFalse(hit.covers(2, 64, 0));
    }

    @Test
    void fallsBackToWalkingTheItemsWhenAContainerCarriesNoPrecomputedTotals() {
        // Written by an older mod version, or a hand-edited file: totals absent, items present.
        ScannedContainer container = new ScannedContainer();
        container.pos = new int[]{0, 64, 0};
        container.kind = "chest";
        container.items = new ArrayList<>(List.of(
                item("minecraft:diamond", 3),
                shulkerHolding("minecraft:diamond", 5)));

        SearchIndex index = SearchIndex.of(List.of(container));

        // Nested contents count: a diamond inside a shulker inside a chest is still a diamond here.
        assertEquals(8L, index.total("minecraft:diamond"));
    }

    @Test
    void ignoresContainersWithoutAUsablePosition() {
        ScannedContainer noPos = new ScannedContainer();
        noPos.kind = "chest";
        noPos.totals = Map.of("minecraft:diamond", 5L);

        assertTrue(SearchIndex.of(List.of(noPos)).isEmpty());
    }

    @Test
    void ignoresZeroAndNegativeCounts() {
        SearchIndex index = SearchIndex.of(List.of(
                container(new int[]{0, 64, 0}, null, "chest", Map.of("minecraft:dirt", 0L))));

        assertTrue(index.isEmpty());
        assertEquals(List.of(), index.hits("minecraft:dirt"));
    }

    @Test
    void listsItemIdsBiggestPileFirst() {
        SearchIndex index = SearchIndex.of(List.of(
                container(new int[]{0, 64, 0}, null, "chest", ordered(
                        "minecraft:dirt", 10L, "minecraft:cobblestone", 900L, "minecraft:diamond", 40L))));

        assertEquals(List.of("minecraft:cobblestone", "minecraft:diamond", "minecraft:dirt"), index.ids());
    }

    // ---- Matching, which is what tab completion is made of -------------------------------

    @Test
    void rankPrefersExactThenPrefixThenSubstring() {
        assertEquals(0, SearchIndex.rank("diamond", "minecraft:diamond"));
        assertEquals(1, SearchIndex.rank("diam", "minecraft:diamond"));
        assertEquals(1, SearchIndex.rank("diamond", "minecraft:diamond_block"));
        assertEquals(2, SearchIndex.rank("block", "minecraft:diamond_block"));
        assertEquals(-1, SearchIndex.rank("emerald", "minecraft:diamond"));
    }

    @Test
    void matchingIgnoresCaseTheNamespaceAndSurroundingSpace() {
        assertEquals(0, SearchIndex.rank("  DIAMOND ", "minecraft:diamond"));
        assertEquals(0, SearchIndex.rank("minecraft:diamond", "minecraft:diamond"));
        // A modded namespace is still reachable by typing it.
        assertEquals(1, SearchIndex.rank("create:", "create:andesite_alloy"));
    }

    @Test
    void aBlankQueryMatchesEverythingSoTheFirstKeystrokeShowsTheBiggestPiles() {
        assertEquals(0, SearchIndex.rank("", "minecraft:anything"));
    }

    @Test
    void suggestsPrefixMatchesAheadOfSubstringMatchesAndBiggerPilesFirst() {
        SearchIndex index = SearchIndex.of(List.of(
                container(new int[]{0, 64, 0}, null, "chest", ordered(
                        "minecraft:diamond", 40L,
                        "minecraft:diamond_block", 900L,
                        "minecraft:block_of_redstone", 5000L,
                        "minecraft:dirt", 1L))));

        // The exactly-typed id wins over the bigger pile: somebody who typed the whole word means it.
        assertEquals(List.of("minecraft:diamond", "minecraft:diamond_block"), index.suggest("diamond", 10));
        // block_of_redstone only contains "block"; the two diamond_* ids start with it or contain it.
        assertEquals(List.of("minecraft:block_of_redstone", "minecraft:diamond_block"),
                index.suggest("block", 10));
    }

    @Test
    void suggestionsAreCapped() {
        SearchIndex index = SearchIndex.of(List.of(
                container(new int[]{0, 64, 0}, null, "chest", ordered(
                        "minecraft:a", 1L, "minecraft:b", 2L, "minecraft:c", 3L))));

        assertEquals(2, index.suggest("", 2).size());
        assertEquals(List.of(), index.suggest("", 0));
    }

    @Test
    void resolveTakesAnExactIdFirstAndOtherwiseTheBestSuggestion() {
        SearchIndex index = SearchIndex.of(List.of(
                container(new int[]{0, 64, 0}, null, "chest", ordered(
                        "minecraft:diamond", 40L, "minecraft:diamond_block", 900L))));

        assertEquals("minecraft:diamond", index.resolve("minecraft:diamond"));
        assertEquals("minecraft:diamond", index.resolve("diamond"));
        assertEquals("minecraft:diamond_block", index.resolve("diamond_b"));
        assertNull(index.resolve("emerald"));
    }

    // ---- helpers -------------------------------------------------------------------------

    private static ScannedContainer container(int[] pos, int[] secondaryPos, String kind,
                                               Map<String, Long> totals) {
        ScannedContainer container = new ScannedContainer();
        container.pos = pos;
        container.secondaryPos = secondaryPos;
        container.kind = kind;
        container.totals = totals;
        return container;
    }

    private static Map<String, Long> ordered(Object... pairs) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }

    private static ScannedItem item(String id, int count) {
        ScannedItem item = new ScannedItem();
        item.id = id;
        item.count = count;
        return item;
    }

    private static ScannedItem shulkerHolding(String id, int count) {
        ScannedItem shulker = item("minecraft:shulker_box", 1);
        shulker.contents = List.of(item(id, count));
        return shulker;
    }
}
