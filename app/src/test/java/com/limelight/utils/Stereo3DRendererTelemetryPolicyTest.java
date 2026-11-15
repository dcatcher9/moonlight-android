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
        assertFalse(ClientSbsPerfStats.shouldPollHealthTelemetry(false, 0));
        assertTrue(ClientSbsPerfStats.shouldPollHealthTelemetry(true, 0));
        assertFalse(ClientSbsPerfStats.shouldPollHealthTelemetry(true, 1));
        assertTrue(ClientSbsPerfStats.shouldPollHealthTelemetry(true, 4));
    }

    @Test
    public void healthTelemetryRetryBackoffIsExponentialAndBounded() {
        assertEquals(0, ClientSbsPerfStats.healthTelemetryRetryPolls(0));
        assertEquals(15, ClientSbsPerfStats.healthTelemetryRetryPolls(1));
        assertEquals(30, ClientSbsPerfStats.healthTelemetryRetryPolls(2));
        assertEquals(60, ClientSbsPerfStats.healthTelemetryRetryPolls(3));
        assertEquals(120, ClientSbsPerfStats.healthTelemetryRetryPolls(4));
        assertEquals(240, ClientSbsPerfStats.healthTelemetryRetryPolls(5));
        assertEquals(240, ClientSbsPerfStats.healthTelemetryRetryPolls(100));
        assertEquals(240, ClientSbsPerfStats.healthTelemetryRetryPolls(Integer.MAX_VALUE));
    }

    @Test
    public void localV2DepthValidityClaimsOnlyFieldsTheClientMeasures() {
        int fields = Stereo3DRenderer.depthTelemetryValidFields();
        assertEquals(SbsDepthTelemetrySnapshot.VALID_CONFIG
                | SbsDepthTelemetrySnapshot.VALID_EFFECTIVE
                | SbsDepthTelemetrySnapshot.VALID_DEPTH_FRACTION
                | SbsDepthTelemetrySnapshot.VALID_FAULTS, fields);
        // Host-only fields: the client has no cut detector, cut range, scene age or change
        // fraction, and no adaptive-pop, anchor or subject classifier.
        for (int hostOnly : new int[] {
                SbsDepthTelemetrySnapshot.VALID_EDGE, SbsDepthTelemetrySnapshot.VALID_CHANGE,
                SbsDepthTelemetrySnapshot.VALID_ANCHOR, SbsDepthTelemetrySnapshot.VALID_SUBJECT,
                SbsDepthTelemetrySnapshot.VALID_RANGE, SbsDepthTelemetrySnapshot.VALID_SCENE,
                SbsDepthTelemetrySnapshot.VALID_CUTS}) {
            assertFalse((fields & hostOnly) != 0);
        }
    }

    @Test
    public void heldCameraTelemetryDoesNotPublishRejectedMeanAsAnAdaptiveTarget()
            throws Exception {
        // Health state is sampled asynchronously; the renderer copies it before a subsequent GPU
        // observation can overwrite the buffer-backed view. Exercise that boundary with a complete
        // raw field rejected by the source clock, then with a genuinely accepted observation.
        // std430 ProcessorState: frameState at 0, v2Camera at 16, adaptiveCamera at 32 and
        // cameraClock at 48 in a 64-byte block.
        ByteBuffer state = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        state.putInt(0, ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_DEPTH_VALID);
        state.putFloat(16, 0.3f);
        state.putFloat(20, 0.4f);
        state.putFloat(28, 1.0f);
        state.putFloat(32, 20.0f);
        state.putFloat(40, 0.1f);
        state.putFloat(44, 0.7f);
        ClientSbsGpuDepthProcessor.HealthSnapshot health =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();
        Method update = health.getClass().getDeclaredMethod("updateFromState",
                ByteBuffer.class, long.class, int.class);
        update.setAccessible(true);
        Class<?> copyType = Class.forName(
                ClientSbsPerfStats.class.getName() + "$DepthHealthState");
        Constructor<?> constructor = copyType.getDeclaredConstructor(health.getClass());
        constructor.setAccessible(true);

        update.invoke(health, state, 35L, 100);
        Object held = constructor.newInstance(health);
        assertEquals(0.3f, floatField(copyType, held, "rawZero"), 0.0f);
        assertEquals(20.0f, floatField(copyType, held, "rawInverseAmplitude"), 0.0f);
        assertTrue(Float.isNaN(floatField(copyType, held, "currentRawMean")));
        assertTrue(Float.isNaN(floatField(copyType, held, "targetRawInverseAmplitude")));
        assertTrue(Float.isNaN(floatField(copyType, held, "rawPercentileLow")));

        state.putInt(0, state.getInt(0)
                | ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_V2_VALID);
        state.putFloat(36, 10.0f);
        state.putInt(56, 1);
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
}
