package dev.hoardkeeper.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuSlotCountsTest {

    @Test
    void knowsTheVanillaChestMenus() {
        assertEquals(9, MenuSlotCounts.expectedFor("minecraft:generic_9x1"));
        assertEquals(27, MenuSlotCounts.expectedFor("minecraft:generic_9x3"));
        assertEquals(54, MenuSlotCounts.expectedFor("minecraft:generic_9x6"));
        assertEquals(27, MenuSlotCounts.expectedFor("minecraft:shulker_box"));
    }

    @Test
    void returnsMinusOneForUnknownMenus() {
        assertEquals(-1, MenuSlotCounts.expectedFor("someplugin:fancy_shop"));
    }

    @Test
    void derivesSlotCountFromPacketSizeMinusPlayerInventory() {
        // 54 container slots + 27 inventory + 9 hotbar
        assertEquals(54, MenuSlotCounts.resolve(90));
        assertEquals(27, MenuSlotCounts.resolve(63));
    }

    @Test
    void trustsTheDerivedCountWhenThePluginMenuLiesAboutItsType() {
        // Claims to be a double chest but only sends 27 container slots.
        assertEquals(27, MenuSlotCounts.resolve(63));
    }

    @Test
    void reportsTheDisagreementSoTheCallerCanLogIt() {
        // The derived count still wins — this only makes the mismatch visible instead of silent.
        assertTrue(MenuSlotCounts.mismatches("minecraft:generic_9x6", 27));
        assertFalse(MenuSlotCounts.mismatches("minecraft:generic_9x6", 54));
    }

    @Test
    void anUnknownMenuTypeCannotMismatchBecauseItClaimsNothing() {
        assertFalse(MenuSlotCounts.mismatches("someplugin:fancy_shop", 27));
        assertFalse(MenuSlotCounts.mismatches(null, 27));
    }

    @Test
    void rejectsPacketsTooSmallToContainAPlayerInventory() {
        assertEquals(-1, MenuSlotCounts.resolve(36));
        assertEquals(-1, MenuSlotCounts.resolve(10));
    }
}
