package com.limelight.sbs;

import static com.limelight.sbs.ClientSbsGlCompute.checkGlError;
import static com.limelight.sbs.ClientSbsGlCompute.createComputeProgram;

import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;

/**
 * GPU-only near-identical reuse classifier for the Client-SBS model-input texture.
 *
 * <p>The input is the renderer's model-sized SDR {@link GLES20#GL_TEXTURE_2D}. One compute pass
 * packs the model tensor (unless native packs lazily) and reduces the same clamped RGB texels
 * into per-16x16-tile change evidence against the retained input of the last accepted real
 * inference. A second pass resolves that evidence and the owner identity into a tiny
 * authenticated reuse record. Partial tiles at the right and bottom edges contain only in-bounds
 * samples.</p>
 *
 * <p>No frame-sized texture or buffer is read by the CPU. The native worker reads only the
 * matching 32-byte reuse record after the input fence. The renderer must finish each transaction
 * with exactly one call to {@link #commitAcceptedFrame(int)} or {@link #discardPendingFrame()}.
 * The commit consumes the depth processor's frame-state word, so the classifier and depth
 * processor must execute in order in the same GL context.</p>
 *
 * <p>This is a renderer-owned hot path: it does not query or restore prior GL state. On return,
 * program, SSBO bindings 0/1/2/3, image binding 0, and texture unit 0's 2D binding are zero;
 * texture unit 0 is active. No other binding is changed.</p>
 */
public final class ClientSbsGpuNearReuseClassifier implements AutoCloseable {
    public static final int NEAR_IDENTICAL_DECISION_SLOT_COUNT = 2;

    /** Pixels per evidence tile edge and invocations per compute workgroup edge. */
    static final int TILE_SIZE = ClientSbsNearIdenticalPolicy.TILE_SIZE;
    private static final int OWNER_SSBO_BINDING = 0;
    private static final int DECISION_SSBO_BINDING = 1;
    private static final int PROCESSOR_STATE_SSBO_BINDING = 1;
    private static final int INPUT_TENSOR_SSBO_BINDING = 2;
    private static final int PREVIOUS_INPUT_TENSOR_SSBO_BINDING = 3;
    private static final int TILE_EVIDENCE_IMAGE_BINDING = 0;
    // std430 layout of the NearReuseOwner block in ClientSbsGpuNearReuseShaders: two uvec2
    // (8-byte aligned) followed by one uint; the block size rounds up to its 8-byte alignment.
    static final int OWNER_FRAME_SEQUENCE_BYTE_OFFSET = 0;
    static final int OWNER_CAPTURED_AT_NS_BYTE_OFFSET = 8;
    static final int OWNER_VALID_BYTE_OFFSET = 16;
    static final int OWNER_BYTES = 24;
    private static final int DECISION_BYTES = NEAR_IDENTICAL_DECISION_SLOT_COUNT
            * ClientSbsNearIdenticalPolicy.DECISION_RECORD_BYTES;

    private final EGLContext ownerContext;
    private final int inputWidth;
    private final int inputHeight;
    private final int tileGridWidth;
    private final int tileGridHeight;
    private final int inputTensorBytes;
    private final FrameTransaction frameTransaction = new FrameTransaction();

    private int packAndClassifyProgram;
    private int nearIdenticalResolveProgram;
    private int commitProgram;
    private int ownerBuffer;
    private int previousInputBuffer;
    private int nearIdenticalDecisionBuffer;
    private int tileEvidenceTexture;
    private int pendingModelInputTexture;
    private long pendingFrameSequence;
    private long pendingCapturedAtNs;
    private int packColorUniform;
    private int packNearIdenticalCandidateUniform;
    private int packLazyInputPackingUniform;
    private int packFrameSequenceUniform;
    private int packCapturedAtNsUniform;
    private int commitFrameSequenceUniform;
    private int commitCapturedAtNsUniform;
    private int commitColorUniform;
    private int nearIdenticalResolveCandidateUniform;
    private int nearIdenticalResolveDecisionWordOffsetUniform;
    private int nearIdenticalResolveTokenUniform;
    private int nearIdenticalResolveFrameSequenceUniform;
    private int nearIdenticalResolveCapturedAtNsUniform;
    private final int[] validatedPackedInputBuffers = new int[NEAR_IDENTICAL_DECISION_SLOT_COUNT];
    /** Preserve stage-specific diagnostics on the first frame after reset, then batch queries. */
    private boolean validateDispatchesIndividually = true;
    private boolean released;

    public ClientSbsGpuNearReuseClassifier(int inputWidth, int inputHeight) {
        this.inputWidth = requirePositiveDimension(inputWidth, "inputWidth");
        this.inputHeight = requirePositiveDimension(inputHeight, "inputHeight");
        tileGridWidth = tilesForPixels(inputWidth);
        tileGridHeight = tilesForPixels(inputHeight);
        long requiredInputBytes = (long) inputWidth * inputHeight * 3L * Float.BYTES;
        if (requiredInputBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Model input tensor is too large: "
                    + inputWidth + "x" + inputHeight);
        }
        inputTensorBytes = (int) requiredInputBytes;

        ownerContext = ClientSbsGlCompute.requireCurrentGles31Context(
                "Client SBS GPU near-reuse classification requires GLES 3.1: ");

        try {
            initializePrograms();
            initializeStorage();
            initializeUniforms();
            reset();
            LimeLog.info("Client SBS GPU near-reuse classifier enabled: " + inputWidth + "x"
                    + inputHeight + " -> " + tileGridWidth + "x" + tileGridHeight + " tiles");
        } catch (RuntimeException error) {
            releaseGlResourcesUnchecked();
            released = true;
            throw error;
        }
    }

    /**
     * Enqueues packing and classification of one renderer-owned SDR model-input texture without
     * waiting. A candidate is compared only with the input committed by the last accepted real
     * inference. A successful call starts a transaction that must be finished with exactly one
     * call to {@link #commitAcceptedFrame(int)} or {@link #discardPendingFrame()} before another
     * frame is processed.
     *
     * <p>With lazy packing, classification reads RGB directly from the retained model texture and
     * does not write the tensor slot. Native must pack that exact texture for every INFER outcome,
     * including a failed or malformed decision read. The texture must remain unchanged until
     * this transaction is committed or discarded.</p>
     */
    public void processRendererOwnedAndPack(int modelInputTexture, int packedFloatSsbo,
                                             boolean nearIdenticalCandidate,
                                             long decisionToken,
                                             int decisionSlot,
                                             long sourceFrameSequence,
                                             long capturedAtNs,
                                             boolean lazyInputPacking) {
        assertOwnerContext();
        if (modelInputTexture == 0) {
            throw new IllegalArgumentException("modelInputTexture must be a valid GL texture");
        }
        if (packedFloatSsbo == 0) {
            throw new IllegalArgumentException("packedFloatSsbo must be a valid GL buffer");
        }
        int decisionByteOffset = nearIdenticalDecisionByteOffsetForSlot(decisionSlot);
        validatePackedInputBuffer(packedFloatSsbo, decisionSlot);
        boolean effectiveNearIdenticalCandidate = nearIdenticalCandidate
                && frameTransaction.hasHistory();
        int decisionWordOffset = decisionByteOffset / Integer.BYTES;

        frameTransaction.beginPendingFrame();
        boolean submitted = false;
        try {
            bindSsbo(OWNER_SSBO_BINDING, ownerBuffer);
            bindSsbo(DECISION_SSBO_BINDING, nearIdenticalDecisionBuffer);
            bindSsbo(INPUT_TENSOR_SSBO_BINDING, packedFloatSsbo);
            bindSsbo(PREVIOUS_INPUT_TENSOR_SSBO_BINDING, previousInputBuffer);

            GLES20.glUseProgram(packAndClassifyProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, modelInputTexture);
            GLES20.glUniform1i(packColorUniform, 0);
            GLES20.glUniform1i(packNearIdenticalCandidateUniform,
                    effectiveNearIdenticalCandidate ? 1 : 0);
            GLES20.glUniform1i(packLazyInputPackingUniform, lazyInputPacking ? 1 : 0);
            GLES30.glUniform2ui(packFrameSequenceUniform,
                    (int) sourceFrameSequence, (int) (sourceFrameSequence >>> 32));
            GLES30.glUniform2ui(packCapturedAtNsUniform,
                    (int) capturedAtNs, (int) (capturedAtNs >>> 32));
            GLES31.glBindImageTexture(TILE_EVIDENCE_IMAGE_BINDING, tileEvidenceTexture, 0,
                    false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32UI);
            dispatch(tileGridWidth, tileGridHeight, 1,
                    "pack model tensor and classify near-identical tiles");
            // The pack pass writes the tensor SSBO and the tile-evidence image read below.
            GLES31.glMemoryBarrier(packCompletionBarrierBits());

            GLES20.glUseProgram(nearIdenticalResolveProgram);
            GLES20.glUniform1i(nearIdenticalResolveCandidateUniform,
                    effectiveNearIdenticalCandidate ? 1 : 0);
            GLES30.glUniform1ui(nearIdenticalResolveDecisionWordOffsetUniform,
                    decisionWordOffset);
            GLES30.glUniform2ui(nearIdenticalResolveTokenUniform,
                    (int) decisionToken, (int) (decisionToken >>> 32));
            GLES30.glUniform2ui(nearIdenticalResolveFrameSequenceUniform,
                    (int) sourceFrameSequence, (int) (sourceFrameSequence >>> 32));
            GLES30.glUniform2ui(nearIdenticalResolveCapturedAtNsUniform,
                    (int) capturedAtNs, (int) (capturedAtNs >>> 32));
            GLES31.glBindImageTexture(TILE_EVIDENCE_IMAGE_BINDING, tileEvidenceTexture, 0,
                    false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA32UI);
            dispatch(1, 1, 1, "resolve near-identical reuse");
            // Makes the published decision record visible to the input fence and its consumer.
            shaderStorageBarrier();
            // Avoid Java/driver glGetError round trips on every inferred frame. The first
            // frame still identifies the exact failing dispatch; steady state checks once here.
            checkGlError("near-reuse pack/classify pipeline");
            validateDispatchesIndividually = false;
            pendingModelInputTexture = modelInputTexture;
            pendingFrameSequence = sourceFrameSequence;
            pendingCapturedAtNs = capturedAtNs;
            submitted = true;
        } finally {
            unbindRendererOwnedState();
            if (!submitted) {
                frameTransaction.discardPendingFrame();
                clearPendingFrame();
            }
        }
    }

    /**
     * Offers the pending frame as the comparison reference after its actual inference result has
     * been postprocessed. The GPU reads {@code processorStateBuffer}'s frame-state word: a
     * V2-valid field commits the model input and becomes the reuse owner; any other field commits
     * nothing and invalidates the owner. This method does not wait for the GPU.
     */
    public void commitAcceptedFrame(int processorStateBuffer) {
        assertOwnerContext();
        frameTransaction.requirePendingFrame();
        if (pendingModelInputTexture == 0) {
            throw new IllegalStateException("No packed model input is pending");
        }
        if (processorStateBuffer == 0) {
            throw new IllegalArgumentException("processorStateBuffer must be a valid GL buffer");
        }
        try {
            bindSsbo(OWNER_SSBO_BINDING, ownerBuffer);
            bindSsbo(PROCESSOR_STATE_SSBO_BINDING, processorStateBuffer);
            bindSsbo(PREVIOUS_INPUT_TENSOR_SSBO_BINDING, previousInputBuffer);
            GLES20.glUseProgram(commitProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pendingModelInputTexture);
            GLES20.glUniform1i(commitColorUniform, 0);
            GLES30.glUniform2ui(commitFrameSequenceUniform,
                    (int) pendingFrameSequence, (int) (pendingFrameSequence >>> 32));
            GLES30.glUniform2ui(commitCapturedAtNsUniform,
                    (int) pendingCapturedAtNs, (int) (pendingCapturedAtNs >>> 32));
            dispatch(tilesForPixels(inputWidth), tilesForPixels(inputHeight), 1,
                    "commit accepted inference input");
            shaderStorageBarrier();
            checkGlError("commit accepted inference input");
        } finally {
            unbindRendererOwnedState();
        }
        clearPendingFrame();
        frameTransaction.commitAcceptedFrame();
    }

    /** Discards a classified frame that does not replace the comparison reference. */
    public void discardPendingFrame() {
        assertOwnerContext();
        frameTransaction.discardPendingFrame();
        clearPendingFrame();
    }

    /**
     * Invalidates the reuse owner and both decision records. This method does not wait for the
     * GPU.
     */
    public void reset() {
        assertOwnerContext();
        validateDispatchesIndividually = true;
        frameTransaction.reset();
        clearPendingFrame();
        try {
            // Order these client-side clears after any earlier commit/resolve shader writes.
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
            clearBuffer(ownerBuffer, OWNER_BYTES);
            clearBuffer(nearIdenticalDecisionBuffer, DECISION_BYTES);
            shaderStorageBarrier();
            checkGlError("reset near-reuse owner and decision records");
        } finally {
            unbindRendererOwnedState();
        }
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
        packAndClassifyProgram = createComputeProgram(
                "pack model tensor and classify near-identical tiles",
                ClientSbsGpuNearReuseShaders.createPackAndClassify(inputWidth, inputHeight));
        nearIdenticalResolveProgram = createComputeProgram("resolve near-identical reuse",
                ClientSbsGpuNearReuseShaders.createNearIdenticalResolve(
                        inputWidth, inputHeight));
        commitProgram = createComputeProgram("commit accepted inference input",
                ClientSbsGpuNearReuseShaders.createCommit(inputWidth, inputHeight));
    }

    private void initializeStorage() {
        ownerBuffer = createBuffer(OWNER_BYTES);
        previousInputBuffer = createBuffer(inputTensorBytes);
        nearIdenticalDecisionBuffer = createBuffer(DECISION_BYTES);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        tileEvidenceTexture = ClientSbsGlCompute.createTexture2D(tileGridWidth, tileGridHeight,
                GLES30.GL_RGBA32UI, GLES20.GL_NEAREST,
                "Unable to allocate client SBS near-reuse evidence texture");
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        checkGlError("create GPU near-reuse storage");
    }

    private void initializeUniforms() {
        packColorUniform = requiredUniform(packAndClassifyProgram, "uCurrentColor");
        packNearIdenticalCandidateUniform = requiredUniform(
                packAndClassifyProgram, "uNearIdenticalCandidate");
        packLazyInputPackingUniform = requiredUniform(
                packAndClassifyProgram, "uLazyInputPacking");
        packFrameSequenceUniform = requiredUniform(
                packAndClassifyProgram, "uCurrentFrameSequence");
        packCapturedAtNsUniform = requiredUniform(
                packAndClassifyProgram, "uCurrentCapturedAtNs");
        commitFrameSequenceUniform = requiredUniform(
                commitProgram, "uCurrentFrameSequence");
        commitCapturedAtNsUniform = requiredUniform(
                commitProgram, "uCurrentCapturedAtNs");
        commitColorUniform = requiredUniform(commitProgram, "uCurrentColor");
        nearIdenticalResolveCandidateUniform = requiredUniform(
                nearIdenticalResolveProgram, "uNearIdenticalCandidate");
        nearIdenticalResolveDecisionWordOffsetUniform = requiredUniform(
                nearIdenticalResolveProgram, "uDecisionWordOffset");
        nearIdenticalResolveTokenUniform = requiredUniform(
                nearIdenticalResolveProgram, "uDecisionToken");
        nearIdenticalResolveFrameSequenceUniform = requiredUniform(
                nearIdenticalResolveProgram, "uCurrentFrameSequence");
        nearIdenticalResolveCapturedAtNsUniform = requiredUniform(
                nearIdenticalResolveProgram, "uCurrentCapturedAtNs");
    }

    private void validatePackedInputBuffer(int buffer, int slot) {
        if (validatedPackedInputBuffers[slot] == buffer) {
            return;
        }
        int[] size = new int[1];
        try {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
            GLES30.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER,
                    GLES20.GL_BUFFER_SIZE, size, 0);
            checkGlError("inspect packed model-input SSBO");
        } finally {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        }
        if (size[0] < inputTensorBytes) {
            throw new IllegalArgumentException("Packed model-input SSBO is " + size[0]
                    + " bytes; tensor requires " + inputTensorBytes);
        }
        validatedPackedInputBuffers[slot] = buffer;
    }

    private static int createBuffer(int bytes) {
        int[] buffer = new int[1];
        GLES30.glGenBuffers(1, buffer, 0);
        if (buffer[0] == 0) {
            throw new IllegalStateException("Unable to allocate client SBS near-reuse buffer");
        }
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0]);
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null,
                GLES30.GL_DYNAMIC_DRAW);
        return buffer[0];
    }

    private static void clearBuffer(int buffer, int bytes) {
        ByteBuffer zeros = ByteBuffer.allocateDirect(bytes);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, zeros);
    }

    private void clearPendingFrame() {
        pendingModelInputTexture = 0;
        pendingFrameSequence = 0L;
        pendingCapturedAtNs = 0L;
    }

    private static int requiredUniform(int program, String name) {
        return ClientSbsGlCompute.requiredUniform(program, name,
                "Required near-reuse uniform was optimized out: ");
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

    /** Evidence tiles, and compute workgroups, needed to cover {@code pixels} on one axis. */
    static int tilesForPixels(int pixels) {
        requirePositiveDimension(pixels, "pixels");
        return 1 + (pixels - 1) / TILE_SIZE;
    }

    /** Returns the persistent two-slot SSBO containing authenticated reuse decisions. */
    public int getNearIdenticalDecisionBufferId() {
        assertOwnerContext();
        return nearIdenticalDecisionBuffer;
    }

    /** Returns the byte offset of one authenticated 32-byte decision record. */
    public int getNearIdenticalDecisionByteOffset(int slot) {
        assertOwnerContext();
        return nearIdenticalDecisionByteOffsetForSlot(slot);
    }

    /**
     * True after at least one real inference has been offered as the comparison reference. The
     * GPU owner record, not this flag, decides whether that inference became a valid owner.
     */
    public boolean hasCommittedInferenceHistory() {
        assertOwnerContext();
        return frameTransaction.hasHistory();
    }

    static int packCompletionBarrierBits() {
        return GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
    }

    static int nearIdenticalDecisionByteOffsetForSlot(int slot) {
        if (slot < 0 || slot >= NEAR_IDENTICAL_DECISION_SLOT_COUNT) {
            throw new IllegalArgumentException("Near-identical decision slot must be 0 or 1: "
                    + slot);
        }
        return slot * ClientSbsNearIdenticalPolicy.DECISION_RECORD_BYTES;
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
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, OWNER_SSBO_BINDING, 0);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, DECISION_SSBO_BINDING, 0);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, INPUT_TENSOR_SSBO_BINDING, 0);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,
                PREVIOUS_INPUT_TENSOR_SSBO_BINDING, 0);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        GLES31.glBindImageTexture(TILE_EVIDENCE_IMAGE_BINDING, 0, 0, false, 0,
                GLES31.GL_READ_ONLY, GLES30.GL_RGBA32UI);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void assertOwnerContext() {
        ClientSbsGlCompute.assertOwnerContext(released, ownerContext,
                "Client SBS GPU near-reuse classifier is released",
                "Client SBS GPU near-reuse classifier used from a different EGL context");
    }

    private void releaseGlResourcesUnchecked() {
        int[] programs = {
                packAndClassifyProgram,
                nearIdenticalResolveProgram,
                commitProgram
        };
        for (int program : programs) {
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
        }
        int[] buffers = {
                ownerBuffer,
                previousInputBuffer,
                nearIdenticalDecisionBuffer
        };
        GLES30.glDeleteBuffers(buffers.length, buffers, 0);
        int[] textures = {tileEvidenceTexture};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        clearHandles();
    }

    private void clearHandles() {
        packAndClassifyProgram = 0;
        nearIdenticalResolveProgram = 0;
        commitProgram = 0;
        ownerBuffer = 0;
        previousInputBuffer = 0;
        nearIdenticalDecisionBuffer = 0;
        tileEvidenceTexture = 0;
        clearPendingFrame();
        validatedPackedInputBuffers[0] = 0;
        validatedPackedInputBuffers[1] = 0;
        frameTransaction.reset();
    }

    /** CPU-side transaction state, kept independent from GLES so its invariants are unit-testable. */
    static final class FrameTransaction {
        private boolean pending;
        private boolean historyOffered;

        void beginPendingFrame() {
            if (pending) {
                throw new IllegalStateException("A near-reuse frame is already pending");
            }
            pending = true;
        }

        void commitAcceptedFrame() {
            requirePendingFrame();
            pending = false;
            historyOffered = true;
        }

        void discardPendingFrame() {
            requirePendingFrame();
            pending = false;
        }

        void reset() {
            pending = false;
            historyOffered = false;
        }

        boolean hasHistory() {
            return historyOffered;
        }

        boolean hasPendingFrame() {
            return pending;
        }

        void requirePendingFrame() {
            if (!pending) {
                throw new IllegalStateException("No near-reuse frame is pending");
            }
        }
    }
}
