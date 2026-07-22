package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.view.ViewCompat;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class XrModeChevronViewTest {
    private Context context;

    @Before
    public void setUp() {
        Context application = ApplicationProvider.getApplicationContext();
        context = new ContextThemeWrapper(application, R.style.AppTheme);
    }

    @Test
    public void intrinsicCueHasNormalChevronAspectAndNeverOwnsInputOrAccessibility() {
        XrModeChevronView chevron = new XrModeChevronView(context);
        chevron.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        assertTrue(chevron.getMeasuredWidth() >= dp(38));
        assertTrue(chevron.getMeasuredWidth() <= dp(42));
        assertTrue(chevron.getMeasuredHeight() >= dp(16));
        assertTrue(chevron.getMeasuredHeight() <= dp(20));
        assertTrue(chevron.getMeasuredWidth() > chevron.getMeasuredHeight() * 2);
        assertTrue(chevron.getMeasuredWidth() < chevron.getMeasuredHeight() * 3);
        assertFalse(chevron.isClickable());
        assertFalse(chevron.isLongClickable());
        assertFalse(chevron.isFocusable());
        assertNull(chevron.getContentDescription());
        assertEquals(ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO,
                ViewCompat.getImportantForAccessibility(chevron));
    }

    @Test
    public void collapsedIsCompactDownwardChevron() {
        XrModeChevronView chevron = laidOutChevron(40, 18);

        assertFalse(chevron.isExpanded());
        assertTrue(chevron.geometryAt(0) >= chevron.getWidth() * 0.14f);
        assertTrue(chevron.geometryAt(4) <= chevron.getWidth() * 0.86f);
        assertEquals(chevron.getWidth() * 0.5f, chevron.geometryAt(2), 0.0001f);
        assertEquals(chevron.geometryAt(1), chevron.geometryAt(5), 0.0001f);
        assertTrue(chevron.geometryAt(3) > chevron.geometryAt(1));
        assertTrue((chevron.geometryAt(4) - chevron.geometryAt(0))
                < chevron.getWidth() * 0.75f);
        assertEquals(dp(3), chevron.resolvedStrokeWidth(), 0.0001f);
        assertEquals(Paint.Cap.ROUND, chevron.resolvedStrokeCap());
        assertEquals(Paint.Join.ROUND, chevron.resolvedStrokeJoin());
    }

    @Test
    public void expandedFlipsApexUpAndDrawDoesNotMutateCachedGeometry() {
        XrModeChevronView chevron = laidOutChevron(40, 18);
        int collapsedColor = chevron.resolvedColor();
        chevron.setExpanded(true);
        float centerY = chevron.geometryAt(3);

        assertTrue(chevron.isExpanded());
        assertTrue(centerY < chevron.geometryAt(1));
        assertTrue(collapsedColor != chevron.resolvedColor());

        Bitmap bitmap = Bitmap.createBitmap(chevron.getWidth(), chevron.getHeight(),
                Bitmap.Config.ARGB_8888);
        chevron.draw(new Canvas(bitmap));

        assertEquals(centerY, chevron.geometryAt(3), 0.0001f);
    }

    private XrModeChevronView laidOutChevron(int widthDp, int heightDp) {
        XrModeChevronView chevron = new XrModeChevronView(context);
        int width = dp(widthDp);
        int height = dp(heightDp);
        chevron.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        chevron.layout(0, 0, width, height);
        return chevron;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
