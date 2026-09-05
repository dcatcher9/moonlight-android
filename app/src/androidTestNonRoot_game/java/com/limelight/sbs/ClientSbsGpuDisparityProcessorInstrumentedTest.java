package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

/** Physical-device compile and dispatch gate for the production contractive disparity shapes. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsGpuDisparityProcessorInstrumentedTest {
    private static final String TAG = "ClientSbsDisparity";
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final int MEASURED_DISPATCHES = 20;

    @Test
    public void allProductionShapesCompileAndDispatchOnDevice() {
        try (EglFixture ignored = EglFixture.create()) {
            int profileTexture = createProfileTexture();
            try {
                runShape(672, 384, 1920, 1080, profileTexture);
                runShape(896, 384, 2560, 1080, profileTexture);
                runShape(928, 384, 3840, 1080, profileTexture);
            } finally {
                GLES20.glDeleteTextures(1, new int[] {profileTexture}, 0);
            }
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        }
    }

    private static void runShape(int width, int height, int sourceWidth, int sourceHeight,
                                 int profileTexture) {
        int depthTexture = createDepthTexture(width, height);
        try {
            long initializationStartedNs = System.nanoTime();
            ClientSbsGpuDisparityProcessor created =
                    new ClientSbsGpuDisparityProcessor(width, height, rawScaleForWidth(width));
            double initializationMs =
                    (System.nanoTime() - initializationStartedNs) / 1_000_000.0;
            double firstDispatchMs;
            double repeatedDispatchMs;
            try (ClientSbsGpuDisparityProcessor processor = created) {
                long firstDispatchStartedNs = System.nanoTime();
                int outputTexture = processor.process(
                        depthTexture, profileTexture, sourceWidth, sourceHeight);
                assertNotEquals("Processor must publish its R32F parallax texture", 0,
                        outputTexture);
                GLES20.glFinish();
                assertEquals("Contractive dispatch failed for " + width + "x" + height,
                        GLES20.GL_NO_ERROR, GLES20.glGetError());
                firstDispatchMs = (System.nanoTime() - firstDispatchStartedNs) / 1_000_000.0;

                long repeatedStartedNs = System.nanoTime();
                for (int iteration = 0; iteration < MEASURED_DISPATCHES; iteration++) {
                    processor.process(depthTexture, profileTexture, sourceWidth, sourceHeight);
                }
                GLES20.glFinish();
                assertEquals("Repeated contractive dispatch failed for " + width + "x" + height,
                        GLES20.GL_NO_ERROR, GLES20.glGetError());
                repeatedDispatchMs = (System.nanoTime() - repeatedStartedNs)
                        / 1_000_000.0 / MEASURED_DISPATCHES;
            }
            Log.i(TAG, String.format(Locale.US,
                    "contractive disparity shape=%dx%d init=%.3f ms first=%.3f ms repeated=%.3f ms",
                    width, height, initializationMs, firstDispatchMs, repeatedDispatchMs));
        } finally {
            GLES20.glDeleteTextures(1, new int[] {depthTexture}, 0);
        }
    }

    private static float rawScaleForWidth(int width) {
        if (width == 672) {
            return 0.04864449f;
        }
        if (width == 896) {
            return 0.04707071f;
        }
        if (width == 928) {
            return 0.05421491f;
        }
        throw new IllegalArgumentException("Uncalibrated ZipDepth width " + width);
    }

    private static int createDepthTexture(int width, int height) {
        FloatBuffer values = ByteBuffer.allocateDirect(width * height * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Smooth ramp plus bounded block edges exercises both limiter directions.
                float ramp = x / (float) Math.max(width - 1, 1);
                float block = ((x / 32 + y / 24) & 1) == 0 ? -0.12f : 0.12f;
                values.put(Math.max(0.0f, Math.min(1.0f, ramp + block)));
            }
        }
        values.flip();
        return createTexture(width, height, GLES30.GL_R32F, GLES30.GL_RED, values);
    }

    private static int createProfileTexture() {
        FloatBuffer values = ByteBuffer.allocateDirect(16 * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        // Texel zero is the production camera contract: shot mean in X and current-frame
        // geometry readiness in W. Keep all retired percentile/Bestv2 profile texels zero.
        values.put(new float[] {
                0.5f, 0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f,
        }).flip();
        return createTexture(4, 1, GLES30.GL_RGBA32F, GLES30.GL_RGBA, values);
    }

    private static int createTexture(int width, int height, int internalFormat, int format,
                                     FloatBuffer values) {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        assertNotEquals(0, texture[0]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                format, GLES20.GL_FLOAT, values);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return texture[0];
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
            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE,
            };
            EGLContext context = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            int[] surfaceAttributes = {
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE,
            };
            EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    surfaceAttributes, 0);
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
