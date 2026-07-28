package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
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
        // Existing landscapes retain their order, followed by exact swapped portrait IDs.
        String[] ids = {
                XrResolutionSelector.RESOLUTION_1080P,
                XrResolutionSelector.RESOLUTION_1440P,
                XrResolutionSelector.RESOLUTION_4K,
                XrResolutionSelector.RESOLUTION_UW_1080P,
                XrResolutionSelector.RESOLUTION_UW_1440P,
                XrResolutionSelector.RESOLUTION_5K2K,
                XrResolutionSelector.RESOLUTION_1080P_PORTRAIT,
                XrResolutionSelector.RESOLUTION_1440P_PORTRAIT,
                XrResolutionSelector.RESOLUTION_4K_PORTRAIT,
                XrResolutionSelector.RESOLUTION_UW_1080P_PORTRAIT,
                XrResolutionSelector.RESOLUTION_UW_1440P_PORTRAIT,
                XrResolutionSelector.RESOLUTION_5K2K_PORTRAIT,
        };
        String[] labels = {
                "1080p", "1440p", "4K", "UW 1080p", "UW 1440p", "5K2K",
                "1080p Portrait", "1440p Portrait", "4K Portrait",
                "UW 1080p Portrait", "UW 1440p Portrait", "5K2K Portrait"
        };

        assertEquals(12, selector.getCardCount());
        assertArrayEquals(ids, context.getResources().getStringArray(
                R.array.xr_resolution_values));
        for (int i = 0; i < ids.length; i++) {
            AppCompatButton card = selector.getCardAt(i);
            assertEquals(ids[i], selector.getResolutionIdAt(i));
            assertEquals(ids[i], card.getTag());
            assertEquals(labels[i], selector.getLabelAt(i));
            // Portrait cards break the orientation word onto a second line so they do not force
            // every card in the uniformly sized grid to the width of the longest label.
            assertEquals(labels[i].replaceFirst(" Portrait$", "\nPortrait"),
                    card.getText().toString());
            assertFalse(CompoundButton.class.isInstance(card));
            assertTrue(card.isClickable());
            assertTrue(card.isFocusable());
            assertFalse(card.isFocusableInTouchMode());
            assertTrue(card.getMinimumWidth() >= dp(112));
            assertTrue(card.getMinimumHeight() >= dp(104));
            assertEquals(context.getResources().getDimension(R.dimen.xr_text_title),
                    card.getTextSize(), 0.5f);
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
        // Six established landscapes followed by their swapped portrait counterparts.
        float[] expectedAspects = {16f / 9f, 16f / 9f, 16f / 9f,
                2560f / 1080f, 3440f / 1440f, 5120f / 2160f,
                9f / 16f, 9f / 16f, 9f / 16f,
                1080f / 2560f, 1440f / 3440f, 2160f / 5120f};
        for (int i = 0; i < selector.getCardCount(); i++) {
            XrResolutionSelector.ResolutionCard card =
                    (XrResolutionSelector.ResolutionCard) selector.getCardAt(i);
            XrResolutionSelector.ResolutionGlyphDrawable glyph = card.glyph;
            glyph.setBounds(0, 0, glyph.getIntrinsicWidth(), glyph.getIntrinsicHeight());
            RectF screen = glyph.getScreenBounds();

            assertEquals(expectedAspects[i], selector.getAspectRatioAt(i), 0.0001f);
            assertEquals(selector.getAspectRatioAt(i),
                    screen.width() / screen.height(), 0.0001f);
            assertEquals(i < 6, screen.width() > screen.height());
            assertTrue(screen.bottom < glyph.getBounds().bottom);
            if (i >= 6) {
                assertEquals(((XrResolutionSelector.ResolutionCard)
                                selector.getCardAt(i - 6)).glyph.getDensityLevel(),
                        glyph.getDensityLevel());
            }
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
        assertEquals(densityOf(selector, XrResolutionSelector.RESOLUTION_1080P),
                densityOf(selector, XrResolutionSelector.RESOLUTION_1080P_PORTRAIT));
        assertEquals(densityOf(selector, XrResolutionSelector.RESOLUTION_UW_1440P),
                densityOf(selector, XrResolutionSelector.RESOLUTION_UW_1440P_PORTRAIT));
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
    public void wideWidthStillKeepsLandscapeAndPortraitCardsOnSeparateRows() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        int width = dp(2000);
        selector.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        selector.layout(0, 0, selector.getMeasuredWidth(), selector.getMeasuredHeight());

        int landscapeTop = selector.getCardAt(0).getTop();
        for (int i = 1; i < 6; i++) {
            assertEquals(landscapeTop, selector.getCardAt(i).getTop());
        }

        int portraitTop = selector.getCardAt(6).getTop();
        assertTrue(portraitTop > landscapeTop);
        for (int i = 7; i < 12; i++) {
            assertEquals(portraitTop, selector.getCardAt(i).getTop());
        }
    }

    @Test
    public void everyCardIsMeasuredToOneUniformSizeRegardlessOfLabelLength() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        selector.measure(View.MeasureSpec.makeMeasureSpec(dp(2000), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        selector.layout(0, 0, selector.getMeasuredWidth(), selector.getMeasuredHeight());

        int width = selector.getCardAt(0).getWidth();
        int height = selector.getCardAt(0).getHeight();
        assertTrue(width > 0);
        assertTrue(height > 0);
        for (int i = 1; i < selector.getCardCount(); i++) {
            // "UW 1440p Portrait" is the longest label on the ladder; before uniform measurement
            // it produced a visibly wider card than the landscape entry beside it.
            assertEquals(width, selector.getCardAt(i).getWidth());
            assertEquals(height, selector.getCardAt(i).getHeight());
        }
    }

    @Test
    public void portraitGlyphsSpreadDensityDotsAlongTheirLongAxisInsteadOfCollapsing() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        // Cards 6..8 are 1080p/1440p/4K portrait: identical 9:16 aspect, so the density matrix is
        // the only thing separating them. Along the narrow axis the dots merged into one smudge,
        // which is what made every portrait icon look the same.
        float previousSpread = -1f;
        for (int i = 0; i < 3; i++) {
            XrResolutionSelector.ResolutionGlyphDrawable glyph = glyphAt(selector, 6 + i);
            float[] centers = glyph.densityDotCenters();
            assertEquals(glyph.getDensityLevel() * 4, centers.length);

            // Dots must step along Y (the long axis) and pair across X.
            float spread = centers[centers.length - 3] - centers[1];
            assertTrue(spread > 0f);
            assertEquals(centers[0], centers[centers.length - 4], 0.001f);
            assertTrue(centers[2] > centers[0]);

            // Consecutive dots must clear their own diameter, or they render as one blob.
            if (glyph.getDensityLevel() > 1) {
                float step = spread / (glyph.getDensityLevel() - 1);
                assertTrue(step > glyph.densityDotRadius() * 2f);
            }
            // Every tier packs the same span, so a denser tier is a visibly different picture.
            if (previousSpread >= 0f) {
                assertEquals(previousSpread, spread, 0.001f);
            }
            previousSpread = spread;
        }
    }

    @Test
    public void landscapeGlyphsKeepTheirHorizontalDensityRow() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        for (int i = 0; i < 6; i++) {
            XrResolutionSelector.ResolutionGlyphDrawable glyph = glyphAt(selector, i);
            float[] centers = glyph.densityDotCenters();
            // Unchanged from before the portrait fix: step along X, pair across Y.
            assertTrue(centers[centers.length - 4] - centers[0] > 0f);
            assertEquals(centers[1], centers[centers.length - 3], 0.001f);
            assertTrue(centers[3] > centers[1]);
        }
    }

    @Test
    public void glyphGeometryCacheTracksBoundsTranslationAndResize() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        XrResolutionSelector.ResolutionGlyphDrawable glyph = glyphAt(selector, 6);
        RectF originalScreen = glyph.getScreenBounds();
        float[] originalCenters = glyph.densityDotCenters();
        int width = glyph.getIntrinsicWidth();
        int height = glyph.getIntrinsicHeight();
        int dx = 13;
        int dy = 17;

        glyph.setBounds(dx, dy, dx + width, dy + height);
        RectF translatedScreen = glyph.getScreenBounds();
        float[] translatedCenters = glyph.densityDotCenters();
        assertEquals(originalScreen.left + dx, translatedScreen.left, 0.001f);
        assertEquals(originalScreen.top + dy, translatedScreen.top, 0.001f);
        assertEquals(originalScreen.right + dx, translatedScreen.right, 0.001f);
        assertEquals(originalScreen.bottom + dy, translatedScreen.bottom, 0.001f);
        for (int i = 0; i < originalCenters.length; i += 2) {
            assertEquals(originalCenters[i] + dx, translatedCenters[i], 0.001f);
            assertEquals(originalCenters[i + 1] + dy, translatedCenters[i + 1], 0.001f);
        }

        glyph.setBounds(dx, dy, dx + width * 2, dy + height * 2);
        RectF resizedScreen = glyph.getScreenBounds();
        float[] resizedCenters = glyph.densityDotCenters();
        assertEquals(glyph.getAspectRatio(),
                resizedScreen.width() / resizedScreen.height(), 0.0001f);
        assertTrue(resizedScreen.width() > translatedScreen.width());
        assertTrue(resizedScreen.height() > translatedScreen.height());
        assertTrue(resizedCenters[resizedCenters.length - 3] - resizedCenters[1]
                > translatedCenters[translatedCenters.length - 3] - translatedCenters[1]);
    }

    @Test
    public void glyphGeometryAccessorsReturnDefensiveCopies() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        XrResolutionSelector.ResolutionGlyphDrawable glyph = glyphAt(selector, 7);
        RectF expectedScreen = glyph.getScreenBounds();
        float[] expectedCenters = glyph.densityDotCenters();

        RectF mutableScreen = glyph.getScreenBounds();
        float[] mutableCenters = glyph.densityDotCenters();
        assertFalse(expectedScreen == mutableScreen);
        assertFalse(expectedCenters == mutableCenters);
        mutableScreen.set(-100f, -100f, -50f, -50f);
        for (int i = 0; i < mutableCenters.length; i++) {
            mutableCenters[i] = -100f;
        }

        assertEquals(expectedScreen, glyph.getScreenBounds());
        assertArrayEquals(expectedCenters, glyph.densityDotCenters(), 0f);
    }

    private static XrResolutionSelector.ResolutionGlyphDrawable glyphAt(
            XrResolutionSelector selector, int index) {
        XrResolutionSelector.ResolutionGlyphDrawable glyph =
                ((XrResolutionSelector.ResolutionCard) selector.getCardAt(index)).glyph;
        glyph.setBounds(0, 0, glyph.getIntrinsicWidth(), glyph.getIntrinsicHeight());
        return glyph;
    }

    @Test
    public void unknownCurrentValueGetsASelectedCustomCardUntilAStandardChoiceWins() {
        XrResolutionSelector selector = new XrResolutionSelector(context);
        // 3840x1600 is a real ultrawide that is deliberately not on the ladder. A retired 1280x720
        // would behave the same way, though migration normally rewrites it before it gets here.
        String customId = "3840x1600";
        selector.setSelectedResolutionId(customId);

        assertEquals(customId, selector.getSelectedResolutionId());
        assertEquals(13, selector.getCardCount());
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
        assertEquals(12, selector.getCardCount());
        assertNull(selector.findCardByResolutionId(customId));
        assertTrue(selector.findCardByResolutionId(
                XrResolutionSelector.RESOLUTION_1440P).isActivated());
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
