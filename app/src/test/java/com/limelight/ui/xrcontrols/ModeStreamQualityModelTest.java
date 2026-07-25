package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ModeStreamQualityModelTest {
    private static final StreamQualityTuple LIVE =
            new StreamQualityTuple("1920x1080", "60", 20000);
    private static final StreamQualityTuple SAVED =
            new StreamQualityTuple("3840x2160", "90", 100000);

    @Test
    public void selectedSavedTupleRequiresApplyWithoutNewlyStagedEdit() {
        ModeStreamQualityModel selected = model(SAVED, SAVED, LIVE, true);
        ModeStreamQualityModel unselected = model(SAVED, SAVED, LIVE, false);

        assertFalse(selected.hasPendingChanges());
        assertTrue(selected.requiresApply());
        assertFalse(unselected.hasPendingChanges());
        assertFalse(unselected.requiresApply());
        assertFalse(unselected.requiresReconnect());
    }

    @Test
    public void stagedSelectedTupleReportsPendingAndApply() {
        ModeStreamQualityModel model = model(LIVE, SAVED, LIVE, true);

        assertTrue(model.hasPendingChanges());
        assertTrue(model.requiresApply());
    }

    @Test
    public void liveApplicableDeltaAppliesWithoutReconnect() {
        ModeStreamQualityModel model = model(LIVE, SAVED, LIVE, true, false, false);

        assertTrue(model.requiresApply());
        assertFalse(model.requiresReconnect());
        assertTrue(model.appliesLive());
    }

    @Test
    public void deltaThatCannotBeAppliedLiveRequiresReconnect() {
        ModeStreamQualityModel model = model(LIVE, SAVED, LIVE, true, false, true);

        assertTrue(model.requiresApply());
        assertTrue(model.requiresReconnect());
        assertFalse(model.appliesLive());
    }

    @Test
    public void transportBoundaryIsAlwaysReconnectEvenWithNoQualityDelta() {
        ModeStreamQualityModel model = model(LIVE, LIVE, LIVE, true, true, false);

        assertTrue(model.requiresApply());
        assertTrue(model.requiresReconnect());
        assertFalse(model.appliesLive());
    }

    @Test
    public void selectedTransportBoundaryRequiresReconnectWithIdenticalLogicalTuple() {
        ModeStreamQualityModel selected = model(LIVE, LIVE, LIVE, true, true);
        ModeStreamQualityModel unselected = model(LIVE, LIVE, LIVE, false, true);

        assertFalse(selected.hasPendingChanges());
        assertTrue(selected.requiresReconnect());
        assertFalse(unselected.requiresReconnect());
    }

    @Test
    public void builderRejectsSharedKeysAndIncompleteQuality() {
        ModeStreamQualityModel.Builder builder = ModeStreamQualityModel.builder(
                LIVE, LIVE, LIVE, true);
        assertThrows(IllegalArgumentException.class, () -> builder.put(
                SessionSettingsModel.Key.CODEC, value("Auto", "Auto")));
        assertThrows(IllegalStateException.class, builder::build);
    }

    private static ModeStreamQualityModel model(StreamQualityTuple applied,
                                                StreamQualityTuple pending,
                                                StreamQualityTuple live,
                                                boolean selected) {
        return model(applied, pending, live, selected, false);
    }

    private static ModeStreamQualityModel model(StreamQualityTuple applied,
                                                StreamQualityTuple pending,
                                                StreamQualityTuple live,
                                                boolean selected,
                                                boolean transportReconnectRequired) {
        return model(applied, pending, live, selected, transportReconnectRequired, true);
    }

    private static ModeStreamQualityModel model(StreamQualityTuple applied,
                                                StreamQualityTuple pending,
                                                StreamQualityTuple live,
                                                boolean selected,
                                                boolean transportReconnectRequired,
                                                boolean qualityDeltaRequiresReconnect) {
        return ModeStreamQualityModel.builder(applied, pending, live, selected)
                .setTransportReconnectRequired(transportReconnectRequired)
                .setQualityDeltaRequiresReconnect(qualityDeltaRequiresReconnect)
                .put(SessionSettingsModel.Key.RESOLUTION,
                        value(applied.resolution, pending.resolution))
                .put(SessionSettingsModel.Key.FRAME_RATE,
                        value(applied.frameRate, pending.frameRate))
                .put(SessionSettingsModel.Key.BITRATE,
                        value(String.valueOf(applied.bitrateKbps),
                                String.valueOf(pending.bitrateKbps)))
                .build();
    }

    private static SessionSettingsModel.Value value(String applied, String pending) {
        return new SessionSettingsModel.Value(applied, pending,
                SessionSettingsModel.Source.CURRENT_SESSION, true);
    }
}
