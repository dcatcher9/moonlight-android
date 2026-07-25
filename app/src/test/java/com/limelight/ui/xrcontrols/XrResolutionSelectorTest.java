package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.util.TypedValue;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.AppCompatButton;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class XrResolutionSelectorTest {
    private Context context;

    @Before
    public void setUp() {
        Context application = ApplicationProvider.getApplicationContext();
        context = new ContextThemeWrapper(application, R.style.AppTheme);
    }

    @Test
    public void standardCardsExposeShortLabelsAndExplicitResolutionIds() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        // Widescreen family first, then ultrawide; each ascending. 720p is retired.
        String[] ids = {
                XrResolutionSelector.RESOLUTION_1080P,
                XrResolutionSelector.RESOLUTION_1440P,
                XrResolutionSelector.RESOLUTION_4K,
                XrResolutionSelector.RESOLUTION_UW_1080P,
                XrResolutionSelector.RESOLUTION_UW_1440P,
                XrResolutionSelector.RESOLUTION_5K2K,
        };
        String[] labels = {"1080p", "1440p", "4K", "UW 1080p", "UW 1440p", "5K2K"};

        assertEquals(6, selector.getCardCount());
        for (int i = 0; i < ids.length; i++) {
            AppCompatButton card = selector.getCardAt(i);
            assertEquals(ids[i], selector.getResolutionIdAt(i));
            assertEquals(ids[i], card.getTag());
            assertEquals(labels[i], selector.getLabelAt(i));
            assertEquals(labels[i], card.getText().toString());
            assertFalse(CompoundButton.class.isInstance(card));
            assertTrue(card.isClickable());
            assertTrue(card.isFocusable());
            assertFalse(card.isFocusableInTouchMode());
            assertTrue(card.getMinimumWidth() >= dp(112));
            assertTrue(card.getMinimumHeight() >= dp(104));
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f,
                    context.getResources().getDisplayMetrics()), card.getTextSize(), 0.5f);
            XrResolutionSelector.ResolutionCard resolutionCard =
                    (XrResolutionSelector.ResolutionCard) card;
            assertEquals(dp(60), resolutionCard.glyph.getIntrinsicWidth());
            assertEquals(dp(40), resolutionCard.glyph.getIntrinsicHeight());
        }
    }

    @Test
    public void firstTouchGestureSelectsAnInitiallyUnfocusedCard() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        XrResolutionSelector selector = new XrResolutionSelector(
                new ContextThemeWrapper(activity, R.style.AppTheme));
        root.addView(selector);
        activity.setContentView(root);

        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicReference<String> selectedByListener = new AtomicReference<>();
        selector.setOnResolutionSelectedListener(id -> {
            listenerCalls.incrementAndGet();
            selectedByListener.set(id);
            return true;
        });
        selector.setSelectedResolutionId(XrResolutionSelector.RESOLUTION_1080P);

        AppCompatButton ultraHd = selector.findCardByResolutionId(
                XrResolutionSelector.RESOLUTION_4K);
        assertFalse(ultraHd.isFocused());
        assertTrue(ultraHd.isFocusable());
        assertFalse(ultraHd.isFocusableInTouchMode());

        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, 1f, 1f, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 16,
                MotionEvent.ACTION_UP, 1f, 1f, 0);
        try {
            assertTrue(ultraHd.dispatchTouchEvent(down));
            assertTrue(ultraHd.dispatchTouchEvent(up));
        }
        finally {
            down.recycle();
            up.recycle();
        }
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, listenerCalls.get());
        assertEquals(XrResolutionSelector.RESOLUTION_4K, selectedByListener.get());
        assertEquals(XrResolutionSelector.RESOLUTION_4K,
                selector.getSelectedResolutionId());
        assertTrue(ultraHd.isActivated());
    }

    @Test
    public void glyphScreenBoundsPreserveTheResolutionAspectCue() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        // Three widescreen cards, then three ultrawide. Density is banded by height, so it rises
        // within each family and restarts at the family boundary.
        float[] expectedAspects = {16f / 9f, 16f / 9f, 16f / 9f,
                2560f / 1080f, 3440f / 1440f, 5120f / 2160f};
        int familySize = 3;
        int previousDensity = 0;
        for (int i = 0; i < selector.getCardCount(); i++) {
            XrResolutionSelector.ResolutionCard card =
                    (XrResolutionSelector.ResolutionCard) selector.getCardAt(i);
            XrResolutionSelector.ResolutionGlyphDrawable glyph = card.glyph;
            glyph.setBounds(0, 0, glyph.getIntrinsicWidth(), glyph.getIntrinsicHeight());
            RectF screen = glyph.getScreenBounds();

            assertEquals(expectedAspects[i], selector.getAspectRatioAt(i), 0.0001f);
            assertEquals(selector.getAspectRatioAt(i),
                    screen.width() / screen.height(), 0.0001f);
            assertTrue(screen.width() > screen.height());
            assertTrue(screen.bottom < glyph.getBounds().bottom);

            if (i % familySize == 0) {
                previousDensity = 0;
            }
            assertTrue(glyph.getDensityLevel() > previousDensity);
            previousDensity = glyph.getDensityLevel();
        }
    }

    @Test
    public void sameHeightAcrossFamiliesSharesADensityTier() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        // 1080p and UW 1080p are the same vertical class; only the glyph aspect separates them.
        assertEquals(densityOf(selector, XrResolutionSelector.RESOLUTION_1080P),
                densityOf(selector, XrResolutionSelector.RESOLUTION_UW_1080P));
        assertEquals(densityOf(selector, XrResolutionSelector.RESOLUTION_1440P),
                densityOf(selector, XrResolutionSelector.RESOLUTION_UW_1440P));
        assertEquals(densityOf(selector, XrResolutionSelector.RESOLUTION_4K),
                densityOf(selector, XrResolutionSelector.RESOLUTION_5K2K));
    }

    private static int densityOf(XrResolutionSelector selector, String resolutionId) {
        XrResolutionSelector.ResolutionCard card =
                (XrResolutionSelector.ResolutionCard) selector.findCardByResolutionId(resolutionId);
        return card.glyph.getDensityLevel();
    }

    @Test
    public void clickingACardUpdatesSelectionAndReportsItsExplicitId() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        AtomicReference<String> selectedByListener = new AtomicReference<>();
        selector.setOnResolutionSelectedListener(id -> {
            selectedByListener.set(id);
            return true;
        });
        selector.setSelectedResolutionId(XrResolutionSelector.RESOLUTION_1080P);

        AppCompatButton fullHd = selector.findCardByResolutionId(
                XrResolutionSelector.RESOLUTION_1080P);
        AppCompatButton ultraHd = selector.findCardByResolutionId(
                XrResolutionSelector.RESOLUTION_4K);
        assertTrue(fullHd.isActivated());
        assertTrue(fullHd.isSelected());
        assertFalse(ultraHd.isActivated());

        ultraHd.performClick();

        assertEquals(XrResolutionSelector.RESOLUTION_4K, selectedByListener.get());
        assertEquals(XrResolutionSelector.RESOLUTION_4K,
                selector.getSelectedResolutionId());
        assertFalse(fullHd.isActivated());
        assertTrue(ultraHd.isActivated());
        assertTrue(ultraHd.getContentDescription().toString().contains("selected"));
    }

    @Test
    public void tightWidthFallsBackToVerticalCardsWhileFocusAndHoverRemainNative() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        XrResolutionSelector selector = new XrResolutionSelector(
                new ContextThemeWrapper(activity, R.style.AppTheme));
        root.addView(selector);
        activity.setContentView(root);

        int width = dp(100);
        selector.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        selector.layout(0, 0, selector.getMeasuredWidth(), selector.getMeasuredHeight());

        for (int i = 1; i < selector.getCardCount(); i++) {
            assertTrue(selector.getCardAt(i).getTop() > selector.getCardAt(i - 1).getTop());
        }

        AppCompatButton card = selector.getCardAt(1);
        assertTrue(card.requestFocus());
        assertTrue(card.isFocused());
        card.setHovered(true);
        assertTrue(card.isHovered());
        assertTrue(card.getBackground().isStateful());
    }

    @Test
    public void unknownCurrentValueGetsASelectedCustomCardUntilAStandardChoiceWins() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        // 3840x1600 is a real ultrawide that is deliberately not on the ladder. A retired 1280x720
        // would behave the same way, though migration normally rewrites it before it gets here.
        String customId = "3840x1600";
        selector.setSelectedResolutionId(customId);

        assertEquals(customId, selector.getSelectedResolutionId());
        assertEquals(7, selector.getCardCount());
        AppCompatButton custom = selector.findCardByResolutionId(customId);
        assertTrue(custom.isActivated());
        assertFalse(custom.isClickable());
        assertEquals(customId, custom.getTag());
        assertTrue(custom.getText().toString().contains("Custom"));
        assertTrue(custom.getText().toString().contains("3840 \u00d7 1600"));

        int customIndex = selector.getCardCount() - 1;
        assertEquals(3840f / 1600f, selector.getAspectRatioAt(customIndex), 0.0001f);
        XrResolutionSelector.ResolutionCard customCard =
                (XrResolutionSelector.ResolutionCard) custom;
        customCard.glyph.setBounds(0, 0, customCard.glyph.getIntrinsicWidth(),
                customCard.glyph.getIntrinsicHeight());
        RectF screen = customCard.glyph.getScreenBounds();
        assertEquals(3840f / 1600f, screen.width() / screen.height(), 0.0001f);

        selector.findCardByResolutionId(XrResolutionSelector.RESOLUTION_1440P).performClick();

        assertEquals(XrResolutionSelector.RESOLUTION_1440P,
                selector.getSelectedResolutionId());
        assertEquals(6, selector.getCardCount());
        assertNull(selector.findCardByResolutionId(customId));
        assertTrue(selector.findCardByResolutionId(
                XrResolutionSelector.RESOLUTION_1440P).isActivated());
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
