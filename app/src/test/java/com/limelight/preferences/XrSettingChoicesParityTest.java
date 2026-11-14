package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import android.content.res.Resources;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.ui.xrcontrols.SessionSettingsModel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * Pins the Global Settings arrays that {@code preferences.xml} feeds to
 * {@link InlineListPreference} to the in-session catalog in {@link XrSettingChoices}. The XML
 * keeps its localizable {@code @string} labels, so each array is checked here instead of being
 * generated from the Java lists.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrSettingChoicesParityTest {
    private Resources resources;

    @Before
    public void setUp() {
        resources = ApplicationProvider.getApplicationContext().getResources();
    }

    @Test
    public void resolutionArraysMatchTheCatalog() {
        assertIdsAndLabelsMatch(R.array.xr_resolution_values, R.array.xr_resolution_names,
                XrSettingChoices.RESOLUTION_CHOICES);
    }

    @Test
    public void frameRateArraysMatchTheCatalog() {
        assertIdsAndLabelsMatch(R.array.xr_fps_values, R.array.xr_fps_names,
                XrSettingChoices.FRAME_RATE_CHOICES);
    }

    @Test
    public void codecArraysMatchTheCatalog() {
        assertIdsAndLabelsMatch(R.array.xr_video_format_values, R.array.xr_video_format_names,
                XrSettingChoices.CODEC_CHOICES);
    }

    @Test
    public void framePacingArraysMatchTheCatalog() {
        assertIdsAndLabelsMatch(R.array.xr_video_frame_pacing_values,
                R.array.xr_video_frame_pacing_names, XrSettingChoices.PACING_CHOICES);
    }

    @Test
    public void audioLayoutArraysMatchTheCatalog() {
        assertIdsAndLabelsMatch(R.array.xr_audio_config_values, R.array.xr_audio_config_names,
                XrSettingChoices.AUDIO_CHOICES);
    }

    @Test
    public void streamGammaValuesMatchTheCatalog() {
        // Global Settings labels these "Windows default" / "Gamma 2.2" / "Gamma 2.4", while the
        // catalog labels are the bare stored values, so only ids and order are shared.
        assertIdsMatch(R.array.stream_gamma_values, XrSettingChoices.GAMMA_CHOICES);
        assertEquals(XrSettingChoices.GAMMA_CHOICES.size(),
                resources.getStringArray(R.array.stream_gamma_names).length);
    }

    private void assertIdsAndLabelsMatch(int valuesArray, int namesArray,
                                         List<SessionSettingsModel.Choice> catalog) {
        assertIdsMatch(valuesArray, catalog);
        String[] names = resources.getStringArray(namesArray);
        assertEquals(catalog.size(), names.length);
        for (int i = 0; i < names.length; i++) {
            assertEquals(catalog.get(i).label, names[i]);
        }
    }

    private void assertIdsMatch(int valuesArray, List<SessionSettingsModel.Choice> catalog) {
        String[] values = resources.getStringArray(valuesArray);
        assertEquals(catalog.size(), values.length);
        for (int i = 0; i < values.length; i++) {
            assertEquals(catalog.get(i).id, values[i]);
        }
    }
}
