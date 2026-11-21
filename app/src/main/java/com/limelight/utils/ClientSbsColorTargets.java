package com.limelight.utils;

import android.opengl.GLES20;
import android.opengl.GLES30;

import com.limelight.LimeLog;
import com.limelight.sbs.ClientSbsGlCompute;

/**
 * The two full-resolution matched-color targets of one {@link Stereo3DRenderer}: one texture and
 * one framebuffer per color slot, all in one presentation format.
 *
 * <p>Threading: GL thread only, with the renderer's EGL context current. The exception is
 * {@link #forgetAfterContextLoss}, which issues no GL call and also runs from terminal teardown
 * after the context is gone.</p>
 */
final class ClientSbsColorTargets {
    static final int SLOT_COUNT = 2;

    enum Format {
        RGB10_A2(GLES30.GL_RGB10_A2, GLES20.GL_RGBA,
                GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, true),
        RGBA16F(GLES30.GL_RGBA16F, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, true),
        RGBA8(GLES30.GL_RGBA8, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, false);

        final int internalFormat;
        final int format;
        final int type;
        final boolean hdrPrecision;

        Format(int internalFormat, int format, int type, boolean hdrPrecision) {
            this.internalFormat = internalFormat;
            this.format = format;
            this.type = type;
            this.hdrPrecision = hdrPrecision;
        }
    }

    private final int[] textures = new int[SLOT_COUNT];
    private final int[] fbos = new int[SLOT_COUNT];
    private int width;
    private int height;
    private Format format = Format.RGBA8;

    /**
     * Allocates both slots at {@code requestedWidth x requestedHeight} (at least 1x1), trying the
     * formats this window can present from best to RGBA8 and releasing each failed attempt.
     * Names left from a lost context are forgotten, never deleted. Returns false when no format
     * completes; {@link #format()} is then RGBA8.
     */
    boolean allocate(int requestedWidth, int requestedHeight, boolean hdrWindowCapable) {
        width = Math.max(1, requestedWidth);
        height = Math.max(1, requestedHeight);
        // Names from a lost context are not valid deletion targets in this new context.
        forgetNames();

        boolean halfFloatRenderable = supportsHalfFloatColorBuffers();
        Format[] candidates;
        if (hdrWindowCapable && halfFloatRenderable) {
            candidates = new Format[] {Format.RGB10_A2, Format.RGBA16F, Format.RGBA8};
        } else if (hdrWindowCapable) {
            candidates = new Format[] {Format.RGB10_A2, Format.RGBA8};
        } else {
            candidates = new Format[] {Format.RGBA8};
        }

        for (Format candidate : candidates) {
            if (createSlots(candidate)) {
                format = candidate;
                return true;
            }
            release();
        }
        format = Format.RGBA8;
        return false;
    }

    /** Whether both slots already exist at exactly {@code w x h}. */
    boolean matches(int w, int h) {
        return width == w && height == h && textures[0] != 0 && fbos[0] != 0;
    }

    /** Deletes both slots' framebuffers and textures through the current context. */
    void release() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(fbos.length, fbos, 0);
        GLES20.glDeleteTextures(textures.length, textures, 0);
        forgetNames();
    }

    /** Drops names that belong to a context that is gone and returns to the RGBA8 default. */
    void forgetAfterContextLoss() {
        forgetNames();
        format = Format.RGBA8;
    }

    int texture(int slot) {
        return textures[slot];
    }

    int framebuffer(int slot) {
        return fbos[slot];
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    Format format() {
        return format;
    }

    /** Allocates one clamped, linearly filtered 2D texture of {@code format}. */
    static int createTexture(int width, int height, Format format) {
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        int textureId = names[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format.internalFormat,
                width, height, 0, format.format, format.type, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return textureId;
    }

    /** Whether the current context can render into half-float color attachments. */
    static boolean supportsHalfFloatColorBuffers() {
        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        return extensions != null && (extensions.contains("GL_EXT_color_buffer_half_float")
                || extensions.contains("GL_EXT_color_buffer_float"));
    }

    private boolean createSlots(Format candidate) {
        ClientSbsGlCompute.drainGlErrors();
        GLES20.glGenFramebuffers(fbos.length, fbos, 0);
        boolean complete = true;
        for (int slot = 0; slot < textures.length; slot++) {
            textures[slot] = createTexture(width, height, candidate);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[slot]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, textures[slot], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                    != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                complete = false;
            }
            complete &= fbos[slot] != 0 && textures[slot] != 0;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        int error = GLES20.glGetError();
        complete &= error == GLES20.GL_NO_ERROR;
        if (!complete) {
            LimeLog.warning("Client SBS " + candidate + " color targets unavailable (GL=0x"
                    + Integer.toHexString(error) + ")");
        }
        return complete;
    }

    private void forgetNames() {
        for (int slot = 0; slot < textures.length; slot++) {
            textures[slot] = 0;
            fbos[slot] = 0;
        }
    }
}
