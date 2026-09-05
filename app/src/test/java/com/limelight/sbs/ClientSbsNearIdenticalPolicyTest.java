package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsNearIdenticalPolicyTest {
    // Matches Apollo's deliberately rectangular partial-tile policy fixture.
    private static final int WIDTH = 97;
    private static final int HEIGHT = 81;
    private static final long OWNER_FRAME = 100L;
    private static final long CURRENT_FRAME = 101L;

    @Test
    public void localDecodedEvidenceCanAuthorizeReuseWithoutHostMetadata() {
        // The compatibility path intentionally has no host capability or metadata argument.
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quietTiles(), OWNER_FRAME, CURRENT_FRAME, 0L));
    }

    @Test
    public void deltaThresholdsAreInclusiveForEitherSignAndRejectNonfiniteValues() {
        assertFalse(ClientSbsNearIdenticalPolicy.isMediumDelta(
                Math.nextDown(ClientSbsNearIdenticalPolicy.MEDIUM_DELTA)));
        assertTrue(ClientSbsNearIdenticalPolicy.isMediumDelta(
                ClientSbsNearIdenticalPolicy.MEDIUM_DELTA));
        assertTrue(ClientSbsNearIdenticalPolicy.isMediumDelta(
                -ClientSbsNearIdenticalPolicy.MEDIUM_DELTA));

        assertFalse(ClientSbsNearIdenticalPolicy.isStrongDelta(
                Math.nextDown(ClientSbsNearIdenticalPolicy.STRONG_DELTA)));
        assertTrue(ClientSbsNearIdenticalPolicy.isStrongDelta(
                ClientSbsNearIdenticalPolicy.STRONG_DELTA));
        assertTrue(ClientSbsNearIdenticalPolicy.isStrongDelta(
                -ClientSbsNearIdenticalPolicy.STRONG_DELTA));

        assertFalse(ClientSbsNearIdenticalPolicy.isMediumDelta(Float.NaN));
        assertFalse(ClientSbsNearIdenticalPolicy.isStrongDelta(Float.POSITIVE_INFINITY));
    }

    @Test
    public void globalThresholdsAllowTheExactHostBoundsAndRejectOneCountAbove() {
        // 97 * 81 = 7,857: floor(10%) is 785 and floor(2.5%) is 196.
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE,
                decide(withChanges(quietTiles(), 785, 0)));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                decide(withChanges(quietTiles(), 786, 0)));

        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE,
                decide(withChanges(quietTiles(), 196, 196)));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                decide(withChanges(quietTiles(), 197, 197)));
    }

    @Test
    public void supportedTileThresholdIsInclusiveAndUnsupportedSliverHasNoLocalVeto() {
        ClientSbsNearIdenticalPolicy.TileEvidence[] tiles = quietTiles();
        tiles[0] = new ClientSbsNearIdenticalPolicy.TileEvidence(256, 192, 192, 0);
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE, decide(tiles));

        tiles[0] = new ClientSbsNearIdenticalPolicy.TileEvidence(256, 193, 193, 0);
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER, decide(tiles));

        tiles = quietTiles();
        int finalOnePixelTile = tiles.length - 1;
        assertEquals(1, tiles[finalOnePixelTile].admitted);
        tiles[finalOnePixelTile] =
                new ClientSbsNearIdenticalPolicy.TileEvidence(1, 1, 1, 0);
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE, decide(tiles));
    }

    @Test
    public void everyExpectedTexelMustBeAdmittedAndFinite() {
        ClientSbsNearIdenticalPolicy.TileEvidence[] missing = quietTiles();
        ClientSbsNearIdenticalPolicy.TileEvidence first = missing[0];
        missing[0] = new ClientSbsNearIdenticalPolicy.TileEvidence(
                first.admitted - 1, 0, 0, 0);
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER, decide(missing));

        ClientSbsNearIdenticalPolicy.TileEvidence[] nonfinite = quietTiles();
        first = nonfinite[0];
        nonfinite[0] = new ClientSbsNearIdenticalPolicy.TileEvidence(
                first.admitted, 0, 0, 1);
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER, decide(nonfinite));
    }

    @Test
    public void malformedEvidenceAlwaysFailsClosedToInference() {
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, null, OWNER_FRAME, CURRENT_FRAME, 0L));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.decide(
                        0, HEIGHT, quietTiles(), OWNER_FRAME, CURRENT_FRAME, 0L));

        ClientSbsNearIdenticalPolicy.TileEvidence[] wrongCount =
                new ClientSbsNearIdenticalPolicy.TileEvidence[quietTiles().length - 1];
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER, decide(wrongCount));

        assertMalformed(new ClientSbsNearIdenticalPolicy.TileEvidence(-1, 0, 0, 0));
        assertMalformed(new ClientSbsNearIdenticalPolicy.TileEvidence(257, 0, 0, 0));
        assertMalformed(new ClientSbsNearIdenticalPolicy.TileEvidence(256, 257, 0, 0));
        assertMalformed(new ClientSbsNearIdenticalPolicy.TileEvidence(256, 1, 2, 0));
        assertMalformed(new ClientSbsNearIdenticalPolicy.TileEvidence(256, 0, 0, 257));
        assertMalformed(null);
    }

    @Test
    public void ownerAllowsFrameGapsOneThroughFourOnly() {
        ClientSbsNearIdenticalPolicy.TileEvidence[] quiet = quietTiles();
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quiet, 100L, 101L, 0L));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quiet, 100L, 104L, 0L));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quiet, 100L, 100L, 0L));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quiet, 100L, 105L, 0L));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quiet, 0L, 1L, 0L));
    }

    @Test
    public void ownerAgeAllowsZeroThroughOneNanosecondBelowOneHundredMilliseconds() {
        ClientSbsNearIdenticalPolicy.TileEvidence[] quiet = quietTiles();
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quiet, 100L, 101L, 0L));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quiet, 100L, 101L, 99_999_999L));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quiet, 100L, 101L, -1L));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.decide(
                        WIDTH, HEIGHT, quiet, 100L, 101L, 100_000_000L));
    }

    @Test
    public void decisionTagsAndTokenWordsHaveStableFailClosedRoundTrips() {
        assertEquals(0, ClientSbsNearIdenticalPolicy.Decision.REUSE.getTag());
        assertEquals(1, ClientSbsNearIdenticalPolicy.Decision.INFER.getTag());
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.REUSE,
                ClientSbsNearIdenticalPolicy.Decision.fromTagFailClosed(0));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.Decision.fromTagFailClosed(1));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.Decision.fromTagFailClosed(-1));
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER,
                ClientSbsNearIdenticalPolicy.Decision.fromTagFailClosed(2));

        long token = 0xfedcba9876543210L;
        int low = ClientSbsNearIdenticalPolicy.tokenLow(token);
        int high = ClientSbsNearIdenticalPolicy.tokenHigh(token);
        assertEquals(0x76543210, low);
        assertEquals(0xfedcba98, high);
        assertEquals(token, ClientSbsNearIdenticalPolicy.joinToken(low, high));
    }

    @Test
    public void decisionReasonsHaveStableDiagnosticCategories() {
        assertTrue(ClientSbsNearIdenticalPolicy.isKnownReason(
                ClientSbsNearIdenticalPolicy.REASON_REUSE));
        assertTrue(ClientSbsNearIdenticalPolicy.isKnownReason(
                ClientSbsNearIdenticalPolicy.REASON_RECORD_INVALID));
        assertFalse(ClientSbsNearIdenticalPolicy.isKnownReason(-1));
        assertFalse(ClientSbsNearIdenticalPolicy.isKnownReason(10));

        assertTrue(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_CONTENT_MEDIUM));
        assertTrue(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_CONTENT_STRONG));
        assertTrue(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_CONTENT_LOCAL));
        assertFalse(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_OWNER_AGE));
    }

    private static ClientSbsNearIdenticalPolicy.Decision decide(
            ClientSbsNearIdenticalPolicy.TileEvidence[] tiles) {
        return ClientSbsNearIdenticalPolicy.decide(
                WIDTH, HEIGHT, tiles, OWNER_FRAME, CURRENT_FRAME, 0L);
    }

    private static void assertMalformed(ClientSbsNearIdenticalPolicy.TileEvidence malformed) {
        ClientSbsNearIdenticalPolicy.TileEvidence[] tiles = quietTiles();
        tiles[0] = malformed;
        assertEquals(ClientSbsNearIdenticalPolicy.Decision.INFER, decide(tiles));
    }

    private static ClientSbsNearIdenticalPolicy.TileEvidence[] quietTiles() {
        int columns = (WIDTH + ClientSbsNearIdenticalPolicy.TILE_SIZE - 1)
                / ClientSbsNearIdenticalPolicy.TILE_SIZE;
        int rows = (HEIGHT + ClientSbsNearIdenticalPolicy.TILE_SIZE - 1)
                / ClientSbsNearIdenticalPolicy.TILE_SIZE;
        ClientSbsNearIdenticalPolicy.TileEvidence[] tiles =
                new ClientSbsNearIdenticalPolicy.TileEvidence[columns * rows];
        for (int tileY = 0; tileY < rows; tileY++) {
            int tileHeight = Math.min(ClientSbsNearIdenticalPolicy.TILE_SIZE,
                    HEIGHT - tileY * ClientSbsNearIdenticalPolicy.TILE_SIZE);
            for (int tileX = 0; tileX < columns; tileX++) {
                int tileWidth = Math.min(ClientSbsNearIdenticalPolicy.TILE_SIZE,
                        WIDTH - tileX * ClientSbsNearIdenticalPolicy.TILE_SIZE);
                int admitted = tileWidth * tileHeight;
                tiles[tileY * columns + tileX] =
                        new ClientSbsNearIdenticalPolicy.TileEvidence(admitted, 0, 0, 0);
            }
        }
        return tiles;
    }

    /** Distributes changes without tripping the independent local strong-change veto. */
    private static ClientSbsNearIdenticalPolicy.TileEvidence[] withChanges(
            ClientSbsNearIdenticalPolicy.TileEvidence[] tiles,
            int mediumChanged, int strongChanged) {
        int remainingStrong = strongChanged;
        int remainingMediumOnly = mediumChanged - strongChanged;
        for (int index = 0; index < tiles.length; index++) {
            ClientSbsNearIdenticalPolicy.TileEvidence tile = tiles[index];
            int localStrongLimit = tile.admitted >= 64
                    ? tile.admitted * 3 / 4 : tile.admitted;
            int localStrong = Math.min(remainingStrong, localStrongLimit);
            int mediumCapacity = tile.admitted - localStrong;
            int localMediumOnly = Math.min(remainingMediumOnly, mediumCapacity);
            tiles[index] = new ClientSbsNearIdenticalPolicy.TileEvidence(
                    tile.admitted,
                    localStrong + localMediumOnly,
                    localStrong,
                    0);
            remainingStrong -= localStrong;
            remainingMediumOnly -= localMediumOnly;
        }
        assertEquals("Strong changes did not fit", 0, remainingStrong);
        assertEquals("Medium changes did not fit", 0, remainingMediumOnly);
        return tiles;
    }
}
