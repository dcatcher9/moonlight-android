package com.limelight.sbs;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;

import com.limelight.LimeLog;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GLES 3.1 implementation of the client SBS temporal depth/profile pipeline.
 *
 * <p>The processor consumes LiteRT GPU's packed Float32 model-output SSBO. All per-pixel work
 * remains on the GPU and the result is exposed as a source-aligned depth texture. A four-texel RGBA32F
 * texture publishes the small profile used by reprojection, so the production renderer need not
 * map a buffer or wait for the CPU. {@link #pollHealthSnapshot()} provides low-frequency,
 * asynchronous health telemetry without waiting for the GPU.</p>
 *
 * <p>Instances are GL-context-owned and thread-confined. Create, process, reset, and release on
 * the owning GLES thread. Producer completion for the model-output SSBO must already be ordered
 * in the current context (or waited with an EGL/GL fence) before calling
 * {@link #processRendererOwned}.</p>
 */
public final class ClientSbsGpuDepthProcessor implements AutoCloseable {
    public static final int DEFAULT_TENSOR_WIDTH = 256;
    public static final int DEFAULT_TENSOR_HEIGHT = 256;
    public static final int DEFAULT_OUTPUT_WIDTH = 256;
    public static final int DEFAULT_OUTPUT_HEIGHT = 144;

    /**
     * Renderer profile texture layout. Each entry is one RGBA32F texel:
     * <ol>
     *     <li>stretch low, stretch high, stretch inverse range, subject depth</li>
     *     <li>recenter delta, zero-plane anchor shift, pop ratio, profile-ready flag</li>
     *     <li>raw P2, raw P98, settle-latched edge fraction, change fraction</li>
     *     <li>subject candidate, pop strength, hard-cut flag, scene age</li>
     * </ol>
     */
    /**
     * Adaptive-pop band. The resolve pass latches a strength in [FLOOR, CEILING] and publishes
     * {@code strength / FLOOR} as the ratio; the warp multiplies FLOOR by that ratio, and sizes its
     * inverse-search radius from CEILING. All three must move together — a radius built from a
     * smaller ceiling than the band can actually latch leaves the frame's displacement outside the
     * search window and the probe silently misses crossings.
     */
    public static final float ADAPTIVE_POP_FLOOR = 1.20f;
    public static final float ADAPTIVE_POP_CEILING = 2.00f;
    /** Diagnostic sentinel used until a settled edge field has classified the current shot. */
    public static final float ADAPTIVE_POP_UNCLASSIFIED_EDGE = -1.0f;
    /** Wall-time/reference-frame age used only for adaptive-pop and anchor settling. */
    static final int PROFILE_SETTLE_REFERENCE_FRAMES = 8;

    public static final int PROFILE_TEXEL_COUNT = 4;
    public static final int PROFILE_TEXEL_STRETCH = 0;
    public static final int PROFILE_TEXEL_STEREO = 1;
    public static final int PROFILE_TEXEL_DIAGNOSTICS = 2;
    public static final int PROFILE_TEXEL_SCENE = 3;

    private static final int RAW_SSBO_BINDING = 0;
    private static final int RAW_STATS_SSBO_BINDING = 1;
    private static final int PROFILE_STATS_SSBO_BINDING = 2;
    private static final int STATE_SSBO_BINDING = 3;
    // Resolve temporarily moves raw stats to binding zero so the scene-cut word can occupy one.
    private static final int EXTERNAL_SCENE_CUT_SSBO_BINDING = RAW_STATS_SSBO_BINDING;
    private static final int DEPTH_IMAGE_BINDING = 0;
    private static final int PROFILE_IMAGE_BINDING = 1;

    private static final int RAW_STATS_BYTES = 4 * (4 + 256);
    private static final int PROFILE_STATS_BYTES = 4 * (256 + 256 + 4);
    // Seven full vectors followed by one vec2 and one ivec2. Splitting the final 16 bytes gives
    // the cut detector an exact valid-depth-update counter without moving existing health fields.
    static final int STATE_BYTES = 7 * 4 * Float.BYTES
            + 2 * Float.BYTES + 2 * Integer.BYTES;
    private static final int SCENE_CUT_MAILBOX_SLOT_COUNT = 2;
    private static final int SCENE_CUT_MAILBOX_BYTES =
            SCENE_CUT_MAILBOX_SLOT_COUNT * Integer.BYTES;
    private static final int HEALTH_READBACK_SLOT_COUNT = 3;
    /**
     * Background cadence: enough to keep a HUD current at negligible cost (~2.4 Hz at 72 fps),
     * since each sample is a 128-byte copy plus a fence.
     */
    private static final int HEALTH_SAMPLE_INTERVAL_FRAMES = 30;
    /**
     * Cadence while the stats panel is open. History is the reason: cut retriggering happens at
     * sub-second scale, so at the background rate a burst of three cuts inside one second shows up
     * as a single sample or none at all. Sampling has to outpace the thing being sampled.
     */
    private static final int HEALTH_SAMPLE_INTERVAL_FRAMES_FOCUSED = 5;
    /** LiteRT exposes two immutable output SSBOs that alternate as inference slots. */
    private static final int RAW_BUFFER_VALIDATION_CACHE_SIZE = 2;

    private final int tensorWidth;
    private final int tensorHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final float contentScaleX;
    private final float contentScaleY;
    /** Converts this model grid's finite differences to Apollo's aspect-matched depth grid. */
    private final float spatialThresholdScale;
    /** Host-side per-update tuning cadence requested for this stream. */
    private final float temporalReferenceHz;
    private final boolean removeReflectedPadding;
    private final int tensorPixelCount;
    private final EGLContext ownerContext;
    private final Result result = new Result();
    private final HealthSnapshot healthSnapshot = new HealthSnapshot();
    private final HealthReadbackSlot[] healthReadbackSlots = {
            new HealthReadbackSlot(), new HealthReadbackSlot(), new HealthReadbackSlot()
    };
    private final int[] depthTextures = new int[2];
    /** Scratch storage for thread-confined GL integer queries. */
    private final int[] integerScratch = new int[1];

    private int depthInternalFormat;
    private boolean linearDepthFiltering;
    private int profileTexture;
    private int rawStatsBuffer;
    private int profileStatsBuffer;
    private int stateBuffer;
    private int zeroExternalSceneCutBuffer;
    private int sceneCutMailboxBuffer;

    private int resetStatsProgram;
    private int rawMinMaxProgram;
    private int rawHistogramProgram;
    private int resolveRawProgram;
    private int temporalProgram;
    private int accumulateProfileProgram;
    private int resolveProfileProgram;
    private int resetStateProgram;

    private RawUniforms rawMinMaxUniforms;
    private RawUniforms rawHistogramUniforms;
    private RawUniforms temporalRawUniforms;
    private ExternalSceneCutUniforms resolveRawExternalCutUniforms;
    private ExternalSceneCutUniforms temporalExternalCutUniforms;
    private ExternalSceneCutUniforms resolveExternalCutUniforms;
    private int rawHistogramPreviousUniform;
    private int resolveRawRangeAlphaUniform;
    private int temporalPreviousUniform;
    private int temporalDepthAlphaUniform;
    private int temporalMovingDepthAlphaUniform;
    private int temporalSpatialScaleUniform;
    private int accumulateCurrentUniform;
    private int accumulateOutputSizeUniform;
    private int accumulateSpatialScaleUniform;
    private int resolvePixelCountUniform;
    private int resolveSubjectAlphaUniform;
    private int resolveBandAlphaUniform;
    private int resolveReferenceFrameAdvanceUniform;

    private int currentDepthIndex;
    private long frameSequence;
    private final int[] validatedRawBuffers = new int[RAW_BUFFER_VALIDATION_CACHE_SIZE];
    private final int[] validatedRawBufferSizes = new int[RAW_BUFFER_VALIDATION_CACHE_SIZE];
    private int nextRawBufferValidationCacheSlot;
    private int validatedExternalSceneCutBuffer;
    private int validatedExternalSceneCutBufferSize;
    private int healthGeneration = 1;
    /** Requests one prompt sample after construction/reset, before the periodic cadence begins. */
    private boolean healthSampleRequested = true;
    /** Raised while the stats panel is visible; see the focused sample interval. */
    private volatile boolean healthSamplingFocused;
    /** Successful process submission time used to preserve Apollo's wall-time EMA response. */
    private long lastProcessAtNs;
    /** First frame after construction/reset keeps per-dispatch diagnostics; steady state batches. */
    private boolean validateDispatchesIndividually = true;
    private boolean released;

    /** Creates the legacy default-size processor used only by isolated tests and tooling. */
    public static ClientSbsGpuDepthProcessor createDefault() {
        return new ClientSbsGpuDepthProcessor(DEFAULT_TENSOR_WIDTH, DEFAULT_TENSOR_HEIGHT,
                16.0f / 9.0f);
    }

    /** Creates a processor whose output removes legacy reflected aspect-ratio padding. */
    public ClientSbsGpuDepthProcessor(int tensorWidth, int tensorHeight, float sourceAspect) {
        this(tensorWidth, tensorHeight, sourceAspect, true);
    }

    /**
     * Creates a processor for either a reflected, aspect-fit tensor or a direct full-frame tensor.
     * Direct rectangular models already produce source-aligned depth and therefore retain their
     * complete output; only the legacy square model removes reflected padding here.
     */
    public ClientSbsGpuDepthProcessor(int tensorWidth, int tensorHeight, float sourceAspect,
                                      boolean removeReflectedPadding) {
        this(tensorWidth, tensorHeight, sourceAspect, removeReflectedPadding,
                ClientSbsTemporalTuning.APOLLO_REFERENCE_HZ);
    }

    public ClientSbsGpuDepthProcessor(int tensorWidth, int tensorHeight, float sourceAspect,
                                      boolean removeReflectedPadding,
                                      float temporalReferenceHz) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("tensor dimensions must be positive");
        }
        if (!Float.isFinite(sourceAspect) || sourceAspect <= 0.0f) {
            throw new IllegalArgumentException("sourceAspect must be finite and positive");
        }
        if (!Float.isFinite(temporalReferenceHz) || temporalReferenceHz <= 0.0f) {
            throw new IllegalArgumentException(
                    "temporalReferenceHz must be finite and positive");
        }
        EGLContext currentContext = EGL14.eglGetCurrentContext();
        if (currentContext == null || currentContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("A current EGL context is required");
        }
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        if (version == null || (!version.contains("OpenGL ES 3.1")
                && !version.contains("OpenGL ES 3.2"))) {
            throw new IllegalStateException("Client SBS GPU depth requires GLES 3.1: " + version);
        }

        this.tensorWidth = tensorWidth;
        this.tensorHeight = tensorHeight;
        this.removeReflectedPadding = removeReflectedPadding;
        this.temporalReferenceHz = temporalReferenceHz;
        contentScaleX = removeReflectedPadding ? Math.min(1.0f, sourceAspect) : 1.0f;
        contentScaleY = removeReflectedPadding ? Math.min(1.0f, 1.0f / sourceAspect) : 1.0f;
        outputWidth = Math.max(1, Math.round(tensorWidth * contentScaleX));
        outputHeight = Math.max(1, Math.round(tensorHeight * contentScaleY));
        spatialThresholdScale = ClientSbsTemporalTuning.spatialThresholdScale(
                outputWidth, outputHeight);
        tensorPixelCount = Math.multiplyExact(tensorWidth, tensorHeight);
        ownerContext = currentContext;

        try {
            initializePrograms();
            initializeStorage();
            initializeUniforms();
            resetTemporalState();
            LimeLog.info("Client SBS GPU depth: tensor=" + tensorWidth + "x" + tensorHeight
                    + " output=" + outputWidth + "x" + outputHeight
                    + " texture=R32F"
                    + " filtering=" + (linearDepthFiltering ? "linear" : "nearest")
                    + " postprocess=7-dispatch-fused-cut"
                    + " temporalReference=" + temporalReferenceHz
                    + "Hz spatialThresholdScale=" + spatialThresholdScale);
        } catch (RuntimeException error) {
            releaseGlResourcesUnchecked();
            released = true;
            throw error;
        }
    }

    /**
     * Renderer hot path. The caller owns the GL context state, so this avoids five synchronous
     * state queries and leaves program/buffer bindings neutral with texture unit zero active.
     */
    public Result processRendererOwned(int packedFloatSsbo, int rawByteOffset,
                                       int rawPixelStrideBytes, boolean externalSceneCut) {
        return processInternal(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes,
                externalSceneCut, 0, 0);
    }

    /** Renderer-owned hot path with a GPU-produced uint32 scene-cut flag. */
    public Result processRendererOwnedWithGpuSceneCut(int packedFloatSsbo, int rawByteOffset,
                                                       int rawPixelStrideBytes,
                                                       int externalSceneCutSsbo,
                                                       int externalSceneCutByteOffset) {
        return processInternal(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes,
                false, externalSceneCutSsbo, externalSceneCutByteOffset);
    }

    /** Stable GPU mailbox shared with the color-cut detector, one word per tensor slot. */
    public int getSceneCutMailboxBufferId() {
        assertOwnerContext();
        return sceneCutMailboxBuffer;
    }

    /** Returns the word-aligned byte offset paired with one native tensor/color slot. */
    public int getSceneCutMailboxByteOffset(int slot) {
        assertOwnerContext();
        return sceneCutMailboxByteOffsetForSlot(slot);
    }

    static int sceneCutMailboxByteOffsetForSlot(int slot) {
        if (slot < 0 || slot >= SCENE_CUT_MAILBOX_SLOT_COUNT) {
            throw new IllegalArgumentException("invalid scene-cut mailbox slot " + slot);
        }
        return slot * Integer.BYTES;
    }

    private Result processInternal(int packedFloatSsbo, int rawByteOffset,
                                   int rawPixelStrideBytes, boolean externalSceneCut,
                                   int externalSceneCutSsbo, int externalSceneCutByteOffset) {
        assertOwnerContext();
        if (packedFloatSsbo == 0) {
            throw new IllegalArgumentException("packedFloatSsbo must be a valid GL buffer");
        }
        if (rawByteOffset < 0 || (rawByteOffset & 3) != 0) {
            throw new IllegalArgumentException("rawByteOffset must be nonnegative and word-aligned");
        }
        if (rawPixelStrideBytes != Float.BYTES) {
            throw new IllegalArgumentException("Packed Float32 pixel stride must be 4 bytes");
        }
        if (externalSceneCutSsbo == 0 && externalSceneCutByteOffset != 0) {
            throw new IllegalArgumentException(
                    "externalSceneCutByteOffset requires an external scene-cut SSBO");
        }
        if (externalSceneCutByteOffset < 0 || (externalSceneCutByteOffset & 3) != 0) {
            throw new IllegalArgumentException(
                    "externalSceneCutByteOffset must be nonnegative and word-aligned");
        }

        int nextDepthIndex = 1 - currentDepthIndex;
        long processAtNs = System.nanoTime();
        long processIntervalNs = lastProcessAtNs == 0L ? 0L
                : Math.max(0L, processAtNs - lastProcessAtNs);
        float rangeAlpha = ClientSbsTemporalTuning.alphaForInterval(
                0.18f, processIntervalNs, temporalReferenceHz);
        float depthAlpha = ClientSbsTemporalTuning.alphaForInterval(
                0.50f, processIntervalNs, temporalReferenceHz);
        // Apollo's moving-edge path composes a 0.50 base update with a 0.25 current-frame
        // preference, which is one effective alpha of 0.625 per reference update.
        float movingDepthAlpha = ClientSbsTemporalTuning.alphaForInterval(
                0.625f, processIntervalNs, temporalReferenceHz);
        float subjectAlpha = ClientSbsTemporalTuning.alphaForInterval(
                0.20f, processIntervalNs, temporalReferenceHz);
        // Only the RELEASE side of the band envelope has a time constant; the attack side is a
        // min/max and is instantaneous by construction.
        float bandAlpha = ClientSbsTemporalTuning.alphaForInterval(
                0.18f, processIntervalNs, temporalReferenceHz);
        int referenceFrameAdvance = ClientSbsTemporalTuning.referenceFrameAdvance(
                processIntervalNs, temporalReferenceHz);
        try {
            validateRawBuffer(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes);
            if (externalSceneCutSsbo != 0) {
                validateExternalSceneCutBuffer(externalSceneCutSsbo,
                        externalSceneCutByteOffset);
            }
            bindSsbo(RAW_SSBO_BINDING, packedFloatSsbo);
            bindSsbo(RAW_STATS_SSBO_BINDING, rawStatsBuffer);
            bindSsbo(PROFILE_STATS_SSBO_BINDING, profileStatsBuffer);
            bindSsbo(STATE_SSBO_BINDING, stateBuffer);

            dispatch(resetStatsProgram, 1, 1, 1, "reset depth scratch stats");
            shaderStorageBarrier();

            GLES20.glUseProgram(rawMinMaxProgram);
            applyRawUniforms(rawMinMaxUniforms, rawByteOffset, rawPixelStrideBytes);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "raw min/max");
            shaderStorageBarrier();

            GLES20.glUseProgram(rawHistogramProgram);
            applyRawUniforms(rawHistogramUniforms, rawByteOffset, rawPixelStrideBytes);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextures[currentDepthIndex]);
            GLES20.glUniform1i(rawHistogramPreviousUniform, 0);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "raw histogram");
            shaderStorageBarrier();

            // The resolve phase does not read the model tensor. Move raw scratch onto binding
            // zero and the GPU cut flag onto binding one so range resolution and same-frame cut
            // application remain one globally ordered dispatch within the four-binding minimum.
            bindSsbo(RAW_SSBO_BINDING, rawStatsBuffer);
            bindSsbo(EXTERNAL_SCENE_CUT_SSBO_BINDING, externalSceneCutSsbo != 0
                    ? externalSceneCutSsbo : zeroExternalSceneCutBuffer);
            GLES20.glUseProgram(resolveRawProgram);
            GLES20.glUniform1f(resolveRawRangeAlphaUniform, rangeAlpha);
            applyExternalSceneCutUniforms(resolveRawExternalCutUniforms, externalSceneCut,
                    externalSceneCutByteOffset);
            dispatchCurrent(1, 1, 1, "resolve raw range");
            shaderStorageBarrier();

            bindSsbo(RAW_SSBO_BINDING, packedFloatSsbo);
            GLES20.glUseProgram(temporalProgram);
            applyRawUniforms(temporalRawUniforms, rawByteOffset, rawPixelStrideBytes);
            applyExternalSceneCutUniforms(temporalExternalCutUniforms, externalSceneCut,
                    externalSceneCutByteOffset);
            GLES20.glUniform1f(temporalDepthAlphaUniform, depthAlpha);
            GLES20.glUniform1f(temporalMovingDepthAlphaUniform, movingDepthAlpha);
            GLES20.glUniform1f(temporalSpatialScaleUniform, spatialThresholdScale);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextures[currentDepthIndex]);
            GLES20.glUniform1i(temporalPreviousUniform, 0);
            GLES31.glBindImageTexture(DEPTH_IMAGE_BINDING, depthTextures[nextDepthIndex], 0,
                    false, 0, GLES31.GL_WRITE_ONLY, depthInternalFormat);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "temporal depth");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT
                    | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
            if (validateDispatchesIndividually) {
                checkGlError("temporal barrier");
            }

            GLES20.glUseProgram(accumulateProfileProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextures[nextDepthIndex]);
            GLES20.glUniform1i(accumulateCurrentUniform, 0);
            GLES20.glUniform2i(accumulateOutputSizeUniform, outputWidth, outputHeight);
            GLES20.glUniform1f(accumulateSpatialScaleUniform, spatialThresholdScale);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1,
                    "accumulate depth profile");
            shaderStorageBarrier();

            GLES20.glUseProgram(resolveProfileProgram);
            applyExternalSceneCutUniforms(resolveExternalCutUniforms, externalSceneCut,
                    externalSceneCutByteOffset);
            GLES20.glUniform1i(resolvePixelCountUniform, outputWidth * outputHeight);
            GLES20.glUniform1f(resolveSubjectAlphaUniform, subjectAlpha);
            GLES20.glUniform1f(resolveBandAlphaUniform, bandAlpha);
            GLES20.glUniform1i(resolveReferenceFrameAdvanceUniform, referenceFrameAdvance);
            GLES31.glBindImageTexture(PROFILE_IMAGE_BINDING, profileTexture, 0, false, 0,
                    GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F);
            dispatchCurrent(1, 1, 1, "resolve depth profile");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
            // Steady state performs one driver error query for the complete seven-dispatch
            // pipeline. The first frame after every reset retains stage-specific diagnostics.
            checkGlError("profile publication barrier");
            validateDispatchesIndividually = false;

            currentDepthIndex = nextDepthIndex;
            frameSequence++;
            result.depthTexture = depthTextures[currentDepthIndex];
            result.profileTexture = profileTexture;
            result.frameSequence = frameSequence;
            result.validFrame = true;
            lastProcessAtNs = processAtNs;
            scheduleHealthReadbackIfDue();
            return result;
        } finally {
            unbindWorkingState();
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        }
    }

    /** Resets all temporal/range/profile state without reallocating GL objects. */
    public void resetTemporalState() {
        assertOwnerContext();
        validateDispatchesIndividually = true;
        int oldProgram = getInteger(GLES20.GL_CURRENT_PROGRAM);
        int oldSsbo = getInteger(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            bindSsbo(STATE_SSBO_BINDING, stateBuffer);
            GLES31.glBindImageTexture(PROFILE_IMAGE_BINDING, profileTexture, 0, false, 0,
                    GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F);
            dispatch(resetStateProgram, 1, 1, 1, "reset temporal state");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
            checkGlError("reset temporal state barrier");
            currentDepthIndex = 0;
            frameSequence = 0L;
            lastProcessAtNs = 0L;
            result.depthTexture = 0;
            result.profileTexture = profileTexture;
            result.frameSequence = 0L;
            result.validFrame = false;
            healthGeneration = healthGeneration == Integer.MAX_VALUE ? 1 : healthGeneration + 1;
            healthSampleRequested = true;
            healthSnapshot.reset();
        } finally {
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, STATE_SSBO_BINDING, 0);
            GLES31.glBindImageTexture(PROFILE_IMAGE_BINDING, 0, 0, false, 0,
                    GLES31.GL_READ_ONLY, GLES30.GL_RGBA32F);
            GLES20.glUseProgram(oldProgram);
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, oldSsbo);
        }
    }

    /**
     * Polls the low-frequency asynchronous health readback ring.
     *
     * <p>This method never waits: fences are queried with a zero timeout and a state buffer is
     * mapped only after its copy fence is signaled. It returns {@code null} if no newer sample is
     * ready. The returned object is reused, so callers should consume its fields immediately.</p>
     */
    public HealthSnapshot pollHealthSnapshot() {
        assertOwnerContext();
        HealthReadbackSlot newestReady = null;
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            if (slot.fence == 0L) {
                continue;
            }
            int waitResult = GLES30.glClientWaitSync(slot.fence, 0, 0L);
            if (waitResult == GLES30.GL_WAIT_FAILED) {
                LimeLog.warning("Client SBS GPU health fence wait failed");
                recycleHealthReadbackSlot(slot);
                continue;
            }
            if (waitResult != GLES30.GL_ALREADY_SIGNALED
                    && waitResult != GLES30.GL_CONDITION_SATISFIED) {
                continue;
            }
            if (slot.generation == healthGeneration
                    && slot.frameSequence > healthSnapshot.frameSequence
                    && (newestReady == null
                    || slot.frameSequence > newestReady.frameSequence)) {
                newestReady = slot;
            }
        }

        if (newestReady == null) {
            recycleCompletedStaleHealthReadbacks(null);
            return null;
        }

        int oldCopyReadBuffer = getInteger(GLES30.GL_COPY_READ_BUFFER_BINDING);
        GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, newestReady.buffer);
        Buffer mapped = GLES30.glMapBufferRange(GLES30.GL_COPY_READ_BUFFER, 0, STATE_BYTES,
                GLES30.GL_MAP_READ_BIT);
        if (!(mapped instanceof ByteBuffer)) {
            GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, oldCopyReadBuffer);
            recycleHealthReadbackSlot(newestReady);
            throw new IllegalStateException("Unable to map client SBS GPU health snapshot");
        }
        try {
            healthSnapshot.updateFromState(((ByteBuffer) mapped).order(ByteOrder.nativeOrder()),
                    newestReady.frameSequence, outputWidth * outputHeight);
        } finally {
            if (!GLES30.glUnmapBuffer(GLES30.GL_COPY_READ_BUFFER)) {
                LimeLog.warning("Client SBS GPU health staging buffer became invalid while mapped");
            }
            GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, oldCopyReadBuffer);
            recycleHealthReadbackSlot(newestReady);
        }
        recycleCompletedStaleHealthReadbacks(newestReady);
        checkGlError("poll GPU depth health");
        return healthSnapshot;
    }

    /** Raises the sample rate while someone is watching the history plots. */
    public void setHealthSamplingFocused(boolean focused) {
        healthSamplingFocused = focused;
    }

    public int getOutputWidth() {
        return outputWidth;
    }

    public int getOutputHeight() {
        return outputHeight;
    }

    public int getDepthInternalFormat() {
        return depthInternalFormat;
    }

    public boolean isLinearDepthFilteringEnabled() {
        return linearDepthFiltering;
    }

    /** Deletes resources. Must be called with the owning context current. */
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
     * Drops Java handles after EGL context loss, when GL deletion is neither useful nor legal.
     * A new processor must be constructed for the replacement context.
     */
    public void abandonAfterContextLoss() {
        if (released) {
            return;
        }
        clearHandles();
        released = true;
    }

    private void initializePrograms() {
        resetStatsProgram = createComputeProgram("reset depth scratch stats",
                ClientSbsGpuDepthShaders.RESET_ALL_STATS);
        rawMinMaxProgram = createComputeProgram("raw min/max",
                ClientSbsGpuDepthShaders.rawMinMax(removeReflectedPadding));
        rawHistogramProgram = createComputeProgram("raw histogram",
                ClientSbsGpuDepthShaders.rawHistogram(removeReflectedPadding));
        resolveRawProgram = createComputeProgram("resolve raw range",
                ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE);
        accumulateProfileProgram = createComputeProgram("accumulate depth profile",
                ClientSbsGpuDepthShaders.accumulateProfile(removeReflectedPadding));
        resolveProfileProgram = createComputeProgram("resolve depth profile",
                ClientSbsGpuDepthShaders.RESOLVE_PROFILE);
        resetStateProgram = createComputeProgram("reset depth state",
                ClientSbsGpuDepthShaders.RESET_STATE);

        // R32F is the only portable GLES 3.1 single-channel image-store format. R16F may be a
        // renderable/filterable texture, but GL_EXT_shader_image_load_formatted does not make
        // GL_R16F a legal glBindImageTexture image-unit format.
        temporalProgram = createComputeProgram("R32F temporal depth",
                ClientSbsGpuDepthShaders.temporalFilter(removeReflectedPadding));
        depthInternalFormat = GLES30.GL_R32F;

        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        boolean floatLinear = extensions != null
                && extensions.contains("GL_OES_texture_float_linear");
        linearDepthFiltering = floatLinear;
    }

    private void initializeStorage() {
        rawStatsBuffer = createBuffer(RAW_STATS_BYTES);
        profileStatsBuffer = createBuffer(PROFILE_STATS_BYTES);
        stateBuffer = createBuffer(STATE_BYTES);
        zeroExternalSceneCutBuffer = createBuffer(Integer.BYTES);
        sceneCutMailboxBuffer = createBuffer(SCENE_CUT_MAILBOX_BYTES);
        ByteBuffer zeroWord = ByteBuffer.allocateDirect(SCENE_CUT_MAILBOX_BYTES)
                .order(ByteOrder.nativeOrder());
        while (zeroWord.hasRemaining()) {
            zeroWord.putInt(0);
        }
        zeroWord.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, zeroExternalSceneCutBuffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, Integer.BYTES,
                zeroWord.duplicate());
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, sceneCutMailboxBuffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                SCENE_CUT_MAILBOX_BYTES, zeroWord);
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            slot.buffer = createBuffer(STATE_BYTES, GLES30.GL_STREAM_READ);
        }
        depthTextures[0] = createTexture(outputWidth, outputHeight, depthInternalFormat,
                linearDepthFiltering ? GLES20.GL_LINEAR : GLES20.GL_NEAREST);
        depthTextures[1] = createTexture(outputWidth, outputHeight, depthInternalFormat,
                linearDepthFiltering ? GLES20.GL_LINEAR : GLES20.GL_NEAREST);
        profileTexture = createTexture(PROFILE_TEXEL_COUNT, 1, GLES30.GL_RGBA32F,
                GLES20.GL_NEAREST);
        checkGlError("create GPU depth storage");
    }

    private void initializeUniforms() {
        rawMinMaxUniforms = new RawUniforms(rawMinMaxProgram, removeReflectedPadding);
        rawHistogramUniforms = new RawUniforms(rawHistogramProgram, removeReflectedPadding);
        temporalRawUniforms = new RawUniforms(temporalProgram, removeReflectedPadding);
        resolveRawExternalCutUniforms = new ExternalSceneCutUniforms(resolveRawProgram);
        temporalExternalCutUniforms = new ExternalSceneCutUniforms(temporalProgram);
        resolveExternalCutUniforms = new ExternalSceneCutUniforms(resolveProfileProgram);
        rawHistogramPreviousUniform = requiredUniform(rawHistogramProgram, "uPreviousDepth");
        resolveRawRangeAlphaUniform = requiredUniform(resolveRawProgram, "uRangeAlpha");
        temporalPreviousUniform = requiredUniform(temporalProgram, "uPreviousDepth");
        temporalDepthAlphaUniform = requiredUniform(temporalProgram, "uDepthAlpha");
        temporalMovingDepthAlphaUniform = requiredUniform(
                temporalProgram, "uMovingDepthAlpha");
        temporalSpatialScaleUniform = requiredUniform(
                temporalProgram, "uSpatialThresholdScale");
        accumulateCurrentUniform = requiredUniform(accumulateProfileProgram, "uCurrentDepth");
        accumulateOutputSizeUniform = requiredUniform(accumulateProfileProgram, "uOutputSize");
        accumulateSpatialScaleUniform = requiredUniform(
                accumulateProfileProgram, "uSpatialThresholdScale");
        resolvePixelCountUniform = requiredUniform(resolveProfileProgram, "uPixelCount");
        resolveSubjectAlphaUniform = requiredUniform(resolveProfileProgram, "uSubjectAlpha");
        resolveBandAlphaUniform = requiredUniform(resolveProfileProgram, "uBandAlpha");
        resolveReferenceFrameAdvanceUniform = requiredUniform(
                resolveProfileProgram, "uReferenceFrameAdvance");
    }

    private int createBuffer(int bytes) {
        return createBuffer(bytes, GLES30.GL_DYNAMIC_DRAW);
    }

    private int createBuffer(int bytes, int usage) {
        int[] buffer = new int[1];
        GLES30.glGenBuffers(1, buffer, 0);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0]);
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, usage);
        if (buffer[0] == 0) {
            throw new IllegalStateException("Unable to allocate client SBS GPU buffer");
        }
        return buffer[0];
    }

    private int createTexture(int width, int height, int internalFormat, int filtering) {
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
            throw new IllegalStateException("Unable to allocate client SBS GPU texture");
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

    private void validateRawBuffer(int rawBuffer, int rawByteOffset, int rawPixelStrideBytes) {
        int validatedSize = -1;
        for (int slot = 0; slot < RAW_BUFFER_VALIDATION_CACHE_SIZE; slot++) {
            if (validatedRawBuffers[slot] == rawBuffer) {
                validatedSize = validatedRawBufferSizes[slot];
                break;
            }
        }
        if (validatedSize < 0) {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, rawBuffer);
            int[] size = new int[1];
            GLES30.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER,
                    GLES20.GL_BUFFER_SIZE, size, 0);
            checkGlError("inspect model output SSBO");
            int cacheSlot = nextRawBufferValidationCacheSlot;
            nextRawBufferValidationCacheSlot = (nextRawBufferValidationCacheSlot + 1)
                    % RAW_BUFFER_VALIDATION_CACHE_SIZE;
            validatedRawBuffers[cacheSlot] = rawBuffer;
            validatedRawBufferSizes[cacheSlot] = size[0];
            validatedSize = size[0];
        }
        long required = (long) rawByteOffset
                + (long) tensorPixelCount * rawPixelStrideBytes;
        if (required > validatedSize) {
            throw new IllegalArgumentException("Model output SSBO is " + validatedSize
                    + " bytes; depth tensor requires " + required);
        }
    }

    private void validateExternalSceneCutBuffer(int buffer, int byteOffset) {
        if (validatedExternalSceneCutBuffer != buffer) {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer);
            int[] size = new int[1];
            GLES30.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER,
                    GLES20.GL_BUFFER_SIZE, size, 0);
            checkGlError("inspect external scene-cut SSBO");
            validatedExternalSceneCutBuffer = buffer;
            validatedExternalSceneCutBufferSize = size[0];
        }
        long required = (long) byteOffset + Integer.BYTES;
        if (required > validatedExternalSceneCutBufferSize) {
            throw new IllegalArgumentException("External scene-cut SSBO is "
                    + validatedExternalSceneCutBufferSize + " bytes; flag requires " + required);
        }
    }

    private void applyRawUniforms(RawUniforms uniforms, int rawByteOffset,
                                  int rawPixelStrideBytes) {
        GLES30.glUniform1ui(uniforms.rawByteOffset, rawByteOffset);
        GLES30.glUniform1ui(uniforms.rawPixelStrideBytes, rawPixelStrideBytes);
        GLES20.glUniform2i(uniforms.tensorSize, tensorWidth, tensorHeight);
        GLES20.glUniform2i(uniforms.outputSize, outputWidth, outputHeight);
        if (uniforms.contentScale >= 0) {
            GLES20.glUniform2f(uniforms.contentScale, contentScaleX, contentScaleY);
        }
    }

    private static void applyExternalSceneCutUniforms(ExternalSceneCutUniforms uniforms,
                                                       boolean externalSceneCut,
                                                       int externalSceneCutByteOffset) {
        GLES20.glUniform1i(uniforms.cpuFlag, externalSceneCut ? 1 : 0);
        GLES30.glUniform1ui(uniforms.wordOffset, externalSceneCutByteOffset / Integer.BYTES);
    }

    private void scheduleHealthReadbackIfDue() {
        if (!shouldScheduleHealthReadback(
                healthSampleRequested, frameSequence, healthSamplingFocused)) {
            return;
        }
        HealthReadbackSlot available = null;
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            if (slot.fence == 0L) {
                available = slot;
                break;
            }
        }
        if (available == null) {
            return;
        }

        try {
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
            GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, stateBuffer);
            GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, available.buffer);
            GLES30.glCopyBufferSubData(GLES30.GL_COPY_READ_BUFFER, GLES30.GL_COPY_WRITE_BUFFER,
                    0, 0, STATE_BYTES);
            available.fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (available.fence == 0L) {
                throw new IllegalStateException("Unable to create GPU depth health fence");
            }
            available.frameSequence = frameSequence;
            available.generation = healthGeneration;
            healthSampleRequested = false;
            // Submit the tiny copy now; glFlush does not wait for its completion.
            GLES20.glFlush();
            checkGlError("schedule GPU depth health readback");
        } finally {
            GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, 0);
            GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, 0);
        }
    }

    static boolean shouldScheduleHealthReadback(boolean sampleRequested, long frameSequence) {
        return shouldScheduleHealthReadback(sampleRequested, frameSequence, false);
    }

    /**
     * @param focused true while the stats panel is visible, which raises the sample rate so the
     *                history plots can resolve events shorter than the background interval
     */
    static boolean shouldScheduleHealthReadback(boolean sampleRequested, long frameSequence,
                                                boolean focused) {
        int interval = focused
                ? HEALTH_SAMPLE_INTERVAL_FRAMES_FOCUSED : HEALTH_SAMPLE_INTERVAL_FRAMES;
        return sampleRequested || frameSequence % interval == 0L;
    }

    private void recycleCompletedStaleHealthReadbacks(HealthReadbackSlot except) {
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            if (slot == except || slot.fence == 0L) {
                continue;
            }
            int waitResult = GLES30.glClientWaitSync(slot.fence, 0, 0L);
            if (waitResult == GLES30.GL_ALREADY_SIGNALED
                    || waitResult == GLES30.GL_CONDITION_SATISFIED
                    || waitResult == GLES30.GL_WAIT_FAILED) {
                recycleHealthReadbackSlot(slot);
            }
        }
    }

    private static void recycleHealthReadbackSlot(HealthReadbackSlot slot) {
        if (slot.fence != 0L) {
            GLES30.glDeleteSync(slot.fence);
        }
        slot.fence = 0L;
        slot.frameSequence = 0L;
        slot.generation = 0;
    }

    private void dispatch(int program, int groupsX, int groupsY, int groupsZ, String stage) {
        GLES20.glUseProgram(program);
        dispatchCurrent(groupsX, groupsY, groupsZ, stage);
    }

    private void dispatchCurrent(int groupsX, int groupsY, int groupsZ, String stage) {
        GLES31.glDispatchCompute(groupsX, groupsY, groupsZ);
        if (validateDispatchesIndividually) {
            checkGlError(stage);
        }
    }

    private static int groups(int length) {
        return (length + 15) / 16;
    }

    private static void shaderStorageBarrier() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    private static void bindSsbo(int binding, int buffer) {
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, buffer);
    }

    private void unbindWorkingState() {
        for (int binding = RAW_SSBO_BINDING; binding <= STATE_SSBO_BINDING; binding++) {
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, 0);
        }
        GLES31.glBindImageTexture(DEPTH_IMAGE_BINDING, 0, 0, false, 0,
                GLES31.GL_READ_ONLY, depthInternalFormat);
        GLES31.glBindImageTexture(PROFILE_IMAGE_BINDING, 0, 0, false, 0,
                GLES31.GL_READ_ONLY, GLES30.GL_RGBA32F);
    }

    private static int requiredUniform(int program, String name) {
        int location = GLES20.glGetUniformLocation(program, name);
        if (location < 0) {
            throw new IllegalStateException("Required compute uniform was optimized out: " + name);
        }
        return location;
    }

    private void assertOwnerContext() {
        if (released) {
            throw new IllegalStateException("Client SBS GPU depth processor is released");
        }
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current == null || current == EGL14.EGL_NO_CONTEXT || !ownerContext.equals(current)) {
            throw new IllegalStateException("Client SBS GPU depth used from a different EGL context");
        }
    }

    private int getInteger(int name) {
        integerScratch[0] = 0;
        GLES20.glGetIntegerv(name, integerScratch, 0);
        return integerScratch[0];
    }

    private static long unsignedIntToLong(int value) {
        return value & 0xffffffffL;
    }

    private static void checkGlError(String stage) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(stage + " failed with GL error 0x"
                    + Integer.toHexString(error));
        }
    }

    private void releaseGlResourcesUnchecked() {
        int[] programs = {resetStatsProgram, rawMinMaxProgram, rawHistogramProgram,
                resolveRawProgram, temporalProgram,
                accumulateProfileProgram, resolveProfileProgram, resetStateProgram};
        for (int program : programs) {
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
        }
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            recycleHealthReadbackSlot(slot);
        }
        int[] buffers = {rawStatsBuffer, profileStatsBuffer, stateBuffer,
                zeroExternalSceneCutBuffer, sceneCutMailboxBuffer,
                healthReadbackSlots[0].buffer, healthReadbackSlots[1].buffer,
                healthReadbackSlots[2].buffer};
        GLES30.glDeleteBuffers(buffers.length, buffers, 0);
        int[] textures = {depthTextures[0], depthTextures[1], profileTexture};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        clearHandles();
    }

    private void clearHandles() {
        resetStatsProgram = 0;
        rawMinMaxProgram = 0;
        rawHistogramProgram = 0;
        resolveRawProgram = 0;
        temporalProgram = 0;
        accumulateProfileProgram = 0;
        resolveProfileProgram = 0;
        resetStateProgram = 0;
        rawStatsBuffer = 0;
        profileStatsBuffer = 0;
        stateBuffer = 0;
        zeroExternalSceneCutBuffer = 0;
        sceneCutMailboxBuffer = 0;
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            slot.buffer = 0;
            slot.fence = 0L;
            slot.frameSequence = 0L;
            slot.generation = 0;
        }
        depthTextures[0] = 0;
        depthTextures[1] = 0;
        profileTexture = 0;
        result.validFrame = false;
    }

    private static final class RawUniforms {
        final int rawByteOffset;
        final int rawPixelStrideBytes;
        final int tensorSize;
        final int outputSize;
        final int contentScale;

        RawUniforms(int program, boolean removeReflectedPadding) {
            rawByteOffset = requiredUniform(program, "uRawByteOffset");
            rawPixelStrideBytes = requiredUniform(program, "uRawPixelStrideBytes");
            tensorSize = requiredUniform(program, "uTensorSize");
            outputSize = requiredUniform(program, "uOutputSize");
            contentScale = removeReflectedPadding
                    ? requiredUniform(program, "uContentScale") : -1;
        }
    }

    private static final class ExternalSceneCutUniforms {
        final int cpuFlag;
        final int wordOffset;

        ExternalSceneCutUniforms(int program) {
            cpuFlag = requiredUniform(program, "uExternalSceneCut");
            wordOffset = requiredUniform(program, "uExternalSceneCutWordOffset");
        }
    }

    private static final class HealthReadbackSlot {
        int buffer;
        long fence;
        long frameSequence;
        int generation;
    }

    /** Allocation-stable GPU result view. */
    public static final class Result {
        private int depthTexture;
        private int profileTexture;
        private long frameSequence;
        private boolean validFrame;

        public int getDepthTextureId() {
            return depthTexture;
        }

        public int getProfileTextureId() {
            return profileTexture;
        }

        public long getFrameSequence() {
            return frameSequence;
        }

        public boolean isValidFrame() {
            return validFrame;
        }
    }

    /** Allocation-stable, asynchronously sampled GPU depth-health view. */
    public static final class HealthSnapshot {
        private long frameSequence;
        private int expectedRawSamples;
        private int validRawSamples;
        // Reference-frame-scaled profile age for pop/anchor diagnostics, not the cut refractory.
        private int sceneAge;
        private int frameNumber;
        private boolean hardCut;
        private boolean depthCutArmed;
        private boolean percentileRangeCollapsed;
        private long hardCutCount;
        private long externalCutRequestCount;
        private long emptyRawFrameCount;
        private long collapsedRawFrameCount;
        private float validRawFraction;
        private float frameRangeLow;
        private float frameRangeHigh;
        private float frameRangeWidth;
        private float effectiveRangeLow;
        private float effectiveRangeHigh;
        private float effectiveRangeWidth;
        private boolean stereoProfileInitialized;
        private float stretchLow;
        private float stretchHigh;
        private float stretchInverseRange;
        private float subjectDepth;
        private float recenterDelta;
        private float zeroAnchorShift;
        private float edgeFraction;
        private float popStrength;
        private float popRatio;
        private float changeFraction;
        private float hardCutEvidence;

        void reset() {
            frameSequence = 0L;
            expectedRawSamples = 0;
            validRawSamples = 0;
            sceneAge = 0;
            frameNumber = 0;
            hardCut = false;
            depthCutArmed = false;
            percentileRangeCollapsed = true;
            validRawFraction = 0.0f;
            frameRangeLow = 0.0f;
            frameRangeHigh = 0.0f;
            frameRangeWidth = 0.0f;
            effectiveRangeLow = 0.0f;
            effectiveRangeHigh = 0.0f;
            effectiveRangeWidth = 0.0f;
            stereoProfileInitialized = false;
            stretchLow = 0.0f;
            stretchHigh = 1.0f;
            stretchInverseRange = 1.0f;
            subjectDepth = 0.5f;
            recenterDelta = 0.0f;
            zeroAnchorShift = 0.0f;
            edgeFraction = ADAPTIVE_POP_UNCLASSIFIED_EDGE;
            popStrength = ADAPTIVE_POP_FLOOR;
            popRatio = 1.0f;
            changeFraction = 0.0f;
            hardCutEvidence = 0.0f;
            hardCutCount = 0L;
            externalCutRequestCount = 0L;
            emptyRawFrameCount = 0L;
            collapsedRawFrameCount = 0L;
        }

        void updateFromState(ByteBuffer state, long sampledFrameSequence, int expectedSamples) {
            frameSequence = sampledFrameSequence;
            expectedRawSamples = expectedSamples;
            validRawSamples = Math.max(0, state.getInt(88));
            sceneAge = state.getInt(80);
            depthCutArmed = ClientSbsShotCutPolicy.isGeometryArmed(state.getInt(84));
            frameNumber = state.getInt(92);
            hardCut = state.getInt(76) != 0;
            frameRangeLow = state.getFloat(0);
            frameRangeHigh = state.getFloat(4);
            frameRangeWidth = frameRangeHigh - frameRangeLow;
            effectiveRangeLow = state.getFloat(8);
            effectiveRangeHigh = state.getFloat(12);
            effectiveRangeWidth = effectiveRangeHigh - effectiveRangeLow;
            stretchLow = state.getFloat(16);
            stretchHigh = state.getFloat(20);
            stretchInverseRange = state.getFloat(24);
            subjectDepth = state.getFloat(32);
            recenterDelta = state.getFloat(36);
            zeroAnchorShift = state.getFloat(40);
            edgeFraction = state.getFloat(44);
            popStrength = state.getFloat(52);
            popRatio = state.getFloat(56);
            stereoProfileInitialized = state.getInt(68) != 0;
            percentileRangeCollapsed = validRawSamples == 0
                    || isCollapsedRange(frameRangeLow, frameRangeHigh);
            changeFraction = state.getFloat(48);
            hardCutEvidence = state.getFloat(60);
            hardCutCount = unsignedIntToLong(state.getInt(96));
            externalCutRequestCount = unsignedIntToLong(state.getInt(100));
            emptyRawFrameCount = unsignedIntToLong(state.getInt(104));
            collapsedRawFrameCount = unsignedIntToLong(state.getInt(108));
            validRawFraction = expectedSamples > 0
                    ? Math.min(1.0f, (float) validRawSamples / expectedSamples) : 0.0f;
        }

        static boolean isCollapsedRange(float low, float high) {
            if (!Float.isFinite(low) || !Float.isFinite(high) || high < low) {
                return true;
            }
            float scale = Math.max(1.0f, Math.max(Math.abs(low), Math.abs(high)));
            return high - low <= scale * 1.0e-5f;
        }

        public long getFrameSequence() { return frameSequence; }
        public int getExpectedRawSamples() { return expectedRawSamples; }
        public int getValidRawSamples() { return validRawSamples; }
        public float getValidRawFraction() { return validRawFraction; }
        public float getFrameRangeLow() { return frameRangeLow; }
        public float getFrameRangeHigh() { return frameRangeHigh; }
        public float getFrameRangeWidth() { return frameRangeWidth; }
        public float getEffectiveRangeLow() { return effectiveRangeLow; }
        public float getEffectiveRangeHigh() { return effectiveRangeHigh; }
        public float getEffectiveRangeWidth() { return effectiveRangeWidth; }
        public boolean isPercentileRangeCollapsed() { return percentileRangeCollapsed; }
        public boolean isStereoProfileInitialized() { return stereoProfileInitialized; }
        public float getStretchLow() { return stretchLow; }
        public float getStretchHigh() { return stretchHigh; }
        public float getStretchInverseRange() { return stretchInverseRange; }
        public float getSubjectDepth() { return subjectDepth; }
        public float getRecenterDelta() { return recenterDelta; }
        /** Shot-latched zero-plane anchor, in Bestv2 source-pixel shift units. */
        public float getZeroAnchorShift() { return zeroAnchorShift; }
        public float getEdgeFraction() { return edgeFraction; }
        /** Whether {@link #getEdgeFraction()} selected the currently latched pop strength. */
        public boolean hasAdaptivePopClassification() {
            return Float.isFinite(edgeFraction) && edgeFraction >= 0.0f;
        }
        public float getPopStrength() { return popStrength; }
        public float getPopRatio() { return popRatio; }
        public float getChangeFraction() { return changeFraction; }
        /** 0..1 internal evidence, or 2 when an external cut flag was observed. */
        public float getHardCutEvidence() { return hardCutEvidence; }
        public boolean wasExternalCutRequested() { return hardCutEvidence > 1.5f; }
        public boolean wasHardCut() { return hardCut; }
        public boolean isDepthCutArmed() { return depthCutArmed; }
        public int getSceneAge() { return sceneAge; }
        public int getFrameNumber() { return frameNumber; }
        /** Cumulative since the last temporal reset, so sparse polling cannot miss cuts. */
        public long getHardCutCount() { return hardCutCount; }
        public long getExternalCutRequestCount() { return externalCutRequestCount; }
        public long getEmptyRawFrameCount() { return emptyRawFrameCount; }
        public long getCollapsedRawFrameCount() { return collapsedRawFrameCount; }
    }
}
