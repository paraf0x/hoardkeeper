package dev.hoardkeeper.search;

/**
 * One scanned container that holds the searched item, and how much of it that container holds.
 *
 * <p>Positions are {@code int[]} rather than {@code BlockPos} for the same reason the rest of the
 * {@code scan} package uses them: this record travels from a JSON file through the pure index and
 * the pure highlight, none of which may need a running game to be exercised.
 *
 * <p>{@code secondaryPos} is the other half of a double chest, or {@code null}. A double chest is
 * one hit, not two — the player opens it once, and {@link #covers} answers for either half so
 * clicking whichever half is nearer dismisses the whole thing.
 *
 * <p>Note that the generated {@code equals}/{@code hashCode} compare the {@code int[]} fields by
 * identity. Nothing here relies on value equality; {@link #covers} is how two positions are
 * compared.
 */
public record ContainerHit(int[] pos, int[] secondaryPos, String kind, long count) {

    /** Whether this hit sits at {@code (x, y, z)} — either half of a double chest counts. */
    public boolean covers(int x, int y, int z) {
        return matches(pos, x, y, z) || matches(secondaryPos, x, y, z);
    }

    private static boolean matches(int[] p, int x, int y, int z) {
        return p != null && p.length == 3 && p[0] == x && p[1] == y && p[2] == z;
    }

    /** Squared distance from {@code eye} to the block centre. Squared, so nothing takes a root. */
    public double distanceSquaredFrom(double[] eye) {
        double dx = pos[0] + 0.5 - eye[0];
        double dy = pos[1] + 0.5 - eye[1];
        double dz = pos[2] + 0.5 - eye[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
