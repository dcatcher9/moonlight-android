package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class XrSegmentedLadderTest {
    @Test
    public void widthRampGrowsMonotonicallyAndStartsAtUnity() {
        float[] weights = XrSegmentedLadder.segmentWeights(4);
        assertEquals(4, weights.length);
        assertEquals(1.00f, weights[0], 0.0001f);
        assertEquals(1.18f, weights[1], 0.0001f);
        assertEquals(1.3924f, weights[2], 0.0001f);
        assertEquals(1.6430f, weights[3], 0.0001f);
        for (int i = 1; i < weights.length; i++) {
            assertTrue("segment " + i + " must be wider than " + (i - 1),
                    weights[i] > weights[i - 1]);
        }
    }

    @Test
    public void widestSegmentStaysWithinReadableProportionOfTheNarrowest() {
        // A bitrate ladder has 13 rungs. The ramp must still look like one control at that length
        // rather than letting the last segment swallow the row, which is what sizing the segments
        // by their underlying values did.
        float[] weights = XrSegmentedLadder.segmentWeights(13);
        float ratio = weights[weights.length - 1] / weights[0];
        assertTrue("13-rung ladder ratio " + ratio + " is too extreme", ratio < 10.0f);
    }

    @Test
    public void degenerateCountsDoNotThrow() {
        assertEquals(0, XrSegmentedLadder.segmentWeights(0).length);
        assertEquals(0, XrSegmentedLadder.segmentWeights(-3).length);
        assertEquals(1, XrSegmentedLadder.segmentWeights(1).length);
        assertEquals(1.0f, XrSegmentedLadder.segmentWeights(1)[0], 0.0001f);
    }

    @Test
    public void reentrantHostRefreshDuringSelectionKeepsTheHostSelection() {
        XrSegmentedLadder ladder = new XrSegmentedLadder(themedContext());
        java.util.List<SessionSettingsModel.Choice> refreshed = Arrays.asList(
                new SessionSettingsModel.Choice("60", "60"),
                new SessionSettingsModel.Choice("90", "90"),
                new SessionSettingsModel.Choice("120", "120"));
        ladder.setChoices(Arrays.asList(
                        new SessionSettingsModel.Choice("30", "30"),
                        new SessionSettingsModel.Choice("60", "60"),
                        new SessionSettingsModel.Choice("90", "90")),
                "60", null, null, null, choice -> {
                    // The accepted choice refreshes the settings model synchronously.
                    ladder.setChoices(refreshed, "90", null, null, null, ignored -> true);
                    return true;
                });

        ladder.segmentRow().getChildAt(0).performClick();

        assertEquals(1, ladder.getSelectedIndex());
    }

    @Test
    public void refreshWithUnchangedChoicesKeepsTheSegmentViews() {
        XrSegmentedLadder ladder = new XrSegmentedLadder(themedContext());
        ladder.setChoices(Arrays.asList(
                        new SessionSettingsModel.Choice("50", "50 Mbps"),
                        new SessionSettingsModel.Choice("100", "100 Mbps")),
                "50", null, null, null, choice -> true);
        android.view.View first = ladder.segmentRow().getChildAt(0);

        ladder.setChoices(Arrays.asList(
                        new SessionSettingsModel.Choice("50", "50 Mbps"),
                        new SessionSettingsModel.Choice("100", "100 Mbps")),
                "100", "100", "recommended", null, choice -> true);

        assertSame(first, ladder.segmentRow().getChildAt(0));
        assertEquals(1, ladder.getSelectedIndex());

        ladder.setChoices(Arrays.asList(
                        new SessionSettingsModel.Choice("50", "50 Mbps"),
                        new SessionSettingsModel.Choice("70", "70 Mbps")),
                "70", null, null, null, choice -> true);
        assertNotSame(first, ladder.segmentRow().getChildAt(0));
        assertEquals("70 Mbps",
                ((android.widget.TextView) ladder.segmentRow().getChildAt(1)).getText()
                        .toString());
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                androidx.appcompat.R.style.Theme_AppCompat);
    }

    @Test
    public void rebuildingChoicesPreservesDisabledStateAndBlocksSelection() {
        Context context = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(),
                androidx.appcompat.R.style.Theme_AppCompat);
        XrSegmentedLadder ladder = new XrSegmentedLadder(context);
        AtomicInteger callbacks = new AtomicInteger();

        ladder.setEnabled(false);
        ladder.setChoices(Arrays.asList(
                        new SessionSettingsModel.Choice("50", "50 Mbps"),
                        new SessionSettingsModel.Choice("100", "100 Mbps")),
                "50", null, null, null, choice -> {
                    callbacks.incrementAndGet();
                    return true;
                });

        LinearLayout row = ladder.segmentRow();
        assertEquals(2, row.getChildCount());
        for (int i = 0; i < row.getChildCount(); i++) {
            assertFalse(row.getChildAt(i).isEnabled());
            assertFalse(row.getChildAt(i).isClickable());
        }
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(
                eventTime, eventTime, MotionEvent.ACTION_DOWN, 1f, 1f, 0);
        MotionEvent up = MotionEvent.obtain(
                eventTime, eventTime + 1L, MotionEvent.ACTION_UP, 1f, 1f, 0);
        assertFalse(row.getChildAt(1).dispatchTouchEvent(down));
        assertFalse(row.getChildAt(1).dispatchTouchEvent(up));
        down.recycle();
        up.recycle();
        assertEquals(0, callbacks.get());
        assertEquals(0, ladder.getSelectedIndex());
    }
}
