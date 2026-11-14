package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class XrDockRevealInteractionTest {
    @Test
    public void focusExpandsOnlyThePillUntilFocusLeaves() {
        Button pill = focusablePill(themedActivity());
        AtomicInteger reveals = new AtomicInteger();
        AtomicBoolean attention = new AtomicBoolean();
        XrStreamPresenter.configureDockRevealInteractions(
                pill, reveals::incrementAndGet, attention::set);

        assertTrue(pill.requestFocus());
        assertTrue(attention.get());
        assertEquals(0, reveals.get());

        pill.clearFocus();
        assertFalse(attention.get());
        assertEquals(0, reveals.get());
    }

    @Test
    public void hoverExpandsOnlyThePillUntilGazeLeaves() {
        Button pill = new Button(themedActivity());
        AtomicInteger reveals = new AtomicInteger();
        AtomicBoolean attention = new AtomicBoolean();
        XrStreamPresenter.configureDockRevealInteractions(
                pill, reveals::incrementAndGet, attention::set);

        hover(pill, MotionEvent.ACTION_HOVER_ENTER);
        assertTrue(attention.get());
        hover(pill, MotionEvent.ACTION_HOVER_MOVE);
        assertTrue(attention.get());
        assertEquals(0, reveals.get());

        hover(pill, MotionEvent.ACTION_HOVER_EXIT);
        assertFalse(attention.get());
        assertEquals(0, reveals.get());
    }

    @Test
    public void pillStaysExpandedWhileEitherHoverOrFocusRemains() {
        Button pill = focusablePill(themedActivity());
        AtomicInteger reveals = new AtomicInteger();
        AtomicBoolean attention = new AtomicBoolean();
        XrStreamPresenter.configureDockRevealInteractions(
                pill, reveals::incrementAndGet, attention::set);

        hover(pill, MotionEvent.ACTION_HOVER_ENTER);
        assertTrue(pill.requestFocus());
        hover(pill, MotionEvent.ACTION_HOVER_EXIT);
        assertTrue(attention.get());
        pill.clearFocus();
        assertFalse(attention.get());

        assertTrue(pill.requestFocus());
        hover(pill, MotionEvent.ACTION_HOVER_ENTER);
        pill.clearFocus();
        assertTrue(attention.get());
        hover(pill, MotionEvent.ACTION_HOVER_EXIT);
        assertFalse(attention.get());
        assertEquals(0, reveals.get());
    }

    @Test
    public void clearedAttentionStateForgetsAHoverWhoseExitWasNeverDelivered() {
        Button pill = focusablePill(themedActivity());
        AtomicBoolean attention = new AtomicBoolean();
        XrStreamPresenter.DockRevealViewAttentionState state =
                new XrStreamPresenter.DockRevealViewAttentionState();
        XrStreamPresenter.configureDockRevealInteractions(
                pill, () -> { }, attention::set, state);

        hover(pill, MotionEvent.ACTION_HOVER_ENTER);
        assertTrue(state.active());
        state.clear();
        assertFalse(state.active());

        // Without the clear, the latched hover would keep attention true after focus leaves.
        assertTrue(pill.requestFocus());
        assertTrue(attention.get());
        pill.clearFocus();
        assertFalse(attention.get());
    }

    @Test
    public void firstPressAndOrdinaryClickBothHaveDirectRevealPaths() {
        Activity activity = themedActivity();
        AtomicInteger pressReveals = new AtomicInteger();
        Button pressedPill = new Button(activity);
        pressedPill.setFocusable(false);
        XrStreamPresenter.configureDockRevealInteractions(
                pressedPill, pressReveals::incrementAndGet, active -> { });
        MotionEvent down = MotionEvent.obtain(1L, 1L, MotionEvent.ACTION_DOWN,
                10f, 10f, 0);
        pressedPill.dispatchTouchEvent(down);
        down.recycle();
        assertEquals(1, pressReveals.get());

        AtomicInteger clickReveals = new AtomicInteger();
        Button clickedPill = new Button(activity);
        XrStreamPresenter.configureDockRevealInteractions(
                clickedPill, clickReveals::incrementAndGet, active -> { });
        assertTrue(clickedPill.performClick());
        assertEquals(1, clickReveals.get());
    }

    private static void hover(Button pill, int action) {
        MotionEvent event = MotionEvent.obtain(1L, 1L, action, 10f, 10f, 0);
        event.setSource(InputDevice.SOURCE_MOUSE);
        pill.dispatchGenericMotionEvent(event);
        event.recycle();
    }

    private static Button focusablePill(Activity activity) {
        FrameLayout root = new FrameLayout(activity);
        root.setFocusableInTouchMode(true);
        Button pill = new Button(activity);
        pill.setFocusable(true);
        pill.setFocusableInTouchMode(true);
        root.addView(pill);
        return pill;
    }

    private static Activity themedActivity() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        activity.setTheme(R.style.AppTheme);
        return activity;
    }
}
