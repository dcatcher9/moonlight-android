package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.XrControlUiState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrStreamPresenterViewTest {
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
        assertEquals(android.view.Gravity.END, params.gravity);
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

        assertTrue(XrStreamPresenter.STATS_TEXT_SP >= 30f);
        assertTrue(XrStreamPresenter.SESSION_SUMMARY_TEXT_SP >= 25f);
        assertTrue(XrStreamPresenter.SESSION_GROUP_TEXT_SP >= 26f);
        assertTrue(XrStreamPresenter.SESSION_ROW_TITLE_TEXT_SP >= 29f);
        assertTrue(XrStreamPresenter.SESSION_META_TEXT_SP >= 22f);
        assertEquals("Raw SBS", activity.getString(R.string.xr_bar_host_sbs_raw));
        assertEquals("Cinema", activity.getString(R.string.xr_bar_cinema_view));
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
