package com.limelight.sbs;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;

/**
 * GLES 3.1 helpers shared by the Client SBS compute processors.
 *
 * <p>Every method issues exactly the GL calls of the per-processor copy it replaced, in the same
 * order, and must run on the thread that owns the current EGL context. Callers supply their own
 * message text so each processor keeps its established diagnostics.</p>
 */
public final class ClientSbsGlCompute {
    /** A bounded drain prevents a pathological driver from trapping the GL thread. */
    private static final int DEFAULT_ERROR_DRAIN_LIMIT = 16;

    private ClientSbsGlCompute() {
    }

    /**
     * Returns the current EGL context after proving it exposes GLES 3.1 or 3.2.
     * {@code requirementPrefix} precedes the reported version string in the failure message.
     */
    static EGLContext requireCurrentGles31Context(String requirementPrefix) {
        EGLContext currentContext = EGL14.eglGetCurrentContext();
        if (currentContext == null || currentContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("A current EGL context is required");
        }
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        if (version == null || (!version.contains("OpenGL ES 3.1")
                && !version.contains("OpenGL ES 3.2"))) {
            throw new IllegalStateException(requirementPrefix + version);
        }
        return currentContext;
    }

    /** Rejects a released owner or a call from any context other than {@code ownerContext}. */
    static void assertOwnerContext(boolean released, EGLContext ownerContext,
                                   String releasedMessage, String differentContextMessage) {
        if (released) {
            throw new IllegalStateException(releasedMessage);
        }
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current == null || current == EGL14.EGL_NO_CONTEXT || !ownerContext.equals(current)) {
            throw new IllegalStateException(differentContextMessage);
        }
    }

    static int createComputeProgram(String label, String source) {
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

    /** {@code missingPrefix} precedes the uniform name when the location is unavailable. */
    static int requiredUniform(int program, String name, String missingPrefix) {
        int location = GLES20.glGetUniformLocation(program, name);
        if (location < 0) {
            throw new IllegalStateException(missingPrefix + name);
        }
        return location;
    }

    /** Allocates immutable single-level 2D storage with clamped edges and one filter mode. */
    static int createTexture2D(int width, int height, int internalFormat, int filtering,
                               String failureMessage) {
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
            throw new IllegalStateException(failureMessage);
        }
        return texture[0];
    }

    /** Reads one integer state value through the caller's thread-confined scratch array. */
    static int getInteger(int name, int[] scratch) {
        scratch[0] = 0;
        GLES20.glGetIntegerv(name, scratch, 0);
        return scratch[0];
    }

    static void checkGlError(String stage) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(stage + " failed with GL error 0x"
                    + Integer.toHexString(error));
        }
    }

    /** Discards pending GL errors, stopping at the first clean query or after sixteen. */
    public static void drainGlErrors() {
        drainGlErrors(DEFAULT_ERROR_DRAIN_LIMIT);
    }

    /** Discards pending GL errors, stopping at the first clean query or after {@code limit}. */
    static void drainGlErrors(int limit) {
        for (int i = 0; i < limit && GLES20.glGetError() != GLES20.GL_NO_ERROR; i++) {
            // Intentionally empty.
        }
    }
}
