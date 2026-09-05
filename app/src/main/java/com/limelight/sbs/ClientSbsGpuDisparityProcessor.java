package com.limelight.sbs;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;

import com.limelight.LimeLog;

/**
 * Builds a host-style contractive signed-parallax field from the calibrated Client SBS profile.
 *
 * <p>Original ZipDepth uses a separately qualified raw scale per graph. After that one
 * model-specific calibration, this follows Host SBS V2 directly: shot-latched arithmetic-mean
 * camera coordinate, the fixed asymmetric curve and pop, a fourth-root pointwise container,
 * vertical upper/lower Lipschitz envelopes, a horizontal least majorant, and one unique inverse.
 * P2/P98 normalization remains upstream only for scene-cut analysis.</p>
 *
 * <p>Each line is owned by one invocation and scanned serially. The largest production field is
 * only 928 by 384, so this avoids the host's Q30/chunk machinery while preserving the exact float
 * recurrences without a workgroup-boundary discontinuity. Four small compute dispatches replace a
 * per-pixel multi-root probe in the preferred renderer path.</p>
 */
public final class ClientSbsGpuDisparityProcessor implements AutoCloseable {
    private static final int IMAGE_BINDING = 0;
    private static final int LOCAL_SIZE = 32;

    private final int width;
    private final int height;
    private final float inverseRawCoordinateScale;
    private final EGLContext ownerContext;
    private final int[] integerScratch = new int[1];

    private int verticalForwardProgram;
    private int verticalFinishProgram;
    private int horizontalForwardProgram;
    private int horizontalFinishProgram;
    private int envelopeTexture;
    private int verticalTexture;
    private int finalTexture;
    private int verticalForwardDepthUniform;
    private int verticalForwardProfileUniform;
    private int verticalForwardInverseRawCoordinateScaleUniform;
    private int verticalFinishEnvelopeUniform;
    private int horizontalForwardVerticalUniform;
    private int horizontalFinishVerticalUniform;
    private int horizontalFinishEnvelopeUniform;
    private boolean validateDispatches = true;
    private boolean released;

    public ClientSbsGpuDisparityProcessor(int width, int height, float rawCoordinateScale) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Disparity field dimensions must be positive");
        }
        if (!Float.isFinite(rawCoordinateScale) || rawCoordinateScale <= 0.0f) {
            throw new IllegalArgumentException(
                    "Raw coordinate scale must be finite and positive");
        }
        EGLContext currentContext = EGL14.eglGetCurrentContext();
        if (currentContext == null || currentContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("A current EGL context is required");
        }
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        if (version == null || (!version.contains("OpenGL ES 3.1")
                && !version.contains("OpenGL ES 3.2"))) {
            throw new IllegalStateException(
                    "Contractive Client SBS disparity requires GLES 3.1: " + version);
        }
        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        if (extensions == null || !extensions.contains("GL_OES_texture_float_linear")) {
            throw new IllegalStateException(
                    "Contractive Client SBS disparity requires linear R32F sampling");
        }

        this.width = width;
        this.height = height;
        inverseRawCoordinateScale = 1.0f / rawCoordinateScale;
        ownerContext = currentContext;
        try {
            initializePrograms();
            initializeStorage();
            initializeUniforms();
            LimeLog.info("Client SBS contractive disparity: R32F " + width + "x" + height
                    + " rawScale=" + rawCoordinateScale
                    + ", vertical=0.75/0.25@2/W horizontal=majorant@0.5/W");
        } catch (RuntimeException error) {
            releaseGlResourcesUnchecked();
            released = true;
            throw error;
        }
    }

    /**
     * Submits the complete conditioner and returns its linearly sampled R32F output texture.
     * Commands remain asynchronous and ordered in the caller's current renderer context.
     */
    public int process(int depthTexture, int profileTexture, int sourceWidth, int sourceHeight) {
        assertOwnerContext();
        if (depthTexture == 0 || profileTexture == 0) {
            throw new IllegalArgumentException("Depth and profile textures must be valid");
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("Source dimensions must be positive");
        }

        if (validateDispatches) {
            drainGlErrors();
        }
        try {
            GLES20.glUseProgram(verticalForwardProgram);
            bindTexture(0, depthTexture, verticalForwardDepthUniform);
            bindTexture(1, profileTexture, verticalForwardProfileUniform);
            GLES20.glUniform1f(verticalForwardInverseRawCoordinateScaleUniform,
                    inverseRawCoordinateScale);
            bindWriteImage(envelopeTexture, GLES30.GL_RGBA32F);
            dispatch(groups(width), "vertical forward envelope");
            imageToTextureBarrier();

            GLES20.glUseProgram(verticalFinishProgram);
            bindTexture(0, envelopeTexture, verticalFinishEnvelopeUniform);
            bindWriteImage(verticalTexture, GLES30.GL_R32F);
            dispatch(groups(width), "vertical reverse envelope");
            imageToTextureBarrier();

            GLES20.glUseProgram(horizontalForwardProgram);
            bindTexture(0, verticalTexture, horizontalForwardVerticalUniform);
            bindWriteImage(envelopeTexture, GLES30.GL_RGBA32F);
            dispatch(groups(height), "horizontal forward majorant");
            imageToTextureBarrier();

            GLES20.glUseProgram(horizontalFinishProgram);
            bindTexture(0, verticalTexture, horizontalFinishVerticalUniform);
            bindTexture(1, envelopeTexture, horizontalFinishEnvelopeUniform);
            bindWriteImage(finalTexture, GLES30.GL_R32F);
            dispatch(groups(height), "horizontal reverse majorant");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
            checkGlError("contractive disparity publication");
            validateDispatches = false;
            return finalTexture;
        } finally {
            GLES31.glBindImageTexture(IMAGE_BINDING, 0, 0, false, 0,
                    GLES31.GL_READ_ONLY, GLES30.GL_R32F);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
        }
    }

    @Override
    public void close() {
        if (released) {
            return;
        }
        assertOwnerContext();
        releaseGlResourcesUnchecked();
        released = true;
    }

    /** Drops names after EGL context loss, where deleting through the replacement is invalid. */
    public void abandonAfterContextLoss() {
        if (released) {
            return;
        }
        clearHandles();
        released = true;
    }

    private void initializePrograms() {
        verticalForwardProgram = createComputeProgram("vertical forward envelope",
                ClientSbsGpuDisparityShaders.verticalForward(width, height));
        verticalFinishProgram = createComputeProgram("vertical reverse envelope",
                ClientSbsGpuDisparityShaders.verticalFinish(width, height));
        horizontalForwardProgram = createComputeProgram("horizontal forward majorant",
                ClientSbsGpuDisparityShaders.horizontalForward(width, height));
        horizontalFinishProgram = createComputeProgram("horizontal reverse majorant",
                ClientSbsGpuDisparityShaders.horizontalFinish(width, height));
    }

    private void initializeStorage() {
        int maxTextureSize = getInteger(GLES20.GL_MAX_TEXTURE_SIZE);
        if (width > maxTextureSize || height > maxTextureSize) {
            throw new IllegalStateException("Disparity field " + width + "x" + height
                    + " exceeds GL_MAX_TEXTURE_SIZE " + maxTextureSize);
        }
        envelopeTexture = createTexture(GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
        verticalTexture = createTexture(GLES30.GL_R32F, GLES20.GL_NEAREST);
        finalTexture = createTexture(GLES30.GL_R32F, GLES20.GL_LINEAR);
        checkGlError("create contractive disparity storage");
    }

    private void initializeUniforms() {
        verticalForwardDepthUniform = requiredUniform(
                verticalForwardProgram, "uDepthTexture");
        verticalForwardProfileUniform = requiredUniform(
                verticalForwardProgram, "uProfileTexture");
        verticalForwardInverseRawCoordinateScaleUniform = requiredUniform(
                verticalForwardProgram, "uInverseRawCoordinateScale");
        verticalFinishEnvelopeUniform = requiredUniform(
                verticalFinishProgram, "uEnvelopeScratch");
        horizontalForwardVerticalUniform = requiredUniform(
                horizontalForwardProgram, "uVerticalConditioned");
        horizontalFinishVerticalUniform = requiredUniform(
                horizontalFinishProgram, "uVerticalConditioned");
        horizontalFinishEnvelopeUniform = requiredUniform(
                horizontalFinishProgram, "uEnvelopeScratch");
    }

    private int createTexture(int internalFormat, int filtering) {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, internalFormat, width, height);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filtering);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filtering);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        if (texture[0] == 0) {
            throw new IllegalStateException("Unable to allocate contractive disparity texture");
        }
        return texture[0];
    }

    private int createComputeProgram(String label, String source) {
        int shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException(label + " shader compilation failed: " + log);
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(shader);
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException(label + " program link failed: " + log);
        }
        return program;
    }

    private void dispatch(int groups, String stage) {
        GLES31.glDispatchCompute(groups, 1, 1);
        if (validateDispatches) {
            checkGlError(stage);
        }
    }

    private static int groups(int length) {
        return (length + LOCAL_SIZE - 1) / LOCAL_SIZE;
    }

    private static void bindTexture(int unit, int texture, int uniform) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(uniform, unit);
    }

    private static void bindWriteImage(int texture, int format) {
        GLES31.glBindImageTexture(IMAGE_BINDING, texture, 0, false, 0,
                GLES31.GL_WRITE_ONLY, format);
    }

    private static void imageToTextureBarrier() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    private static int requiredUniform(int program, String name) {
        int location = GLES20.glGetUniformLocation(program, name);
        if (location < 0) {
            throw new IllegalStateException("Required disparity uniform was optimized out: "
                    + name);
        }
        return location;
    }

    private int getInteger(int name) {
        integerScratch[0] = 0;
        GLES20.glGetIntegerv(name, integerScratch, 0);
        return integerScratch[0];
    }

    private void assertOwnerContext() {
        if (released) {
            throw new IllegalStateException("Client SBS disparity processor is released");
        }
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current == null || current == EGL14.EGL_NO_CONTEXT || !ownerContext.equals(current)) {
            throw new IllegalStateException(
                    "Client SBS disparity processor used from a different EGL context");
        }
    }

    private static void drainGlErrors() {
        for (int i = 0; i < 16 && GLES20.glGetError() != GLES20.GL_NO_ERROR; i++) {
            // Intentionally empty.
        }
    }

    private static void checkGlError(String stage) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(stage + " failed with GL error 0x"
                    + Integer.toHexString(error));
        }
    }

    private void releaseGlResourcesUnchecked() {
        int[] programs = {verticalForwardProgram, verticalFinishProgram,
                horizontalForwardProgram, horizontalFinishProgram};
        for (int program : programs) {
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
        }
        int[] textures = {envelopeTexture, verticalTexture, finalTexture};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        clearHandles();
    }

    private void clearHandles() {
        verticalForwardProgram = 0;
        verticalFinishProgram = 0;
        horizontalForwardProgram = 0;
        horizontalFinishProgram = 0;
        envelopeTexture = 0;
        verticalTexture = 0;
        finalTexture = 0;
    }
}
