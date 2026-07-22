package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class DecodedVideoDimensionsTest {
    @Test
    public void codedDimensionsReplaceNegotiatedFallback() {
        DecodedVideoDimensions dimensions = DecodedVideoDimensions.resolve(
                new DecodedVideoDimensions(3840, 2160),
                7680, 2160, null, null, null, null);

        assertEquals(7680, dimensions.width);
        assertEquals(2160, dimensions.height);
    }

    @Test
    public void completeCropUsesInclusiveVisibleDimensions() {
        DecodedVideoDimensions dimensions = DecodedVideoDimensions.resolve(
                new DecodedVideoDimensions(3840, 2160),
                3840, 2176, 0, 8, 3839, 2167);

        assertEquals(3840, dimensions.width);
        assertEquals(2160, dimensions.height);
    }

    @Test
    public void partialCropFallsBackToCodedDimensions() {
        DecodedVideoDimensions dimensions = DecodedVideoDimensions.resolve(
                new DecodedVideoDimensions(3840, 2160),
                7680, 2176, 0, 8, 7679, null);

        assertEquals(7680, dimensions.width);
        assertEquals(2176, dimensions.height);
    }

    @Test
    public void invalidCropFallsBackToCodedDimensions() {
        DecodedVideoDimensions dimensions = DecodedVideoDimensions.resolve(
                new DecodedVideoDimensions(3840, 2160),
                7680, 2160, 0, 0, 8000, 2159);

        assertEquals(7680, dimensions.width);
        assertEquals(2160, dimensions.height);
    }

    @Test
    public void offsetCropCannotExtendPastCodedBounds() {
        DecodedVideoDimensions dimensions = DecodedVideoDimensions.resolve(
                new DecodedVideoDimensions(3840, 2160),
                3840, 2160, 100, 0, 3899, 2159);

        assertEquals(3840, dimensions.width);
        assertEquals(2160, dimensions.height);
    }

    @Test
    public void invalidCodedDimensionsRetainCoherentFallbackObject() {
        DecodedVideoDimensions fallback = new DecodedVideoDimensions(3840, 2160);

        DecodedVideoDimensions dimensions = DecodedVideoDimensions.resolve(
                fallback, 0, -1, null, null, null, null);

        assertSame(fallback, dimensions);
    }

    @Test
    public void partialCodedDimensionsRetainCoherentFallbackPair() {
        DecodedVideoDimensions fallback = new DecodedVideoDimensions(3840, 2160);

        DecodedVideoDimensions dimensions = DecodedVideoDimensions.resolve(
                fallback, 7680, null, null, null, null, null);

        assertSame(fallback, dimensions);
    }
}
