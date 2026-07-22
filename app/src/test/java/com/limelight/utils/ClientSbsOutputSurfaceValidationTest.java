package com.limelight.utils;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClientSbsOutputSurfaceValidationTest {
    @Test
    public void acceptsExactFullResolutionPackedSurfaceWithinLimits() {
        assertNull(ClientSbsOutputSurfaceValidation.validate(
                7680, 2160, 7680, 2160,
                3840, 2160, 16384, 16384, 16384));
    }

    @Test
    public void rejectsEglSurfaceThatWasSilentlyClamped() {
        String reason = ClientSbsOutputSurfaceValidation.validate(
                7680, 2160, 4096, 2160,
                3840, 2160, 16384, 16384, 16384);

        assertNotNull(reason);
        assertTrue(reason.contains("does not match"));
    }

    @Test
    public void rejectsEglSurfaceWhosePackedOrientationWasSwapped() {
        String reason = ClientSbsOutputSurfaceValidation.validate(
                4320, 3840, 3840, 4320,
                2160, 3840, 4096, 4096, 4096);

        assertNotNull(reason);
        assertTrue(reason.contains("does not match"));
    }

    @Test
    public void packedSurfaceMayExceedViewportWidthWhenEachEyeFits() {
        assertNull(ClientSbsOutputSurfaceValidation.validate(
                7680, 2160, 7680, 2160,
                3840, 2160, 4096, 4096, 16384));
    }

    @Test
    public void rejectsPerEyeViewportBeyondDeviceLimit() {
        String reason = ClientSbsOutputSurfaceValidation.validate(
                7680, 2160, 7680, 2160,
                3840, 2160, 2048, 4096, 16384);

        assertNotNull(reason);
        assertTrue(reason.contains("GL_MAX_VIEWPORT_DIMS"));
    }

    @Test
    public void rejectsPerEyeColorTextureBeyondDeviceLimit() {
        String reason = ClientSbsOutputSurfaceValidation.validate(
                7680, 2160, 7680, 2160,
                3840, 2160, 16384, 16384, 2048);

        assertNotNull(reason);
        assertTrue(reason.contains("GL_MAX_TEXTURE_SIZE"));
    }

    @Test
    public void rejectsOverrideThatIsNotTwoFullResolutionEyes() {
        String reason = ClientSbsOutputSurfaceValidation.validate(
                4096, 2160, 4096, 2160,
                3840, 2160, 16384, 16384, 16384);

        assertNotNull(reason);
        assertTrue(reason.contains("2W x H"));
    }
}
