package dev.hoardkeeper.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTextTest {

    @Test
    void dropsMinecraftsOwnNamespaceButKeepsAForeignOne() {
        assertEquals("diamond", SearchText.shortId("minecraft:diamond"));
        assertEquals("create:andesite_alloy", SearchText.shortId("create:andesite_alloy"));
        assertEquals("diamond", SearchText.shortId("diamond"));
    }

    @Test
    void groupsBigNumbersSoAWallOfDigitsStaysReadable() {
        assertEquals("12,480", SearchText.count(12480));
        assertEquals("7", SearchText.count(7));
    }

    @Test
    void actionBarCarriesTheItemTheContainersLeftAndTheCountdown() {
        String bar = SearchText.actionBar("minecraft:diamond", 3, 1204, 24);

        assertTrue(bar.contains("diamond"));
        assertTrue(bar.contains("3"));
        assertTrue(bar.contains("1,204"));
        assertTrue(bar.contains("24s"));
        assertFalse(bar.contains("minecraft:"));
    }

    @Test
    void actionBarSaysContainerRatherThanContainersForTheLastOne() {
        assertTrue(SearchText.actionBar("minecraft:diamond", 1, 9, 5).contains("container §7"));
        assertTrue(SearchText.actionBar("minecraft:diamond", 2, 9, 5).contains("containers §7"));
    }

    @Test
    void foundNamesTheTotalAndTheContainersWithoutInventingAScan() {
        String line = SearchText.found("minecraft:diamond", 12480, 37, 37);

        assertTrue(line.contains("12,480"));
        assertTrue(line.contains("diamond"));
        assertTrue(line.contains("37"));
        // The search reads the measured map, which has no session identity to name. The line used
        // to print one and it was the dimension's directory name.
        assertFalse(line.contains("scan "), "the map is not a scan and must not be called one");
        assertTrue(line.contains("measured"));
    }

    @Test
    void anEmptyMapSaysSoWithoutNamingASession() {
        assertFalse(SearchText.emptyScan().contains("scan"));
    }

    @Test
    void foundSaysSoWhenOnlyTheNearestContainersAreLit() {
        String all = SearchText.found("minecraft:cobblestone", 90000, 300, 300);
        String capped = SearchText.found("minecraft:cobblestone", 90000, 300, 128);

        assertFalse(all.contains("nearest"));
        assertTrue(capped.contains("nearest"));
        assertTrue(capped.contains("128"));
    }

    @Test
    void notFoundEchoesWhatWasTypedWithoutColourCodesFromIt() {
        assertTrue(SearchText.notFound("emrald").contains("emrald"));
    }

    @Test
    void theHeldItemMissDoesNotOfferTabCompletionOrSuggestATypo() {
        String line = SearchText.notHere("minecraft:oak_log");

        assertTrue(line.contains("oak_log"));
        assertFalse(line.contains("minecraft:"));
        // There was no typing, so there is nothing to have mistyped and nothing to complete.
        assertFalse(line.contains("Tab"));
        assertFalse(line.contains("Nothing like"));
    }

    @Test
    void pressingTheKeyWithNothingInHandSaysWhatToDoAboutIt() {
        assertTrue(SearchText.emptyHand().contains("Hold"));
    }

    @Test
    void allVisitedNamesTheItem() {
        assertTrue(SearchText.allVisited("minecraft:diamond", 6).contains("diamond"));
        assertTrue(SearchText.allVisited("minecraft:diamond", 6).contains("6"));
    }

    @Test
    void elsewhereGivesTheDistanceAndWhatToDoAboutIt() {
        String line = SearchText.elsewhere(2140);

        assertTrue(line.contains("2,140"));
        assertTrue(line.contains("/hoard"));
    }

    @Test
    void allGoneNamesTheItemAndDoesNotClaimTheContainersWereOpened() {
        String line = SearchText.allGone("minecraft:diamond");

        assertTrue(line.contains("diamond"));
        assertFalse(line.contains("last of"));
    }

    @Test
    void suggestionTooltipShowsTheTotalAndTheContainerCount() {
        String tooltip = SearchText.suggestionTooltip(12480, 37);

        assertTrue(tooltip.contains("12,480"));
        assertTrue(tooltip.contains("37"));
        // Tooltips are drawn by the completion box, which renders no section codes.
        assertFalse(tooltip.contains("§"));
    }
}
