package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsPipelineContractTest {
    private static final String DA_V2 =
            ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_ID;
    private static final String MIDAS_V2 = ClientSbsModelManifest.MIDAS_V2_STATIC_ID;
    private static final String DEPTHART_S448 = ClientSbsModelManifest.DEPTHART_S448_FP16_ID;
    private static final String ZIPDEPTH_BASE = ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID;

    @Test
    public void modelSpecificBoundariesArePartOfTheContract() {
        double daBoundary = Math.sqrt((322.0 / 182.0) * (350.0 / 154.0));
        double midasBoundary = Math.sqrt((352.0 / 192.0) * (384.0 / 160.0));

        assertFalse(ClientSbsPipelineContract.sameForStream(
                DA_V2, daBoundary - 0.001, daBoundary + 0.001));
        assertFalse(ClientSbsPipelineContract.sameForStream(
                MIDAS_V2, midasBoundary - 0.001, midasBoundary + 0.001));
    }

    @Test
    public void aspect205To237IsLiveForDepthAnythingButReconnectsForMidas() {
        assertTrue(ClientSbsPipelineContract.sameForStream(DA_V2, 2.05, 2.37));
        assertFalse(ClientSbsPipelineContract.sameForStream(MIDAS_V2, 2.05, 2.37));
    }

    @Test
    public void compiledProbeIdentityIsComparedSeparatelyFromTheMidasManifest() {
        ClientSbsPipelineContract beforeProbeBoundary =
                ClientSbsPipelineContract.forStream(MIDAS_V2, 1.99);
        ClientSbsPipelineContract afterProbeBoundary =
                ClientSbsPipelineContract.forStream(MIDAS_V2, 2.05);

        // Both aspects still use MiDaS's 352x192 graph, but DA-aligned probe bucketing changes
        // the literal embedded in the already-compiled reprojection programs.
        assertEquals(beforeProbeBoundary.getModelManifestId(),
                afterProbeBoundary.getModelManifestId());
        assertNotEquals(beforeProbeBoundary.getReprojectionProbeSteps(),
                afterProbeBoundary.getReprojectionProbeSteps());
        assertNotEquals(beforeProbeBoundary, afterProbeBoundary);
    }

    @Test
    public void portraitUsesAspectFitCropInsteadOfStretchingTheLandscapeModel() {
        ClientSbsPipelineContract portrait =
                ClientSbsPipelineContract.forStream(MIDAS_V2, 9.0 / 16.0);
        ClientSbsPipelineContract landscape =
                ClientSbsPipelineContract.forStream(MIDAS_V2, 16.0 / 9.0);

        assertFalse(portrait.usesDirectFullFrameResize());
        assertEquals((9.0f / 16.0f) / (352.0f / 192.0f),
                portrait.getModelContentAspect(), 0.0001f);
        assertEquals(108, portrait.getDepthOutputWidth());
        assertEquals(192, portrait.getDepthOutputHeight());
        assertEquals(10, portrait.getReprojectionProbeSteps());
        assertNotEquals(landscape, portrait);
    }

    @Test
    public void sameAspectPortraitResizesShareOnePipelineContract() {
        assertTrue(ClientSbsPipelineContract.sameForStream(
                MIDAS_V2, 1080.0 / 1920.0, 1440.0 / 2560.0));
    }

    @Test
    public void fourKTo1080pLiveResizeKeepsBothLandscapePipelineContracts() {
        double fourKAspect = 3840.0 / 2160.0;
        double fullHdAspect = 1920.0 / 1080.0;

        assertTrue(ClientSbsPipelineContract.sameForStream(
                DA_V2, fourKAspect, fullHdAspect));
        assertTrue(ClientSbsPipelineContract.sameForStream(
                MIDAS_V2, fourKAspect, fullHdAspect));
    }

    @Test
    public void depthArtOutputWidthControlsTheCompiledProbeBudget() {
        ClientSbsPipelineContract sixteenNineContract =
                ClientSbsPipelineContract.forStream(DEPTHART_S448, 16.0 / 9.0);
        ClientSbsPipelineContract twentyOneNineContract =
                ClientSbsPipelineContract.forStream(DEPTHART_S448, 21.0 / 9.0);
        ClientSbsPipelineContract thirtyTwoNineContract =
                ClientSbsPipelineContract.forStream(DEPTHART_S448, 32.0 / 9.0);

        assertEquals(672, sixteenNineContract.getDepthOutputWidth());
        assertEquals(36, sixteenNineContract.getReprojectionProbeSteps());
        assertEquals(928, twentyOneNineContract.getDepthOutputWidth());
        assertEquals(33, twentyOneNineContract.getReprojectionProbeSteps());
        assertEquals(928, thirtyTwoNineContract.getDepthOutputWidth());
        assertEquals(33, thirtyTwoNineContract.getReprojectionProbeSteps());
        assertTrue(ClientSbsPipelineContract.sameForStream(
                DEPTHART_S448, 2.01, 2.04));
        assertTrue(ClientSbsPipelineContract.sameForStream(
                DEPTHART_S448, 2.10, 32.0 / 9.0));
    }

    @Test
    public void zipDepthUsesItsOwnCompleteNearestAspectIntervals() {
        double firstBoundary = Math.sqrt((672.0 / 384.0) * (896.0 / 384.0));
        double secondBoundary = Math.sqrt((896.0 / 384.0) * (928.0 / 384.0));

        assertEquals(4.0f / 3.0f,
                ClientSbsModelManifest.minimumLandscapeAspectForDedicatedProbeBucket(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9), 0.0001f);
        assertEquals((float) firstBoundary,
                ClientSbsModelManifest.minimumLandscapeAspectForDedicatedProbeBucket(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9), 0.0001f);
        assertEquals((float) secondBoundary,
                ClientSbsModelManifest.minimumLandscapeAspectForDedicatedProbeBucket(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9), 0.0001f);

        assertFalse(ClientSbsPipelineContract.sameForStream(
                ZIPDEPTH_BASE, firstBoundary - 0.001, firstBoundary + 0.001));
        assertFalse(ClientSbsPipelineContract.sameForStream(
                ZIPDEPTH_BASE, secondBoundary - 0.001, secondBoundary + 0.001));

        // The first pair crosses a legacy DA-V2 probe boundary but not ZipDepth's first model
        // boundary. None of these in-bucket changes should rebuild an identical ZipDepth target.
        assertTrue(ClientSbsPipelineContract.sameForStream(ZIPDEPTH_BASE, 1.99, 2.01));
        assertTrue(ClientSbsPipelineContract.sameForStream(ZIPDEPTH_BASE, 2.03, 2.37));
        assertTrue(ClientSbsPipelineContract.sameForStream(
                ZIPDEPTH_BASE, 2.38, 32.0 / 9.0));

        ClientSbsPipelineContract sixteenNine = ClientSbsPipelineContract.forStream(
                ZIPDEPTH_BASE, 16.0 / 9.0);
        ClientSbsPipelineContract twentyOneNine = ClientSbsPipelineContract.forStream(
                ZIPDEPTH_BASE, 21.0 / 9.0);
        ClientSbsPipelineContract ultrawide = ClientSbsPipelineContract.forStream(
                ZIPDEPTH_BASE, 32.0 / 9.0);
        assertEquals(672, sixteenNine.getDepthOutputWidth());
        assertEquals(36, sixteenNine.getReprojectionProbeSteps());
        assertEquals(896, twentyOneNine.getDepthOutputWidth());
        assertEquals(32, twentyOneNine.getReprojectionProbeSteps());
        assertEquals(928, ultrawide.getDepthOutputWidth());
        assertEquals(28, ultrawide.getReprojectionProbeSteps());
    }

    @Test
    public void zipDepthPortraitKeepsReflectedAspectFit() {
        ClientSbsPipelineContract portrait =
                ClientSbsPipelineContract.forStream(ZIPDEPTH_BASE, 9.0 / 16.0);

        assertFalse(portrait.usesDirectFullFrameResize());
        assertEquals((9.0f / 16.0f) / (672.0f / 384.0f),
                portrait.getModelContentAspect(), 0.0001f);
        assertEquals(216, portrait.getDepthOutputWidth());
        assertEquals(384, portrait.getDepthOutputHeight());
    }
}
