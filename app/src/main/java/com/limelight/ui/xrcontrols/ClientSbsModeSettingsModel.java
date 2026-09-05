package com.limelight.ui.xrcontrols;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.ClientSbsPipelineContract;

import java.util.Objects;

/** Stream bucket and backend status for the fixed production ZipDepth model. */
public final class ClientSbsModeSettingsModel {
    public final String bucket;
    public final String status;

    public ClientSbsModeSettingsModel(String bucket, String status) {
        this.bucket = requireText(bucket, "bucket");
        this.status = requireText(status, "status");
    }

    /** Select the same immutable aspect bucket that will be compiled when the stream reconnects. */
    public static String selectBucket(int streamWidth, int streamHeight) {
        if (streamWidth <= 0 || streamHeight <= 0) {
            throw new IllegalArgumentException("stream dimensions must be positive");
        }
        ClientSbsPipelineContract contract = ClientSbsPipelineContract.forStream(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16,
                (double) streamWidth / streamHeight);
        return contract.getModelInputWidth() + " x " + contract.getModelInputHeight();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
