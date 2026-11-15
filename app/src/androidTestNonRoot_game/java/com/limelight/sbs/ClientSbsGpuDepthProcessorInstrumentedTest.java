package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
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

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

/** Physical-device gate for raw ZipDepth and the source-time adaptive V2 camera handoff. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsGpuDepthProcessorInstrumentedTest {
    private static final String TAG = "ClientSbsRawV2";
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final int WIDTH = 32;
    private static final int HEIGHT = 32;
    private static final long REFERENCE_SOURCE_STEP_NS = 16_666_000L;

    @Test
    public void fixedUniformsAreReadyBeforeDispatchAndSurviveOtherProcessorsAndReset()
            throws Exception {
        try (EglFixture ignored = EglFixture.create()) {
            int rawBuffer = createRawBuffer(0.0f, false);
            try (ClientSbsGpuDepthProcessor first = new ClientSbsGpuDepthProcessor(
                    WIDTH, HEIGHT, 1.0f, false, 0.05f);
                 ClientSbsGpuDisparityProcessor disparity = new ClientSbsGpuDisparityProcessor(
                         WIDTH, HEIGHT, 0.05f);
                 TextureProbe probe = new TextureProbe()) {
                assertFixedDepthUniforms(first, WIDTH, HEIGHT, 0.05f);
                assertFixedDisparityUniforms(disparity);
                first.setHealthSamplingEnabled(false);
                int firstRaw = first.processRendererOwned(
                        rawBuffer, 0, Float.BYTES, REFERENCE_SOURCE_STEP_NS).getDepthTextureId();
                float before = probe.readRed(firstRaw, 7, 11);

                // Uniform state is per program, so a second shape/calibration must not overwrite
                // the first processor, even with interleaved rendering and readback programs.
                try (ClientSbsGpuDepthProcessor other = new ClientSbsGpuDepthProcessor(
                        16, 8, 2.0f, false, 0.10f);
                     ClientSbsGpuDisparityProcessor otherDisparity =
                             new ClientSbsGpuDisparityProcessor(16, 8, 0.10f)) {
                    assertFixedDepthUniforms(other, 16, 8, 0.10f);
                    assertFixedDisparityUniforms(otherDisparity);
                    assertFixedDepthUniforms(first, WIDTH, HEIGHT, 0.05f);
                    assertFixedDisparityUniforms(disparity);
                }
                first.resetTemporalState();
                assertFixedDepthUniforms(first, WIDTH, HEIGHT, 0.05f);
                ClientSbsGpuDepthProcessor.Result after = first.processRendererOwned(
                        rawBuffer, 0, Float.BYTES, REFERENCE_SOURCE_STEP_NS);
                assertEquals(before, probe.readRed(after.getDepthTextureId(), 7, 11), 0.0f);
                disparity.process(after.getDepthTextureId(), after.getProfileTextureId());
                assertFixedDisparityUniforms(disparity);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            } finally {
                GLES30.glDeleteBuffers(1, new int[] {rawBuffer}, 0);
            }
        }
    }

    private static void assertFixedDepthUniforms(ClientSbsGpuDepthProcessor processor,
                                                  int width, int height, float rawScale)
            throws Exception {
        for (String program : new String[] {
                "rawMinMaxProgram", "rawHistogramProgram", "writeRawDepthProgram"}) {
            assertUniform(processor, program, "uTensorSize", width, height);
            assertUniform(processor, program, "uOutputSize", width, height);
            assertUnsignedUniform(processor, program, "uRawPixelStrideBytes", Float.BYTES);
        }
        assertUniform(processor, "resolveRawProgram", "uExpectedPixelCount", width * height);
        assertUniform(processor, "resolveRawProgram", "uRawGroupCount",
                ((width + 15) / 16) * ((height + 15) / 16));
        assertUniform(processor, "publishProfileProgram", "uRawCoordinateScale", rawScale);
    }

    private static void assertFixedDisparityUniforms(ClientSbsGpuDisparityProcessor processor)
            throws Exception {
        assertUniform(processor, "verticalForwardProgram", "uDepthTexture", 0);
        assertUniform(processor, "verticalForwardProgram", "uProfileTexture", 1);
        assertEquals("Inverse amplitude must come from the current profile", -1,
                GLES20.glGetUniformLocation(processorProgram(processor, "verticalForwardProgram"),
                        "uInverseRawCoordinateScale"));
        assertUniform(processor, "verticalFinishProgram", "uEnvelopeScratch", 0);
        assertUniform(processor, "horizontalForwardProgram", "uVerticalConditioned", 0);
        assertUniform(processor, "horizontalFinishProgram", "uVerticalConditioned", 0);
    }

    private static int processorProgram(Object processor, String programField) throws Exception {
        Field field = processor.getClass().getDeclaredField(programField);
        field.setAccessible(true);
        return field.getInt(processor);
    }

    private static void assertUniform(Object processor, String programField,
                                      String uniform, int... expected) throws Exception {
        assertIntegerUniform(processor, programField, uniform, false, expected);
    }

    private static void assertUnsignedUniform(Object processor, String programField,
                                              String uniform, int... expected) throws Exception {
        assertIntegerUniform(processor, programField, uniform, true, expected);
    }

    private static void assertIntegerUniform(Object processor, String programField,
                                             String uniform, boolean unsigned,
                                             int[] expected) throws Exception {
        int program = processorProgram(processor, programField);
        int location = GLES20.glGetUniformLocation(program, uniform);
        assertTrue(programField + ": " + uniform, location >= 0);
        int[] actual = new int[4];
        // Sampler units are integer state. Query integer/unsigned uniforms in their declared
        // representation instead of relying on the driver's float-query conversion path.
        if (unsigned) {
            GLES30.glGetUniformuiv(program, location, actual, 0);
        } else {
            GLES20.glGetUniformiv(program, location, actual, 0);
        }
        assertEquals(programField + ": query " + uniform,
                GLES20.GL_NO_ERROR, GLES20.glGetError());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(programField + ": " + uniform + "[" + index + "]",
                    expected[index], actual[index]);
        }
    }

    private static void assertUniform(Object processor, String programField,
                                      String uniform, float... expected) throws Exception {
        int program = processorProgram(processor, programField);
        int location = GLES20.glGetUniformLocation(program, uniform);
        assertTrue(programField + ": " + uniform, location >= 0);
        float[] actual = new float[4];
        GLES20.glGetUniformfv(program, location, actual, 0);
        assertEquals(programField + ": query " + uniform,
                GLES20.GL_NO_ERROR, GLES20.glGetError());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(programField + ": " + uniform + "[" + index + "]",
                    expected[index], actual[index], 0.0f);
        }
    }

    @Test
    public void failedUnmapRejectsCopiedHealthAndSuccessfulReadbackCanRecover() {
        ByteBuffer state = ByteBuffer.allocate(ClientSbsGpuDepthProcessor.STATE_BYTES)
                .order(ByteOrder.nativeOrder());
        state.putFloat(ClientSbsGpuDepthProcessor.V2_CAMERA_VALID_COUNT_BYTE_OFFSET,
                WIDTH * HEIGHT);
        ClientSbsGpuDepthProcessor.HealthSnapshot health =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();
        health.updateFromState(state, 77L, WIDTH * HEIGHT);
        assertEquals(WIDTH * HEIGHT, health.getValidRawSamples());

        assertThrows(IllegalStateException.class,
                () -> ClientSbsGpuDepthProcessor.requireValidHealthReadback(health, false));
        assertEquals(0L, health.getFrameSequence());
        assertEquals(0, health.getValidRawSamples());

        health.updateFromState(state, 78L, WIDTH * HEIGHT);
        ClientSbsGpuDepthProcessor.requireValidHealthReadback(health, true);
        assertEquals(78L, health.getFrameSequence());
        assertEquals(WIDTH * HEIGHT, health.getValidRawSamples());
    }

    @Test
    public void failedDiagnosticCopyPreservesDepthAndRecoversWithFreshHealth() throws Exception {
        try (EglFixture ignored = EglFixture.create()) {
            int rawBuffer = createRawBuffer(0.0f, false);
            try (ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                    WIDTH, HEIGHT, 1.0f, false, 1.0f)) {
                Field slotsField = ClientSbsGpuDepthProcessor.class
                        .getDeclaredField("healthReadbackSlots");
                slotsField.setAccessible(true);
                Object firstSlot = ((Object[]) slotsField.get(processor))[0];
                Field bufferField = firstSlot.getClass().getDeclaredField("buffer");
                bufferField.setAccessible(true);
                int stagingBuffer = bufferField.getInt(firstSlot);

                GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, stagingBuffer);
                int[] size = new int[1];
                GLES30.glGetBufferParameteriv(GLES30.GL_COPY_WRITE_BUFFER,
                        GLES20.GL_BUFFER_SIZE, size, 0);
                assertEquals(64, ClientSbsGpuDepthProcessor.STATE_BYTES);
                assertEquals(ClientSbsGpuDepthProcessor.STATE_BYTES, size[0]);

                // Only the optional staging copy is invalid. Production state/textures stay
                // intact, so this produces GL_INVALID_VALUE without a device or context fault.
                GLES30.glBufferData(GLES30.GL_COPY_WRITE_BUFFER, 1, null, GLES30.GL_STREAM_READ);
                GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, 0);

                ClientSbsGpuDepthProcessor.Result first =
                        processReferenceStep(processor, rawBuffer);
                assertTrue(first.isValidFrame());
                assertTrue(first.getDepthTextureId() != 0);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                // While the error awaits polling, subsequent depth work must also continue.
                assertTrue(processReferenceStep(processor, rawBuffer).isValidFrame());
                assertThrows(IllegalStateException.class, processor::pollHealthSnapshot);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

                GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, stagingBuffer);
                GLES30.glBufferData(GLES30.GL_COPY_WRITE_BUFFER,
                        ClientSbsGpuDepthProcessor.STATE_BYTES,
                        null, GLES30.GL_STREAM_READ);
                GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, 0);
                ClientSbsGpuDepthProcessor.Result recovered =
                        processReferenceStep(processor, rawBuffer);
                assertTrue(recovered.isValidFrame());
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot health = processor.pollHealthSnapshot();
                assertNotNull(health);
                assertEquals(recovered.getFrameSequence(), health.getFrameSequence());
                assertEquals(WIDTH * HEIGHT, health.getValidRawSamples());
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            } finally {
                GLES30.glDeleteBuffers(1, new int[] {rawBuffer}, 0);
            }
        }
    }

    @Test
    public void abruptGeometryChangePublishesCurrentRawThroughTheContinuousCamera()
            throws Exception {
        try (EglFixture ignored = EglFixture.create()) {
            int rawBuffer = createRawBuffer(0.0f, false);
            try (ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                    WIDTH, HEIGHT, 1.0f, false, 1.0f);
                 TextureProbe probe = new TextureProbe()) {

                ClientSbsGpuDepthProcessor.Result baselineResult = null;
                for (int frame = 1; frame <= 9; frame++) {
                    baselineResult = processReferenceStep(processor, rawBuffer);
                }
                ClientSbsGpuDepthProcessor.HealthSnapshot baselineHealth =
                        readCompletedHealth(processor, 5L);
                assertTrue(baselineHealth.isCurrentGeometryReady());
                assertNotNull(baselineResult);
                float[] baselineProfile = probe.readRgba(
                        baselineResult.getProfileTextureId(), 0, 0);
                assertTrue(baselineProfile[3] > 0.5f);
                assertEquals(expectedMean(0.0f), baselineProfile[0], 2.0e-5f);

                // Reverse the field and shift it far outside its previous range, as an editorial
                // cut would. There is no cut detector: the current valid field is rendered at
                // once through the continuously adapting camera, which moves by at most its
                // source-time rate bound.
                float changedOffset = 10.0f;
                updateRawBufferReversed(rawBuffer, changedOffset);
                ClientSbsGpuDepthProcessor.Result changedResult =
                        processReferenceStep(processor, rawBuffer);
                ClientSbsGpuDepthProcessor.HealthSnapshot changedHealth =
                        readCompletedHealth(processor, changedResult.getFrameSequence());
                assertTrue(describeHealth(changedHealth), changedHealth.isCurrentDepthValid());
                assertTrue(changedHealth.isCurrentGeometryReady());
                assertTrue(changedHealth.isCameraClockArmed());
                assertEquals(0L, changedHealth.getIncompleteRawFrameCount());
                assertEquals(0L, changedHealth.getCollapsedRawFrameCount());
                assertEquals("the current source-aligned raw field must be published",
                        reversedRawValue(0, 0, changedOffset),
                        probe.readRed(changedResult.getDepthTextureId(), 0, 0), 2.0e-5f);
                float[] changedProfile = probe.readRgba(
                        changedResult.getProfileTextureId(), 0, 0);
                assertTrue("adaptive zero must follow the new field",
                        changedProfile[0] > baselineProfile[0]);
                float zeroRateBound = REFERENCE_SOURCE_STEP_NS * 1.0e-9f / changedProfile[2];
                assertTrue("adaptive zero must obey its source-time rate bound",
                        changedProfile[0] - baselineProfile[0] <= zeroRateBound + 2.0e-5f);
                assertEquals("profile must publish the current raw mean",
                        expectedMean(changedOffset), changedProfile[1], 2.0e-5f);
                assertEquals("identical relief keeps the inverse amplitude",
                        baselineProfile[2], changedProfile[2], 1.0e-4f * baselineProfile[2]);
                assertTrue("a valid field must remain profile-ready", changedProfile[3] > 0.5f);

                // Neither a partial/non-finite transaction nor a finite collapsed field may leak
                // stale/current raw geometry through the renderer ABI, and both hold the camera.
                updateRawBuffer(rawBuffer, 0.0f, true);
                ClientSbsGpuDepthProcessor.Result invalidResult =
                        processReferenceStep(processor, rawBuffer);
                GLES20.glFinish();
                assertEquals(0.0f,
                        probe.readRed(invalidResult.getDepthTextureId(), WIDTH / 2, HEIGHT / 2),
                        0.0f);
                float[] invalidProfile = probe.readRgba(
                        invalidResult.getProfileTextureId(), 0, 0);
                assertEquals(0.0f, invalidProfile[3], 0.0f);
                assertEquals("invalid depth must hold the established zero",
                        changedProfile[0], invalidProfile[0], 0.0f);
                assertEquals("invalid depth must hold the established amplitude",
                        changedProfile[2], invalidProfile[2], 0.0f);

                updateRawBufferConstant(rawBuffer, 2.0f);
                ClientSbsGpuDepthProcessor.Result collapsedResult =
                        processReferenceStep(processor, rawBuffer);
                GLES20.glFinish();
                assertEquals(0.0f,
                        probe.readRed(collapsedResult.getDepthTextureId(), WIDTH / 2, HEIGHT / 2),
                        0.0f);
                float[] collapsedProfile = probe.readRgba(
                        collapsedResult.getProfileTextureId(), 0, 0);
                assertEquals(0.0f, collapsedProfile[3], 0.0f);
                assertEquals(changedProfile[0], collapsedProfile[0], 0.0f);
                assertEquals(changedProfile[2], collapsedProfile[2], 0.0f);

                // The next usable field only rearms the clock: it is ready again, and the camera
                // has not spent the interrupted interval.
                updateRawBufferReversed(rawBuffer, changedOffset);
                ClientSbsGpuDepthProcessor.Result rearmedResult =
                        processReferenceStep(processor, rawBuffer);
                GLES20.glFinish();
                float[] rearmedProfile = probe.readRgba(
                        rearmedResult.getProfileTextureId(), 0, 0);
                assertTrue(rearmedProfile[3] > 0.5f);
                assertEquals(changedProfile[0], rearmedProfile[0], 0.0f);
                assertEquals(changedProfile[2], rearmedProfile[2], 0.0f);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

                Log.i(TAG, String.format(Locale.US,
                        "abrupt change raw=%.6f zero=%.6f ready=%s",
                        changedProfile[1], changedProfile[0],
                        changedHealth.isCurrentGeometryReady()));
            } finally {
                GLES30.glDeleteBuffers(1, new int[] {rawBuffer}, 0);
            }
        }
    }

    @Test
    public void rawMeanAdaptsOnSourceTimeAndInvalidFieldPublishesNotReady() {
        try (EglFixture ignored = EglFixture.create()) {
            int rawBuffer = createRawBuffer(0.0f, false);
            try (ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                    WIDTH, HEIGHT, 1.0f, false, 1.0f)) {

                float firstMean = expectedMean(0.0f);
                ClientSbsGpuDepthProcessor.Result first =
                         processor.processRendererOwned(rawBuffer, 0, Float.BYTES,
                                 100_000_000L);
                assertNotEquals(0, first.getDepthTextureId());
                assertNotEquals(0, first.getProfileTextureId());
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot firstHealth =
                        processor.pollHealthSnapshot();
                assertNotNull(firstHealth);
                assertTrue(firstHealth.isCurrentGeometryReady());
                assertEquals(firstMean, firstHealth.getRawZero(), 2.0e-5f);
                assertEquals(firstMean, firstHealth.getCurrentRawMean(), 2.0e-5f);
                float firstObservedZero = firstHealth.getRawZero();

                // A small ordinary change. Frame five triggers the focused health cadence.
                float ordinaryOffset = 0.01f;
                updateRawBuffer(rawBuffer, ordinaryOffset, false);
                for (int frame = 2; frame <= 5; frame++) {
                    processor.processRendererOwned(rawBuffer, 0, Float.BYTES,
                            frame * 100_000_000L);
                }
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot movedHealth =
                        processor.pollHealthSnapshot();
                assertNotNull(movedHealth);
                assertTrue(movedHealth.isCurrentGeometryReady());
                float expectedZero = firstMean + ordinaryOffset
                        * (1.0f - (float) Math.exp(-0.4f / 0.5f));
                assertEquals("ordinary observations must adapt zero on source time",
                        expectedZero, movedHealth.getRawZero(), 2.0e-5f);
                assertEquals(0L, movedHealth.getIncompleteRawFrameCount());
                assertEquals(0L, movedHealth.getCollapsedRawFrameCount());
                assertEquals(expectedMean(ordinaryOffset), movedHealth.getCurrentRawMean(),
                        2.0e-5f);
                // HealthSnapshot is a reused view. Freeze diagnostics before reset/readback
                // replaces it, just as the renderer copies sampled health into its own state.
                float movedObservedZero = movedHealth.getRawZero();
                float movedObservedMean = movedHealth.getCurrentRawMean();

                // Reset requests an immediate health sample. One invalid texel must reject the
                // complete current transaction instead of publishing partial geometry.
                processor.resetTemporalState();
                updateRawBuffer(rawBuffer, 0.0f, true);
                processor.processRendererOwned(rawBuffer, 0, Float.BYTES, 100_000_000L);
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot invalidHealth =
                        processor.pollHealthSnapshot();
                assertNotNull(invalidHealth);
                assertFalse(invalidHealth.isCurrentDepthValid());
                assertFalse(invalidHealth.hasCompleteRawField());
                assertFalse(invalidHealth.isCurrentGeometryReady());
                assertEquals(WIDTH * HEIGHT - 1, invalidHealth.getValidRawSamples());
                assertEquals(1L, invalidHealth.getIncompleteRawFrameCount());
                assertEquals(0L, invalidHealth.getCollapsedRawFrameCount());
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

                Log.i(TAG, String.format(Locale.US,
                        "raw V2 zero first=%.6f adapted=%.6f currentMean=%.6f invalidReady=%s",
                        firstObservedZero, movedObservedZero, movedObservedMean,
                        invalidHealth.isCurrentGeometryReady()));
            } finally {
                GLES30.glDeleteBuffers(1, new int[] {rawBuffer}, 0);
            }
        }
    }

    @Test
    public void collapsedStartupStaysFlatAndNextUsableFieldAcquiresCamera()
            throws Exception {
        try (EglFixture ignored = EglFixture.create()) {
            int rawBuffer = createRawBuffer(0.0f, false);
            try (ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                    WIDTH, HEIGHT, 1.0f, false, 1.0f)) {

                updateRawBufferConstant(rawBuffer, 2.0f);
                processReferenceStep(processor, rawBuffer);
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot collapsed =
                        processor.pollHealthSnapshot();
                assertNotNull(collapsed);
                assertFalse(collapsed.isCurrentDepthValid());
                assertTrue("a collapsed field is complete, only unusable for V2",
                        collapsed.hasCompleteRawField());
                assertFalse(collapsed.isCurrentGeometryReady());
                assertFalse(collapsed.isCameraInitialized());
                assertEquals(1L, collapsed.getCollapsedRawFrameCount());
                assertEquals(0L, collapsed.getIncompleteRawFrameCount());
                assertEquals(2.0f, collapsed.getCurrentRawMean(), 1.0e-6f);

                updateRawBuffer(rawBuffer, 0.0f, false);
                for (int frame = 0; frame < 5; frame++) {
                    processReferenceStep(processor, rawBuffer);
                }
                GLES20.glFinish();
                ClientSbsGpuDepthProcessor.HealthSnapshot recovered =
                        processor.pollHealthSnapshot();
                assertNotNull(recovered);
                assertTrue(recovered.isCurrentGeometryReady());
                assertEquals(1L, recovered.getCollapsedRawFrameCount());
                assertEquals(expectedMean(0.0f), recovered.getRawZero(), 2.0e-5f);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            } finally {
                GLES30.glDeleteBuffers(1, new int[] {rawBuffer}, 0);
            }
        }
    }

    /**
     * One real inference at the reference source step. Camera time is this deterministic matched
     * source clock, independent of driver/CPU speed.
     */
    private static ClientSbsGpuDepthProcessor.Result processReferenceStep(
            ClientSbsGpuDepthProcessor processor, int rawBuffer) throws Exception {
        Field frameSequence = ClientSbsGpuDepthProcessor.class.getDeclaredField("frameSequence");
        frameSequence.setAccessible(true);
        long sourceTimestampNs = (frameSequence.getLong(processor) + 1L)
                * REFERENCE_SOURCE_STEP_NS;
        return processor.processRendererOwned(
                rawBuffer, 0, Float.BYTES, sourceTimestampNs);
    }

    private static ClientSbsGpuDepthProcessor.HealthSnapshot readCompletedHealth(
            ClientSbsGpuDepthProcessor processor, long expectedFrameSequence) {
        // Blocking is confined to this GPU fixture. The production poll still uses zero timeout.
        GLES20.glFinish();
        ClientSbsGpuDepthProcessor.HealthSnapshot health = processor.pollHealthSnapshot();
        assertNotNull("No completed health copy for frame " + expectedFrameSequence, health);
        assertEquals(describeHealth(health), expectedFrameSequence, health.getFrameSequence());
        return health;
    }

    private static String describeHealth(ClientSbsGpuDepthProcessor.HealthSnapshot health) {
        return "frame=" + health.getFrameSequence()
                + " complete=" + health.hasCompleteRawField()
                + " valid=" + health.isCurrentDepthValid()
                + " clock=" + health.isCameraClockArmed()
                + " zero=" + health.getRawZero()
                + " mean=" + health.getCurrentRawMean();
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
