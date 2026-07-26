package com.limelight.sbs;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;

import com.limelight.LimeLog;

/**
 * GPU-only hard color-cut detector for the Client-SBS model-input texture.
 *
 * <p>The input is the renderer's model-sized SDR {@link GLES20#GL_TEXTURE_2D}. Each compute
 * workgroup reduces a 16x16 tile to average integer Rec.709 luma plus the median max-RGB value of
 * a fixed 3x3 sample lattice. Partial tiles at the right and bottom edges contain only in-bounds
 * samples. A second pass compares the resulting grid with persistent GPU history, and a final pass
 * combines spatially broad luma change with reliable local ordinal-structure change. The ordinal
 * cue rejects a shared global monotone exposure transform, including clamp-created ties, while
 * publishing high-recall structural evidence for the depth processor.</p>
 *
 * <p>No texture, buffer, or flag is read by the CPU.
 * {@link #processRendererOwnedAndPack(int, int)} packs the model tensor while reducing the same
 * texels for scene-cut detection, then publishes one uint32 flag. The two-argument overload uses
 * the SSBO returned by {@link #getSceneCutBufferId()}; the renderer overload writes a stable
 * caller-owned word for the matching tensor slot. The renderer must finish the transaction with
 * exactly one call
 * to {@link #commitAcceptedFrame()} or {@link #discardPendingFrame()}. Pass the buffer and
 * {@link #SCENE_CUT_BYTE_OFFSET} directly to
 * {@link ClientSbsGpuDepthProcessor#processRendererOwnedWithGpuSceneCut(int, int, int, int, int)}.
 * The detector and depth processor must execute in order in the same GL context. Different
 * in-flight frames must use different output words.</p>
 *
 * <p>This is a renderer-owned hot path: it does not query or restore prior GL state. On return,
 * program, SSBO bindings 0/1/2, image bindings 0/1, and texture unit 0's 2D binding are zero;
 * texture unit 0 is active. No other binding is changed.</p>
 */
public final class ClientSbsGpuSceneCutDetector implements AutoCloseable {
    public static final int SCENE_CUT_BYTE_OFFSET = 0;

    private static final int BLOCK_SIZE = 16;
    private static final int STATS_SSBO_BINDING = 0;
    private static final int OUTPUT_SSBO_BINDING = 1;
    private static final int INPUT_TENSOR_SSBO_BINDING = 2;
    private static final int PREVIOUS_LUMA_IMAGE_BINDING = 0;
    private static final int CURRENT_LUMA_IMAGE_BINDING = 1;
    private static final int STATS_UINT_COUNT = 8 + 16 + 16;
    private static final int STATS_BYTES = STATS_UINT_COUNT * Integer.BYTES;
    private static final int OUTPUT_BYTES = Integer.BYTES;

    private final EGLContext ownerContext;
    private final int inputWidth;
    private final int inputHeight;
    private final int blockGridWidth;
    private final int blockGridHeight;
    private final int[] lumaTextures = new int[2];
    private final Result result = new Result();
    private final FrameTransaction frameTransaction = new FrameTransaction();

    private int resetProgram;
    private int packAndDownsampleProgram;
    private int compareProgram;
    private int resolveProgram;
    private int commitProgram;
    private int statsBuffer;
    private int outputBuffer;
    private int packAndDownsampleColorUniform;
    private int compareBlockGridUniform;
    private int compareHistoryValidUniform;
    private int resolveHistoryValidUniform;
    private int resetOutputWordOffsetUniform;
    private int resolveOutputWordOffsetUniform;
    private int validatedOutputBuffer;
    private int validatedOutputBufferSize;
    /** Preserve stage-specific diagnostics on the first frame after reset, then batch queries. */
    private boolean validateDispatchesIndividually = true;
    private boolean released;

    public ClientSbsGpuSceneCutDetector(int inputWidth, int inputHeight) {
        this.inputWidth = requirePositiveDimension(inputWidth, "inputWidth");
        this.inputHeight = requirePositiveDimension(inputHeight, "inputHeight");
        blockGridWidth = blocksForPixels(inputWidth);
        blockGridHeight = blocksForPixels(inputHeight);

        EGLContext currentContext = EGL14.eglGetCurrentContext();
        if (currentContext == null || currentContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("A current EGL context is required");
        }
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        if (version == null || (!version.contains("OpenGL ES 3.1")
                && !version.contains("OpenGL ES 3.2"))) {
            throw new IllegalStateException("Client SBS GPU scene cuts require GLES 3.1: "
                    + version);
        }
        ownerContext = currentContext;

        try {
            initializePrograms();
            initializeStorage();
            initializeUniforms();
            reset();
            LimeLog.info("Client SBS GPU color-cut detector enabled: " + inputWidth + "x"
                    + inputHeight + " -> " + blockGridWidth + "x" + blockGridHeight);
        } catch (RuntimeException error) {
            releaseGlResourcesUnchecked();
            released = true;
            throw error;
        }
    }

    /**
     * Enqueues detection for one renderer-owned SDR model-input texture without waiting.
     *
     * <p>The first accepted frame after construction or {@link #reset()} always publishes zero.
     * Subsequent calls compare against the immediately preceding accepted frame. A successful
     * call starts a transaction that must be finished with exactly one call to
     * {@link #commitAcceptedFrame()} or {@link #discardPendingFrame()} before another frame is
     * processed. The returned object is reused and must not be retained as a per-frame
     * snapshot.</p>
     */
    public Result processRendererOwnedAndPack(int modelInputTexture, int packedFloatSsbo) {
        return processRendererOwnedAndPack(modelInputTexture, packedFloatSsbo,
                outputBuffer, SCENE_CUT_BYTE_OFFSET);
    }

    /**
     * Enqueues detection while publishing the cut word into caller-owned per-frame storage.
     * Keeping one word per tensor slot lets a later capture advance detector history without
     * overwriting the cut evidence paired with a completed inference result.
     */
    public Result processRendererOwnedAndPack(int modelInputTexture, int packedFloatSsbo,
                                               int sceneCutOutputSsbo,
                                               int sceneCutOutputByteOffset) {
        assertOwnerContext();
        if (modelInputTexture == 0) {
            throw new IllegalArgumentException("modelInputTexture must be a valid GL texture");
        }
        if (packedFloatSsbo == 0) {
            throw new IllegalArgumentException("packedFloatSsbo must be a valid GL buffer");
        }
        if (sceneCutOutputSsbo == 0) {
            throw new IllegalArgumentException(
                    "sceneCutOutputSsbo must be a valid GL buffer");
        }
        if (sceneCutOutputByteOffset < 0 || (sceneCutOutputByteOffset & 3) != 0) {
            throw new IllegalArgumentException(
                    "sceneCutOutputByteOffset must be nonnegative and word-aligned");
        }
        validateOutputBuffer(sceneCutOutputSsbo, sceneCutOutputByteOffset);
        int outputWordOffset = sceneCutOutputByteOffset / Integer.BYTES;

        int currentLumaIndex = frameTransaction.beginPendingFrame();
        boolean submitted = false;
        try {
            bindSsbo(STATS_SSBO_BINDING, statsBuffer);
            bindSsbo(OUTPUT_SSBO_BINDING, sceneCutOutputSsbo);
            bindSsbo(INPUT_TENSOR_SSBO_BINDING, packedFloatSsbo);

            GLES20.glUseProgram(resetProgram);
            GLES30.glUniform1ui(resetOutputWordOffsetUniform, outputWordOffset);
            dispatch(1, 1, 1, "reset color-cut stats");
            shaderStorageBarrier();

            GLES20.glUseProgram(packAndDownsampleProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, modelInputTexture);
            GLES20.glUniform1i(packAndDownsampleColorUniform, 0);
            GLES31.glBindImageTexture(CURRENT_LUMA_IMAGE_BINDING,
                    lumaTextures[currentLumaIndex], 0, false, 0,
                    GLES31.GL_WRITE_ONLY, GLES30.GL_R32UI);
            dispatch(blockGridWidth, blockGridHeight, 1,
                    "pack model tensor and downsample color-cut luma");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            GLES20.glUseProgram(compareProgram);
            GLES20.glUniform2i(compareBlockGridUniform,
                    blockGridWidth, blockGridHeight);
            GLES20.glUniform1i(compareHistoryValidUniform,
                    frameTransaction.hasHistory() ? 1 : 0);
            GLES31.glBindImageTexture(PREVIOUS_LUMA_IMAGE_BINDING,
                    lumaTextures[frameTransaction.getPreviousLumaIndex()], 0, false, 0,
                    GLES31.GL_READ_ONLY, GLES30.GL_R32UI);
            GLES31.glBindImageTexture(CURRENT_LUMA_IMAGE_BINDING,
                    lumaTextures[currentLumaIndex], 0, false, 0,
                    GLES31.GL_READ_ONLY, GLES30.GL_R32UI);
            dispatch(workgroupsForItems(blockGridWidth), workgroupsForItems(blockGridHeight), 1,
                    "compare color-cut luma");
            shaderStorageBarrier();

            GLES20.glUseProgram(resolveProgram);
            GLES20.glUniform1i(resolveHistoryValidUniform,
                    frameTransaction.hasHistory() ? 1 : 0);
            GLES30.glUniform1ui(resolveOutputWordOffsetUniform, outputWordOffset);
            dispatch(1, 1, 1, "resolve color cut");
            // Makes the published word visible to the following depth-processor dispatch.
            shaderStorageBarrier();
            // Avoid four Java/driver glGetError round trips on every inferred frame. The first
            // frame still identifies the exact failing dispatch; steady state checks once here.
            checkGlError("color-cut pack/compare pipeline");
            validateDispatchesIndividually = false;
            submitted = true;
        } finally {
            unbindRendererOwnedState();
            if (!submitted) {
                frameTransaction.discardPendingFrame();
            }
        }

        return result;
    }

    /**
     * Commits the pending frame as temporal history after its inference task is accepted.
     * This method does not wait for the GPU.
     */
    public void commitAcceptedFrame() {
        assertOwnerContext();
        frameTransaction.requirePendingFrame();
        try {
            bindSsbo(STATS_SSBO_BINDING, statsBuffer);
            GLES20.glUseProgram(commitProgram);
            dispatch(1, 1, 1, "commit accepted color-cut history");
            shaderStorageBarrier();
            checkGlError("commit accepted color-cut history");
        } finally {
            unbindRendererOwnedState();
        }
        frameTransaction.commitAcceptedFrame();
        result.frameSequence = frameTransaction.getFrameSequence();
    }

    /** Discards a resolved frame that was not transferred to the inference queue. */
    public void discardPendingFrame() {
        assertOwnerContext();
        frameTransaction.discardPendingFrame();
        result.frameSequence = frameTransaction.getFrameSequence();
    }

    /**
     * Invalidates temporal color history and enqueues an immediate zero output flag.
     * This method does not wait for the GPU.
     */
    public void reset() {
        assertOwnerContext();
        validateDispatchesIndividually = true;
        frameTransaction.reset();
        result.frameSequence = 0L;
        try {
            bindSsbo(STATS_SSBO_BINDING, statsBuffer);
            bindSsbo(OUTPUT_SSBO_BINDING, outputBuffer);
            GLES20.glUseProgram(resetProgram);
            GLES30.glUniform1ui(resetOutputWordOffsetUniform, 0);
            dispatch(1, 1, 1, "reset color-cut history");
            shaderStorageBarrier();
        } finally {
            unbindRendererOwnedState();
        }
    }

    /** Returns the dedicated SSBO containing the uint32 scene-cut word. */
    public int getSceneCutBufferId() {
        assertOwnerContext();
        return outputBuffer;
    }

    /** Returns zero; provided to make depth-processor integration self-documenting. */
    public int getSceneCutByteOffset() {
        assertOwnerContext();
        return SCENE_CUT_BYTE_OFFSET;
    }

    /** Deletes resources. The owning EGL context must be current. */
    @Override
    public void close() {
        if (released) {
            return;
        }
        assertOwnerContext();
        releaseGlResourcesUnchecked();
        released = true;
    }

    /**
     * Drops Java handles after EGL context loss without deleting names in a replacement context.
     */
    public void abandonAfterContextLoss() {
        if (released) {
            return;
        }
        clearHandles();
        released = true;
    }

    private void initializePrograms() {
        resetProgram = createComputeProgram("reset color-cut stats",
                ClientSbsGpuSceneCutShaders.RESET);
        packAndDownsampleProgram = createComputeProgram(
                "pack model tensor and downsample color-cut luma",
                ClientSbsGpuSceneCutShaders.createPackAndDownsampleLuma(
                        inputWidth, inputHeight));
        compareProgram = createComputeProgram("compare color-cut luma",
                ClientSbsGpuSceneCutShaders.COMPARE);
        resolveProgram = createComputeProgram("resolve color cut",
                ClientSbsGpuSceneCutShaders.RESOLVE);
        commitProgram = createComputeProgram("commit accepted color-cut history",
                ClientSbsGpuSceneCutShaders.COMMIT);
    }

    private void initializeStorage() {
        statsBuffer = createBuffer(STATS_BYTES);
        outputBuffer = createBuffer(OUTPUT_BYTES);
        GLES20.glGenTextures(lumaTextures.length, lumaTextures, 0);
        for (int texture : lumaTextures) {
            if (texture == 0) {
                throw new IllegalStateException("Unable to allocate client SBS color-cut texture");
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_R32UI,
                    blockGridWidth, blockGridHeight);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        checkGlError("create GPU color-cut storage");
    }

    private void initializeUniforms() {
        packAndDownsampleColorUniform = requiredUniform(
                packAndDownsampleProgram, "uCurrentColor");
        compareBlockGridUniform = requiredUniform(compareProgram, "uBlockGrid");
        compareHistoryValidUniform = requiredUniform(compareProgram, "uHistoryValid");
        resolveHistoryValidUniform = requiredUniform(resolveProgram, "uHistoryValid");
        resetOutputWordOffsetUniform = requiredUniform(resetProgram, "uOutputWordOffset");
        resolveOutputWordOffsetUniform = requiredUniform(
                resolveProgram, "uOutputWordOffset");
    }

    private void validateOutputBuffer(int buffer, int byteOffset) {
        if (validatedOutputBuffer != buffer) {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
            int[] size = new int[1];
            GLES30.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER,
                    GLES20.GL_BUFFER_SIZE, size, 0);
            checkGlError("inspect color-cut output SSBO");
            validatedOutputBuffer = buffer;
            validatedOutputBufferSize = size[0];
        }
        long required = (long) byteOffset + Integer.BYTES;
        if (required > validatedOutputBufferSize) {
            throw new IllegalArgumentException("Color-cut output SSBO is "
                    + validatedOutputBufferSize + " bytes; word requires " + required);
        }
    }

    private static int createBuffer(int bytes) {
        int[] buffer = new int[1];
        GLES30.glGenBuffers(1, buffer, 0);
        if (buffer[0] == 0) {
            throw new IllegalStateException("Unable to allocate client SBS color-cut buffer");
        }
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0]);
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null,
                GLES30.GL_DYNAMIC_DRAW);
        return buffer[0];
    }

    private static int createComputeProgram(String label, String source) {
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

    private static int requiredUniform(int program, String name) {
        int location = GLES20.glGetUniformLocation(program, name);
        if (location < 0) {
            throw new IllegalStateException("Required color-cut uniform was optimized out: "
                    + name);
        }
        return location;
    }

    private static void bindSsbo(int binding, int buffer) {
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, buffer);
    }

    private void dispatch(int groupsX, int groupsY, int groupsZ, String stage) {
        GLES31.glDispatchCompute(groupsX, groupsY, groupsZ);
        if (validateDispatchesIndividually) {
            checkGlError(stage);
        }
    }

    static int blocksForPixels(int pixels) {
        return ceilDivideByBlockSize(pixels, "pixels");
    }

    static int workgroupsForItems(int items) {
        return ceilDivideByBlockSize(items, "items");
    }

    private static int ceilDivideByBlockSize(int value, String name) {
        requirePositiveDimension(value, name);
        return 1 + (value - 1) / BLOCK_SIZE;
    }

    private static int requirePositiveDimension(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
        return value;
    }

    private static void shaderStorageBarrier() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    private static void unbindRendererOwnedState() {
        GLES20.glUseProgram(0);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, STATS_SSBO_BINDING, 0);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, OUTPUT_SSBO_BINDING, 0);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, INPUT_TENSOR_SSBO_BINDING, 0);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        GLES31.glBindImageTexture(PREVIOUS_LUMA_IMAGE_BINDING, 0, 0, false, 0,
                GLES31.GL_READ_ONLY, GLES30.GL_R32UI);
        GLES31.glBindImageTexture(CURRENT_LUMA_IMAGE_BINDING, 0, 0, false, 0,
                GLES31.GL_READ_ONLY, GLES30.GL_R32UI);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void assertOwnerContext() {
        if (released) {
            throw new IllegalStateException("Client SBS GPU color-cut detector is released");
        }
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current == null || current == EGL14.EGL_NO_CONTEXT || !ownerContext.equals(current)) {
            throw new IllegalStateException(
                    "Client SBS GPU color-cut detector used from a different EGL context");
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
        int[] programs = {
                resetProgram,
                packAndDownsampleProgram,
                compareProgram,
                resolveProgram,
                commitProgram
        };
        for (int program : programs) {
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
        }
        int[] buffers = {statsBuffer, outputBuffer};
        GLES30.glDeleteBuffers(buffers.length, buffers, 0);
        GLES20.glDeleteTextures(lumaTextures.length, lumaTextures, 0);
        clearHandles();
    }

    private void clearHandles() {
        resetProgram = 0;
        packAndDownsampleProgram = 0;
        compareProgram = 0;
        resolveProgram = 0;
        commitProgram = 0;
        statsBuffer = 0;
        outputBuffer = 0;
        validatedOutputBuffer = 0;
        validatedOutputBufferSize = 0;
        lumaTextures[0] = 0;
        lumaTextures[1] = 0;
        frameTransaction.reset();
        result.frameSequence = 0L;
    }

    /** Reused view of the most recently accepted detection. */
    public final class Result {
        private long frameSequence;

        private Result() {
        }

        public int getSceneCutBufferId() {
            return ClientSbsGpuSceneCutDetector.this.getSceneCutBufferId();
        }

        public int getSceneCutByteOffset() {
            return SCENE_CUT_BYTE_OFFSET;
        }

        public long getFrameSequence() {
            return frameSequence;
        }
    }

    /** CPU-side transaction state, kept independent from GLES so its invariants are unit-testable. */
    static final class FrameTransaction {
        private static final int NO_PENDING_LUMA_INDEX = -1;

        private int previousLumaIndex;
        private int pendingLumaIndex = NO_PENDING_LUMA_INDEX;
        private long frameSequence;
        private boolean historyValid;

        int beginPendingFrame() {
            requireNoPendingFrame();
            pendingLumaIndex = 1 - previousLumaIndex;
            return pendingLumaIndex;
        }

        void commitAcceptedFrame() {
            requirePendingFrame();
            previousLumaIndex = pendingLumaIndex;
            pendingLumaIndex = NO_PENDING_LUMA_INDEX;
            historyValid = true;
            frameSequence++;
        }

        void discardPendingFrame() {
            requirePendingFrame();
            pendingLumaIndex = NO_PENDING_LUMA_INDEX;
        }

        void reset() {
            previousLumaIndex = 0;
            pendingLumaIndex = NO_PENDING_LUMA_INDEX;
            frameSequence = 0L;
            historyValid = false;
        }

        boolean hasHistory() {
            return historyValid;
        }

        boolean hasPendingFrame() {
            return pendingLumaIndex != NO_PENDING_LUMA_INDEX;
        }

        int getPreviousLumaIndex() {
            return previousLumaIndex;
        }

        long getFrameSequence() {
            return frameSequence;
        }

        void requirePendingFrame() {
            if (!hasPendingFrame()) {
                throw new IllegalStateException("No color-cut frame is pending");
            }
        }

        private void requireNoPendingFrame() {
            if (hasPendingFrame()) {
                throw new IllegalStateException("A color-cut frame is already pending");
            }
        }
    }
}
