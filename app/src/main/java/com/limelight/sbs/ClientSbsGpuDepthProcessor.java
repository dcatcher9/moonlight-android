package com.limelight.sbs;

import static com.limelight.sbs.ClientSbsGlCompute.checkGlError;
import static com.limelight.sbs.ClientSbsGlCompute.createComputeProgram;

import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;

import com.limelight.LimeLog;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GLES 3.1 implementation of the client SBS raw-depth validation and adaptive-camera pipeline.
 *
 * <p>The processor consumes LiteRT GPU's packed Float32 model-output SSBO. All per-pixel work
 * remains on the GPU and the result is exposed as a source-aligned raw-depth texture. A one-texel
 * RGBA32F texture publishes the source-time adaptive camera used by reprojection, so production need not
 * map a buffer or wait for the CPU. {@link #pollHealthSnapshot()} provides low-frequency,
 * asynchronous health telemetry without waiting for the GPU.</p>
 *
 * <p>Instances are GL-context-owned and thread-confined. Create, process, reset, and release on
 * the owning GLES thread. Producer completion for the model-output SSBO must already be ordered
 * in the current context (or waited with an EGL/GL fence) before calling
 * {@link #processRendererOwned}.</p>
 */
public final class ClientSbsGpuDepthProcessor implements AutoCloseable {
    /**
     * Renderer profile texture layout: one RGBA32F texel holding the active raw zero, the current
     * raw mean, the inverse raw amplitude and the profile-ready flag.
     */
    public static final int PROFILE_TEXEL_COUNT = 1;

    /** The newest real inference produced a complete, finite, nonnegative raw field. */
    public static final int FRAME_STATE_CURRENT_DEPTH_VALID = 1 << 0;
    /**
     * That field is also noncollapsed with valid P05/P95 bounds and, once the profile has been
     * published, an adaptive zero/scale valid for its source observation.
     */
    public static final int FRAME_STATE_CURRENT_V2_VALID = 1 << 1;

    private static final int RAW_SSBO_BINDING = 0;
    private static final int RAW_STATS_SSBO_BINDING = 1;
    private static final int STATE_SSBO_BINDING = 3;
    private static final int RAW_DEPTH_IMAGE_BINDING = 0;
    private static final int PROFILE_IMAGE_BINDING = 1;

    /** std430 offset of {@code rawGroupMoments}: the scalar words and histogram, 16-aligned. */
    static final int RAW_STATS_HEADER_BYTES = alignTo16(Integer.BYTES
            * (ClientSbsGpuDepthShaders.RAW_STATS_SCALAR_WORDS
            + ClientSbsGpuDepthShaders.HISTOGRAM_BINS));
    private static final int RAW_GROUP_MOMENT_BYTES = 4 * Float.BYTES;
    // std430 byte offsets of the ProcessorState fields in ClientSbsGpuDepthShaders.STATE. The
    // three leading uints are packed; each vec4/uvec4 that follows is 16-byte aligned.
    static final int FRAME_STATE_BYTE_OFFSET = 0;
    static final int INCOMPLETE_FIELD_COUNT_BYTE_OFFSET = 4;
    static final int COLLAPSED_FIELD_COUNT_BYTE_OFFSET = 8;
    static final int V2_CAMERA_ACTIVE_ZERO_BYTE_OFFSET = 16;
    static final int V2_CAMERA_CURRENT_MEAN_BYTE_OFFSET = 20;
    static final int V2_CAMERA_VALID_COUNT_BYTE_OFFSET = 24;
    static final int V2_CAMERA_INITIALIZED_BYTE_OFFSET = 28;
    static final int ADAPTIVE_CAMERA_BYTE_OFFSET = 32;
    static final int CAMERA_CLOCK_BYTE_OFFSET = 48;
    static final int STATE_BYTES = 64;
    /** Word index of {@code frameState}, read by the near-reuse history commit. */
    static final int FRAME_STATE_WORD_INDEX = FRAME_STATE_BYTE_OFFSET / Integer.BYTES;
    private static final int HEALTH_READBACK_SLOT_COUNT = 3;
    /** Visible Stats cadence; no health copies run while its consumer is disabled. */
    private static final int HEALTH_SAMPLE_INTERVAL_FRAMES = 5;
    /** LiteRT exposes two immutable output SSBOs that alternate as inference slots. */
    private static final int RAW_BUFFER_VALIDATION_CACHE_SIZE = 2;
    /** Bounded error drain after a failed diagnostic readback. */
    private static final int HEALTH_FAILURE_ERROR_DRAIN_LIMIT = 8;

    private final int tensorWidth;
    private final int tensorHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final float contentScaleX;
    private final float contentScaleY;
    /** Per-graph offline calibration is the minimum raw amplitude, not a fixed live divisor. */
    private final float calibratedRawScale;
    private final boolean removeReflectedPadding;
    private final int tensorPixelCount;
    private final int rawGroupCount;
    private final EGLContext ownerContext;
    private final Result result = new Result();
    private final HealthSnapshot healthSnapshot = new HealthSnapshot();
    private final HealthReadbackSlot[] healthReadbackSlots =
            new HealthReadbackSlot[HEALTH_READBACK_SLOT_COUNT];
    /** Scratch storage for thread-confined GL integer queries. */
    private final int[] integerScratch = new int[1];

    private boolean linearDepthFiltering;
    private int profileTexture;
    private int rawDepthTexture;
    private int rawStatsBuffer;
    private int stateBuffer;

    private int resetStatsProgram;
    private int rawMinMaxProgram;
    private int rawHistogramProgram;
    private int resolveRawProgram;
    private int writeRawDepthProgram;
    private int publishProfileProgram;
    private int resetStateProgram;

    private RawUniforms rawMinMaxUniforms;
    private RawUniforms rawHistogramUniforms;
    private RawUniforms writeRawDepthUniforms;
    private int resolveRawExpectedPixelCountUniform;
    private int resolveRawGroupCountUniform;
    private int publishSourceTimestampUniform;
    private int publishRawCoordinateScaleUniform;

    private long frameSequence;
    private final int[] validatedRawBuffers = new int[RAW_BUFFER_VALIDATION_CACHE_SIZE];
    private final int[] validatedRawBufferSizes = new int[RAW_BUFFER_VALIDATION_CACHE_SIZE];
    private int nextRawBufferValidationCacheSlot;
    private int healthGeneration = 1;
    /** Requests one prompt sample after construction/reset, before the periodic cadence begins. */
    private boolean healthSampleRequested = true;
    /** Delivered by polling so optional staging failures never fail depth production. */
    private RuntimeException pendingHealthReadbackFailure;
    /** The renderer disables this when Stats is hidden; isolated tooling can request samples. */
    private boolean healthSamplingEnabled = true;
    /** First frame after construction/reset keeps per-dispatch diagnostics; steady state batches. */
    private boolean validateDispatchesIndividually = true;
    private boolean released;

    /**
     * Creates a processor for either a reflected, aspect-fit tensor or a direct full-frame tensor.
     * Direct rectangular models already produce source-aligned depth and therefore retain their
     * complete output; a reflected tensor has its padding removed here.
     */
    public ClientSbsGpuDepthProcessor(int tensorWidth, int tensorHeight, float sourceAspect,
                                      boolean removeReflectedPadding,
                                      float calibratedRawScale) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("tensor dimensions must be positive");
        }
        if (!Float.isFinite(sourceAspect) || sourceAspect <= 0.0f) {
            throw new IllegalArgumentException("sourceAspect must be finite and positive");
        }
        if (!Float.isFinite(calibratedRawScale) || calibratedRawScale <= 0.0f) {
            throw new IllegalArgumentException("calibratedRawScale must be finite and positive");
        }
        EGLContext currentContext = ClientSbsGlCompute.requireCurrentGles31Context(
                "Client SBS GPU depth requires GLES 3.1: ");

        this.tensorWidth = tensorWidth;
        this.tensorHeight = tensorHeight;
        this.removeReflectedPadding = removeReflectedPadding;
        this.calibratedRawScale = calibratedRawScale;
        contentScaleX = removeReflectedPadding ? Math.min(1.0f, sourceAspect) : 1.0f;
        contentScaleY = removeReflectedPadding ? Math.min(1.0f, 1.0f / sourceAspect) : 1.0f;
        outputWidth = Math.max(1, Math.round(tensorWidth * contentScaleX));
        outputHeight = Math.max(1, Math.round(tensorHeight * contentScaleY));
        tensorPixelCount = Math.multiplyExact(tensorWidth, tensorHeight);
        rawGroupCount = Math.multiplyExact(groups(outputWidth), groups(outputHeight));
        ownerContext = currentContext;
        for (int slot = 0; slot < healthReadbackSlots.length; slot++) {
            healthReadbackSlots[slot] = new HealthReadbackSlot();
        }

        try {
            initializePrograms();
            initializeStorage();
            initializeUniforms();
            resetTemporalState();
            LimeLog.info("Client SBS GPU depth: tensor=" + tensorWidth + "x" + tensorHeight
                    + " output=" + outputWidth + "x" + outputHeight
                    + " texture=R32F"
                    + " filtering=" + (linearDepthFiltering ? "linear" : "nearest")
                    + " postprocess=6-dispatch-raw-v2");
        } catch (RuntimeException error) {
            releaseGlResourcesUnchecked();
            released = true;
            throw error;
        }
    }

    /** GPU state whose frame-state word says whether the newest real inference is V2-valid. */
    public int getFrameStateBufferId() {
        assertOwnerContext();
        return stateBuffer;
    }

    /**
     * Renderer hot path. The caller owns the GL context state, so this avoids synchronous state
     * queries and leaves program, SSBO and image bindings neutral with texture unit zero active. Real
     * inference must carry the matched decoder/color owner's source timestamp.
     */
    public Result processRendererOwned(int packedFloatSsbo, int rawByteOffset,
                                       int rawPixelStrideBytes, long sourceTimestampNs) {
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

        try {
            validateRawBuffer(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes);
            bindSsbo(RAW_SSBO_BINDING, packedFloatSsbo);
            bindSsbo(RAW_STATS_SSBO_BINDING, rawStatsBuffer);
            bindSsbo(STATE_SSBO_BINDING, stateBuffer);

            dispatch(resetStatsProgram, 1, 1, 1, "reset depth scratch stats");
            shaderStorageBarrier();

            GLES20.glUseProgram(rawMinMaxProgram);
            GLES30.glUniform1ui(rawMinMaxUniforms.rawByteOffset, rawByteOffset);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "raw min/max");
            shaderStorageBarrier();

            GLES20.glUseProgram(rawHistogramProgram);
            GLES30.glUniform1ui(rawHistogramUniforms.rawByteOffset, rawByteOffset);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "raw histogram");
            shaderStorageBarrier();

            // Validity must be resolved before the raw field is written for reprojection.
            GLES20.glUseProgram(resolveRawProgram);
            dispatchCurrent(1, 1, 1, "resolve raw field");
            shaderStorageBarrier();

            GLES20.glUseProgram(writeRawDepthProgram);
            GLES30.glUniform1ui(writeRawDepthUniforms.rawByteOffset, rawByteOffset);
            GLES31.glBindImageTexture(RAW_DEPTH_IMAGE_BINDING, rawDepthTexture, 0,
                    false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "write raw depth");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
            if (validateDispatchesIndividually) {
                checkGlError("raw depth barrier");
            }

            GLES20.glUseProgram(publishProfileProgram);
            long sourceTimestampUs = sourceTimestampUs(sourceTimestampNs);
            GLES30.glUniform2ui(publishSourceTimestampUniform,
                    (int) sourceTimestampUs, (int) (sourceTimestampUs >>> 32));
            GLES31.glBindImageTexture(PROFILE_IMAGE_BINDING, profileTexture, 0, false, 0,
                    GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F);
            dispatchCurrent(1, 1, 1, "publish depth profile");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
            // Steady state performs one driver error query for the complete six-dispatch
            // pipeline. The first frame after every reset retains stage-specific diagnostics.
            checkGlError("profile publication barrier");
            validateDispatchesIndividually = false;

            frameSequence++;
            result.depthTexture = rawDepthTexture;
            result.profileTexture = profileTexture;
            result.frameSequence = frameSequence;
            result.validFrame = true;
            scheduleHealthReadbackIfDue();
            return result;
        } finally {
            unbindWorkingState();
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glUseProgram(0);
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        }
    }

    /** Resets validity, health and adaptive-camera state without reallocating GL objects. */
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
            frameSequence = 0L;
            result.depthTexture = 0;
            result.profileTexture = profileTexture;
            result.frameSequence = 0L;
            result.validFrame = false;
            healthGeneration = healthGeneration == Integer.MAX_VALUE ? 1 : healthGeneration + 1;
            healthSampleRequested = true;
            pendingHealthReadbackFailure = null;
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
        if (!healthSamplingEnabled) {
            return null;
        }
        try {
            if (pendingHealthReadbackFailure != null) {
                RuntimeException failure = pendingHealthReadbackFailure;
                pendingHealthReadbackFailure = null;
                throw failure;
            }
            return pollHealthSnapshotInternal();
        } catch (RuntimeException failure) {
            invalidateHealthReadbacks();
            throw failure;
        }
    }

    private HealthSnapshot pollHealthSnapshotInternal() {
        HealthReadbackSlot newestReady = null;
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            if (slot.fence == 0L) {
                continue;
            }
            int waitResult = GLES30.glClientWaitSync(slot.fence, 0, 0L);
            if (waitResult == GLES30.GL_WAIT_FAILED) {
                throw new IllegalStateException("Client SBS GPU health fence wait failed");
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
            try {
                requireValidHealthReadback(healthSnapshot,
                        GLES30.glUnmapBuffer(GLES30.GL_COPY_READ_BUFFER));
            } finally {
                GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, oldCopyReadBuffer);
                recycleHealthReadbackSlot(newestReady);
            }
        }
        recycleCompletedStaleHealthReadbacks(newestReady);
        checkGlError("poll GPU depth health");
        return healthSnapshot;
    }

    /**
     * Enables the GPU-to-CPU health ring only while Stats consumes it. Disabling retires every queued staging fence; re-enabling requests a fresh
     * sample, so a newly opened panel never maps stale pre-boundary state.
     */
    public void setHealthSamplingEnabled(boolean enabled) {
        assertOwnerContext();
        if (healthSamplingEnabled == enabled) {
            return;
        }
        healthSamplingEnabled = enabled;
        healthSampleRequested = enabled;
        if (!enabled) {
            for (HealthReadbackSlot slot : healthReadbackSlots) {
                recycleHealthReadbackSlot(slot);
            }
            healthSnapshot.reset();
            pendingHealthReadbackFailure = null;
        }
    }

    public int getOutputWidth() {
        return outputWidth;
    }

    public int getOutputHeight() {
        return outputHeight;
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
                ClientSbsGpuDepthShaders.RESET_RAW_STATS);
        rawMinMaxProgram = createComputeProgram("raw min/max",
                ClientSbsGpuDepthShaders.rawMinMax(removeReflectedPadding));
        rawHistogramProgram = createComputeProgram("raw histogram",
                ClientSbsGpuDepthShaders.rawHistogram(removeReflectedPadding));
        resolveRawProgram = createComputeProgram("resolve raw field",
                ClientSbsGpuDepthShaders.RESOLVE_RAW_FIELD);
        // R32F is the only portable GLES 3.1 single-channel image-store format.
        writeRawDepthProgram = createComputeProgram("write raw depth",
                ClientSbsGpuDepthShaders.writeRawDepth(removeReflectedPadding));
        publishProfileProgram = createComputeProgram("publish adaptive V2 camera",
                ClientSbsGpuDepthShaders.PUBLISH_PROFILE);
        resetStateProgram = createComputeProgram("reset depth state",
                ClientSbsGpuDepthShaders.RESET_STATE);

        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        linearDepthFiltering = extensions != null
                && extensions.contains("GL_OES_texture_float_linear");
    }

    private void initializeStorage() {
        rawStatsBuffer = createBuffer(Math.addExact(RAW_STATS_HEADER_BYTES,
                Math.multiplyExact(rawGroupCount, RAW_GROUP_MOMENT_BYTES)));
        stateBuffer = createBuffer(STATE_BYTES);
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            slot.buffer = createBuffer(STATE_BYTES, GLES30.GL_STREAM_READ);
        }
        rawDepthTexture = createTexture(outputWidth, outputHeight, GLES30.GL_R32F,
                linearDepthFiltering ? GLES20.GL_LINEAR : GLES20.GL_NEAREST);
        profileTexture = createTexture(PROFILE_TEXEL_COUNT, 1, GLES30.GL_RGBA32F,
                GLES20.GL_NEAREST);
        checkGlError("create GPU depth storage");
    }

    private void initializeUniforms() {
        rawMinMaxUniforms = new RawUniforms(rawMinMaxProgram, removeReflectedPadding);
        rawHistogramUniforms = new RawUniforms(rawHistogramProgram, removeReflectedPadding);
        writeRawDepthUniforms = new RawUniforms(writeRawDepthProgram, removeReflectedPadding);
        resolveRawExpectedPixelCountUniform = requiredUniform(
                resolveRawProgram, "uExpectedPixelCount");
        resolveRawGroupCountUniform = requiredUniform(resolveRawProgram, "uRawGroupCount");
        publishSourceTimestampUniform = requiredUniform(publishProfileProgram,
                "uSourceTimestampUs");
        publishRawCoordinateScaleUniform = requiredUniform(publishProfileProgram,
                "uRawCoordinateScale");

        // Every program belongs to this fixed-shape processor. Uniforms survive glUseProgram
        // and temporal resets; only source offsets and the source timestamp vary per frame.
        initializeRawUniformConstants(rawMinMaxProgram, rawMinMaxUniforms);
        initializeRawUniformConstants(rawHistogramProgram, rawHistogramUniforms);
        initializeRawUniformConstants(writeRawDepthProgram, writeRawDepthUniforms);
        GLES31.glProgramUniform1i(resolveRawProgram, resolveRawExpectedPixelCountUniform,
                outputWidth * outputHeight);
        GLES31.glProgramUniform1i(resolveRawProgram, resolveRawGroupCountUniform, rawGroupCount);
        GLES31.glProgramUniform1f(publishProfileProgram, publishRawCoordinateScaleUniform,
                calibratedRawScale);
        checkGlError("initialize GPU depth uniforms");
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
        return ClientSbsGlCompute.createTexture2D(width, height, internalFormat, filtering,
                "Unable to allocate client SBS GPU texture");
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

    private void initializeRawUniformConstants(int program, RawUniforms uniforms) {
        GLES31.glProgramUniform1ui(program, uniforms.rawPixelStrideBytes, Float.BYTES);
        GLES31.glProgramUniform2i(program, uniforms.tensorSize, tensorWidth, tensorHeight);
        GLES31.glProgramUniform2i(program, uniforms.outputSize, outputWidth, outputHeight);
        if (uniforms.contentScale >= 0) {
            GLES31.glProgramUniform2f(program, uniforms.contentScale, contentScaleX, contentScaleY);
        }
    }

    private void scheduleHealthReadbackIfDue() {
        if (pendingHealthReadbackFailure != null) {
            return;
        }
        if (!shouldScheduleHealthReadback(
                healthSamplingEnabled, healthSampleRequested,
                frameSequence)) {
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
            scheduleHealthReadback(available);
        } catch (RuntimeException failure) {
            // Production depth has already passed its error check. Retire only diagnostics and
            // deliver the failure through polling's existing renderer backoff on the next poll.
            invalidateHealthReadbacks();
            pendingHealthReadbackFailure = failure;
        }
    }

    private void scheduleHealthReadback(HealthReadbackSlot available) {
        try {
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
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

    static boolean shouldScheduleHealthReadback(boolean enabled, boolean sampleRequested,
                                                long frameSequence) {
        return enabled && (sampleRequested || frameSequence % HEALTH_SAMPLE_INTERVAL_FRAMES == 0L);
    }

    private void recycleCompletedStaleHealthReadbacks(HealthReadbackSlot except) {
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            if (slot == except || slot.fence == 0L) {
                continue;
            }
            int waitResult = GLES30.glClientWaitSync(slot.fence, 0, 0L);
            if (waitResult == GLES30.GL_WAIT_FAILED) {
                throw new IllegalStateException("Client SBS GPU health fence wait failed");
            }
            if (waitResult == GLES30.GL_ALREADY_SIGNALED
                    || waitResult == GLES30.GL_CONDITION_SATISFIED) {
                recycleHealthReadbackSlot(slot);
            }
        }
    }

    /** The CPU copy has no diagnostic authority when GL reports corrupt mapped storage. */
    static void requireValidHealthReadback(HealthSnapshot snapshot, boolean valid) {
        if (!valid) {
            snapshot.reset();
            throw new IllegalStateException(
                    "Client SBS GPU health staging buffer became invalid while mapped");
        }
    }

    private void invalidateHealthReadbacks() {
        healthSnapshot.reset();
        healthSampleRequested = true;
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            recycleHealthReadbackSlot(slot);
        }
        // Consume errors from the failed diagnostic operation and its cleanup so they cannot
        // contaminate the next valid production dispatch. This runs only on failure.
        ClientSbsGlCompute.drainGlErrors(HEALTH_FAILURE_ERROR_DRAIN_LIMIT);
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

    static int groups(int length) {
        return (length + ClientSbsGpuDepthShaders.WORKGROUP_EDGE - 1)
                / ClientSbsGpuDepthShaders.WORKGROUP_EDGE;
    }

    private static int alignTo16(int bytes) {
        return (bytes + 15) & ~15;
    }

    static long sourceTimestampUs(long sourceTimestampNs) {
        return sourceTimestampNs > 0L ? sourceTimestampNs / 1000L : 0L;
    }

    private static void shaderStorageBarrier() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    private static void bindSsbo(int binding, int buffer) {
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, buffer);
    }

    private void unbindWorkingState() {
        bindSsbo(RAW_SSBO_BINDING, 0);
        bindSsbo(RAW_STATS_SSBO_BINDING, 0);
        bindSsbo(STATE_SSBO_BINDING, 0);
        GLES31.glBindImageTexture(RAW_DEPTH_IMAGE_BINDING, 0, 0, false, 0,
                GLES31.GL_READ_ONLY, GLES30.GL_R32F);
        GLES31.glBindImageTexture(PROFILE_IMAGE_BINDING, 0, 0, false, 0,
                GLES31.GL_READ_ONLY, GLES30.GL_RGBA32F);
    }

    private static int requiredUniform(int program, String name) {
        return ClientSbsGlCompute.requiredUniform(program, name,
                "Required compute uniform was optimized out: ");
    }

    private void assertOwnerContext() {
        ClientSbsGlCompute.assertOwnerContext(released, ownerContext,
                "Client SBS GPU depth processor is released",
                "Client SBS GPU depth used from a different EGL context");
    }

    private int getInteger(int name) {
        return ClientSbsGlCompute.getInteger(name, integerScratch);
    }

    private static long unsignedIntToLong(int value) {
        return value & 0xffffffffL;
    }

    private void releaseGlResourcesUnchecked() {
        int[] programs = {resetStatsProgram, rawMinMaxProgram, rawHistogramProgram,
                resolveRawProgram, writeRawDepthProgram, publishProfileProgram,
                resetStateProgram};
        for (int program : programs) {
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
        }
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            recycleHealthReadbackSlot(slot);
        }
        int[] buffers = {rawStatsBuffer, stateBuffer,
                healthReadbackSlots[0].buffer, healthReadbackSlots[1].buffer,
                healthReadbackSlots[2].buffer};
        GLES30.glDeleteBuffers(buffers.length, buffers, 0);
        int[] textures = {rawDepthTexture, profileTexture};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        clearHandles();
    }

    private void clearHandles() {
        resetStatsProgram = 0;
        rawMinMaxProgram = 0;
        rawHistogramProgram = 0;
        resolveRawProgram = 0;
        writeRawDepthProgram = 0;
        publishProfileProgram = 0;
        resetStateProgram = 0;
        rawStatsBuffer = 0;
        stateBuffer = 0;
        for (HealthReadbackSlot slot : healthReadbackSlots) {
            slot.buffer = 0;
            slot.fence = 0L;
            slot.frameSequence = 0L;
            slot.generation = 0;
        }
        rawDepthTexture = 0;
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
        private long incompleteRawFrameCount;
        private long collapsedRawFrameCount;
        private float validRawFraction;
        private boolean cameraInitialized;
        private float rawZero;
        private float currentRawMean;
        private float rawInverseAmplitude;
        private float targetRawInverseAmplitude;
        private float rawPercentileLow;
        private float rawPercentileHigh;
        private boolean completeRawField;
        private boolean cameraClockArmed;
        private boolean currentDepthValid;

        void reset() {
            frameSequence = 0L;
            expectedRawSamples = 0;
            validRawSamples = 0;
            validRawFraction = 0.0f;
            cameraInitialized = false;
            rawZero = 0.0f;
            currentRawMean = 0.0f;
            rawInverseAmplitude = Float.NaN;
            targetRawInverseAmplitude = Float.NaN;
            rawPercentileLow = Float.NaN;
            rawPercentileHigh = Float.NaN;
            completeRawField = false;
            cameraClockArmed = false;
            currentDepthValid = false;
            incompleteRawFrameCount = 0L;
            collapsedRawFrameCount = 0L;
        }

        void updateFromState(ByteBuffer state, long sampledFrameSequence, int expectedSamples) {
            frameSequence = sampledFrameSequence;
            expectedRawSamples = expectedSamples;
            // v2Camera.z always carries the actual current field's valid-sample count, including
            // incomplete fields.
            validRawSamples = Math.max(0, Math.round(
                    state.getFloat(V2_CAMERA_VALID_COUNT_BYTE_OFFSET)));
            rawZero = state.getFloat(V2_CAMERA_ACTIVE_ZERO_BYTE_OFFSET);
            currentRawMean = state.getFloat(V2_CAMERA_CURRENT_MEAN_BYTE_OFFSET);
            int frameState = state.getInt(FRAME_STATE_BYTE_OFFSET);
            completeRawField = (frameState & FRAME_STATE_CURRENT_DEPTH_VALID) != 0;
            rawInverseAmplitude = positiveOrUnavailable(
                    state.getFloat(ADAPTIVE_CAMERA_BYTE_OFFSET));
            targetRawInverseAmplitude = positiveOrUnavailable(
                    state.getFloat(ADAPTIVE_CAMERA_BYTE_OFFSET + Float.BYTES));
            float p05 = state.getFloat(ADAPTIVE_CAMERA_BYTE_OFFSET + 2 * Float.BYTES);
            float p95 = state.getFloat(ADAPTIVE_CAMERA_BYTE_OFFSET + 3 * Float.BYTES);
            // Missing geometry quantiles are canonical zero. ZipDepth's admitted population is
            // nonnegative; a noncollapsed finite-range histogram has a strictly positive P95 edge.
            boolean percentilesAvailable = completeRawField
                    && Float.isFinite(targetRawInverseAmplitude)
                    && Float.isFinite(p05) && Float.isFinite(p95)
                    && p05 >= 0.0f && p95 > 0.0f && p95 >= p05;
            rawPercentileLow = percentilesAvailable ? p05 : Float.NaN;
            rawPercentileHigh = percentilesAvailable ? p95 : Float.NaN;
            cameraClockArmed = state.getInt(CAMERA_CLOCK_BYTE_OFFSET + 2 * Integer.BYTES) == 1;
            int requiredCurrentFlags = FRAME_STATE_CURRENT_DEPTH_VALID
                    | FRAME_STATE_CURRENT_V2_VALID;
            currentDepthValid = (frameState & requiredCurrentFlags) == requiredCurrentFlags;
            cameraInitialized = state.getFloat(V2_CAMERA_INITIALIZED_BYTE_OFFSET) > 0.5f;
            incompleteRawFrameCount = unsignedIntToLong(
                    state.getInt(INCOMPLETE_FIELD_COUNT_BYTE_OFFSET));
            collapsedRawFrameCount = unsignedIntToLong(
                    state.getInt(COLLAPSED_FIELD_COUNT_BYTE_OFFSET));
            validRawFraction = expectedSamples > 0
                    ? Math.min(1.0f, (float) validRawSamples / expectedSamples) : 0.0f;
        }

        private static float positiveOrUnavailable(float value) {
            return Float.isFinite(value) && value > 0.0f ? value : Float.NaN;
        }

        public long getFrameSequence() { return frameSequence; }
        public int getExpectedRawSamples() { return expectedRawSamples; }
        public int getValidRawSamples() { return validRawSamples; }
        public float getValidRawFraction() { return validRawFraction; }
        /** Whether an adaptive raw camera has been established since the last reset. */
        public boolean isCameraInitialized() { return cameraInitialized; }
        /** Continuously observed raw zero plane. */
        public float getRawZero() { return rawZero; }
        public float getCurrentRawMean() { return currentRawMean; }
        public float getRawInverseAmplitude() { return rawInverseAmplitude; }
        public float getTargetRawInverseAmplitude() { return targetRawInverseAmplitude; }
        public float getRawPercentileLow() { return rawPercentileLow; }
        public float getRawPercentileHigh() { return rawPercentileHigh; }
        public boolean hasCompleteRawField() { return completeRawField; }
        public boolean isCameraClockArmed() { return cameraClockArmed; }
        /** The sampled field passed both the completeness and the V2 validity gates. */
        public boolean isCurrentDepthValid() { return currentDepthValid; }
        public boolean isCurrentGeometryReady() {
            return currentDepthValid && cameraInitialized;
        }
        /** Cumulative fields with a missing, non-finite or negative texel since the last reset. */
        public long getIncompleteRawFrameCount() { return incompleteRawFrameCount; }
        /** Cumulative complete fields rejected as collapsed since the last reset. */
        public long getCollapsedRawFrameCount() { return collapsedRawFrameCount; }
    }
}
