package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.BuildConfig;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.XrChoiceGroup;
import com.limelight.preferences.XrResolutionOptions;
import com.limelight.sbs.ClientSbsMetricHistory;
import com.limelight.ui.xrcontrols.AuthoredStereoModeState;
import com.limelight.ui.xrcontrols.AuthoredStereoModeState.MoviePictureFormat;
import com.limelight.ui.xrcontrols.ClientSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.XrControlUiState;
import com.limelight.ui.xrcontrols.XrResolutionSelector;
import com.limelight.ui.xrcontrols.XrSparklineView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrStreamPresenterViewTest {

    @Test
    public void dockHasOneDisconnectActionAndItsTileDispatchesOnce() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        prefs.enablePerfOverlay = false;
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, prefs, surface -> { }, visible -> { });
        int[] disconnects = { 0 };
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public boolean onDisconnectRequested() {
                disconnects[0]++;
                return true;
            }
        });
        // Keep real hosted Android Views and their click handlers; only SceneCore entities are mocked.
        try (MockedStatic<PanelEntity> panels = mockStatic(PanelEntity.class,
                invocation -> mock(PanelEntity.class))) {
            // buildControlBar creates the dock gaze tracker, which requires a Session.
            setField(presenter, "session", mock(androidx.xr.runtime.Session.class));
            Method build = XrStreamPresenter.class.getDeclaredMethod("buildControlBar", float.class);
            build.setAccessible(true);
            build.invoke(presenter, 2.0f);

            List<?> items = (List<?>) getField(presenter, "barItems");
            assertEquals(BuildConfig.DEBUG ? 10 : 9, items.size());
            List<String> labels = new ArrayList<>();
            View disconnect = null;
            for (Object item : items) {
                FrameLayout tile = (FrameLayout) getField(item, "root");
                View tapTarget = tile.getChildAt(0);
                String label = tapTarget.getContentDescription().toString();
                labels.add(label);
                if (activity.getString(R.string.game_menu_disconnect).equals(label)) {
                    assertNull(disconnect);
                    disconnect = tapTarget;
                }
            }
            assertFalse(labels.contains("Library"));
            assertFalse(labels.contains(activity.getString(R.string.xr_home_end_session)));
            assertEquals(java.util.Arrays.asList(
                    activity.getString(R.string.xr_bar_2d),
                    activity.getString(R.string.xr_bar_host_ai_3d),
                    activity.getString(R.string.xr_bar_client_ai_3d),
                    activity.getString(R.string.xr_bar_game_3d),
                    activity.getString(R.string.xr_bar_movie_3d)), labels.subList(0, 5));
            assertSame(PresentationMode.HOST_SBS_AI, getField(items.get(1), "selectsMode"));
            assertSame(PresentationMode.CLIENT_SBS_AI, getField(items.get(2), "selectsMode"));
            assertSame(PresentationMode.GAME_3D, getField(items.get(3), "selectsMode"));
            assertSame(PresentationMode.MOVIE_3D, getField(items.get(4), "selectsMode"));
            assertFalse(labels.contains(activity.getString(R.string.xr_bar_host_sbs_ai)));
            assertFalse(labels.contains(activity.getString(R.string.xr_bar_client_sbs_ai)));
            assertFalse(labels.contains("Raw SBS"));
            assertEquals(activity.getString(R.string.game_menu_disconnect), labels.get(8));
            if (BuildConfig.DEBUG) {
                assertEquals(activity.getString(R.string.xr_bar_dump), labels.get(9));
            }
            assertTrue(disconnect != null && disconnect.performClick());
            assertEquals(1, disconnects[0]);
            assertFalse(activity.isFinishing());
        } finally {
            presenter.onDestroy();
            controller.destroy();
        }
    }

    @Test
    public void movieModePaneStartsIn2dAndOffersOnlyExplicitIncomingPictureFormats()
            throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        int streamWidth = prefs.width;
        int streamHeight = prefs.height;
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, prefs, surface -> { }, visible -> { });
        SurfaceEntity surface = mock(SurfaceEntity.class);
        setField(presenter, "surfaceEntity", surface);
        setField(presenter, "currentPresenterMode", PresentationMode.MOVIE_3D);
        setField(presenter, "streamPresentationReady", true);
        AuthoredStereoModeState authored = (AuthoredStereoModeState) getField(
                presenter, "authoredStereoModeState");
        FrameLayout host = new FrameLayout(activity);
        setField(modeOptions(presenter), "modeOptionsHost", host);
        XrControlUiState state = (XrControlUiState) getField(presenter, "controlUiState");
        state.toggleModeOptions(PresentationMode.MOVIE_3D.name());

        Method render = XrModeOptionsPane.class.getDeclaredMethod("renderModeOptions");
        render.setAccessible(true);
        render.invoke(modeOptions(presenter));

        XrChoiceGroup group = (XrChoiceGroup) getField(
                modeOptions(presenter), "moviePictureFormatChoiceGroup");
        assertEquals(3, group.getChildCount());
        assertTrue(group.getChildAt(0) instanceof AppCompatButton);
        assertTrue(group.getChildAt(1) instanceof AppCompatButton);
        assertTrue(group.getChildAt(2) instanceof AppCompatButton);
        assertEquals("2D", group.getButtonAt(0).getText().toString());
        assertEquals("Half SBS", group.getButtonAt(1).getText().toString());
        assertEquals("Full SBS", group.getButtonAt(2).getText().toString());
        assertEquals("2d", group.getButtonAt(0).getTag());
        assertEquals("half_sbs", group.getButtonAt(1).getTag());
        assertEquals("full_sbs", group.getButtonAt(2).getTag());
        assertEquals("2d", group.getSelectedValue());
        assertTrue(group.getButtonAt(0).isActivated());
        assertTrue(visibleTexts(host).contains(
                activity.getString(R.string.xr_movie_picture_format)));
        assertTrue(visibleTexts(host).contains(
                activity.getString(R.string.xr_mode_movie_detail)));
        assertFalse(visibleTexts(host).contains("Auto"));

        int width = dp(activity, 720);
        group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        group.layout(0, 0, group.getMeasuredWidth(), group.getMeasuredHeight());
        assertEquals(group.getButtonAt(0).getTop(), group.getButtonAt(1).getTop());
        assertEquals(group.getButtonAt(0).getRight(), group.getButtonAt(1).getLeft());
        assertEquals(group.getButtonAt(1).getRight(), group.getButtonAt(2).getLeft());
        assertEquals(group.getMeasuredWidth(), group.getButtonAt(2).getRight());

        group.getButtonAt(1).performClick();
        assertEquals(MoviePictureFormat.HALF_SBS, authored.getMoviePictureFormat());
        assertEquals("half_sbs", group.getSelectedValue());
        group.getButtonAt(2).performClick();
        assertEquals(MoviePictureFormat.FULL_SBS, authored.getMoviePictureFormat());
        assertEquals("full_sbs", group.getSelectedValue());
        group.getButtonAt(0).performClick();
        assertEquals(MoviePictureFormat.TWO_D, authored.getMoviePictureFormat());
        assertEquals("2d", group.getSelectedValue());
        verify(surface, times(2)).setStereoMode(SurfaceEntity.StereoMode.SIDE_BY_SIDE);
        verify(surface).setStereoMode(SurfaceEntity.StereoMode.MONO);
        verify(surface, never()).setSurfacePixelDimensions(any());
        assertEquals(streamWidth, prefs.width);
        assertEquals(streamHeight, prefs.height);

        state.toggleModeOptions(PresentationMode.NORMAL.name());
        render.invoke(modeOptions(presenter));
        assertNull(getField(modeOptions(presenter), "moviePictureFormatChoiceGroup"));
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void gameModePaneDescribesCompatibleSourcesWithoutInventingDetection()
            throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        FrameLayout host = new FrameLayout(activity);
        setField(modeOptions(presenter), "modeOptionsHost", host);
        XrControlUiState state = (XrControlUiState) getField(presenter, "controlUiState");
        state.toggleModeOptions(PresentationMode.GAME_3D.name());
        Method render = XrModeOptionsPane.class.getDeclaredMethod("renderModeOptions");
        render.setAccessible(true);
        render.invoke(modeOptions(presenter));

        List<String> text = visibleTexts(host);
        assertTrue(text.contains(activity.getString(R.string.xr_mode_game_detail)));
        assertTrue(text.contains(activity.getString(R.string.xr_game_showing_2d)));
        assertTrue(text.contains(activity.getString(R.string.xr_game_source_unavailable)));
        assertFalse(text.contains("Auto"));
        assertNull(getField(modeOptions(presenter), "moviePictureFormatChoiceGroup"));
        assertSame(PresentationMode.NORMAL, getField(presenter, "currentPresenterMode"));
        controller.destroy();
    }

    @Test
    public void modeQualitySubpaneOmitsPhoneAndTabletResolutionPresets()
            throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        FrameLayout host = new FrameLayout(activity);
        setField(modeOptions(presenter), "modeOptionsHost", host);
        XrControlUiState state = (XrControlUiState) getField(presenter, "controlUiState");
        state.toggleModeOptions(PresentationMode.NORMAL.name());

        Method render = XrModeOptionsPane.class.getDeclaredMethod("renderModeOptions");
        render.setAccessible(true);
        render.invoke(modeOptions(presenter));

        XrResolutionSelector selector = (XrResolutionSelector) getField(
                modeOptions(presenter), "modeResolutionSelector");
        assertEquals(XrResolutionOptions.modeSubpaneOptions().size(),
                selector.getCardCount());
        assertNull(selector.findCardByResolutionId(
                XrResolutionOptions.RESOLUTION_PHONE_18_9));
        assertNull(selector.findCardByResolutionId(
                XrResolutionOptions.RESOLUTION_TABLET_1200P));
        assertTrue(selector.findCardByResolutionId(
                XrResolutionOptions.RESOLUTION_1080P) != null);
        controller.destroy();
    }

    @Test
    public void clientModePaneShowsFixedZipDepthWithoutSelectorAndCanScroll()
            throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, prefs, surface -> { }, visible -> { });
        FrameLayout host = new FrameLayout(activity);
        setField(modeOptions(presenter), "modeOptionsHost", host);
        XrControlUiState state = (XrControlUiState) getField(presenter, "controlUiState");
        state.toggleModeOptions(PresentationMode.CLIENT_SBS_AI.name());

        Method render = XrModeOptionsPane.class.getDeclaredMethod("renderModeOptions");
        render.setAccessible(true);
        render.invoke(modeOptions(presenter));

        TextView modelName = (TextView) getField(modeOptions(presenter), "clientModelNameView");
        assertEquals("ZipDepth Base FP16 · short 384", modelName.getText().toString());
        assertThrows(NoSuchFieldException.class,
                () -> XrStreamPresenter.class.getDeclaredField("clientModelChoiceGroup"));

        assertThrows(NoSuchFieldException.class,
                () -> XrStreamPresenter.class.getDeclaredField("modeApplyButton"));
        Button modeDefaults = (Button) getField(modeOptions(presenter), "modeDefaultsButton");
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT,
                modeDefaults.getLayoutParams().width);

        assertTrue(host.getChildAt(0) instanceof ScrollView);
        ScrollView scroll = (ScrollView) host.getChildAt(0);
        assertTrue(scroll.isFillViewport());
        // Keep the whole pane scrollable if its fixed model/status and quality rows exceed the
        // constrained Galaxy XR panel height.
        int width = dp(activity, 600);
        int height = dp(activity, 260);
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, width, height);

        assertTrue(scroll.getChildAt(0).getMeasuredHeight() > scroll.getMeasuredHeight());
        controller.destroy();
    }

    @Test
    public void automaticSettingsHaveOnlyDefaultsInTheFooter() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, prefs, surface -> { }, visible -> { });
        Object pane = getField(presenter, "sessionSettingsPane");
        Method build = XrSessionSettingsPane.class.getDeclaredMethod("buildSessionSettingsView");
        build.setAccessible(true);
        build.invoke(pane);

        assertThrows(NoSuchFieldException.class,
                () -> XrStreamPresenter.class.getDeclaredField("sessionApplyButton"));
        assertThrows(NoSuchFieldException.class,
                () -> XrSessionSettingsPane.class.getDeclaredField("sessionApplyButton"));
        Button defaults = (Button) getField(pane, "sessionDefaultsButton");
        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) defaults.getLayoutParams();
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, params.width);

        LinearLayout footer = (LinearLayout) defaults.getParent();
        assertEquals(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END,
                footer.getGravity());
        assertEquals(footer, defaults.getParent());
        assertEquals(0, footer.indexOfChild(defaults));
        assertEquals(1, footer.getChildCount());
        controller.destroy();
    }

    @Test
    public void rebuildingAndRefreshingSettingsCannotUnlockAPendingTransaction() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();
        XrStreamPresenter presenter = new XrStreamPresenter(activity,
                PreferenceConfiguration.readPreferences(activity), surface -> { }, visible -> { });
        presenter.setSettingsTransactionPending(true);
        Object pane = getField(presenter, "sessionSettingsPane");
        Method build = XrSessionSettingsPane.class.getDeclaredMethod("buildSessionSettingsView");
        build.setAccessible(true);
        build.invoke(pane);
        java.util.Map<?, ?> groups = (java.util.Map<?, ?>) getField(pane, "sessionChoiceGroups");
        XrChoiceGroup hdr = (XrChoiceGroup) groups.get(com.limelight.ui.xrcontrols.SessionSettingsModel.Key.HDR);
        assertFalse(hdr.isEnabled());
        Method update = XrSessionSettingsPane.class.getDeclaredMethod("updateSessionSettingsView");
        update.setAccessible(true);
        update.invoke(pane);
        assertFalse(hdr.isEnabled());
        build.invoke(pane);
        hdr = (XrChoiceGroup) groups.get(com.limelight.ui.xrcontrols.SessionSettingsModel.Key.HDR);
        assertFalse(hdr.isEnabled());
        presenter.setSettingsTransactionPending(false);
        assertTrue(hdr.isEnabled());
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void activeModeTileCancelsTheQueuedDifferentPresentation() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();
        XrStreamPresenter presenter = new XrStreamPresenter(activity,
                PreferenceConfiguration.readPreferences(activity), surface -> { }, visible -> { });
        int[] cancelled = {0};
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public void onPresentationModeCommitted(PresentationMode mode) {
                assertSame(PresentationMode.NORMAL, mode);
                cancelled[0]++;
            }
        });
        setField(presenter, "streamPresentationReady", true);
        presenter.setSettingsRequestedPresentationMode(PresentationMode.HOST_SBS_AI);
        try (MockedStatic<PanelEntity> panels = mockStatic(PanelEntity.class,
                invocation -> mock(PanelEntity.class))) {
            // buildControlBar creates the dock gaze tracker, which requires a Session.
            setField(presenter, "session", mock(androidx.xr.runtime.Session.class));
            Method build = XrStreamPresenter.class.getDeclaredMethod("buildControlBar", float.class);
            build.setAccessible(true);
            build.invoke(presenter, 2.0f);
            java.util.List<?> items = (java.util.List<?>) getField(presenter, "barItems");
            Object normal = items.get(0);
            Method tap = XrStreamPresenter.class.getDeclaredMethod("onModeTileTapped", normal.getClass());
            tap.setAccessible(true);
            tap.invoke(presenter, normal);
            assertEquals(1, cancelled[0]);
            assertNull(getField(presenter, "settingsRequestedPresentationMode"));
        }
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void xrPanelsUseReadableTypeAndCinemaNaming() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        assertEquals(R.dimen.xr_text_display, XrStatsPanel.STATS_TEXT_DIMEN);
        assertEquals(R.dimen.xr_text_title,
                XrSessionSettingsPane.SESSION_SUMMARY_TEXT_DIMEN);
        assertEquals(R.dimen.xr_text_title,
                XrSessionSettingsPane.SESSION_GROUP_TEXT_DIMEN);
        assertEquals(R.dimen.xr_text_display,
                XrSessionSettingsPane.SESSION_ROW_TITLE_TEXT_DIMEN);
        assertEquals(R.dimen.xr_text_title,
                XrSessionSettingsPane.SESSION_META_TEXT_DIMEN);
        assertEquals("2D", activity.getString(R.string.xr_bar_2d));
        assertEquals("Host AI 3D", activity.getString(R.string.xr_bar_host_ai_3d));
        assertEquals("Client AI 3D", activity.getString(R.string.xr_bar_client_ai_3d));
        assertEquals("Game 3D", activity.getString(R.string.xr_bar_game_3d));
        assertEquals("Movie 3D", activity.getString(R.string.xr_bar_movie_3d));
        assertEquals("Cinema", activity.getString(R.string.xr_bar_cinema_view));
        controller.destroy();
    }

    @Test
    public void xrCeilingLabelsKeepTheirMeaningInUntranslatedLocales() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        Configuration french = new Configuration(activity.getResources().getConfiguration());
        french.setLocale(Locale.FRANCE);
        Context localized = activity.createConfigurationContext(french);
        assertEquals("Max frame rate",
                localized.getString(R.string.title_fps_ceiling));
        assertTrue(localized.getString(R.string.summary_fps_ceiling)
                .startsWith("Upper limit for the stream."));
        assertEquals("Max bitrate",
                localized.getString(R.string.title_bitrate_ceiling));
        assertTrue(localized.getString(R.string.summary_bitrate_ceiling)
                .startsWith("Upper limit for the stream."));
        controller.destroy();
    }

    @Test
    public void glanceStreamFormatsLiveBitrateWithoutResourceTypeMismatch() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        assertEquals("3840 \u00d7 2160 \u00b7 90 FPS \u00b7 100 Mbps \u00b7 HDR",
                XrStatsFormat.formatGlanceStream(
                        activity, "3840x2160", "90", 100000, "HDR"));
        controller.destroy();
    }

    @Test
    public void statsEntityScalePreservesAuthoredWorldSize() {
        float localWidth = XrPanelPlacement.statsEntityLocalMeters(1.40f, 1.26f);
        float localHeight = XrPanelPlacement.statsEntityLocalMeters(1.85f, 1.26f);

        assertEquals(1.40f, localWidth * 1.26f, 0.0001f);
        assertEquals(1.85f, localHeight * 1.26f, 0.0001f);
        assertEquals(0.0f,
                XrPanelPlacement.statsEntityLocalMeters(1.40f, 0.0f), 0.0f);
    }

    @Test
    public void clientStatsReportAdaptiveGeometryWithoutCutOrLegacyConcepts() {
        String state = XrStatsFormat.formatClientV2State(
                true, true, 1.0f);
        String coordinate = XrStatsFormat.formatClientAdaptiveZero(
                0.123456f, 0.234567f);
        String amplitude = XrStatsFormat.formatClientDepthAmplitude(20.0f, 10.0f);
        String distribution = XrStatsFormat.formatClientRawDistribution(
                0.1f, 0.3f, 0.04864449f);
        String mapping = XrStatsFormat.formatClientLinearMapping(1.75f, true, 30L);
        String faults = XrStatsFormat.formatClientFaults(0L, 0L, true, 0L, 0L);

        assertEquals("ready yes | current valid yes | valid 100.0%", state);
        assertEquals("ready no | current valid no | valid n/a",
                XrStatsFormat.formatClientV2State(false, false, Float.NaN));
        assertEquals("zero 0.123456 | mean target 0.234567",
                coordinate);
        assertEquals("D 0.050000 | inverse 20.0000 | target inverse 10.0000", amplitude);
        assertEquals("P05 0.100000 | P95 0.300000 | floor 0.048644", distribution);
        assertEquals("pop 1.750 | source clock armed | sample 30", mapping);
        assertEquals("content 1 | invalid 4",
                XrStatsFormat.formatClientReuseRejects(1L, 4L));
        assertEquals("color_busy 0 | flat 0 | raw invalid 0 | raw collapsed 0", faults);
        assertEquals("color_busy 2 | flat 3 | raw n/a",
                XrStatsFormat.formatClientFaults(2L, 3L, false, 5L, 7L));

        String visible = (state + coordinate + amplitude + distribution + mapping + faults)
                .toLowerCase(Locale.US);
        for (String obsolete : new String[] {
                "stretch", "recenter", "subject", "bestv2", "adaptive pop", "anchor", "shot",
                "cut", "history", "appearance", "structure", "scene age"
        }) {
            assertFalse("obsolete Client SBS stats concept: " + obsolete,
                    visible.contains(obsolete));
        }
    }

    @Test
    public void clientStatsDoNotTurnMissingCameraObservationsIntoZeros() {
        assertEquals("zero 0.123456 | mean target n/a",
                XrStatsFormat.formatClientAdaptiveZero(0.123456f, Float.NaN));
        assertEquals("D 0.050000 | inverse 20.0000 | target inverse n/a",
                XrStatsFormat.formatClientDepthAmplitude(20.0f, Float.NaN));
        assertEquals("D n/a | inverse n/a | target inverse n/a",
                XrStatsFormat.formatClientDepthAmplitude(0.0f, -1.0f));
        assertEquals("P05 n/a | P95 n/a | floor 0.048644",
                XrStatsFormat.formatClientRawDistribution(
                        Float.NaN, Float.NaN, 0.04864449f));
        assertEquals("pop 1.750 | source clock disarmed | sample 35",
                XrStatsFormat.formatClientLinearMapping(1.75f, false, 35L));
    }

    @Test
    public void depthHealthReadbackFailureIsVisibleInsteadOfLookingLikeWarmup() {
        assertEquals("Waiting for sample",
                XrStatsFormat.formatDepthHealthUnavailable(false));
        assertEquals("Telemetry unavailable | retrying",
                XrStatsFormat.formatDepthHealthUnavailable(true));
    }

    @Test
    public void clientGpuStageRowsIncludeRawV2DepthTimer() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        TableLayout table = new TableLayout(activity);
        setField(stats(presenter), "statsTable", table);

        Method begin = XrStatsPanel.class.getDeclaredMethod("beginStatsRows");
        Method finish = XrStatsPanel.class.getDeclaredMethod("finishStatsRows");
        Method addGpuStages = XrStatsPanel.class.getDeclaredMethod(
                "addClientGpuStageRows", boolean.class, float[].class, long[].class);
        begin.setAccessible(true);
        finish.setAccessible(true);
        addGpuStages.setAccessible(true);

        begin.invoke(stats(presenter));
        addGpuStages.invoke(stats(presenter), true,
                new float[] {0.11f, 0.22f, 0.33f, 0.44f},
                new long[] {11L, 22L, 33L, 44L});
        finish.invoke(stats(presenter));

        assertEquals(4, table.getChildCount());
        TableRow depthCut = (TableRow) table.getChildAt(2);
        assertEquals("Depth GPU",
                ((TextView) depthCut.getChildAt(0)).getText().toString());
        assertEquals("0.33 ms | raw validity + adaptive camera",
                ((TextView) depthCut.getChildAt(1)).getText().toString());
        controller.destroy();
    }

    @Test
    public void trendRowsRenderHistoriesAndReuseTheirSparklines() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        TableLayout table = new TableLayout(activity);
        setField(stats(presenter), "statsTable", table);

        Method begin = XrStatsPanel.class.getDeclaredMethod("beginStatsRows");
        Method finish = XrStatsPanel.class.getDeclaredMethod("finishStatsRows");
        Method addTrend = XrStatsPanel.class.getDeclaredMethod(
                "addTrendStatsRow", String.class, String.class, int.class,
                float[].class, boolean.class, float.class, float.class);
        begin.setAccessible(true);
        finish.setAccessible(true);
        addTrend.setAccessible(true);

        String[] labels = {
                "Edge evidence",
                "Depth change",
                "Event count",
                "Frame age",
                "Network jitter"
        };
        begin.invoke(stats(presenter));
        for (int i = 0; i < labels.length; i++) {
            addTrend.invoke(stats(presenter), labels[i], "sample " + i, 0xFFFFFFFF,
                    new float[] {i, i + 0.5f, i + 1.0f},
                    "Event count".equals(labels[i]), Float.NaN, Float.NaN);
        }
        finish.invoke(stats(presenter));

        assertEquals(labels.length, table.getChildCount());
        TableRow[] firstRows = new TableRow[labels.length];
        View[] firstSparklines = new View[labels.length];
        Field slotCapacity = XrSparklineView.class.getDeclaredField("slotCapacity");
        slotCapacity.setAccessible(true);
        for (int i = 0; i < labels.length; i++) {
            firstRows[i] = (TableRow) table.getChildAt(i);
            assertEquals(2, firstRows[i].getChildCount());
            assertEquals(labels[i],
                    ((TextView) firstRows[i].getChildAt(0)).getText().toString());
            LinearLayout trendContent = (LinearLayout) firstRows[i].getChildAt(1);
            assertEquals(2, trendContent.getChildCount());
            firstSparklines[i] = trendContent.getChildAt(1);
            assertEquals("Event count".equals(labels[i])
                            ? ClientSbsMetricHistory.CAPACITY - 1
                            : ClientSbsMetricHistory.CAPACITY,
                    slotCapacity.getInt(firstSparklines[i]));
            int plottedSamples = "Event count".equals(labels[i]) ? 2 : 3;
            String summary = "Event count".equals(labels[i])
                    ? "steady, recent range 0.5 to 0.5, latest 0.5"
                    : "rising, recent range " + (float) i + " to " + (i + 1.0f)
                            + ", latest " + (i + 1.0f);
            assertEquals(labels[i] + " trend, " + plottedSamples
                            + " samples, " + summary,
                    firstSparklines[i].getContentDescription().toString());
        }

        begin.invoke(stats(presenter));
        for (int i = 0; i < labels.length; i++) {
            addTrend.invoke(stats(presenter), labels[i], "updated " + i, 0xFFFFFFFF,
                    new float[] {i + 0.1f, i + 0.6f, i + 1.1f},
                    "Event count".equals(labels[i]), Float.NaN, Float.NaN);
        }
        finish.invoke(stats(presenter));

        assertEquals(labels.length, table.getChildCount());
        for (int i = 0; i < labels.length; i++) {
            assertSame(firstRows[i], table.getChildAt(i));
            LinearLayout trendContent =
                    (LinearLayout) ((TableRow) table.getChildAt(i)).getChildAt(1);
            assertSame(firstSparklines[i],
                    trendContent.getChildAt(1));
        }
        controller.destroy();
    }

    @Test
    public void trendRowsReplacePlainFallbacksAsHistoryBecomesAvailable() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        TableLayout table = new TableLayout(activity);
        setField(stats(presenter), "statsTable", table);

        Method begin = XrStatsPanel.class.getDeclaredMethod("beginStatsRows");
        Method finish = XrStatsPanel.class.getDeclaredMethod("finishStatsRows");
        Method addTrend = XrStatsPanel.class.getDeclaredMethod(
                "addTrendStatsRow", String.class, String.class, int.class,
                float[].class, boolean.class, float.class, float.class);
        begin.setAccessible(true);
        finish.setAccessible(true);
        addTrend.setAccessible(true);

        begin.invoke(stats(presenter));
        addTrend.invoke(stats(presenter), "Depth change", "warming", 0xFFFFFFFF,
                new float[] {1.0f}, false, 0.0f, 2.0f);
        finish.invoke(stats(presenter));
        TableRow fallback = (TableRow) table.getChildAt(0);
        assertTrue(fallback.getChildAt(1) instanceof TextView);

        begin.invoke(stats(presenter));
        addTrend.invoke(stats(presenter), "Depth change", "live", 0xFFFFFFFF,
                new float[] {1.0f, 1.5f}, false, 0.0f, 2.0f);
        finish.invoke(stats(presenter));
        TableRow trend = (TableRow) table.getChildAt(0);
        assertNotSame(fallback, trend);
        assertTrue(trend.getChildAt(1) instanceof LinearLayout);

        begin.invoke(stats(presenter));
        addTrend.invoke(stats(presenter), "Depth change", "reset", 0xFFFFFFFF,
                new float[] {1.0f}, false, 0.0f, 2.0f);
        finish.invoke(stats(presenter));
        TableRow resetFallback = (TableRow) table.getChildAt(0);
        assertNotSame(trend, resetFallback);
        assertTrue(resetFallback.getChildAt(1) instanceof TextView);
        controller.destroy();
    }

    private static List<String> visibleTexts(View root) {
        List<String> result = new ArrayList<>();
        if (root.getVisibility() != View.VISIBLE) {
            return result;
        }
        if (root instanceof TextView) {
            result.add(((TextView) root).getText().toString());
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                result.addAll(visibleTexts(group.getChildAt(i)));
            }
        }
        return result;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static Object modeOptions(XrStreamPresenter presenter) {
        return org.robolectric.util.ReflectionHelpers.getField(presenter, "modeOptionsPane");
    }

    private static Object stats(XrStreamPresenter presenter) {
        return org.robolectric.util.ReflectionHelpers.getField(presenter, "statsUi");
    }
}
