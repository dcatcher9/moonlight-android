package com.limelight.utils;

import java.util.Arrays;

/** Immutable model and tensor contract for the native client-SBS GPU pipeline. */
final class ClientSbsModelManifest {
    /**
     * Qualcomm AI Hub's float export. Public tensors are packed Float32 NHWC while LiteRT keeps
     * its internal FP16 PHWC4 representation on the GPU.
     */
    static final ClientSbsModelManifest MIDAS_V2_FLOAT = new ClientSbsModelManifest(
            "midas-v2-float",
            "midas-midas-v2-float.tflite",
            "3990551be4f21be7bffc71c159bb643279af221c6e8b328ce265374776ff2ec1",
            new TensorSpec(0, "image", new int[] {1, 256, 256, 3}),
            new TensorSpec(0, "depth_estimates", new int[] {1, 256, 256, 1}));

    private final String id;
    private final String assetName;
    private final String assetSha256;
    private final TensorSpec inputTensor;
    private final TensorSpec outputTensor;

    private ClientSbsModelManifest(String id, String assetName, String assetSha256,
                                   TensorSpec inputTensor, TensorSpec outputTensor) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Model id must not be empty");
        }
        if (assetName == null || assetName.isEmpty()) {
            throw new IllegalArgumentException("Model asset must not be empty");
        }
        if (assetSha256 == null || !assetSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Model SHA-256 must be 64 lowercase hex digits");
        }
        this.id = id;
        this.assetName = assetName;
        this.assetSha256 = assetSha256;
        this.inputTensor = inputTensor;
        this.outputTensor = outputTensor;
    }

    String getId() {
        return id;
    }

    String getAssetName() {
        return assetName;
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

        int getWidth() {
            return shape[2];
        }

        int getChannels() {
            return shape[3];
        }
    }
}
