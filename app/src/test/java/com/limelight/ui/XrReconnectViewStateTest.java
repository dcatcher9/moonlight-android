package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Intent;

import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public final class XrReconnectViewStateTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void handoffRoundTripsHeightAndRealWorldPose() {
        Intent intent = new Intent();
        new XrReconnectViewState(2.75f, new Pose(
                new Vector3(0.25f, 1.1f, -3.4f),
                new Quaternion(0.0f, 0.25f, 0.0f, 0.9682458f)))
                .writeTo(intent);

        XrReconnectViewState restored = XrReconnectViewState.consumeFrom(intent);

        assertNotNull(restored);
        assertEquals(2.75f, restored.panelHeightMeters, EPSILON);
        assertEquals(0.25f, restored.realWorldPose.getTranslation().getX(), EPSILON);
        assertEquals(1.1f, restored.realWorldPose.getTranslation().getY(), EPSILON);
        assertEquals(-3.4f, restored.realWorldPose.getTranslation().getZ(), EPSILON);
        assertNull(XrReconnectViewState.consumeFrom(intent));
    }

    @Test
    public void effectiveHeightFoldsEntityScaleIntoCanonicalShape() {
        assertEquals(3.0f,
                XrReconnectViewState.effectiveHeight(2.0f, 1.5f, 2.0f), EPSILON);
        assertEquals(2.0f,
                XrReconnectViewState.effectiveHeight(
                        Float.NaN, 1.0f, 2.0f), EPSILON);
        assertEquals(2.0f,
                XrReconnectViewState.localHeight(3.0f, 1.5f), EPSILON);
    }

    @Test
    public void missingTransientStateDoesNotOverrideDurableViewState() {
        assertNull(XrReconnectViewState.consumeFrom(new Intent()));
    }
}
