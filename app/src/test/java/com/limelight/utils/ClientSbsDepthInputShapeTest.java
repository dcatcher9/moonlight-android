package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ClientSbsDepthInputShapeTest {
    @Test
    public void selectsExactCommonAspectBuckets() {
        assertShape(ClientSbsDepthInputShape.select(16.0 / 9.0), 322, 182);
        assertShape(ClientSbsDepthInputShape.select(7.0 / 3.0), 350, 154);
        assertShape(ClientSbsDepthInputShape.select(32.0 / 9.0), 434, 126);
    }

    @Test
    public void otherAspectsMapToTheNearestStaticBucket() {
        assertShape(ClientSbsDepthInputShape.select(4.0 / 3.0), 322, 182);
        assertShape(ClientSbsDepthInputShape.select(16.0 / 10.0), 322, 182);
        assertShape(ClientSbsDepthInputShape.select(2.0), 322, 182);
        assertShape(ClientSbsDepthInputShape.select(21.0 / 10.0), 350, 154);
        assertShape(ClientSbsDepthInputShape.select(3.0), 434, 126);
        assertShape(ClientSbsDepthInputShape.select(48.0 / 9.0), 434, 126);
    }

    @Test
    public void everyBundledBucketIsPatchAlignedAndInsideBudget() {
        for (ClientSbsDepthInputShape shape : ClientSbsDepthInputShape.allBuckets()) {
            assertEquals(0, shape.getWidth() % ClientSbsDepthInputShape.PATCH_MULTIPLE);
            assertEquals(0, shape.getHeight() % ClientSbsDepthInputShape.PATCH_MULTIPLE);
            assertEquals(shape.getWidth() * shape.getHeight(), shape.getPixels());
            assertTrue(shape.getPixels() >= ClientSbsDepthInputShape.MIN_PIXELS);
            assertTrue(shape.getPixels() <= ClientSbsDepthInputShape.MAX_PIXELS);
        }
    }

    @Test
    public void everyBundledBucketHasNaturallyAlignedAttentionTokens() {
        assertAlignedShape(ClientSbsDepthInputShape.ASPECT_16_9,
                322, 182, 299, 300);
        assertAlignedShape(ClientSbsDepthInputShape.ASPECT_21_9,
                350, 154, 275, 276);
        assertAlignedShape(ClientSbsDepthInputShape.ASPECT_32_9,
                434, 126, 279, 280);

        assertEquals(3, ClientSbsDepthInputShape.allBuckets().length);
    }

    @Test
    public void selectionUsesTheBundledBucketAspectCutovers() {
        double firstCutover = Math.sqrt(
                ClientSbsDepthInputShape.ASPECT_16_9.getAspect()
                        * ClientSbsDepthInputShape.ASPECT_21_9.getAspect());
        double secondCutover = Math.sqrt(
                ClientSbsDepthInputShape.ASPECT_21_9.getAspect()
                        * ClientSbsDepthInputShape.ASPECT_32_9.getAspect());
        assertEquals(2.0052378963552, firstCutover, 1e-12);
        assertEquals(2.79790686554839, secondCutover, 1e-12);

        assertSame(ClientSbsDepthInputShape.ASPECT_16_9,
                ClientSbsDepthInputShape.select(firstCutover * (1.0 - 1e-9)));
        assertSame(ClientSbsDepthInputShape.ASPECT_21_9,
                ClientSbsDepthInputShape.select(firstCutover * (1.0 + 1e-9)));
        assertSame(ClientSbsDepthInputShape.ASPECT_21_9,
                ClientSbsDepthInputShape.select(secondCutover * (1.0 - 1e-9)));
        assertSame(ClientSbsDepthInputShape.ASPECT_32_9,
                ClientSbsDepthInputShape.select(secondCutover * (1.0 + 1e-9)));
    }

    @Test
    public void alignedFactoryRejectsNonC4AttentionTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientSbsDepthInputShape.createAligned(336, 168));
    }

    @Test
    public void selectionIsDeterministic() {
        ClientSbsDepthInputShape first = ClientSbsDepthInputShape.select(2.0);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, ClientSbsDepthInputShape.select(2.0));
            assertEquals(first.hashCode(), ClientSbsDepthInputShape.select(2.0).hashCode());
        }
    }

    @Test
    public void rejectsNonFiniteAndNonPositiveAspects() {
        assertInvalid(Double.NaN);
        assertInvalid(Double.NEGATIVE_INFINITY);
        assertInvalid(Double.POSITIVE_INFINITY);
        assertInvalid(-1.0);
        assertInvalid(0.0);
        assertInvalid(-0.0);
    }

    @Test
    public void extremeFiniteAspectsRemainBounded() {
        assertShape(ClientSbsDepthInputShape.select(Double.MIN_VALUE), 322, 182);
        assertShape(ClientSbsDepthInputShape.select(Double.MAX_VALUE), 434, 126);
    }

    private static void assertShape(ClientSbsDepthInputShape shape, int width, int height) {
        assertEquals(width, shape.getWidth());
        assertEquals(height, shape.getHeight());
        assertEquals(width * height, shape.getPixels());
        assertEquals(width + "x" + height + " (" + (width * height) + " px)",
                shape.toString());
    }

    private static void assertAlignedShape(ClientSbsDepthInputShape shape,
                                           int width,
                                           int height,
                                           int expectedImagePatches,
                                           int expectedTokens) {
        assertShape(shape, width, height);
        assertEquals(0, shape.getWidth() % ClientSbsDepthInputShape.PATCH_MULTIPLE);
        assertEquals(0, shape.getHeight() % ClientSbsDepthInputShape.PATCH_MULTIPLE);
        int imagePatches = shape.getWidth() / ClientSbsDepthInputShape.PATCH_MULTIPLE
                * (shape.getHeight() / ClientSbsDepthInputShape.PATCH_MULTIPLE);
        assertEquals(expectedImagePatches, imagePatches);
        assertEquals(expectedTokens, imagePatches + 1);
        assertEquals(0, expectedTokens % 4);
    }

    private static void assertInvalid(double sourceAspect) {
        try {
            ClientSbsDepthInputShape.select(sourceAspect);
            fail("Expected IllegalArgumentException for aspect " + sourceAspect);
        } catch (IllegalArgumentException expected) {
            assertEquals("sourceAspect must be finite and positive", expected.getMessage());
        }
    }
}
