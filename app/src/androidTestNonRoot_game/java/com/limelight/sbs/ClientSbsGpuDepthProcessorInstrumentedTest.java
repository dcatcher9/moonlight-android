package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

/** Physical-device gate for the raw ZipDepth field and shot-latched V2 camera handoff. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsGpuDepthProcessorInstrumentedTest {
    private static final String TAG = "ClientSbsRawV2";
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final int WIDTH = 32;
    private static final int HEIGHT = 32;

    @Test
    public void heldGeometryCandidatePublishesCurrentRawWithoutAdvancingReliableHistory()
            throws Exception {
        try (EglFixture ignored = EglFixture.create()) {
            int rawBuffer = createRawBuffer(0.0f, false);
            int sceneCutBuffer = createSceneCutBuffer(0);
            try (ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                    WIDTH, HEIGHT, 1.0f, false, 60.0f);
                 TextureProbe probe = new TextureProbe()) {
                processor.setHealthSamplingFocused(true);

                ClientSbsGpuDepthProcessor.Result baselineResult = null;
                // The ninth source update crosses the geometry arming guard. Keeping the same
                // field here also makes the retained normalized texture deterministic.
                for (int frame = 1; frame <= 9; frame++) {
                    baselineResult = processor.processRendererOwnedWithGpuSceneCut(
                            rawBuffer, 0, Float.BYTES, sceneCutBuffer, 0);
                }
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot baselineHealth =
                        processor.pollHealthSnapshot();
                assertNotNull(baselineHealth);
                assertTrue(baselineHealth.isCurrentGeometryReady());
                assertTrue(baselineHealth.didHistoryAdvance());
                assertNotNull(baselineResult);
                float baselineRangeLow = baselineHealth.getEffectiveRangeLow();
                float baselineRangeHigh = baselineHealth.getEffectiveRangeHigh();

                float baselineReliable = probe.readRed(
                        processor.getReliableDepthTextureId(), 0, 0);
                float baselineTemporal = probe.readRed(
                        processor.getTemporalDepthTextureId(), 0, 0);
                float[] baselineProfile = probe.readRgba(
                        baselineResult.getProfileTextureId(), 0, 0);
                assertTrue(baselineProfile[3] > 0.5f);

                // Reverse every ordinal relation and shift the complete raw field. The independent
                // structure evidence makes this the first geometry-confirmation candidate: Apollo
                // holds reliable comparison history, but still renders this current valid field
                // through the already-latched shot camera.
                float heldOffset = 10.0f;
                updateRawBufferReversed(rawBuffer, heldOffset);
                updateSceneCutBuffer(sceneCutBuffer, 4);
                ClientSbsGpuDepthProcessor.Result heldResult =
                        processor.processRendererOwnedWithGpuSceneCut(
                                rawBuffer, 0, Float.BYTES, sceneCutBuffer, 0);
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot heldHealth =
                        processor.pollHealthSnapshot();
                assertNotNull(heldHealth);
                assertTrue(heldHealth.isCurrentDepthValid());
                assertFalse(heldHealth.didHistoryAdvance());
                assertTrue(heldHealth.isCurrentGeometryReady());
                assertTrue((heldHealth.getCutDecisionFlags()
                        & ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_CANDIDATE) != 0);
                assertTrue("valid held depth must still update the live normalization range",
                        heldHealth.getEffectiveRangeLow() != baselineRangeLow
                                || heldHealth.getEffectiveRangeHigh() != baselineRangeHigh);

                float currentReliable = probe.readRed(
                        processor.getReliableDepthTextureId(), 0, 0);
                float currentTemporal = probe.readRed(
                        processor.getTemporalDepthTextureId(), 0, 0);
                assertEquals("held result must not alter reliable cut history",
                        baselineReliable, currentReliable, 0.0f);
                assertNotEquals("held valid depth must still advance immediate temporal depth",
                        baselineTemporal, currentTemporal, 0.0f);
                assertEquals("held result must publish the current source-aligned raw field",
                        reversedRawValue(0, 0, heldOffset),
                        probe.readRed(heldResult.getDepthTextureId(), 0, 0), 2.0e-5f);
                float[] heldProfile = probe.readRgba(
                        heldResult.getProfileTextureId(), 0, 0);
                assertEquals("held result must keep the previous shot camera",
                        baselineProfile[0], heldProfile[0], 2.0e-5f);
                assertEquals("profile must publish the current raw mean",
                        expectedMean(heldOffset), heldProfile[1], 2.0e-5f);
                assertTrue("valid held geometry must remain profile-ready",
                        heldProfile[3] > 0.5f);

                // Neither a partial/non-finite transaction nor a finite collapsed field may leak
                // stale/current raw geometry through the renderer ABI.
                updateRawBuffer(rawBuffer, 0.0f, true);
                ClientSbsGpuDepthProcessor.Result invalidResult =
                        processor.processRendererOwnedWithGpuSceneCut(
                                rawBuffer, 0, Float.BYTES, sceneCutBuffer, 0);
                GLES20.glFinish();
                assertEquals(0.0f,
                        probe.readRed(invalidResult.getDepthTextureId(), WIDTH / 2, HEIGHT / 2),
                        0.0f);
                assertEquals(0.0f,
                        probe.readRgba(invalidResult.getProfileTextureId(), 0, 0)[3], 0.0f);

                updateRawBufferConstant(rawBuffer, 2.0f);
                ClientSbsGpuDepthProcessor.Result collapsedResult =
                        processor.processRendererOwnedWithGpuSceneCut(
                                rawBuffer, 0, Float.BYTES, sceneCutBuffer, 0);
                GLES20.glFinish();
                assertEquals(0.0f,
                        probe.readRed(collapsedResult.getDepthTextureId(), WIDTH / 2, HEIGHT / 2),
                        0.0f);
                assertEquals(0.0f,
                        probe.readRgba(collapsedResult.getProfileTextureId(), 0, 0)[3], 0.0f);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

                Log.i(TAG, String.format(Locale.US,
                        "held raw=%.6f history=%.6f ready=%s",
                        heldProfile[1], currentReliable, heldHealth.isCurrentGeometryReady()));
            } finally {
                GLES30.glDeleteBuffers(2, new int[] {rawBuffer, sceneCutBuffer}, 0);
            }
        }
    }

    @Test
    public void rawMeanLatchesPerShotAndInvalidFieldPublishesNotReady() {
        try (EglFixture ignored = EglFixture.create()) {
            int rawBuffer = createRawBuffer(0.0f, false);
            try (ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                    WIDTH, HEIGHT, 1.0f, false, 60.0f)) {
                processor.setHealthSamplingFocused(true);

                float firstMean = expectedMean(0.0f);
                ClientSbsGpuDepthProcessor.Result first =
                        processor.processRendererOwned(rawBuffer, 0, Float.BYTES, false);
                assertNotEquals(0, first.getDepthTextureId());
                assertNotEquals(0, first.getProfileTextureId());
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot firstHealth =
                        processor.pollHealthSnapshot();
                assertNotNull(firstHealth);
                assertTrue(firstHealth.isCurrentGeometryReady());
                assertEquals(firstMean, firstHealth.getShotRawMean(), 2.0e-5f);
                assertEquals(firstMean, firstHealth.getCurrentRawMean(), 2.0e-5f);

                // Stay below cut thresholds. Frame five triggers the focused health cadence.
                float ordinaryOffset = 0.01f;
                updateRawBuffer(rawBuffer, ordinaryOffset, false);
                for (int frame = 2; frame <= 5; frame++) {
                    processor.processRendererOwned(rawBuffer, 0, Float.BYTES, false);
                }
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot movedHealth =
                        processor.pollHealthSnapshot();
                assertNotNull(movedHealth);
                assertTrue(movedHealth.isCurrentGeometryReady());
                assertEquals("ordinary motion must not move the shot camera",
                        firstMean, movedHealth.getShotRawMean(), 2.0e-5f);
                assertEquals(expectedMean(ordinaryOffset), movedHealth.getCurrentRawMean(),
                        2.0e-5f);

                // Reset requests an immediate health sample. One invalid texel must reject the
                // complete current transaction instead of publishing partial geometry.
                processor.resetTemporalState();
                updateRawBuffer(rawBuffer, 0.0f, true);
                processor.processRendererOwned(rawBuffer, 0, Float.BYTES, false);
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot invalidHealth =
                        processor.pollHealthSnapshot();
                assertNotNull(invalidHealth);
                assertFalse(invalidHealth.isCurrentDepthValid());
                assertFalse(invalidHealth.didHistoryAdvance());
                assertFalse(invalidHealth.isCurrentGeometryReady());
                assertEquals(WIDTH * HEIGHT - 1, invalidHealth.getValidRawSamples());
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

                Log.i(TAG, String.format(Locale.US,
                        "raw V2 mean first=%.6f current=%.6f invalidReady=%s",
                        firstHealth.getShotRawMean(), movedHealth.getCurrentRawMean(),
                        invalidHealth.isCurrentGeometryReady()));
            } finally {
                GLES30.glDeleteBuffers(1, new int[] {rawBuffer}, 0);
            }
        }
    }

    @Test
    public void collapsedStartupStaysFlatAndNextUsableFieldAcquiresCamera() {
        try (EglFixture ignored = EglFixture.create()) {
            int rawBuffer = createRawBuffer(0.0f, false);
            try (ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                    WIDTH, HEIGHT, 1.0f, false, 60.0f)) {
                processor.setHealthSamplingFocused(true);

                updateRawBufferConstant(rawBuffer, 2.0f);
                processor.processRendererOwned(rawBuffer, 0, Float.BYTES, false);
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot collapsed =
                        processor.pollHealthSnapshot();
                assertNotNull(collapsed);
                assertFalse(collapsed.isCurrentDepthValid());
                assertTrue("collapsed raw may still advance Apollo's private cut history",
                        collapsed.didHistoryAdvance());
                assertFalse(collapsed.isCurrentGeometryReady());
                assertEquals(1L, collapsed.getCollapsedRawFrameCount());
                assertEquals(2.0f, collapsed.getCurrentRawMean(), 1.0e-6f);

                updateRawBuffer(rawBuffer, 0.0f, false);
                for (int frame = 0; frame < 5; frame++) {
                    processor.processRendererOwned(rawBuffer, 0, Float.BYTES, false);
                }
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot recovered =
                        processor.pollHealthSnapshot();
                assertNotNull(recovered);
                assertTrue(recovered.isCurrentGeometryReady());
                assertEquals(expectedMean(0.0f), recovered.getShotRawMean(), 2.0e-5f);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            } finally {
                GLES30.glDeleteBuffers(1, new int[] {rawBuffer}, 0);
            }
        }
    }

    @Test
    public void geometryCutNeedsOrdinalStructureAndEventSurvivesSparseHealthCopy() {
        try (EglFixture ignored = EglFixture.create()) {
            int rawBuffer = createRawBuffer(0.0f, false);
            int sceneCutBuffer = createSceneCutBuffer(0);
            try (ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                    WIDTH, HEIGHT, 1.0f, false, 60.0f)) {
                processor.setHealthSamplingFocused(true);

                // Initialize and advance through the eight-source-step arming guard.
                for (int frame = 1; frame <= 9; frame++) {
                    processor.processRendererOwnedWithGpuSceneCut(rawBuffer, 0, Float.BYTES,
                            sceneCutBuffer, 0);
                }
                GLES20.glFinish();
                assertNotNull(processor.pollHealthSnapshot());

                // Reverse the depth field so nearly every normalized sample changes. With no
                // independent ordinal reversal, Apollo's structural gate must reject the trigger.
                updateRawBufferReversed(rawBuffer);
                updateSceneCutBuffer(sceneCutBuffer, 0);
                processor.processRendererOwnedWithGpuSceneCut(rawBuffer, 0, Float.BYTES,
                        sceneCutBuffer, 0);
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot rejected =
                        processor.pollHealthSnapshot();
                assertNotNull(rejected);
                assertTrue((rejected.getCutDecisionFlags()
                        & ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_DEPTH_TRIGGER) != 0);
                assertFalse((rejected.getCutDecisionFlags()
                        & ClientSbsGpuDepthProcessor
                        .CUT_DECISION_GEOMETRY_STRUCTURE_CORROBORATED) != 0);
                assertFalse((rejected.getCutDecisionFlags()
                        & ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_CANDIDATE) != 0);
                assertEquals(0L, rejected.getAcceptedGeometryCutCount());
                assertEquals(1L, rejected.getCutEventSequence());
                long rejectedEventSequence = rejected.getCutEventSequence();
                int rejectedDecisionFlags = rejected.getCutDecisionFlags();

                // Four non-notable transactions plus the fifth focused-cadence sample must not
                // erase the intervening rejection evidence. Make the current depth invalid so the
                // temporal depth texture cannot spend these rapid synthetic calls converging on
                // the reversed field and legitimately produce a new depth-trigger rejection on
                // every call.
                updateRawBuffer(rawBuffer, 0.0f, true);
                for (int frame = 0; frame < 5; frame++) {
                    processor.processRendererOwnedWithGpuSceneCut(rawBuffer, 0, Float.BYTES,
                            sceneCutBuffer, 0);
                }
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot retained =
                        processor.pollHealthSnapshot();
                assertNotNull(retained);
                assertEquals(rejectedEventSequence, retained.getCutEventSequence());
                assertEquals(rejectedDecisionFlags, retained.getCutDecisionFlags());

                // Apollo explicitly waives ordinal-change corroboration when the retained color
                // reference itself is structureless: it has no reliable ordinal pairs that a new
                // scene can reverse. Current structure remains reliable, so the first update starts
                // confirmation and the second accepts. Later ordinary frames retain that event.
                // Use a field well outside the retained range. The rejected reversal advanced its
                // history through temporal filtering, so switching back to the original field can
                // be nearly identical to that texture during a fast test loop and never reach the
                // depth trigger. The large shift remains above the absolute bar on both updates
                // because confirmation holds the reliable comparison reference even while range
                // and immediate temporal depth continue to advance.
                updateRawBuffer(rawBuffer, 10.0f, false);
                updateSceneCutBuffer(sceneCutBuffer, 0, false);
                processor.processRendererOwnedWithGpuSceneCut(rawBuffer, 0, Float.BYTES,
                        sceneCutBuffer, 0);
                processor.processRendererOwnedWithGpuSceneCut(rawBuffer, 0, Float.BYTES,
                        sceneCutBuffer, 0);
                updateSceneCutBuffer(sceneCutBuffer, 0);
                for (int frame = 0; frame < 3; frame++) {
                    processor.processRendererOwnedWithGpuSceneCut(rawBuffer, 0, Float.BYTES,
                            sceneCutBuffer, 0);
                }
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot accepted =
                        processor.pollHealthSnapshot();
                assertNotNull(accepted);
                assertEquals(3L, accepted.getCutEventSequence());
                assertEquals(1L, accepted.getAcceptedGeometryCutCount());
                assertTrue((accepted.getCutDecisionFlags()
                        & ClientSbsGpuDepthProcessor.CUT_DECISION_ACCEPTED_GEOMETRY) != 0);
                assertTrue((accepted.getCutDecisionFlags()
                        & ClientSbsGpuDepthProcessor
                        .CUT_DECISION_GEOMETRY_STRUCTURE_CORROBORATED) != 0);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

                Log.i(TAG, String.format(Locale.US,
                        "structural gate rejectedEvent=%d acceptedEvent=%d geometryCuts=%d",
                        rejectedEventSequence, accepted.getCutEventSequence(),
                        accepted.getAcceptedGeometryCutCount()));
            } finally {
                GLES30.glDeleteBuffers(2, new int[] {rawBuffer, sceneCutBuffer}, 0);
            }
        }
    }

    private static float expectedMean(float offset) {
        return 1.0f + offset + 0.001f * (WIDTH - 1) * 0.5f
                + 0.002f * (HEIGHT - 1) * 0.5f;
    }

    private static int createRawBuffer(float offset, boolean invalidFirst) {
        int[] buffer = new int[1];
        GLES30.glGenBuffers(1, buffer, 0);
        assertNotEquals(0, buffer[0]);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0]);
        FloatBuffer values = rawValues(offset, invalidFirst);
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,
                WIDTH * HEIGHT * Float.BYTES, values, GLES30.GL_DYNAMIC_DRAW);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return buffer[0];
    }

    private static void updateRawBuffer(int buffer, float offset, boolean invalidFirst) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                WIDTH * HEIGHT * Float.BYTES, rawValues(offset, invalidFirst));
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static void updateRawBufferReversed(int buffer) {
        updateRawBufferReversed(buffer, 0.0f);
    }

    private static void updateRawBufferReversed(int buffer, float offset) {
        FloatBuffer values = ByteBuffer.allocateDirect(WIDTH * HEIGHT * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                values.put(reversedRawValue(x, y, offset));
            }
        }
        values.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                WIDTH * HEIGHT * Float.BYTES, values);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static float reversedRawValue(int x, int y, float offset) {
        return 1.0f + offset + 0.001f * (WIDTH - 1 - x)
                + 0.002f * (HEIGHT - 1 - y);
    }

    private static void updateRawBufferConstant(int buffer, float value) {
        FloatBuffer values = ByteBuffer.allocateDirect(WIDTH * HEIGHT * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int index = 0; index < WIDTH * HEIGHT; index++) {
            values.put(value);
        }
        values.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                WIDTH * HEIGHT * Float.BYTES, values);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static int createSceneCutBuffer(int structuralChangeCount) {
        int[] buffer = new int[1];
        GLES30.glGenBuffers(1, buffer, 0);
        assertNotEquals(0, buffer[0]);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0]);
        ByteBuffer record = sceneCutRecord(structuralChangeCount);
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,
                ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES,
                record, GLES30.GL_DYNAMIC_DRAW);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return buffer[0];
    }

    private static void updateSceneCutBuffer(int buffer, int structuralChangeCount) {
        updateSceneCutBuffer(buffer, structuralChangeCount, true);
    }

    private static void updateSceneCutBuffer(int buffer, int structuralChangeCount,
                                             boolean previousStructureSupported) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES,
                sceneCutRecord(structuralChangeCount, previousStructureSupported));
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static ByteBuffer sceneCutRecord(int structuralChangeCount) {
        return sceneCutRecord(structuralChangeCount, true);
    }

    private static ByteBuffer sceneCutRecord(int structuralChangeCount,
                                             boolean previousStructureSupported) {
        // A 32x32 model grid has a 2x2 color-cut block grid. Keep both current and common
        // structure reliable while controlling only the independent ordering-reversal count.
        ByteBuffer record = ByteBuffer.allocateDirect(
                ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES)
                .order(ByteOrder.nativeOrder());
        record.putInt(0); // typed scene evidence
        record.putInt(4); // block count
        record.putInt(0); // raw-moderate count
        record.putInt(0); // raw-delta sum
        record.putInt(structuralChangeCount);
        record.putInt(4); // current structural support
        record.putInt(4); // common structural support
        int diagnosticFlags = ClientSbsGpuSceneCutDetector.DIAGNOSTIC_COMPARABLE
                | ClientSbsGpuSceneCutDetector.DIAGNOSTIC_CURRENT_STRUCTURE_SUPPORTED
                | ClientSbsGpuSceneCutDetector.DIAGNOSTIC_COMMON_STRUCTURE_SUPPORTED;
        if (previousStructureSupported) {
            diagnosticFlags |= ClientSbsGpuSceneCutDetector
                    .DIAGNOSTIC_PREVIOUS_STRUCTURE_SUPPORTED;
        }
        record.putInt(diagnosticFlags);
        record.flip();
        return record;
    }

    private static FloatBuffer rawValues(float offset, boolean invalidFirst) {
        FloatBuffer values = ByteBuffer.allocateDirect(WIDTH * HEIGHT * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                values.put(invalidFirst && x == 0 && y == 0
                        ? Float.NaN : 1.0f + offset + 0.001f * x + 0.002f * y);
            }
        }
        values.flip();
        return values;
    }

    /** Test-only compute probe so R32F/RGBA32F verification does not depend on FBO extensions. */
    private static final class TextureProbe implements AutoCloseable {
        private static final int RESULT_BYTES = 4 * Float.BYTES;
        private final int program;
        private final int textureUniform;
        private final int pointUniform;
        private final int resultBuffer;

        TextureProbe() {
            String source = "#version 310 es\n"
                    + "precision highp float;\n"
                    + "layout(local_size_x = 1) in;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "uniform ivec2 uPoint;\n"
                    + "layout(std430, binding = 0) buffer Result { vec4 value; };\n"
                    + "void main() { value = texelFetch(uTexture, uPoint, 0); }\n";
            int shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            assertEquals("texture-probe compile failed: " + GLES20.glGetShaderInfoLog(shader),
                    1, status[0]);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(shader);
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            assertEquals("texture-probe link failed: " + GLES20.glGetProgramInfoLog(program),
                    1, status[0]);
            textureUniform = GLES20.glGetUniformLocation(program, "uTexture");
            pointUniform = GLES20.glGetUniformLocation(program, "uPoint");
            assertTrue(textureUniform >= 0);
            assertTrue(pointUniform >= 0);

            int[] buffers = new int[1];
            GLES30.glGenBuffers(1, buffers, 0);
            resultBuffer = buffers[0];
            assertNotEquals(0, resultBuffer);
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, resultBuffer);
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,
                    RESULT_BYTES, null, GLES30.GL_STREAM_READ);
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        }

        float readRed(int texture, int x, int y) {
            return readRgba(texture, x, y)[0];
        }

        float[] readRgba(int texture, int x, int y) {
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(textureUniform, 0);
            GLES20.glUniform2i(pointUniform, x, y);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, resultBuffer);
            GLES31.glDispatchCompute(1, 1, 1);
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
            GLES20.glFinish();

            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, resultBuffer);
            Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,
                    0, RESULT_BYTES, GLES30.GL_MAP_READ_BIT);
            assertTrue(mapped instanceof ByteBuffer);
            FloatBuffer values = ((ByteBuffer) mapped)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            float[] result = new float[4];
            values.get(result);
            assertTrue(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER));
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            return result;
        }

        @Override
        public void close() {
            GLES20.glDeleteProgram(program);
            GLES30.glDeleteBuffers(1, new int[] {resultBuffer}, 0);
        }
    }

    private static final class EglFixture implements AutoCloseable {
        private final EGLDisplay display;
        private final EGLSurface surface;
        private final EGLContext context;

        private EglFixture(EGLDisplay display, EGLSurface surface, EGLContext context) {
            this.display = display;
            this.surface = surface;
            this.context = context;
        }

        static EglFixture create() {
            EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            assertTrue(display != EGL14.EGL_NO_DISPLAY);
            assertTrue(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0));
            assertTrue(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API));
            int[] configAttributes = {
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            assertTrue(EGL14.eglChooseConfig(display, configAttributes, 0,
                    configs, 0, 1, count, 0));
            assertEquals(1, count[0]);
            EGLContext context = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, new int[] {
                            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                            EGL14.EGL_NONE,
                    }, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[] {
                            EGL14.EGL_WIDTH, 1,
                            EGL14.EGL_HEIGHT, 1,
                            EGL14.EGL_NONE,
                    }, 0);
            assertTrue(surface != EGL14.EGL_NO_SURFACE);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
            String version = GLES20.glGetString(GLES20.GL_VERSION);
            assertTrue("GLES 3.1+ required, got " + version,
                    version != null && (version.contains("OpenGL ES 3.1")
                            || version.contains("OpenGL ES 3.2")));
            return new EglFixture(display, surface, context);
        }

        @Override
        public void close() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
}
