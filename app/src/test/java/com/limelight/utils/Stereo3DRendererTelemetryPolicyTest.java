package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.sbs.SbsDepthTelemetrySnapshot;
import com.limelight.sbs.ClientSbsGpuDepthProcessor;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Stereo3DRendererTelemetryPolicyTest {
    @Test
    public void healthReadbackIsGatedWithDiagnosticsAndKeepsItsEnabledStride() {
        assertFalse(Stereo3DRenderer.shouldPollHealthTelemetry(false, 0));
        assertTrue(Stereo3DRenderer.shouldPollHealthTelemetry(true, 0));
        assertFalse(Stereo3DRenderer.shouldPollHealthTelemetry(true, 1));
        assertTrue(Stereo3DRenderer.shouldPollHealthTelemetry(true, 4));

        assertFalse(Stereo3DRenderer.shouldPollPerformanceTelemetry(false, 0));
        assertTrue(Stereo3DRenderer.shouldPollPerformanceTelemetry(true, 0));
        assertFalse(Stereo3DRenderer.shouldPollPerformanceTelemetry(true, 1));
    }

    @Test
    public void unclassifiedEdgeSentinelNeverEntersTheTrend() {
        assertFalse(Stereo3DRenderer.shouldAppendEdgeHistory(false, -1.0f));
        assertFalse(Stereo3DRenderer.shouldAppendEdgeHistory(true, -1.0f));
        assertFalse(Stereo3DRenderer.shouldAppendEdgeHistory(true, Float.NaN));
        assertTrue(Stereo3DRenderer.shouldAppendEdgeHistory(true, 0.04f));
    }

    @Test
    public void healthTelemetryRetryBackoffIsExponentialAndBounded() {
        assertEquals(0, Stereo3DRenderer.healthTelemetryRetryPolls(0));
        assertEquals(15, Stereo3DRenderer.healthTelemetryRetryPolls(1));
        assertEquals(30, Stereo3DRenderer.healthTelemetryRetryPolls(2));
        assertEquals(60, Stereo3DRenderer.healthTelemetryRetryPolls(3));
        assertEquals(120, Stereo3DRenderer.healthTelemetryRetryPolls(4));
        assertEquals(240, Stereo3DRenderer.healthTelemetryRetryPolls(5));
        assertEquals(240, Stereo3DRenderer.healthTelemetryRetryPolls(100));
        assertEquals(240, Stereo3DRenderer.healthTelemetryRetryPolls(Integer.MAX_VALUE));
    }

    @Test
    public void localV2DepthValidityDoesNotClaimRetiredAdaptiveOrAnchorFields() {
        int waiting = Stereo3DRenderer.depthTelemetryValidFields(false, false);
        assertTrue((waiting & SbsDepthTelemetrySnapshot.VALID_EFFECTIVE) != 0);
        assertFalse((waiting & SbsDepthTelemetrySnapshot.VALID_EDGE) != 0);
        assertFalse((waiting & SbsDepthTelemetrySnapshot.VALID_ANCHOR) != 0);
        assertFalse((waiting & SbsDepthTelemetrySnapshot.VALID_SUBJECT) != 0);
        assertFalse((waiting & SbsDepthTelemetrySnapshot.VALID_SCENE) != 0);

        int classified = Stereo3DRenderer.depthTelemetryValidFields(false, true);
        assertFalse((classified & SbsDepthTelemetrySnapshot.VALID_EDGE) != 0);
        assertFalse((classified & SbsDepthTelemetrySnapshot.VALID_ANCHOR) != 0);

        int ready = Stereo3DRenderer.depthTelemetryValidFields(true, true);
        assertTrue((ready & SbsDepthTelemetrySnapshot.VALID_SCENE) != 0);
        assertFalse((ready & SbsDepthTelemetrySnapshot.VALID_EDGE) != 0);
        assertFalse((ready & SbsDepthTelemetrySnapshot.VALID_ANCHOR) != 0);
        assertFalse((ready & SbsDepthTelemetrySnapshot.VALID_SUBJECT) != 0);
    }

    @Test
    public void heldCameraTelemetryDoesNotPublishRejectedMeanAsAnAdaptiveTarget()
            throws Exception {
        // Health state is sampled asynchronously; the renderer copies it before a subsequent GPU
        // observation can overwrite the buffer-backed view. Exercise that boundary with a complete
        // raw field rejected by the source clock, then with a genuinely accepted observation.
        ByteBuffer state = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
        state.putInt(68, 1);
        state.putInt(72, frameStateFlag("FRAME_STATE_CURRENT_DEPTH_VALID"));
        state.putFloat(128, 0.3f);
        state.putFloat(132, 0.4f);
        state.putFloat(140, 1.0f);
        state.putFloat(224, 20.0f);
        state.putFloat(232, 0.1f);
        state.putFloat(236, 0.7f);
        ClientSbsGpuDepthProcessor.HealthSnapshot health =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();
        Method update = health.getClass().getDeclaredMethod("updateFromState",
                ByteBuffer.class, long.class, int.class);
        update.setAccessible(true);
        Class<?> copyType = Class.forName(
                Stereo3DRenderer.class.getName() + "$DepthHealthState");
        Constructor<?> constructor = copyType.getDeclaredConstructor(health.getClass());
        constructor.setAccessible(true);

        update.invoke(health, state, 35L, 100);
        Object held = constructor.newInstance(health);
        assertEquals(0.3f, floatField(copyType, held, "rawZero"), 0.0f);
        assertEquals(20.0f, floatField(copyType, held, "rawInverseAmplitude"), 0.0f);
        assertTrue(Float.isNaN(floatField(copyType, held, "currentRawMean")));
        assertTrue(Float.isNaN(floatField(copyType, held, "targetRawInverseAmplitude")));
        assertTrue(Float.isNaN(floatField(copyType, held, "rawPercentileLow")));

        state.putInt(72, state.getInt(72)
                | frameStateFlag("FRAME_STATE_CURRENT_V2_VALID"));
        state.putFloat(228, 10.0f);
        state.putInt(248, 1);
        update.invoke(health, state, 40L, 100);
        Object accepted = constructor.newInstance(health);
        assertEquals(0.4f, floatField(copyType, accepted, "currentRawMean"), 0.0f);
        assertEquals(10.0f, floatField(copyType, accepted, "targetRawInverseAmplitude"), 0.0f);
        assertEquals(0.1f, floatField(copyType, accepted, "rawPercentileLow"), 0.0f);
        // Updating the reused health view cannot revise a previously published immutable sample.
        assertTrue(Float.isNaN(floatField(copyType, held, "currentRawMean")));
    }

    private static float floatField(Class<?> type, Object owner, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(owner);
    }

    private static int frameStateFlag(String name) throws Exception {
        Field field = Class.forName("com.limelight.sbs.ClientSbsShotCutPolicy")
                .getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
