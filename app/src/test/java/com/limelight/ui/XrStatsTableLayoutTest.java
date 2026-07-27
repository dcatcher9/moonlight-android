package com.limelight.ui;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;

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
 * <p>Trend rows put a sparkline in a third column after the label and the value. The plot is the
 * one column that cannot shrink, so if the other two grow it lands outside the raster and is
 * simply never drawn. Measured at the real raster width and the headset's 240dpi, since both
 * numbers decide whether it fits.</p>
 *
 * <p>Known limit: Robolectric does no real font measurement, so the label column comes back far
 * narrower here than on the device. This catches a sparkline sized or positioned wrongly; it
 * cannot catch overflow driven purely by long label text.</p>
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

        // The longest label the pane actually renders, alongside a plausible value.
        Method addTrend = XrStreamPresenter.class.getDeclaredMethod("addTrendStatsRow",
                String.class, String.class, int.class, float[].class, boolean.class,
                float.class, float.class);
        addTrend.setAccessible(true);
        float[] samples = new float[32];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = i % 7;
        }
        addTrend.invoke(presenter, "Depth edge fraction trend", "0.081", 0xFFFFFFFF,
                samples, false, 0f, 1f);

        int raster = intConstant("STATS_RASTER_WIDTH");
        table.measure(View.MeasureSpec.makeMeasureSpec(raster, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        table.layout(0, 0, raster, table.getMeasuredHeight());

        TableRow row = (TableRow) table.getChildAt(0);
        assertTrue("trend row should carry a sparkline in column 2",
                row.getChildAt(2) instanceof XrSparklineView);
        View spark = row.getChildAt(2);

        assertTrue("sparkline must have a drawable width, was " + spark.getWidth(),
                spark.getWidth() > 0);
        assertTrue("sparkline must have a drawable height, was " + spark.getHeight(),
                spark.getHeight() > 0);
        int right = spark.getLeft() + spark.getWidth();
        assertTrue("sparkline runs past the stats raster: right edge " + right
                        + " against a " + raster + "px raster, so it is never drawn",
                right <= raster);
        controller.destroy();
    }
}
