package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The control is now a discrete ladder rather than a seek bar with steppers, so these assert the
 * segment behaviour: one pinchable target per rung, filled to the selection, no drag anywhere.
 */
@RunWith(RobolectricTestRunner.class)
public class XrBitrateControlTest {
    private static Context themedContext() {
        return new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                androidx.appcompat.R.style.Theme_AppCompat);
    }

    private static SessionSettingsModel.Choice choice(String id, String label) {
        return new SessionSettingsModel.Choice(id, label);
    }

    private static List<SessionSettingsModel.Choice> ladder() {
        return Arrays.asList(
                choice("50000", "50 Mbps"),
                choice("70000", "70 Mbps"),
                choice("100000", "100 Mbps"));
    }

    /** The row of segments inside the control's ladder. */
    private static LinearLayout segmentRow(XrBitrateControl control) {
        return ((XrSegmentedLadder) control.getChildAt(0)).segmentRow();
    }

    @Test
    public void everyRungBecomesItsOwnPinchableSegment() {
        XrBitrateControl control = new XrBitrateControl(themedContext());
        control.setChoices(ladder(), "50000", null, value -> true);

        LinearLayout row = segmentRow(control);
        assertEquals(3, row.getChildCount());
        for (int i = 0; i < row.getChildCount(); i++) {
            View segment = row.getChildAt(i);
            assertTrue("segment " + i + " must be clickable", segment.isClickable());
            assertTrue("segment " + i + " must be focusable", segment.isFocusable());
            // Gaze drives hover; touch-mode focus would eat the first pinch just to focus.
            assertFalse(segment.isFocusableInTouchMode());
        }
    }

    @Test
    public void segmentWidthsGrowLeftToRightSoTheRowIsNotUniform() {
        XrBitrateControl control = new XrBitrateControl(themedContext());
        control.setChoices(ladder(), "50000", null, value -> true);

        LinearLayout row = segmentRow(control);
        float previous = 0f;
        for (int i = 0; i < row.getChildCount(); i++) {
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
            assertTrue("segment " + i + " must be wider than its predecessor",
                    params.weight > previous);
            previous = params.weight;
        }
    }

    @Test
    public void pinchingASegmentSelectsItsStableChoiceId() {
        XrBitrateControl control = new XrBitrateControl(themedContext());
        AtomicReference<String> selected = new AtomicReference<>();
        control.setChoices(ladder(), "50000", null, value -> {
            selected.set(value);
            return true;
        });

        segmentRow(control).getChildAt(2).performClick();

        assertEquals("100000", selected.get());
        assertEquals("100000", control.getSelectedChoiceId());
    }

    @Test
    public void rejectedSelectionLeavesTheExistingValueInPlace() {
        XrBitrateControl control = new XrBitrateControl(themedContext());
        control.setChoices(ladder(), "50000", null, value -> false);

        segmentRow(control).getChildAt(1).performClick();

        assertEquals("50000", control.getSelectedChoiceId());
    }

    @Test
    public void choicesAreSortedByValueSoFillAlwaysRunsLowToHigh() {
        XrBitrateControl control = new XrBitrateControl(themedContext());
        control.setChoices(Arrays.asList(
                choice("100000", "100 Mbps"),
                choice("50000", "50 Mbps"),
                choice("70000", "70 Mbps")), "70000", null, value -> true);

        segmentRow(control).getChildAt(0).performClick();
        assertEquals("50000", control.getSelectedChoiceId());
    }

    @Test
    public void hostDrivenUpdateMovesTheSelectionWithoutNotifying() {
        XrBitrateControl control = new XrBitrateControl(themedContext());
        AtomicReference<String> selected = new AtomicReference<>();
        control.setChoices(ladder(), "50000", null, value -> {
            selected.set(value);
            return true;
        });

        assertTrue(control.setSelectedChoiceId("100000"));
        assertEquals("100000", control.getSelectedChoiceId());
        assertNull("a host-driven update must not call back", selected.get());
        assertFalse(control.setSelectedChoiceId("999999"));
    }

    @Test
    public void disablingPropagatesToEverySegment() {
        XrBitrateControl control = new XrBitrateControl(themedContext());
        control.setChoices(ladder(), "50000", null, value -> true);

        control.setEnabled(false);
        LinearLayout row = segmentRow(control);
        for (int i = 0; i < row.getChildCount(); i++) {
            assertFalse(row.getChildAt(i).isEnabled());
        }
    }

    @Test
    public void recommendationHintIsShownOnlyWhenARungSuits() {
        XrBitrateControl withHint = new XrBitrateControl(themedContext());
        withHint.setChoices(ladder(), "50000", 100000, "recommended", value -> true);
        assertEquals(3, segmentRow(withHint).getChildCount());
        XrSegmentedLadder marked = (XrSegmentedLadder) withHint.getChildAt(0);
        assertEquals(View.VISIBLE, marked.recommendationMarker().getVisibility());
        assertEquals("recommended", marked.recommendationMarker().getContentDescription());

        // -1 means no rung suits the stream shape; the control must still render.
        XrBitrateControl noHint = new XrBitrateControl(themedContext());
        noHint.setChoices(ladder(), "50000", -1, null, value -> true);
        assertEquals("50000", noHint.getSelectedChoiceId());
        XrSegmentedLadder unmarked = (XrSegmentedLadder) noHint.getChildAt(0);
        assertEquals(View.GONE, unmarked.recommendationMarker().getVisibility());
    }
}
