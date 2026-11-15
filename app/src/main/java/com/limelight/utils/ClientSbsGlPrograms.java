package com.limelight.utils;

import android.opengl.GLES20;
import android.opengl.GLES31;

import com.limelight.LimeLog;

/**
 * Shader program construction and cached attribute/uniform locations for
 * {@link Stereo3DRenderer}. Every method and constructor issues GL calls and therefore needs the
 * renderer's EGL context current on the calling thread.
 */
final class ClientSbsGlPrograms {
    private ClientSbsGlPrograms() {
    }

    static int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.severe("Could not compile shader " + type + ":");
            LimeLog.severe(GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    static int createProgram(String vertex, String fragment) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        if (vertexShader == 0) return 0;
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader);
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            return 0;
        }

        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        GLES20.glDetachShader(program, vertexShader);
        GLES20.glDetachShader(program, fragmentShader);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            LimeLog.severe("Could not link program: ");
            LimeLog.severe(GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    static int createComputeProgram(String source) {
        int computeShader = loadShader(GLES31.GL_COMPUTE_SHADER, source);
        if (computeShader == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        if (program == 0) {
            GLES20.glDeleteShader(computeShader);
            return 0;
        }
        GLES20.glAttachShader(program, computeShader);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(computeShader);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            LimeLog.severe("Could not link client SBS compute program: "
                    + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    /** Program locations are context-local and immutable after link. Cache them once rather than
     * crossing the Java/JNI/driver boundary for every eye and every decoded frame. */
    static final class QuadProgramBindings {
        final int position;
        final int texCoord;
        final int isHdr;
        final int textureTransform;
        final int tonemapHdrToSdr;
        final int sourceAspect;
        /** Source pixels per model texel, per axis; drives the model-input box filter. */
        final int downsampleRatio;
        final int sourceSize;

        QuadProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            isHdr = GLES20.glGetUniformLocation(program, "u_isHdr");
            textureTransform = GLES20.glGetUniformLocation(program, "u_TextureTransform");
            tonemapHdrToSdr = GLES20.glGetUniformLocation(program, "u_tonemapHdrToSdr");
            sourceAspect = GLES20.glGetUniformLocation(program, "u_sourceAspect");
            downsampleRatio = GLES20.glGetUniformLocation(program, "u_downsampleRatio");
            sourceSize = GLES20.glGetUniformLocation(program, "u_sourceSize");
        }
    }

    static final class GpuPackProgramBindings {
        final int modelInputTexture;

        GpuPackProgramBindings(int program) {
            modelInputTexture = GLES20.glGetUniformLocation(program, "s_ModelInputTexture");
        }

        boolean isComplete() {
            return modelInputTexture >= 0;
        }
    }

    static final class ContractiveWarpMapProgramBindings {
        final int position;
        final int texCoord;
        final int parallaxTexture;

        ContractiveWarpMapProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            parallaxTexture = GLES20.glGetUniformLocation(program, "s_ParallaxTexture");
        }

        boolean isComplete() {
            return position >= 0 && texCoord >= 0 && parallaxTexture >= 0;
        }
    }

    static final class ContractiveWarpMapRefinementProgramBindings {
        final int position;
        final int texCoord;
        final int coarseWarpMapTexture;
        final int parallaxTexture;

        ContractiveWarpMapRefinementProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            coarseWarpMapTexture = GLES20.glGetUniformLocation(
                    program, "s_CoarseWarpMapTexture");
            parallaxTexture = GLES20.glGetUniformLocation(program, "s_ParallaxTexture");
        }

        boolean isComplete() {
            return position >= 0 && texCoord >= 0 && coarseWarpMapTexture >= 0
                    && parallaxTexture >= 0;
        }
    }

    static final class WarpedReprojectionProgramBindings {
        final int position;
        final int texCoord;
        final int colorTexture;
        final int warpMapTexture;

        WarpedReprojectionProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            colorTexture = GLES20.glGetUniformLocation(program, "s_ColorTexture");
            warpMapTexture = GLES20.glGetUniformLocation(program, "s_WarpMapTexture");
        }

        boolean isComplete() {
            return position >= 0 && texCoord >= 0 && colorTexture >= 0
                    && warpMapTexture >= 0;
        }
    }
}
