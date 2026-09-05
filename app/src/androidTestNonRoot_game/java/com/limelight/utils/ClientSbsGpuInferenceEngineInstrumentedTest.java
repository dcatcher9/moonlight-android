package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Physical-device smoke test for packed Float32 GL I/O with LiteRT GPU execution. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsGpuInferenceEngineInstrumentedTest {
    private static final String TAG = "ClientSbsGpuSmoke";
    private static final String BENCHMARK_TAG = "ClientSbsGpuBench";
    private static final String BISECT_TAG = "ClientSbsDav2Bisect";
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final int PACKED_RGB_FLOAT_PIXEL_BYTES = 3 * Float.BYTES;
    private static final int PACKED_DEPTH_FLOAT_PIXEL_BYTES = Float.BYTES;
    private static final int PHWC4_FP16_PIXEL_BYTES = 4 * Short.BYTES;
    private static final float PHWC4_OUTPUT_SENTINEL = -1024.0f;
    private static final int BENCHMARK_WARMUP_RUNS = 20;
    private static final int BENCHMARK_MEASURED_RUNS = 100;
    /**
     * Explicit device-only capability probe for LiteRT direct external FP16 PHWC4 buffer storage.
     * Normal suites skip this test; run it with {@code -e direct_phwc4_probe true}. Production
     * remains packed NHWC with automatic internal storage.
     */
    @Test
    public void benchmarkOnlyDirectExternalPhwc4MatchesPacked() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue("Direct external PHWC4 is an explicit instrumentation-only probe; pass "
                        + "-e direct_phwc4_probe true",
                "true".equalsIgnoreCase(arguments.getString("direct_phwc4_probe", "false")));
        int warmupRuns = positiveArgument(arguments, "warmups", 10);
        int measuredRuns = positiveArgument(arguments, "runs", 30);
        double maximumNormalizedRmse = nonNegativeDoubleArgument(
                arguments, "phwc4_max_nrmse", 0.02);
        double minimumCosine = boundedDoubleArgument(
                arguments, "phwc4_min_cosine", 0.999, -1.0, 1.0);

        Context runtimeContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AssetManager modelAssets = runtimeContext.getAssets();
        ClientSbsModelManifest manifest = ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9;
        PowerManager powerManager = (PowerManager) runtimeContext.getSystemService(
                Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager == null ? null
                : powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "Artemis:ClientSbsPhwc4Probe");
        if (wakeLock != null) {
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(TimeUnit.MINUTES.toMillis(3));
        }

        ExternalIoProbeRun packedRun = null;
        ExternalIoProbeRun directRun = null;
        assertTrue("Stale benchmark caches must be removed before the PHWC4 probe",
                ClientSbsGpuInferenceEngine.clearBenchmarkCaches(runtimeContext));
        try (EglFixture egl = EglFixture.create()) {
            packedRun = runExternalIoProbeLeg(runtimeContext, modelAssets, egl, manifest,
                    false, warmupRuns, measuredRuns);
            directRun = runExternalIoProbeLeg(runtimeContext, modelAssets, egl, manifest,
                    true, warmupRuns, measuredRuns);
        } finally {
            try {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } finally {
                assertTrue("PHWC4 probe caches must be removed",
                        ClientSbsGpuInferenceEngine.clearBenchmarkCaches(runtimeContext));
            }
        }

        assertNotNull(packedRun);
        assertNotNull(directRun);
        DepthComparison comparison = DepthComparison.compare(
                directRun.output, packedRun.output);
        Log.i(BENCHMARK_TAG, "directExternalPhwc4Probe comparison model="
                + manifest.getId() + " packedVsDirect=" + comparison
                + " packedRunWallMs=" + summarizeNanos(packedRun.liteRtRunNanos)
                + " directRunWallMs=" + summarizeNanos(directRun.liteRtRunNanos));
        assertEquals("Every packed/direct depth value must form a finite pair",
                manifest.getOutputWidth() * manifest.getOutputHeight(), comparison.compared);
        assertTrue("Direct external PHWC4 NRMSE exceeds " + maximumNormalizedRmse
                        + ": " + comparison,
                comparison.normalizedRmse <= maximumNormalizedRmse);
        assertTrue("Direct external PHWC4 cosine is below " + minimumCosine
                        + ": " + comparison,
                comparison.cosineSimilarity >= minimumCosine);
    }

    @Test
    public void zipDepthBase672x384RunsWithPackedGlBuffers() throws Exception {
        assertZipDepthBucket(
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9,
                "zipdepth-base-static-672x384-fp16weights.tflite.model",
                "6296d5c2e4f857fd551d854ebf4dd2ab2462c0d7372d526bf0a7463718b8b6d1",
                672, 384);
    }

    @Test
    public void zipDepthBase896x384RunsWithPackedGlBuffers() throws Exception {
        assertZipDepthBucket(
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9,
                "zipdepth-base-static-896x384-fp16weights.tflite.model",
                "31467ab0cd187b74c65b3b20f4850973309d120519b587610e3dd3e27b72df4a",
                896, 384);
    }

    @Test
    public void zipDepthBase928x384RunsWithPackedGlBuffers() throws Exception {
        assertZipDepthBucket(
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9,
                "zipdepth-base-static-928x384-fp16weights.tflite.model",
                "169d5e8802bea9aac839df6acb4a8dd8e92a53728ea6f4e39a4baca453fd34cc",
                928, 384);
    }

    /**
     * Runs an externally pushed, output-rewired DA-V2 graph at both delegate precisions. Invoke
     * this test directly with instrumentation arguments; it is deliberately absent from normal
     * device suites so generated checkpoint models never enter the APK or production path.
     */
    @Test
    public void externallyPushedCheckpointFp16VsFp32() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeExternalDiagnosticArgumentsPresent(arguments,
                "externallyPushedCheckpointFp16VsFp32",
                "checkpoint", "sha256", "input_shape", "output_shape");
        String checkpointName = requiredArgument(arguments, "checkpoint");
        String checkpointSha256 = requiredArgument(arguments, "sha256");
        int[] inputShape = parseShape(requiredArgument(arguments, "input_shape"));
        int[] outputShape = parseShape(requiredArgument(arguments, "output_shape"));
        int warmupRuns = positiveArgument(arguments, "warmups", 2);
        int measuredRuns = positiveArgument(arguments, "runs", 5);
        // Intermediate checkpoints have different numeric ranges, so these defaults are
        // deliberately looser than the validated final-depth parity and remain overrideable.
        double maximumNormalizedRmse = nonNegativeDoubleArgument(
                arguments, "max_nrmse", 0.02);
        double minimumCosine = boundedDoubleArgument(
                arguments, "min_cosine", 0.999, -1.0, 1.0);
        double maximumAbsoluteError = nonNegativeDoubleArgument(
                arguments, "max_abs", 0.5);
        String precision = arguments.getString("precision", "both");
        String order = arguments.getString("order", "fp16-fp32");
        String outputPrefix = arguments.getString("output_prefix");
        assertTrue("precision must be fp16, fp32, or both",
                precision.equals("fp16") || precision.equals("fp32")
                        || precision.equals("both"));
        assertTrue("order must be fp16-fp32 or fp32-fp16",
                order.equals("fp16-fp32") || order.equals("fp32-fp16"));

        Context runtimeContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File checkpointRoot = runtimeContext.getExternalFilesDir("client-sbs-checkpoints");
        assertNotNull("Target external-files directory must be available", checkpointRoot);
        File checkpoint = new File(checkpointRoot, checkpointName);
        assertExternalModelPresent("externallyPushedCheckpointFp16VsFp32", checkpoint);
        String inputFileName = arguments.getString("input_file");
        File inputTensor = inputFileName == null || inputFileName.trim().isEmpty()
                ? null : new File(checkpointRoot, inputFileName.trim());
        if (inputTensor != null) {
            assertTrue("Externally pushed input tensor is missing: "
                            + inputTensor.getAbsolutePath(),
                    inputTensor.isFile());
        }
        PowerManager powerManager = (PowerManager) runtimeContext.getSystemService(
                Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager == null ? null
                : powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "Artemis:ClientSbsDav2Bisect");
        if (wakeLock != null) {
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(TimeUnit.MINUTES.toMillis(3));
        }

        CheckpointRun fp16 = null;
        CheckpointRun fp32 = null;
        try (EglFixture egl = EglFixture.create()) {
            String[] policies;
            if (precision.equals("both")) {
                policies = order.equals("fp16-fp32")
                        ? new String[] {"fp16", "fp32"}
                        : new String[] {"fp32", "fp16"};
            } else {
                policies = new String[] {precision};
            }
            for (String policy : policies) {
                ClientSbsModelManifest.GpuExecutionPolicy executionPolicy =
                        policy.equals("fp16")
                                ? ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16
                                : ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP32;
                ClientSbsModelManifest manifest =
                        ClientSbsModelManifest.createCheckpointBenchmarkManifest(
                                "dav2-checkpoint-" + checkpointName + '-' + policy,
                                checkpointSha256, inputShape, outputShape, executionPolicy);
                CheckpointRun result = runExternalCheckpoint(
                        runtimeContext, powerManager, egl, checkpoint, manifest,
                        inputTensor, warmupRuns, measuredRuns);
                if (policy.equals("fp16")) {
                    fp16 = result;
                } else {
                    fp32 = result;
                }
            }
        } finally {
            try {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } finally {
                assertTrue("Checkpoint benchmark caches must be removed",
                        ClientSbsGpuInferenceEngine.clearBenchmarkCaches(runtimeContext));
            }
        }

        int expectedElements = tensorElementCount(outputShape);
        if (fp16 != null) {
            assertCompleteFiniteTensor("FP16 checkpoint", fp16.output, expectedElements);
            writeOptionalTensorSnapshot(checkpointRoot, outputPrefix, "fp16", fp16.output);
        }
        if (fp32 != null) {
            assertCompleteFiniteTensor("FP32 checkpoint", fp32.output, expectedElements);
            writeOptionalTensorSnapshot(checkpointRoot, outputPrefix, "fp32", fp32.output);
        }
        if (fp16 != null && fp32 != null) {
            TensorComparison comparison = TensorComparison.compare(fp16.output, fp32.output);
            Log.i(BISECT_TAG, "comparison checkpoint=" + checkpointName
                    + " fp16VsFp32=" + comparison);
            assertEquals("Every checkpoint output element must form a finite FP16/FP32 pair",
                    expectedElements, comparison.compared);
            assertTrue("Checkpoint FP16-vs-FP32 NRMSE exceeds "
                            + maximumNormalizedRmse + ": " + comparison,
                    comparison.normalizedRmse <= maximumNormalizedRmse);
            assertTrue("Checkpoint FP16-vs-FP32 cosine is below "
                            + minimumCosine + ": " + comparison,
                    comparison.cosineSimilarity >= minimumCosine);
            assertTrue("Checkpoint FP16-vs-FP32 maximum absolute error exceeds "
                            + maximumAbsoluteError + ": " + comparison,
                    comparison.maximumAbsoluteError <= maximumAbsoluteError);
        }
    }

    /**
     * Compares two externally pushed graphs in FP32 through the same production GL/OpenCL bridge.
     * This catches graph-rewrite drift independently from the expected FP16 rounding difference.
     */
    @Test
    public void externallyPushedModelsFp32Parity() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeExternalDiagnosticArgumentsPresent(arguments,
                "externallyPushedModelsFp32Parity",
                "reference", "reference_sha256", "candidate", "candidate_sha256",
                "input_shape", "output_shape");
        String referenceName = requiredArgument(arguments, "reference");
        String referenceSha256 = requiredArgument(arguments, "reference_sha256");
        String candidateName = requiredArgument(arguments, "candidate");
        String candidateSha256 = requiredArgument(arguments, "candidate_sha256");
        int[] inputShape = parseShape(requiredArgument(arguments, "input_shape"));
        int[] outputShape = parseShape(requiredArgument(arguments, "output_shape"));
        int warmupRuns = positiveArgument(arguments, "warmups", 2);
        int measuredRuns = positiveArgument(arguments, "runs", 3);
        double maximumNormalizedRmse = nonNegativeDoubleArgument(
                arguments, "max_nrmse", 0.002);
        double minimumCosine = boundedDoubleArgument(
                arguments, "min_cosine", 0.9999, -1.0, 1.0);

        Context runtimeContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File checkpointRoot = runtimeContext.getExternalFilesDir("client-sbs-checkpoints");
        assertNotNull("Target external-files directory must be available", checkpointRoot);
        File reference = new File(checkpointRoot, referenceName);
        File candidate = new File(checkpointRoot, candidateName);
        assertExternalModelPresent("externallyPushedModelsFp32Parity reference", reference);
        assertExternalModelPresent("externallyPushedModelsFp32Parity candidate", candidate);
        ClientSbsModelManifest referenceManifest =
                ClientSbsModelManifest.createCheckpointBenchmarkManifest(
                        "dav2-reference-" + referenceName,
                        referenceSha256, inputShape, outputShape,
                        ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP32);
        ClientSbsModelManifest candidateManifest =
                ClientSbsModelManifest.createCheckpointBenchmarkManifest(
                        "dav2-candidate-" + candidateName,
                        candidateSha256, inputShape, outputShape,
                        ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP32);
        PowerManager powerManager = (PowerManager) runtimeContext.getSystemService(
                Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager == null ? null
                : powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "Artemis:ClientSbsDav2Parity");
        if (wakeLock != null) {
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(TimeUnit.MINUTES.toMillis(3));
        }

        CheckpointRun referenceRun;
        CheckpointRun candidateRun;
        try (EglFixture egl = EglFixture.create()) {
            referenceRun = runExternalCheckpoint(runtimeContext, powerManager, egl,
                    reference, referenceManifest, null, warmupRuns, measuredRuns);
            candidateRun = runExternalCheckpoint(runtimeContext, powerManager, egl,
                    candidate, candidateManifest, null, warmupRuns, measuredRuns);
        } finally {
            try {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } finally {
                assertTrue("Model parity benchmark caches must be removed",
                        ClientSbsGpuInferenceEngine.clearBenchmarkCaches(runtimeContext));
            }
        }

        TensorComparison comparison = TensorComparison.compare(
                candidateRun.output, referenceRun.output);
        Log.i(BISECT_TAG, "comparison candidate=" + candidateName
                + " reference=" + referenceName
                + " fp32VsFp32=" + comparison);
        assertEquals("Every output element must be finite in both graphs",
                outputShape[0] * outputShape[1] * outputShape[2] * outputShape[3],
                comparison.compared);
        assertTrue("FP32 graph rewrite NRMSE exceeds " + maximumNormalizedRmse
                        + ": " + comparison,
                comparison.normalizedRmse <= maximumNormalizedRmse);
        assertTrue("FP32 graph rewrite cosine is below " + minimumCosine
                        + ": " + comparison,
                comparison.cosineSimilarity >= minimumCosine);
    }

    /** Captures one intrusive ML Drift OpenCL kernel profile on a disposable engine. */
    @Test
    public void externallyPushedModelOpenClProfile() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeExternalDiagnosticArgumentsPresent(arguments,
                "externallyPushedModelOpenClProfile",
                "checkpoint", "sha256", "input_shape", "output_shape");
        String checkpointName = requiredArgument(arguments, "checkpoint");
        String checkpointSha256 = requiredArgument(arguments, "sha256");
        int[] inputShape = parseShape(requiredArgument(arguments, "input_shape"));
        int[] outputShape = parseShape(requiredArgument(arguments, "output_shape"));
        String precision = arguments.getString("precision", "fp32");
        assertTrue("precision must be fp16 or fp32",
                precision.equals("fp16") || precision.equals("fp32"));
        ClientSbsModelManifest.GpuExecutionPolicy executionPolicy = precision.equals("fp16")
                ? ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16
                : ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP32;
        ClientSbsModelManifest manifest =
                ClientSbsModelManifest.createCheckpointBenchmarkManifest(
                        "dav2-profile-" + checkpointName + '-' + precision,
                        checkpointSha256, inputShape, outputShape, executionPolicy);

        Context runtimeContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File checkpointRoot = runtimeContext.getExternalFilesDir("client-sbs-checkpoints");
        assertNotNull("Target external-files directory must be available", checkpointRoot);
        File checkpoint = new File(checkpointRoot, checkpointName);
        assertExternalModelPresent("externallyPushedModelOpenClProfile", checkpoint);
        PowerManager powerManager = (PowerManager) runtimeContext.getSystemService(
                Context.POWER_SERVICE);
        try (EglFixture egl = EglFixture.create()) {
            runExternalProfile(runtimeContext, powerManager, egl, checkpoint, manifest);
        } finally {
            assertTrue("Profile benchmark caches must be removed",
                    ClientSbsGpuInferenceEngine.clearBenchmarkCaches(runtimeContext));
        }
    }

    private static void assertZipDepthBucket(ClientSbsModelManifest manifest,
                                             String assetName,
                                             String assetSha256,
                                             int width,
                                             int height) throws Exception {
        assertEquals(assetName, manifest.getAssetName());
        assertEquals(assetSha256, manifest.getAssetSha256());
        assertEquals(width, manifest.getInputWidth());
        assertEquals(height, manifest.getInputHeight());
        assertTrue("ZipDepth dimensions must be divisible by 32",
                width % 32 == 0 && height % 32 == 0);
        assertTrue("ZipDepth must use direct full-frame resize",
                manifest.usesDirectFullFrameResize());
        assertTrue("Static bucket must skip dynamic tensor resize",
                !manifest.hasDynamicSpatialShape());
        assertEquals(ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16,
                manifest.getGpuExecutionPolicy());
        runPackedGlModelSmokeTest(manifest);
    }

    private static CheckpointRun runExternalCheckpoint(
            Context runtimeContext, PowerManager powerManager, EglFixture egl,
            File checkpoint, ClientSbsModelManifest manifest,
            File inputTensor, int warmupRuns, int measuredRuns) throws Exception {
        int thermalBefore = powerManager == null
                ? -1 : powerManager.getCurrentThermalStatus();
        ClientSbsGpuInferenceEngine engine = ClientSbsGpuInferenceEngine.createShared();
        assertNotNull("A shared LiteRT GPU context must be available", engine);
        ExecutorService inferenceWorker = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "ClientSbsDav2BisectWorker"));
        long[] outputConsumedFences =
                new long[ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
        try {
            inferenceWorker.submit(() -> {
                engine.initializeExternalForBenchmark(runtimeContext, checkpoint, manifest);
                return null;
            }).get();
            egl.assertRendererContextCurrent();

            int inputBuffer = engine.getInputBufferId(0);
            int outputBuffer = engine.getOutputBufferId(0);
            assertTrue(inputBuffer != 0);
            assertTrue(outputBuffer != 0);
            assertTrue(engine.getInputBufferSize(0) >= manifest.getInputByteSize());
            assertTrue(engine.getOutputBufferSize(0) >= manifest.getOutputByteSize());
            assertEquals(manifest.getInputTensor().getChannels() * Float.BYTES,
                    engine.getInputPixelStrideBytes(0));
            assertEquals(manifest.getOutputTensor().getChannels() * Float.BYTES,
                    engine.getOutputPixelStrideBytes(0));
            if (inputTensor == null) {
                uploadGradient(inputBuffer, manifest.getInputWidth(), manifest.getInputHeight(),
                        false, false);
            } else {
                uploadPackedFloatTensor(inputBuffer, inputTensor,
                        manifest.getInputByteSize());
            }
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

            for (int iteration = 0; iteration < warmupRuns; iteration++) {
                runBenchmarkInvocation(engine, inferenceWorker, egl, 0,
                        outputConsumedFences);
            }

            long[] liteRtRunNanos = new long[measuredRuns];
            long[] outputReadyNanos = new long[measuredRuns];
            long measuredStartedNanos = System.nanoTime();
            for (int iteration = 0; iteration < measuredRuns; iteration++) {
                BenchmarkSample sample = runBenchmarkInvocation(
                        engine, inferenceWorker, egl, 0, outputConsumedFences);
                liteRtRunNanos[iteration] = sample.liteRtRunNanos;
                outputReadyNanos[iteration] = sample.outputReadyNanos;
            }
            long measuredWindowNanos = System.nanoTime() - measuredStartedNanos;

            // Replace the provisional no-read consumer fence with one submitted after readback.
            GLES30.glDeleteSync(outputConsumedFences[0]);
            outputConsumedFences[0] = 0L;
            TensorSnapshot output = readTensorSnapshot(outputBuffer,
                    manifest.getOutputByteSize() / Float.BYTES,
                    manifest.getOutputTensor().getChannels());
            outputConsumedFences[0] = createRendererFence();
            int thermalAfter = powerManager == null
                    ? -1 : powerManager.getCurrentThermalStatus();
            CheckpointRun result = new CheckpointRun(output, liteRtRunNanos,
                    outputReadyNanos, measuredWindowNanos,
                    thermalBefore, thermalAfter);
            Log.i(BISECT_TAG, "result model=" + manifest.getId()
                    + " backend=" + manifest.getGpuExecutionPolicy().getBackendId()
                    + " shape=" + Arrays.toString(manifest.getInputTensor().getShape())
                    + "->" + Arrays.toString(manifest.getOutputTensor().getShape())
                    + " warmups=" + warmupRuns + " runs=" + measuredRuns
                    + " compileInitMs=" + formatMillis(
                    engine.getLastNativeInitializationNanos())
                    + " measuredWindowMs=" + formatMillis(measuredWindowNanos)
                    + " thermal=" + thermalBefore + "->" + thermalAfter
                    + " liteRtRunWallMs=" + summarizeNanos(liteRtRunNanos)
                    + " invokeToOutputReadyMs=" + summarizeNanos(outputReadyNanos)
                    + " output=" + output);
            return result;
        } finally {
            try {
                final long slotZeroFence = outputConsumedFences[0];
                final long slotOneFence = outputConsumedFences[1];
                outputConsumedFences[0] = 0L;
                outputConsumedFences[1] = 0L;
                GLES20.glFinish();
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                assertTrue("Native checkpoint engine close must complete on its owner worker",
                        inferenceWorker.submit(() -> engine.close(
                                slotZeroFence, slotOneFence, true)).get());
            } finally {
                inferenceWorker.shutdown();
                assertTrue("Checkpoint inference worker must terminate",
                        inferenceWorker.awaitTermination(10, TimeUnit.SECONDS));
            }
            egl.assertRendererContextCurrent();
        }
    }

    private static void runExternalProfile(
            Context runtimeContext, PowerManager powerManager, EglFixture egl,
            File checkpoint, ClientSbsModelManifest manifest) throws Exception {
        int thermalBefore = powerManager == null
                ? -1 : powerManager.getCurrentThermalStatus();
        ClientSbsGpuInferenceEngine engine = ClientSbsGpuInferenceEngine.createShared();
        assertNotNull("A shared LiteRT GPU context must be available", engine);
        ExecutorService inferenceWorker = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "ClientSbsDav2ProfileWorker"));
        long[] outputConsumedFences =
                new long[ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
        try {
            inferenceWorker.submit(() -> {
                engine.initializeExternalForBenchmark(
                        runtimeContext, checkpoint, manifest, true);
                return null;
            }).get();
            egl.assertRendererContextCurrent();
            int inputBuffer = engine.getInputBufferId(0);
            int outputBuffer = engine.getOutputBufferId(0);
            uploadGradient(inputBuffer, manifest.getInputWidth(), manifest.getInputHeight(),
                    false, false);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

            // The profiler pointer itself makes ML Drift dispatch twice. One unrecorded run warms
            // allocations; exactly one subsequent run is captured before this engine is destroyed.
            runBenchmarkInvocation(engine, inferenceWorker, egl, 0, outputConsumedFences);
            inferenceWorker.submit(() -> {
                engine.startDiagnosticProfiler();
                return null;
            }).get();
            BenchmarkSample sample = runBenchmarkInvocation(
                    engine, inferenceWorker, egl, 0, outputConsumedFences);
            String report = inferenceWorker.submit(
                    engine::stopDiagnosticProfilerAndGetReport).get();

            GLES30.glDeleteSync(outputConsumedFences[0]);
            outputConsumedFences[0] = 0L;
            TensorSnapshot output = readTensorSnapshot(outputBuffer,
                    manifest.getOutputByteSize() / Float.BYTES,
                    manifest.getOutputTensor().getChannels());
            outputConsumedFences[0] = createRendererFence();
            int thermalAfter = powerManager == null
                    ? -1 : powerManager.getCurrentThermalStatus();
            Log.i(BISECT_TAG, "profile model=" + manifest.getId()
                    + " thermal=" + thermalBefore + "->" + thermalAfter
                    + " intrusiveDoubleDispatch=true"
                    + " profiledLiteRtRunWallMs=" + formatMillis(sample.liteRtRunNanos)
                    + " invokeToOutputReadyMs=" + formatMillis(sample.outputReadyNanos)
                    + " report=" + report + " output=" + output);
        } finally {
            try {
                final long slotZeroFence = outputConsumedFences[0];
                final long slotOneFence = outputConsumedFences[1];
                outputConsumedFences[0] = 0L;
                outputConsumedFences[1] = 0L;
                GLES20.glFinish();
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                assertTrue("Native profile engine close must complete on its owner worker",
                        inferenceWorker.submit(() -> engine.close(
                                slotZeroFence, slotOneFence, true)).get());
            } finally {
                inferenceWorker.shutdown();
                assertTrue("Profile inference worker must terminate",
                        inferenceWorker.awaitTermination(10, TimeUnit.SECONDS));
            }
            egl.assertRendererContextCurrent();
        }
    }

    private static ExternalIoProbeRun runExternalIoProbeLeg(
            Context runtimeContext, AssetManager modelAssets, EglFixture egl,
            ClientSbsModelManifest manifest, boolean directExternalPhwc4,
            int warmupRuns, int measuredRuns) throws Exception {
        ClientSbsGpuInferenceEngine engine = ClientSbsGpuInferenceEngine.createShared();
        assertNotNull("A shared LiteRT GPU context must be available", engine);
        ExecutorService inferenceWorker = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, directExternalPhwc4
                        ? "ClientSbsPhwc4Worker" : "ClientSbsPackedProbeWorker"));
        long[] outputConsumedFences =
                new long[ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
        try {
            inferenceWorker.submit(() -> {
                engine.initializeForBenchmark(
                        runtimeContext, modelAssets, manifest, directExternalPhwc4);
                return null;
            }).get();
            egl.assertRendererContextCurrent();
            assertEquals("Native external-I/O mode must match the requested benchmark leg",
                    directExternalPhwc4, engine.isDirectExternalPhwc4Mode());

            int inputBuffer = engine.getInputBufferId(0);
            int outputBuffer = engine.getOutputBufferId(0);
            int expectedInputBytes = manifest.getInputWidth() * manifest.getInputHeight()
                    * (directExternalPhwc4
                    ? PHWC4_FP16_PIXEL_BYTES : PACKED_RGB_FLOAT_PIXEL_BYTES);
            int expectedOutputBytes = manifest.getOutputWidth() * manifest.getOutputHeight()
                    * (directExternalPhwc4
                    ? PHWC4_FP16_PIXEL_BYTES : PACKED_DEPTH_FLOAT_PIXEL_BYTES);
            assertTrue(engine.getInputBufferSize(0) >= expectedInputBytes);
            assertTrue(engine.getOutputBufferSize(0) >= expectedOutputBytes);
            assertEquals(directExternalPhwc4
                            ? PHWC4_FP16_PIXEL_BYTES : PACKED_RGB_FLOAT_PIXEL_BYTES,
                    engine.getInputPixelStrideBytes(0));
            assertEquals(directExternalPhwc4
                            ? PHWC4_FP16_PIXEL_BYTES : PACKED_DEPTH_FLOAT_PIXEL_BYTES,
                    engine.getOutputPixelStrideBytes(0));

            if (directExternalPhwc4) {
                uploadGradientPhwc4Fp16(inputBuffer, manifest.getInputWidth(),
                        manifest.getInputHeight());
                fillPhwc4Fp16Depth(outputBuffer, manifest.getOutputWidth(),
                        manifest.getOutputHeight(), PHWC4_OUTPUT_SENTINEL);
            } else {
                uploadBenchmarkGradient(inputBuffer, manifest.getInputWidth(),
                        manifest.getInputHeight(), false, false);
            }
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

            for (int iteration = 0; iteration < warmupRuns; iteration++) {
                runBenchmarkInvocation(engine, inferenceWorker, egl, 0,
                        outputConsumedFences);
            }
            long[] liteRtRunNanos = new long[measuredRuns];
            long[] outputReadyNanos = new long[measuredRuns];
            for (int iteration = 0; iteration < measuredRuns; iteration++) {
                BenchmarkSample sample = runBenchmarkInvocation(
                        engine, inferenceWorker, egl, 0, outputConsumedFences);
                liteRtRunNanos[iteration] = sample.liteRtRunNanos;
                outputReadyNanos[iteration] = sample.outputReadyNanos;
            }

            GLES30.glDeleteSync(outputConsumedFences[0]);
            outputConsumedFences[0] = 0L;
            int outputStride = directExternalPhwc4
                    ? PHWC4_FP16_PIXEL_BYTES : PACKED_DEPTH_FLOAT_PIXEL_BYTES;
            DepthReadback firstReadback = readDepthSnapshot(
                    outputBuffer, manifest.getOutputWidth(), manifest.getOutputHeight(),
                    outputStride, directExternalPhwc4 ? PHWC4_OUTPUT_SENTINEL : null);
            Log.i(BENCHMARK_TAG, "directExternalPhwc4Probe provisional leg="
                    + (directExternalPhwc4 ? "direct-phwc4-fp16-buffer" : "packed-nhwc")
                    + " model=" + manifest.getId()
                    + " sentinelCount=" + firstReadback.sentinelCount
                    + " sentinelSamples=" + firstReadback.sentinelSamples
                    + " outputRange=[" + firstReadback.output.minimum + ','
                    + firstReadback.output.maximum + "]"
                    + " liteRtRunWallMs=" + summarizeNanos(liteRtRunNanos)
                    + " invokeToOutputReadyMs=" + summarizeNanos(outputReadyNanos));
            if (directExternalPhwc4) {
                assertEquals("Direct external PHWC4 output lane 0 retained the sentinel in "
                                + firstReadback.sentinelCount + " pixels; LiteRT did not write "
                                + "the complete bound GL output",
                        0, firstReadback.sentinelCount);
                fillPhwc4Fp16Depth(outputBuffer, manifest.getOutputWidth(),
                        manifest.getOutputHeight(), PHWC4_OUTPUT_SENTINEL);
            }
            assertDepthSnapshot(firstReadback.output);
            outputConsumedFences[0] = createRendererFence();
            runBenchmarkInvocation(engine, inferenceWorker, egl, 0, outputConsumedFences);
            GLES30.glDeleteSync(outputConsumedFences[0]);
            outputConsumedFences[0] = 0L;
            DepthReadback repeatedReadback = readDepthSnapshot(
                    outputBuffer, manifest.getOutputWidth(), manifest.getOutputHeight(),
                    outputStride, directExternalPhwc4 ? PHWC4_OUTPUT_SENTINEL : null);
            if (directExternalPhwc4) {
                Log.i(BENCHMARK_TAG, "directExternalPhwc4Probe freshWrite sentinelCount="
                        + repeatedReadback.sentinelCount + " sentinelSamples="
                        + repeatedReadback.sentinelSamples);
                assertEquals("Direct external PHWC4 output lane 0 remained at the sentinel after "
                                + "a fresh invocation; LiteRT left the bound GL output untouched",
                        0, repeatedReadback.sentinelCount);
            }
            assertDepthSnapshot(repeatedReadback.output);
            assertRepeatableOutput(firstReadback.output, repeatedReadback.output, 0);
            outputConsumedFences[0] = createRendererFence();

            Log.i(BENCHMARK_TAG, "directExternalPhwc4Probe leg="
                    + (directExternalPhwc4 ? "direct-phwc4" : "packed-nhwc")
                    + " model=" + manifest.getId()
                    + " completeOpenClDelegation=required"
                    + " physicalPixelStride=" + outputStride
                    + " warmups=" + warmupRuns + " runs=" + measuredRuns
                    + " liteRtRunWallMs=" + summarizeNanos(liteRtRunNanos)
                    + " invokeToOutputReadyMs=" + summarizeNanos(outputReadyNanos));
            return new ExternalIoProbeRun(firstReadback.output, liteRtRunNanos,
                    outputReadyNanos);
        } finally {
            try {
                final long slotZeroFence = outputConsumedFences[0];
                final long slotOneFence = outputConsumedFences[1];
                outputConsumedFences[0] = 0L;
                outputConsumedFences[1] = 0L;
                GLES20.glFinish();
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                assertTrue("Native PHWC4 probe engine close must complete on its owner worker",
                        inferenceWorker.submit(() -> engine.close(
                                slotZeroFence, slotOneFence, true)).get());
            } finally {
                inferenceWorker.shutdown();
                assertTrue("PHWC4 probe inference worker must terminate",
                        inferenceWorker.awaitTermination(10, TimeUnit.SECONDS));
            }
            egl.assertRendererContextCurrent();
        }
    }

    private static String requiredArgument(Bundle arguments, String key) {
        String value = arguments.getString(key);
        assertNotNull("Missing instrumentation argument: " + key, value);
        assertTrue("Empty instrumentation argument: " + key, !value.trim().isEmpty());
        return value.trim();
    }

    private static void assumeExternalDiagnosticArgumentsPresent(
            Bundle arguments, String diagnosticName, String... requiredKeys) {
        boolean anyRequiredArgumentSupplied = false;
        for (String key : requiredKeys) {
            if (arguments.containsKey(key)) {
                anyRequiredArgumentSupplied = true;
                break;
            }
        }
        assumeTrue("Skipping " + diagnosticName + ": requires instrumentation arguments "
                        + Arrays.toString(requiredKeys)
                        + " and externally pushed model file(s)",
                anyRequiredArgumentSupplied);
    }

    private static void assertExternalModelPresent(String diagnosticName, File model) {
        assertTrue(diagnosticName + " requires externally pushed model file: "
                        + model.getAbsolutePath(),
                model.isFile());
    }

    private static int positiveArgument(Bundle arguments, String key, int fallback) {
        String value = arguments.getString(key);
        int parsed = value == null ? fallback : Integer.parseInt(value);
        assertTrue(key + " must be positive", parsed > 0);
        return parsed;
    }

    private static double nonNegativeDoubleArgument(Bundle arguments,
                                                     String key,
                                                     double fallback) {
        String value = arguments.getString(key);
        double parsed = value == null ? fallback : Double.parseDouble(value);
        assertTrue(key + " must be finite and non-negative",
                Double.isFinite(parsed) && parsed >= 0.0);
        return parsed;
    }

    private static double boundedDoubleArgument(Bundle arguments,
                                                String key,
                                                double fallback,
                                                double minimum,
                                                double maximum) {
        String value = arguments.getString(key);
        double parsed = value == null ? fallback : Double.parseDouble(value);
        assertTrue(key + " must be finite and in [" + minimum + ", " + maximum + "]",
                Double.isFinite(parsed) && parsed >= minimum && parsed <= maximum);
        return parsed;
    }

    private static int[] parseShape(String value) {
        String[] dimensions = value.trim().split("[xX,]");
        assertEquals("Checkpoint tensors must use rank-4 NHWC", 4, dimensions.length);
        int[] shape = new int[dimensions.length];
        for (int index = 0; index < dimensions.length; index++) {
            shape[index] = Integer.parseInt(dimensions[index].trim());
            assertTrue("Checkpoint dimensions must be positive", shape[index] > 0);
        }
        return shape;
    }

    private static int tensorElementCount(int[] shape) {
        int elements = 1;
        for (int dimension : shape) {
            elements = Math.multiplyExact(elements, dimension);
        }
        return elements;
    }

    private static void writeOptionalTensorSnapshot(File root, String prefix,
                                                    String precision,
                                                    TensorSnapshot tensor) throws Exception {
        if (prefix == null || prefix.trim().isEmpty()) {
            return;
        }
        String safePrefix = prefix.trim();
        assertTrue("output_prefix must be a simple file-name prefix",
                safePrefix.matches("[A-Za-z0-9._-]+"));
        ByteBuffer bytes = ByteBuffer.allocate(tensor.values.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.asFloatBuffer().put(tensor.values);
        File output = new File(root, safePrefix + '-' + precision + ".f32le");
        Files.write(output.toPath(), bytes.array());
        Log.i(BISECT_TAG, "wrote checkpoint output=" + output.getAbsolutePath()
                + " bytes=" + bytes.capacity());
    }

    private static void assertCompleteFiniteTensor(String label,
                                                   TensorSnapshot tensor,
                                                   int expectedElements) {
        assertTrue(label + " must not be empty", tensor.values.length > 0);
        assertEquals(label + " element count", expectedElements, tensor.values.length);
        assertEquals(label + " finite element count", expectedElements, tensor.finiteCount);
        assertEquals(label + " NaN count", 0, tensor.nanCount);
        assertEquals(label + " positive-infinity count", 0,
                tensor.positiveInfinityCount);
        assertEquals(label + " negative-infinity count", 0,
                tensor.negativeInfinityCount);
    }

    /**
     * Measures the actual production bridge: packed Float32 GL input, LiteRT CompiledModel with
     * mandatory complete OpenCL delegation, output GL fence completion, and strict slot reuse.
     * Model upload/readback and initialization are deliberately outside the measured window.
     */
    static void runPackedGlModelBenchmark(ClientSbsModelManifest manifest,
                                          String weightStorage,
                                          boolean externalImageNetNormalization,
                                          boolean useTargetModelAssets)
            throws Exception {
        Context runtimeContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AssetManager modelAssets = useTargetModelAssets ? runtimeContext.getAssets()
                : InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        PowerManager powerManager = (PowerManager) runtimeContext.getSystemService(
                Context.POWER_SERVICE);
        int thermalStatusBefore = powerManager == null
                ? -1 : powerManager.getCurrentThermalStatus();
        PowerManager.WakeLock benchmarkWakeLock = powerManager == null ? null
                : powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "Artemis:ClientSbsGpuBenchmark");
        if (benchmarkWakeLock != null) {
            benchmarkWakeLock.setReferenceCounted(false);
            benchmarkWakeLock.acquire(TimeUnit.MINUTES.toMillis(2));
        }

        try {
            try (EglFixture egl = EglFixture.create()) {
            ClientSbsGpuInferenceEngine engine = ClientSbsGpuInferenceEngine.createShared();
            assertNotNull("A shared LiteRT GPU context must be available", engine);
            ExecutorService inferenceWorker = Executors.newSingleThreadExecutor(
                    runnable -> new Thread(runnable, "ClientSbsGpuBenchWorker"));
            long[] outputConsumedFences =
                    new long[ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
            try {
                inferenceWorker.submit(() -> {
                    engine.initializeForBenchmark(runtimeContext, modelAssets, manifest);
                    return null;
                }).get();
                egl.assertRendererContextCurrent();

                int[] inputBuffers = new int[ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
                int[] outputBuffers = new int[ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
                for (int slot = 0; slot < ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT; slot++) {
                    inputBuffers[slot] = engine.getInputBufferId(slot);
                    outputBuffers[slot] = engine.getOutputBufferId(slot);
                    assertTrue(inputBuffers[slot] != 0);
                    assertTrue(outputBuffers[slot] != 0);
                    assertTrue(engine.getInputBufferSize(slot) >= manifest.getInputByteSize());
                    assertTrue(engine.getOutputBufferSize(slot) >= manifest.getOutputByteSize());
                    assertEquals(PACKED_RGB_FLOAT_PIXEL_BYTES,
                            engine.getInputPixelStrideBytes(slot));
                    assertEquals(PACKED_DEPTH_FLOAT_PIXEL_BYTES,
                            engine.getOutputPixelStrideBytes(slot));
                    uploadBenchmarkGradient(inputBuffers[slot], manifest.getInputWidth(),
                            manifest.getInputHeight(), slot == 1,
                            externalImageNetNormalization);
                }
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

                for (int iteration = 0; iteration < BENCHMARK_WARMUP_RUNS; iteration++) {
                    runBenchmarkInvocation(engine, inferenceWorker, egl,
                            iteration % ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT,
                            outputConsumedFences);
                }

                long[] liteRtRunNanos = new long[BENCHMARK_MEASURED_RUNS];
                long[] outputReadyNanos = new long[BENCHMARK_MEASURED_RUNS];
                long measuredStartedNanos = System.nanoTime();
                for (int iteration = 0; iteration < BENCHMARK_MEASURED_RUNS; iteration++) {
                    BenchmarkSample sample = runBenchmarkInvocation(
                            engine, inferenceWorker, egl,
                            iteration % ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT,
                            outputConsumedFences);
                    liteRtRunNanos[iteration] = sample.liteRtRunNanos;
                    outputReadyNanos[iteration] = sample.outputReadyNanos;
                }
                long measuredWindowNanos = System.nanoTime() - measuredStartedNanos;

                DepthSnapshot[] outputs = new DepthSnapshot[outputBuffers.length];
                for (int slot = 0; slot < outputBuffers.length; slot++) {
                    // The existing fence has not transferred to native yet. Replace it with a
                    // fence after validation so close-time ownership covers every renderer read.
                    GLES30.glDeleteSync(outputConsumedFences[slot]);
                    outputConsumedFences[slot] = 0L;
                    outputs[slot] = readDepthSnapshot(outputBuffers[slot],
                            manifest.getOutputWidth(), manifest.getOutputHeight());
                    assertDepthSnapshot(outputs[slot]);
                    outputConsumedFences[slot] = createRendererFence();
                }
                assertDistinctSlotOutputs(outputs[0], outputs[1],
                        manifest.getOutputWidth(), manifest.getOutputHeight(), manifest);

                int thermalStatusAfter = powerManager == null
                        ? -1 : powerManager.getCurrentThermalStatus();
                double measuredSeconds = measuredWindowNanos / 1_000_000_000.0;
                double isolatedInferenceFps = BENCHMARK_MEASURED_RUNS / measuredSeconds;
                Log.i(BENCHMARK_TAG,
                        "result backend=" + manifest.getGpuExecutionPolicy().getBackendId()
                                + " completeOpenClDelegation=required"
                                + " model=" + manifest.getId()
                                + " gpuPriorityHint=" + engine.getGpuPriorityHintLabel()
                                + " gpuPriorityOverride="
                                + engine.isGpuPriorityHintOverridden()
                                + " weights=" + weightStorage
                                + " normalization=" + (externalImageNetNormalization
                                ? "EXTERNAL_IMAGENET" : "EMBEDDED_IMAGENET")
                                + " shape=" + manifest.getInputWidth() + "x"
                                + manifest.getInputHeight()
                                + " warmup=" + BENCHMARK_WARMUP_RUNS
                                + " samples=" + BENCHMARK_MEASURED_RUNS
                                + " compileInitMs=" + formatMillis(
                                engine.getLastNativeInitializationNanos())
                                + " measuredWindowMs=" + formatMillis(measuredWindowNanos)
                                + " isolatedInferenceFps=" + String.format(Locale.ROOT, "%.2f",
                                isolatedInferenceFps)
                                + " inputPackAndPostprocessExcluded=true"
                                + " externalNormalizationTimed=false"
                                + " thermal=" + thermalStatusBefore + "->"
                                + thermalStatusAfter
                                + " partialWakeLock=" + (benchmarkWakeLock != null
                                && benchmarkWakeLock.isHeld())
                                + " liteRtRunWallMs=" + summarizeNanos(liteRtRunNanos)
                                + " invokeToOutputReadyMs=" + summarizeNanos(outputReadyNanos)
                                + " outputRange0=[" + outputs[0].minimum + ","
                                + outputs[0].maximum + "]");
            } finally {
                try {
                    final long slotZeroFence = outputConsumedFences[0];
                    final long slotOneFence = outputConsumedFences[1];
                    outputConsumedFences[0] = 0L;
                    outputConsumedFences[1] = 0L;
                    GLES20.glFinish();
                    assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                    assertTrue("Native GPU close must complete on its owner worker",
                            inferenceWorker.submit(() -> engine.close(
                                    slotZeroFence, slotOneFence, true)).get());
                    assertTrue("A completed native close retry must be idempotent",
                            inferenceWorker.submit(() ->
                                    engine.retryCloseOnCurrentWorker(false)).get());
                } finally {
                    inferenceWorker.shutdown();
                    assertTrue("Inference worker must terminate",
                            inferenceWorker.awaitTermination(10, TimeUnit.SECONDS));
                }
                egl.assertRendererContextCurrent();
            }
            }
        } finally {
            if (benchmarkWakeLock != null && benchmarkWakeLock.isHeld()) {
                benchmarkWakeLock.release();
            }
        }
    }

    private static BenchmarkSample runBenchmarkInvocation(
            ClientSbsGpuInferenceEngine engine,
            ExecutorService inferenceWorker,
            EglFixture egl,
            int slot,
            long[] outputConsumedFences) throws Exception {
        long inputReadyFence = createRendererFence();
        long previousOutputConsumedFence = outputConsumedFences[slot];
        outputConsumedFences[slot] = 0L;
        long startedNanos = System.nanoTime();
        long outputFence = inferenceWorker.submit(() -> engine.run(
                slot, inputReadyFence, previousOutputConsumedFence)).get();
        egl.assertRendererContextCurrent();
        waitAndDeleteFence(outputFence);
        long outputReadyNanos = System.nanoTime() - startedNanos;
        outputConsumedFences[slot] = createRendererFence();

        long liteRtRunNanos = engine.getLastLiteRtRunWallNanos(slot);
        assertTrue("LiteRT run wall timing must be positive", liteRtRunNanos > 0L);
        return new BenchmarkSample(liteRtRunNanos, outputReadyNanos);
    }

    private static String summarizeNanos(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        double sum = 0.0;
        for (long value : sorted) {
            sum += value;
        }
        double median;
        int middle = sorted.length / 2;
        if ((sorted.length & 1) == 0) {
            median = (sorted[middle - 1] + sorted[middle]) / 2.0;
        } else {
            median = sorted[middle];
        }
        return String.format(Locale.ROOT,
                "{min=%.3f mean=%.3f median=%.3f p90=%.3f p95=%.3f p99=%.3f max=%.3f}",
                sorted[0] / 1_000_000.0,
                sum / sorted.length / 1_000_000.0,
                median / 1_000_000.0,
                nearestRank(sorted, 0.90) / 1_000_000.0,
                nearestRank(sorted, 0.95) / 1_000_000.0,
                nearestRank(sorted, 0.99) / 1_000_000.0,
                sorted[sorted.length - 1] / 1_000_000.0);
    }

    private static long nearestRank(long[] sorted, double percentile) {
        int index = Math.max(0,
                Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1));
        return sorted[index];
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static final class BenchmarkSample {
        final long liteRtRunNanos;
        final long outputReadyNanos;

        BenchmarkSample(long liteRtRunNanos, long outputReadyNanos) {
            this.liteRtRunNanos = liteRtRunNanos;
            this.outputReadyNanos = outputReadyNanos;
        }
    }

    private static final class ExternalIoProbeRun {
        final DepthSnapshot output;
        final long[] liteRtRunNanos;
        final long[] outputReadyNanos;

        ExternalIoProbeRun(DepthSnapshot output, long[] liteRtRunNanos,
                           long[] outputReadyNanos) {
            this.output = output;
            this.liteRtRunNanos = liteRtRunNanos;
            this.outputReadyNanos = outputReadyNanos;
        }
    }

    private static final class CheckpointRun {
        final TensorSnapshot output;
        final long[] liteRtRunNanos;
        final long[] outputReadyNanos;
        final long measuredWindowNanos;
        final int thermalBefore;
        final int thermalAfter;

        CheckpointRun(TensorSnapshot output, long[] liteRtRunNanos,
                      long[] outputReadyNanos,
                      long measuredWindowNanos, int thermalBefore, int thermalAfter) {
            this.output = output;
            this.liteRtRunNanos = liteRtRunNanos;
            this.outputReadyNanos = outputReadyNanos;
            this.measuredWindowNanos = measuredWindowNanos;
            this.thermalBefore = thermalBefore;
            this.thermalAfter = thermalAfter;
        }
    }

    private static void runPackedGlModelSmokeTest(ClientSbsModelManifest manifest)
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        try (EglFixture egl = EglFixture.create()) {
            ClientSbsGpuInferenceEngine engine = ClientSbsGpuInferenceEngine.createShared();
            assertNotNull("A shared LiteRT GPU context must be available", engine);
            ExecutorService inferenceWorker = Executors.newSingleThreadExecutor(
                    runnable -> new Thread(runnable, "ClientSbsGpuSmokeWorker"));
            long[] outputConsumedFences =
                    new long[ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
            try {
                inferenceWorker.submit(() -> {
                    engine.initialize(context, manifest);
                    return null;
                }).get();
                egl.assertRendererContextCurrent();
                Log.i(TAG, "initialized model=" + manifest.getId()
                        + " shape=" + manifest.getInputWidth() + "x"
                        + manifest.getInputHeight()
                        + " dynamicResize=" + manifest.hasDynamicSpatialShape()
                        + " completeOpenClDelegation=required"
                        + " verify=" + engine.getLastAssetVerificationNanos() / 1_000_000.0
                        + "ms compileInit="
                        + engine.getLastNativeInitializationNanos() / 1_000_000.0 + "ms");
                int expectedInputBytes = manifest.getInputByteSize();
                int expectedOutputBytes = manifest.getOutputByteSize();
                assertEquals(2, ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT);
                assertInvalidSlot(() -> engine.getInputBufferId(-1));
                assertInvalidSlot(() -> engine.getOutputBufferId(
                        ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT));
                assertInvalidSlot(() -> engine.run(
                        ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT, 0L, 0L));
                assertInvalidSlot(() -> engine.run(0, 0L, 0L));

                int[] inputBuffers = new int[ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
                int[] outputBuffers = new int[ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
                for (int slot = 0; slot < ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT; slot++) {
                    inputBuffers[slot] = engine.getInputBufferId(slot);
                    outputBuffers[slot] = engine.getOutputBufferId(slot);
                    assertTrue(inputBuffers[slot] != 0);
                    assertTrue(outputBuffers[slot] != 0);
                    assertTrue(engine.getInputBufferSize(slot) >= expectedInputBytes);
                    assertTrue(engine.getOutputBufferSize(slot) >= expectedOutputBytes);
                    assertEquals(PACKED_RGB_FLOAT_PIXEL_BYTES,
                            engine.getInputPixelStrideBytes(slot));
                    assertEquals(PACKED_DEPTH_FLOAT_PIXEL_BYTES,
                            engine.getOutputPixelStrideBytes(slot));
                }
                assertTrue("Input slots must have distinct GL buffers",
                        inputBuffers[0] != inputBuffers[1]);
                assertTrue("Output slots must have distinct GL buffers",
                        outputBuffers[0] != outputBuffers[1]);

                DepthSnapshot[][] snapshots = new DepthSnapshot[2]
                        [ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT];
                // Run each slot twice. The second pass returns the first pass's renderer-created
                // output-consumed fence to the matching slot, exercising strict per-slot reuse.
                // Slot 1 receives the horizontal mirror of slot 0 so output-slot swaps, stale
                // bindings, transposed layouts, and all-zero untouched buffers cannot hide behind
                // a plausible global min/max range.
                for (int pass = 0; pass < 2; pass++) {
                    for (int slot = 0;
                         slot < ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT; slot++) {
                        uploadGradient(inputBuffers[slot], manifest.getInputWidth(),
                                manifest.getInputHeight(), slot == 1);
                        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                        float[] inputRange = readFloatRange(inputBuffers[slot],
                                expectedInputBytes / Float.BYTES);
                        assertEquals(0.0f, inputRange[0], 0.0f);
                        assertEquals(1.0f, inputRange[1], 0.0f);

                        final int invocationSlot = slot;
                        long inputReadyFence = createRendererFence();
                        long previousOutputConsumedFence = outputConsumedFences[slot];
                        // Ownership transfers to nativeRun even if invocation fails.
                        outputConsumedFences[slot] = 0L;
                        long startedNs = System.nanoTime();
                        long outputFence = inferenceWorker.submit(() -> engine.run(
                                invocationSlot, inputReadyFence,
                                previousOutputConsumedFence)).get();
                        egl.assertRendererContextCurrent();
                        waitAndDeleteFence(outputFence);
                        double elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0;
                        DepthSnapshot output = readDepthSnapshot(outputBuffers[slot],
                                manifest.getOutputWidth(), manifest.getOutputHeight());
                        snapshots[pass][slot] = output;
                        assertDepthSnapshot(output);
                        if (pass > 0) {
                            assertRepeatableOutput(snapshots[0][slot], output, slot);
                        }

                        long liteRtRunNs = engine.getLastLiteRtRunWallNanos(slot);
                        assertTrue("LiteRT run wall timing must be positive", liteRtRunNs > 0L);
                        Log.i(TAG, "backend="
                                + manifest.getGpuExecutionPolicy().getBackendId()
                                + " model=" + manifest.getId()
                                + " shape=" + manifest.getInputWidth() + "x"
                                + manifest.getInputHeight() + " slot=" + slot
                                + " pass=" + pass + " invokeToOutputReady=" + elapsedMs
                                + "ms liteRtRunWall=" + liteRtRunNs / 1_000_000.0
                                + "ms outputRange=[" + output.minimum + ","
                                + output.maximum + "] spatialChecksum="
                                + output.spatialChecksum);

                        // The final pass also needs a close-time consumer fence. nativeDestroy
                        // waits it before releasing LiteRT tensor wrappers and shared GL buffers.
                        outputConsumedFences[slot] = createRendererFence();
                    }
                }
                assertDistinctSlotOutputs(snapshots[0][0], snapshots[0][1],
                        manifest.getOutputWidth(), manifest.getOutputHeight(), manifest);
            } finally {
                try {
                    final long slotZeroFence = outputConsumedFences[0];
                    final long slotOneFence = outputConsumedFences[1];
                    outputConsumedFences[0] = 0L;
                    outputConsumedFences[1] = 0L;
                    // Mirror the production failure-safe teardown contract: the renderer context
                    // explicitly drains before native releases shared LiteRT/GL resources, and the
                    // owner worker must report destruction rather than silently quarantining it.
                    GLES20.glFinish();
                    assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                    assertTrue("Native GPU close must complete on its owner worker",
                            inferenceWorker.submit(() -> engine.close(
                                    slotZeroFence, slotOneFence, true)).get());
                    assertTrue("A completed native close retry must be idempotent",
                            inferenceWorker.submit(() ->
                                    engine.retryCloseOnCurrentWorker(false)).get());
                } finally {
                    inferenceWorker.shutdown();
                    assertTrue("Inference worker must terminate",
                            inferenceWorker.awaitTermination(10, TimeUnit.SECONDS));
                }
                egl.assertRendererContextCurrent();
            }
        }
    }

    private static void assertInvalidSlot(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Invalid buffer slot must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertDepthSnapshot(DepthSnapshot output) {
        assertTrue("Depth output must be finite and nontrivial: ["
                        + output.minimum + "," + output.maximum + "]",
                Float.isFinite(output.minimum) && Float.isFinite(output.maximum)
                        && output.maximum > output.minimum
                        && output.maximum > 0.0f);
        assertTrue("Depth output must be non-negative: " + output.minimum,
                output.minimum >= 0.0f);
        assertTrue("Depth output must contain spatial structure",
                output.tileMaximum - output.tileMinimum
                        >= (output.maximum - output.minimum) * 0.01f);
        assertTrue("Depth output spatial checksum must be finite",
                Double.isFinite(output.spatialChecksum));
    }

    private static void uploadGradient(int bufferId, int width, int height,
                                       boolean mirrorHorizontally) {
        uploadGradient(bufferId, width, height, mirrorHorizontally, false);
    }

    private static void uploadBenchmarkGradient(int bufferId, int width, int height,
                                                boolean mirrorHorizontally,
                                                boolean imageNetNormalize) {
        uploadGradient(bufferId, width, height, mirrorHorizontally, imageNetNormalize);
    }

    private static void uploadGradient(int bufferId, int width, int height,
                                       boolean mirrorHorizontally,
                                       boolean imageNetNormalize) {
        ByteBuffer input = ByteBuffer.allocateDirect(width * height
                        * PACKED_RGB_FLOAT_PIXEL_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sourceX = mirrorHorizontally ? width - 1 - x : x;
                float normalizedX = sourceX / (float) (width - 1);
                float red = normalizedX;
                float green = y / (float) (height - 1);
                float blue = (sourceX + y) / (float) (width + height - 2);
                // Add one off-center object so a transposed, stale, or numerically fragile output
                // cannot hide behind the smooth gradient used by the original smoke test.
                float objectX = normalizedX - 0.22f;
                float objectY = green - 0.68f;
                if (objectX * objectX + objectY * objectY < 0.018f) {
                    red = 1.0f;
                    green = 0.05f;
                    blue = 0.8f;
                }
                if (imageNetNormalize) {
                    red = (red - 0.485f) / 0.229f;
                    green = (green - 0.456f) / 0.224f;
                    blue = (blue - 0.406f) / 0.225f;
                }
                input.putFloat(red);
                input.putFloat(green);
                input.putFloat(blue);
            }
        }
        input.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferId);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, input.remaining(), input);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void uploadPackedFloatTensor(int bufferId, File inputTensor,
                                                int expectedBytes) throws Exception {
        byte[] bytes = Files.readAllBytes(inputTensor.toPath());
        assertEquals("External input tensor byte count", expectedBytes, bytes.length);
        ByteBuffer input = ByteBuffer.allocateDirect(bytes.length)
                .order(ByteOrder.nativeOrder());
        input.put(bytes).flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferId);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, input.remaining(), input);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void uploadGradientPhwc4Fp16(int bufferId, int width, int height) {
        ByteBuffer input = ByteBuffer.allocateDirect(
                        width * height * PHWC4_FP16_PIXEL_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float red = x / (float) (width - 1);
                float green = y / (float) (height - 1);
                float blue = (x + y) / (float) (width + height - 2);
                float objectX = red - 0.22f;
                float objectY = green - 0.68f;
                if (objectX * objectX + objectY * objectY < 0.018f) {
                    red = 1.0f;
                    green = 0.05f;
                    blue = 0.8f;
                }
                input.putShort(floatToHalfBits(red));
                input.putShort(floatToHalfBits(green));
                input.putShort(floatToHalfBits(blue));
                input.putShort(floatToHalfBits(0.0f));
            }
        }
        input.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferId);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, input.remaining(), input);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void fillPhwc4Fp16Depth(int bufferId, int width, int height,
                                           float sentinel) {
        ByteBuffer output = ByteBuffer.allocateDirect(
                        width * height * PHWC4_FP16_PIXEL_BYTES)
                .order(ByteOrder.nativeOrder());
        short sentinelBits = floatToHalfBits(sentinel);
        for (int index = 0; index < width * height * 4; index++) {
            output.putShort(sentinelBits);
        }
        output.flip();
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferId);
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, output.remaining(), output);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static DepthSnapshot readDepthSnapshot(int bufferId, int width, int height) {
        return readDepthSnapshot(bufferId, width, height,
                PACKED_DEPTH_FLOAT_PIXEL_BYTES, null).output;
    }

    private static DepthReadback readDepthSnapshot(int bufferId, int width, int height,
                                                   int pixelStrideBytes, Float sentinel) {
        assertTrue("Depth pixel stride must be packed Float32 or FP16 PHWC4",
                pixelStrideBytes == PACKED_DEPTH_FLOAT_PIXEL_BYTES
                        || pixelStrideBytes == PHWC4_FP16_PIXEL_BYTES);
        boolean fp16Phwc4 = pixelStrideBytes == PHWC4_FP16_PIXEL_BYTES;
        short sentinelHalfBits = sentinel == null ? 0 : floatToHalfBits(sentinel);
        int valueCount = width * height;
        int bytes = valueCount * pixelStrideBytes;
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferId);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes,
                GLES30.GL_MAP_READ_BIT);
        assertTrue("Model output GL buffer must be mappable", mapped instanceof ByteBuffer);
        ByteBuffer output = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        float[] values = new float[valueCount];
        double[] tileSums = new double[16];
        int[] tileCounts = new int[16];
        double spatialChecksum = 0.0;
        int nonFiniteCount = 0;
        int sentinelCount = 0;
        StringBuilder sentinelSamples = new StringBuilder();
        for (int index = 0; index < valueCount; index++) {
            int byteOffset = index * pixelStrideBytes;
            short halfBits = fp16Phwc4 ? output.getShort(byteOffset) : 0;
            float value = fp16Phwc4
                    ? halfBitsToFloat(halfBits) : output.getFloat(byteOffset);
            values[index] = value;
            if (sentinel != null && (fp16Phwc4
                    ? halfBits == sentinelHalfBits
                    : Float.floatToRawIntBits(value) == Float.floatToRawIntBits(sentinel))) {
                sentinelCount++;
                if (sentinelCount <= 16) {
                    if (sentinelSamples.length() != 0) {
                        sentinelSamples.append(',');
                    }
                    sentinelSamples.append(index).append('@')
                            .append(index % width).append('x').append(index / width);
                }
            }
            if (Float.isFinite(value)) {
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
                int x = index % width;
                int y = index / width;
                int tile = Math.min(3, y * 4 / height) * 4
                        + Math.min(3, x * 4 / width);
                tileSums[tile] += value;
                tileCounts[tile]++;
                spatialChecksum += value * (1.0 + x * 0.013 + y * 0.021);
            } else {
                nonFiniteCount++;
            }
        }
        assertTrue("Model output GL buffer must unmap", GLES30.glUnmapBuffer(
                GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        assertEquals("Model output must contain only finite values", 0, nonFiniteCount);
        float tileMinimum = Float.POSITIVE_INFINITY;
        float tileMaximum = Float.NEGATIVE_INFINITY;
        for (int tile = 0; tile < tileSums.length; tile++) {
            float mean = (float) (tileSums[tile] / tileCounts[tile]);
            tileMinimum = Math.min(tileMinimum, mean);
            tileMaximum = Math.max(tileMaximum, mean);
        }
        return new DepthReadback(
                new DepthSnapshot(values, minimum, maximum, tileMinimum, tileMaximum,
                        spatialChecksum / valueCount),
                sentinelCount, sentinelSamples.toString());
    }

    /** IEEE-754 round-to-nearest-even conversion used to populate raw GLES half4 SSBOs. */
    private static short floatToHalfBits(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int exponent = (bits >>> 23) & 0xff;
        int significand = bits & 0x7fffff;
        if (exponent == 0xff) {
            int halfSignificand = significand == 0 ? 0 : Math.max(1, significand >>> 13);
            return (short) (sign | 0x7c00 | halfSignificand);
        }

        int halfExponent = exponent - 127 + 15;
        if (halfExponent >= 0x1f) {
            return (short) (sign | 0x7c00);
        }
        if (halfExponent <= 0) {
            if (halfExponent < -10) {
                return (short) sign;
            }
            significand |= 0x800000;
            int shift = 14 - halfExponent;
            int halfSignificand = significand >>> shift;
            int remainder = significand & ((1 << shift) - 1);
            int halfway = 1 << (shift - 1);
            if (remainder > halfway || (remainder == halfway && (halfSignificand & 1) != 0)) {
                halfSignificand++;
            }
            return (short) (sign | halfSignificand);
        }

        int halfSignificand = significand >>> 13;
        int remainder = significand & 0x1fff;
        if (remainder > 0x1000 || (remainder == 0x1000 && (halfSignificand & 1) != 0)) {
            halfSignificand++;
            if (halfSignificand == 0x400) {
                halfSignificand = 0;
                halfExponent++;
                if (halfExponent >= 0x1f) {
                    return (short) (sign | 0x7c00);
                }
            }
        }
        return (short) (sign | (halfExponent << 10) | halfSignificand);
    }

    /** IEEE-754 binary16 to binary32 conversion for direct-PHWC4 result validation. */
    private static float halfBitsToFloat(short half) {
        int bits = half & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1f;
        int significand = bits & 0x3ff;
        if (exponent == 0) {
            if (significand == 0) {
                return Float.intBitsToFloat(sign);
            }
            exponent = 1;
            while ((significand & 0x400) == 0) {
                significand <<= 1;
                exponent--;
            }
            significand &= 0x3ff;
            exponent += 127 - 15;
        } else if (exponent == 0x1f) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (significand << 13));
        } else {
            exponent += 127 - 15;
        }
        return Float.intBitsToFloat(sign | (exponent << 23) | (significand << 13));
    }

    private static TensorSnapshot readTensorSnapshot(int bufferId, int valueCount,
                                                     int vectorWidth) {
        int bytes = Math.multiplyExact(valueCount, Float.BYTES);
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferId);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes,
                GLES30.GL_MAP_READ_BIT);
        assertTrue("Checkpoint output GL buffer must be mappable", mapped instanceof ByteBuffer);
        ByteBuffer buffer = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        float[] values = new float[valueCount];
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        double sum = 0.0;
        double sumSquares = 0.0;
        double checksum = 0.0;
        int finiteCount = 0;
        int zeroCount = 0;
        int nanCount = 0;
        int positiveInfinityCount = 0;
        int negativeInfinityCount = 0;
        for (int index = 0; index < valueCount; index++) {
            float value = buffer.getFloat(index * Float.BYTES);
            values[index] = value;
            if (!Float.isFinite(value)) {
                if (Float.isNaN(value)) {
                    nanCount++;
                } else if (value > 0.0f) {
                    positiveInfinityCount++;
                } else {
                    negativeInfinityCount++;
                }
                continue;
            }
            finiteCount++;
            if (value == 0.0f) {
                zeroCount++;
            }
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
            sum += value;
            sumSquares += (double) value * value;
            checksum += value * (1.0 + (index % 257) / 257.0);
        }
        assertTrue("Checkpoint output GL buffer must unmap", GLES30.glUnmapBuffer(
                GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        double mean = finiteCount == 0 ? Double.NaN : sum / finiteCount;
        double rms = finiteCount == 0 ? Double.NaN
                : Math.sqrt(sumSquares / finiteCount);
        double normalizedChecksum = finiteCount == 0 ? Double.NaN
                : checksum / finiteCount;
        int allNonFiniteVectors = 0;
        int partialNonFiniteVectors = 0;
        if (vectorWidth > 0 && valueCount % vectorWidth == 0) {
            for (int vectorStart = 0; vectorStart < valueCount;
                 vectorStart += vectorWidth) {
                int nonFiniteInVector = 0;
                for (int lane = 0; lane < vectorWidth; lane++) {
                    if (!Float.isFinite(values[vectorStart + lane])) {
                        nonFiniteInVector++;
                    }
                }
                if (nonFiniteInVector == vectorWidth) {
                    allNonFiniteVectors++;
                } else if (nonFiniteInVector != 0) {
                    partialNonFiniteVectors++;
                }
            }
        }
        return new TensorSnapshot(values, minimum, maximum, mean, rms,
                finiteCount, zeroCount, nanCount, positiveInfinityCount,
                negativeInfinityCount, allNonFiniteVectors, partialNonFiniteVectors,
                vectorWidth, normalizedChecksum);
    }

    private static float[] readFloatRange(int bufferId, int valueCount) {
        int bytes = valueCount * Float.BYTES;
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferId);
        Buffer mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes,
                GLES30.GL_MAP_READ_BIT);
        assertTrue("Model input GL buffer must be mappable", mapped instanceof ByteBuffer);
        ByteBuffer values = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder());
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < valueCount; index++) {
            float value = values.getFloat(index * Float.BYTES);
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        assertTrue("Model input GL buffer must unmap", GLES30.glUnmapBuffer(
                GLES31.GL_SHADER_STORAGE_BUFFER));
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        return new float[] {minimum, maximum};
    }

    private static void assertRepeatableOutput(DepthSnapshot first, DepthSnapshot repeated,
                                               int slot) {
        double absoluteError = 0.0;
        for (int index = 0; index < first.values.length; index++) {
            absoluteError += Math.abs(first.values[index] - repeated.values[index]);
        }
        double normalizedMae = absoluteError / first.values.length
                / Math.max(first.maximum - first.minimum, 1.0e-6f);
        assertTrue("Repeated output changed unexpectedly for slot " + slot
                        + ": normalized MAE=" + normalizedMae,
                normalizedMae < 0.02);
    }

    private static void assertDistinctSlotOutputs(DepthSnapshot original,
                                                  DepthSnapshot mirrored,
                                                  int width,
                                                  int height,
                                                  ClientSbsModelManifest manifest) {
        double directError = 0.0;
        double mirroredError = 0.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float value = original.values[y * width + x];
                directError += Math.abs(value - mirrored.values[y * width + x]);
                mirroredError += Math.abs(value
                        - mirrored.values[y * width + (width - 1 - x)]);
            }
        }
        double scale = Math.max(original.maximum - original.minimum, 1.0e-6f)
                * width * height;
        double normalizedDirect = directError / scale;
        double normalizedMirrored = mirroredError / scale;
        assertTrue("Distinct slot inputs must produce distinct spatial outputs",
                normalizedDirect > 0.005);
        // Convolutional MiDaS should remain approximately flip-equivariant. DA-V2 has absolute
        // and interpolated transformer positional embeddings, so the CPU reference itself is not
        // mirror-equivariant; finite/non-flat, distinct-slot, and exact-repeatability checks are
        // the valid correctness gates for that family.
        if (manifest.getId().startsWith("midas-v2-static-")) {
            assertTrue("Horizontally mirrored input must mirror the spatial depth response: mirror="
                            + normalizedMirrored + " direct=" + normalizedDirect,
                    normalizedMirrored < normalizedDirect);
        }
    }

    private static final class DepthSnapshot {
        final float[] values;
        final float minimum;
        final float maximum;
        final float tileMinimum;
        final float tileMaximum;
        final double spatialChecksum;

        DepthSnapshot(float[] values, float minimum, float maximum,
                      float tileMinimum, float tileMaximum, double spatialChecksum) {
            this.values = values;
            this.minimum = minimum;
            this.maximum = maximum;
            this.tileMinimum = tileMinimum;
            this.tileMaximum = tileMaximum;
            this.spatialChecksum = spatialChecksum;
        }
    }

    private static final class DepthReadback {
        final DepthSnapshot output;
        final int sentinelCount;
        final String sentinelSamples;

        DepthReadback(DepthSnapshot output, int sentinelCount, String sentinelSamples) {
            this.output = output;
            this.sentinelCount = sentinelCount;
            this.sentinelSamples = sentinelSamples;
        }
    }

    private static final class TensorSnapshot {
        final float[] values;
        final float minimum;
        final float maximum;
        final double mean;
        final double rms;
        final int finiteCount;
        final int zeroCount;
        final int nanCount;
        final int positiveInfinityCount;
        final int negativeInfinityCount;
        final int allNonFiniteVectors;
        final int partialNonFiniteVectors;
        final int vectorWidth;
        final double checksum;

        TensorSnapshot(float[] values, float minimum, float maximum,
                       double mean, double rms, int finiteCount,
                       int zeroCount, int nanCount, int positiveInfinityCount,
                       int negativeInfinityCount, int allNonFiniteVectors,
                       int partialNonFiniteVectors, int vectorWidth, double checksum) {
            this.values = values;
            this.minimum = minimum;
            this.maximum = maximum;
            this.mean = mean;
            this.rms = rms;
            this.finiteCount = finiteCount;
            this.zeroCount = zeroCount;
            this.nanCount = nanCount;
            this.positiveInfinityCount = positiveInfinityCount;
            this.negativeInfinityCount = negativeInfinityCount;
            this.allNonFiniteVectors = allNonFiniteVectors;
            this.partialNonFiniteVectors = partialNonFiniteVectors;
            this.vectorWidth = vectorWidth;
            this.checksum = checksum;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "{elements=%d finite=%d nan=%d posInf=%d negInf=%d "
                            + "vectors(width=%d allNonFinite=%d partialNonFinite=%d) "
                            + "min=%.9g max=%.9g mean=%.9g rms=%.9g "
                            + "zeroFraction=%.9g checksum=%.12g}",
                    values.length, finiteCount, nanCount, positiveInfinityCount,
                    negativeInfinityCount, vectorWidth, allNonFiniteVectors,
                    partialNonFiniteVectors, minimum, maximum, mean, rms,
                    values.length == 0 ? Double.NaN : zeroCount / (double) values.length,
                    checksum);
        }
    }

    private static final class DepthComparison {
        final int compared;
        final double normalizedRmse;
        final double cosineSimilarity;

        DepthComparison(int compared, double normalizedRmse, double cosineSimilarity) {
            this.compared = compared;
            this.normalizedRmse = normalizedRmse;
            this.cosineSimilarity = cosineSimilarity;
        }

        static DepthComparison compare(DepthSnapshot candidate, DepthSnapshot reference) {
            assertEquals("Packed/direct depth element count",
                    reference.values.length, candidate.values.length);
            int compared = 0;
            double squaredError = 0.0;
            double candidateSquares = 0.0;
            double referenceSquares = 0.0;
            double dot = 0.0;
            for (int index = 0; index < candidate.values.length; index++) {
                float candidateValue = candidate.values[index];
                float referenceValue = reference.values[index];
                if (!Float.isFinite(candidateValue) || !Float.isFinite(referenceValue)) {
                    continue;
                }
                compared++;
                double error = candidateValue - referenceValue;
                squaredError += error * error;
                candidateSquares += (double) candidateValue * candidateValue;
                referenceSquares += (double) referenceValue * referenceValue;
                dot += (double) candidateValue * referenceValue;
            }
            if (compared == 0) {
                return new DepthComparison(0, Double.NaN, Double.NaN);
            }
            double rmse = Math.sqrt(squaredError / compared);
            double referenceRms = Math.sqrt(referenceSquares / compared);
            double cosine = dot / Math.max(
                    Math.sqrt(candidateSquares * referenceSquares), 1.0e-30);
            return new DepthComparison(compared,
                    rmse / Math.max(referenceRms, 1.0e-30), cosine);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "{finitePairs=%d nrmse=%.9g cosine=%.9g}",
                    compared, normalizedRmse, cosineSimilarity);
        }
    }

    private static final class TensorComparison {
        final int compared;
        final double normalizedRmse;
        final double maximumAbsoluteError;
        final double cosineSimilarity;
        final double rmsRatio;
        final double pearsonCorrelation;
        final double affineNormalizedRmse;
        final double affineScale;
        final double affineOffset;

        TensorComparison(int compared, double normalizedRmse,
                         double maximumAbsoluteError, double cosineSimilarity,
                         double rmsRatio, double pearsonCorrelation,
                         double affineNormalizedRmse, double affineScale,
                         double affineOffset) {
            this.compared = compared;
            this.normalizedRmse = normalizedRmse;
            this.maximumAbsoluteError = maximumAbsoluteError;
            this.cosineSimilarity = cosineSimilarity;
            this.rmsRatio = rmsRatio;
            this.pearsonCorrelation = pearsonCorrelation;
            this.affineNormalizedRmse = affineNormalizedRmse;
            this.affineScale = affineScale;
            this.affineOffset = affineOffset;
        }

        static TensorComparison compare(TensorSnapshot fp16, TensorSnapshot fp32) {
            assertEquals("FP16/FP32 checkpoint element count",
                    fp32.values.length, fp16.values.length);
            int compared = 0;
            double squaredError = 0.0;
            double fp16Squares = 0.0;
            double fp32Squares = 0.0;
            double dot = 0.0;
            double maximumAbsoluteError = 0.0;
            double fp16Sum = 0.0;
            double fp32Sum = 0.0;
            double fp32Minimum = Double.POSITIVE_INFINITY;
            double fp32Maximum = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < fp16.values.length; index++) {
                float candidate = fp16.values[index];
                float reference = fp32.values[index];
                if (!Float.isFinite(candidate) || !Float.isFinite(reference)) {
                    continue;
                }
                compared++;
                double error = candidate - reference;
                squaredError += error * error;
                fp16Squares += (double) candidate * candidate;
                fp32Squares += (double) reference * reference;
                dot += (double) candidate * reference;
                maximumAbsoluteError = Math.max(maximumAbsoluteError, Math.abs(error));
                fp16Sum += candidate;
                fp32Sum += reference;
                fp32Minimum = Math.min(fp32Minimum, reference);
                fp32Maximum = Math.max(fp32Maximum, reference);
            }
            if (compared == 0) {
                return new TensorComparison(0, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN);
            }
            double rmse = Math.sqrt(squaredError / compared);
            double fp16Rms = Math.sqrt(fp16Squares / compared);
            double fp32Rms = Math.sqrt(fp32Squares / compared);
            double normalizedRmse = rmse / Math.max(fp32Rms, 1.0e-30);
            double cosine = dot / Math.max(
                    Math.sqrt(fp16Squares * fp32Squares), 1.0e-30);
            double rmsRatio = fp16Rms / Math.max(fp32Rms, 1.0e-30);
            double fp16Mean = fp16Sum / compared;
            double fp32Mean = fp32Sum / compared;
            double fp16CenteredSquares = 0.0;
            double fp32CenteredSquares = 0.0;
            double centeredDot = 0.0;
            for (int index = 0; index < fp16.values.length; index++) {
                float candidate = fp16.values[index];
                float reference = fp32.values[index];
                if (!Float.isFinite(candidate) || !Float.isFinite(reference)) {
                    continue;
                }
                double centeredCandidate = candidate - fp16Mean;
                double centeredReference = reference - fp32Mean;
                fp16CenteredSquares += centeredCandidate * centeredCandidate;
                fp32CenteredSquares += centeredReference * centeredReference;
                centeredDot += centeredCandidate * centeredReference;
            }
            double pearson = centeredDot / Math.max(
                    Math.sqrt(fp16CenteredSquares * fp32CenteredSquares), 1.0e-30);
            double affineScale = centeredDot / Math.max(fp16CenteredSquares, 1.0e-30);
            double affineOffset = fp32Mean - affineScale * fp16Mean;
            double affineSquaredError = 0.0;
            for (int index = 0; index < fp16.values.length; index++) {
                float candidate = fp16.values[index];
                float reference = fp32.values[index];
                if (!Float.isFinite(candidate) || !Float.isFinite(reference)) {
                    continue;
                }
                double error = affineScale * candidate + affineOffset - reference;
                affineSquaredError += error * error;
            }
            double affineRmse = Math.sqrt(affineSquaredError / compared);
            double affineNormalizedRmse = affineRmse
                    / Math.max(fp32Maximum - fp32Minimum, 1.0e-30);
            return new TensorComparison(compared, normalizedRmse,
                    maximumAbsoluteError, cosine, rmsRatio, pearson,
                    affineNormalizedRmse, affineScale, affineOffset);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "{finitePairs=%d nrmse=%.9g maxAbs=%.9g cosine=%.9g rmsRatio=%.9g "
                            + "pearson=%.9g affineNrmse=%.9g affineScale=%.9g "
                            + "affineOffset=%.9g}",
                    compared, normalizedRmse, maximumAbsoluteError,
                    cosineSimilarity, rmsRatio, pearsonCorrelation,
                    affineNormalizedRmse, affineScale, affineOffset);
        }
    }

    private static long createRendererFence() {
        long fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertTrue("Renderer must create an input-ready GL fence", fence != 0L);
        GLES20.glFlush();
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        return fence;
    }

    private static void waitAndDeleteFence(long fence) {
        assertTrue("LiteRT must return an output GL fence", fence != 0L);
        int result = GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT,
                5_000_000_000L);
        GLES30.glDeleteSync(fence);
        assertTrue("LiteRT output fence wait failed: 0x" + Integer.toHexString(result),
                result == GLES30.GL_ALREADY_SIGNALED
                        || result == GLES30.GL_CONDITION_SATISFIED);
    }

    private static final class EglFixture implements AutoCloseable {
        private final EGLDisplay display;
        private final EGLSurface surface;
        private final EGLContext context;

        private EglFixture(EGLDisplay display, EGLSurface surface, EGLContext context) {
            this.display = display;
            this.surface = surface;
            this.context = context;
        }

        static EglFixture create() {
            EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            assertTrue(display != EGL14.EGL_NO_DISPLAY);
            assertTrue(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0));
            assertTrue(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API));
            int[] configAttributes = {
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            assertTrue(EGL14.eglChooseConfig(display, configAttributes, 0,
                    configs, 0, 1, count, 0));
            assertEquals(1, count[0]);
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            EGLContext context = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            int[] surfaceAttributes = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    surfaceAttributes, 0);
            assertTrue(surface != EGL14.EGL_NO_SURFACE);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
            return new EglFixture(display, surface, context);
        }

        void assertRendererContextCurrent() {
            assertEquals(context, EGL14.eglGetCurrentContext());
            assertEquals(surface, EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
            assertEquals(surface, EGL14.eglGetCurrentSurface(EGL14.EGL_READ));
        }

        @Override
        public void close() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
}
