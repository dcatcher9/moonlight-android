package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35})
public final class XrBitrateControlTest {
    @Test
    public void controlsUseLargeDirectManipulationTargets() {
        Context context = themedContext();
        XrBitrateControl control = new XrBitrateControl(context);

        assertEquals(dp(context, 56), control.getChildAt(0).getLayoutParams().width);
        assertEquals(dp(context, 48), control.getChildAt(0).getLayoutParams().height);
        assertEquals(dp(context, 64), control.getChildAt(1).getLayoutParams().width);
        assertEquals(dp(context, 64), control.getChildAt(1).getLayoutParams().height);
        assertEquals(dp(context, 64), control.getChildAt(2).getLayoutParams().height);
        assertEquals(dp(context, 120), control.getChildAt(3).getLayoutParams().width);
        assertEquals(dp(context, 64), control.getChildAt(4).getLayoutParams().width);
        assertEquals(20f * context.getResources().getDisplayMetrics().scaledDensity,
                ((TextView) control.getChildAt(3)).getTextSize(), 0.5f);
    }

    @Test
    public void stepButtonsSelectStableChoiceAndUpdateVisualMeter() {
        Context context = themedContext();
        XrBitrateControl control = new XrBitrateControl(context);
        AtomicReference<String> selected = new AtomicReference<>();
        control.setChoices(Arrays.asList(
                        choice("10000", "10 Mbps"),
                        choice("20000", "20 Mbps"),
                        choice("40000", "40 Mbps")),
                "10000", null, value -> {
                    selected.set(value);
                    return true;
                });

        assertEquals(1, control.getActiveBarsForTests());
        control.getChildAt(4).performClick();

        assertEquals("20000", selected.get());
        assertEquals("20000", control.getSelectedChoiceId());
        assertEquals(3, control.getActiveBarsForTests());
        assertTrue(control.getChildAt(1).isEnabled());
    }

    @Test
    public void rejectedSelectionRestoresExistingValueAndDisabledStatePropagates() {
        Context context = themedContext();
        XrBitrateControl control = new XrBitrateControl(context);
        control.setChoices(Arrays.asList(
                        choice("10000", "10 Mbps"),
                        choice("20000", "20 Mbps")),
                "10000", null, value -> false);

        control.getChildAt(4).performClick();
        assertEquals("10000", control.getSelectedChoiceId());
        assertEquals(1, control.getActiveBarsForTests());

        control.setEnabled(false);
        for (int i = 0; i < control.getChildCount(); i++) {
            View child = control.getChildAt(i);
            assertFalse(child.isEnabled());
        }
    }

    @Test
    public void customBitrateIsOrderedByValueInsteadOfAppendedAfterMaximum() {
        Context context = themedContext();
        XrBitrateControl control = new XrBitrateControl(context);
        control.setChoices(Arrays.asList(
                        choice("10000", "10 Mbps"),
                        choice("300000", "300 Mbps"),
                        choice("113000", "113 Mbps")),
                "113000", null, value -> true);

        assertEquals("113000", control.getSelectedChoiceId());
        control.getChildAt(4).performClick();
        assertEquals("300000", control.getSelectedChoiceId());
    }

    @Test
    public void replacingChoicesRemovesTransientBitrateAndUsesLatestListener() {
        Context context = themedContext();
        XrBitrateControl control = new XrBitrateControl(context);
        AtomicInteger staleCalls = new AtomicInteger();
        AtomicReference<String> selected = new AtomicReference<>();
        control.setChoices(Arrays.asList(
                        choice("10000", "10 Mbps"),
                        choice("20000", "20 Mbps"),
                        choice("24000", "24 Mbps"),
                        choice("30000", "30 Mbps")),
                "24000", null, value -> {
                    staleCalls.incrementAndGet();
                    return true;
                });

        control.setChoices(Arrays.asList(
                        choice("10000", "10 Mbps"),
                        choice("20000", "20 Mbps"),
                        choice("30000", "30 Mbps")),
                "10000", null, value -> {
                    selected.set(value);
                    return true;
                });
        control.getChildAt(4).performClick();

        assertEquals(0, staleCalls.get());
        assertEquals("20000", selected.get());
        assertEquals("20000", control.getSelectedChoiceId());
    }

    private static SessionSettingsModel.Choice choice(String id, String label) {
        return new SessionSettingsModel.Choice(id, label);
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.AppTheme);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
