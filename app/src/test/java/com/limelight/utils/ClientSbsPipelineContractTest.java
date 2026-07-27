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
}
