package com.limelight.ui.xrcontrols;

import com.limelight.utils.ClientSbsPipelineContract;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Applied/pending Client SBS model choice plus stream-fixed bucket and backend status. */
public final class ClientSbsModeSettingsModel {
    public final String appliedModelId;
    public final String appliedModelName;
    public final String pendingModelId;
    public final String pendingModelName;
    public final SessionSettingsModel.Source source;
    public final String bucket;
    public final String status;
    public final List<SessionSettingsModel.Choice> choices;
    public final String selectedChoiceId;

    public ClientSbsModeSettingsModel(String appliedModelId, String appliedModelName,
                                      String pendingModelId, String pendingModelName,
                                      SessionSettingsModel.Source source,
                                      String bucket, String status) {
        this(appliedModelId, appliedModelName, pendingModelId, pendingModelName,
                source, bucket, status, Collections.emptyList(), null);
    }

    public ClientSbsModeSettingsModel(String appliedModelId, String appliedModelName,
                                      String pendingModelId, String pendingModelName,
                                      SessionSettingsModel.Source source,
                                      String bucket, String status,
                                      List<SessionSettingsModel.Choice> choices,
                                      String selectedChoiceId) {
        this.appliedModelId = requireText(appliedModelId, "appliedModelId");
        this.appliedModelName = requireText(appliedModelName, "appliedModelName");
        this.pendingModelId = requireText(pendingModelId, "pendingModelId");
        this.pendingModelName = requireText(pendingModelName, "pendingModelName");
        this.source = Objects.requireNonNull(source, "source");
        this.bucket = requireText(bucket, "bucket");
        this.status = requireText(status, "status");
        this.choices = SessionSettingsModel.immutableChoices(choices, selectedChoiceId);
        this.selectedChoiceId = selectedChoiceId;
    }

    public boolean hasPendingModelChange() {
        return !appliedModelId.equals(pendingModelId);
    }

    /** Select the same immutable aspect bucket that will be compiled when the stream reconnects. */
    public static String selectBucket(String modelId, int streamWidth, int streamHeight) {
        if (streamWidth <= 0 || streamHeight <= 0) {
            throw new IllegalArgumentException("stream dimensions must be positive");
        }
        ClientSbsPipelineContract contract = ClientSbsPipelineContract.forStream(
                modelId, (double) streamWidth / streamHeight);
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
