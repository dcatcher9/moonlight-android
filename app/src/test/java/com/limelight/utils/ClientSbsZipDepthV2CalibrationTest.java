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
    public void uncalibratedGraphsCannotEnterRawV2Geometry() {
        ClientSbsModelManifest.createCheckpointBenchmarkManifest(
                "external-checkpoint-t1504",
                "d707ea7be998540979ac4b5d630770810b531aa0944ae3a52853d9af85ffb6dc",
                new int[] {1, 196, 350, 3},
                new int[] {1, 196, 350, 32},
                ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16)
                .getV2RawCoordinateScale();
    }
}
