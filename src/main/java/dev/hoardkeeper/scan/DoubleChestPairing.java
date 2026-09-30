package dev.hoardkeeper.scan;

/**
 * Canonicalises the two block positions that make up a single double chest, so that either half
 * discovered by a world scan resolves to the same logical container.
 */
public final class DoubleChestPairing {

    private DoubleChestPairing() {
    }

    /**
     * Returns the lexicographically smaller of {@code a} and {@code b} by (x, y, z). Independent
     * of argument order: {@code canonical(a, b) == canonical(b, a)}.
     */
    public static int[] canonical(int[] a, int[] b) {
        return compare(a, b) <= 0 ? a : b;
    }

    /**
     * Returns whichever of {@code a} and {@code b} is not {@link #canonical(int[], int[])}.
     * Independent of argument order.
     */
    public static int[] other(int[] a, int[] b) {
        return canonical(a, b) == a ? b : a;
    }

    private static int compare(int[] a, int[] b) {
        if (a[0] != b[0]) {
            return Integer.compare(a[0], b[0]);
        }
        if (a[1] != b[1]) {
            return Integer.compare(a[1], b[1]);
        }
        return Integer.compare(a[2], b[2]);
    }
}
