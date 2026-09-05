package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ClientSbsModeSettingsModelTest {
    @Test
    public void fixedZipDepthRuntimeUsesThreeShort384AspectBuckets() {
        assertEquals("672 x 384", ClientSbsModeSettingsModel.selectBucket(1920, 1080));
        assertEquals("896 x 384", ClientSbsModeSettingsModel.selectBucket(2560, 1080));
        assertEquals("928 x 384", ClientSbsModeSettingsModel.selectBucket(3440, 1440));
        assertEquals("928 x 384", ClientSbsModeSettingsModel.selectBucket(5120, 1440));
    }

    @Test
    public void fixedModelStateContainsOnlyBucketAndRuntimeStatus() {
        ClientSbsModeSettingsModel model =
                new ClientSbsModeSettingsModel("672 x 384", "Ready");

        assertEquals("672 x 384", model.bucket);
        assertEquals("Ready", model.status);
    }

    @Test
    public void invalidStreamDimensionsCannotSelectABucket() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientSbsModeSettingsModel.selectBucket(0, 1080));
        assertThrows(IllegalArgumentException.class,
                () -> ClientSbsModeSettingsModel.selectBucket(1920, -1));
    }
}
