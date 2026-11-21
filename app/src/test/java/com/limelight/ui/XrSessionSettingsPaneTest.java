package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.widget.TextView;

import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.Space;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.ClientSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** The shared pane labels a staged change by how it will actually apply. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrSessionSettingsPaneTest {
    private static final StreamQualityTuple LIVE =
            new StreamQualityTuple("1920x1080", "60", 100000);
    private static final StreamQualityTuple STAGED =
            new StreamQualityTuple("1920x1080", "60", 200000);

    private ActivityController<Activity> controller;
    private XrStreamPresenter presenter;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class);
        controller.get().setTheme(R.style.AppTheme);
        controller.setup();
        presenter = new XrStreamPresenter(controller.get(),
                PreferenceConfiguration.readPreferences(controller.get()),
                surface -> { }, visible -> { });
    }

    @After
    public void tearDown() {
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void liveApplicableBitrateChangeIsNotLabelledReconnectRequired() {
        assertEquals(controller.get().getString(R.string.xr_setting_pending_live, "100 Mbps"),
                bitratePendingText(false));
    }

    @Test
    public void bitrateChangeThatMustReconnectSaysSo() {
        assertEquals(controller.get().getString(R.string.xr_setting_pending_active, "100 Mbps"),
                bitratePendingText(true));
    }

    @Test
    public void sharedReconnectSettingsKeepTheirReconnectLabel() {
        SessionSettingsModel.Value hdr = new SessionSettingsModel.Value("On", "Off",
                SessionSettingsModel.Source.CURRENT_SESSION, true,
                Arrays.asList(new SessionSettingsModel.Choice("true", "On"),
                        new SessionSettingsModel.Choice("false", "Off")), "false");
        install(SessionSettingsModel.builder()
                .put(SessionSettingsModel.Key.HDR, hdr).build(), false);

        assertEquals(controller.get().getString(R.string.xr_setting_pending_active, "On"),
                pendingText(SessionSettingsModel.Key.HDR));
    }

    @Test
    public void paneRendersAndPlacesOnlyWhileShown() {
        install(SessionSettingsModel.builder().build(), false);
        PanelEntity panel = mock(PanelEntity.class);
        when(panel.getPose(Space.PARENT)).thenReturn(Pose.Identity);
        android.widget.FrameLayout host = new android.widget.FrameLayout(controller.get());
        ReflectionHelpers.setField(pane(), "panel", panel);
        ReflectionHelpers.setField(pane(), "contentHost", host);
        Pose shown = new Pose(new Vector3(-1f, 0f, 0.2f), Quaternion.Identity);

        XrSessionSettingsPane settings = (XrSessionSettingsPane) pane();
        settings.applyVisibility(true, shown);
        assertEquals(1, host.getChildCount());
        verify(panel).setPose(shown);
        verify(panel).setEnabled(true);

        settings.applyVisibility(false, null);
        verify(panel).setEnabled(false);
        verify(panel, times(1)).setPose(any(Pose.class));
        assertTrue(ReflectionHelpers.<Map<?, ?>>getField(pane(), "sessionPendingViews").isEmpty());

        settings.disposePanel();
        verify(panel).dispose();
        assertFalse(settings.hasPanel());
        assertFalse(settings.hasContentHost());
    }

    private String bitratePendingText(boolean qualityDeltaRequiresReconnect) {
        SessionSettingsModel.Value bitrate = new SessionSettingsModel.Value(
                "100 Mbps", "200 Mbps", SessionSettingsModel.Source.CURRENT_SESSION, true,
                Arrays.asList(new SessionSettingsModel.Choice("100000", "100 Mbps"),
                        new SessionSettingsModel.Choice("200000", "200 Mbps")), "200000");
        install(SessionSettingsModel.builder()
                .put(SessionSettingsModel.Key.BITRATE, bitrate).build(),
                qualityDeltaRequiresReconnect);
        return pendingText(SessionSettingsModel.Key.BITRATE);
    }

    private void install(SessionSettingsModel session, boolean qualityDeltaRequiresReconnect) {
        SessionSettingsModel.Value value = new SessionSettingsModel.Value(
                "live", "staged", SessionSettingsModel.Source.CURRENT_SESSION, true);
        Map<PresentationMode, ModeStreamQualityModel> models =
                new EnumMap<>(PresentationMode.class);
        for (PresentationMode mode : PresentationMode.values()) {
            models.put(mode, ModeStreamQualityModel.builder(
                            LIVE, STAGED, LIVE, mode == PresentationMode.NORMAL)
                    .setQualityDeltaRequiresReconnect(qualityDeltaRequiresReconnect)
                    .put(SessionSettingsModel.Key.RESOLUTION, value)
                    .put(SessionSettingsModel.Key.FRAME_RATE, value)
                    .put(SessionSettingsModel.Key.BITRATE, value)
                    .build());
        }
        presenter.setSettingsModels(session, models,
                new ClientSbsModeSettingsModel("640 x 384", "Ready"), true,
                qualityDeltaRequiresReconnect);
        ReflectionHelpers.callInstanceMethod(pane(), "buildSessionSettingsView");
    }

    private String pendingText(SessionSettingsModel.Key key) {
        Map<SessionSettingsModel.Key, TextView> pending =
                ReflectionHelpers.getField(pane(), "sessionPendingViews");
        return pending.get(key).getText().toString();
    }

    private Object pane() {
        return ReflectionHelpers.getField(presenter, "sessionSettingsPane");
    }
}
