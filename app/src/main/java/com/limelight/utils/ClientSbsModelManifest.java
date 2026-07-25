package com.limelight.utils;

import java.util.Arrays;

/** Immutable model and tensor contract for the native client-SBS GPU pipeline. */
final class ClientSbsModelManifest {
    enum GpuExecutionPolicy {
        AUTOMATIC_FP16(false, "FP16", "LITERT_OPENCL_FP16_GL_IO",
                "opencl-auto-v2"),
        AUTOMATIC_FP32(true, "FP32", "LITERT_OPENCL_FP32_GL_IO",
                "opencl-auto-fp32-v1");

        private final boolean forceFp32Compute;
        private final String computePrecisionLabel;
        private final String backendId;
        private final String compilerCacheSuffix;

        GpuExecutionPolicy(boolean forceFp32Compute,
                           String computePrecisionLabel, String backendId,
                           String compilerCacheSuffix) {
            this.forceFp32Compute = forceFp32Compute;
            this.computePrecisionLabel = computePrecisionLabel;
            this.backendId = backendId;
            this.compilerCacheSuffix = compilerCacheSuffix;
        }

        boolean forcesFp32Compute() {
            return forceFp32Compute;
        }

        String getComputePrecisionLabel() {
            return computePrecisionLabel;
        }

        String getBackendId() {
            return backendId;
        }

        String getCompilerCacheSuffix() {
            return compilerCacheSuffix;
        }
    }

    static final String DEPTH_ANYTHING_V2_SMALL_STATIC_ID =
            "depth-anything-v2-small-static-performance";
    static final String MIDAS_V2_STATIC_ID = "midas-v2-float";
    private static final String LEGACY_DEPTH_ANYTHING_V2_SMALL_QUALITY_ID =
            "depth-anything-v2-small-static-buckets";
    private static final String LEGACY_DEPTH_ANYTHING_V2_SMALL_DYNAMIC_ID =
            "depth-anything-v2-small-dynamic";
    private static final String LEGACY_DEPTH_ANYTHING_V2_SMALL_STATIC_350_ID =
            "depth-anything-v2-small-static-350";
    private static final String DEPTH_ANYTHING_V2_MODEL_ARCHIVE_ASSET =
            "client-sbs-dav2-models.tar.xz";
    private static final String MIDAS_V2_MODEL_ARCHIVE_ASSET =
            "client-sbs-midas-models.tar.xz";
    private static final String DEPTH_ANYTHING_V2_SMALL_STATIC_16_9_ASSET =
            "depth-anything-v2-small-static-322x182-fp16weights.tflite.model";
    private static final String DEPTH_ANYTHING_V2_SMALL_STATIC_16_9_SHA256 =
            "82f8594f4ee615ab82f968aa461a3960c4cd680293fd087cb65d8631b18e4271";
    private static final String DEPTH_ANYTHING_V2_SMALL_STATIC_21_9_ASSET =
            "depth-anything-v2-small-static-350x154-fp16weights.tflite.model";
    private static final String DEPTH_ANYTHING_V2_SMALL_STATIC_21_9_SHA256 =
            "2739f306ce71b19a913cdc32c779226a620f7f81685a1946ac213fdbeeba67b0";
    private static final String DEPTH_ANYTHING_V2_SMALL_STATIC_32_9_ASSET =
            "depth-anything-v2-small-static-434x126-fp16weights.tflite.model";
    private static final String DEPTH_ANYTHING_V2_SMALL_STATIC_32_9_SHA256 =
            "353eb80fd6b9c6f97552a20b7bd29f79466a9b05287dcd4bfd93baaa4c1730f5";
    private static final String MIDAS_V2_STATIC_16_9_ASSET =
            "midas-v2-small-static-352x192-fp16weights.tflite.model";
    private static final String MIDAS_V2_STATIC_16_9_SHA256 =
            "2a3ee0a1e818c4f785bcd0ceb10f5c81f08b3b91304f2f15d113c1089d3e524e";
    private static final String MIDAS_V2_STATIC_21_9_ASSET =
            "midas-v2-small-static-384x160-fp16weights.tflite.model";
    private static final String MIDAS_V2_STATIC_21_9_SHA256 =
            "5a66ab484a888c3c9e1642580ac3086c7d6d3175a860ca1e82f30d7a58c532bd";
    private static final String MIDAS_V2_STATIC_32_9_ASSET =
            "midas-v2-small-static-448x128-fp16weights.tflite.model";
    private static final String MIDAS_V2_STATIC_32_9_SHA256 =
            "060ec0e16fd4e20f2626d6ac51d80853a1bdf9b2f082c3d933099784cf9cabfb";

    /**
     * MiDaS v2.1 Small uses an EfficientNet-Lite3/refinement pyramid whose static dimensions must
     * be divisible by 32. These graphs share the verified Qualcomm float weights and keep public
     * tensors packed Float32 NHWC while storing large weights and computing internally in FP16.
     */
    static final ClientSbsModelManifest MIDAS_V2_STATIC_16_9 = createMidasStaticManifest(
            "midas-v2-static-352x192",
            MIDAS_V2_MODEL_ARCHIVE_ASSET,
            MIDAS_V2_STATIC_16_9_ASSET,
            MIDAS_V2_STATIC_16_9_SHA256,
            352, 192);

    static final ClientSbsModelManifest MIDAS_V2_STATIC_21_9 = createMidasStaticManifest(
            "midas-v2-static-384x160",
            MIDAS_V2_MODEL_ARCHIVE_ASSET,
            MIDAS_V2_STATIC_21_9_ASSET,
            MIDAS_V2_STATIC_21_9_SHA256,
            384, 160);

    static final ClientSbsModelManifest MIDAS_V2_STATIC_32_9 = createMidasStaticManifest(
            "midas-v2-static-448x128",
            MIDAS_V2_MODEL_ARCHIVE_ASSET,
            MIDAS_V2_STATIC_32_9_ASSET,
            MIDAS_V2_STATIC_32_9_SHA256,
            448, 128);

    private static final ClientSbsModelManifest[] MIDAS_V2_STATIC_BUCKETS = {
            MIDAS_V2_STATIC_16_9,
            MIDAS_V2_STATIC_21_9,
            MIDAS_V2_STATIC_32_9,
    };

    /** Galaxy-validated C4-aligned graph: 683/683 operators in one OpenCL FP16 partition. */
    static final ClientSbsModelManifest DEPTH_ANYTHING_V2_SMALL_STATIC_16_9 =
            createDepthAnythingStaticManifest(
                    "depth-anything-v2-small-static-322x182",
                    DEPTH_ANYTHING_V2_SMALL_STATIC_16_9_ASSET,
                    DEPTH_ANYTHING_V2_SMALL_STATIC_16_9_SHA256,
                    ClientSbsDepthInputShape.ASPECT_16_9);

    /** Galaxy-validated C4-aligned graph: 683/683 operators in one OpenCL FP16 partition. */
    static final ClientSbsModelManifest DEPTH_ANYTHING_V2_SMALL_STATIC_21_9 =
            createDepthAnythingStaticManifest(
                    "depth-anything-v2-small-static-350x154",
                    DEPTH_ANYTHING_V2_SMALL_STATIC_21_9_ASSET,
                    DEPTH_ANYTHING_V2_SMALL_STATIC_21_9_SHA256,
                    ClientSbsDepthInputShape.ASPECT_21_9);

    /** Galaxy-validated C4-aligned graph: 683/683 operators in one OpenCL FP16 partition. */
    static final ClientSbsModelManifest DEPTH_ANYTHING_V2_SMALL_STATIC_32_9 =
            createDepthAnythingStaticManifest(
                    "depth-anything-v2-small-static-434x126",
                    DEPTH_ANYTHING_V2_SMALL_STATIC_32_9_ASSET,
                    DEPTH_ANYTHING_V2_SMALL_STATIC_32_9_SHA256,
                    ClientSbsDepthInputShape.ASPECT_32_9);

    private static ClientSbsModelManifest createDepthAnythingStaticManifest(
            String id, String assetName, String assetSha256,
            ClientSbsDepthInputShape shape) {
        // DA-V2's output is disparity-like: larger value = nearer.
        requireHighIsNearDepth(id, true);
        return new ClientSbsModelManifest(
                id,
                DEPTH_ANYTHING_V2_MODEL_ARCHIVE_ASSET,
                assetName,
                assetSha256,
                new TensorSpec(0, "rgb_nhwc",
                        new int[] {1, shape.getHeight(), shape.getWidth(), 3}),
                new TensorSpec(0, "depth_bhwc",
                        new int[] {1, shape.getHeight(), shape.getWidth(), 1}),
                true,
                false,
                GpuExecutionPolicy.AUTOMATIC_FP16);
    }

    /** Package-private so physical-device tests can describe test-APK-only MiDaS graphs. */
    static ClientSbsModelManifest createMidasStaticManifest(
            String id, String modelArchiveAssetName, String assetName,
            String assetSha256, int width, int height) {
        if (width % 32 != 0 || height % 32 != 0) {
            throw new IllegalArgumentException("MiDaS static dimensions must be divisible by 32");
        }
        // MiDaS v2 emits inverse depth: larger value = nearer.
        requireHighIsNearDepth(id, true);
        return new ClientSbsModelManifest(
                id,
                modelArchiveAssetName,
                assetName,
                assetSha256,
                new TensorSpec(0, "image", new int[] {1, height, width, 3}),
                new TensorSpec(0, "depth_estimates", new int[] {1, height, width, 1}),
                true,
                false,
                GpuExecutionPolicy.AUTOMATIC_FP16);
    }

    /**
     * Describes an instrumentation-only graph whose single output exposes an internal DA-V2
     * checkpoint. Public tensors remain packed Float32 NHWC, but the checkpoint's spatial shape
     * and channel count intentionally do not have to match the renderer's one-channel depth
     * contract.
     */
    static ClientSbsModelManifest createCheckpointBenchmarkManifest(
            String id, String assetSha256, int[] inputShape, int[] outputShape,
            GpuExecutionPolicy gpuExecutionPolicy) {
        ClientSbsModelManifest manifest = new ClientSbsModelManifest(
                id,
                "external-checkpoint-models.tar.xz",
                "external-checkpoint.tflite",
                assetSha256,
                new TensorSpec(0, "rgb_nhwc", inputShape),
                new TensorSpec(0, "checkpoint_nhwc", outputShape),
                true,
                false,
                gpuExecutionPolicy);
        manifest.validateFloatGpuCheckpointContract();
        return manifest;
    }

    /** Selects one immutable model contract when a stream renderer is constructed. */
    static ClientSbsModelManifest forStream(String modelId, double sourceAspect) {
        if (MIDAS_V2_STATIC_ID.equals(modelId)) {
            return selectMidasStaticBucket(sourceAspect);
        }
        if (DEPTH_ANYTHING_V2_SMALL_STATIC_ID.equals(modelId)
                || LEGACY_DEPTH_ANYTHING_V2_SMALL_QUALITY_ID.equals(modelId)
                || LEGACY_DEPTH_ANYTHING_V2_SMALL_DYNAMIC_ID.equals(modelId)
                || LEGACY_DEPTH_ANYTHING_V2_SMALL_STATIC_350_ID.equals(modelId)) {
            ClientSbsDepthInputShape shape = ClientSbsDepthInputShape.select(sourceAspect);
            if (shape == ClientSbsDepthInputShape.ASPECT_16_9) {
                return DEPTH_ANYTHING_V2_SMALL_STATIC_16_9;
            }
            if (shape == ClientSbsDepthInputShape.ASPECT_21_9) {
                return DEPTH_ANYTHING_V2_SMALL_STATIC_21_9;
            }
            if (shape == ClientSbsDepthInputShape.ASPECT_32_9) {
                return DEPTH_ANYTHING_V2_SMALL_STATIC_32_9;
            }
            throw new IllegalStateException("Unregistered Client SBS depth bucket: " + shape);
        }
        throw new IllegalArgumentException("Unknown Client SBS depth model: " + modelId);
    }

    private static ClientSbsModelManifest selectMidasStaticBucket(double sourceAspect) {
        if (!Double.isFinite(sourceAspect) || sourceAspect <= 0.0) {
            throw new IllegalArgumentException("sourceAspect must be finite and positive");
        }

        ClientSbsModelManifest best = MIDAS_V2_STATIC_BUCKETS[0];
        double bestAspectError = Double.POSITIVE_INFINITY;
        for (ClientSbsModelManifest candidate : MIDAS_V2_STATIC_BUCKETS) {
            double candidateAspect = (double) candidate.getInputWidth()
                    / candidate.getInputHeight();
            double aspectError = Math.abs(Math.log(candidateAspect / sourceAspect));
            if (aspectError < bestAspectError) {
                bestAspectError = aspectError;
                best = candidate;
            }
        }
        return best;
    }

    private final String id;
    private final String modelArchiveAssetName;
    private final String assetName;
    private final String assetSha256;
    private final TensorSpec inputTensor;
    private final TensorSpec outputTensor;
    private final boolean directFullFrameResize;
    private final boolean dynamicSpatialShape;
    private final GpuExecutionPolicy gpuExecutionPolicy;

    /**
     * The whole SBS chain assumes the model emits HIGH-IS-NEAR relative depth: the subject estimate
     * scans the histogram from bin 255 as "near", and bestv2RawShift maps higher shaped depth to a
     * larger positive shift. Apollo has an explicit conversion step for this; the client has none,
     * so a low-is-near graph would render every scene inside-out with nothing catching it. Both
     * shipped families satisfy it — MiDaS v2 emits inverse depth and DA-V2's output is
     * disparity-like — but the assumption was previously implicit and untested. Fail at manifest
     * construction rather than silently inverting a future model.
     */
    private static void requireHighIsNearDepth(String id, boolean highIsNearDepth) {
        if (!highIsNearDepth) {
            throw new IllegalArgumentException(
                    "Model " + id + " declares low-is-near depth, which the client SBS chain does "
                            + "not implement: it would render inverted stereo");
        }
    }

    private ClientSbsModelManifest(String id, String modelArchiveAssetName, String assetName,
                                   String assetSha256,
                                   TensorSpec inputTensor, TensorSpec outputTensor,
                                   boolean directFullFrameResize,
                                   boolean dynamicSpatialShape,
                                   GpuExecutionPolicy gpuExecutionPolicy) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Model id must not be empty");
        }
        if (modelArchiveAssetName == null || modelArchiveAssetName.isEmpty()) {
            throw new IllegalArgumentException("Model archive asset must not be empty");
        }
        if (assetName == null || assetName.isEmpty()) {
            throw new IllegalArgumentException("Model asset must not be empty");
        }
        if (assetSha256 == null || !assetSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Model SHA-256 must be 64 lowercase hex digits");
        }
        if (gpuExecutionPolicy == null) {
            throw new IllegalArgumentException("GPU execution policy must not be null");
        }
        this.id = id;
        this.modelArchiveAssetName = modelArchiveAssetName;
        this.assetName = assetName;
        this.assetSha256 = assetSha256;
        this.inputTensor = inputTensor;
        this.outputTensor = outputTensor;
        this.directFullFrameResize = directFullFrameResize;
        this.dynamicSpatialShape = dynamicSpatialShape;
        this.gpuExecutionPolicy = gpuExecutionPolicy;
    }

    String getId() {
        return id;
    }

    String getAssetName() {
        return assetName;
    }

    String getModelArchiveAssetName() {
        return modelArchiveAssetName;
    }

    String getAssetSha256() {
        return assetSha256;
    }

    TensorSpec getInputTensor() {
        return inputTensor;
    }

    TensorSpec getOutputTensor() {
        return outputTensor;
    }

    int getInputWidth() {
        return inputTensor.getWidth();
    }

    int getInputHeight() {
        return inputTensor.getHeight();
    }

    int getOutputWidth() {
        return outputTensor.getWidth();
    }

    int getOutputHeight() {
        return outputTensor.getHeight();
    }

    int getInputByteSize() {
        return inputTensor.getByteSize();
    }

    int getOutputByteSize() {
        return outputTensor.getByteSize();
    }

    boolean usesDirectFullFrameResize() {
        return directFullFrameResize;
    }

    boolean hasDynamicSpatialShape() {
        return dynamicSpatialShape;
    }

    GpuExecutionPolicy getGpuExecutionPolicy() {
        return gpuExecutionPolicy;
    }

    int getDepthOutputWidth(float sourceAspect) {
        validateSourceAspect(sourceAspect);
        return directFullFrameResize ? getOutputWidth()
                : Math.max(1, Math.round(getOutputWidth() * Math.min(1.0f, sourceAspect)));
    }

    int getDepthOutputHeight(float sourceAspect) {
        validateSourceAspect(sourceAspect);
        return directFullFrameResize ? getOutputHeight()
                : Math.max(1, Math.round(getOutputHeight()
                * Math.min(1.0f, 1.0f / sourceAspect)));
    }

    private static void validateSourceAspect(float sourceAspect) {
        if (!Float.isFinite(sourceAspect) || sourceAspect <= 0.0f) {
            throw new IllegalArgumentException("sourceAspect must be finite and positive");
        }
    }

    void validateFloatGpuRendererContract() {
        if (inputTensor.getChannels() != 3 || outputTensor.getChannels() != 1
                || inputTensor.getWidth() != outputTensor.getWidth()
                || inputTensor.getHeight() != outputTensor.getHeight()) {
            throw new IllegalStateException("Client SBS model " + id
                    + " GPU tensor contract mismatch: expected FLOAT32 RGB input and same-size "
                    + "FLOAT32 depth output, got " + Arrays.toString(inputTensor.getShape())
                    + " -> " + Arrays.toString(outputTensor.getShape()));
        }
    }

    void validateFloatGpuCheckpointContract() {
        if (inputTensor.getBatch() != 1 || outputTensor.getBatch() != 1
                || inputTensor.getChannels() != 3) {
            throw new IllegalStateException("Client SBS checkpoint " + id
                    + " GPU tensor contract mismatch: expected batch-1 FLOAT32 RGB input and "
                    + "a batch-1 FLOAT32 NHWC output, got "
                    + Arrays.toString(inputTensor.getShape()) + " -> "
                    + Arrays.toString(outputTensor.getShape()));
        }
    }

    /** One packed Float32 NHWC tensor contract. The shape is copied for immutability. */
    static final class TensorSpec {
        private final int index;
        private final String name;
        private final int[] shape;
        private final int byteSize;

        private TensorSpec(int index, String name, int[] shape) {
            if (index < 0) {
                throw new IllegalArgumentException("Tensor index must not be negative");
            }
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Tensor name must not be empty");
            }
            if (shape == null || shape.length != 4) {
                throw new IllegalArgumentException("Client SBS tensors must use NHWC rank 4");
            }
            long elements = 1L;
            for (int dimension : shape) {
                if (dimension <= 0) {
                    throw new IllegalArgumentException("Tensor dimensions must be positive");
                }
                elements = Math.multiplyExact(elements, dimension);
            }
            long bytes = Math.multiplyExact(elements, Float.BYTES);
            if (bytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Tensor byte size exceeds Java buffer limits");
            }
            this.index = index;
            this.name = name;
            this.shape = shape.clone();
            this.byteSize = (int) bytes;
        }

        int getIndex() {
            return index;
        }

        String getName() {
            return name;
        }

        int[] getShape() {
            return shape.clone();
        }

        int getByteSize() {
            return byteSize;
        }

        int getHeight() {
            return shape[1];
        }

        int getBatch() {
            return shape[0];
        }

        int getWidth() {
            return shape[2];
        }

        int getChannels() {
            return shape[3];
        }
    }
}
