package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.limelight.sbs.ClientSbsGpuDepthProcessor;
import com.limelight.sbs.ClientSbsGpuSceneCutDetector;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Actual OES preprocessing -> RGB packing -> ordinal comparison and GPU history commit. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsSourceOrdinalInstrumentedTest {
    private static final int SOURCE_WIDTH = 3840, SOURCE_HEIGHT = 2160;
    private static final int WIDTH = 672, HEIGHT = 384;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final float[] VERTICES = {-1, -1, 1, -1, -1, 1, 1, 1};
    private static final float[] UV = {0, 0, 1, 0, 0, 1, 1, 1};

    @Test
    public void sparseOrdinalsMatchDenseSourcePointsForProductionAndPartialTiles() {
        try (EglFixture ignored = EglFixture.create()) {
            int[][] cases = {
                    {672, 384, 1920, 1080}, {896, 384, 2560, 1080},
                    {928, 384, 3440, 1440}, {672, 384, 1080, 1920},
                    {1, 1, 2, 2}, {2, 15, 5, 29}, {15, 2, 29, 5},
                    {17, 1, 43, 2}, {1, 17, 2, 43}, {33, 17, 79, 43},
            };
            for (int[] size : cases) {
                // Both production shader variants, including reflected padding folds. The OES
                // fixture carries 8-bit codes; the HDR case exercises the actual PQ conversion
                // branch and does not claim to validate a hardware 10-bit decoder.
                boolean direct = size[2] >= size[3];
                runSparseOrdinalCase(size[0], size[1], size[2], size[3], direct, false);
                runSparseOrdinalCase(size[0], size[1], size[2], size[3], direct, true);
            }
        }
    }

    private static void runSparseOrdinalCase(int width, int height, int sourceWidth,
                                              int sourceHeight, boolean direct, boolean hdr) {
        int[] textures = new int[3], fbos = new int[2], sampler = new int[1];
        GLES20.glGenTextures(3, textures, 0);
        GLES20.glGenFramebuffers(2, fbos, 0);
        GLES30.glGenSamplers(1, sampler, 0);
        GLES30.glSamplerParameteri(sampler[0], GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES30.glSamplerParameteri(sampler[0], GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES30.glSamplerParameteri(sampler[0], GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES30.glSamplerParameteri(sampler[0], GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        String production = ClientSbsShaders.createModelInputFragment(direct);
        // This restores the previous dense production behavior without replacing the source
        // sampler, decoder transform, transfer conversion, RGB integral or ordinal function.
        String denseControl = production.replace("if (isOrdinalAnchor(targetPixel))", "if (true)");
        int sparseProgram = createProgram(ShaderUtils.VERTEX_SHADER, production);
        int denseProgram = createProgram(ShaderUtils.VERTEX_SHADER, denseControl);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        SurfaceTexture sourceTexture = new SurfaceTexture(textures[0]);
        sourceTexture.setDefaultBufferSize(sourceWidth, sourceHeight);
        Surface sourceSurface = new Surface(sourceTexture);
        try {
            int[] pixels = new int[sourceWidth * sourceHeight];
            for (int y = 0; y < sourceHeight; y++) for (int x = 0; x < sourceWidth; x++) {
                int r = 32 + (x * 17 + y * 11) % 208;
                int g = 32 + (x * 7 + y * 23) % 208;
                int b = 32 + (x * 31 + y * 3) % 208;
                pixels[y * sourceWidth + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
            Bitmap bitmap = Bitmap.createBitmap(pixels, sourceWidth, sourceHeight,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = sourceSurface.lockCanvas(null);
            try { canvas.drawBitmap(bitmap, 0, 0, null); }
            finally { sourceSurface.unlockCanvasAndPost(canvas); bitmap.recycle(); }
            sourceTexture.updateTexImage();
            float[] transform = new float[16];
            sourceTexture.getTransformMatrix(transform);
            if (hdr) {
                // Also exercise a horizontal decoder transform. Anchors belong to model/FBO
                // coordinates, not to transformed source coordinates.
                transform[12] += transform[0];
                transform[13] += transform[1];
                transform[0] = -transform[0];
                transform[1] = -transform[1];
            }
            for (int i = 0; i < 2; i++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i + 1]);
                textureParameters(GLES20.GL_TEXTURE_2D);
                GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                        width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                        GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[i + 1], 0);
                assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE,
                        GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
            }
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDisable(GLES20.GL_DITHER);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            draw(sparseProgram, textures[0], sampler[0], fbos[0], transform,
                    width, height, sourceWidth, sourceHeight, hdr);
            draw(denseProgram, textures[0], sampler[0], fbos[1], transform,
                    width, height, sourceWidth, sourceHeight, hdr);
            ByteBuffer sparse = readPixels(fbos[0], width, height);
            ByteBuffer dense = readPixels(fbos[1], width, height);
            int anchorCount = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    if (sparse.get(offset + channel) != dense.get(offset + channel)) {
                        throw new AssertionError("RGB changed at " + x + "," + y
                                + " in " + width + "x" + height + " HDR=" + hdr);
                    }
                }
                boolean anchor = isDetectorAnchor(x, width) && isDetectorAnchor(y, height);
                if (anchor) {
                    anchorCount++;
                    assertEquals("Consumed ordinal changed at " + x + "," + y,
                            dense.get(offset + 3), sparse.get(offset + 3));
                } else {
                    assertEquals("Unused ordinal storage must remain zero", 0,
                            sparse.get(offset + 3) & 0xff);
                }
            }
            if (width == 672 && height == 384) assertEquals(9072, anchorCount);
            assertNoGlError("sparse/dense source ordinal comparison");
        } finally {
            sourceSurface.release();
            sourceTexture.release();
            GLES20.glDeleteProgram(sparseProgram);
            GLES20.glDeleteProgram(denseProgram);
            GLES30.glDeleteSamplers(1, sampler, 0);
            GLES20.glDeleteFramebuffers(2, fbos, 0);
            GLES20.glDeleteTextures(3, textures, 0);
        }
    }

    private static boolean isDetectorAnchor(int pixel, int size) {
        int local = pixel % 16;
        int validExtent = Math.min(16, size - (pixel / 16) * 16);
        return local == 0 || local == (validExtent - 1) / 2 || local == validExtent - 1;
    }

    @Test
    public void sourceExposureBeforeAreaResizePreservesOrdinalsAndModelRgb() {
        try (EglFixture ignored = EglFixture.create();
             ClientSbsGpuSceneCutDetector fixed = new ClientSbsGpuSceneCutDetector(WIDTH, HEIGHT);
             ClientSbsGpuSceneCutDetector old = new ClientSbsGpuSceneCutDetector(WIDTH, HEIGHT);
             ClientSbsGpuDepthProcessor depth = new ClientSbsGpuDepthProcessor(
                     WIDTH, HEIGHT, WIDTH / (float) HEIGHT, false)) {
            int[] textures = new int[3], fbos = new int[2], buffers = new int[3], sampler = new int[1];
            GLES20.glGenTextures(3, textures, 0);
            GLES20.glGenFramebuffers(2, fbos, 0);
            GLES30.glGenBuffers(3, buffers, 0);
            GLES30.glGenSamplers(1, sampler, 0);
            GLES30.glSamplerParameteri(sampler[0], GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES30.glSamplerParameteri(sampler[0], GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES30.glSamplerParameteri(sampler[0], GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES30.glSamplerParameteri(sampler[0], GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            int fixedProgram = createProgram(ShaderUtils.VERTEX_SHADER,
                    ClientSbsShaders.createModelInputFragment(true));
            // Control reproduces the former max-of-area-RGB descriptor with the same new detector.
            int oldProgram = createProgram(ShaderUtils.VERTEX_SHADER,
                    ClientSbsShaders.createModelInputFragment(true).replace(
                            "sourcePointOrdinal(sourceUv)", "max(color.r, max(color.g, color.b))"));
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            SurfaceTexture sourceTexture = new SurfaceTexture(textures[0]);
            sourceTexture.setDefaultBufferSize(SOURCE_WIDTH, SOURCE_HEIGHT);
            Surface sourceSurface = new Surface(sourceTexture);
            try {
                for (int i = 0; i < 2; i++) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i + 1]);
                    textureParameters(GLES20.GL_TEXTURE_2D);
                    GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                            WIDTH, HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i]);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                            GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[i + 1], 0);
                    assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE,
                            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
                    GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[i]);
                    GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,
                            WIDTH * HEIGHT * 3 * Float.BYTES, null, GLES30.GL_DYNAMIC_DRAW);
                }
                FloatBuffer raw = ByteBuffer.allocateDirect(WIDTH * HEIGHT * Float.BYTES)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++)
                    raw.put(0.2f + 0.3f * x / WIDTH);
                raw.flip();
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[2]);
                GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,
                        raw.remaining() * Float.BYTES, raw, GLES30.GL_DYNAMIC_DRAW);
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
                GLES20.glDisable(GLES20.GL_BLEND);
                GLES20.glDisable(GLES20.GL_DITHER);
                GLES20.glDisable(GLES20.GL_DEPTH_TEST);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

                for (int frame = 0; frame < 2; frame++) {
                    publishSource(sourceSurface, frame != 0);
                    sourceTexture.updateTexImage();
                    float[] transform = new float[16];
                    sourceTexture.getTransformMatrix(transform);
                    draw(fixedProgram, textures[0], sampler[0], fbos[0], transform);
                    draw(oldProgram, textures[0], sampler[0], fbos[1], transform);
                    assertModelRgbEqual(fbos[0], fbos[1]);
                    process(fixed, textures[1], buffers[0], frame + 1);
                    process(old, textures[2], buffers[1], frame + 1);
                    if (frame == 0) {
                        // Exercise real depth state publication and the actual shared commit path.
                        depth.processRendererOwned(buffers[2], 0, Float.BYTES, false);
                        fixed.commitAcceptedFrame(depth.getHistoryDecisionStateBufferId());
                        old.commitAcceptedFrame(depth.getHistoryDecisionStateBufferId());
                    } else {
                        int[] fixedRecord = readRecord(fixed), oldRecord = readRecord(old);
                        assertEquals("Point ordinals must not propose an exposure cut", 0, fixedRecord[0] & 1);
                        assertEquals("Supported pure exposure must retain its veto", 2, fixedRecord[0] & 2);
                        assertEquals("Point orderings cannot reverse under gamma", 0, fixedRecord[4]);
                        assertTrue("Control must reproduce false appearance authority", (oldRecord[0] & 1) != 0);
                        assertTrue("Control must cross the 15% structural gate", oldRecord[4] * 100 >= oldRecord[1] * 15);
                        fixed.discardPendingFrame();
                        old.discardPendingFrame();
                    }
                }
                assertNoGlError("source-grid exposure regression");
            } finally {
                sourceSurface.release();
                sourceTexture.release();
                GLES20.glDeleteProgram(fixedProgram);
                GLES20.glDeleteProgram(oldProgram);
                GLES30.glDeleteSamplers(1, sampler, 0);
                GLES30.glDeleteBuffers(3, buffers, 0);
                GLES20.glDeleteFramebuffers(2, fbos, 0);
                GLES20.glDeleteTextures(3, textures, 0);
            }
        }
    }

    private static void publishSource(Surface surface, boolean gamma) {
        int[] pixels = new int[SOURCE_WIDTH * SOURCE_HEIGHT];
        for (int y = 0; y < SOURCE_HEIGHT; y++) for (int x = 0; x < SOURCE_WIDTH; x++) {
            int macroX = (int) ((x + 0.5) * WIDTH / SOURCE_WIDTH / 16);
            int macroY = (int) ((y + 0.5) * HEIGHT / SOURCE_HEIGHT / 16);
            int code = ((macroX + macroY) & 1) == 0 ? 148 : (((x + y) & 1) == 0 ? 38 : 217);
            if (gamma) code = (int) Math.floor(code * code / 255.0 + 0.5);
            pixels[y * SOURCE_WIDTH + x] = 0xff000000 | (code << 16) | (code << 8) | code;
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = surface.lockCanvas(null);
        try { canvas.drawBitmap(bitmap, 0, 0, null); }
        finally { surface.unlockCanvasAndPost(canvas); bitmap.recycle(); }
    }

    private static void draw(int program, int texture, int sampler, int framebuffer, float[] transform) {
        draw(program, texture, sampler, framebuffer, transform,
                WIDTH, HEIGHT, SOURCE_WIDTH, SOURCE_HEIGHT, false);
    }

    private static void draw(int program, int texture, int sampler, int framebuffer, float[] transform,
                             int width, int height, int sourceWidth, int sourceHeight, boolean hdr) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        int position = GLES20.glGetAttribLocation(program, "a_Position");
        int uv = GLES20.glGetAttribLocation(program, "a_TexCoord");
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, floats(VERTICES));
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, floats(UV));
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_Texture"), 0);
        int ordinal = GLES20.glGetUniformLocation(program, "u_OrdinalTexture");
        if (ordinal >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES30.glBindSampler(1, sampler);
            GLES20.glUniform1i(ordinal, 1);
        }
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "u_TextureTransform"), 1, false, transform, 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_isHdr"), hdr ? 1 : 0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_sourceSize"), sourceWidth, sourceHeight);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_downsampleRatio"),
                sourceWidth / (float) width, sourceHeight / (float) height);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_sourceAspect"),
                (sourceWidth / (float) sourceHeight) / (width / (float) height));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(position);
        GLES20.glDisableVertexAttribArray(uv);
        GLES30.glBindSampler(1, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glFinish();
        assertNoGlError("production OES model-input draw");
    }

    private static void process(ClientSbsGpuSceneCutDetector detector, int texture, int tensor, long frame) {
        detector.processRendererOwnedAndPack(texture, tensor,
                detector.getSceneCutBufferId(), detector.getSceneCutByteOffset(),
                false, frame, 0, frame, frame * 16_000_000L);
        GLES20.glFinish();
    }

    private static int[] readRecord(ClientSbsGpuSceneCutDetector detector) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, detector.getSceneCutBufferId());
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,
                detector.getSceneCutByteOffset(), ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES,
                GLES30.GL_MAP_READ_BIT);
        assertTrue(mapped instanceof ByteBuffer);
        ByteBuffer bytes = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        int[] words = new int[8];
        for (int i = 0; i < words.length; i++) words[i] = bytes.getInt(i * Integer.BYTES);
        assertTrue(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        return words;
    }

    private static void assertModelRgbEqual(int first, int second) {
        ByteBuffer a = readPixels(first), b = readPixels(second);
        for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) for (int c = 0; c < 3; c++)
            assertEquals("Model RGB changed at pixel " + pixel + " channel " + c,
                    a.get(pixel * 4 + c), b.get(pixel * 4 + c));
    }

    private static ByteBuffer readPixels(int fbo) {
        return readPixels(fbo, WIDTH, HEIGHT);
    }

    private static ByteBuffer readPixels(int fbo, int width, int height) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static FloatBuffer floats(float[] values) {
        FloatBuffer out = ByteBuffer.allocateDirect(values.length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        out.put(values).flip(); return out;
    }

    private static void textureParameters(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static void assertNoGlError(String stage) {
        assertEquals(stage, GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    // EGL and shader-compiler helpers are appended from the existing contractive device fixture.
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
