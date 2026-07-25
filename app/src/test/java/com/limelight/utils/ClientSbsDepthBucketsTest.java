package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The depth bucket is the unit of immutability for Client SBS, so it is also the boundary between
 * a live resolution change and one that needs a reconnect. These tests pin the selection boundary
 * itself rather than trusting arithmetic done by hand.
 */
public class ClientSbsDepthBucketsTest {
    // Bucket aspects: 322/182, 350/154, 434/126. select() minimizes |log(bucket / source)|, so the
    // boundary between two buckets is their geometric mean.
    private static final double BOUNDARY_16_9_TO_21_9 =
            Math.sqrt((322.0 / 182.0) * (350.0 / 154.0));
    private static final double BOUNDARY_21_9_TO_32_9 =
            Math.sqrt((350.0 / 154.0) * (434.0 / 126.0));

    @Test
    public void everyStandardPresetLandsInTheSixteenNineBucket() {
        // The whole XrResolutionSelector ladder is 16:9, so preset-to-preset changes never move
        // the bucket and are therefore always live-applicable on the bucket axis.
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_16_9,
                ClientSbsDepthBuckets.select(1280.0 / 720.0));
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_16_9,
                ClientSbsDepthBuckets.select(1920.0 / 1080.0));
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_16_9,
                ClientSbsDepthBuckets.select(2560.0 / 1440.0));
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_16_9,
                ClientSbsDepthBuckets.select(3840.0 / 2160.0));
    }

    @Test
    public void sixteenTenSharesTheSixteenNineBucket() {
        // An aspect change within a bucket needs no model change and no shader regeneration.
        assertTrue(ClientSbsDepthBuckets.sameBucket(16.0 / 9.0, 16.0 / 10.0));
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_16_9,
                ClientSbsDepthBuckets.select(16.0 / 10.0));
    }

    @Test
    public void theSixteenNineToTwentyOneNineBoundaryIsTheGeometricMean() {
        assertEquals(2.0052, BOUNDARY_16_9_TO_21_9, 1e-4);
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_16_9,
                ClientSbsDepthBuckets.select(BOUNDARY_16_9_TO_21_9 - 0.01));
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_21_9,
                ClientSbsDepthBuckets.select(BOUNDARY_16_9_TO_21_9 + 0.01));
        assertFalse(ClientSbsDepthBuckets.sameBucket(
                BOUNDARY_16_9_TO_21_9 - 0.01, BOUNDARY_16_9_TO_21_9 + 0.01));
    }

    @Test
    public void theTwentyOneNineToThirtyTwoNineBoundaryIsTheGeometricMean() {
        assertEquals(2.7979, BOUNDARY_21_9_TO_32_9, 1e-4);
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_21_9,
                ClientSbsDepthBuckets.select(BOUNDARY_21_9_TO_32_9 - 0.01));
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_32_9,
                ClientSbsDepthBuckets.select(BOUNDARY_21_9_TO_32_9 + 0.01));
    }

    @Test
    public void ultrawideAndSuperUltrawidePresetsCrossTheBucketBoundary() {
        assertFalse(ClientSbsDepthBuckets.sameBucket(1920.0 / 1080.0, 2560.0 / 1080.0));
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_21_9,
                ClientSbsDepthBuckets.select(2560.0 / 1080.0));
        assertEquals(ClientSbsDepthBuckets.Bucket.ASPECT_32_9,
                ClientSbsDepthBuckets.select(3840.0 / 1080.0));
    }

    @Test
    public void probeStepsAreInvariantWithinABucketAndDifferAcrossThem() {
        // This is the property that lets a same-bucket resize skip shader regeneration: the
        // PROBE_STEPS literal substituted into the shader SOURCE is bucket-derived.
        assertEquals(ClientSbsShaders.probeStepsForAspect(16.0f / 9.0f),
                ClientSbsShaders.probeStepsForAspect(16.0f / 10.0f));
        assertEquals(19, ClientSbsShaders.probeStepsForAspect(1920f / 1080f));
        assertEquals(14, ClientSbsShaders.probeStepsForAspect(2560f / 1080f));
        assertEquals(12, ClientSbsShaders.probeStepsForAspect(3840f / 1080f));
    }

    @Test
    public void sameBucketAspectsProduceByteIdenticalShaderSource() {
        assertEquals(ClientSbsShaders.createReprojectionFragment(16.0f / 9.0f),
                ClientSbsShaders.createReprojectionFragment(16.0f / 10.0f));
        assertEquals(ClientSbsShaders.createWarpMapFragment(16.0f / 9.0f),
                ClientSbsShaders.createWarpMapFragment(16.0f / 10.0f));
    }
}
