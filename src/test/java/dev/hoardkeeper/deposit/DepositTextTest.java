package dev.hoardkeeper.deposit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepositTextTest {

    @Test
    void theLabelNamesTheFirstItemItsStacksAndCountsTheRest() {
        assertEquals("cobblestone", DepositText.label(List.of("minecraft:cobblestone"), 1));
        assertEquals("cobblestone ×3", DepositText.label(List.of("minecraft:cobblestone"), 3));
        assertEquals("cobblestone ×2 +2", DepositText.label(List.of("minecraft:cobblestone", "minecraft:dirt",
                "create:andesite_alloy"), 2));
        assertEquals("create:andesite_alloy", DepositText.label(List.of("create:andesite_alloy"), 1));
        assertEquals("", DepositText.label(List.of(), 0));
    }

    @Test
    void aDepositNamesWhatWentInAndTheRunningTotal() {
        String line = DepositText.depositedInto(3, 12, 4);
        assertTrue(line.contains("+3§f stacks into this container"), line);
        assertTrue(line.contains("12§f moved"), line);
        assertTrue(line.contains("4§f lit"), line);
        assertFalse(DepositText.depositedInto(1, 1, 0).contains("lit"));
        assertTrue(DepositText.allPutAway(5).contains("all put away"));
        assertEquals("+2", DepositText.flash(2));
    }

    @Test
    void theModeNamesTheNewClick() {
        assertTrue(DepositText.on().contains("Sneak-right-click"));
        assertTrue(DepositText.on().contains("empty hand"));
    }

    @Test
    void theBarLeavesTheDistanceOutWhenNothingIsLit() {
        assertTrue(DepositText.bar(3, 2, 6).contains("nearest §f6 m"));
        assertFalse(DepositText.bar(3, 0, 6).contains("nearest"));
        assertFalse(DepositText.bar(3, 2, -1).contains("nearest"));
    }

    @Test
    void oneStackIsSingular() {
        assertTrue(DepositText.clicked(1).contains("1§f stack into"));
        assertTrue(DepositText.clicked(4).contains("4§f stacks into"));
        assertTrue(DepositText.clicked(0).contains("nothing you carry"));
    }

    @Test
    void offServerPointsAtTheServersOwnCommand() {
        assertTrue(DepositText.notSingleplayer().contains("singleplayer only"));
    }
}
