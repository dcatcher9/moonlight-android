package com.limelight.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClientSbsZipDepthV2CalibrationTest {
    @Test
    public void everyProductionGraphCarriesItsOfflineFitRawScale() {
        assertEquals(0.04864449f,
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9
                        .getV2RawCoordinateScale(),
                0.0f);
        assertEquals(0.04707071f,
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9
                        .getV2RawCoordinateScale(),
                0.0f);
        assertEquals(0.05421491f,
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9
                        .getV2RawCoordinateScale(),
                0.0f);
    }

    @Test(expected = IllegalStateException.class)
    public void retiredUncalibratedGraphsCannotEnterRawV2Geometry() {
        ClientSbsModelManifest.MIDAS_V2_STATIC_16_9.getV2RawCoordinateScale();
    }
}
