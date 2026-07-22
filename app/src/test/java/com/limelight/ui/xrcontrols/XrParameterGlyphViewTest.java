package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
public final class XrParameterGlyphViewTest {
    private Context context;

    @Before
    public void setUp() {
        Context application = ApplicationProvider.getApplicationContext();
        context = new ContextThemeWrapper(application, R.style.AppTheme);
    }

    @Test
    public void passiveGlyphIsFortyDpAndHiddenBehindAdjacentAccessibleText() {
        XrParameterGlyphView glyph = new XrParameterGlyphView(context);
        glyph.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        assertTrue(glyph.getMeasuredWidth() >= dp(36));
        assertTrue(glyph.getMeasuredWidth() <= dp(44));
        assertEquals(glyph.getMeasuredWidth(), glyph.getMeasuredHeight());
        assertFalse(glyph.isClickable());
        assertFalse(glyph.isLongClickable());
        assertFalse(glyph.isFocusable());
        assertNull(glyph.getContentDescription());
        assertEquals(ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO,
                ViewCompat.getImportantForAccessibility(glyph));
    }

    @Test
    public void fpsAndHdrResolveStableValuesIntoDeterministicMotionAndSunGeometry() {
        XrParameterGlyphView glyph = laidOutGlyph();
        glyph.setParameter(XrParameterGlyphView.Kind.FPS_MOTION_BARS, "30");
        assertEquals(1, glyph.resolvedVariant());
        assertEquals(12, glyph.geometryCount());
        float firstBarStart = glyph.geometryAt(0);

        glyph.setParameter(XrParameterGlyphView.Kind.FPS_MOTION_BARS, "120");
        assertEquals(3, glyph.resolvedVariant());
        assertEquals(12, glyph.geometryCount());
        assertEquals(firstBarStart, glyph.geometryAt(0), 0.0001f);

        glyph.setParameter(XrParameterGlyphView.Kind.HDR_SUN, "off");
        int offColor = glyph.resolvedSemanticColor();
        assertEquals(0, glyph.resolvedVariant());
        assertEquals(35, glyph.geometryCount());
        assertTrue(glyph.geometryAt(2) > 0f);

        glyph.setParameter(XrParameterGlyphView.Kind.HDR_SUN, "on");
        assertEquals(2, glyph.resolvedVariant());
        assertTrue(offColor != glyph.resolvedSemanticColor());
        assertEquals(35, glyph.geometryCount());
    }

    @Test
    public void rangeSwatchesAndPacingLineExposeDistinctFullAndCadenceStates() {
        XrParameterGlyphView glyph = laidOutGlyph();
        glyph.setParameter(XrParameterGlyphView.Kind.VIDEO_RANGE, "limited");
        assertEquals(0, glyph.resolvedVariant());
        assertEquals(16, glyph.geometryCount());
        float firstSwatchLeft = glyph.geometryAt(0);

        glyph.setParameter(XrParameterGlyphView.Kind.VIDEO_RANGE, "full");
        assertEquals(1, glyph.resolvedVariant());
        assertEquals(firstSwatchLeft, glyph.geometryAt(0), 0.0001f);

        glyph.setParameter(XrParameterGlyphView.Kind.FRAME_PACING, "latency");
        assertEquals(0, glyph.resolvedVariant());
        assertEquals(24, glyph.geometryCount());
        float latencyFirstTick = glyph.geometryAt(4);

        glyph.setParameter(XrParameterGlyphView.Kind.FRAME_PACING, "balanced");
        assertEquals(1, glyph.resolvedVariant());
        assertEquals(24, glyph.geometryCount());
        assertTrue(latencyFirstTick != glyph.geometryAt(4));

        glyph.setParameter(XrParameterGlyphView.Kind.FRAME_PACING, "cap-fps");
        assertEquals(2, glyph.resolvedVariant());
        glyph.setParameter(XrParameterGlyphView.Kind.FRAME_PACING, "smoothness");
        assertEquals(3, glyph.resolvedVariant());
    }

    @Test
    public void audioLayoutsMapStereoSurroundAndSevenOneToSpeakerDots() {
        XrParameterGlyphView glyph = laidOutGlyph();
        glyph.setParameter(XrParameterGlyphView.Kind.AUDIO_LAYOUT, "2");
        assertEquals(2, glyph.resolvedAudioDotCount());
        assertEquals(18, glyph.geometryCount());
        float speakerLeft = glyph.geometryAt(0);

        glyph.setParameter(XrParameterGlyphView.Kind.AUDIO_LAYOUT, "5.1");
        assertEquals(6, glyph.resolvedAudioDotCount());
        assertEquals(30, glyph.geometryCount());
        assertEquals(speakerLeft, glyph.geometryAt(0), 0.0001f);

        glyph.setParameter(XrParameterGlyphView.Kind.AUDIO_LAYOUT, "71");
        assertEquals(8, glyph.resolvedAudioDotCount());
        assertEquals(36, glyph.geometryCount());
    }

    @Test
    public void producerSwitchesBetweenPcMonitorAndClientHeadsetGeometry() {
        XrParameterGlyphView glyph = laidOutGlyph();
        glyph.setParameter(XrParameterGlyphView.Kind.PRODUCER, "pc");
        assertEquals(0, glyph.resolvedVariant());
        assertEquals(12, glyph.geometryCount());
        float pcTop = glyph.geometryAt(1);

        glyph.setParameter(XrParameterGlyphView.Kind.PRODUCER, "headset");
        assertEquals(1, glyph.resolvedVariant());
        assertEquals(12, glyph.geometryCount());
        assertTrue(pcTop != glyph.geometryAt(1));
    }

    @Test
    public void statusMapsGreenAmberAndRedWithoutDrawMutatingGeometry() {
        XrParameterGlyphView glyph = laidOutGlyph();
        glyph.setParameter(XrParameterGlyphView.Kind.STATUS, "green");
        int green = glyph.resolvedSemanticColor();
        assertEquals(0, glyph.resolvedVariant());
        assertEquals(4, glyph.geometryCount());

        glyph.setParameter(XrParameterGlyphView.Kind.STATUS, "amber");
        int amber = glyph.resolvedSemanticColor();
        assertEquals(1, glyph.resolvedVariant());
        glyph.setParameter(XrParameterGlyphView.Kind.STATUS, "red");
        int red = glyph.resolvedSemanticColor();
        assertEquals(2, glyph.resolvedVariant());
        assertTrue(green != amber);
        assertTrue(amber != red);
        assertTrue(green != red);

        float centerX = glyph.geometryAt(0);
        Bitmap bitmap = Bitmap.createBitmap(glyph.getWidth(), glyph.getHeight(),
                Bitmap.Config.ARGB_8888);
        glyph.draw(new Canvas(bitmap));
        assertEquals(centerX, glyph.geometryAt(0), 0.0001f);
        assertEquals(4, glyph.geometryCount());
    }

    @Test
    public void everySupportedKindDrawsFromItsPrecomputedGeometry() {
        XrParameterGlyphView glyph = laidOutGlyph();
        Bitmap bitmap = Bitmap.createBitmap(glyph.getWidth(), glyph.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        XrParameterGlyphView.Kind[] kinds = {
                XrParameterGlyphView.Kind.FPS_MOTION_BARS,
                XrParameterGlyphView.Kind.HDR_SUN,
                XrParameterGlyphView.Kind.VIDEO_RANGE,
                XrParameterGlyphView.Kind.FRAME_PACING,
                XrParameterGlyphView.Kind.AUDIO_LAYOUT,
                XrParameterGlyphView.Kind.PRODUCER,
                XrParameterGlyphView.Kind.STATUS,
        };
        String[] values = {"90", "on", "full", "balanced", "7.1", "headset", "green"};

        for (int i = 0; i < kinds.length; i++) {
            glyph.setParameter(kinds[i], values[i]);
            assertTrue(glyph.geometryCount() > 0);
            int geometryCount = glyph.geometryCount();
            glyph.draw(canvas);
            assertEquals(geometryCount, glyph.geometryCount());
        }
    }

    private XrParameterGlyphView laidOutGlyph() {
        XrParameterGlyphView glyph = new XrParameterGlyphView(context);
        int size = dp(40);
        glyph.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        glyph.layout(0, 0, size, size);
        return glyph;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
