package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

public final class RawSbsModeSettingsModelTest {
    @Test
    public void exposesOnlyFullAndHalfAndSelectsPendingValue() {
        RawSbsModeSettingsModel model = new RawSbsModeSettingsModel(
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                SessionSettingsModel.Source.CURRENT_SESSION);

        assertSame(PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                model.appliedResolution);
        assertEquals("full", model.appliedResolutionId);
        assertEquals("Full", model.appliedResolutionName);
        assertSame(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                model.pendingResolution);
        assertEquals("half", model.pendingResolutionId);
        assertEquals("Half", model.pendingResolutionName);
        assertEquals(SessionSettingsModel.Source.CURRENT_SESSION, model.source);
        assertEquals("half", model.selectedChoiceId);
        assertEquals(2, model.choices.size());
        assertEquals(new SessionSettingsModel.Choice("full", "Full"), model.choices.get(0));
        assertEquals(new SessionSettingsModel.Choice("half", "Half"), model.choices.get(1));
        assertThrows(UnsupportedOperationException.class,
                () -> model.choices.add(new SessionSettingsModel.Choice("other", "Other")));
    }

    @Test
    public void detectsPendingResolutionChange() {
        RawSbsModeSettingsModel unchanged = new RawSbsModeSettingsModel(
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                SessionSettingsModel.Source.GLOBAL);
        RawSbsModeSettingsModel changed = new RawSbsModeSettingsModel(
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                SessionSettingsModel.Source.CURRENT_SESSION);

        assertFalse(unchanged.hasPendingChange());
        assertTrue(changed.hasPendingChange());
    }

    @Test
    public void constructorRejectsMissingValues() {
        assertThrows(NullPointerException.class, () -> new RawSbsModeSettingsModel(
                null,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                SessionSettingsModel.Source.GLOBAL));
        assertThrows(NullPointerException.class, () -> new RawSbsModeSettingsModel(
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                null,
                SessionSettingsModel.Source.GLOBAL));
        assertThrows(NullPointerException.class, () -> new RawSbsModeSettingsModel(
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                null));
    }
}
