package dev.hoardkeeper.deposit;

import dev.hoardkeeper.deposit.DepositRules.Signature;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 2026-09-28-singleplayer-deposit-design.md §6, remembered contents and the index hint. */
class DepositMemoryTest {

    private static final Signature<String> COBBLE = Signature.item("cobblestone");
    private static final Signature<String> DIRT = Signature.item("dirt");

    @Test
    void aContainerNeverVisitedIsAlwaysWorthOpening() {
        DepositMemory<String> memory = new DepositMemory<>();
        assertTrue(memory.worthOpening(1L, Set.of()));
        assertNull(memory.stillWanted(1L, Set.of(COBBLE)), "the index has to answer for it");
    }

    @Test
    void aVisitedContainerIsReopenedOnlyForSomethingItHolds() {
        DepositMemory<String> memory = new DepositMemory<>();
        memory.visited(1L, Set.of(COBBLE), Set.of());
        assertFalse(memory.worthOpening(1L, Set.of(DIRT)));
        assertFalse(memory.worthOpening(1L, Set.of()), "nothing carried, nothing to do");
        assertTrue(memory.worthOpening(1L, Set.of(DIRT, COBBLE)), "picked up more cobblestone");
        assertEquals(Boolean.TRUE, memory.stillWanted(1L, Set.of(COBBLE)));
    }

    @Test
    void aFullContainerStaysClosedUntilForgotten() {
        DepositMemory<String> memory = new DepositMemory<>();
        memory.visited(1L, Set.of(COBBLE), Set.of(COBBLE));
        assertFalse(memory.worthOpening(1L, Set.of(COBBLE)));
        assertEquals(Boolean.FALSE, memory.stillWanted(1L, Set.of(COBBLE)));
        memory.forget(1L);
        assertTrue(memory.worthOpening(1L, Set.of(COBBLE)), "the player opened it by hand");
    }

    /** Full for one item is not full for another: the container still takes what else it holds. */
    @Test
    void fullIsPerItem() {
        DepositMemory<String> memory = new DepositMemory<>();
        memory.visited(1L, Set.of(COBBLE, DIRT), Set.of(COBBLE));
        assertFalse(memory.worthOpening(1L, Set.of(COBBLE)));
        assertTrue(memory.worthOpening(1L, Set.of(COBBLE, DIRT)), "dirt still fits");
    }

    /** A chest remembered as holding loose coal is worth opening for a full box of coal. */
    @Test
    void aBoxOfCoalIsWorthALooseCoalChest() {
        DepositMemory<String> memory = new DepositMemory<>();
        memory.visited(1L, Set.of(Signature.item("coal")), Set.of());
        assertTrue(memory.worthOpening(1L, Set.of(Signature.boxOf("coal"))));
    }

    @Test
    void clearForgetsEverything() {
        DepositMemory<String> memory = new DepositMemory<>();
        memory.visited(1L, Set.of(COBBLE), Set.of(COBBLE));
        memory.visited(2L, Set.of(DIRT), Set.of());
        memory.clear();
        assertTrue(memory.worthOpening(1L, Set.of()));
        assertTrue(memory.worthOpening(2L, Set.of()));
    }

    @Test
    void theIndexRulesOutOnlyWhatItKnows() {
        assertTrue(DepositMemory.indexRulesOut(Set.of("minecraft:dirt"), Set.of("minecraft:cobblestone")));
        assertFalse(DepositMemory.indexRulesOut(Set.of("minecraft:dirt", "minecraft:cobblestone"),
                Set.of("minecraft:cobblestone")));
        assertFalse(DepositMemory.indexRulesOut(null, Set.of("minecraft:cobblestone")),
                "a container the index does not know is never ruled out");
    }
}
