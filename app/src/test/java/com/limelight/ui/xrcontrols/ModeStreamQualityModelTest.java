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
    public void selectedSavedTupleRequiresReconnectWithoutNewlyStagedEdit() {
        ModeStreamQualityModel selected = model(SAVED, SAVED, LIVE, true);
        ModeStreamQualityModel unselected = model(SAVED, SAVED, LIVE, false);

        assertFalse(selected.hasPendingChanges());
        assertTrue(selected.requiresReconnect());
        assertFalse(unselected.hasPendingChanges());
        assertFalse(unselected.requiresReconnect());
    }

    @Test
    public void stagedSelectedTupleReportsPendingAndReconnect() {
        ModeStreamQualityModel model = model(LIVE, SAVED, LIVE, true);

        assertTrue(model.hasPendingChanges());
        assertTrue(model.requiresReconnect());
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
        return ModeStreamQualityModel.builder(applied, pending, live, selected)
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
