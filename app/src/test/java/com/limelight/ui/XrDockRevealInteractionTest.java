package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.MotionEvent;
import android.widget.Button;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class XrDockRevealInteractionTest {
    @Test
    public void focusAcquisitionRevealsOnTheFirstInteraction() {
        Activity activity = themedActivity();
        Button pill = new Button(activity);
        pill.setFocusable(true);
        pill.setFocusableInTouchMode(true);
        AtomicInteger reveals = new AtomicInteger();
        XrStreamPresenter.configureDockRevealInteractions(pill, reveals::incrementAndGet);

        assertTrue(pill.requestFocus());
        assertEquals(1, reveals.get());
    }

    @Test
    public void firstPressAndOrdinaryClickBothHaveDirectRevealPaths() {
        Activity activity = themedActivity();
        AtomicInteger pressReveals = new AtomicInteger();
        Button pressedPill = new Button(activity);
        pressedPill.setFocusable(false);
        XrStreamPresenter.configureDockRevealInteractions(
                pressedPill, pressReveals::incrementAndGet);
        MotionEvent down = MotionEvent.obtain(1L, 1L, MotionEvent.ACTION_DOWN,
                10f, 10f, 0);
        pressedPill.dispatchTouchEvent(down);
        down.recycle();
        assertEquals(1, pressReveals.get());

        AtomicInteger clickReveals = new AtomicInteger();
        Button clickedPill = new Button(activity);
        XrStreamPresenter.configureDockRevealInteractions(
                clickedPill, clickReveals::incrementAndGet);
        assertTrue(clickedPill.performClick());
        assertEquals(1, clickReveals.get());
    }

    private static Activity themedActivity() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        activity.setTheme(R.style.AppTheme);
        return activity;
    }
}
