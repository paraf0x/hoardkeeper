package dev.hoardkeeper.tooltip;

import dev.hoardkeeper.search.SearchText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TooltipTextTest {

    @Test
    void groupsThousandsThroughTheSameHelperTheSearchLineUses() {
        String line = TooltipText.line(3104, 6);

        // SearchText.count is the one place thousands grouping is decided; the tooltip must read
        // the same number the search does rather than formatting it a second way.
        assertTrue(line.contains(SearchText.count(3104)));
        assertEquals("3,104", SearchText.count(3104));
    }

    @Test
    void saysChestSingularForOneContainer() {
        String line = TooltipText.line(192, 1);

        assertTrue(line.contains("192 in 1 chest"));
        assertFalse(line.contains("1 chests"));
    }

    @Test
    void saysChestsPluralForSeveralContainers() {
        String line = TooltipText.line(3104, 6);

        assertTrue(line.contains("3,104 in 6 chests"));
    }

    @Test
    void saysChestsPluralForZeroContainersToo() {
        // A defensive grammar check on the plural rule itself: English says "0 chests", not
        // "0 chest". Real callers never reach this with total > 0 and containers == 0 -- the search
        // index cannot produce that combination -- but the wording function does not know that and
        // must not get the grammar wrong if it is ever called that way.
        String line = TooltipText.line(5, 0);

        assertTrue(line.contains("0 chests"));
    }

    @Test
    void saysNoneRatherThanZeroWhenTheStorageHoldsNoneOfTheItem() {
        String line = TooltipText.line(0, 0);

        assertTrue(line.contains("none"));
        // "0" must not appear anywhere in the zero-total line: this is "we have none", a positive
        // fact, not a number that happens to be zero.
        assertFalse(line.contains("0"));
    }

    @Test
    void neverSaysNotMeasured() {
        // The distinction between "we have none" (this method) and "I do not know" (StorageTooltip
        // never calling this method at all, because StorageIndexCache.current() came back null) is
        // the whole point. This class has no wording at all for the second case.
        assertFalse(TooltipText.line(0, 0).toLowerCase().contains("not measured"));
        assertFalse(TooltipText.line(3104, 6).toLowerCase().contains("not measured"));
    }

    @Test
    void labelIsGreyAndNumbersAreWhiteWithTheSameColourCodesTheModUsesElsewhere() {
        String line = TooltipText.line(3104, 6);

        assertTrue(line.startsWith("§7Storage:"), "the label reads grey, like the rest of the mod's quiet lines");
        assertTrue(line.contains("§f3,104 in 6 chests"), "the numbers and unit read white");
    }

    @Test
    void theNoneLineIsAlsoGreyLabelWhiteValue() {
        String line = TooltipText.line(0, 0);

        assertTrue(line.startsWith("§7Storage:"));
        assertTrue(line.contains("§fnone"));
    }
}
