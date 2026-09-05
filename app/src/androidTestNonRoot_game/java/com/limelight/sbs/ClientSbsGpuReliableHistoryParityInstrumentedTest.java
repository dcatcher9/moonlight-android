package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Device gate for Apollo-equivalent reliable-history ownership across the complete GPU chain. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsGpuReliableHistoryParityInstrumentedTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int BLOCK_SIZE = 16;
    // Clamp every noninitial temporal update to the same 0.25 reference-frame response. This test
    // validates ownership, not device scheduling latency.
    private static final float TEST_TEMPORAL_REFERENCE_HZ = 0.001f;
    // Makes the separate fallback fixture effectively snap, so its broad depth edit is independent
    // of host/device command-submission timing.
    private static final float TEST_SNAP_TEMPORAL_REFERENCE_HZ = 1_000_000_000.0f;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final int SCENE_SLOT = 0;

    private static final int COLOR_BASELINE = 0;
    private static final int COLOR_EXPOSURE = 1;
    private static final int COLOR_STRUCTURELESS = 2;

    private static final int RAW_BASELINE = 0;
    private static final int RAW_REVERSED = 1;
    private static final int RAW_SHIFTED = 2;

    private static final int PROCESSOR_FRAME_STATE_BYTE_OFFSET = 72;
    private static final int PROCESSOR_HARD_CUT_BYTE_OFFSET = 76;
    private static final int PROCESSOR_CHANGE_FRACTION_BYTE_OFFSET = 48;
    private static final int PROCESSOR_CUT_DECISION_BYTE_OFFSET =
            ClientSbsGpuDepthProcessor.CUT_APPEARANCE_META_BYTE_OFFSET + 3 * Integer.BYTES;

    @Test
    public void exposureAndStructurelessSequenceOwnsReliableHistoryLikeHost() {
        try (EglFixture ignored = EglFixture.create();
             ClientSbsGpuSceneCutDetector detector =
                     new ClientSbsGpuSceneCutDetector(WIDTH, HEIGHT);
             ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                     WIDTH, HEIGHT, 1.0f, false, TEST_TEMPORAL_REFERENCE_HZ);
             TextureProbe probe = new TextureProbe()) {
            int colorTexture = createColorTexture(COLOR_BASELINE);
            int packedInputBuffer = createBuffer(WIDTH * HEIGHT * 3 * Float.BYTES);
            int rawDepthBuffer = createRawDepthBuffer(RAW_BASELINE);
            try {
                Observation baseline = processAndCommit(
                        detector, processor, probe, colorTexture, packedInputBuffer,
                        rawDepthBuffer, 1L);
                assertEquals(0, baseline.scene.evidence);
                assertHistoryAdvances(baseline.processor.frameState);

                updateColorTexture(colorTexture, COLOR_EXPOSURE);
                updateRawDepthBuffer(rawDepthBuffer, RAW_REVERSED);
                Observation exposure = processAndCommit(
                        detector, processor, probe, colorTexture, packedInputBuffer,
                        rawDepthBuffer, 2L);
                assertTrue("the monotone supported color change must be exposure-like",
                        has(exposure.scene.evidence,
                                ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE));
                assertFalse("ordinary exposure must not become an appearance cut",
                        has(exposure.scene.evidence,
                                ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE));
                assertTrue("exposure keeps reliable current structure",
                        exposure.scene.currentSupportCount > 0);
                assertTrue("exposure keeps common structure with its predecessor",
                        exposure.scene.commonSupportCount > 0);
                assertHistoryAdvances(exposure.processor.frameState);
                assertEquals("an advancing result must leave the prior committed inference reliable",
                        baseline.temporalDepth, exposure.reliableDepth, 0.0f);
                assertNotEquals("exposure promotion must be deferred to the next inference",
                        exposure.temporalDepth, exposure.reliableDepth, 1.0e-4f);

                updateColorTexture(colorTexture, COLOR_STRUCTURELESS);
                updateRawDepthBuffer(rawDepthBuffer, RAW_SHIFTED);
                Observation firstStructureless = processAndCommit(
                        detector, processor, probe, colorTexture, packedInputBuffer,
                        rawDepthBuffer, 3L);
                assertTrue("supported-to-structureless is the one exposure-like bridge",
                        has(firstStructureless.scene.evidence,
                                ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE));
                assertEquals(0, firstStructureless.scene.currentSupportCount);
                assertTrue("the bridge must identify its retained supported reference",
                        has(firstStructureless.scene.diagnosticFlags,
                                ClientSbsGpuSceneCutDetector
                                        .DIAGNOSTIC_PREVIOUS_STRUCTURE_SUPPORTED));
                assertHistoryHolds(firstStructureless.processor.frameState);
                assertEquals("frame 3 must promote the advancing exposure before comparison",
                        exposure.temporalDepth, firstStructureless.reliableDepth, 0.0f);
                assertNotEquals("the same held inference must still advance immediate temporal depth",
                        firstStructureless.reliableDepth,
                        firstStructureless.temporalDepth, 1.0e-4f);

                Observation persistentLow = processAndCommit(
                        detector, processor, probe, colorTexture, packedInputBuffer,
                        rawDepthBuffer, 4L);
                assertTrue("the second low-support inference must leave the one-frame bridge",
                        has(persistentLow.scene.evidence,
                                ClientSbsShotCutPolicy.SCENE_EVIDENCE_PERSISTENT_LOW_START));
                assertFalse(has(persistentLow.scene.evidence,
                        ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE));
                assertHistoryAdvances(persistentLow.processor.frameState);
                assertTrue("the next inference must still compare against retained reliable depth",
                        persistentLow.processor.changeFraction > 0.25f);
                assertEquals("a held result must not be promoted at the next boundary",
                        firstStructureless.reliableDepth,
                        persistentLow.reliableDepth, 0.0f);
                assertNotEquals("persistent-low promotion must wait for one inference boundary",
                        persistentLow.temporalDepth,
                        persistentLow.reliableDepth, 1.0e-4f);

                // Frame 4 authorized promotion, so frame 5 must install frame 4's exact temporal
                // output before comparing its own depth. The third flat color update is no longer
                // the one-frame bridge and must not carry the structureless-gap reason bit.
                Observation persistentLowBoundary = processAndCommit(
                        detector, processor, probe, colorTexture, packedInputBuffer,
                        rawDepthBuffer, 5L);
                assertEquals(0, persistentLowBoundary.scene.evidence);
                assertHistoryAdvances(persistentLowBoundary.processor.frameState);
                assertEquals("frame 5 must promote frame 4's persistent-low temporal result",
                        persistentLow.temporalDepth,
                        persistentLowBoundary.reliableDepth, 0.0f);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            } finally {
                GLES20.glDeleteTextures(1, new int[] {colorTexture}, 0);
                GLES30.glDeleteBuffers(2,
                        new int[] {packedInputBuffer, rawDepthBuffer}, 0);
            }
        }
    }

    @Test
    public void missingColorDetectorKeepsDepthLiveAndUsesBoundedConfirmation() {
        try (EglFixture ignored = EglFixture.create();
             ClientSbsGpuDepthProcessor processor = new ClientSbsGpuDepthProcessor(
                     WIDTH, HEIGHT, 1.0f, false, TEST_SNAP_TEMPORAL_REFERENCE_HZ)) {
            int rawDepthBuffer = createRawDepthBuffer(RAW_BASELINE);
            try {
                ProcessorRecord settled = null;
                // The settle-crossing update arms geometry only after its own decision.
                for (int frame = 0; frame < 9; frame++) {
                    settled = processDepthOnly(processor, rawDepthBuffer);
                }
                assertTrue(settled != null);
                assertHistoryAdvances(settled.frameState);

                updateRawDepthBuffer(rawDepthBuffer, RAW_REVERSED);
                ProcessorRecord pending = processDepthOnly(processor, rawDepthBuffer);
                assertTrue(has(pending.frameState,
                        ClientSbsShotCutPolicy.FRAME_STATE_HOLD_RELIABLE_HISTORY));
                assertFalse(has(pending.frameState,
                        ClientSbsShotCutPolicy.FRAME_STATE_STRUCTURELESS_GAP));
                assertFalse(pending.hardCut);
                assertTrue(has(pending.cutDecisionFlags,
                        ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_CANDIDATE));
                assertTrue(has(pending.cutDecisionFlags,
                        ClientSbsGpuDepthProcessor.CUT_DECISION_DEPTH_ONLY_FALLBACK));

                ProcessorRecord accepted = processDepthOnly(processor, rawDepthBuffer);
                assertHistoryAdvances(accepted.frameState);
                assertTrue(accepted.hardCut);
                assertTrue(has(accepted.cutDecisionFlags,
                        ClientSbsGpuDepthProcessor.CUT_DECISION_ACCEPTED_GEOMETRY));
                assertTrue(has(accepted.cutDecisionFlags,
                        ClientSbsGpuDepthProcessor.CUT_DECISION_DEPTH_ONLY_FALLBACK));
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            } finally {
                GLES30.glDeleteBuffers(1, new int[] {rawDepthBuffer}, 0);
            }
        }
    }

    private static Observation processAndCommit(
            ClientSbsGpuSceneCutDetector detector,
            ClientSbsGpuDepthProcessor processor,
            TextureProbe probe,
            int colorTexture,
            int packedInputBuffer,
            int rawDepthBuffer,
            long sourceFrameSequence) {
        int mailboxBuffer = processor.getSceneCutMailboxBufferId();
        int mailboxOffset = processor.getSceneCutMailboxByteOffset(SCENE_SLOT);
        detector.processRendererOwnedAndPack(
                colorTexture, packedInputBuffer, mailboxBuffer, mailboxOffset,
                false, 0L, SCENE_SLOT, sourceFrameSequence,
                sourceFrameSequence * 16_666_667L);
        processor.processRendererOwnedWithGpuSceneCut(
                rawDepthBuffer, 0, Float.BYTES, mailboxBuffer, mailboxOffset, 1);
        GLES20.glFinish();

        SceneRecord scene = readSceneRecord(mailboxBuffer, mailboxOffset);
        ProcessorRecord state = readProcessorRecord(
                processor.getHistoryDecisionStateBufferId());
        float reliableDepth = probe.readRed(processor.getReliableDepthTextureId(), 0, 0);
        float temporalDepth = probe.readRed(processor.getTemporalDepthTextureId(), 0, 0);

        detector.commitAcceptedFrame(processor.getHistoryDecisionStateBufferId());
        GLES20.glFinish();
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return new Observation(scene, state, reliableDepth, temporalDepth);
    }

    private static ProcessorRecord processDepthOnly(
            ClientSbsGpuDepthProcessor processor, int rawDepthBuffer) {
        processor.processRendererOwned(rawDepthBuffer, 0, Float.BYTES, false, 1);
        GLES20.glFinish();
        ProcessorRecord state = readProcessorRecord(
                processor.getHistoryDecisionStateBufferId());
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return state;
    }

    private static void assertHistoryAdvances(int frameState) {
        assertTrue(has(frameState, ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_DEPTH_VALID));
        assertTrue(has(frameState, ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_V2_VALID));
        assertTrue(has(frameState, ClientSbsShotCutPolicy.FRAME_STATE_HISTORY_ADVANCES));
        assertFalse(has(frameState,
                ClientSbsShotCutPolicy.FRAME_STATE_HOLD_RELIABLE_HISTORY));
        assertFalse(has(frameState,
                ClientSbsShotCutPolicy.FRAME_STATE_STRUCTURELESS_GAP));
    }

    private static void assertHistoryHolds(int frameState) {
        assertTrue(has(frameState, ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_DEPTH_VALID));
        assertTrue(has(frameState, ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_V2_VALID));
        assertFalse(has(frameState, ClientSbsShotCutPolicy.FRAME_STATE_HISTORY_ADVANCES));
        assertTrue(has(frameState,
                ClientSbsShotCutPolicy.FRAME_STATE_HOLD_RELIABLE_HISTORY));
        assertTrue(has(frameState,
                ClientSbsShotCutPolicy.FRAME_STATE_STRUCTURELESS_GAP));
    }

    private static boolean has(int value, int flag) {
        return (value & flag) != 0;
    }

    private static int createColorTexture(int pattern) {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        assertNotEquals(0, texture[0]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                WIDTH, HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                colorPixels(pattern));
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return texture[0];
    }

    private static void updateColorTexture(int texture, int pattern) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                WIDTH, HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                colorPixels(pattern));
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static ByteBuffer colorPixels(int pattern) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int value;
                if (pattern == COLOR_STRUCTURELESS) {
                    value = 48;
                } else {
                    int blockX = x / BLOCK_SIZE;
                    int blockY = y / BLOCK_SIZE;
                    value = 24 + 24 * blockX + 8 * blockY;
                    if (pattern == COLOR_EXPOSURE) {
                        value += 64;
                    }
                }
                pixels.put((byte) value);
                pixels.put((byte) value);
                pixels.put((byte) value);
                pixels.put((byte) 255);
            }
        }
        pixels.flip();
        return pixels;
    }

    private static int createBuffer(int bytes) {
        int[] buffer = new int[1];
        GLES30.glGenBuffers(1, buffer, 0);
        assertNotEquals(0, buffer[0]);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0]);
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,
                bytes, null, GLES30.GL_DYNAMIC_DRAW);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return buffer[0];
    }

    private static int createRawDepthBuffer(int pattern) {
        int buffer = createBuffer(WIDTH * HEIGHT * Float.BYTES);
        updateRawDepthBuffer(buffer, pattern);
        return buffer;
    }

    private static void updateRawDepthBuffer(int buffer, int pattern) {
        FloatBuffer values = ByteBuffer.allocateDirect(WIDTH * HEIGHT * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                float value;
                if (pattern == RAW_REVERSED) {
                    value = 10.0f + 0.01f * (WIDTH - 1 - x)
                            + 0.02f * (HEIGHT - 1 - y);
                } else if (pattern == RAW_SHIFTED) {
                    value = 20.0f + 0.01f * x + 0.02f * y;
                } else {
                    value = 1.0f + 0.01f * x + 0.02f * y;
                }
                values.put(value);
            }
        }
        values.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                WIDTH * HEIGHT * Float.BYTES, values);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static SceneRecord readSceneRecord(int buffer, int byteOffset) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,
                byteOffset, ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES,
                GLES30.GL_MAP_READ_BIT);
        assertTrue(mapped instanceof ByteBuffer);
        ByteBuffer values = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        SceneRecord record = new SceneRecord(
                values.getInt(0), values.getInt(5 * Integer.BYTES),
                values.getInt(6 * Integer.BYTES), values.getInt(7 * Integer.BYTES));
        assertTrue(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        return record;
    }

    private static ProcessorRecord readProcessorRecord(int buffer) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,
                0, ClientSbsGpuDepthProcessor.STATE_BYTES, GLES30.GL_MAP_READ_BIT);
        assertTrue(mapped instanceof ByteBuffer);
        ByteBuffer values = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        ProcessorRecord record = new ProcessorRecord(
                values.getInt(PROCESSOR_FRAME_STATE_BYTE_OFFSET),
                values.getFloat(PROCESSOR_CHANGE_FRACTION_BYTE_OFFSET),
                values.getInt(PROCESSOR_HARD_CUT_BYTE_OFFSET) != 0,
                values.getInt(PROCESSOR_CUT_DECISION_BYTE_OFFSET));
        assertTrue(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        return record;
    }

    private static final class SceneRecord {
        final int evidence;
        final int currentSupportCount;
        final int commonSupportCount;
        final int diagnosticFlags;

        SceneRecord(int evidence, int currentSupportCount,
                    int commonSupportCount, int diagnosticFlags) {
            this.evidence = evidence;
            this.currentSupportCount = currentSupportCount;
            this.commonSupportCount = commonSupportCount;
            this.diagnosticFlags = diagnosticFlags;
        }
    }

    private static final class ProcessorRecord {
        final int frameState;
        final float changeFraction;
        final boolean hardCut;
        final int cutDecisionFlags;

        ProcessorRecord(int frameState, float changeFraction,
                        boolean hardCut, int cutDecisionFlags) {
            this.frameState = frameState;
            this.changeFraction = changeFraction;
            this.hardCut = hardCut;
            this.cutDecisionFlags = cutDecisionFlags;
        }
    }

    private static final class Observation {
        final SceneRecord scene;
        final ProcessorRecord processor;
        final float reliableDepth;
        final float temporalDepth;

        Observation(SceneRecord scene, ProcessorRecord processor,
                    float reliableDepth, float temporalDepth) {
            this.scene = scene;
            this.processor = processor;
            this.reliableDepth = reliableDepth;
            this.temporalDepth = temporalDepth;
        }
    }

    /** Test-only compute probe so R32F verification does not depend on FBO extensions. */
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
            float value = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder()).getFloat(0);
            assertTrue(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER));
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            return value;
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
            int[] attributes = {
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            assertTrue(EGL14.eglChooseConfig(display, attributes, 0,
                    configs, 0, 1, count, 0));
            assertEquals(1, count[0]);
            EGLContext context = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, new int[] {
                            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                            EGL14.EGL_NONE
                    }, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[] {
                            EGL14.EGL_WIDTH, 1,
                            EGL14.EGL_HEIGHT, 1,
                            EGL14.EGL_NONE
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
