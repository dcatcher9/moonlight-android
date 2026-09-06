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
import android.opengl.GLES31;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.Buffer;
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
    public void reusedR32fScratchMatchesPriorRgbaHorizontalPassesBitForBit() throws Exception {
        try (EglFixture ignored = EglFixture.create()) {
            int profile = createProfileTexture();
            try {
                for (int[] size : new int[][] {
                        {1, 1}, {1, 7}, {7, 1}, {33, 17},
                        {672, 384}, {896, 384}, {928, 384}}) {
                    comparePriorHorizontalPasses(size[0], size[1], profile);
                }
            } finally {
                GLES20.glDeleteTextures(1, new int[] {profile}, 0);
            }
        }
    }

    private static void comparePriorHorizontalPasses(int width, int height, int profile)
            throws Exception {
        int depth = createDepthTexture(width, height);
        int forwardScratch = createTexture(width, height, GLES30.GL_RGBA32F, GLES30.GL_RGBA, null);
        int referenceOutput = createTexture(width, height, GLES30.GL_R32F, GLES30.GL_RED, null);
        // Restore only the previous production storage operations. Its recurrence, indexing,
        // boundary handling and Float32 arithmetic remain the exact production shader source.
        String oldForward = ClientSbsGpuDisparityShaders.horizontalForward(width, height)
                .replace("layout(r32f, binding = 0)", "layout(rgba32f, binding = 0)")
                .replace("uFinalParallax", "uEnvelopeScratch");
        String oldReverse = ClientSbsGpuDisparityShaders.horizontalFinish(width, height)
                .replace("layout(r32f, binding = 0) uniform highp image2D uFinalParallax;",
                        "uniform highp sampler2D uEnvelopeScratch;\n"
                                + "layout(r32f, binding = 0) uniform writeonly highp image2D uFinalParallax;")
                .replace("imageLoad(uFinalParallax, ivec2(x, y)).r",
                        "texelFetch(uEnvelopeScratch, ivec2(x, y), 0).r");
        assertTrue(oldForward.contains("layout(rgba32f, binding = 0)"));
        assertTrue(oldReverse.contains("texelFetch(uEnvelopeScratch"));
        int forward = createComputeProgram(oldForward);
        int reverse = createComputeProgram(oldReverse);
        int readback = createComputeProgram("#version 310 es\n"
                + "precision highp float; precision highp int;\n"
                + "layout(local_size_x=16, local_size_y=16) in;\n"
                + "uniform highp sampler2D uField;\n"
                + "layout(std430, binding=0) writeonly buffer Out { uint words[]; };\n"
                + "void main() { ivec2 p=ivec2(gl_GlobalInvocationID.xy);\n"
                + "if(p.x >= " + width + " || p.y >= " + height + ") return;\n"
                + "words[p.y * " + width + " + p.x] = floatBitsToUint(texelFetch(uField,p,0).r); }\n");
        int[] readbackBuffer = new int[1];
        GLES30.glGenBuffers(1, readbackBuffer, 0);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, readbackBuffer[0]);
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, width * height * Float.BYTES,
                null, GLES30.GL_STREAM_READ);
        try (ClientSbsGpuDisparityProcessor processor = new ClientSbsGpuDisparityProcessor(
                width, height, width >= 672 ? rawScaleForWidth(width) : 0.04864449f)) {
            java.lang.reflect.Field verticalField = ClientSbsGpuDisparityProcessor.class
                    .getDeclaredField("verticalTexture");
            verticalField.setAccessible(true);
            int vertical = verticalField.getInt(processor);
            int previousOutput = 0;
            for (int frame = 0; frame < 4; frame++) {
                // Repeated target reuse, opposing cliffs, a plane and an invalid profile expose
                // accidental reads of last frame's final output instead of this frame's forward scan.
                FloatBuffer values = ByteBuffer.allocateDirect(width * height * Float.BYTES)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    float value = frame == 2 ? 0.5f
                            : ((((x / 3 + y / 2 + frame) & 1) == 0) ? 0.0f : 1.0f);
                    values.put(value);
                }
                values.flip();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depth);
                GLES30.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                        GLES30.GL_RED, GLES20.GL_FLOAT, values);
                FloatBuffer camera = ByteBuffer.allocateDirect(4 * Float.BYTES)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                camera.put(new float[] {0.5f, 0, 0, frame == 3 ? 0 : 1}).flip();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, profile);
                GLES30.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 1, 1,
                        GLES30.GL_RGBA, GLES20.GL_FLOAT, camera);

                int actual = processor.process(depth, profile);
                if (frame > 0) assertEquals("Final texture allocation must be reused", previousOutput, actual);
                previousOutput = actual;
                dispatchReference(forward, vertical, 0, forwardScratch, GLES30.GL_RGBA32F, height);
                dispatchReference(reverse, vertical, forwardScratch, referenceOutput, GLES30.GL_R32F, height);
                int[] expected = readTextureWords(readback, readbackBuffer[0], referenceOutput, width, height);
                int[] observed = readTextureWords(readback, readbackBuffer[0], actual, width, height);
                for (int pixel = 0; pixel < expected.length; pixel++) {
                    if (expected[pixel] != observed[pixel]) {
                        throw new AssertionError("R32F scratch differs from prior RGBA32F at "
                                + width + "x" + height + " frame=" + frame + " pixel=" + pixel
                                + " expectedBits=" + expected[pixel] + " actualBits=" + observed[pixel]);
                    }
                    assertTrue("Published parallax must be finite",
                            Float.isFinite(Float.intBitsToFloat(observed[pixel])));
                }
            }
        } finally {
            GLES31.glBindImageTexture(0, 0, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GLES20.glUseProgram(0);
            GLES20.glDeleteProgram(forward);
            GLES20.glDeleteProgram(reverse);
            GLES20.glDeleteProgram(readback);
            GLES30.glDeleteBuffers(1, readbackBuffer, 0);
            GLES20.glDeleteTextures(3, new int[] {depth, forwardScratch, referenceOutput}, 0);
        }
    }

    private static void dispatchReference(int program, int vertical, int scratch,
                                          int output, int format, int height) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vertical);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uVerticalConditioned"), 0);
        if (scratch != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scratch);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uEnvelopeScratch"), 1);
        }
        GLES31.glBindImageTexture(0, output, 0, false, 0, GLES31.GL_WRITE_ONLY, format);
        assertEquals("Reference shader image binding", GLES20.GL_NO_ERROR, GLES20.glGetError());
        GLES31.glDispatchCompute((height + 31) / 32, 1, 1);
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static int[] readTextureWords(int program, int buffer, int texture,
                                           int width, int height) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uField"), 0);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer);
        GLES31.glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
        GLES20.glFinish();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,
                0, width * height * Float.BYTES, GLES30.GL_MAP_READ_BIT);
        assertTrue(mapped instanceof ByteBuffer);
        ByteBuffer bytes = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        int[] words = new int[width * height];
        for (int i = 0; i < words.length; i++) words[i] = bytes.getInt(i * Integer.BYTES);
        assertTrue(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER));
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return words;
    }

    private static int createComputeProgram(String source) {
        int shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        assertEquals(GLES20.glGetShaderInfoLog(shader), GLES20.GL_TRUE, status[0]);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(shader);
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        assertEquals(GLES20.glGetProgramInfoLog(program), GLES20.GL_TRUE, status[0]);
        return program;
    }

    @Test
    public void allProductionShapesCompileAndDispatchOnDevice() {
        try (EglFixture ignored = EglFixture.create()) {
            int profileTexture = createProfileTexture();
            try {
                runShape(672, 384, profileTexture);
                runShape(896, 384, profileTexture);
                runShape(928, 384, profileTexture);
            } finally {
                GLES20.glDeleteTextures(1, new int[] {profileTexture}, 0);
            }
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        }
    }

    private static void runShape(int width, int height, int profileTexture) {
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
                int outputTexture = processor.process(depthTexture, profileTexture);
                assertNotEquals("Processor must publish its R32F parallax texture", 0,
                        outputTexture);
                GLES20.glFinish();
                assertEquals("Contractive dispatch failed for " + width + "x" + height,
                        GLES20.GL_NO_ERROR, GLES20.glGetError());
                firstDispatchMs = (System.nanoTime() - firstDispatchStartedNs) / 1_000_000.0;

                long repeatedStartedNs = System.nanoTime();
                for (int iteration = 0; iteration < MEASURED_DISPATCHES; iteration++) {
                    processor.process(depthTexture, profileTexture);
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
        // The reference scratch/output are bound as shader images. ES 3.1 requires
        // immutable image storage, matching the production processor's allocation.
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, internalFormat, width, height);
        if (values != null) {
            GLES30.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    format, GLES20.GL_FLOAT, values);
        }
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
