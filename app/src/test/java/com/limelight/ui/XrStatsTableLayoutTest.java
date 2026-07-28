package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.XrSparklineView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The stats table renders to a fixed-width raster, so its columns compete for a hard budget.
 *
 * <p>Trend rows keep their value and sparkline in one bounded second-column container. This avoids
 * relying on runtime font measurement to keep a third global table column inside SceneCore's
 * clipped panel raster.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "hdpi", shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrStatsTableLayoutTest {

    private static Object field(Object target, String name) throws Exception {
        Field f = XrStreamPresenter.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static int intConstant(String name) throws Exception {
        Field f = XrStreamPresenter.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    @Test
    public void trendSparklineFitsInsideTheStatsRaster() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        XrStreamPresenter presenter = new XrStreamPresenter(activity,
                PreferenceConfiguration.readPreferences(activity), surface -> { }, visible -> { });

        Method createTable = XrStreamPresenter.class.getDeclaredMethod("createStatsTable");
        createTable.setAccessible(true);
        TableLayout table = (TableLayout) createTable.invoke(presenter);

        Field tableField = XrStreamPresenter.class.getDeclaredField("statsTable");
        tableField.setAccessible(true);
        tableField.set(presenter, table);

        // Include the longest ordinary label: TableLayout shares each column's width across all
        // rows, so this is what used to push an independent chart column beyond the raster.
        Method addRow = XrStreamPresenter.class.getDeclaredMethod(
                "addStatsRow", String.class, String.class, int.class);
        addRow.setAccessible(true);
        addRow.invoke(presenter, "Decoder output / release / surface",
                "90.0 / 90.0 / 90.0", 0xFFFFFFFF);
        TableRow widestLabelRow = (TableRow) table.getChildAt(0);

        Method addTrend = XrStreamPresenter.class.getDeclaredMethod("addTrendStatsRow",
                String.class, String.class, int.class, float[].class, boolean.class,
                float.class, float.class);
        addTrend.setAccessible(true);
        float[] samples = new float[32];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = i % 7;
        }
        String trendValue =
                "valid 100.0% | range 2.8467 | pop 2.000 | subject 0.884 | collapsed no";
        addTrend.invoke(presenter, "Pop strength", trendValue,
                0xFFFFFFFF, samples, false, 0f, 1f);

        int raster = intConstant("STATS_RASTER_WIDTH");
        Method statsDp = XrStreamPresenter.class.getDeclaredMethod("statsDp", float.class);
        statsDp.setAccessible(true);
        int panelPadding = (int) statsDp.invoke(presenter, 18.0f);
        int viewportWidth = raster - panelPadding * 2;
        // Robolectric's font metrics are intentionally simple. Give the shared label column a
        // deterministic, realistic pressure so this test does not pass only because text is
        // measured narrower than it is on the headset.
        ((TextView) widestLabelRow.getChildAt(0)).setMinWidth(viewportWidth * 2 / 3);

        table.measure(View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        table.layout(0, 0, viewportWidth, table.getMeasuredHeight());

        TableRow row = (TableRow) table.getChildAt(1);
        assertTrue("trend row must use only the table's label and bounded content columns",
                row.getChildCount() == 2);
        assertTrue("trend value and plot should share one bounded cell",
                row.getChildAt(1) instanceof LinearLayout);
        LinearLayout trendContent = (LinearLayout) row.getChildAt(1);
        assertTrue("bounded trend cell should contain the value and sparkline",
                trendContent.getChildCount() == 2);
        TextView value = (TextView) trendContent.getChildAt(0);
        assertEquals("trend telemetry must remain one row high", 1, value.getMaxLines());
        assertEquals("long trend telemetry must truncate instead of wrapping",
                TextUtils.TruncateAt.END, value.getEllipsize());
        assertEquals("ellipsis policy must not discard the accessible full value",
                trendValue, value.getText().toString());
        AccessibilityNodeInfo accessibility = value.createAccessibilityNodeInfo();
        assertEquals("accessibility must expose the untruncated telemetry value",
                trendValue, accessibility.getText().toString());
        accessibility.recycle();
        assertTrue("trend cell should carry a sparkline after its value",
                trendContent.getChildAt(1) instanceof XrSparklineView);
        View spark = trendContent.getChildAt(1);

        assertTrue("sparkline must have a drawable width, was " + spark.getWidth(),
                spark.getWidth() > 0);
        assertTrue("sparkline must have a drawable height, was " + spark.getHeight(),
                spark.getHeight() > 0);
        assertTrue("bounded trend cell runs past the stats viewport",
                trendContent.getLeft() + trendContent.getWidth() <= viewportWidth);
        assertTrue("sparkline runs past its bounded trend cell",
                spark.getRight() <= trendContent.getWidth());
        controller.destroy();
    }
}
