package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class DoubleChestPairingTest {

    @Test
    void picksTheSmallerXAsCanonical() {
        assertArrayEquals(new int[]{7998, 100, 7998},
                DoubleChestPairing.canonical(new int[]{7999, 100, 7998}, new int[]{7998, 100, 7998}));
    }

    @Test
    void isOrderIndependentSoEitherHalfDiscoversTheSameUnit() {
        int[] a = new int[]{10, 64, 20};
        int[] b = new int[]{10, 64, 21};

        assertArrayEquals(DoubleChestPairing.canonical(a, b), DoubleChestPairing.canonical(b, a));
        assertArrayEquals(DoubleChestPairing.other(a, b), DoubleChestPairing.other(b, a));
    }

    @Test
    void fallsThroughToYThenZWhenXTies() {
        assertArrayEquals(new int[]{5, 63, 20},
                DoubleChestPairing.canonical(new int[]{5, 64, 20}, new int[]{5, 63, 20}));
        assertArrayEquals(new int[]{5, 64, 19},
                DoubleChestPairing.canonical(new int[]{5, 64, 20}, new int[]{5, 64, 19}));
    }

    @Test
    void otherIsAlwaysTheHalfThatIsNotCanonical() {
        int[] a = new int[]{1, 1, 1};
        int[] b = new int[]{2, 1, 1};

        assertArrayEquals(new int[]{2, 1, 1}, DoubleChestPairing.other(a, b));
    }
}
