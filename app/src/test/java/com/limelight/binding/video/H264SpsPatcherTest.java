package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;
import org.junit.Test;

public final class H264SpsPatcherTest {
    private static final int API_25 = 25;
    private static final int API_34 = 34;

    @Test
    public void lowersLevelAndReferenceFramesWithoutReferenceFrameInvalidation() {
        assertEquals(31, patchedLevel(720, 480, 60));
        assertEquals(32, patchedLevel(1280, 720, 60));
        assertEquals(42, patchedLevel(1920, 1080, 60));
        // Larger or faster streams keep the encoder's level.
        assertEquals(50, patchedLevel(3840, 2160, 60));
        assertEquals(50, patchedLevel(1920, 1080, 120));

        SeqParameterSet sps = highProfileSps();
        H264SpsPatcher.patch(sps, settings(API_34, false, 1920, 1080, 60, true, false, false, false));
        assertEquals(1, sps.numRefFrames);
        assertEquals(1, sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering);
    }

    @Test
    public void keepsLevelAndReferenceFramesWithReferenceFrameInvalidation() {
        SeqParameterSet sps = highProfileSps();
        H264SpsPatcher.patch(sps, settings(API_34, true, 1280, 720, 60, true, false, false, false));

        assertEquals(50, sps.levelIdc);
        assertEquals(16, sps.numRefFrames);
        assertEquals(16, sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering);
    }

    @Test
    public void addsBitstreamRestrictionsOnCurrentAndroidAndKeepsExistingOnes() {
        SeqParameterSet bare = highProfileSps();
        H264SpsPatcher.patch(bare, settings(API_34, false, 1920, 1080, 60, true, false, false, false));
        VUIParameters.BitstreamRestriction added = bare.vuiParams.bitstreamRestriction;
        assertTrue(added.motionVectorsOverPicBoundariesFlag);
        assertEquals(2, added.maxBytesPerPicDenom);
        assertEquals(1, added.maxBitsPerMbDenom);
        assertEquals(16, added.log2MaxMvLengthHorizontal);
        assertEquals(16, added.log2MaxMvLengthVertical);
        assertEquals(0, added.numReorderFrames);

        SeqParameterSet restricted = highProfileSps();
        restricted.vuiParams = new VUIParameters();
        restricted.vuiParams.bitstreamRestriction = new VUIParameters.BitstreamRestriction();
        restricted.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 7;
        restricted.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 9;
        VUIParameters.BitstreamRestriction original = restricted.vuiParams.bitstreamRestriction;
        H264SpsPatcher.patch(restricted,
                settings(API_34, false, 1920, 1080, 60, true, false, false, false));
        assertTrue(original == restricted.vuiParams.bitstreamRestriction);
        assertEquals(7, original.maxBytesPerPicDenom);
        assertEquals(9, original.maxBitsPerMbDenom);
        assertEquals(1, original.maxDecFrameBuffering);
    }

    @Test
    public void legacyAndroidRestrictsOnlyDecodersThatNeedIt() {
        SeqParameterSet unlisted = highProfileSps();
        unlisted.vuiParams = new VUIParameters();
        unlisted.vuiParams.bitstreamRestriction = new VUIParameters.BitstreamRestriction();
        H264SpsPatcher.patch(unlisted,
                settings(API_25, false, 1920, 1080, 60, true, false, false, false));
        assertNull(unlisted.vuiParams.bitstreamRestriction);

        SeqParameterSet withoutVui = highProfileSps();
        H264SpsPatcher.patch(withoutVui,
                settings(API_25, false, 1920, 1080, 60, true, false, false, false));
        assertNull(withoutVui.vuiParams);

        SeqParameterSet listed = highProfileSps();
        listed.vuiParams = new VUIParameters();
        listed.vuiParams.bitstreamRestriction = new VUIParameters.BitstreamRestriction();
        listed.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 7;
        listed.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 9;
        H264SpsPatcher.patch(listed,
                settings(API_25, false, 1920, 1080, 60, true, true, false, false));
        assertNotNull(listed.vuiParams.bitstreamRestriction);
        assertEquals(2, listed.vuiParams.bitstreamRestriction.maxBytesPerPicDenom);
        assertEquals(1, listed.vuiParams.bitstreamRestriction.maxBitsPerMbDenom);
    }

    @Test
    public void stripsColourSignallingOnlyOnLegacyAndroidWithoutANewerCodec() {
        assertFalse(colourSignallingAfterPatch(API_25, false));
        assertTrue(colourSignallingAfterPatch(API_25, true));
        assertTrue(colourSignallingAfterPatch(API_34, false));
    }

    @Test
    public void appliesBaselineAndConstrainedHighProfileAdjustments() {
        SeqParameterSet constrained = highProfileSps();
        H264SpsPatcher.patch(constrained,
                settings(API_34, false, 1920, 1080, 60, true, false, false, true));
        assertEquals(100, constrained.profileIdc);
        assertTrue(constrained.constraintSet4Flag);
        assertTrue(constrained.constraintSet5Flag);

        SeqParameterSet plain = highProfileSps();
        plain.constraintSet4Flag = true;
        plain.constraintSet5Flag = true;
        H264SpsPatcher.patch(plain,
                settings(API_34, false, 1920, 1080, 60, true, false, false, false));
        assertFalse(plain.constraintSet4Flag);
        assertFalse(plain.constraintSet5Flag);

        // The baseline hack wins over Constrained High Profile until the SPS is replayed.
        SeqParameterSet baseline = highProfileSps();
        H264SpsPatcher.patch(baseline,
                settings(API_34, false, 1920, 1080, 60, true, false, true, true));
        assertEquals(66, baseline.profileIdc);
        assertFalse(baseline.constraintSet4Flag);
        assertFalse(baseline.constraintSet5Flag);

        baseline.profileIdc = 100;
        H264SpsPatcher.applyProfileConstraints(baseline, true);
        assertTrue(baseline.constraintSet4Flag);
        assertTrue(baseline.constraintSet5Flag);
    }

    private static int patchedLevel(int width, int height, int refreshRate) {
        SeqParameterSet sps = highProfileSps();
        H264SpsPatcher.patch(sps,
                settings(API_34, false, width, height, refreshRate, true, false, false, false));
        return sps.levelIdc;
    }

    private static boolean colourSignallingAfterPatch(int sdkInt, boolean newerCodecAvailable) {
        SeqParameterSet sps = highProfileSps();
        sps.vuiParams = new VUIParameters();
        sps.vuiParams.videoSignalTypePresentFlag = true;
        sps.vuiParams.colourDescriptionPresentFlag = true;
        sps.vuiParams.chromaLocInfoPresentFlag = true;
        H264SpsPatcher.patch(sps, settings(sdkInt, false, 1920, 1080, 60,
                newerCodecAvailable, false, false, false));
        assertEquals(sps.vuiParams.videoSignalTypePresentFlag,
                sps.vuiParams.colourDescriptionPresentFlag);
        assertEquals(sps.vuiParams.videoSignalTypePresentFlag,
                sps.vuiParams.chromaLocInfoPresentFlag);
        return sps.vuiParams.videoSignalTypePresentFlag;
    }

    private static SeqParameterSet highProfileSps() {
        SeqParameterSet sps = new SeqParameterSet();
        sps.profileIdc = 100;
        sps.levelIdc = 50;
        sps.numRefFrames = 16;
        return sps;
    }

    private static H264SpsPatcher.Settings settings(
            int sdkInt, boolean refFrameInvalidationActive, int width, int height,
            int refreshRate, boolean newerCodecAvailable, boolean needsBitstreamRestrictions,
            boolean baselineHack, boolean constrainedHighProfile) {
        return new H264SpsPatcher.Settings(sdkInt, refFrameInvalidationActive, width, height,
                refreshRate, newerCodecAvailable, needsBitstreamRestrictions, baselineHack,
                constrainedHighProfile);
    }
}
