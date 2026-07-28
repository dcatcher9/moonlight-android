package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.XrChoiceGroup;
import com.limelight.sbs.ClientSbsMetricHistory;
import com.limelight.ui.xrcontrols.RawSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.XrControlUiState;
import com.limelight.ui.xrcontrols.XrSparklineView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrStreamPresenterViewTest {
    @Test
    public void rawModePaneUsesConnectedFullHalfButtonsAndEmitsHalfChoice()
            throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        prefs.rawSbsPerEyeResolution =
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL;
        String[] selectedResolution = new String[1];
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, prefs, surface -> { }, visible -> { });
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override
            public boolean onRawSbsPerEyeResolutionSelected(
                    String resolutionId, RawSbsModeSettingsModel current) {
                selectedResolution[0] = resolutionId;
                return true;
            }
        });
        FrameLayout host = new FrameLayout(activity);
        setField(presenter, "modeOptionsHost", host);
        XrControlUiState state = (XrControlUiState) getField(presenter, "controlUiState");
        state.toggleModeOptions(XrStreamPresenter.PresenterMode.HOST_SBS_RAW.name());

        Method render = XrStreamPresenter.class.getDeclaredMethod("renderModeOptions");
        render.setAccessible(true);
        render.invoke(presenter);

        XrChoiceGroup group = (XrChoiceGroup) getField(
                presenter, "rawSbsPerEyeResolutionChoiceGroup");
        assertEquals(2, group.getChildCount());
        assertTrue(group.getChildAt(0) instanceof AppCompatButton);
        assertTrue(group.getChildAt(1) instanceof AppCompatButton);
        assertEquals("Full", group.getButtonAt(0).getText().toString());
        assertEquals("Half", group.getButtonAt(1).getText().toString());
        assertEquals(RawSbsModeSettingsModel.FULL_ID, group.getButtonAt(0).getTag());
        assertEquals(RawSbsModeSettingsModel.HALF_ID, group.getButtonAt(1).getTag());
        assertEquals(RawSbsModeSettingsModel.FULL_ID, group.getSelectedValue());
        assertTrue(group.getButtonAt(0).isActivated());

        int width = dp(activity, 720);
        group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        group.layout(0, 0, group.getMeasuredWidth(), group.getMeasuredHeight());
        assertEquals(group.getButtonAt(0).getTop(), group.getButtonAt(1).getTop());
        assertEquals(group.getButtonAt(0).getRight(), group.getButtonAt(1).getLeft());
        assertEquals(group.getMeasuredWidth(), group.getButtonAt(1).getRight());

        group.getButtonAt(1).performClick();
        assertEquals(RawSbsModeSettingsModel.HALF_ID, selectedResolution[0]);
        assertEquals(RawSbsModeSettingsModel.HALF_ID, group.getSelectedValue());

        state.toggleModeOptions(XrStreamPresenter.PresenterMode.NORMAL.name());
        render.invoke(presenter);
        assertNull(getField(presenter, "rawSbsPerEyeResolutionChoiceGroup"));
        controller.destroy();
    }

    @Test
    public void clientModePaneUsesWholePaneVerticalOverflowAtConstrainedHeight()
            throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, prefs, surface -> { }, visible -> { });
        FrameLayout host = new FrameLayout(activity);
        setField(presenter, "modeOptionsHost", host);
        XrControlUiState state = (XrControlUiState) getField(presenter, "controlUiState");
        state.toggleModeOptions(XrStreamPresenter.PresenterMode.CLIENT_SBS_AI.name());

        Method render = XrStreamPresenter.class.getDeclaredMethod("renderModeOptions");
        render.setAccessible(true);
        render.invoke(presenter);

        Button modeApply = (Button) getField(presenter, "modeApplyButton");
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT,
                modeApply.getLayoutParams().width);

        assertTrue(host.getChildAt(0) instanceof ScrollView);
        ScrollView scroll = (ScrollView) host.getChildAt(0);
        assertTrue(scroll.isFillViewport());
        int width = dp(activity, 1200);
        int height = dp(activity, 260);
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, width, height);

        assertTrue(scroll.getChildAt(0).getMeasuredHeight() > scroll.getMeasuredHeight());
        controller.destroy();
    }

    @Test
    public void sharedApplyActionWrapsContentAndAlignsToPaneEnd() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, prefs, surface -> { }, visible -> { });
        Method build = XrStreamPresenter.class.getDeclaredMethod("buildSessionSettingsView");
        build.setAccessible(true);
        build.invoke(presenter);

        Button apply = (Button) getField(presenter, "sessionApplyButton");
        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) apply.getLayoutParams();
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, params.width);

        // Alignment moved from the button to the footer that now holds both actions, so assert
        // the arrangement rather than a gravity flag that no longer lives on the button: reset
        // first, apply last, and the pair pushed to the pane end.
        LinearLayout footer = (LinearLayout) apply.getParent();
        assertEquals(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END,
                footer.getGravity());
        Button defaults = (Button) getField(presenter, "sessionDefaultsButton");
        assertEquals(footer, defaults.getParent());
        assertEquals(0, footer.indexOfChild(defaults));
        assertEquals(1, footer.indexOfChild(apply));
        controller.destroy();
    }

    @Test
    public void compactDockExpandsInlineWithPlusAndMinusAffordance() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        assertEquals(View.GONE, XrStreamPresenter.secondaryActionVisibility(false));
        assertEquals(View.VISIBLE, XrStreamPresenter.secondaryActionVisibility(true));
        assertEquals(R.drawable.ic_add_base,
                XrStreamPresenter.expansionIconResource(false));
        assertEquals(R.drawable.ic_remove_base,
                XrStreamPresenter.expansionIconResource(true));
        assertEquals("Show session tools",
                activity.getString(R.string.xr_dock_expand_session_tools));
        assertEquals("Hide session tools",
                activity.getString(R.string.xr_dock_collapse_session_tools));
        controller.destroy();
    }

    @Test
    public void xrPanelsUseReadableTypeAndCinemaNaming() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        assertEquals(R.dimen.xr_text_display, XrStreamPresenter.STATS_TEXT_DIMEN);
        assertEquals(R.dimen.xr_text_title,
                XrStreamPresenter.SESSION_SUMMARY_TEXT_DIMEN);
        assertEquals(R.dimen.xr_text_title,
                XrStreamPresenter.SESSION_GROUP_TEXT_DIMEN);
        assertEquals(R.dimen.xr_text_display,
                XrStreamPresenter.SESSION_ROW_TITLE_TEXT_DIMEN);
        assertEquals(R.dimen.xr_text_title,
                XrStreamPresenter.SESSION_META_TEXT_DIMEN);
        assertEquals("Raw SBS", activity.getString(R.string.xr_bar_host_sbs_raw));
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
                XrStreamPresenter.formatGlanceStream(
                        activity, "3840x2160", "90", 100000, "HDR"));
        controller.destroy();
    }

    @Test
    public void statsEntityScalePreservesAuthoredWorldSize() {
        float localWidth = XrStreamPresenter.statsEntityLocalMeters(1.40f, 1.26f);
        float localHeight = XrStreamPresenter.statsEntityLocalMeters(1.85f, 1.26f);

        assertEquals(1.40f, localWidth * 1.26f, 0.0001f);
        assertEquals(1.85f, localHeight * 1.26f, 0.0001f);
        assertEquals(0.0f,
                XrStreamPresenter.statsEntityLocalMeters(1.40f, 0.0f), 0.0f);
    }

    @Test
    public void sceneCutStatusExposesArmingAndExternalAttribution() {
        assertEquals("7 total | scene age 19 frames | geometry armed | external requests 2",
                XrStreamPresenter.formatSceneCutStatus(7L, 19, true, 2L));
        assertEquals("7 total | scene age 19 frames | geometry disarmed | external requests 0",
                XrStreamPresenter.formatSceneCutStatus(7L, 19, false, 0L));
    }

    @Test
    public void depthHealthReadbackFailureIsVisibleInsteadOfLookingLikeWarmup() {
        assertEquals("Waiting for sample",
                XrStreamPresenter.formatDepthHealthUnavailable(false));
        assertEquals("Telemetry unavailable | retrying",
                XrStreamPresenter.formatDepthHealthUnavailable(true));
    }

    @Test
    public void clientGpuStageRowsIncludeDepthProfileTimer() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        TableLayout table = new TableLayout(activity);
        setField(presenter, "statsTable", table);

        Method begin = XrStreamPresenter.class.getDeclaredMethod("beginStatsRows");
        Method finish = XrStreamPresenter.class.getDeclaredMethod("finishStatsRows");
        Method addGpuStages = XrStreamPresenter.class.getDeclaredMethod(
                "addClientGpuStageRows", boolean.class, float[].class, long[].class);
        begin.setAccessible(true);
        finish.setAccessible(true);
        addGpuStages.setAccessible(true);

        begin.invoke(presenter);
        addGpuStages.invoke(presenter, true,
                new float[] {0.11f, 0.22f, 0.33f, 0.44f},
                new long[] {11L, 22L, 33L, 44L});
        finish.invoke(presenter);

        assertEquals(4, table.getChildCount());
        TableRow depthProfile = (TableRow) table.getChildAt(2);
        assertEquals("Depth profile GL GPU",
                ((TextView) depthProfile.getChildAt(0)).getText().toString());
        assertEquals("0.33 ms | filter + adaptive profile",
                ((TextView) depthProfile.getChildAt(1)).getText().toString());
        controller.destroy();
    }

    @Test
    public void healthTrendRowsRenderEveryHistoryAndReuseTheirSparklines() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        TableLayout table = new TableLayout(activity);
        setField(presenter, "statsTable", table);

        Method begin = XrStreamPresenter.class.getDeclaredMethod("beginStatsRows");
        Method finish = XrStreamPresenter.class.getDeclaredMethod("finishStatsRows");
        Method addTrend = XrStreamPresenter.class.getDeclaredMethod(
                "addTrendStatsRow", String.class, String.class, int.class,
                float[].class, boolean.class, float.class, float.class);
        begin.setAccessible(true);
        finish.setAccessible(true);
        addTrend.setAccessible(true);

        String[] labels = {
                "Pop strength",
                "Edge fraction",
                "Changed-depth fraction",
                "Scene cuts",
                "Zero-plane anchor shift"
        };
        begin.invoke(presenter);
        for (int i = 0; i < labels.length; i++) {
            addTrend.invoke(presenter, labels[i], "sample " + i, 0xFFFFFFFF,
                    new float[] {i, i + 0.5f, i + 1.0f},
                    "Scene cuts".equals(labels[i]), Float.NaN, Float.NaN);
        }
        finish.invoke(presenter);

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
            assertEquals("Scene cuts".equals(labels[i])
                            ? ClientSbsMetricHistory.CAPACITY - 1
                            : ClientSbsMetricHistory.CAPACITY,
                    slotCapacity.getInt(firstSparklines[i]));
            int plottedSamples = "Scene cuts".equals(labels[i]) ? 2 : 3;
            String summary = "Scene cuts".equals(labels[i])
                    ? "steady, recent range 0.5 to 0.5, latest 0.5"
                    : "rising, recent range " + (float) i + " to " + (i + 1.0f)
                            + ", latest " + (i + 1.0f);
            assertEquals(labels[i] + " trend, " + plottedSamples
                            + " samples, " + summary,
                    firstSparklines[i].getContentDescription().toString());
        }

        begin.invoke(presenter);
        for (int i = 0; i < labels.length; i++) {
            addTrend.invoke(presenter, labels[i], "updated " + i, 0xFFFFFFFF,
                    new float[] {i + 0.1f, i + 0.6f, i + 1.1f},
                    "Scene cuts".equals(labels[i]), Float.NaN, Float.NaN);
        }
        finish.invoke(presenter);

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
        setField(presenter, "statsTable", table);

        Method begin = XrStreamPresenter.class.getDeclaredMethod("beginStatsRows");
        Method finish = XrStreamPresenter.class.getDeclaredMethod("finishStatsRows");
        Method addTrend = XrStreamPresenter.class.getDeclaredMethod(
                "addTrendStatsRow", String.class, String.class, int.class,
                float[].class, boolean.class, float.class, float.class);
        begin.setAccessible(true);
        finish.setAccessible(true);
        addTrend.setAccessible(true);

        begin.invoke(presenter);
        addTrend.invoke(presenter, "Pop strength", "warming", 0xFFFFFFFF,
                new float[] {1.0f}, false, 0.0f, 2.0f);
        finish.invoke(presenter);
        TableRow fallback = (TableRow) table.getChildAt(0);
        assertTrue(fallback.getChildAt(1) instanceof TextView);

        begin.invoke(presenter);
        addTrend.invoke(presenter, "Pop strength", "live", 0xFFFFFFFF,
                new float[] {1.0f, 1.5f}, false, 0.0f, 2.0f);
        finish.invoke(presenter);
        TableRow trend = (TableRow) table.getChildAt(0);
        assertNotSame(fallback, trend);
        assertTrue(trend.getChildAt(1) instanceof LinearLayout);

        begin.invoke(presenter);
        addTrend.invoke(presenter, "Pop strength", "reset", 0xFFFFFFFF,
                new float[] {1.0f}, false, 0.0f, 2.0f);
        finish.invoke(presenter);
        TableRow resetFallback = (TableRow) table.getChildAt(0);
        assertNotSame(trend, resetFallback);
        assertTrue(resetFallback.getChildAt(1) instanceof TextView);
        controller.destroy();
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
}
