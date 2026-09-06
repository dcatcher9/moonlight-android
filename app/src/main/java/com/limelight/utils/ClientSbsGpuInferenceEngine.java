package com.limelight.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.ParcelFileDescriptor;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Native LiteRT CompiledModel GPU engine with packed Float32 OpenGL tensor buffers.
 *
 * <p>The shared EGL context is created on the renderer thread. Initialization and invocation stay
 * on one inference worker; destruction starts there and a bounded failure may be retried by the
 * next dedicated inference worker after the old context was unbound. The OpenGL buffer names are
 * shared with the renderer context, allowing model input and output to stay GPU-resident.</p>
 */
final class ClientSbsGpuInferenceEngine implements AutoCloseable {
    static final int BUFFER_SLOT_COUNT = 2;

    enum RunDisposition {
        INFER(1),
        REUSE(2);

        final int wireValue;

        RunDisposition(int wireValue) {
            this.wireValue = wireValue;
        }

        static RunDisposition fromNativeValue(int value) {
            for (RunDisposition disposition : values()) {
                if (disposition.wireValue == value) {
                    return disposition;
                }
            }
            throw new IllegalStateException(
                    "Unknown native Client SBS run disposition: " + value);
        }
    }

    private static final boolean NATIVE_BRIDGE_AVAILABLE = loadNativeBridge();
    private static final Object DEFERRED_CLOSES_LOCK = new Object();
    private static final Set<ClientSbsGpuInferenceEngine> DEFERRED_CLOSES = new HashSet<>();
    /** Process-wide guard: compiling a replacement model must never overlap the current one. */
    private static final ProcessModelSlot PROCESS_MODEL_SLOT = new ProcessModelSlot();
    private static final long PROCESS_MODEL_SLOT_WAIT_SECONDS = 10L;
    private static final long PROCESS_MODEL_SLOT_RETRY_MILLIS = 250L;
    private static final String PRODUCTION_COMPILER_CACHE = "client-sbs-litert-gpu";
    private static final String BENCHMARK_COMPILER_CACHE = "client-sbs-benchmark-litert-gpu";

    private long nativeHandle;
    private volatile boolean initialized;
    private boolean initializationStarted;
    private boolean closeStarted;
    private boolean processModelSlotClaimed;
    private volatile long lastAssetVerificationNanos;
    private volatile long lastNativeInitializationNanos;
    private volatile boolean lazyInputPackingSupported;
    // Immutable for one compiled engine. Cache these JNI results once so the renderer's per-frame
    // pack/postprocess path only binds GL objects and never crosses JNI for buffer metadata.
    private final int[] inputBufferIds = new int[BUFFER_SLOT_COUNT];
    private final int[] outputBufferIds = new int[BUFFER_SLOT_COUNT];
    private final int[] inputBufferSizes = new int[BUFFER_SLOT_COUNT];
    private final int[] outputBufferSizes = new int[BUFFER_SLOT_COUNT];
    private final int[] inputPixelStrides = new int[BUFFER_SLOT_COUNT];
    private final int[] outputPixelStrides = new int[BUFFER_SLOT_COUNT];

    private ClientSbsGpuInferenceEngine(long nativeHandle) {
        this.nativeHandle = nativeHandle;
    }

    /** Small separately testable ownership primitive for the process-wide compiled-model slot. */
    static final class ProcessModelSlot {
        private Object owner;

        synchronized void claim(Object candidate) {
            if (candidate == null) {
                throw new NullPointerException("Client SBS model-slot owner must not be null");
            }
            if (owner != null) {
                throw new IllegalStateException(
                        "A Client SBS model is already compiling or GPU-resident");
            }
            owner = candidate;
        }

        synchronized boolean claimWhenAvailable(Object candidate, long timeout, TimeUnit unit)
                throws InterruptedException {
            if (candidate == null) {
                throw new NullPointerException("Client SBS model-slot owner must not be null");
            }
            if (unit == null) {
                throw new NullPointerException("Client SBS model-slot wait unit must not be null");
            }
            if (timeout < 0L) {
                throw new IllegalArgumentException("Client SBS model-slot wait must not be negative");
            }
            if (owner == candidate) {
                throw new IllegalStateException("Client SBS model-slot owner claimed twice");
            }

            long remainingNanos = unit.toNanos(timeout);
            long deadlineNanos = System.nanoTime() + remainingNanos;
            while (owner != null) {
                if (remainingNanos <= 0L) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                remainingNanos = deadlineNanos - System.nanoTime();
            }
            owner = candidate;
            return true;
        }

        synchronized void release(Object candidate) {
            if (owner != candidate) {
                throw new IllegalStateException(
                        "Client SBS model slot released by a non-owner");
            }
            owner = null;
            notifyAll();
        }

        synchronized boolean isClaimed() {
            return owner != null;
        }
    }

    private void claimProcessModelSlot() {
        try {
            boolean claimed = claimModelSlotWithDeferredCloseRetries(
                    PROCESS_MODEL_SLOT,
                    this,
                    PROCESS_MODEL_SLOT_WAIT_SECONDS,
                    TimeUnit.SECONDS,
                    PROCESS_MODEL_SLOT_RETRY_MILLIS,
                    TimeUnit.MILLISECONDS,
                    () -> drainDeferredClosuresOnCurrentWorker());
            if (!claimed) {
                throw new IllegalStateException(
                        "Timed out waiting for the previous Client SBS model to leave the GPU");
            }
            processModelSlotClaimed = true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for the previous Client SBS model to leave the GPU",
                    error);
        }
    }

    static boolean claimModelSlotWithDeferredCloseRetries(
            ProcessModelSlot slot,
            Object candidate,
            long timeout,
            TimeUnit timeoutUnit,
            long retryInterval,
            TimeUnit retryUnit,
            Runnable deferredCloseDrainer) throws InterruptedException {
        if (slot == null || timeoutUnit == null || retryUnit == null
                || deferredCloseDrainer == null) {
            throw new NullPointerException("Client SBS model-slot retry arguments must not be null");
        }
        if (timeout < 0L || retryInterval <= 0L) {
            throw new IllegalArgumentException(
                    "Client SBS model-slot timeout must be non-negative and retry positive");
        }

        long remainingNanos = timeoutUnit.toNanos(timeout);
        long retryNanos = retryUnit.toNanos(retryInterval);
        if (retryNanos <= 0L) {
            throw new IllegalArgumentException("Client SBS model-slot retry interval is too small");
        }
        long deadlineNanos = System.nanoTime() + remainingNanos;
        while (remainingNanos > 0L) {
            long waitNanos = Math.min(remainingNanos, retryNanos);
            if (slot.claimWhenAvailable(candidate, waitNanos, TimeUnit.NANOSECONDS)) {
                return true;
            }

            // Native destruction can need several non-blocking fence polls. Keep retrying it for
            // the same bounded interval used to wait for the process-wide compiled-model slot.
            deferredCloseDrainer.run();
            if (slot.claimWhenAvailable(candidate, 0L, TimeUnit.NANOSECONDS)) {
                return true;
            }
            remainingNanos = deadlineNanos - System.nanoTime();
        }
        return false;
    }

    private void releaseProcessModelSlot() {
        if (processModelSlotClaimed) {
            PROCESS_MODEL_SLOT.release(this);
            processModelSlotClaimed = false;
        }
    }

    static String compilerCacheDirectoryName(ClientSbsModelManifest manifest) {
        return manifest.getId() + '-'
                + manifest.getAssetSha256().substring(0, 12) + '-'
                + manifest.getInputWidth() + 'x' + manifest.getInputHeight() + '-'
                + manifest.getGpuExecutionPolicy().getCompilerCacheSuffix();
    }

    /** Must be called while the renderer's GLES context is current. */
    static ClientSbsGpuInferenceEngine createShared() {
        if (!NATIVE_BRIDGE_AVAILABLE) {
            return null;
        }
        try {
            long handle = nativeCreateSharedContext();
            if (handle == 0L) {
                LimeLog.warning("Client SBS zero-copy GPU context unavailable");
                return null;
            }
            return new ClientSbsGpuInferenceEngine(handle);
        } catch (Throwable error) {
            LimeLog.warning("Client SBS zero-copy GPU context creation failed: "
                    + error.getMessage());
            return null;
        }
    }

    /** Must be called once on the inference worker thread. */
    void initialize(Context context, ClientSbsModelManifest manifest) throws IOException {
        if (!isZipDepthManifest(manifest)) {
            throw new IllegalArgumentException(
                    "Production Client SBS inference supports only ZipDepth Base");
        }
        initialize(context, context.getAssets(), manifest, false);
    }

    /**
     * Physical-device benchmark hook. Test assets are staged in dedicated target-app code-cache
     * namespaces because instrumentation executes under the target UID. The benchmark class must
     * call {@link #clearBenchmarkCaches(Context)} after all engines are closed.
     */
    void initializeForBenchmark(Context runtimeContext, AssetManager modelAssets,
                                ClientSbsModelManifest manifest) throws IOException {
        initialize(runtimeContext, modelAssets, manifest, true);
    }

    /**
     * Instrumentation-only hook for a checkpoint graph pushed beside the target app's external
     * files. This never participates in production model selection or APK asset staging.
     */
    void initializeExternalForBenchmark(Context runtimeContext, File modelFile,
                                        ClientSbsModelManifest manifest) throws IOException {
        initializeExternalForBenchmark(runtimeContext, modelFile, manifest, false);
    }

    void initializeExternalForBenchmark(Context runtimeContext, File modelFile,
                                        ClientSbsModelManifest manifest,
                                        boolean enableDiagnosticProfiling) throws IOException {
        if (!BuildConfig.DEBUG) {
            throw new SecurityException("External Client SBS checkpoints require a debug build");
        }
        if (!beginInitialization()) {
            return;
        }
        manifest.validateFloatGpuCheckpointContract();

        File checkpointRoot = runtimeContext.getExternalFilesDir("client-sbs-checkpoints");
        if (checkpointRoot == null) {
            throw new IOException("Client SBS checkpoint external-files directory unavailable");
        }
        File canonicalRoot = checkpointRoot.getCanonicalFile();
        File canonicalModel = modelFile.getCanonicalFile();
        if (!canonicalRoot.equals(canonicalModel.getParentFile())) {
            throw new SecurityException("Client SBS checkpoint must be a direct child of "
                    + canonicalRoot);
        }
        if (!canonicalModel.isFile()) {
            throw new IOException("Client SBS checkpoint does not exist: " + canonicalModel);
        }

        long verificationStartedNs = System.nanoTime();
        if (!ClientSbsModelAssetCache.digestMatches(
                canonicalModel, manifest.getAssetSha256())) {
            throw new IOException("Client SBS checkpoint SHA-256 mismatch: " + canonicalModel);
        }
        lastAssetVerificationNanos = Math.max(0L,
                System.nanoTime() - verificationStartedNs);
        initializePreparedModel(runtimeContext, canonicalModel, manifest,
                BENCHMARK_COMPILER_CACHE, enableDiagnosticProfiling);
    }

    private void initialize(Context runtimeContext, AssetManager modelAssets,
                            ClientSbsModelManifest manifest, boolean benchmarkCache)
            throws IOException {
        if (!beginInitialization()) {
            return;
        }
        manifest.validateFloatGpuRendererContract();
        long verificationStartedNs = System.nanoTime();
        String modelCacheName = benchmarkCache
                ? ClientSbsModelAssetCache.BENCHMARK_MODEL_CACHE
                : ClientSbsModelAssetCache.PRODUCTION_MODEL_CACHE;
        String compilerCacheName = benchmarkCache
                ? BENCHMARK_COMPILER_CACHE : PRODUCTION_COMPILER_CACHE;
        File modelFile = ClientSbsModelAssetCache.prepareVerifiedModelFile(
                runtimeContext, modelAssets, manifest, modelCacheName, true);
        lastAssetVerificationNanos = Math.max(0L,
                System.nanoTime() - verificationStartedNs);

        initializePreparedModel(runtimeContext, modelFile, manifest, compilerCacheName,
                false);
    }

    private synchronized boolean beginInitialization() {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Native GPU engine is closed");
        }
        if (closeStarted) {
            throw new IllegalStateException("Native GPU engine teardown has started");
        }
        if (initialized) {
            return false;
        }
        if (initializationStarted) {
            throw new IllegalStateException("Native GPU engine initialization already started");
        }
        // Make one eager non-blocking teardown pass, but do not fail a rapid reconnect merely
        // because the old GPU fence needs another poll. The model-slot claim below owns the
        // bounded drain/wait/retry policy and preserves the max-one-model invariant.
        drainDeferredClosuresOnCurrentWorker();
        // A deferred-close drain must never retire this engine, but recheck before granting the
        // one-shot initialization token so an anomalous lifecycle cannot reach JNI with handle 0.
        if (nativeHandle == 0L || closeStarted) {
            throw new IllegalStateException("Native GPU engine closed during deferred teardown");
        }
        initializationStarted = true;
        return true;
    }

    private void initializePreparedModel(Context runtimeContext, File modelFile,
                                         ClientSbsModelManifest manifest,
                                         String compilerCacheName,
                                         boolean enableDiagnosticProfiling) throws IOException {
        File codeCacheRoot = runtimeContext.getCodeCacheDir();
        File cacheRoot = new File(codeCacheRoot, compilerCacheName);
        // Compute precision is part of the key, so a model can never reuse an artifact compiled
        // under a different policy. Both policies let LiteRT choose internal tensor storage.
        if (PRODUCTION_COMPILER_CACHE.equals(compilerCacheName)
                && isZipDepthManifest(manifest)) {
            pruneRetiredProductionCompilerCaches(codeCacheRoot, cacheRoot);
        }
        File cacheDirectory = new File(cacheRoot, compilerCacheDirectoryName(manifest));
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            LimeLog.warning("Unable to create LiteRT GPU cache directory");
        }

        long nativeInitializationStartedNs = System.nanoTime();
        try {
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                    modelFile, ParcelFileDescriptor.MODE_READ_ONLY)) {
                // Claim immediately before native compilation. A failed or partially completed
                // initialization retains the slot until nativeDestroy() succeeds, preventing a
                // second model from overlapping quarantined driver/LiteRT allocations.
                claimProcessModelSlot();
                boolean success = nativeInitialize(
                        nativeHandle,
                        descriptor.getFd(),
                        0L,
                        modelFile.length(),
                        runtimeContext.getApplicationInfo().nativeLibraryDir,
                        cacheDirectory.getAbsolutePath(),
                        manifest.getGpuExecutionPolicy().forcesFp32Compute(),
                        manifest.hasDynamicSpatialShape(),
                        manifest.getInputWidth(), manifest.getInputHeight(),
                        manifest.getInputTensor().getChannels(),
                        manifest.getOutputWidth(), manifest.getOutputHeight(),
                        manifest.getOutputTensor().getChannels(),
                        enableDiagnosticProfiling);
                if (!success) {
                    throw new IllegalStateException(nativeGetLastError(nativeHandle));
                }
            }
        } finally {
            lastNativeInitializationNanos = Math.max(0L,
                    System.nanoTime() - nativeInitializationStartedNs);
        }

        int nativeSlotCount = nativeGetBufferSlotCount();
        if (nativeSlotCount != BUFFER_SLOT_COUNT) {
            throw new IllegalStateException("Native Client SBS slot count mismatch: "
                    + nativeSlotCount + "/" + BUFFER_SLOT_COUNT);
        }

        for (int slot = 0; slot < BUFFER_SLOT_COUNT; slot++) {
            inputBufferIds[slot] = nativeGetInputBufferId(nativeHandle, slot);
            outputBufferIds[slot] = nativeGetOutputBufferId(nativeHandle, slot);
            inputBufferSizes[slot] = nativeGetInputBufferSize(nativeHandle, slot);
            outputBufferSizes[slot] = nativeGetOutputBufferSize(nativeHandle, slot);
            inputPixelStrides[slot] = nativeGetInputPixelStrideBytes(nativeHandle, slot);
            outputPixelStrides[slot] = nativeGetOutputPixelStrideBytes(nativeHandle, slot);
        }

        int expectedInputBytes = manifest.getInputTensor().getByteSize();
        int expectedOutputBytes = manifest.getOutputTensor().getByteSize();
        int expectedInputStride = Math.multiplyExact(
                manifest.getInputTensor().getChannels(), Float.BYTES);
        int expectedOutputStride = Math.multiplyExact(
                manifest.getOutputTensor().getChannels(), Float.BYTES);
        for (int slot = 0; slot < BUFFER_SLOT_COUNT; slot++) {
            if (getInputBufferId(slot) == 0 || getOutputBufferId(slot) == 0
                    || getInputBufferSize(slot) < expectedInputBytes
                    || getOutputBufferSize(slot) < expectedOutputBytes
                    || getInputPixelStrideBytes(slot) != expectedInputStride
                    || getOutputPixelStrideBytes(slot) != expectedOutputStride) {
                throw new IllegalStateException("Invalid packed tensor allocation for slot "
                        + slot + ": input=" + getInputBufferId(slot) + "/"
                        + getInputBufferSize(slot) + "/" + expectedInputBytes
                        + " output=" + getOutputBufferId(slot) + "/"
                        + getOutputBufferSize(slot) + "/" + expectedOutputBytes
                        + " strides=" + getInputPixelStrideBytes(slot) + "/"
                        + getOutputPixelStrideBytes(slot));
            }
        }
        lazyInputPackingSupported = nativeSupportsLazyInputPacking(nativeHandle);
        initialized = true;
        LimeLog.info("Client SBS GPU ready: model=" + manifest.getId()
                + " tensor=" + manifest.getInputWidth() + "x" + manifest.getInputHeight()
                + " fully-delegated OpenCL "
                + manifest.getGpuExecutionPolicy().getComputePrecisionLabel()
                + " + packed GL, verify="
                + String.format(java.util.Locale.ROOT, "%.1f ms",
                        lastAssetVerificationNanos / 1_000_000.0)
                + " compile/init=" + String.format(java.util.Locale.ROOT, "%.1f ms",
                        lastNativeInitializationNanos / 1_000_000.0)
                + ", GPU priority hint=Low"
                + ", slots=" + BUFFER_SLOT_COUNT + " input GL buffers="
                + getInputBufferId(0) + ","
                + getInputBufferId(1) + " (" + getInputBufferSize(0)
                + " bytes each), output GL buffers=" + getOutputBufferId(0) + ","
                + getOutputBufferId(1) + " (" + getOutputBufferSize(0)
                + " bytes each), pixel stride=" + getOutputPixelStrideBytes(0));
    }

    /** Deletes only the dedicated benchmark namespaces after every benchmark engine is closed. */
    static boolean clearBenchmarkCaches(Context context) {
        File codeCache = context.getCodeCacheDir();
        return ClientSbsModelAssetCache.clearBenchmarkModelCache(context)
                && deleteExactCodeCacheChild(codeCache, BENCHMARK_COMPILER_CACHE);
    }

    private static boolean deleteExactCodeCacheChild(File codeCache, String childName) {
        File child = new File(codeCache, childName);
        if (!child.exists()) {
            return true;
        }
        try {
            File canonicalRoot = codeCache.getCanonicalFile();
            File canonicalChild = child.getCanonicalFile();
            if (!canonicalRoot.equals(canonicalChild.getParentFile())) {
                LimeLog.warning("Refusing to remove Client SBS benchmark cache outside code cache: "
                        + canonicalChild);
                return false;
            }
            return deleteCacheTree(canonicalChild);
        } catch (IOException error) {
            LimeLog.warning("Unable to resolve Client SBS benchmark cache: "
                    + error.getMessage());
            return false;
        }
    }

    private static boolean deleteCacheTree(File entry) {
        if (entry.isDirectory()) {
            File[] children = entry.listFiles();
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!deleteCacheTree(child)) {
                    return false;
                }
            }
        }
        return !entry.exists() || entry.delete();
    }

    boolean isInitialized() {
        return initialized && nativeHandle != 0L;
    }

    int getInputBufferId(int slotIndex) {
        validateSlotIndex(slotIndex);
        return inputBufferIds[slotIndex];
    }

    int getOutputBufferId(int slotIndex) {
        validateSlotIndex(slotIndex);
        return outputBufferIds[slotIndex];
    }

    int getInputBufferSize(int slotIndex) {
        validateSlotIndex(slotIndex);
        return inputBufferSizes[slotIndex];
    }

    int getOutputBufferSize(int slotIndex) {
        validateSlotIndex(slotIndex);
        return outputBufferSizes[slotIndex];
    }

    int getInputPixelStrideBytes(int slotIndex) {
        validateSlotIndex(slotIndex);
        return inputPixelStrides[slotIndex];
    }

    int getOutputPixelStrideBytes(int slotIndex) {
        validateSlotIndex(slotIndex);
        return outputPixelStrides[slotIndex];
    }

    /**
     * Waits for renderer input and prior output-consumer fences on the GPU, invokes LiteRT, and
     * returns a fence that the renderer can poll before consuming the output SSBO.
     *
     * <p>After all Java validation succeeds, ownership of both nonzero input fences transfers to
     * native code even if invocation fails. Native normally queues each wait and deletes its
     * handle; a fence whose wait itself fails remains retained for guarded teardown. The caller
     * owns the nonzero output-ready fence returned on success. A consumed result must replace it
     * with a new fence submitted after the renderer's output-buffer reads. Only an unread,
     * discarded result may reuse its output-ready fence as the slot's no-op consumption fence.</p>
     */
    long run(int slotIndex, long inputReadyFence, long previousOutputConsumedFence) {
        return run(slotIndex, inputReadyFence, previousOutputConsumedFence,
                false, 0, 0, 0L);
    }

    /**
     * Removes compiler artifacts for retired production model families while retaining every
     * ZipDepth aspect bucket. This runs only during ZipDepth initialization and only beneath the
     * exact app-private production compiler-cache root.
     */
    private static void pruneRetiredProductionCompilerCaches(File codeCacheRoot, File cacheRoot) {
        if (!cacheRoot.isDirectory()) {
            return;
        }
        Set<String> retained = new HashSet<>();
        retained.add(compilerCacheDirectoryName(
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9));
        retained.add(compilerCacheDirectoryName(
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9));
        retained.add(compilerCacheDirectoryName(
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9));

        try {
            File canonicalCodeCache = codeCacheRoot.getCanonicalFile();
            File canonicalCacheRoot = cacheRoot.getCanonicalFile();
            if (!PRODUCTION_COMPILER_CACHE.equals(canonicalCacheRoot.getName())
                    || !canonicalCodeCache.equals(canonicalCacheRoot.getParentFile())) {
                LimeLog.warning("Refusing to prune Client SBS compiler cache outside code cache: "
                        + canonicalCacheRoot);
                return;
            }
            File[] entries = canonicalCacheRoot.listFiles();
            if (entries == null) {
                return;
            }
            for (File entry : entries) {
                if (retained.contains(entry.getName())) {
                    continue;
                }
                if (!deleteCacheTreeWithin(entry, canonicalCacheRoot)) {
                    LimeLog.warning("Unable to prune retired Client SBS compiler cache: "
                            + entry.getName());
                }
            }
        }
        catch (IOException error) {
            LimeLog.warning("Unable to resolve Client SBS compiler cache: "
                    + error.getMessage());
        }
    }

    private static boolean isZipDepthManifest(ClientSbsModelManifest manifest) {
        return manifest == ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9
                || manifest == ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9
                || manifest == ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9;
    }

    /** Refuses links or traversal before recursively deleting one immediate cache child. */
    private static boolean deleteCacheTreeWithin(File entry, File canonicalRoot)
            throws IOException {
        File absoluteEntry = entry.getAbsoluteFile();
        File canonicalEntry = entry.getCanonicalFile();
        if (!absoluteEntry.equals(canonicalEntry)
                || (!canonicalRoot.equals(canonicalEntry.getParentFile())
                && !canonicalEntry.getAbsolutePath().startsWith(
                canonicalRoot.getAbsolutePath() + File.separator))) {
            return false;
        }
        if (canonicalEntry.isDirectory()) {
            File[] children = canonicalEntry.listFiles();
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!deleteCacheTreeWithin(child, canonicalRoot)) {
                    return false;
                }
            }
        }
        return !canonicalEntry.exists() || canonicalEntry.delete();
    }

    /**
     * Runs one authenticated production transaction. A valid near-identical proposal may skip
     * LiteRT, but native still consumes both transferred fences and returns a new completion fence
     * that participates in the same per-slot ownership contract as an inferred output.
     */
    long run(int slotIndex, long inputReadyFence, long previousOutputConsumedFence,
             boolean nearIdenticalCandidate, int decisionBufferId,
             int decisionByteOffset, long decisionToken) {
        return run(slotIndex, inputReadyFence, previousOutputConsumedFence,
                nearIdenticalCandidate, decisionBufferId, decisionByteOffset, decisionToken, 0);
    }

    /** A nonzero model texture defers exact RGB tensor packing to native on INFER only. */
    long run(int slotIndex, long inputReadyFence, long previousOutputConsumedFence,
             boolean nearIdenticalCandidate, int decisionBufferId,
             int decisionByteOffset, long decisionToken, int modelInputTextureId) {
        validateSlotIndex(slotIndex);
        if (!isInitialized()) {
            throw new IllegalStateException("Native GPU engine is not initialized");
        }
        if (inputReadyFence == 0L) {
            throw new IllegalArgumentException(
                    "Client SBS input-ready fence must be nonzero");
        }
        if (nearIdenticalCandidate && (decisionBufferId == 0
                || decisionByteOffset < 0 || (decisionByteOffset & 3) != 0
                || decisionToken == 0L)) {
            throw new IllegalArgumentException(
                    "Near-identical candidate requires a decision buffer, aligned offset, and token");
        }
        long resultFence = nativeRun(nativeHandle, slotIndex, inputReadyFence,
                previousOutputConsumedFence, nearIdenticalCandidate,
                decisionBufferId, decisionByteOffset, decisionToken, modelInputTextureId);
        if (resultFence == 0L) {
            throw new IllegalStateException(nativeGetLastError(nativeHandle));
        }
        return resultFence;
    }

    RunDisposition getLastRunDisposition(int slotIndex) {
        validateSlotIndex(slotIndex);
        if (!isInitialized()) {
            throw new IllegalStateException("Native GPU engine is not initialized");
        }
        return RunDisposition.fromNativeValue(
                nativeGetLastRunDisposition(nativeHandle, slotIndex));
    }

    boolean supportsLazyInputPacking() {
        return isInitialized() && lazyInputPackingSupported;
    }

    /**
     * CPU monotonic wall time inside LiteRtRunCompiledModel() for this slot. LiteRT does not expose
     * the OpenCL event behind its GL interop path, and a GLES timer query cannot safely bracket
     * OpenCL work, so this is intentionally not labelled as pure GPU execution time.
     */
    long getLastLiteRtRunWallNanos(int slotIndex) {
        validateSlotIndex(slotIndex);
        return nativeGetLastLiteRtRunWallNanos(nativeHandle, slotIndex);
    }

    /**
     * CPU monotonic wall time spent validating/reading the candidate's authenticated 32-byte
     * decision record. This is zero for a run that was not a near-identical candidate.
     */
    long getLastNearIdenticalDecisionReadWallNanos(int slotIndex) {
        validateSlotIndex(slotIndex);
        return nativeGetLastNearIdenticalDecisionReadWallNanos(nativeHandle, slotIndex);
    }

    /** GPU-published reason for the most recent candidate's reuse/infer decision. */
    int getLastNearIdenticalDecisionReason(int slotIndex) {
        validateSlotIndex(slotIndex);
        return nativeGetLastNearIdenticalDecisionReason(nativeHandle, slotIndex);
    }

    void startDiagnosticProfiler() {
        if (!BuildConfig.DEBUG || !isInitialized()) {
            throw new IllegalStateException("Diagnostic profiler requires an initialized debug "
                    + "checkpoint engine");
        }
        if (!nativeStartDiagnosticProfiler(nativeHandle)) {
            throw new IllegalStateException(nativeGetLastError(nativeHandle));
        }
    }

    String stopDiagnosticProfilerAndGetReport() {
        if (!BuildConfig.DEBUG || !isInitialized()) {
            throw new IllegalStateException("Diagnostic profiler requires an initialized debug "
                    + "checkpoint engine");
        }
        String report = nativeStopDiagnosticProfilerAndGetReport(nativeHandle);
        if (report == null || report.startsWith("error=")) {
            throw new IllegalStateException(nativeGetLastError(nativeHandle));
        }
        return report;
    }

    long getLastAssetVerificationNanos() {
        return lastAssetVerificationNanos;
    }

    long getLastNativeInitializationNanos() {
        return lastNativeInitializationNanos;
    }

    /** Fixed native scheduling preference, shown in the Stats panel. */
    String getGpuPriorityHintLabel() {
        return "Low";
    }

    private static void validateSlotIndex(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= BUFFER_SLOT_COUNT) {
            throw new IllegalArgumentException("Client SBS buffer slot must be in [0,"
                    + BUFFER_SLOT_COUNT + "): " + slotIndex);
        }
    }

    /**
     * Must run on the inference worker after its final invocation. This no-fence form is only for
     * initialization failure or a caller that has already proved no renderer GPU command can
     * still access either output slot.
     */
    @Override
    public void close() {
        close(0L, 0L, false);
    }

    /**
     * Closes after bounded native CPU waits for each slot's final renderer-consumer fence. Each
     * nonzero fence transfers to native ownership. Equal fences are permitted when one renderer
     * fence covers reads from both slots.
     */
    boolean close(long slotZeroLastConsumerFence, long slotOneLastConsumerFence,
                  boolean rendererFinishConfirmed) {
        return attemptCloseOnCurrentWorker(slotZeroLastConsumerFence,
                slotOneLastConsumerFence, rendererFinishConfirmed);
    }

    /** Retries a bounded close after native already took ownership of the final slot fences. */
    boolean retryCloseOnCurrentWorker(boolean rendererFinishConfirmed) {
        return attemptCloseOnCurrentWorker(0L, 0L, rendererFinishConfirmed);
    }

    /**
     * Native owns close fences after the first call even if its bounded drain times out. Keep this
     * Java object in a process quarantine until a later inference worker can safely retry with no
     * new fences. That avoids losing the only opaque handle while also avoiding teardown on an
     * arbitrary UI/GL thread.
     */
    private synchronized boolean attemptCloseOnCurrentWorker(long slotZeroLastConsumerFence,
                                                             long slotOneLastConsumerFence,
                                                             boolean rendererFinishConfirmed) {
        long handle = nativeHandle;
        if (handle == 0L) {
            if (slotZeroLastConsumerFence != 0L || slotOneLastConsumerFence != 0L) {
                throw new IllegalStateException(
                        "Cannot transfer close fences to an already-closed GPU engine");
            }
            // Defensive only: the normal successful-destroy path releases this first. Keeping it
            // here prevents a malformed retry lifecycle from permanently wedging the process slot.
            releaseProcessModelSlot();
            return true;
        }
        if (closeStarted
                && (slotZeroLastConsumerFence != 0L || slotOneLastConsumerFence != 0L)) {
            throw new IllegalStateException(
                    "Client SBS close fences were already transferred to native teardown");
        }
        boolean firstAttempt = !closeStarted;
        closeStarted = true;
        initialized = false;
        for (int slot = 0; slot < BUFFER_SLOT_COUNT; slot++) {
            inputBufferIds[slot] = 0;
            outputBufferIds[slot] = 0;
            inputBufferSizes[slot] = 0;
            outputBufferSizes[slot] = 0;
            inputPixelStrides[slot] = 0;
            outputPixelStrides[slot] = 0;
        }

        boolean destroyed = nativeDestroy(handle,
                firstAttempt ? slotZeroLastConsumerFence : 0L,
                firstAttempt ? slotOneLastConsumerFence : 0L,
                rendererFinishConfirmed);
        if (destroyed) {
            nativeHandle = 0L;
            releaseProcessModelSlot();
            synchronized (DEFERRED_CLOSES_LOCK) {
                DEFERRED_CLOSES.remove(this);
            }
            return true;
        }
        String error = nativeGetLastError(handle);
        synchronized (DEFERRED_CLOSES_LOCK) {
            DEFERRED_CLOSES.add(this);
        }
        LimeLog.warning("Client SBS GPU teardown deferred: " + error);
        return false;
    }

    /** Runs only at the beginning of a dedicated inference worker generation. */
    private static boolean drainDeferredClosuresOnCurrentWorker() {
        ClientSbsGpuInferenceEngine[] deferred;
        synchronized (DEFERRED_CLOSES_LOCK) {
            if (DEFERRED_CLOSES.isEmpty()) {
                return true;
            }
            deferred = DEFERRED_CLOSES.toArray(new ClientSbsGpuInferenceEngine[0]);
        }
        boolean allClosed = true;
        for (ClientSbsGpuInferenceEngine engine : deferred) {
            // Native remembers a renderer-finish acknowledgement from any earlier attempt. A
            // new inference generation must not invent one for an old failed-run generation.
            allClosed &= engine.retryCloseOnCurrentWorker(false);
        }
        return allClosed;
    }

    private static boolean loadNativeBridge() {
        try {
            // Keep this optional bridge separate from moonlight-core. A LiteRT loader or ABI
            // failure must never prevent Artemis from starting or streaming in non-SBS modes.
            System.loadLibrary("client-sbs-gpu");
            return true;
        } catch (Throwable error) {
            LimeLog.warning("Client SBS native GPU bridge unavailable: " + error.getMessage());
            return false;
        }
    }

    private static native long nativeCreateSharedContext();
    private static native boolean nativeInitialize(long handle, int modelFd,
                                                    long modelOffset, long modelLength,
                                                    String nativeLibraryDir, String cacheDir,
                                                    boolean forceGpuFp32Compute,
                                                    boolean dynamicShape,
                                                    int inputWidth, int inputHeight,
                                                    int inputChannels,
                                                    int outputWidth, int outputHeight,
                                                    int outputChannels,
                                                    boolean enableDiagnosticProfiling);
    private static native long nativeRun(long handle, int slotIndex, long inputReadyFence,
                                         long previousOutputConsumedFence,
                                         boolean nearIdenticalCandidate,
                                         int decisionBufferId, int decisionByteOffset,
                                         long decisionToken, int modelInputTextureId);
    private static native int nativeGetBufferSlotCount();
    private static native boolean nativeSupportsLazyInputPacking(long handle);
    private static native int nativeGetInputBufferId(long handle, int slotIndex);
    private static native int nativeGetOutputBufferId(long handle, int slotIndex);
    private static native int nativeGetInputBufferSize(long handle, int slotIndex);
    private static native int nativeGetOutputBufferSize(long handle, int slotIndex);
    private static native int nativeGetInputPixelStrideBytes(long handle, int slotIndex);
    private static native int nativeGetOutputPixelStrideBytes(long handle, int slotIndex);
    private static native long nativeGetLastLiteRtRunWallNanos(long handle, int slotIndex);
    private static native long nativeGetLastNearIdenticalDecisionReadWallNanos(
            long handle, int slotIndex);
    private static native int nativeGetLastNearIdenticalDecisionReason(
            long handle, int slotIndex);
    private static native int nativeGetLastRunDisposition(long handle, int slotIndex);
    private static native boolean nativeStartDiagnosticProfiler(long handle);
    private static native String nativeStopDiagnosticProfilerAndGetReport(long handle);
    private static native String nativeGetLastError(long handle);
    private static native boolean nativeDestroy(long handle,
                                                 long slotZeroLastConsumerFence,
                                                 long slotOneLastConsumerFence,
                                                 boolean rendererFinishConfirmed);
}
