package dev.hoardkeeper.deposit;

import dev.hoardkeeper.deposit.DepositRules.Signature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 2026-09-28-singleplayer-deposit-design.md §3 and §4. Keys are plain strings here. */
class DepositRulesTest {

    private static DepositStack<String> cobble(int count) {
        return DepositStack.item("cobblestone", count, 64);
    }

    private static List<DepositStack<String>> fullOf(String key) {
        return new ArrayList<>(Collections.nCopies(DepositRules.SHULKER_SLOTS, DepositStack.item(key, 64, 64)));
    }

    @SafeVarargs
    private static List<DepositStack<String>> inventory(DepositStack<String>... stacks) {
        List<DepositStack<String>> main = new ArrayList<>(Collections.nCopies(27, null));
        for (int i = 0; i < stacks.length; i++) {
            main.set(i, stacks[i]);
        }
        return main;
    }

    @Test
    void aLooseStackMovesWhereTheSameItemIs() {
        Set<Signature<String>> held = DepositRules.held(List.of(cobble(12)));
        assertEquals(List.of(0), DepositRules.slotsToMove(inventory(cobble(64)), held));
    }

    /** "The same item" is the key, which the client makes carry the components. */
    @Test
    void aDifferentKeyIsADifferentItem() {
        Set<Signature<String>> held = DepositRules.held(List.of(DepositStack.item("diamond{name=Bob}", 1, 64)));
        assertEquals(List.of(), DepositRules.slotsToMove(inventory(DepositStack.item("diamond", 3, 64)), held));
    }

    @Test
    void nothingMovesIntoAContainerThatHoldsNoneOfIt() {
        Set<Signature<String>> held = DepositRules.held(List.of(DepositStack.item("dirt", 64, 64)));
        assertEquals(List.of(), DepositRules.slotsToMove(inventory(cobble(64)), held));
    }

    @Test
    void everyMatchingSlotMovesInInventoryOrder() {
        Set<Signature<String>> held = DepositRules.held(List.of(cobble(1), DepositStack.item("dirt", 1, 64)));
        List<DepositStack<String>> main = inventory(cobble(64), DepositStack.item("sand", 64, 64), null,
                DepositStack.item("dirt", 5, 64), cobble(3));
        assertEquals(List.of(0, 3, 4), DepositRules.slotsToMove(main, held));
    }

    @Test
    void anythingWithDurabilityNeverMoves() {
        Set<Signature<String>> held = DepositRules.held(List.of(DepositStack.damageable("iron_pickaxe")));
        assertEquals(List.of(), DepositRules.slotsToMove(inventory(DepositStack.damageable("iron_pickaxe")), held));
        assertEquals(Optional.empty(), DepositRules.movable(DepositStack.damageable("iron_pickaxe")));
    }

    @Test
    void aBundleNeverMoves() {
        assertEquals(Optional.empty(), DepositRules.movable(DepositStack.withContents("bundle")));
        assertEquals(Set.of(), DepositRules.held(List.of(DepositStack.withContents("bundle"))),
                "a bundle in a chest is not a reason for anything to go there");
    }

    @Test
    void aFullBoxOfOneItemMovesToABoxWithTheSameContents() {
        DepositStack<String> redDirt = DepositStack.shulkerBox("red_shulker_box", fullOf("dirt"));
        DepositStack<String> blueDirt = DepositStack.shulkerBox("blue_shulker_box", fullOf("dirt"));
        Set<Signature<String>> held = DepositRules.held(List.of(blueDirt));
        assertEquals(Set.of(Signature.boxOf("dirt"), Signature.item("dirt")), held,
                "a box full of dirt answers to boxes of dirt and to loose dirt");
        assertEquals(List.of(0), DepositRules.slotsToMove(inventory(redDirt), held),
                "the colour is not part of the signature");
    }

    @Test
    void aBoxWithAnyGapStays() {
        List<DepositStack<String>> contents = fullOf("dirt");
        contents.remove(26);
        assertEquals(Optional.empty(), DepositRules.movable(DepositStack.shulkerBox("shulker_box", contents)),
                "26 of 27 slots");
    }

    @Test
    void aBoxWithAPartStackStays() {
        List<DepositStack<String>> contents = fullOf("dirt");
        contents.set(5, DepositStack.item("dirt", 63, 64));
        assertEquals(Optional.empty(), DepositRules.movable(DepositStack.shulkerBox("shulker_box", contents)));
    }

    @Test
    void aBoxOfMixedItemsStays() {
        List<DepositStack<String>> contents = fullOf("dirt");
        contents.set(5, DepositStack.item("sand", 64, 64));
        assertEquals(Optional.empty(), DepositRules.movable(DepositStack.shulkerBox("shulker_box", contents)));
    }

    @Test
    void anEmptyBoxStays() {
        assertEquals(Optional.empty(), DepositRules.movable(DepositStack.shulkerBox("shulker_box", List.of())));
    }

    /** A full box of unstackables — 27 swords would be damageable anyway, but 27 cakes are not. */
    @Test
    void aBoxOfOneUnstackableItemPerSlotIsFull() {
        List<DepositStack<String>> contents = new ArrayList<>(
                Collections.nCopies(DepositRules.SHULKER_SLOTS, DepositStack.item("cake", 1, 1)));
        assertEquals(Optional.of(Signature.boxOf("cake")),
                DepositRules.movable(DepositStack.shulkerBox("shulker_box", contents)));
    }

    /** Owner, 2026-09-29: a chest holding only a box of coal may take loose coal beside it. */
    @Test
    void looseItemsFollowABoxOfOnlyThem() {
        DepositStack<String> boxOfCobble = DepositStack.shulkerBox("shulker_box", fullOf("cobblestone"));
        assertEquals(List.of(0), DepositRules.slotsToMove(inventory(cobble(64)),
                DepositRules.held(List.of(boxOfCobble))), "a full box of cobblestone");
        List<DepositStack<String>> some = new ArrayList<>(fullOf("cobblestone").subList(0, 3));
        assertEquals(List.of(0), DepositRules.slotsToMove(inventory(cobble(64)),
                DepositRules.held(List.of(DepositStack.shulkerBox("shulker_box", some)))),
                "a box holding only cobblestone, not full");
    }

    @Test
    void aMixedBoxInTheContainerIsNoTargetForLooseItems() {
        List<DepositStack<String>> mixed = List.of(DepositStack.item("cobblestone", 64, 64), DepositStack.item("dirt", 64, 64));
        assertEquals(List.of(), DepositRules.slotsToMove(inventory(cobble(64)),
                DepositRules.held(List.of(DepositStack.shulkerBox("shulker_box", mixed)))));
    }

    /** A full box of coal goes where loose coal is, as well as where a box of coal is. */
    @Test
    void aFullBoxFollowsLooseItemsOfItsOneItem() {
        DepositStack<String> boxOfCoal = DepositStack.shulkerBox("shulker_box", fullOf("coal"));
        assertEquals(List.of(0), DepositRules.slotsToMove(inventory(boxOfCoal),
                DepositRules.held(List.of(DepositStack.item("coal", 12, 64)))));
        assertEquals(List.of(), DepositRules.slotsToMove(inventory(boxOfCoal),
                DepositRules.held(List.of(DepositStack.item("charcoal", 12, 64)))), "only its own item");
        assertTrue(DepositRules.accepts(Set.of(Signature.item("coal")), Signature.boxOf("coal")));
        assertTrue(DepositRules.accepts(Set.of(Signature.boxOf("coal")), Signature.boxOf("coal")));
        assertFalse(DepositRules.accepts(Set.of(Signature.boxOf("coal")), Signature.item("coal")));
    }

    /** A box of only dirt that is not full takes loose dirt, but is no target for a full box. */
    @Test
    void aPartlyFilledBoxOfOneItemTakesLooseItemsOnly() {
        List<DepositStack<String>> contents = fullOf("dirt");
        contents.remove(0);
        assertEquals(Set.of(Signature.item("dirt")),
                DepositRules.held(List.of(DepositStack.shulkerBox("shulker_box", contents))));
    }

    @Test
    void carriedIsEverythingThatCouldMove() {
        DepositStack<String> box = DepositStack.shulkerBox("shulker_box", fullOf("dirt"));
        assertEquals(Set.of(Signature.item("cobblestone"), Signature.boxOf("dirt")),
                DepositRules.carried(inventory(cobble(1), DepositStack.damageable("sword"), box, null,
                        DepositStack.withContents("bundle"))));
    }

    @Test
    void emptyAndNullStacksAreIgnored() {
        assertEquals(Optional.empty(), DepositRules.movable(null));
        assertEquals(Optional.empty(), DepositRules.movable(cobble(0)));
        assertEquals(Set.of(), DepositRules.held(java.util.Arrays.asList(null, cobble(0))));
    }
}
