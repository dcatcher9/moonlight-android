package com.limelight.utils;

import java.util.Objects;

/**
 * Complete identity of the Client SBS pipeline state that is immutable for one renderer.
 *
 * <p>Model families do not share aspect-bucket boundaries, and the reprojection probe count is
 * compiled into shader source using its own bucket table. A live resize is safe only when both
 * identities, along with every manifest-derived target dimension, remain unchanged.</p>
 */
public final class ClientSbsPipelineContract {
    private final ClientSbsModelManifest modelManifest;
    private final String modelManifestId;
    private final int modelInputWidth;
    private final int modelInputHeight;
    private final int depthOutputWidth;
    private final int depthOutputHeight;
    private final boolean directFullFrameResize;
    private final float modelContentAspect;
    private final int aspectIdentity;
    private final int reprojectionProbeSteps;

    private ClientSbsPipelineContract(ClientSbsModelManifest modelManifest, float sourceAspect) {
        this.modelManifest = modelManifest;
        modelManifestId = modelManifest.getId();
        modelInputWidth = modelManifest.getInputWidth();
        modelInputHeight = modelManifest.getInputHeight();
        float modelAspect = modelInputWidth / (float) modelInputHeight;
        boolean portraitAspectFit = sourceAspect < 1.0f && modelAspect > 1.0f;
        directFullFrameResize =
                modelManifest.usesDirectFullFrameResize() && !portraitAspectFit;
        // Portrait streams are real W x H streams. Preserve that aspect inside the available
        // landscape model by reflecting side padding rather than stretching the image across it.
        // The model-relative ratio must drive both input rendering and output cropping.
        modelContentAspect = portraitAspectFit ? sourceAspect / modelAspect : sourceAspect;
        depthOutputWidth = directFullFrameResize ? modelManifest.getOutputWidth()
                : Math.max(1, Math.round(modelManifest.getOutputWidth()
                * Math.min(1.0f, modelContentAspect)));
        depthOutputHeight = directFullFrameResize ? modelManifest.getOutputHeight()
                : Math.max(1, Math.round(modelManifest.getOutputHeight()
                * Math.min(1.0f, 1.0f / modelContentAspect)));
        // A padded graph sizes processor state continuously from the effective content aspect.
        aspectIdentity = directFullFrameResize ? 0 : Float.floatToIntBits(modelContentAspect);
        reprojectionProbeSteps = ClientSbsShaders.probeStepsForAspect(sourceAspect);
    }

    /** Resolves the exact immutable graph/target/shader contract for one stream aspect. */
    public static ClientSbsPipelineContract forStream(String modelId, double sourceAspect) {
        if (!Double.isFinite(sourceAspect) || sourceAspect <= 0.0) {
            throw new IllegalArgumentException("Source aspect must be finite and positive");
        }
        float rendererAspect = (float) sourceAspect;
        return new ClientSbsPipelineContract(
                ClientSbsModelManifest.forStream(modelId, rendererAspect), rendererAspect);
    }

    /** Whether two aspects can share one already-constructed Client SBS pipeline. */
    public static boolean sameForStream(String modelId, double firstAspect, double secondAspect) {
        return forStream(modelId, firstAspect).equals(forStream(modelId, secondAspect));
    }

    /** Stable selected graph identity, useful for diagnostics and boundary tests. */
    public String getModelManifestId() {
        return modelManifestId;
    }

    /** Probe-loop bound compiled into both reprojection shader programs. */
    public int getReprojectionProbeSteps() {
        return reprojectionProbeSteps;
    }

    ClientSbsModelManifest getModelManifest() {
        return modelManifest;
    }

    int getDepthOutputWidth() {
        return depthOutputWidth;
    }

    int getDepthOutputHeight() {
        return depthOutputHeight;
    }

    boolean usesDirectFullFrameResize() {
        return directFullFrameResize;
    }

    float getModelContentAspect() {
        return modelContentAspect;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClientSbsPipelineContract)) {
            return false;
        }
        ClientSbsPipelineContract that = (ClientSbsPipelineContract) other;
        return modelInputWidth == that.modelInputWidth
                && modelInputHeight == that.modelInputHeight
                && depthOutputWidth == that.depthOutputWidth
                && depthOutputHeight == that.depthOutputHeight
                && directFullFrameResize == that.directFullFrameResize
                && aspectIdentity == that.aspectIdentity
                && reprojectionProbeSteps == that.reprojectionProbeSteps
                && modelManifestId.equals(that.modelManifestId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelManifestId, modelInputWidth, modelInputHeight,
                depthOutputWidth, depthOutputHeight, directFullFrameResize, aspectIdentity,
                reprojectionProbeSteps);
    }

    @Override
    public String toString() {
        return modelManifestId + " input=" + modelInputWidth + "x" + modelInputHeight
                + " depth=" + depthOutputWidth + "x" + depthOutputHeight
                + " inputResize=" + (directFullFrameResize ? "direct" : "aspect-fit")
                + " probes=" + reprojectionProbeSteps;
    }
}
