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
 * map a buffer or wait for the CPU. {@link #readProfileSnapshot()} exists for telemetry and golden
 * comparisons only and intentionally introduces a GPU/CPU synchronization point.</p>
 *
 * <p>Instances are GL-context-owned and thread-confined. Create, process, reset, and release on
 * the owning GLES thread. Producer completion for the model-output SSBO must already be ordered
 * in the current context (or waited with an EGL/GL fence) before calling {@link #process}.</p>
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
     *     <li>recenter delta, convergence, pop ratio, profile-ready flag</li>
     *     <li>raw P2, raw P98, edge fraction, change fraction</li>
     *     <li>subject candidate, pop strength, hard-cut flag, scene age</li>
     * </ol>
     */
    public static final int PROFILE_TEXEL_COUNT = 4;
    public static final int PROFILE_TEXEL_STRETCH = 0;
    public static final int PROFILE_TEXEL_STEREO = 1;
    public static final int PROFILE_TEXEL_DIAGNOSTICS = 2;
    public static final int PROFILE_TEXEL_SCENE = 3;

    private static final int RAW_SSBO_BINDING = 0;
    private static final int RAW_STATS_SSBO_BINDING = 1;
    private static final int PROFILE_STATS_SSBO_BINDING = 2;
    private static final int STATE_SSBO_BINDING = 3;
    private static final int DEPTH_IMAGE_BINDING = 0;
    private static final int PROFILE_IMAGE_BINDING = 1;

    private static final int RAW_STATS_BYTES = 4 * (4 + 256);
    private static final int PROFILE_STATS_BYTES = 4 * (256 + 256 + 4);
    private static final int STATE_BYTES = 6 * 4 * Float.BYTES;

    private final int tensorWidth;
    private final int tensorHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final float contentScaleX;
    private final float contentScaleY;
    private final int tensorPixelCount;
    private final EGLContext ownerContext;
    private final Result result = new Result();
    private final ProfileSnapshot profileSnapshot = new ProfileSnapshot();
    private final int[] depthTextures = new int[2];

    private Precision precision;
    private int depthInternalFormat;
    private boolean linearDepthFiltering;
    private int profileTexture;
    private int rawStatsBuffer;
    private int profileStatsBuffer;
    private int stateBuffer;

    private int resetRawProgram;
    private int rawMinMaxProgram;
    private int rawHistogramProgram;
    private int resolveRawProgram;
    private int temporalProgram;
    private int resetProfileProgram;
    private int accumulateProfileProgram;
    private int resolveProfileProgram;
    private int resetStateProgram;

    private RawUniforms rawMinMaxUniforms;
    private RawUniforms rawHistogramUniforms;
    private RawUniforms temporalRawUniforms;
    private int temporalPreviousUniform;
    private int accumulateCurrentUniform;
    private int accumulatePreviousUniform;
    private int accumulateOutputSizeUniform;
    private int resolveExternalCutUniform;
    private int resolvePixelCountUniform;

    private int currentDepthIndex;
    private long frameSequence;
    private int validatedRawBuffer;
    private int validatedRawBufferSize;
    private boolean released;

    public enum Precision {
        /** Prefer R16F image load/store when the driver exposes it, otherwise use core R32F. */
        AUTO,
        /** Request R16F, with a safe R32F fallback if the extension or shader is unavailable. */
        R16F,
        /** Use the GLES 3.1 core R32F image format. */
        R32F
    }

    /** Creates the fixed 256x256 to 256x144 16:9 production processor. */
    public static ClientSbsGpuDepthProcessor createDefault() {
        return new ClientSbsGpuDepthProcessor(DEFAULT_TENSOR_WIDTH, DEFAULT_TENSOR_HEIGHT,
                16.0f / 9.0f, Precision.AUTO);
    }

    /** Creates a processor whose output removes the model's reflected aspect-ratio padding. */
    public ClientSbsGpuDepthProcessor(int tensorWidth, int tensorHeight, float sourceAspect,
                                      Precision requestedPrecision) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("tensor dimensions must be positive");
        }
        if (!Float.isFinite(sourceAspect) || sourceAspect <= 0.0f) {
            throw new IllegalArgumentException("sourceAspect must be finite and positive");
        }
        if (requestedPrecision == null) {
            throw new NullPointerException("requestedPrecision");
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
        contentScaleX = Math.min(1.0f, sourceAspect);
        contentScaleY = Math.min(1.0f, 1.0f / sourceAspect);
        outputWidth = Math.max(1, Math.round(tensorWidth * contentScaleX));
        outputHeight = Math.max(1, Math.round(tensorHeight * contentScaleY));
        tensorPixelCount = Math.multiplyExact(tensorWidth, tensorHeight);
        ownerContext = currentContext;

        try {
            initializePrograms(requestedPrecision);
            initializeStorage();
            initializeUniforms();
            resetTemporalState();
            LimeLog.info("Client SBS GPU depth: tensor=" + tensorWidth + "x" + tensorHeight
                    + " output=" + outputWidth + "x" + outputHeight
                    + " texture=" + precision
                    + " filtering=" + (linearDepthFiltering ? "linear" : "nearest"));
        } catch (RuntimeException error) {
            releaseGlResourcesUnchecked();
            released = true;
            throw error;
        }
    }

    /**
     * Dispatches one complete depth update. This method does not wait for completion.
     *
     * @param packedFloatSsbo GL buffer containing packed Float32 model output
     * @param rawByteOffset byte offset of the tensor within the buffer
     * @param rawPixelStrideBytes physical bytes between adjacent output values (four for Float32)
     * @param externalSceneCut decoder/color scene-cut signal paired with this depth tensor
     * @return a reused view whose texture IDs are valid in this context
     */
    public Result process(int packedFloatSsbo, int rawByteOffset, int rawPixelStrideBytes,
                          boolean externalSceneCut) {
        return processInternal(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes,
                externalSceneCut, true);
    }

    /**
     * Renderer hot path. The caller owns the GL context state, so this avoids five synchronous
     * state queries and leaves program/buffer bindings neutral with texture unit zero active.
     */
    public Result processRendererOwned(int packedFloatSsbo, int rawByteOffset,
                                       int rawPixelStrideBytes, boolean externalSceneCut) {
        return processInternal(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes,
                externalSceneCut, false);
    }

    private Result processInternal(int packedFloatSsbo, int rawByteOffset,
                                   int rawPixelStrideBytes, boolean externalSceneCut,
                                   boolean preserveGlState) {
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

        int oldProgram = 0;
        int oldActiveTexture = GLES20.GL_TEXTURE0;
        int oldSsbo = 0;
        int oldTexture0 = 0;
        int oldTexture1 = 0;
        if (preserveGlState) {
            oldProgram = getInteger(GLES20.GL_CURRENT_PROGRAM);
            oldActiveTexture = getInteger(GLES20.GL_ACTIVE_TEXTURE);
            oldSsbo = getInteger(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            oldTexture0 = getInteger(GLES20.GL_TEXTURE_BINDING_2D);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            oldTexture1 = getInteger(GLES20.GL_TEXTURE_BINDING_2D);
        }

        int nextDepthIndex = 1 - currentDepthIndex;
        try {
            validateRawBuffer(packedFloatSsbo, rawByteOffset, rawPixelStrideBytes);
            bindSsbo(RAW_SSBO_BINDING, packedFloatSsbo);
            bindSsbo(RAW_STATS_SSBO_BINDING, rawStatsBuffer);
            bindSsbo(PROFILE_STATS_SSBO_BINDING, profileStatsBuffer);
            bindSsbo(STATE_SSBO_BINDING, stateBuffer);

            dispatch(resetRawProgram, 1, 1, 1, "reset raw stats");
            shaderStorageBarrier();

            GLES20.glUseProgram(rawMinMaxProgram);
            applyRawUniforms(rawMinMaxUniforms, rawByteOffset, rawPixelStrideBytes);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "raw min/max");
            shaderStorageBarrier();

            GLES20.glUseProgram(rawHistogramProgram);
            applyRawUniforms(rawHistogramUniforms, rawByteOffset, rawPixelStrideBytes);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "raw histogram");
            shaderStorageBarrier();

            dispatch(resolveRawProgram, 1, 1, 1, "resolve raw range");
            shaderStorageBarrier();

            GLES20.glUseProgram(temporalProgram);
            applyRawUniforms(temporalRawUniforms, rawByteOffset, rawPixelStrideBytes);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextures[currentDepthIndex]);
            GLES20.glUniform1i(temporalPreviousUniform, 0);
            GLES31.glBindImageTexture(DEPTH_IMAGE_BINDING, depthTextures[nextDepthIndex], 0,
                    false, 0, GLES31.GL_WRITE_ONLY, depthInternalFormat);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1, "temporal depth");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT
                    | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
            checkGlError("temporal barrier");

            dispatch(resetProfileProgram, 1, 1, 1, "reset profile stats");
            shaderStorageBarrier();

            GLES20.glUseProgram(accumulateProfileProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextures[nextDepthIndex]);
            GLES20.glUniform1i(accumulateCurrentUniform, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextures[currentDepthIndex]);
            GLES20.glUniform1i(accumulatePreviousUniform, 1);
            GLES20.glUniform2i(accumulateOutputSizeUniform, outputWidth, outputHeight);
            dispatchCurrent(groups(outputWidth), groups(outputHeight), 1,
                    "accumulate depth profile");
            shaderStorageBarrier();

            GLES20.glUseProgram(resolveProfileProgram);
            GLES20.glUniform1i(resolveExternalCutUniform, externalSceneCut ? 1 : 0);
            GLES20.glUniform1i(resolvePixelCountUniform, outputWidth * outputHeight);
            GLES31.glBindImageTexture(PROFILE_IMAGE_BINDING, profileTexture, 0, false, 0,
                    GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F);
            dispatchCurrent(1, 1, 1, "resolve depth profile");
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
            checkGlError("profile publication barrier");

            currentDepthIndex = nextDepthIndex;
            frameSequence++;
            result.depthTexture = depthTextures[currentDepthIndex];
            result.profileTexture = profileTexture;
            result.profileStateBuffer = stateBuffer;
            result.frameSequence = frameSequence;
            result.validFrame = true;
            return result;
        } finally {
            unbindWorkingState();
            if (preserveGlState) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oldTexture0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oldTexture1);
                GLES20.glActiveTexture(oldActiveTexture);
                GLES20.glUseProgram(oldProgram);
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, oldSsbo);
            }
            else {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                GLES20.glUseProgram(0);
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            }
        }
    }

    /** Resets all temporal/range/profile state without reallocating GL objects. */
    public void resetTemporalState() {
        assertOwnerContext();
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
            result.depthTexture = 0;
            result.profileTexture = profileTexture;
            result.profileStateBuffer = stateBuffer;
            result.frameSequence = 0L;
            result.validFrame = false;
        } finally {
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, STATE_SSBO_BINDING, 0);
            GLES31.glBindImageTexture(PROFILE_IMAGE_BINDING, 0, 0, false, 0,
                    GLES31.GL_READ_ONLY, GLES30.GL_RGBA32F);
            GLES20.glUseProgram(oldProgram);
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, oldSsbo);
        }
    }

    /**
     * Maps 96 bytes of state for telemetry or CPU golden comparison. Do not call every frame in
     * production; the map waits for preceding GPU work.
     */
    public ProfileSnapshot readProfileSnapshot() {
        assertOwnerContext();
        int oldSsbo = getInteger(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, stateBuffer);
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, STATE_BYTES,
                GLES30.GL_MAP_READ_BIT);
        if (!(mapped instanceof ByteBuffer)) {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, oldSsbo);
            throw new IllegalStateException("Unable to map client SBS GPU profile state");
        }
        try {
            ByteBuffer state = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
            profileSnapshot.frameRangeLow = state.getFloat(0);
            profileSnapshot.frameRangeHigh = state.getFloat(4);
            profileSnapshot.rangeLow = state.getFloat(8);
            profileSnapshot.rangeHigh = state.getFloat(12);
            profileSnapshot.stretchLow = state.getFloat(16);
            profileSnapshot.stretchHigh = state.getFloat(20);
            profileSnapshot.stretchInverseRange = state.getFloat(24);
            profileSnapshot.subjectCandidate = state.getFloat(28);
            profileSnapshot.subjectDepth = state.getFloat(32);
            profileSnapshot.recenterDelta = state.getFloat(36);
            profileSnapshot.convergence = state.getFloat(40);
            profileSnapshot.edgeFraction = state.getFloat(44);
            profileSnapshot.changeFraction = state.getFloat(48);
            profileSnapshot.popStrength = state.getFloat(52);
            profileSnapshot.popRatio = state.getFloat(56);
            profileSnapshot.rangeInitialized = state.getInt(64) != 0;
            profileSnapshot.initialized = state.getInt(68) != 0;
            profileSnapshot.firstValidFrame = state.getInt(72) != 0;
            profileSnapshot.hardCut = state.getInt(76) != 0;
            profileSnapshot.sceneAge = state.getInt(80);
            profileSnapshot.depthCutState = state.getInt(84);
            profileSnapshot.validRawSamples = state.getInt(88);
            profileSnapshot.frameNumber = state.getInt(92);
        } finally {
            if (!GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)) {
                LimeLog.warning("Client SBS GPU profile state became invalid while mapped");
            }
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, oldSsbo);
        }
        checkGlError("read GPU profile state");
        return profileSnapshot;
    }

    /** Binds the renderer-facing 4x1 RGBA32F profile texture to {@code textureUnit}. */
    public void bindProfileTexture(int textureUnit) {
        assertOwnerContext();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + textureUnit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, profileTexture);
    }

    /** Binds persistent std430 state for a GLES 3.1 renderer that consumes the SSBO directly. */
    public void bindProfileStateBuffer(int bindingPoint) {
        assertOwnerContext();
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, bindingPoint, stateBuffer);
    }

    public int getDepthTextureId() {
        return result.validFrame ? depthTextures[currentDepthIndex] : 0;
    }

    public int getProfileTextureId() {
        return profileTexture;
    }

    public int getProfileStateBufferId() {
        return stateBuffer;
    }

    public int getOutputWidth() {
        return outputWidth;
    }

    public int getOutputHeight() {
        return outputHeight;
    }

    public Precision getPrecision() {
        return precision;
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

    private void initializePrograms(Precision requestedPrecision) {
        resetRawProgram = createComputeProgram("reset raw stats",
                ClientSbsGpuDepthShaders.RESET_RAW_STATS);
        rawMinMaxProgram = createComputeProgram("raw min/max",
                ClientSbsGpuDepthShaders.RAW_MIN_MAX);
        rawHistogramProgram = createComputeProgram("raw histogram",
                ClientSbsGpuDepthShaders.RAW_HISTOGRAM);
        resolveRawProgram = createComputeProgram("resolve raw range",
                ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE);
        resetProfileProgram = createComputeProgram("reset profile stats",
                ClientSbsGpuDepthShaders.RESET_PROFILE_STATS);
        accumulateProfileProgram = createComputeProgram("accumulate depth profile",
                ClientSbsGpuDepthShaders.ACCUMULATE_PROFILE);
        resolveProfileProgram = createComputeProgram("resolve depth profile",
                ClientSbsGpuDepthShaders.RESOLVE_PROFILE);
        resetStateProgram = createComputeProgram("reset depth state",
                ClientSbsGpuDepthShaders.RESET_STATE);

        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        boolean formattedHalfImage = extensions != null
                && extensions.contains("GL_EXT_shader_image_load_formatted");
        if (requestedPrecision != Precision.R32F && formattedHalfImage) {
            try {
                temporalProgram = createComputeProgram("R16F temporal depth",
                        ClientSbsGpuDepthShaders.temporalFilter(true));
                precision = Precision.R16F;
                depthInternalFormat = GLES30.GL_R16F;
            } catch (RuntimeException halfError) {
                LimeLog.warning("Client SBS R16F compute unavailable; using R32F: "
                        + halfError.getMessage());
            }
        }
        if (temporalProgram == 0) {
            temporalProgram = createComputeProgram("R32F temporal depth",
                    ClientSbsGpuDepthShaders.temporalFilter(false));
            precision = Precision.R32F;
            depthInternalFormat = GLES30.GL_R32F;
        }

        boolean floatLinear = extensions != null
                && extensions.contains("GL_OES_texture_float_linear");
        // Half-float filtering is core on the devices that expose formatted half-float images;
        // R32F linear filtering still requires OES_texture_float_linear.
        linearDepthFiltering = precision == Precision.R16F || floatLinear;
    }

    private void initializeStorage() {
        rawStatsBuffer = createBuffer(RAW_STATS_BYTES);
        profileStatsBuffer = createBuffer(PROFILE_STATS_BYTES);
        stateBuffer = createBuffer(STATE_BYTES);
        depthTextures[0] = createTexture(outputWidth, outputHeight, depthInternalFormat,
                linearDepthFiltering ? GLES20.GL_LINEAR : GLES20.GL_NEAREST);
        depthTextures[1] = createTexture(outputWidth, outputHeight, depthInternalFormat,
                linearDepthFiltering ? GLES20.GL_LINEAR : GLES20.GL_NEAREST);
        profileTexture = createTexture(PROFILE_TEXEL_COUNT, 1, GLES30.GL_RGBA32F,
                GLES20.GL_NEAREST);
        checkGlError("create GPU depth storage");
    }

    private void initializeUniforms() {
        rawMinMaxUniforms = new RawUniforms(rawMinMaxProgram);
        rawHistogramUniforms = new RawUniforms(rawHistogramProgram);
        temporalRawUniforms = new RawUniforms(temporalProgram);
        temporalPreviousUniform = requiredUniform(temporalProgram, "uPreviousDepth");
        accumulateCurrentUniform = requiredUniform(accumulateProfileProgram, "uCurrentDepth");
        accumulatePreviousUniform = requiredUniform(accumulateProfileProgram, "uPreviousDepth");
        accumulateOutputSizeUniform = requiredUniform(accumulateProfileProgram, "uOutputSize");
        resolveExternalCutUniform = requiredUniform(resolveProfileProgram, "uExternalSceneCut");
        resolvePixelCountUniform = requiredUniform(resolveProfileProgram, "uPixelCount");
    }

    private int createBuffer(int bytes) {
        int[] buffer = new int[1];
        GLES30.glGenBuffers(1, buffer, 0);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0]);
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES30.GL_DYNAMIC_DRAW);
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
        if (validatedRawBuffer != rawBuffer) {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, rawBuffer);
            int[] size = new int[1];
            GLES30.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER,
                    GLES20.GL_BUFFER_SIZE, size, 0);
            checkGlError("inspect model output SSBO");
            validatedRawBuffer = rawBuffer;
            validatedRawBufferSize = size[0];
        }
        long required = (long) rawByteOffset
                + (long) tensorPixelCount * rawPixelStrideBytes;
        if (required > validatedRawBufferSize) {
            throw new IllegalArgumentException("Model output SSBO is " + validatedRawBufferSize
                    + " bytes; depth tensor requires " + required);
        }
    }

    private void applyRawUniforms(RawUniforms uniforms, int rawByteOffset,
                                  int rawPixelStrideBytes) {
        GLES30.glUniform1ui(uniforms.rawByteOffset, rawByteOffset);
        GLES30.glUniform1ui(uniforms.rawPixelStrideBytes, rawPixelStrideBytes);
        GLES20.glUniform2i(uniforms.tensorSize, tensorWidth, tensorHeight);
        GLES20.glUniform2i(uniforms.outputSize, outputWidth, outputHeight);
        GLES20.glUniform2f(uniforms.contentScale, contentScaleX, contentScaleY);
    }

    private void dispatch(int program, int groupsX, int groupsY, int groupsZ, String stage) {
        GLES20.glUseProgram(program);
        dispatchCurrent(groupsX, groupsY, groupsZ, stage);
    }

    private void dispatchCurrent(int groupsX, int groupsY, int groupsZ, String stage) {
        GLES31.glDispatchCompute(groupsX, groupsY, groupsZ);
        checkGlError(stage);
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

    private static int getInteger(int name) {
        int[] value = new int[1];
        GLES20.glGetIntegerv(name, value, 0);
        return value[0];
    }

    private static void checkGlError(String stage) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(stage + " failed with GL error 0x"
                    + Integer.toHexString(error));
        }
    }

    private void releaseGlResourcesUnchecked() {
        int[] programs = {resetRawProgram, rawMinMaxProgram, rawHistogramProgram,
                resolveRawProgram, temporalProgram, resetProfileProgram,
                accumulateProfileProgram, resolveProfileProgram, resetStateProgram};
        for (int program : programs) {
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
        }
        int[] buffers = {rawStatsBuffer, profileStatsBuffer, stateBuffer};
        GLES30.glDeleteBuffers(buffers.length, buffers, 0);
        int[] textures = {depthTextures[0], depthTextures[1], profileTexture};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        clearHandles();
    }

    private void clearHandles() {
        resetRawProgram = 0;
        rawMinMaxProgram = 0;
        rawHistogramProgram = 0;
        resolveRawProgram = 0;
        temporalProgram = 0;
        resetProfileProgram = 0;
        accumulateProfileProgram = 0;
        resolveProfileProgram = 0;
        resetStateProgram = 0;
        rawStatsBuffer = 0;
        profileStatsBuffer = 0;
        stateBuffer = 0;
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

        RawUniforms(int program) {
            rawByteOffset = requiredUniform(program, "uRawByteOffset");
            rawPixelStrideBytes = requiredUniform(program, "uRawPixelStrideBytes");
            tensorSize = requiredUniform(program, "uTensorSize");
            outputSize = requiredUniform(program, "uOutputSize");
            contentScale = requiredUniform(program, "uContentScale");
        }
    }

    /** Allocation-stable GPU result view. */
    public static final class Result {
        private int depthTexture;
        private int profileTexture;
        private int profileStateBuffer;
        private long frameSequence;
        private boolean validFrame;

        public int getDepthTextureId() {
            return depthTexture;
        }

        public int getProfileTextureId() {
            return profileTexture;
        }

        public int getProfileStateBufferId() {
            return profileStateBuffer;
        }

        public long getFrameSequence() {
            return frameSequence;
        }

        public boolean isValidFrame() {
            return validFrame;
        }
    }

    /** Mutable telemetry view populated only by {@link #readProfileSnapshot()}. */
    public static final class ProfileSnapshot {
        private boolean rangeInitialized;
        private boolean initialized;
        private boolean firstValidFrame;
        private boolean hardCut;
        private int sceneAge;
        private int depthCutState;
        private int validRawSamples;
        private int frameNumber;
        private float frameRangeLow;
        private float frameRangeHigh;
        private float rangeLow;
        private float rangeHigh;
        private float stretchLow;
        private float stretchHigh;
        private float stretchInverseRange;
        private float subjectCandidate;
        private float subjectDepth;
        private float recenterDelta;
        private float convergence;
        private float edgeFraction;
        private float changeFraction;
        private float popStrength;
        private float popRatio;

        public boolean isRangeInitialized() { return rangeInitialized; }
        public boolean isInitialized() { return initialized; }
        public boolean isFirstValidFrame() { return firstValidFrame; }
        public boolean wasHardCut() { return hardCut; }
        public int getSceneAge() { return sceneAge; }
        public boolean isDepthCutArmed() { return depthCutState > 0; }
        public int getValidRawSamples() { return validRawSamples; }
        public int getFrameNumber() { return frameNumber; }
        public float getFrameRangeLow() { return frameRangeLow; }
        public float getFrameRangeHigh() { return frameRangeHigh; }
        public float getRangeLow() { return rangeLow; }
        public float getRangeHigh() { return rangeHigh; }
        public float getStretchLow() { return stretchLow; }
        public float getStretchHigh() { return stretchHigh; }
        public float getStretchInverseRange() { return stretchInverseRange; }
        public float getSubjectCandidate() { return subjectCandidate; }
        public float getSubjectDepth() { return subjectDepth; }
        public float getRecenterDelta() { return recenterDelta; }
        public float getConvergence() { return convergence; }
        public float getEdgeFraction() { return edgeFraction; }
        public float getChangeFraction() { return changeFraction; }
        public float getPopStrength() { return popStrength; }
        public float getPopRatio() { return popRatio; }
    }
}
