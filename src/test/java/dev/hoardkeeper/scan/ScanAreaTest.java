package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The area decides which containers enter a scan at all, so these are tests about the scan being the
 * shape the player was told it is — not about arithmetic for its own sake.
 */
class ScanAreaTest {

    // ---- chunk mode ------------------------------------------------------

    @Test
    void theDefaultChunkAreaIsTheThreeByThreeAroundTheStandingChunk() {
        ScanArea area = ScanArea.ofChunks(0, 0, 1);

        assertEquals(3, area.chunkSpan());
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                assertTrue(area.coversChunk(cx, cz), "chunk (" + cx + ", " + cz + ") is in the 3x3");
            }
        }
        assertFalse(area.coversChunk(2, 0), "the fourth chunk out is not");
        assertFalse(area.coversChunk(0, -2));
    }

    @Test
    void standingAnywhereInAChunkGivesTheSameArea() {
        // The whole reason for the mode: the area snaps to the grid instead of to the player.
        ScanArea corner = ScanArea.ofChunks(16 * 5, 16 * 7, 1);
        ScanArea otherCorner = ScanArea.ofChunks(16 * 5 + 15, 16 * 7 + 15, 1);

        assertEquals(corner.originChunkX(), otherCorner.originChunkX());
        assertEquals(corner.originChunkZ(), otherCorner.originChunkZ());
        assertTrue(corner.overlaps(otherCorner));
    }

    @Test
    void negativeCoordinatesFloorToTheChunkTheyAreIn() {
        // -1 >> 4 is -1, not 0. Getting this wrong shifts the whole area one chunk east/north for
        // every player standing west or north of the origin, which is most of a base.
        assertEquals(-1, ScanArea.ofChunks(-1, -1, 1).originChunkX());
        assertEquals(-1, ScanArea.ofChunks(-16, -16, 1).originChunkX());
        assertEquals(-2, ScanArea.ofChunks(-17, -17, 1).originChunkX());
    }

    @Test
    void aChunkAreaCoversEveryBlockOfEveryChunkItHolds() {
        ScanArea area = ScanArea.ofChunks(8, 8, 1);

        assertTrue(area.coversBlock(-16, -16), "north-west corner block of the box");
        assertTrue(area.coversBlock(31, 31), "south-east corner block of the box");
        assertFalse(area.coversBlock(32, 0), "first block of the next chunk east");
        assertFalse(area.coversBlock(-17, 0), "last block of the chunk west");
    }

    @Test
    void aChunkRadiusOfZeroIsTheStandingChunkAlone() {
        ScanArea area = ScanArea.ofChunks(20, 20, 0);

        assertEquals(1, area.chunkSpan());
        assertTrue(area.coversChunk(1, 1));
        assertFalse(area.coversChunk(0, 1));
    }

    @Test
    void aChunkAreaSweepsExactlyItsOwnChunks() {
        // A radius scan has to sweep one chunk further than it covers, because the player stands at
        // an arbitrary offset inside their chunk. A chunk area does not -- it is already aligned.
        assertEquals(1, ScanArea.ofChunks(0, 0, 1).sweepChunkRadius());
        assertEquals(2, ScanArea.ofRadius(0, 0, 16).sweepChunkRadius());
    }

    @Test
    void theEquivalentBlockRadiusUnderstatesRatherThanOverstates() {
        // 24 is the half-width of the 3x3's 48-block square; the corners reach 33.9. Understating is
        // the safe direction: a reader that only knows radii claims less than the scan really did.
        ScanArea area = ScanArea.ofChunks(0, 0, 1);

        assertEquals(24, area.equivalentBlockRadius());
        assertTrue(area.coversBlock(31, 31), "but the scan itself does reach the corner");
    }

    @Test
    void describesItselfByChunkSoTheChatLineIsActionable() {
        assertEquals("3x3 chunks at chunk (12, -14)", ScanArea.ofChunks(16 * 12 + 3, 16 * -14 + 9, 1).describe());
        assertEquals("r=48", ScanArea.ofRadius(0, 0, 48).describe());
    }

    // ---- radius mode -----------------------------------------------------

    @Test
    void aRadiusAreaIsAColumnNotASphere() {
        ScanArea area = ScanArea.ofRadius(0, 0, 10);

        assertTrue(area.coversBlock(10, 0), "exactly on the edge");
        assertFalse(area.coversBlock(11, 0));
        assertTrue(area.coversBlock(7, 7), "7² + 7² = 98 <= 100");
        assertFalse(area.coversBlock(8, 8), "8² + 8² = 128 > 100");
    }

    @Test
    void aRadiusAreaCoversAChunkItOnlyClips() {
        // The sweep bounds chunks, but scanChunk also runs for chunks that merely loaded, so
        // coversChunk has to answer for a circle that reaches a chunk's near edge and no further.
        // The numbers are the point: measured to the NEAREST block of the chunk, not to its centre.
        ScanArea area = ScanArea.ofRadius(0, 0, 20);

        assertTrue(area.coversChunk(1, 0), "nearest block (16, 0) is 16 out — clipped");
        assertFalse(area.coversChunk(1, 1), "nearest block (16, 16) is 22.6 out — just misses");
        assertFalse(area.coversChunk(2, 0), "nearest block (32, 0) is 32 out — untouched");

        // Three more blocks of reach and the diagonal chunk comes into range.
        assertTrue(ScanArea.ofRadius(0, 0, 23).coversChunk(1, 1), "22.6 <= 23");
    }

    // ---- bounding box ------------------------------------------------------

    @Test
    void aRadiusAreasBoundingBoxIsTheTrueCircleBoxNotTheOriginsChunk() {
        // Before the fix these four read from chunkRadius, which is always 0 for a radius scan --
        // collapsing a "/hoard start 8" to the origin's single 16x16 chunk regardless of
        // the requested radius. The manager needs the box a circle of that radius actually sits in.
        ScanArea area = ScanArea.ofRadius(100, -50, 8);

        assertEquals(92, area.minBlockX());
        assertEquals(108, area.maxBlockX());
        assertEquals(-58, area.minBlockZ());
        assertEquals(-42, area.maxBlockZ());
    }

    @Test
    void aRadiusAreasBoundingBoxHandlesANegativeOrigin() {
        ScanArea area = ScanArea.ofRadius(-100, -200, 8);

        assertEquals(-108, area.minBlockX());
        assertEquals(-92, area.maxBlockX());
        assertEquals(-208, area.minBlockZ());
        assertEquals(-192, area.maxBlockZ());
    }

    @Test
    void aRadiusOfZeroIsASingleBlockBoundingBox() {
        ScanArea area = ScanArea.ofRadius(5, 5, 0);

        assertEquals(5, area.minBlockX());
        assertEquals(5, area.maxBlockX());
        assertEquals(5, area.minBlockZ());
        assertEquals(5, area.maxBlockZ());
    }

    @Test
    void aChunkAreasBoundingBoxIsUnchanged() {
        // Pins the existing chunk-mode formula: the fix must not touch this shape at all.
        ScanArea area = ScanArea.ofChunks(16 * 12 + 3, 16 * -14 + 9, 1);

        assertEquals(16 * 11, area.minBlockX());
        assertEquals(16 * 13 + 15, area.maxBlockX());
        assertEquals(16 * -15, area.minBlockZ());
        assertEquals(16 * -13 + 15, area.maxBlockZ());
    }

    // ---- overlap ---------------------------------------------------------

    @Test
    void twoChunkAreasOverlapWhenTheirBoxesShareAChunk() {
        ScanArea here = ScanArea.ofChunks(0, 0, 1);

        assertTrue(here.overlaps(ScanArea.ofChunks(16 * 2, 0, 1)), "boxes touch at chunk 1");
        assertFalse(here.overlaps(ScanArea.ofChunks(16 * 3, 0, 1)), "one clear chunk between them");
    }

    @Test
    void aCircleMeetsABoxAtItsNearestCornerNotItsCentre() {
        // Measuring to the box centre would call this an overlap 9 blocks too early, and would then
        // delete the older scan of a storage that is genuinely somewhere else.
        ScanArea box = ScanArea.ofChunks(0, 0, 1);   // blocks -16..31 on both axes
        assertFalse(box.overlaps(ScanArea.ofRadius(40, 40, 4)), "corner (31,31) is 12.7 away");
        assertTrue(box.overlaps(ScanArea.ofRadius(40, 40, 13)));
    }

    @Test
    void aCircleInsideABoxOverlapsIt() {
        ScanArea box = ScanArea.ofChunks(0, 0, 1);

        assertTrue(box.overlaps(ScanArea.ofRadius(8, 8, 1)));
        assertTrue(ScanArea.ofRadius(8, 8, 1).overlaps(box), "and the test is symmetric");
    }

    @Test
    void farApartAreasDoNotOverflowIntoAnOverlap() {
        // 65536 blocks apart squares to exactly 0 in int arithmetic. Merely picking a huge separation
        // does not catch it -- at ±29,999,999 the wrapped square lands on a large positive number.
        assertFalse(ScanArea.ofRadius(-32_768, 0, 64).overlaps(ScanArea.ofRadius(32_768, 0, 64)));
        assertFalse(ScanArea.ofChunks(0, 0, 1).overlaps(ScanArea.ofRadius(65_536, 0, 64)));
    }

    // ---- snapshot round trip ---------------------------------------------

    @Test
    void aSnapshotWithNoAreaModeIsTheRadiusScanItUsedToBe() {
        // Every session.json written before schema 3 looks exactly like this, including the two
        // already sitting in the play profile. Reading them as chunk scans would silently reshape
        // what the retention rule thinks they covered.
        ScanArea restored = ScanArea.fromSnapshot(null, new int[]{100, 64, -200}, 24, 0);

        assertEquals(ScanArea.Mode.RADIUS, restored.mode());
        assertEquals(24, restored.radius());
    }

    @Test
    void aChunkSnapshotRoundTrips() {
        ScanArea original = ScanArea.ofChunks(16 * 12 + 3, 16 * -14 + 9, 2);
        ScanArea restored = ScanArea.fromSnapshot(original.mode().name(),
                new int[]{original.originX(), 64, original.originZ()},
                original.equivalentBlockRadius(), original.chunkRadius());

        assertEquals(original, restored);
    }
}
