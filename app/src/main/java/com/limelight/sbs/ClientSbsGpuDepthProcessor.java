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
 * remains on the GPU and the result is exposed as a source-aligned raw-depth texture. A four-texel
 * RGBA32F texture publishes the shot-latched camera used by reprojection, so production need not
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
     *     <li>shot-latched raw mean, current raw mean, reserved, profile-ready flag</li>
     *     <li>current P2/P98 and effective P2/P98 (cut diagnostics only)</li>
     *     <li>change fraction, cut evidence, geometry baseline, scene age</li>
     *     <li>valid count, hard-cut pulse, current-valid bit, history-advance bit</li>
     * </ol>
     */
    // Retained solely by offline legacy shader utilities. Production V2 uses fixed pop 1.75.
    static final float LEGACY_ADAPTIVE_POP_FLOOR = 1.20f;
    static final float LEGACY_ADAPTIVE_POP_CEILING = 2.00f;
    static final float LEGACY_ADAPTIVE_POP_UNCLASSIFIED_EDGE = -1.0f;
    @Deprecated public static final float ADAPTIVE_POP_FLOOR = LEGACY_ADAPTIVE_POP_FLOOR;
    @Deprecated public static final float ADAPTIVE_POP_CEILING = LEGACY_ADAPTIVE_POP_CEILING;
    @Deprecated public static final float ADAPTIVE_POP_UNCLASSIFIED_EDGE =
            LEGACY_ADAPTIVE_POP_UNCLASSIFIED_EDGE;
    /** Retained by the offline legacy profile shader only. */
    static final int PROFILE_SETTLE_REFERENCE_FRAMES = 8;

    public static final int PROFILE_TEXEL_COUNT = 4;

    /** Last notable cut-event diagnostic bits copied with the existing health snapshot. */
    public static final int CUT_DECISION_CURRENT_APPEARANCE_PROPOSAL = 1 << 0;
    public static final int CUT_DECISION_SELECTED_APPEARANCE = 1 << 1;
    public static final int CUT_DECISION_APPEARANCE_ARMED = 1 << 2;
    public static final int CUT_DECISION_APPEARANCE_DEPTH_CORROBORATED = 1 << 3;
    public static final int CUT_DECISION_EXPOSURE_LIKE = 1 << 4;
    public static final int CUT_DECISION_GEOMETRY_CANDIDATE = 1 << 5;
    public static final int CUT_DECISION_GEOMETRY_CONFIRMATION_PENDING = 1 << 6;
    public static final int CUT_DECISION_HISTORY_ADVANCED = 1 << 7;
    public static final int CUT_DECISION_ACCEPTED_APPEARANCE = 1 << 8;
    public static final int CUT_DECISION_ACCEPTED_GEOMETRY = 1 << 9;
    public static final int CUT_DECISION_ACCEPTED_STRUCTURELESS_ENTRY = 1 << 10;
    public static final int CUT_DECISION_CURRENT_DEPTH_VALID = 1 << 11;
    /** Depth alone reached a geometry-cut entry threshold before structural corroboration. */
    public static final int CUT_DECISION_GEOMETRY_DEPTH_TRIGGER = 1 << 12;
    /** Apollo-equivalent independent ordinal structure corroborated the geometry trigger. */
    public static final int CUT_DECISION_GEOMETRY_STRUCTURE_CORROBORATED = 1 << 13;
    /** A pending two-observation geometry confirmation failed on its next valid update. */
    public static final int CUT_DECISION_GEOMETRY_CONFIRMATION_REJECTED = 1 << 14;
    public static final int CUT_DECISION_PERSISTENT_LOW_START = 1 << 15;
    public static final int CUT_DECISION_SUPPORTED_RETURN = 1 << 16;
    /** Ordinal color evidence was unavailable, so bounded two-update depth confirmation is active. */
    public static final int CUT_DECISION_DEPTH_ONLY_FALLBACK = 1 << 17;
    public static final int CUT_DECISION_ACCEPTED_MASK = CUT_DECISION_ACCEPTED_APPEARANCE
            | CUT_DECISION_ACCEPTED_GEOMETRY
            | CUT_DECISION_ACCEPTED_STRUCTURELESS_ENTRY;
    public static final int PROFILE_TEXEL_STRETCH = 0;
    public static final int PROFILE_TEXEL_STEREO = 1;
    public static final int PROFILE_TEXEL_DIAGNOSTICS = 2;
    public static final int PROFILE_TEXEL_SCENE = 3;

    private static final int RAW_SSBO_BINDING = 0;
    private static final int RAW_STATS_SSBO_BINDING = 1;
    private static final int STATE_SSBO_BINDING = 3;
    // Resolve temporarily moves raw stats to binding zero so the scene-cut record can occupy one.
    private static final int EXTERNAL_SCENE_CUT_SSBO_BINDING = RAW_STATS_SSBO_BINDING;
    private static final int DEPTH_IMAGE_BINDING = 0;
    private static final int PROFILE_IMAGE_BINDING = 1;
    private static final int RAW_DEPTH_IMAGE_BINDING = 2;
    private static final int RELIABLE_DEPTH_IMAGE_BINDING = 3;

    private static final int RAW_STATS_HEADER_BYTES = 4 * (4 + 256);
    private static final int RAW_GROUP_MOMENT_BYTES = 4 * Float.BYTES;
    // The original seven vectors plus cut auxiliary fields occupy 128 bytes; the V2 camera starts
    // at 128. Causal cut telemetry is append-only from byte 144, preserving every existing offset.
    static final int CUT_REASON_COUNTERS_BYTE_OFFSET = 144;
    static final int CUT_APPEARANCE_STATS_BYTE_OFFSET = 160;
    static final int CUT_APPEARANCE_META_BYTE_OFFSET = 176;
    static final int CUT_DEPTH_DIAGNOSTICS_BYTE_OFFSET = 192;
    static final int CUT_EVENT_META_BYTE_OFFSET = 208;
    static final int STATE_BYTES = 7 * 4 * Float.BYTES
            + 2 * Float.BYTES + 2 * Integer.BYTES + 4 * Float.BYTES
            + 5 * 4 * Integer.BYTES;
    private static final int SCENE_CUT_MAILBOX_SLOT_COUNT = 2;
    private static final int SCENE_CUT_MAILBOX_BYTES =
            SCENE_CUT_MAILBOX_SLOT_COUNT
                    * ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES;
    private static final int HEALTH_READBACK_SLOT_COUNT = 3;
    /**
     * Background cadence: enough to keep a HUD current at negligible cost (~2.4 Hz at 72 fps),
     * since each sample is one tiny state copy plus a fence.
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
    private final int rawGroupCount;
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
    private int rawDepthTexture;
    private int reliableDepthTexture;
    private int rawStatsBuffer;
    private int stateBuffer;
    private int zeroExternalSceneCutBuffer;
    private int sceneCutMailboxBuffer;

    private int resetStatsProgram;
    private int rawMinMaxProgram;
    private int rawHistogramProgram;
    private int resolveRawProgram;
    private int temporalProgram;
    private int resolveProfileProgram;
    private int resetStateProgram;

    private RawUniforms rawMinMaxUniforms;
    private RawUniforms rawHistogramUniforms;
    private RawUniforms temporalRawUniforms;
    private ExternalSceneCutUniforms resolveExternalCutUniforms;
    private int rawMinMaxPreviousUniform;
    private int resolveRawRangeAlphaUniform;
    private int resolveRawExpectedPixelCountUniform;
    private int resolveRawGroupCountUniform;
    private int resolveProfileSourceFrameDeltaUniform;
    private int temporalPreviousUniform;
    private int temporalReliableUniform;
    private int temporalDepthAlphaUniform;
    private int temporalMovingDepthAlphaUniform;
    private int temporalSpatialScaleUniform;
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
        rawGroupCount = Math.multiplyExact(groups(outputWidth), groups(outputHeight));
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
                    + " postprocess=6-dispatch-raw-v2"
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
        return processRendererOwned(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes,
                externalSceneCut, 1);
    }

    public Result processRendererOwned(int packedFloatSsbo, int rawByteOffset,
                                       int rawPixelStrideBytes, boolean externalSceneCut,
                                       int sourceFrameDelta) {
        return processInternal(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes,
                externalSceneCut, 0, 0, sourceFrameDelta);
    }

    /** Renderer-owned hot path with a GPU-produced appearance-evidence record. */
    public Result processRendererOwnedWithGpuSceneCut(int packedFloatSsbo, int rawByteOffset,
                                                       int rawPixelStrideBytes,
                                                       int externalSceneCutSsbo,
                                                       int externalSceneCutByteOffset) {
        return processRendererOwnedWithGpuSceneCut(packedFloatSsbo, rawByteOffset,
                rawPixelStrideBytes, externalSceneCutSsbo, externalSceneCutByteOffset, 1);
    }

    public Result processRendererOwnedWithGpuSceneCut(int packedFloatSsbo, int rawByteOffset,
                                                       int rawPixelStrideBytes,
                                                       int externalSceneCutSsbo,
                                                       int externalSceneCutByteOffset,
                                                       int sourceFrameDelta) {
        return processInternal(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes,
                false, externalSceneCutSsbo, externalSceneCutByteOffset, sourceFrameDelta);
    }

    /** Stable GPU mailbox shared with the color-cut detector, one record per tensor slot. */
    public int getSceneCutMailboxBufferId() {
        assertOwnerContext();
        return sceneCutMailboxBuffer;
    }

    /** GPU state containing the current result's authoritative history-advance bit. */
    public int getHistoryDecisionStateBufferId() {
        assertOwnerContext();
        return stateBuffer;
    }

    /**
     * Dedicated normalized cut reference. A held result never authorizes its own promotion, though
     * the preceding advancing result may be installed at this actual-inference boundary.
     */
    public int getReliableDepthTextureId() {
        assertOwnerContext();
        return reliableDepthTexture;
    }

    /** Immediate normalized temporal depth, primarily exposed for on-device parity diagnostics. */
    public int getTemporalDepthTextureId() {
        assertOwnerContext();
        return depthTextures[currentDepthIndex];
    }

    /** Returns the record-aligned byte offset paired with one native tensor/color slot. */
    public int getSceneCutMailboxByteOffset(int slot) {
        assertOwnerContext();
        return sceneCutMailboxByteOffsetForSlot(slot);
    }

    static int sceneCutMailboxByteOffsetForSlot(int slot) {
        if (slot < 0 || slot >= SCENE_CUT_MAILBOX_SLOT_COUNT) {
            throw new IllegalArgumentException("invalid scene-cut mailbox slot " + slot);
        }
        return slot * ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES;
    }

    private Result processInternal(int packedFloatSsbo, int rawByteOffset,
                                   int rawPixelStrideBytes, boolean externalSceneCut,
                                   int externalSceneCutSsbo, int externalSceneCutByteOffset,
                                   int sourceFrameDelta) {
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
            bindSsbo(STATE_SSBO_BINDING, stateBuffer);

            dispatch(resetStatsProgram, 1, 1, 1, "reset depth scratch stats");
            shaderStorageBarrier();

            GLES20.glUseProgram(rawMinMaxProgram);
            applyRawUniforms(rawMinMaxUniforms, rawByteOffset, rawPixelStrideBytes);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextures[currentDepthIndex]);
            GLES20.glUniform1i(rawMinMaxPreviousUniform, 0);
            GLES31.glBindImageTexture(RELIABLE_DEPTH_IMAGE_BINDING, reliableDepthTexture, 0,
                    false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "raw min/max");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
            GLES31.glBindImageTexture(RELIABLE_DEPTH_IMAGE_BINDING, 0, 0,
                    false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F);

            GLES20.glUseProgram(rawHistogramProgram);
            applyRawUniforms(rawHistogramUniforms, rawByteOffset, rawPixelStrideBytes);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "raw histogram");
            shaderStorageBarrier();

            // Range must be current before temporal mapping. Move raw scratch onto binding zero;
            // the later temporal pass moves it back to one for its change-count reduction.
            bindSsbo(RAW_SSBO_BINDING, rawStatsBuffer);
            GLES20.glUseProgram(resolveRawProgram);
            GLES20.glUniform1f(resolveRawRangeAlphaUniform, rangeAlpha);
            GLES20.glUniform1i(resolveRawExpectedPixelCountUniform,
                    outputWidth * outputHeight);
            GLES20.glUniform1i(resolveRawGroupCountUniform, rawGroupCount);
            dispatchCurrent(1, 1, 1, "resolve raw range");
            shaderStorageBarrier();

            bindSsbo(RAW_SSBO_BINDING, packedFloatSsbo);
            bindSsbo(RAW_STATS_SSBO_BINDING, rawStatsBuffer);
            GLES20.glUseProgram(temporalProgram);
            applyRawUniforms(temporalRawUniforms, rawByteOffset, rawPixelStrideBytes);
            GLES20.glUniform1f(temporalDepthAlphaUniform, depthAlpha);
            GLES20.glUniform1f(temporalMovingDepthAlphaUniform, movingDepthAlpha);
            GLES20.glUniform1f(temporalSpatialScaleUniform, spatialThresholdScale);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextures[currentDepthIndex]);
            GLES20.glUniform1i(temporalPreviousUniform, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, reliableDepthTexture);
            GLES20.glUniform1i(temporalReliableUniform, 1);
            GLES31.glBindImageTexture(DEPTH_IMAGE_BINDING, depthTextures[nextDepthIndex], 0,
                    false, 0, GLES31.GL_WRITE_ONLY, depthInternalFormat);
            GLES31.glBindImageTexture(RAW_DEPTH_IMAGE_BINDING, rawDepthTexture, 0,
                    false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "temporal depth");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT
                    | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
            if (validateDispatchesIndividually) {
                checkGlError("temporal barrier");
            }

            // Cut resolution consumes the freshly filtered temporal comparison. Raw scratch and
            // the optional scene record occupy bindings zero and one; state remains binding three.
            bindSsbo(RAW_SSBO_BINDING, rawStatsBuffer);
            bindSsbo(EXTERNAL_SCENE_CUT_SSBO_BINDING, externalSceneCutSsbo != 0
                    ? externalSceneCutSsbo : zeroExternalSceneCutBuffer);
            GLES20.glUseProgram(resolveProfileProgram);
            applyExternalSceneCutUniforms(resolveExternalCutUniforms, externalSceneCut,
                    externalSceneCutSsbo != 0, externalSceneCutByteOffset);
            GLES20.glUniform1i(resolveProfileSourceFrameDeltaUniform,
                    clampSourceFrameDelta(sourceFrameDelta));
            GLES20.glUniform1i(resolveReferenceFrameAdvanceUniform, referenceFrameAdvance);
            GLES31.glBindImageTexture(PROFILE_IMAGE_BINDING, profileTexture, 0, false, 0,
                    GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F);
            dispatchCurrent(1, 1, 1, "resolve depth profile");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
            // Steady state performs one driver error query for the complete six-dispatch
            // pipeline. The first frame after every reset retains stage-specific diagnostics.
            checkGlError("profile publication barrier");
            validateDispatchesIndividually = false;

            currentDepthIndex = nextDepthIndex;
            frameSequence++;
            result.depthTexture = rawDepthTexture;
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
        resolveProfileProgram = createComputeProgram("publish V2 shot camera",
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
        rawStatsBuffer = createBuffer(Math.addExact(RAW_STATS_HEADER_BYTES,
                Math.multiplyExact(rawGroupCount, RAW_GROUP_MOMENT_BYTES)));
        stateBuffer = createBuffer(STATE_BYTES);
        zeroExternalSceneCutBuffer = createBuffer(
                ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES);
        sceneCutMailboxBuffer = createBuffer(SCENE_CUT_MAILBOX_BYTES);
        ByteBuffer zeroWord = ByteBuffer.allocateDirect(SCENE_CUT_MAILBOX_BYTES)
                .order(ByteOrder.nativeOrder());
        while (zeroWord.hasRemaining()) {
            zeroWord.putInt(0);
        }
        zeroWord.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, zeroExternalSceneCutBuffer);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES,
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
        rawDepthTexture = createTexture(outputWidth, outputHeight, GLES30.GL_R32F,
                linearDepthFiltering ? GLES20.GL_LINEAR : GLES20.GL_NEAREST);
        reliableDepthTexture = createTexture(outputWidth, outputHeight, GLES30.GL_R32F,
                linearDepthFiltering ? GLES20.GL_LINEAR : GLES20.GL_NEAREST);
        profileTexture = createTexture(PROFILE_TEXEL_COUNT, 1, GLES30.GL_RGBA32F,
                GLES20.GL_NEAREST);
        checkGlError("create GPU depth storage");
    }

    private void initializeUniforms() {
        rawMinMaxUniforms = new RawUniforms(rawMinMaxProgram, removeReflectedPadding);
        rawHistogramUniforms = new RawUniforms(rawHistogramProgram, removeReflectedPadding);
        temporalRawUniforms = new RawUniforms(temporalProgram, removeReflectedPadding);
        resolveExternalCutUniforms = new ExternalSceneCutUniforms(resolveProfileProgram);
        rawMinMaxPreviousUniform = requiredUniform(
                rawMinMaxProgram, "uPreviousTemporalDepth");
        resolveRawRangeAlphaUniform = requiredUniform(resolveRawProgram, "uRangeAlpha");
        resolveRawExpectedPixelCountUniform = requiredUniform(
                resolveRawProgram, "uExpectedPixelCount");
        resolveRawGroupCountUniform = requiredUniform(resolveRawProgram, "uRawGroupCount");
        resolveProfileSourceFrameDeltaUniform = requiredUniform(
                resolveProfileProgram, "uSourceFrameDelta");
        temporalPreviousUniform = requiredUniform(temporalProgram, "uPreviousDepth");
        temporalReliableUniform = requiredUniform(temporalProgram, "uReliableDepth");
        temporalDepthAlphaUniform = requiredUniform(temporalProgram, "uDepthAlpha");
        temporalMovingDepthAlphaUniform = requiredUniform(
                temporalProgram, "uMovingDepthAlpha");
        temporalSpatialScaleUniform = requiredUniform(
                temporalProgram, "uSpatialThresholdScale");
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
        long required = (long) byteOffset
                + ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES;
        if (required > validatedExternalSceneCutBufferSize) {
            throw new IllegalArgumentException("External scene-cut SSBO is "
                    + validatedExternalSceneCutBufferSize + " bytes; record requires " + required);
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
                                                       boolean sceneEvidenceAvailable,
                                                       int externalSceneCutByteOffset) {
        GLES20.glUniform1i(uniforms.cpuFlag, externalSceneCut ? 1 : 0);
        GLES20.glUniform1i(uniforms.sceneEvidenceAvailable,
                sceneEvidenceAvailable ? 1 : 0);
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

    static int clampSourceFrameDelta(long sourceFrameDelta) {
        return (int) Math.min(Math.max(sourceFrameDelta, 1L), 65535L);
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
        GLES31.glBindImageTexture(RAW_DEPTH_IMAGE_BINDING, 0, 0, false, 0,
                GLES31.GL_READ_ONLY, GLES30.GL_R32F);
        GLES31.glBindImageTexture(RELIABLE_DEPTH_IMAGE_BINDING, 0, 0, false, 0,
                GLES31.GL_READ_ONLY, GLES30.GL_R32F);
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
                resolveRawProgram, temporalProgram, resolveProfileProgram, resetStateProgram};
        for (int program : programs) {
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
        }
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            recycleHealthReadbackSlot(slot);
        }
        int[] buffers = {rawStatsBuffer, stateBuffer,
                zeroExternalSceneCutBuffer, sceneCutMailboxBuffer,
                healthReadbackSlots[0].buffer, healthReadbackSlots[1].buffer,
                healthReadbackSlots[2].buffer};
        GLES30.glDeleteBuffers(buffers.length, buffers, 0);
        int[] textures = {depthTextures[0], depthTextures[1], rawDepthTexture,
                reliableDepthTexture, profileTexture};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        clearHandles();
    }

    private void clearHandles() {
        resetStatsProgram = 0;
        rawMinMaxProgram = 0;
        rawHistogramProgram = 0;
        resolveRawProgram = 0;
        temporalProgram = 0;
        resolveProfileProgram = 0;
        resetStateProgram = 0;
        rawStatsBuffer = 0;
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
        rawDepthTexture = 0;
        reliableDepthTexture = 0;
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
        final int sceneEvidenceAvailable;
        final int wordOffset;

        ExternalSceneCutUniforms(int program) {
            cpuFlag = requiredUniform(program, "uExternalSceneCut");
            sceneEvidenceAvailable = requiredUniform(program, "uSceneEvidenceAvailable");
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
        private boolean geometryCutArmed;
        private boolean percentileRangeCollapsed;
        private long hardCutCount;
        private long appearanceProposalCount;
        private long acceptedAppearanceCutCount;
        private long acceptedGeometryCutCount;
        private long acceptedStructurelessEntryCutCount;
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
        private float shotRawMean;
        private float currentRawMean;
        private boolean currentDepthValid;
        private boolean historyAdvanced;
        private int appearanceBlockCount;
        private float appearanceRawChangeFraction;
        private float appearanceMeanLumaDelta;
        private float appearanceStructuralChangeFraction;
        private float appearanceCurrentSupportFraction;
        private float appearanceCommonSupportFraction;
        private int appearanceDetectorFlags;
        private int cutDecisionFlags;
        private long cutEventSequence;
        private float latestDepthChangeFraction;
        private float latestRangeShift;
        private float latestInternalCutEvidence;
        private float geometryChangeBaseline;

        void reset() {
            frameSequence = 0L;
            expectedRawSamples = 0;
            validRawSamples = 0;
            sceneAge = 0;
            frameNumber = 0;
            hardCut = false;
            geometryCutArmed = false;
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
            edgeFraction = LEGACY_ADAPTIVE_POP_UNCLASSIFIED_EDGE;
            popStrength = ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH;
            popRatio = 1.0f;
            changeFraction = 0.0f;
            hardCutEvidence = 0.0f;
            shotRawMean = 0.0f;
            currentRawMean = 0.0f;
            currentDepthValid = false;
            historyAdvanced = false;
            hardCutCount = 0L;
            appearanceProposalCount = 0L;
            acceptedAppearanceCutCount = 0L;
            acceptedGeometryCutCount = 0L;
            acceptedStructurelessEntryCutCount = 0L;
            emptyRawFrameCount = 0L;
            collapsedRawFrameCount = 0L;
            appearanceBlockCount = 0;
            appearanceRawChangeFraction = 0.0f;
            appearanceMeanLumaDelta = 0.0f;
            appearanceStructuralChangeFraction = 0.0f;
            appearanceCurrentSupportFraction = 0.0f;
            appearanceCommonSupportFraction = 0.0f;
            appearanceDetectorFlags = 0;
            cutDecisionFlags = 0;
            cutEventSequence = 0L;
            latestDepthChangeFraction = Float.NaN;
            latestRangeShift = Float.NaN;
            latestInternalCutEvidence = 0.0f;
            geometryChangeBaseline = 0.0f;
        }

        void updateFromState(ByteBuffer state, long sampledFrameSequence, int expectedSamples) {
            frameSequence = sampledFrameSequence;
            expectedRawSamples = expectedSamples;
            // v2Camera.z always carries the actual current field's valid-sample count, including
            // invalid fields whose stateCounters.z authority is cleared.
            validRawSamples = Math.max(0, Math.round(state.getFloat(136)));
            sceneAge = state.getInt(120);
            geometryCutArmed = ClientSbsShotCutPolicy.isGeometryArmed(state.getInt(84));
            frameNumber = state.getInt(92);
            hardCut = state.getInt(76) != 0;
            frameRangeLow = state.getFloat(0);
            frameRangeHigh = state.getFloat(4);
            frameRangeWidth = frameRangeHigh - frameRangeLow;
            effectiveRangeLow = state.getFloat(8);
            effectiveRangeHigh = state.getFloat(12);
            effectiveRangeWidth = effectiveRangeHigh - effectiveRangeLow;
            shotRawMean = state.getFloat(128);
            currentRawMean = state.getFloat(132);
            int frameState = state.getInt(72);
            int requiredCurrentFlags = ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_DEPTH_VALID
                    | ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_V2_VALID;
            currentDepthValid = (frameState & requiredCurrentFlags) == requiredCurrentFlags;
            historyAdvanced = (frameState
                    & ClientSbsShotCutPolicy.FRAME_STATE_HISTORY_ADVANCES) != 0;
            // Keep the source-neutral telemetry ABI truthful: V2 has no normalized stretch,
            // recenter, Bestv2 anchor, edge classifier, or adaptive-pop controller.
            stretchLow = 0.0f;
            stretchHigh = 0.0f;
            stretchInverseRange = 0.0f;
            subjectDepth = shotRawMean;
            recenterDelta = 0.0f;
            zeroAnchorShift = 0.0f;
            edgeFraction = LEGACY_ADAPTIVE_POP_UNCLASSIFIED_EDGE;
            popStrength = ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH;
            popRatio = 1.0f;
            // Match publishProfile() exactly: both the persistent depth state and the
            // shot-latched raw camera must exist. Current-field validity is reported separately
            // because a valid frame may deliberately hold history while remaining renderable.
            stereoProfileInitialized = state.getInt(68) != 0 && state.getFloat(140) > 0.5f;
            percentileRangeCollapsed = validRawSamples == 0
                    || isCollapsedRange(frameRangeLow, frameRangeHigh);
            changeFraction = state.getFloat(48);
            hardCutEvidence = state.getFloat(60);
            hardCutCount = unsignedIntToLong(state.getInt(96));
            // Offset 100 remains the source-compatible proposal counter. The appended copy owns
            // the explicit causal ABI and must carry the same value.
            appearanceProposalCount = unsignedIntToLong(
                    state.getInt(CUT_REASON_COUNTERS_BYTE_OFFSET));
            acceptedAppearanceCutCount = unsignedIntToLong(
                    state.getInt(CUT_REASON_COUNTERS_BYTE_OFFSET + Integer.BYTES));
            acceptedGeometryCutCount = unsignedIntToLong(
                    state.getInt(CUT_REASON_COUNTERS_BYTE_OFFSET + 2 * Integer.BYTES));
            acceptedStructurelessEntryCutCount = unsignedIntToLong(
                    state.getInt(CUT_REASON_COUNTERS_BYTE_OFFSET + 3 * Integer.BYTES));
            emptyRawFrameCount = unsignedIntToLong(state.getInt(104));
            collapsedRawFrameCount = unsignedIntToLong(state.getInt(108));
            appearanceBlockCount = Math.max(0,
                    state.getInt(CUT_APPEARANCE_STATS_BYTE_OFFSET));
            long rawModerateCount = unsignedIntToLong(
                    state.getInt(CUT_APPEARANCE_STATS_BYTE_OFFSET + Integer.BYTES));
            long rawDeltaSum = unsignedIntToLong(
                    state.getInt(CUT_APPEARANCE_STATS_BYTE_OFFSET + 2 * Integer.BYTES));
            long structuralChangeCount = unsignedIntToLong(
                    state.getInt(CUT_APPEARANCE_STATS_BYTE_OFFSET + 3 * Integer.BYTES));
            long currentSupportCount = unsignedIntToLong(
                    state.getInt(CUT_APPEARANCE_META_BYTE_OFFSET));
            long commonSupportCount = unsignedIntToLong(
                    state.getInt(CUT_APPEARANCE_META_BYTE_OFFSET + Integer.BYTES));
            appearanceDetectorFlags = state.getInt(
                    CUT_APPEARANCE_META_BYTE_OFFSET + 2 * Integer.BYTES);
            cutDecisionFlags = state.getInt(
                    CUT_APPEARANCE_META_BYTE_OFFSET + 3 * Integer.BYTES);
            cutEventSequence = unsignedIntToLong(state.getInt(CUT_EVENT_META_BYTE_OFFSET));
            appearanceRawChangeFraction = fraction(rawModerateCount, appearanceBlockCount);
            appearanceMeanLumaDelta = appearanceBlockCount == 0 ? 0.0f
                    : Math.min(1.0f, rawDeltaSum / (255.0f * appearanceBlockCount));
            appearanceStructuralChangeFraction = fraction(
                    structuralChangeCount, appearanceBlockCount);
            appearanceCurrentSupportFraction = fraction(
                    currentSupportCount, appearanceBlockCount);
            appearanceCommonSupportFraction = fraction(
                    commonSupportCount, appearanceBlockCount);
            float diagnosticChange = state.getFloat(CUT_DEPTH_DIAGNOSTICS_BYTE_OFFSET);
            float diagnosticShift = state.getFloat(
                    CUT_DEPTH_DIAGNOSTICS_BYTE_OFFSET + Float.BYTES);
            latestDepthChangeFraction = diagnosticChange >= 0.0f
                    ? diagnosticChange : Float.NaN;
            latestRangeShift = diagnosticShift >= 0.0f ? diagnosticShift : Float.NaN;
            latestInternalCutEvidence = state.getFloat(
                    CUT_DEPTH_DIAGNOSTICS_BYTE_OFFSET + 2 * Float.BYTES);
            geometryChangeBaseline = state.getFloat(
                    CUT_DEPTH_DIAGNOSTICS_BYTE_OFFSET + 3 * Float.BYTES);
            validRawFraction = expectedSamples > 0
                    ? Math.min(1.0f, (float) validRawSamples / expectedSamples) : 0.0f;
        }

        private static float fraction(long value, int total) {
            return total <= 0 ? 0.0f : Math.min(1.0f, (float) value / total);
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
        /** V2 has fixed pop, so the retired adaptive classifier is never present. */
        public boolean hasAdaptivePopClassification() {
            return false;
        }
        public float getPopStrength() { return popStrength; }
        public float getPopRatio() { return popRatio; }
        public float getChangeFraction() { return changeFraction; }
        /** 0..1 internal evidence, or 2 when an appearance proposal was selected. */
        public float getHardCutEvidence() { return hardCutEvidence; }
        public float getShotRawMean() { return shotRawMean; }
        public float getCurrentRawMean() { return currentRawMean; }
        public boolean isCurrentDepthValid() { return currentDepthValid; }
        public boolean didHistoryAdvance() { return historyAdvanced; }
        public boolean isCurrentGeometryReady() {
            return currentDepthValid && stereoProfileInitialized;
        }
        public boolean wasAppearanceProposalSelected() {
            return (cutDecisionFlags & CUT_DECISION_SELECTED_APPEARANCE) != 0;
        }
        /** @deprecated This was always an appearance proposal, not an accepted cut request. */
        @Deprecated
        public boolean wasExternalCutRequested() { return wasAppearanceProposalSelected(); }
        public boolean wasHardCut() { return hardCut; }
        public boolean isGeometryCutArmed() { return geometryCutArmed; }
        public int getSceneAge() { return sceneAge; }
        public int getFrameNumber() { return frameNumber; }
        /** Cumulative since the last temporal reset, so sparse polling cannot miss cuts. */
        public long getHardCutCount() { return hardCutCount; }
        public long getAppearanceProposalCount() { return appearanceProposalCount; }
        /** @deprecated Use {@link #getAppearanceProposalCount()}. */
        @Deprecated
        public long getExternalCutRequestCount() { return appearanceProposalCount; }
        public long getAcceptedAppearanceCutCount() { return acceptedAppearanceCutCount; }
        public long getAcceptedGeometryCutCount() { return acceptedGeometryCutCount; }
        public long getAcceptedStructurelessEntryCutCount() {
            return acceptedStructurelessEntryCutCount;
        }
        public int getAppearanceBlockCount() { return appearanceBlockCount; }
        public float getAppearanceRawChangeFraction() { return appearanceRawChangeFraction; }
        public float getAppearanceMeanLumaDelta() { return appearanceMeanLumaDelta; }
        public float getAppearanceStructuralChangeFraction() {
            return appearanceStructuralChangeFraction;
        }
        public float getAppearanceCurrentSupportFraction() {
            return appearanceCurrentSupportFraction;
        }
        public float getAppearanceCommonSupportFraction() {
            return appearanceCommonSupportFraction;
        }
        public int getAppearanceDetectorFlags() { return appearanceDetectorFlags; }
        public int getCutDecisionFlags() { return cutDecisionFlags; }
        /** Monotonic sequence of the latched accepted/proposed/vetoed/rejected cut decision. */
        public long getCutEventSequence() { return cutEventSequence; }
        public float getLatestDepthChangeFraction() { return latestDepthChangeFraction; }
        public float getLatestRangeShift() { return latestRangeShift; }
        public float getLatestInternalCutEvidence() { return latestInternalCutEvidence; }
        public float getGeometryChangeBaseline() { return geometryChangeBaseline; }
        public long getEmptyRawFrameCount() { return emptyRawFrameCount; }
        public long getCollapsedRawFrameCount() { return collapsedRawFrameCount; }
    }
}
