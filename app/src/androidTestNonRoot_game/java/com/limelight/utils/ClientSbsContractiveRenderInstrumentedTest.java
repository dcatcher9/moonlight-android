package com.limelight.utils;

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

/** Physical-device offscreen gate for the strict seed, refinement, and packed-compose shaders. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsContractiveRenderInstrumentedTest {
    private static final String TAG = "ClientSbsContractive";
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final int DEPTH_WIDTH = 672;
    private static final int DEPTH_HEIGHT = 384;
    private static final int REFINED_WIDTH = DEPTH_WIDTH * 2;
    private static final int PACKED_WIDTH = DEPTH_WIDTH * 2;
    private static final float PARALLAX = 1.0f / 32.0f;

    // Match Stereo3DRenderer's production quad and vertically flipped texture coordinates.
    private static final float[] QUAD_VERTICES = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
    };
    private static final float[] TEXTURE_VERTICES = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
    };

    @Test
    public void contractiveWarpSeedRefinementAndPackedComposeRenderOffscreen() {
        try (EglFixture ignored = EglFixture.create()) {
            int contractiveProgram = 0;
            int refinementProgram = 0;
            int composeProgram = 0;
            int parallaxTexture = 0;
            int warpMapTexture = 0;
            int refinedWarpMapTexture = 0;
            int colorTexture = 0;
            int outputTexture = 0;
            int warpMapFbo = 0;
            int refinedWarpMapFbo = 0;
            int outputFbo = 0;
            try {
                FloatBuffer quadVertices = floatBuffer(QUAD_VERTICES);
                FloatBuffer textureVertices = floatBuffer(TEXTURE_VERTICES);
                contractiveProgram = createProgram(
                        ShaderUtils.VERTEX_SHADER,
                        ClientSbsShaders.CONTRACTIVE_WARP_MAP_FRAGMENT);
                refinementProgram = createProgram(
                        ShaderUtils.VERTEX_SHADER,
                        ClientSbsShaders.CONTRACTIVE_WARP_MAP_REFINEMENT_FRAGMENT);
                composeProgram = createProgram(
                        ShaderUtils.VERTEX_SHADER,
                        ClientSbsShaders.WARPED_REPROJECTION_FRAGMENT);

                parallaxTexture = createParallaxTexture();
                warpMapTexture = createTexture(
                        DEPTH_WIDTH, DEPTH_HEIGHT,
                        GLES30.GL_RG16F, GLES30.GL_RG, GLES30.GL_HALF_FLOAT, null);
                warpMapFbo = createFramebuffer(warpMapTexture, "RG16F 1x warp seed");
                refinedWarpMapTexture = createTexture(
                        REFINED_WIDTH, DEPTH_HEIGHT,
                        GLES30.GL_RG16F, GLES30.GL_RG, GLES30.GL_HALF_FLOAT, null);
                refinedWarpMapFbo = createFramebuffer(
                        refinedWarpMapTexture, "RG16F 2x-horizontal refined warp map");

                GLES20.glDisable(GLES20.GL_BLEND);
                GLES20.glDisable(GLES20.GL_DEPTH_TEST);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                GLES20.glDisable(GLES20.GL_DITHER);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, warpMapFbo);
                GLES20.glViewport(0, 0, DEPTH_WIDTH, DEPTH_HEIGHT);
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glUseProgram(contractiveProgram);
                int[] contractiveAttributes = bindQuad(
                        contractiveProgram, quadVertices, textureVertices);
                int parallaxUniform = requireUniform(
                        contractiveProgram, "s_ParallaxTexture");
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, parallaxTexture);
                GLES20.glUniform1i(parallaxUniform, 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                disableAttributes(contractiveAttributes);
                assertNoGlError("contractive RG16F 1x warp-seed draw");

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, refinedWarpMapFbo);
                GLES20.glViewport(0, 0, REFINED_WIDTH, DEPTH_HEIGHT);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glUseProgram(refinementProgram);
                int[] refinementAttributes = bindQuad(
                        refinementProgram, quadVertices, textureVertices);
                int coarseWarpMapUniform = requireUniform(
                        refinementProgram, "s_CoarseWarpMapTexture");
                int refinementParallaxUniform = requireUniform(
                        refinementProgram, "s_ParallaxTexture");
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMapTexture);
                GLES20.glUniform1i(coarseWarpMapUniform, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, parallaxTexture);
                GLES20.glUniform1i(refinementParallaxUniform, 1);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                disableAttributes(refinementAttributes);
                assertNoGlError("seeded RG16F 2x-horizontal refinement draw");

                colorTexture = createColorTexture();
                outputTexture = createTexture(
                        PACKED_WIDTH, DEPTH_HEIGHT,
                        GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                outputFbo = createFramebuffer(outputTexture, "RGBA8 packed output");

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo);
                GLES20.glViewport(0, 0, PACKED_WIDTH, DEPTH_HEIGHT);
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glUseProgram(composeProgram);
                int[] composeAttributes = bindQuad(
                        composeProgram, quadVertices, textureVertices);
                int colorUniform = requireUniform(composeProgram, "s_ColorTexture");
                int warpMapUniform = requireUniform(composeProgram, "s_WarpMapTexture");
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture);
                GLES20.glUniform1i(colorUniform, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, refinedWarpMapTexture);
                GLES20.glUniform1i(warpMapUniform, 1);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                disableAttributes(composeAttributes);
                GLES20.glFinish();
                assertNoGlError("packed warp-map compose draw");

                ByteBuffer left = readPixel(PACKED_WIDTH / 4, DEPTH_HEIGHT / 2, "left eye");
                ByteBuffer right = readPixel(
                        3 * PACKED_WIDTH / 4, DEPTH_HEIGHT / 2, "right eye");
                int leftRed = unsigned(left.get(0));
                int rightRed = unsigned(right.get(0));
                assertTrue("Positive parallax must shift the right-eye gradient sample forward: "
                                + leftRed + " vs " + rightRed,
                        rightRed >= leftRed + 8);
                assertEquals(255, unsigned(left.get(3)));
                assertEquals(255, unsigned(right.get(3)));
                Log.i(TAG, "offscreen contractive refinement route passed at seed="
                        + DEPTH_WIDTH + "x" + DEPTH_HEIGHT
                        + " refined=" + REFINED_WIDTH + "x" + DEPTH_HEIGHT
                        + " -> " + PACKED_WIDTH + "x" + DEPTH_HEIGHT
                        + "; center red=" + leftRed + "/" + rightRed);
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glUseProgram(0);
                GLES20.glDeleteFramebuffers(3,
                        new int[] {warpMapFbo, refinedWarpMapFbo, outputFbo}, 0);
                GLES20.glDeleteTextures(5, new int[] {
                        parallaxTexture, warpMapTexture, refinedWarpMapTexture,
                        colorTexture, outputTexture,
                }, 0);
                if (contractiveProgram != 0) {
                    GLES20.glDeleteProgram(contractiveProgram);
                }
                if (refinementProgram != 0) {
                    GLES20.glDeleteProgram(refinementProgram);
                }
                if (composeProgram != 0) {
                    GLES20.glDeleteProgram(composeProgram);
                }
            }
        }
    }

    private static int createParallaxTexture() {
        FloatBuffer values = ByteBuffer
                .allocateDirect(DEPTH_WIDTH * DEPTH_HEIGHT * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        for (int pixel = 0; pixel < DEPTH_WIDTH * DEPTH_HEIGHT; pixel++) {
            values.put(PARALLAX);
        }
        values.flip();
        return createTexture(
                DEPTH_WIDTH, DEPTH_HEIGHT,
                GLES30.GL_R32F, GLES30.GL_RED, GLES20.GL_FLOAT, values);
    }

    private static int createColorTexture() {
        ByteBuffer values = ByteBuffer.allocateDirect(DEPTH_WIDTH * DEPTH_HEIGHT * 4);
        for (int y = 0; y < DEPTH_HEIGHT; y++) {
            int green = y * 255 / (DEPTH_HEIGHT - 1);
            for (int x = 0; x < DEPTH_WIDTH; x++) {
                values.put((byte) (x * 255 / (DEPTH_WIDTH - 1)));
                values.put((byte) green);
                values.put((byte) 64);
                values.put((byte) 255);
            }
        }
        values.flip();
        return createTexture(
                DEPTH_WIDTH, DEPTH_HEIGHT,
                GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, values);
    }

    private static int createTexture(int width, int height, int internalFormat, int format,
                                     int type, java.nio.Buffer values) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        assertNotEquals("Texture allocation returned name zero", 0, textures[0]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat,
                width, height, 0, format, type, values);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        assertNoGlError("texture allocation " + width + "x" + height);
        return textures[0];
    }

    private static int createFramebuffer(int texture, String label) {
        int[] framebuffers = new int[1];
        GLES20.glGenFramebuffers(1, framebuffers, 0);
        assertNotEquals(label + " framebuffer allocation returned name zero",
                0, framebuffers[0]);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0]);
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texture, 0);
        assertEquals(label + " framebuffer is incomplete",
                GLES20.GL_FRAMEBUFFER_COMPLETE,
                GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
        assertNoGlError(label + " framebuffer setup");
        return framebuffers[0];
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        if (program == 0) {
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            throw new AssertionError("Program allocation returned name zero");
        }
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        String log = GLES20.glGetProgramInfoLog(program);
        GLES20.glDetachShader(program, vertexShader);
        GLES20.glDetachShader(program, fragmentShader);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (linked[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(program);
            throw new AssertionError("Program link failed: " + log);
        }
        assertNoGlError("program link");
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) {
            throw new AssertionError("Shader allocation returned name zero for type " + type);
        }
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new AssertionError("Shader compile failed for type " + type + ": " + log);
        }
        return shader;
    }

    private static int[] bindQuad(int program, FloatBuffer positions, FloatBuffer textureCoords) {
        int position = GLES20.glGetAttribLocation(program, "a_Position");
        int texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
        assertTrue("a_Position must remain active", position >= 0);
        assertTrue("a_TexCoord must remain active", texCoord >= 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        positions.position(0);
        GLES20.glVertexAttribPointer(
                position, 2, GLES20.GL_FLOAT, false, 0, positions);
        textureCoords.position(0);
        GLES20.glVertexAttribPointer(
                texCoord, 2, GLES20.GL_FLOAT, false, 0, textureCoords);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glEnableVertexAttribArray(texCoord);
        return new int[] {position, texCoord};
    }

    private static void disableAttributes(int[] attributes) {
        for (int attribute : attributes) {
            GLES20.glDisableVertexAttribArray(attribute);
        }
    }

    private static int requireUniform(int program, String name) {
        int location = GLES20.glGetUniformLocation(program, name);
        assertTrue(name + " must remain active", location >= 0);
        return location;
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).flip();
        return buffer;
    }

    private static ByteBuffer readPixel(int x, int y, String label) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(x, y, 1, 1,
                GLES30.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        assertNoGlError(label + " packed output readback");
        return pixel;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static void assertNoGlError(String stage) {
        assertEquals(stage, GLES20.GL_NO_ERROR, GLES20.glGetError());
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
            EGLContext context = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            int[] surfaceAttributes = {
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE,
            };
            EGLSurface surface = EGL14.eglCreatePbufferSurface(
                    display, configs[0], surfaceAttributes, 0);
            assertTrue(surface != EGL14.EGL_NO_SURFACE);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
            String version = GLES20.glGetString(GLES20.GL_VERSION);
            assertTrue("GLES 3.x required, got " + version,
                    version != null && (version.contains("OpenGL ES 3.0")
                            || version.contains("OpenGL ES 3.1")
                            || version.contains("OpenGL ES 3.2")));
            assertNoGlError("EGL/GLES initialization");
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
