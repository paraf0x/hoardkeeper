package dev.hoardkeeper.observe;

import dev.hoardkeeper.scan.ContainerKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationGateTest {

    private static ObservationGate.Decision decide(ContainerKind kind, int expected, int derived,
                                                    boolean inside) {
        return ObservationGate.decide(kind, expected, derived, inside, true, true, false);
    }

    private static ObservationGate.Decision decide(ContainerKind kind, int expected, int derived,
                                                    boolean inside, boolean registered,
                                                    boolean anywhere) {
        return ObservationGate.decide(kind, expected, derived, inside, registered, true, anywhere);
    }

    @Test
    void recordsAnOrdinaryChestInsideAScannedArea() {
        assertTrue(decide(ContainerKind.CHEST, 27, 27, true).allowed());
    }

    @Test
    void refusesABlockThatIsNotAContainerThisModScans() {
        // An ender chest is not a ChestBlock: its contents belong to the player, not the base.
        assertEquals(ObservationGate.Refusal.NOT_A_CONTAINER,
                decide(null, 27, 27, true).reason());
    }

    @Test
    void refusesAMenuTypeTheScannerDoesNotUnderstand() {
        // A shop or lock prompt opened by right-clicking a chest.
        assertEquals(ObservationGate.Refusal.UNKNOWN_MENU,
                decide(ContainerKind.CHEST, 0, 27, true).reason());
    }

    @Test
    void refusesWhenTheDerivedSlotCountIsNotPositive() {
        assertEquals(ObservationGate.Refusal.SLOT_COUNT_MISMATCH,
                decide(ContainerKind.CHEST, 27, 0, true).reason());
    }

    @Test
    void refusesOutsideEveryScannedArea() {
        // Looting a bastion is not information the group wants in the upload server.
        assertEquals(ObservationGate.Refusal.OUTSIDE_SCANNED_AREA,
                decide(ContainerKind.CHEST, 27, 27, false).reason());
    }

    @Test
    void observeAnywhereLiftsTheAreaRule() {
        assertTrue(ObservationGate.decide(ContainerKind.CHEST, 27, 27, false, false, true, true).allowed());
    }

    @Test
    void aDoubleChestWhoseOtherHalfWasBrokenIsFiledAsASingle() {
        // 27 container slots under a block that says double: the partner went while the GUI was open.
        ObservationGate.Decision d = decide(ContainerKind.CHEST_DOUBLE, 54, 27, true);
        assertTrue(d.allowed());
        assertEquals(ContainerKind.CHEST, d.resolvedKind());
    }

    @Test
    void aSingleChestClaimingFiftyFourSlotsIsRefused() {
        // Nothing legitimate produces this; a plugin is lying about the menu.
        assertEquals(ObservationGate.Refusal.SLOT_COUNT_MISMATCH,
                decide(ContainerKind.CHEST, 27, 54, true).reason());
    }

    @Test
    void disabledRefusesEverything() {
        assertEquals(ObservationGate.Refusal.DISABLED,
                ObservationGate.decide(ContainerKind.CHEST, 27, 27, true, true, false, false).reason());
    }

    // ---- Registered containers: what actually belongs to the storage ----

    /**
     * The clause this whole check exists for. Standing inside a scanned storage and opening a
     * shulker box you just put down, or a chest somebody added since the last scan, is not a
     * content update for that storage — nothing registered it, so there is nothing to update.
     */
    @Test
    void refusesAContainerNoScanEverRegistered() {
        assertEquals(ObservationGate.Refusal.NOT_A_REGISTERED_CONTAINER,
                decide(ContainerKind.CHEST, 27, 27, true, false, false).reason());
    }

    @Test
    void recordsAContainerTheScanRegistered() {
        assertTrue(decide(ContainerKind.CHEST, 27, 27, true, true, false).allowed());
    }

    /**
     * {@code passiveObserveAnywhere} is the one escape hatch, and it lifts both location clauses:
     * somebody who turned it on asked for every container they open, registered or not.
     */
    @Test
    void observeAnywhereAlsoLiftsTheRegistrationRequirement() {
        assertTrue(decide(ContainerKind.CHEST, 27, 27, false, false, true).allowed());
    }

    /**
     * Ordering is for the log reader, not for the outcome: both refuse. "Outside every scanned
     * area" is the more useful thing to be told, because the other reason is true of every
     * container out there and says nothing.
     */
    @Test
    void beingOutsideEveryAreaIsReportedBeforeBeingUnregistered() {
        assertEquals(ObservationGate.Refusal.OUTSIDE_SCANNED_AREA,
                decide(ContainerKind.CHEST, 27, 27, false, false, false).reason());
    }
}
