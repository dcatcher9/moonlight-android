package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.xr.runtime.math.Vector3;

import org.junit.Test;

public final class XrGazeTargetTest {
    @Test
    public void forwardRayHitsFromEitherSideOfThePanelPlane() {
        assertTrue(looksAt(new Vector3(0f, 0f, 2f), new Vector3(0f, 0f, -1f)));
        assertTrue(looksAt(new Vector3(0f, 0f, -2f), new Vector3(0f, 0f, 1f)));
    }

    @Test
    public void offAxisRayIntersectsAtItsProjectedPosition() {
        assertTrue(looksAt(new Vector3(2f, 1f, 2f), new Vector3(-2f, -1f, -2f)));
        assertTrue(looksAt(new Vector3(0f, 0f, 2f), new Vector3(0.4f, 0.2f, -1f)));
        assertFalse(looksAt(new Vector3(0f, 0f, 2f), new Vector3(0.6f, 0f, -1f)));
        assertFalse(looksAt(new Vector3(0f, 0f, 2f), new Vector3(0f, 0.3f, -1f)));
    }

    @Test
    public void panelEdgesAreIncludedButPointsBeyondEitherAxisAreNot() {
        Vector3 direction = new Vector3(0f, 0f, -1f);
        assertTrue(looksAt(new Vector3(1f, 0.5f, 2f), direction));
        assertTrue(looksAt(new Vector3(-1f, -0.5f, 2f), direction));
        assertFalse(looksAt(new Vector3(1.001f, 0f, 2f), direction));
        assertFalse(looksAt(new Vector3(0f, -0.501f, 2f), direction));
    }

    @Test
    public void raysAwayFromOrParallelToThePanelCannotHit() {
        Vector3 origin = new Vector3(0f, 0f, 2f);
        assertFalse(looksAt(origin, new Vector3(0f, 0f, 1f)));
        assertFalse(looksAt(origin, new Vector3(1f, 0f, 0f)));
        assertFalse(looksAt(origin, new Vector3(0f, 0f, 0f)));
        assertFalse(looksAt(origin, new Vector3(1f, 0f, -Float.MIN_VALUE)));
    }

    @Test
    public void rayStartingInsideThePanelHasAZeroDistanceHit() {
        assertTrue(looksAt(new Vector3(0.2f, -0.1f, 0f), new Vector3(0f, 0f, -1f)));
        assertFalse(looksAt(new Vector3(2f, 0f, 0f), new Vector3(0f, 0f, -1f)));
    }

    @Test
    public void transformedLocalCoordinatesUseUnscaledPanelDimensions() {
        Vector3 direction = new Vector3(0f, 0f, -1f);
        // The caller already divided the eye position by the panel's 2x entity scale.
        assertTrue(XrGazeTarget.isLookingAt(new Vector3(0.75f, 0f, 1f), direction, 2f, 1f));
        assertFalse(XrGazeTarget.isLookingAt(new Vector3(1.5f, 0f, 2f), direction, 2f, 1f));
    }

    @Test
    public void invalidDimensionsAndMissingRayComponentsFailClosed() {
        Vector3 origin = new Vector3(0f, 0f, 2f);
        Vector3 direction = new Vector3(0f, 0f, -1f);
        for (float invalid : new float[] {0f, -1f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertFalse(XrGazeTarget.isLookingAt(origin, direction, invalid, 1f));
            assertFalse(XrGazeTarget.isLookingAt(origin, direction, 2f, invalid));
        }
        assertFalse(XrGazeTarget.isLookingAt(null, direction, 2f, 1f));
        assertFalse(XrGazeTarget.isLookingAt(origin, null, 2f, 1f));
    }

    @Test
    public void nonFiniteRayCoordinatesFailClosed() {
        Vector3 origin = new Vector3(0f, 0f, 2f);
        Vector3 direction = new Vector3(0f, 0f, -1f);
        for (float invalid : new float[] {Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertFalse(looksAt(new Vector3(invalid, 0f, 2f), direction));
            assertFalse(looksAt(new Vector3(0f, invalid, 2f), direction));
            assertFalse(looksAt(new Vector3(0f, 0f, invalid), direction));
            assertFalse(looksAt(origin, new Vector3(invalid, 0f, -1f)));
            assertFalse(looksAt(origin, new Vector3(0f, invalid, -1f)));
            assertFalse(looksAt(origin, new Vector3(0f, 0f, invalid)));
        }
    }

    private static boolean looksAt(Vector3 origin, Vector3 direction) {
        return XrGazeTarget.isLookingAt(origin, direction, 2f, 1f);
    }
}
