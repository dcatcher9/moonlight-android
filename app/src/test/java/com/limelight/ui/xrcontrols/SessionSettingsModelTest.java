package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SessionSettingsModelTest {
    @Test
    public void pendingValueRemainsDistinctFromAppliedStreamValue() {
        SessionSettingsModel model = SessionSettingsModel.builder()
                .put(SessionSettingsModel.Key.FRAME_RATE,
                        "60 FPS", "90 FPS", SessionSettingsModel.Source.CURRENT_SESSION)
                .putApplied(SessionSettingsModel.Key.HDR,
                        "On", SessionSettingsModel.Source.GLOBAL)
                .build();

        SessionSettingsModel.Value frameRate = model.get(SessionSettingsModel.Key.FRAME_RATE);
        assertEquals("60 FPS", frameRate.appliedValue);
        assertEquals("90 FPS", frameRate.pendingValue);
        assertTrue(frameRate.reconnectRequired);
        assertTrue(frameRate.hasPendingChange());
        assertFalse(model.get(SessionSettingsModel.Key.HDR).hasPendingChange());
        assertTrue(model.hasPendingChanges());
    }

    @Test
    public void inheritedValuesDoNotInventSessionOverrides() {
        SessionSettingsModel model = SessionSettingsModel.builder()
                .putApplied(SessionSettingsModel.Key.RESOLUTION,
                        "3840 x 2160", SessionSettingsModel.Source.GLOBAL)
                .build();

        assertEquals(SessionSettingsModel.Source.GLOBAL,
                model.get(SessionSettingsModel.Key.RESOLUTION).source);
        assertFalse(model.hasPendingChanges());
    }

    @Test
    public void selectableValueCopiesChoicesAndExposesPendingSelectionId() {
        List<SessionSettingsModel.Choice> choices = new ArrayList<>(Arrays.asList(
                new SessionSettingsModel.Choice("60", "60 FPS"),
                new SessionSettingsModel.Choice("90", "90 FPS")));
        SessionSettingsModel model = SessionSettingsModel.builder()
                .put(SessionSettingsModel.Key.FRAME_RATE,
                        "60 FPS", "90 FPS", SessionSettingsModel.Source.CURRENT_SESSION,
                        choices, "90")
                .build();

        choices.clear();
        SessionSettingsModel.Value value = model.get(SessionSettingsModel.Key.FRAME_RATE);
        assertEquals("90", value.selectedChoiceId);
        assertEquals(Arrays.asList(
                new SessionSettingsModel.Choice("60", "60 FPS"),
                new SessionSettingsModel.Choice("90", "90 FPS")), value.choices);
        assertThrows(UnsupportedOperationException.class,
                () -> value.choices.add(new SessionSettingsModel.Choice("120", "120 FPS")));
    }

    @Test
    public void selectableValueRejectsMissingOrDuplicateChoiceIds() {
        assertThrows(IllegalArgumentException.class, () -> SessionSettingsModel.builder()
                .putApplied(SessionSettingsModel.Key.CODEC, "Auto",
                        SessionSettingsModel.Source.GLOBAL,
                        Arrays.asList(new SessionSettingsModel.Choice("auto", "Auto")),
                        "forceav1"));
        assertThrows(IllegalArgumentException.class, () -> SessionSettingsModel.builder()
                .putApplied(SessionSettingsModel.Key.CODEC, "Auto",
                        SessionSettingsModel.Source.GLOBAL,
                        Arrays.asList(
                                new SessionSettingsModel.Choice("auto", "Auto"),
                                new SessionSettingsModel.Choice("auto", "Automatic")),
                        "auto"));
    }
}
