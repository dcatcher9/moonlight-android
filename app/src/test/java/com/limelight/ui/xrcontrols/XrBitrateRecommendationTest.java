package com.limelight.ui.xrcontrols;

import static com.limelight.ui.xrcontrols.XrBitrateRecommendation.CODEC_AUTO;
import static com.limelight.ui.xrcontrols.XrBitrateRecommendation.CODEC_AV1;
import static com.limelight.ui.xrcontrols.XrBitrateRecommendation.CODEC_H264;
import static com.limelight.ui.xrcontrols.XrBitrateRecommendation.CODEC_HEVC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.preferences.session.SessionSettingsStore;

import org.junit.Test;

import java.util.Arrays;

/**
 * Pins the recommendation table. These numbers were derived once from bits-per-pixel targets; the
 * point of the test is that they cannot drift silently afterwards.
 */
public class XrBitrateRecommendationTest {
    private static int packed(int w, int h, int fps, String codec) {
        return XrBitrateRecommendation.recommendedKbps(true, w, h, fps, codec);
    }

    private static int flat(int w, int h, int fps, String codec) {
        return XrBitrateRecommendation.recommendedKbps(false, w, h, fps, codec);
    }

    @Test
    public void ladderIsTheDerivedSixRungSet() {
        assertEquals(Arrays.asList(50000, 70000, 100000, 140000, 200000, 300000),
                XrBitrateRecommendation.LADDER_KBPS);
    }

    @Test
    public void hostSbsFourKMatchesTheDerivedTable() {
        assertEquals(100000, packed(3840, 2160, 60, CODEC_AV1));
        assertEquals(140000, packed(3840, 2160, 60, CODEC_HEVC));
        // The configuration actually in use.
        assertEquals(140000, packed(3840, 2160, 72, CODEC_AV1));
        assertEquals(200000, packed(3840, 2160, 72, CODEC_HEVC));
        assertEquals(200000, packed(3840, 2160, 90, CODEC_AV1));
        assertEquals(200000, packed(3840, 2160, 90, CODEC_HEVC));
    }

    @Test
    public void hostSbsLowerResolutionsMatchTheDerivedTable() {
        assertEquals(50000, packed(1920, 1080, 60, CODEC_HEVC));
        assertEquals(50000, packed(1920, 1080, 90, CODEC_HEVC));
        assertEquals(70000, packed(2560, 1440, 72, CODEC_HEVC));
        assertEquals(100000, packed(2560, 1440, 90, CODEC_HEVC));
        assertEquals(200000, packed(5120, 2160, 72, CODEC_HEVC));
        assertEquals(300000, packed(5120, 2160, 90, CODEC_HEVC));
    }

    @Test
    public void flatModesSitLowerThanPackedAtTheSameShape() {
        // Client SBS and Normal share this column: no special case for either.
        assertEquals(70000, flat(3840, 2160, 60, CODEC_HEVC));
        assertEquals(100000, flat(3840, 2160, 72, CODEC_HEVC));
        assertEquals(100000, flat(3840, 2160, 90, CODEC_HEVC));
        assertEquals(50000, flat(1920, 1080, 60, CODEC_HEVC));
        // Same shape packed needs a rung more: 4K/72 HEVC is 100 flat, 200 packed.
        assertEquals(200000, packed(3840, 2160, 72, CODEC_HEVC));
        // A packed frame is twice the pixels, so it must never recommend less than flat.
        for (int fps : new int[] {30, 60, 72, 90}) {
            assertTrue(packed(2560, 1440, fps, CODEC_HEVC) >= flat(2560, 1440, fps, CODEC_HEVC));
        }
    }

    @Test
    public void clientSbsDefaultsLandOnTheFloorLikeAnyOtherLowDemandShape() {
        assertEquals(50000, flat(1920, 1080, 30, CODEC_HEVC));
        assertEquals(50000, flat(1920, 1080, 30, CODEC_AV1));
    }

    @Test
    public void h264IsUnreachableOnLargePackedFrames() {
        // ~470 Mbps would be needed at 4K host SBS; pointing at the top rung would imply a
        // quality H.264 cannot deliver, so the answer is "change codec".
        assertEquals(-1, packed(3840, 2160, 72, CODEC_H264));
        assertEquals(-1, packed(5120, 2160, 60, CODEC_H264));
        // It remains usable on smaller frames.
        assertEquals(100000, packed(1920, 1080, 60, CODEC_H264));
        assertEquals(200000, packed(2560, 1440, 60, CODEC_H264));
    }

    @Test
    public void autoIsCostedAsHevcSoTheHintNeverUnderProvisions() {
        assertEquals(XrBitrateRecommendation.bitsPerPixelFor(CODEC_HEVC),
                XrBitrateRecommendation.bitsPerPixelFor(CODEC_AUTO), 0.0001);
        assertTrue(XrBitrateRecommendation.bitsPerPixelFor(CODEC_AV1)
                < XrBitrateRecommendation.bitsPerPixelFor(CODEC_HEVC));
        assertTrue(XrBitrateRecommendation.bitsPerPixelFor(CODEC_H264)
                > XrBitrateRecommendation.bitsPerPixelFor(CODEC_HEVC));
    }

    @Test
    public void recommendationRisesMonotonicallyWithPixelsAndRate() {
        int previous = 0;
        for (int fps : new int[] {30, 60, 72, 90}) {
            int value = packed(3840, 2160, fps, CODEC_HEVC);
            assertTrue("fps " + fps + " regressed", value >= previous);
            previous = value;
        }
    }

    @Test
    public void degenerateShapesReportNoRecommendationRatherThanGuessing() {
        assertEquals(-1, packed(0, 1080, 60, CODEC_HEVC));
        assertEquals(-1, packed(1920, 0, 60, CODEC_HEVC));
        assertEquals(-1, packed(1920, 1080, 0, CODEC_HEVC));
    }

    @Test
    public void packedModesAreTheHostSbsOnes() {
        assertTrue(XrBitrateRecommendation.isPackedMode(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW));
        assertTrue(XrBitrateRecommendation.isPackedMode(
                SessionSettingsStore.PresenterMode.HOST_SBS_AI));
        assertFalse(XrBitrateRecommendation.isPackedMode(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI));
        assertFalse(XrBitrateRecommendation.isPackedMode(
                SessionSettingsStore.PresenterMode.NORMAL));
    }
}
