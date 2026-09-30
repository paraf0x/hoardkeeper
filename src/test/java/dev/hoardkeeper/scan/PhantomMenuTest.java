package dev.hoardkeeper.scan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhantomMenuTest {

    @AfterEach
    void reset() {
        PhantomMenu.forget();
    }

    @Test
    void aChestsPlayerSlotsAreTheMainInventoryThenTheHotbar() {
        assertEquals(-1, PhantomMenu.inventoryIndex(27, 26));   // the chest's own last slot
        assertEquals(9, PhantomMenu.inventoryIndex(27, 27));
        assertEquals(35, PhantomMenu.inventoryIndex(27, 53));
        assertEquals(0, PhantomMenu.inventoryIndex(27, 54));
        assertEquals(8, PhantomMenu.inventoryIndex(27, 62));
        assertEquals(-1, PhantomMenu.inventoryIndex(27, 63));
        assertEquals(0, PhantomMenu.inventoryIndex(54, 81));    // a double chest
        assertEquals(-1, PhantomMenu.inventoryIndex(27, -1));   // the carried item
    }

    @Test
    void onlyTheRememberedMenusIdMaps() {
        assertEquals(-1, PhantomMenu.inventorySlot(5, 54));
        PhantomMenu.opened(5, 27);
        assertEquals(0, PhantomMenu.inventorySlot(5, 54));
        assertEquals(-1, PhantomMenu.inventorySlot(6, 54));
        assertEquals(-1, PhantomMenu.inventorySlot(0, 54));
        PhantomMenu.forget();
        assertEquals(-1, PhantomMenu.inventorySlot(5, 54));
    }

    @Test
    void theNextMenuReplacesIt() {
        PhantomMenu.opened(5, 27);
        PhantomMenu.opened(6, 54);
        assertEquals(-1, PhantomMenu.inventorySlot(5, 54));
        assertEquals(0, PhantomMenu.inventorySlot(6, 81));
    }
}
