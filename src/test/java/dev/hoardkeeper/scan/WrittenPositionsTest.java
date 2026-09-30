package dev.hoardkeeper.scan;

import dev.hoardkeeper.model.ScannedContainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrittenPositionsTest {

    private static ContainerCandidate single(int x, int y, int z) {
        return new ContainerCandidate(new int[]{x, y, z}, null, ContainerKind.CHEST);
    }

    private static ContainerCandidate doubleChest(int[] a, int[] b) {
        return new ContainerCandidate(a, b, ContainerKind.CHEST_DOUBLE);
    }

    private static ScannedContainer row(int[] pos, int[] secondaryPos) {
        ScannedContainer c = new ScannedContainer();
        c.pos = pos;
        c.secondaryPos = secondaryPos;
        return c;
    }

    @Test
    void anEmptySetCoversNothing() {
        WrittenPositions written = new WrittenPositions();

        assertTrue(written.isEmpty());
        assertFalse(written.covers(single(10, 64, -20)));
        assertFalse(written.covers(doubleChest(new int[]{0, 0, 0}, new int[]{0, 0, 1})));
    }

    @Test
    void matchesASinglePositionItWasGiven() {
        WrittenPositions written = new WrittenPositions();
        written.add(new int[]{10, 64, -20}, null);

        assertTrue(written.covers(single(10, 64, -20)));
        assertFalse(written.covers(single(10, 64, -21)));
        assertFalse(written.covers(single(11, 64, -20)));
        assertFalse(written.covers(single(10, 65, -20)));
    }

    @Test
    void matchesADoubleChestOnEitherHalfRegardlessOfWhichIsCanonical() {
        // The live-scan duplicate: a single chest at A is scanned and written; someone places the
        // adjacent chest at B; the next sweep discards the SCANNED single and registers a fresh
        // PENDING double over A+B. Matching on either half is what stops A being appended twice.
        int[] a = {100, 64, 100};
        int[] b = {101, 64, 100};

        WrittenPositions written = new WrittenPositions();
        written.add(a, null);

        assertTrue(written.covers(doubleChest(a, b)), "matched on the canonical half");
        assertTrue(written.covers(doubleChest(b, a)), "matched on the secondary half");
    }

    @Test
    void recordsBothHalvesOfADoubleSoEitherHalfIsRecognisedLater() {
        int[] a = {100, 64, 100};
        int[] b = {100, 64, 101};

        WrittenPositions written = new WrittenPositions();
        written.add(a, b);

        assertTrue(written.covers(single(100, 64, 100)));
        assertTrue(written.covers(single(100, 64, 101)));
        assertEquals(2, written.size());
    }

    @Test
    void loadsPositionsFromScannedContainerRows() {
        WrittenPositions written = WrittenPositions.of(List.of(
                row(new int[]{1, 2, 3}, null),
                row(new int[]{10, 64, -20}, new int[]{11, 64, -20})));

        assertEquals(3, written.size());
        assertTrue(written.covers(single(1, 2, 3)));
        assertTrue(written.covers(single(11, 64, -20)));
        assertFalse(written.covers(single(12, 64, -20)));
    }

    @Test
    void ignoresRowsAndPositionsThatAreNotUsableCoordinates() {
        // A torn last line after a crash deserialises into a row with no position at all; it must
        // be skipped, never turned into a bogus entry that shadows a real chest.
        WrittenPositions written = WrittenPositions.of(List.of(
                row(null, null),
                row(new int[]{1, 2}, new int[]{9, 9, 9, 9})));

        assertTrue(written.isEmpty());
    }

    @Test
    void handlesNegativeAndExtremeCoordinatesWithoutColliding() {
        WrittenPositions written = new WrittenPositions();
        written.add(new int[]{-3_000_000, -64, 2_999_999}, null);

        assertTrue(written.covers(single(-3_000_000, -64, 2_999_999)));
        assertFalse(written.covers(single(2_999_999, -64, -3_000_000)), "x and z must not swap");
        assertFalse(written.covers(single(-3_000_000, 64, 2_999_999)));
    }
}
