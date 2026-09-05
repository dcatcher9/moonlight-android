package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsPipelineContractTest {
    private static final String ZIPDEPTH_BASE = ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID;

    @Test
    public void zipDepthUsesItsCompleteNearestAspectIntervals() {
        double firstBoundary = Math.sqrt((672.0 / 384.0) * (896.0 / 384.0));
        double secondBoundary = Math.sqrt((896.0 / 384.0) * (928.0 / 384.0));

        assertFalse(ClientSbsPipelineContract.sameForStream(
                ZIPDEPTH_BASE, firstBoundary - 0.001, firstBoundary + 0.001));
        assertFalse(ClientSbsPipelineContract.sameForStream(
                ZIPDEPTH_BASE, secondBoundary - 0.001, secondBoundary + 0.001));
        assertTrue(ClientSbsPipelineContract.sameForStream(ZIPDEPTH_BASE, 1.99, 2.01));
        assertTrue(ClientSbsPipelineContract.sameForStream(ZIPDEPTH_BASE, 2.03, 2.37));
        assertTrue(ClientSbsPipelineContract.sameForStream(
                ZIPDEPTH_BASE, 2.38, 32.0 / 9.0));
    }

    @Test
    public void zipDepthBucketsOwnTheirModelAndDepthDimensions() {
        ClientSbsPipelineContract sixteenNine = ClientSbsPipelineContract.forStream(
                ZIPDEPTH_BASE, 16.0 / 9.0);
        ClientSbsPipelineContract twentyOneNine = ClientSbsPipelineContract.forStream(
                ZIPDEPTH_BASE, 21.0 / 9.0);
        ClientSbsPipelineContract ultrawide = ClientSbsPipelineContract.forStream(
                ZIPDEPTH_BASE, 32.0 / 9.0);

        assertEquals(672, sixteenNine.getDepthOutputWidth());
        assertEquals(896, twentyOneNine.getDepthOutputWidth());
        assertEquals(928, ultrawide.getDepthOutputWidth());
        assertNotEquals(sixteenNine, twentyOneNine);
        assertNotEquals(twentyOneNine, ultrawide);
    }

    @Test
    public void portraitUsesAspectFitCropInsteadOfStretchingZipDepth() {
        ClientSbsPipelineContract portrait =
                ClientSbsPipelineContract.forStream(ZIPDEPTH_BASE, 9.0 / 16.0);
        ClientSbsPipelineContract landscape =
                ClientSbsPipelineContract.forStream(ZIPDEPTH_BASE, 16.0 / 9.0);

        assertFalse(portrait.usesDirectFullFrameResize());
        assertEquals((9.0f / 16.0f) / (672.0f / 384.0f),
                portrait.getModelContentAspect(), 0.0001f);
        assertEquals(216, portrait.getDepthOutputWidth());
        assertEquals(384, portrait.getDepthOutputHeight());
        assertNotEquals(landscape, portrait);
        assertTrue(ClientSbsPipelineContract.sameForStream(
                ZIPDEPTH_BASE, 1080.0 / 1920.0, 1440.0 / 2560.0));
    }

    @Test
    public void sameAspectFourKTo1080pKeepsTheZipDepthPipeline() {
        assertTrue(ClientSbsPipelineContract.sameForStream(
                ZIPDEPTH_BASE, 3840.0 / 2160.0, 1920.0 / 1080.0));
    }

    @Test
    public void retiredFamiliesCannotCreateAProductionPipelineContract() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientSbsPipelineContract.forStream(
                        ClientSbsModelManifest.MIDAS_V2_STATIC_ID, 16.0 / 9.0));
        assertThrows(IllegalArgumentException.class,
                () -> ClientSbsPipelineContract.forStream(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_ID, 16.0 / 9.0));
        assertThrows(IllegalArgumentException.class,
                () -> ClientSbsPipelineContract.forStream(
                        ClientSbsModelManifest.DEPTHART_S448_FP16_ID, 16.0 / 9.0));
    }
}
