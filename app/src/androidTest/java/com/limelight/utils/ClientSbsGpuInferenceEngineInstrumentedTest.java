package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Physical-device smoke test for packed Float32 GL I/O with LiteRT GPU execution. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsGpuInferenceEngineInstrumentedTest {
    private static final String TAG = "ClientSbsGpuSmoke";
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final int PACKED_RGB_FLOAT_PIXEL_BYTES = 3 * Float.BYTES;
    private static final int PACKED_DEPTH_FLOAT_PIXEL_BYTES = Float.BYTES;

    @Test
    public void floatModelRunsWithPackedGlBuffers() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClientSbsModelManifest manifest = ClientSbsModelManifest.MIDAS_V2_FLOAT;

        try (EglFixture egl = EglFixture.create()) {
            ClientSbsGpuInferenceEngine engine = ClientSbsGpuInferenceEngine.createShared();
            assertNotNull("A shared LiteRT GPU context must be available", engine);
            ExecutorService inferenceWorker = Executors.newSingleThreadExecutor(
                    runnable -> new Thread(runnable, "ClientSbsGpuSmokeWorker"));
            try {
                inferenceWorker.submit(() -> {
                    engine.initialize(context, manifest);
                    return null;
                }).get();
                egl.assertRendererContextCurrent();
                int expectedInputBytes = manifest.getInputByteSize();
                int expectedOutputBytes = manifest.getOutputByteSize();
                assertTrue(engine.getInputBufferSize() >= expectedInputBytes);
                assertTrue(engine.getOutputBufferSize() >= expectedOutputBytes);
                assertEquals(PACKED_RGB_FLOAT_PIXEL_BYTES,
                        engine.getInputPixelStrideBytes());
                assertEquals(PACKED_DEPTH_FLOAT_PIXEL_BYTES,
                        engine.getOutputPixelStrideBytes());

                uploadGradient(engine.getInputBufferId(), manifest.getInputWidth(),
                        manifest.getInputHeight());
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                float[] inputRange = readFloatRange(engine.getInputBufferId(),
                        expectedInputBytes / Float.BYTES);
                Log.i(TAG, "input packed Float32 range=[" + inputRange[0] + ","
                        + inputRange[1] + "]");
                assertEquals(0.0f, inputRange[0], 0.0f);
                assertEquals(1.0f, inputRange[1], 0.0f);

                long inputReadyFence = createRendererFence();
                long startedNs = System.nanoTime();
                long outputFence = inferenceWorker.submit(
                        () -> engine.run(inputReadyFence, 0L)).get();
                egl.assertRendererContextCurrent();
                waitAndDeleteFence(outputFence);
                double elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0;
                float[] outputRange = readFloatRange(engine.getOutputBufferId(),
                        expectedOutputBytes / Float.BYTES);
                assertTrue("Depth output must be finite and nontrivial: ["
                                + outputRange[0] + "," + outputRange[1] + "]",
                        Float.isFinite(outputRange[0]) && Float.isFinite(outputRange[1])
                                && outputRange[1] > outputRange[0]
                                && outputRange[1] > 0.0f);
                // The model hash above pins this deterministic gradient's reference range. Keep
                // tolerance broad enough for FP16 driver variation while still catching zero,
                // scrambled, or wrong-layout output (Galaxy XR observed [96.125, 574.5]).
                assertTrue("GPU output minimum is outside the pinned-model range: "
                                + outputRange[0],
                        outputRange[0] >= 70.0f && outputRange[0] <= 140.0f);
                assertTrue("GPU output maximum is outside the pinned-model range: "
                                + outputRange[1],
                        outputRange[1] >= 450.0f && outputRange[1] <= 700.0f);
                assertTrue("GPU output dynamic range is too small",
                        outputRange[1] - outputRange[0] >= 350.0f);
                Log.i(TAG, "backend=LITERT_GPU_GL_FP16 invoke=" + elapsedMs
                        + "ms outputRange=[" + outputRange[0] + "," + outputRange[1] + "]");
            } finally {
                try {
                    inferenceWorker.submit(engine::close).get();
                } finally {
                    inferenceWorker.shutdown();
                    assertTrue("Inference worker must terminate",
                            inferenceWorker.awaitTermination(10, TimeUnit.SECONDS));
                }
                egl.assertRendererContextCurrent();
            }
        }
    }

    private static void uploadGradient(int bufferId, int width, int height) {
        ByteBuffer input = ByteBuffer.allocateDirect(width * height
                        * PACKED_RGB_FLOAT_PIXEL_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float red = x / (float) (width - 1);
                float green = y / (float) (height - 1);
                float blue = (x + y) / (float) (width + height - 2);
                input.putFloat(red);
                input.putFloat(green);
                input.putFloat(blue);
            }
        }
        input.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferId);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, input.remaining(), input);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static float[] readFloatRange(int bufferId, int valueCount) {
        int bytes = valueCount * Float.BYTES;
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferId);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes,
                GLES30.GL_MAP_READ_BIT);
        assertTrue("Model output GL buffer must be mappable", mapped instanceof ByteBuffer);
        ByteBuffer output = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < valueCount; index++) {
            float value = output.getFloat(index * Float.BYTES);
            if (Float.isFinite(value)) {
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        assertTrue("Model output GL buffer must unmap", GLES30.glUnmapBuffer(
                GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        return new float[] {minimum, maximum};
    }

    private static long createRendererFence() {
        long fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertTrue("Renderer must create an input-ready GL fence", fence != 0L);
        GLES20.glFlush();
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return fence;
    }

    private static void waitAndDeleteFence(long fence) {
        assertTrue("LiteRT must return an output GL fence", fence != 0L);
        int result = GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT,
                5_000_000_000L);
        GLES30.glDeleteSync(fence);
        assertTrue("LiteRT output fence wait failed: 0x" + Integer.toHexString(result),
                result == GLES30.GL_ALREADY_SIGNALED
                        || result == GLES30.GL_CONDITION_SATISFIED);
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
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            assertTrue(EGL14.eglChooseConfig(display, configAttributes, 0,
                    configs, 0, 1, count, 0));
            assertEquals(1, count[0]);
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            EGLContext context = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            int[] surfaceAttributes = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    surfaceAttributes, 0);
            assertTrue(surface != EGL14.EGL_NO_SURFACE);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
            return new EglFixture(display, surface, context);
        }

        void assertRendererContextCurrent() {
            assertEquals(context, EGL14.eglGetCurrentContext());
            assertEquals(surface, EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
            assertEquals(surface, EGL14.eglGetCurrentSurface(EGL14.EGL_READ));
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
