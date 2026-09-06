package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
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

/** Device gate for the GPU-published near-identical rejection reason in decision word 7. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsGpuSceneCutDecisionReasonInstrumentedTest {
    // Exercise a partial tile in both dimensions, including a final tile smaller than 64 texels.
    private static final int WIDTH = 35;
    private static final int HEIGHT = 19;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;

    @Test
    public void decisionRecordDistinguishesContentMonotonicIdentityAndCollapsedOwner() {
        try (EglFixture ignored = EglFixture.create();
             ClientSbsGpuSceneCutDetector detector =
                     new ClientSbsGpuSceneCutDetector(WIDTH, HEIGHT)) {
            int texture = createTexture(64);
            int[] inputBuffers = createInputBuffers();
            int processorState = createProcessorStateBuffer();
            try {
                process(detector, texture, inputBuffers[0], false,
                        1L, 100L, 1_000_000_000L);
                assertEquals(ClientSbsNearIdenticalPolicy.REASON_NOT_CANDIDATE,
                        readDecisionReason(detector, 0));
                detector.commitAcceptedFrame(processorState);
                GLES20.glFinish();

                process(detector, texture, inputBuffers[0], true,
                        2L, 101L, 1_033_000_000L);
                assertEquals(ClientSbsNearIdenticalPolicy.REASON_REUSE,
                        readDecisionReason(detector, 0));
                detector.discardPendingFrame();

                // Repeated/regressed source identities cannot borrow the retained result.
                process(detector, texture, inputBuffers[0], true,
                        3L, 100L, 1_033_000_000L);
                assertEquals(ClientSbsNearIdenticalPolicy.REASON_OWNER_INVALID,
                        readDecisionReason(detector, 0));
                detector.discardPendingFrame();

                process(detector, texture, inputBuffers[0], true,
                        4L, 101L, 999_999_999L);
                assertEquals(ClientSbsNearIdenticalPolicy.REASON_OWNER_INVALID,
                        readDecisionReason(detector, 0));
                detector.discardPendingFrame();

                updateTexture(texture, 70);
                process(detector, texture, inputBuffers[0], true,
                        5L, 101L, 1_033_000_000L);
                assertEquals(ClientSbsNearIdenticalPolicy.REASON_CONTENT_MEDIUM,
                        readDecisionReason(detector, 0));
                detector.discardPendingFrame();

                // A finite collapsed result still advances private cut/color history, but must
                // not become a reusable geometry owner. The next candidate must infer again.
                process(detector, texture, inputBuffers[0], false,
                        6L, 106L, 1_200_000_000L);
                updateProcessorFrameState(processorState,
                        ClientSbsShotCutPolicy.FRAME_STATE_HISTORY_ADVANCES);
                detector.commitAcceptedFrame(processorState);
                GLES20.glFinish();

                process(detector, texture, inputBuffers[0], true,
                        7L, 107L, 1_233_000_000L);
                assertEquals(ClientSbsNearIdenticalPolicy.REASON_OWNER_INVALID,
                        readDecisionReason(detector, 0));
                detector.discardPendingFrame();
            } finally {
                GLES20.glDeleteTextures(1, new int[] {texture}, 0);
                GLES30.glDeleteBuffers(inputBuffers.length, inputBuffers, 0);
                GLES30.glDeleteBuffers(1, new int[] {processorState}, 0);
            }
        }
    }

    @Test
    public void longLivedNearReuseKeepsRealOwnerAndRejectsCumulativeChange() {
        try (EglFixture ignored = EglFixture.create();
             ClientSbsGpuSceneCutDetector detector =
                     new ClientSbsGpuSceneCutDetector(WIDTH, HEIGHT)) {
            int texture = createTexture(64);
            int[] inputs = createInputBuffers();
            int state = createProcessorStateBuffer();
            final long ownerFrame = 0x1_0000_0064L;
            final long ownerTime = 1_000_000_000L;
            final long firstToken = 0x7654_3210_fedc_ba98L;
            try {
                process(detector, texture, inputs[0], false,
                        firstToken, ownerFrame, ownerTime);
                detector.commitAcceptedFrame(state);
                fillInputWithSentinel(inputs[1]);

                for (int step = 0; step < 4; step++) {
                    updateTexture(texture, 64 + step);
                    processLazy(detector, texture, inputs[1], firstToken + 1L + step,
                            ownerFrame + 0x1_0000_0000L + step,
                            ownerTime + 86_401_000_000_000L + step);
                    assertDecision(detector, ClientSbsNearIdenticalPolicy.DECISION_REUSE_TAG,
                            ClientSbsNearIdenticalPolicy.REASON_REUSE, firstToken + 1L + step);
                    assertInputSentinelUnchanged(inputs[1]);
                    // Reused frames never commit a replacement inference reference.
                    detector.discardPendingFrame();
                }

                // Each preceding step changed only one code. Against the actual owner (64),
                // this is four codes everywhere and must infer; a chained 67 owner would reuse.
                updateTexture(texture, 68);
                processLazy(detector, texture, inputs[1], firstToken + 5L,
                        ownerFrame + 0x1_0000_0004L, ownerTime + 86_401_000_000_004L);
                assertDecision(detector, ClientSbsNearIdenticalPolicy.DECISION_INFER_TAG,
                        ClientSbsNearIdenticalPolicy.REASON_CONTENT_MEDIUM, firstToken + 5L);
                assertInputSentinelUnchanged(inputs[1]);
                detector.discardPendingFrame();

                // A successful new real result replaces the content reference.
                process(detector, texture, inputs[0], false,
                        firstToken + 6L, ownerFrame + 0x1_0000_0005L,
                        ownerTime + 86_401_000_000_005L);
                detector.commitAcceptedFrame(state);
                processLazy(detector, texture, inputs[1], firstToken + 7L,
                        ownerFrame + 0x1_0000_0006L, ownerTime + 172_802_000_000_000L);
                assertDecision(detector, ClientSbsNearIdenticalPolicy.DECISION_REUSE_TAG,
                        ClientSbsNearIdenticalPolicy.REASON_REUSE, firstToken + 7L);
                detector.discardPendingFrame();

                detector.reset();
                processLazy(detector, texture, inputs[1], firstToken + 8L,
                        ownerFrame + 0x1_0000_0007L, ownerTime + 172_802_000_000_001L);
                assertDecision(detector, ClientSbsNearIdenticalPolicy.DECISION_INFER_TAG,
                        ClientSbsNearIdenticalPolicy.REASON_NOT_CANDIDATE, firstToken + 8L);
                detector.discardPendingFrame();
            } finally {
                GLES20.glDeleteTextures(1, new int[] {texture}, 0);
                GLES30.glDeleteBuffers(inputs.length, inputs, 0);
                GLES30.glDeleteBuffers(1, new int[] {state}, 0);
            }
        }
    }

    private static void processLazy(ClientSbsGpuSceneCutDetector detector, int texture,
                                    int inputBuffer, long token, long frame, long capturedAtNs) {
        detector.processRendererOwnedAndPack(texture, inputBuffer,
                detector.getSceneCutBufferId(), detector.getSceneCutByteOffset(),
                true, token, 0, frame, capturedAtNs, true);
        GLES20.glFinish();
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static void assertDecision(ClientSbsGpuSceneCutDetector detector,
                                       int tag, int reason, long token) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,
                detector.getNearIdenticalDecisionBufferId());
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                ClientSbsGpuSceneCutDetector.NEAR_IDENTICAL_DECISION_RECORD_BYTES,
                GLES30.GL_MAP_READ_BIT);
        assertTrue(mapped instanceof ByteBuffer);
        ByteBuffer words = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        assertEquals(tag, words.getInt(0));
        assertEquals(reason, words.getInt(7 * Integer.BYTES));
        assertEquals(tag ^ ClientSbsNearIdenticalPolicy.DECISION_COOKIE,
                words.getInt(Integer.BYTES));
        assertEquals((int) token, words.getInt(2 * Integer.BYTES));
        assertEquals((int) (token >>> 32), words.getInt(3 * Integer.BYTES));
        assertEquals((int) token ^ ClientSbsNearIdenticalPolicy.TOKEN_LOW_COOKIE,
                words.getInt(4 * Integer.BYTES));
        assertEquals((int) (token >>> 32) ^ ClientSbsNearIdenticalPolicy.TOKEN_HIGH_COOKIE,
                words.getInt(5 * Integer.BYTES));
        assertEquals(ClientSbsNearIdenticalPolicy.PROPOSAL_MAGIC,
                words.getInt(6 * Integer.BYTES));
        assertTrue(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void fillInputWithSentinel(int inputBuffer) {
        ByteBuffer sentinel = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        while (sentinel.hasRemaining()) sentinel.putInt(0x7fc12345);
        sentinel.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, inputBuffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, sentinel.capacity(), sentinel);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void assertInputSentinelUnchanged(int inputBuffer) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, inputBuffer);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                WIDTH * HEIGHT * 3 * Float.BYTES, GLES30.GL_MAP_READ_BIT);
        assertTrue(mapped instanceof ByteBuffer);
        ByteBuffer words = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        for (int index = 0; index < WIDTH * HEIGHT * 3; index++) {
            assertEquals("Lazy classifier wrote input word " + index,
                    0x7fc12345, words.getInt(index * Integer.BYTES));
        }
        assertTrue(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void process(ClientSbsGpuSceneCutDetector detector, int texture,
                                int inputBuffer, boolean candidate, long token,
                                long frameSequence, long capturedAtNs) {
        detector.processRendererOwnedAndPack(texture, inputBuffer,
                detector.getSceneCutBufferId(), detector.getSceneCutByteOffset(),
                candidate, token, 0, frameSequence, capturedAtNs);
        GLES20.glFinish();
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static int readDecisionReason(ClientSbsGpuSceneCutDetector detector, int slot) {
        return readDecisionWord(detector, slot, 7);
    }

    private static int readDecisionWord(ClientSbsGpuSceneCutDetector detector, int slot, int word) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,
                detector.getNearIdenticalDecisionBufferId());
        int offset = detector.getNearIdenticalDecisionByteOffset(slot);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,
                offset, ClientSbsGpuSceneCutDetector.NEAR_IDENTICAL_DECISION_RECORD_BYTES,
                GLES30.GL_MAP_READ_BIT);
        assertTrue(mapped instanceof ByteBuffer);
        int value = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder())
                .getInt(word * Integer.BYTES);
        assertTrue(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return value;
    }

    private static int createTexture(int channel) {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        assertTrue(texture[0] != 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_NEAREST);
        uploadTexture(channel, false);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return texture[0];
    }

    private static void updateTexture(int texture, int channel) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        uploadTexture(channel, true);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static void uploadTexture(int channel, boolean update) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
        for (int index = 0; index < WIDTH * HEIGHT; index++) {
            pixels.put((byte) channel);
            pixels.put((byte) channel);
            pixels.put((byte) channel);
            // Fixture RGB is already source-point grayscale; alpha carries its ordinal.
            pixels.put((byte) channel);
        }
        pixels.flip();
        if (update) {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, WIDTH, HEIGHT,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        } else {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, WIDTH, HEIGHT, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        }
    }

    private static int[] createInputBuffers() {
        int[] buffers = new int[ClientSbsGpuSceneCutDetector.NEAR_IDENTICAL_DECISION_SLOT_COUNT];
        GLES30.glGenBuffers(buffers.length, buffers, 0);
        int bytes = WIDTH * HEIGHT * 3 * Float.BYTES;
        for (int buffer : buffers) {
            assertTrue(buffer != 0);
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null,
                    GLES30.GL_DYNAMIC_DRAW);
        }
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return buffers;
    }

    private static int createProcessorStateBuffer() {
        int[] buffer = new int[1];
        GLES30.glGenBuffers(1, buffer, 0);
        assertTrue(buffer[0] != 0);
        ByteBuffer state = ByteBuffer.allocateDirect(32 * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
        state.putInt(18 * Integer.BYTES,
                ClientSbsShotCutPolicy.FRAME_STATE_HISTORY_ADVANCES
                        | ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_DEPTH_VALID
                        | ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_V2_VALID);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0]);
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, state.capacity(), state,
                GLES30.GL_DYNAMIC_DRAW);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return buffer[0];
    }

    private static void updateProcessorFrameState(int buffer, int frameState) {
        ByteBuffer state = ByteBuffer.allocateDirect(Integer.BYTES)
                .order(ByteOrder.nativeOrder());
        state.putInt(frameState);
        state.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER,
                18 * Integer.BYTES, Integer.BYTES, state);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
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
            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE
            };
            EGLContext context = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            int[] surfaceAttributes = {
                    EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE
            };
            EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    surfaceAttributes, 0);
            assertTrue(surface != EGL14.EGL_NO_SURFACE);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
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
