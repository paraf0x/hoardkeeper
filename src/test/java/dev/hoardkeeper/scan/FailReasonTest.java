package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailReasonTest {

    @Test
    void reasonsDecidedBeforeAnyPacketWasSentAreLocalSkips() {
        assertTrue(FailReason.BLOCKED.isLocalSkip());
        assertTrue(FailReason.GONE.isLocalSkip());
    }

    @Test
    void everyReasonIsDeliberatelyClassifiedByOneOfTheTwoTestsAbove() {
        // OUT_OF_RANGE used to be an enum constant that nothing could ever raise — TargetSelector
        // skips out-of-range candidates without failing them, correctly — kept alive only by a test
        // asserting it was a local skip. This guard replaces it: a reason added later fails here
        // until somebody decides, in one of the two tests above, which side of the split it is on.
        assertEquals(EnumSet.of(FailReason.BLOCKED, FailReason.GONE, FailReason.NO_RESPONSE,
                        FailReason.NO_CONTENT, FailReason.UNEXPECTED_MENU, FailReason.BAD_MENU),
                EnumSet.allOf(FailReason.class));
    }

    @Test
    void everythingElseCountsAsTheServerRefusingUs() {
        assertFalse(FailReason.NO_RESPONSE.isLocalSkip());
        assertFalse(FailReason.NO_CONTENT.isLocalSkip());
        assertFalse(FailReason.UNEXPECTED_MENU.isLocalSkip());
        assertFalse(FailReason.BAD_MENU.isLocalSkip());
    }

    @Test
    void doublingAKindOnlyAffectsChests() {
        assertEquals(ContainerKind.CHEST_DOUBLE, ContainerKind.CHEST.asDouble());
        assertEquals(ContainerKind.TRAPPED_CHEST_DOUBLE, ContainerKind.TRAPPED_CHEST.asDouble());
        assertEquals(ContainerKind.BARREL, ContainerKind.BARREL.asDouble());
        assertEquals("chest_double", ContainerKind.CHEST_DOUBLE.id());
    }
}
