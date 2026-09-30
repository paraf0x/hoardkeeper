package dev.hoardkeeper.observe;

import dev.hoardkeeper.model.ScannedContainer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list of containers a storage actually registered — the answer to "does this chest belong
 * here at all?". Pure: takes rows, returns a position set, no Minecraft and no files.
 */
class RegisteredContainersTest {

    private static ScannedContainer at(int x, int y, int z) {
        ScannedContainer c = new ScannedContainer();
        c.pos = new int[]{x, y, z};
        return c;
    }

    @Test
    void everyPositionAScanFiledIsRegistered() {
        Set<Long> known = RegisteredContainers.positionsOf(List.of(at(10, -60, 20), at(11, -60, 20)));
        assertTrue(RegisteredContainers.holds(known, new int[]{10, -60, 20}));
        assertTrue(RegisteredContainers.holds(known, new int[]{11, -60, 20}));
    }

    @Test
    void aPositionNoScanRecordedIsNotRegistered() {
        Set<Long> known = RegisteredContainers.positionsOf(List.of(at(10, -60, 20)));
        assertFalse(RegisteredContainers.holds(known, new int[]{10, -60, 21}));
    }

    /**
     * The packing must separate all three axes. A scheme that collided would silently register a
     * container the scan never saw, which is the exact failure this class exists to prevent.
     */
    @Test
    void positionsAreDistinguishedOnEveryAxis() {
        Set<Long> known = RegisteredContainers.positionsOf(List.of(at(10, -60, 20)));
        assertFalse(RegisteredContainers.holds(known, new int[]{-60, 10, 20}), "x and y swapped");
        assertFalse(RegisteredContainers.holds(known, new int[]{20, -60, 10}), "x and z swapped");
        assertFalse(RegisteredContainers.holds(known, new int[]{10, -59, 20}), "one block up");
    }

    @Test
    void negativeAndFarAwayCoordinatesSurviveThePacking() {
        Set<Long> known = RegisteredContainers.positionsOf(
                List.of(at(-29_999_999, -64, 29_999_999), at(29_999_999, 319, -29_999_999)));
        assertTrue(RegisteredContainers.holds(known, new int[]{-29_999_999, -64, 29_999_999}));
        assertTrue(RegisteredContainers.holds(known, new int[]{29_999_999, 319, -29_999_999}));
        assertEquals(2, known.size());
    }

    /**
     * A hand-edited or older-version containers.jsonl must not fail the whole build over one bad
     * line — the same guard the search index and the overlay already apply.
     */
    @Test
    void rowsWithoutAUsablePositionAreSkipped() {
        ScannedContainer noPos = new ScannedContainer();
        ScannedContainer shortPos = new ScannedContainer();
        shortPos.pos = new int[]{1, 2};
        assertEquals(Set.of(), RegisteredContainers.positionsOf(List.of(noPos, shortPos)));
    }

    @Test
    void anUnusablePositionIsNeverRegistered() {
        Set<Long> known = RegisteredContainers.positionsOf(List.of(at(10, -60, 20)));
        assertFalse(RegisteredContainers.holds(known, null));
        assertFalse(RegisteredContainers.holds(known, new int[]{10, -60}));
    }
}
