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

    static final String ZIPDEPTH_BASE_FP16_ID = "zipdepth-base-fp16";
    private static final String ZIPDEPTH_BASE_MODEL_ARCHIVE_ASSET =
            "client-sbs-zipdepth-models.tar.xz";
    private static final String ZIPDEPTH_BASE_STATIC_16_9_ASSET =
            "zipdepth-base-static-672x384-fp16weights.tflite.model";
    private static final String ZIPDEPTH_BASE_STATIC_16_9_SHA256 =
            "6296d5c2e4f857fd551d854ebf4dd2ab2462c0d7372d526bf0a7463718b8b6d1";
    private static final String ZIPDEPTH_BASE_STATIC_21_9_ASSET =
            "zipdepth-base-static-896x384-fp16weights.tflite.model";
    private static final String ZIPDEPTH_BASE_STATIC_21_9_SHA256 =
            "31467ab0cd187b74c65b3b20f4850973309d120519b587610e3dd3e27b72df4a";
    private static final String ZIPDEPTH_BASE_STATIC_32_9_ASSET =
            "zipdepth-base-static-928x384-fp16weights.tflite.model";
    private static final String ZIPDEPTH_BASE_STATIC_32_9_SHA256 =
            "169d5e8802bea9aac839df6acb4a8dd8e92a53728ea6f4e39a4baca453fd34cc";

    /**
     * Original ZipDepth Base graphs with the learned standard upsampler at short side 384.
     * Input normalization is embedded in each graph. Public input/output tensors stay Float32
     * NHWC while weights and delegated compute use FP16.
     */
    static final ClientSbsModelManifest ZIPDEPTH_BASE_STATIC_16_9 =
            createZipDepthBaseManifest(
                    "zipdepth-base-static-672x384",
                    ZIPDEPTH_BASE_STATIC_16_9_ASSET,
                    ZIPDEPTH_BASE_STATIC_16_9_SHA256,
                    672, 384,
                    0.04864449f);

    static final ClientSbsModelManifest ZIPDEPTH_BASE_STATIC_21_9 =
            createZipDepthBaseManifest(
                    "zipdepth-base-static-896x384",
                    ZIPDEPTH_BASE_STATIC_21_9_ASSET,
                    ZIPDEPTH_BASE_STATIC_21_9_SHA256,
                    896, 384,
                    0.04707071f);

    static final ClientSbsModelManifest ZIPDEPTH_BASE_STATIC_32_9 =
            createZipDepthBaseManifest(
                    "zipdepth-base-static-928x384",
                    ZIPDEPTH_BASE_STATIC_32_9_ASSET,
                    ZIPDEPTH_BASE_STATIC_32_9_SHA256,
                    928, 384,
                    0.05421491f);

    private static final ClientSbsModelManifest[] ZIPDEPTH_BASE_STATIC_BUCKETS = {
            ZIPDEPTH_BASE_STATIC_16_9,
            ZIPDEPTH_BASE_STATIC_21_9,
            ZIPDEPTH_BASE_STATIC_32_9,
    };

    /** The production ZipDepth aspect buckets, ordered by ascending aspect. */
    static ClientSbsModelManifest[] productionBuckets() {
        return ZIPDEPTH_BASE_STATIC_BUCKETS.clone();
    }

    /** Whether {@code manifest} is one of the production ZipDepth aspect buckets. */
    static boolean isProduction(ClientSbsModelManifest manifest) {
        for (ClientSbsModelManifest bucket : ZIPDEPTH_BASE_STATIC_BUCKETS) {
            if (bucket == manifest) {
                return true;
            }
        }
        return false;
    }

    private static ClientSbsModelManifest createZipDepthBaseManifest(
            String id, String assetName, String assetSha256, int width, int height,
            float v2RawCoordinateScale) {
        if (width % 32 != 0 || height % 32 != 0) {
            throw new IllegalArgumentException(
                    "ZipDepth static dimensions must be divisible by 32");
        }
        // ZipDepth emits nonnegative affine-invariant inverse depth: larger value = nearer. The V2
        // coordinate chain implements only that high-is-near polarity.
        // These graph-specific priors came from the historical first-frame-centered fit against
        // Apollo's fused DAV2 field. The adaptive camera uses each as its minimum raw amplitude;
        // it does not use the old shot-latched mapping. Current preprocessing/runtime and the
        // new floor policy require matched-data qualification before these values are retuned.
        if (!Float.isFinite(v2RawCoordinateScale) || v2RawCoordinateScale <= 0.0f) {
            throw new IllegalArgumentException(
                    "ZipDepth V2 raw coordinate scale must be finite and positive");
        }
        return new ClientSbsModelManifest(
                id,
                ZIPDEPTH_BASE_MODEL_ARCHIVE_ASSET,
                assetName,
                assetSha256,
                new TensorSpec("image", new int[] {1, height, width, 3}),
                new TensorSpec("depth", new int[] {1, height, width, 1}),
                true,
                GpuExecutionPolicy.AUTOMATIC_FP16,
                v2RawCoordinateScale);
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
                new TensorSpec("rgb_nhwc", inputShape),
                new TensorSpec("checkpoint_nhwc", outputShape),
                true,
                gpuExecutionPolicy,
                Float.NaN);
        manifest.validateFloatGpuCheckpointContract();
        return manifest;
    }

    /** Selects the sole production ZipDepth contract when a stream renderer is constructed. */
    static ClientSbsModelManifest forStream(String modelId, double sourceAspect) {
        if (!ZIPDEPTH_BASE_FP16_ID.equals(modelId)) {
            throw new IllegalArgumentException(
                    "Only ZipDepth Base is supported by the production Client SBS renderer");
        }
        return selectNearestStaticBucket(ZIPDEPTH_BASE_STATIC_BUCKETS, sourceAspect);
    }

    private static ClientSbsModelManifest selectNearestStaticBucket(
            ClientSbsModelManifest[] buckets, double sourceAspect) {
        if (!Double.isFinite(sourceAspect) || sourceAspect <= 0.0) {
            throw new IllegalArgumentException("sourceAspect must be finite and positive");
        }

        ClientSbsModelManifest best = buckets[0];
        double bestAspectError = Double.POSITIVE_INFINITY;
        for (ClientSbsModelManifest candidate : buckets) {
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
    private final GpuExecutionPolicy gpuExecutionPolicy;
    private final float v2RawCoordinateScale;

    private ClientSbsModelManifest(String id, String modelArchiveAssetName, String assetName,
                                   String assetSha256,
                                   TensorSpec inputTensor, TensorSpec outputTensor,
                                   boolean directFullFrameResize,
                                   GpuExecutionPolicy gpuExecutionPolicy,
                                   float v2RawCoordinateScale) {
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
        this.gpuExecutionPolicy = gpuExecutionPolicy;
        this.v2RawCoordinateScale = v2RawCoordinateScale;
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

    GpuExecutionPolicy getGpuExecutionPolicy() {
        return gpuExecutionPolicy;
    }

    float getV2RawCoordinateScale() {
        if (!Float.isFinite(v2RawCoordinateScale) || v2RawCoordinateScale <= 0.0f) {
            throw new IllegalStateException("Model " + id
                    + " has no Depth Coordinate V2 raw calibration");
        }
        return v2RawCoordinateScale;
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
        private final String name;
        private final int[] shape;
        private final int byteSize;

        private TensorSpec(String name, int[] shape) {
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
            this.name = name;
            this.shape = shape.clone();
            this.byteSize = (int) bytes;
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
