package com.limelight.ui.xrcontrols;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Applied and pending Host SBS Raw per-eye resolution choice. */
public final class RawSbsModeSettingsModel {
    public static final String FULL_ID =
            PreferenceConfiguration.RawSbsPerEyeResolution.FULL.preferenceValue;
    public static final String HALF_ID =
            PreferenceConfiguration.RawSbsPerEyeResolution.HALF.preferenceValue;

    private static final List<SessionSettingsModel.Choice> CHOICES =
            Collections.unmodifiableList(Arrays.asList(
                    new SessionSettingsModel.Choice(FULL_ID, "Full"),
                    new SessionSettingsModel.Choice(HALF_ID, "Half")));

    public final PreferenceConfiguration.RawSbsPerEyeResolution appliedResolution;
    public final String appliedResolutionId;
    public final String appliedResolutionName;
    public final PreferenceConfiguration.RawSbsPerEyeResolution pendingResolution;
    public final String pendingResolutionId;
    public final String pendingResolutionName;
    public final SessionSettingsModel.Source source;
    public final List<SessionSettingsModel.Choice> choices;
    public final String selectedChoiceId;

    public RawSbsModeSettingsModel(
            PreferenceConfiguration.RawSbsPerEyeResolution appliedResolution,
            PreferenceConfiguration.RawSbsPerEyeResolution pendingResolution,
            SessionSettingsModel.Source source) {
        this.appliedResolution = Objects.requireNonNull(
                appliedResolution, "appliedResolution");
        this.appliedResolutionId = idFor(appliedResolution);
        this.appliedResolutionName = nameFor(appliedResolution);
        this.pendingResolution = Objects.requireNonNull(
                pendingResolution, "pendingResolution");
        this.pendingResolutionId = idFor(pendingResolution);
        this.pendingResolutionName = nameFor(pendingResolution);
        this.source = Objects.requireNonNull(source, "source");
        this.choices = CHOICES;
        this.selectedChoiceId = pendingResolutionId;
    }

    public boolean hasPendingChange() {
        return appliedResolution != pendingResolution;
    }

    public static String idFor(PreferenceConfiguration.RawSbsPerEyeResolution resolution) {
        switch (Objects.requireNonNull(resolution, "resolution")) {
            case FULL:
                return FULL_ID;
            case HALF:
                return HALF_ID;
            default:
                throw new IllegalArgumentException("Unknown per-eye resolution: " + resolution);
        }
    }

    public static String nameFor(PreferenceConfiguration.RawSbsPerEyeResolution resolution) {
        switch (Objects.requireNonNull(resolution, "resolution")) {
            case FULL:
                return "Full";
            case HALF:
                return "Half";
            default:
                throw new IllegalArgumentException("Unknown per-eye resolution: " + resolution);
        }
    }
}
