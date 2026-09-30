package dev.hoardkeeper.scan;

/**
 * The area one scan covers, in one of two shapes.
 *
 * <p><b>{@link Mode#CHUNKS}</b> is a chunk-aligned square: the chunk the player stands in plus
 * {@code chunkRadius} rings around it, so {@code chunkRadius = 1} is the 3×3 block of chunks. This is
 * the default because it mirrors the quick-deposit plugin on the server, which offers the containers
 * in exactly that 3×3 pattern — scanning the same shape answers the question "what can quick-deposit
 * reach from here?" rather than a question nobody asked.
 *
 * <p>It has a second property that a block radius cannot have: it is <em>snapped to the grid</em>.
 * Two scans started anywhere in the same chunk cover the identical set of chunks, so "is this the
 * same place as last time?" becomes integer equality instead of a distance threshold. That is what
 * makes {@link dev.hoardkeeper.measured.StorageClusters}'s overlap test exact for chunk scans —
 * the same property that used to make the deleted {@code SessionRetention}'s overlap test exact —
 * and it is why the mode is worth having as a mode rather than as a radius that happens to be about
 * 24.
 *
 * <p><b>{@link Mode#RADIUS}</b> is the original shape, kept for {@code /hoard start <n>}: a
 * cylinder of {@code radius} blocks around the player, tested on x/z with height ignored.
 *
 * <p>Pure — no Minecraft types, no mutable state, fully unit-tested. Every geometric question the
 * scanner and the storage-clustering rule ask goes through this class, so there is exactly one
 * definition of "inside the scan" to get wrong.
 */
public record ScanArea(Mode mode, int originX, int originZ, int radius, int chunkRadius) {

    public enum Mode {
        /** A cylinder of {@code radius} blocks around the origin. */
        RADIUS,
        /** The chunk-aligned square of {@code (2 * chunkRadius + 1)²} chunks around the origin's chunk. */
        CHUNKS
    }

    /**
     * A requested area before the origin is known — what the command parses and hands to
     * {@link ScanController#start}, which only learns the player's position once it is sure a scan
     * can start at all. Keeping the two apart is what stops the command from having to reach for the
     * player, and stops the controller from having to know command syntax.
     */
    public record Request(Mode mode, int size) {

        public static Request ofRadius(int radius) {
            return new Request(Mode.RADIUS, radius);
        }

        public static Request ofChunks(int chunkRadius) {
            return new Request(Mode.CHUNKS, chunkRadius);
        }

        public ScanArea at(int originX, int originZ) {
            return mode == Mode.CHUNKS
                    ? ScanArea.ofChunks(originX, originZ, size)
                    : ScanArea.ofRadius(originX, originZ, size);
        }
    }

    /** Blocks per chunk along one axis. */
    private static final int CHUNK_SIZE = 16;

    public static ScanArea ofRadius(int originX, int originZ, int radius) {
        return new ScanArea(Mode.RADIUS, originX, originZ, Math.max(0, radius), 0);
    }

    public static ScanArea ofChunks(int originX, int originZ, int chunkRadius) {
        return new ScanArea(Mode.CHUNKS, originX, originZ, 0, Math.max(0, chunkRadius));
    }

    /**
     * Rebuilds an area from what a {@code session.json} recorded. {@code areaMode} is {@code null} in
     * every snapshot written before schema 3, and those are all radius scans — so null means
     * {@link Mode#RADIUS}, which is what keeps sessions already on disk readable.
     */
    public static ScanArea fromSnapshot(String areaMode, int[] origin, int radius, int chunkRadius) {
        int x = origin != null && origin.length > 0 ? origin[0] : 0;
        int z = origin != null && origin.length > 2 ? origin[2] : 0;
        if (Mode.CHUNKS.name().equals(areaMode)) {
            return ofChunks(x, z, chunkRadius);
        }
        return ofRadius(x, z, radius);
    }

    /** The chunk the origin sits in. Arithmetic shift, so it floors correctly for negative coordinates. */
    public int originChunkX() {
        return originX >> 4;
    }

    public int originChunkZ() {
        return originZ >> 4;
    }

    /**
     * How far {@link ContainerDiscovery#sweep} has to walk in chunks to be sure it has seen every
     * chunk this area touches. For a radius scan that is one chunk further than the radius covers,
     * because the origin sits at an arbitrary offset inside its own chunk.
     */
    public int sweepChunkRadius() {
        return mode == Mode.CHUNKS ? chunkRadius : (radius >> 4) + 1;
    }

    /** Whether any part of the chunk at {@code (cx, cz)} is inside this area. */
    public boolean coversChunk(int cx, int cz) {
        if (mode == Mode.CHUNKS) {
            return Math.abs(cx - originChunkX()) <= chunkRadius
                    && Math.abs(cz - originChunkZ()) <= chunkRadius;
        }
        // A radius scan can clip a chunk's corner without covering its centre, so the honest test is
        // circle-against-chunk-square rather than a distance between centres.
        return circleTouchesBox((long) radius,
                (long) cx * CHUNK_SIZE, (long) cx * CHUNK_SIZE + CHUNK_SIZE - 1,
                (long) cz * CHUNK_SIZE, (long) cz * CHUNK_SIZE + CHUNK_SIZE - 1);
    }

    /**
     * Whether the block column at {@code (x, z)} is inside this area. Height is deliberately not a
     * parameter: a scan is a column, and a chest under the origin is as much part of the base as one
     * beside it.
     */
    public boolean coversBlock(int x, int z) {
        if (mode == Mode.CHUNKS) {
            return coversChunk(x >> 4, z >> 4);
        }
        long dx = (long) x - originX;
        long dz = (long) z - originZ;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    /**
     * Whether two scans cover any ground in common — the test behind "these are the same storage".
     *
     * <p>Three cases, each exact in integer arithmetic, none of them approximating the others:
     * two chunk scans are two aligned boxes; two radius scans are two circles; a mixed pair is a
     * circle against a box, answered by clamping the circle's centre into the box and measuring from
     * there. No square roots and no floating point, so the answer never depends on rounding.
     */
    public boolean overlaps(ScanArea other) {
        if (mode == Mode.CHUNKS && other.mode == Mode.CHUNKS) {
            return Math.abs(originChunkX() - other.originChunkX()) <= chunkRadius + other.chunkRadius
                    && Math.abs(originChunkZ() - other.originChunkZ()) <= chunkRadius + other.chunkRadius;
        }
        if (mode == Mode.RADIUS && other.mode == Mode.RADIUS) {
            long dx = (long) originX - other.originX;
            long dz = (long) originZ - other.originZ;
            long reach = (long) radius + other.radius;
            return dx * dx + dz * dz <= reach * reach;
        }
        ScanArea circle = mode == Mode.RADIUS ? this : other;
        ScanArea box = mode == Mode.RADIUS ? other : this;
        return circle.circleTouchesBox((long) circle.radius,
                box.minBlockX(), box.maxBlockX(), box.minBlockZ(), box.maxBlockZ());
    }

    /**
     * West edge of this area's true bounding box, in blocks: the chunk-aligned west edge of the
     * square for a chunk-mode area, or {@code originX - radius} for a radius-mode area. Together
     * the four bounds below give the real axis-aligned box the area sits inside, in both modes --
     * for {@link Mode#RADIUS} that is the box around the circle, not the single chunk its origin
     * happens to stand in.
     */
    public long minBlockX() {
        return mode == Mode.CHUNKS
                ? (long) (originChunkX() - chunkRadius) * CHUNK_SIZE
                : (long) originX - radius;
    }

    /** East edge of this area's true bounding box, in blocks. See {@link #minBlockX()}. */
    public long maxBlockX() {
        return mode == Mode.CHUNKS
                ? (long) (originChunkX() + chunkRadius) * CHUNK_SIZE + CHUNK_SIZE - 1
                : (long) originX + radius;
    }

    /** North edge of this area's true bounding box, in blocks. See {@link #minBlockX()}. */
    public long minBlockZ() {
        return mode == Mode.CHUNKS
                ? (long) (originChunkZ() - chunkRadius) * CHUNK_SIZE
                : (long) originZ - radius;
    }

    /** South edge of this area's true bounding box, in blocks. See {@link #minBlockX()}. */
    public long maxBlockZ() {
        return mode == Mode.CHUNKS
                ? (long) (originChunkZ() + chunkRadius) * CHUNK_SIZE + CHUNK_SIZE - 1
                : (long) originZ + radius;
    }

    /** Chunks along one edge: 3 for the default 3×3. */
    public int chunkSpan() {
        return 2 * chunkRadius + 1;
    }

    /**
     * A radius in blocks for readers that only understand one — the half-width of a chunk area's
     * square, 24 blocks for the default 3×3. Written into {@code session.json} and the report so an
     * old reader gets a sane number rather than a zero. It <em>under</em>-covers the square's corners
     * by design: a number that is too small makes a stale reader claim less than the scan did, which
     * is the harmless direction to be wrong in.
     */
    public int equivalentBlockRadius() {
        return mode == Mode.CHUNKS ? chunkSpan() * CHUNK_SIZE / 2 : radius;
    }

    /** How this area reads in chat and on the HUD. */
    public String describe() {
        if (mode == Mode.CHUNKS) {
            return chunkSpan() + "x" + chunkSpan() + " chunks at chunk ("
                    + originChunkX() + ", " + originChunkZ() + ")";
        }
        return "r=" + radius;
    }

    /**
     * Squared distance from this area's origin to the nearest point of an axis-aligned box, compared
     * against {@code circleRadius}. Clamping the centre into the box is the standard exact test and
     * handles the centre being inside the box (distance zero) without a special case.
     */
    private boolean circleTouchesBox(long circleRadius, long minX, long maxX, long minZ, long maxZ) {
        long nearestX = Math.min(Math.max(originX, minX), maxX);
        long nearestZ = Math.min(Math.max(originZ, minZ), maxZ);
        long dx = originX - nearestX;
        long dz = originZ - nearestZ;
        return dx * dx + dz * dz <= circleRadius * circleRadius;
    }
}
