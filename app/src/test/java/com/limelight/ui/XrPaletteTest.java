package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the presenter's colour constants equal to the palette they mirror.
 *
 * <p>{@link XrStreamPresenter} declares its colours as {@code static final int}, which cannot read
 * a resource, so the values are duplicated from {@code colors_xr.xml}. That duplication is the
 * exact mechanism that produced six near-identical darks and two different error reds before the
 * palette existed, so it is enforced here instead of trusted: change the XML without changing the
 * constant and this fails.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class XrPaletteTest {
    private static Map<String, Integer> expectedTokens() {
        Map<String, Integer> map = new HashMap<>();
        map.put("TILE_IDLE_COLOR", R.color.xr_surface_raised);
        map.put("TILE_ACTIVE_COLOR", R.color.xr_accent_deep);
        map.put("TILE_ACTIVE_BORDER_COLOR", R.color.xr_accent);
        map.put("PANEL_BACKGROUND_COLOR", R.color.xr_surface_sunken);
        map.put("PANEL_SECTION_COLOR", R.color.xr_surface_raised);
        map.put("PANEL_SUBTLE_COLOR", R.color.xr_surface);
        map.put("STATS_LABEL_COLOR", R.color.xr_text_secondary);
        map.put("STATS_VALUE_COLOR", R.color.xr_text_primary);
        map.put("STATS_ON_COLOR", R.color.xr_status_ok);
        map.put("STATS_WARN_COLOR", R.color.xr_status_warn);
        map.put("STATS_ERROR_COLOR", R.color.xr_danger);
        map.put("STATS_UNAVAILABLE_COLOR", R.color.xr_text_disabled);
        return map;
    }

    @Test
    public void presenterConstantsMatchTheirPaletteTokens() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        for (Map.Entry<String, Integer> entry : expectedTokens().entrySet()) {
            Field field = XrStreamPresenter.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            assertTrue(entry.getKey() + " should be a constant",
                    Modifier.isStatic(field.getModifiers()));
            int constant = field.getInt(null);
            int token = ContextCompat.getColor(context, entry.getValue());
            assertEquals(entry.getKey() + " has drifted from its palette token",
                    Integer.toHexString(token), Integer.toHexString(constant));
        }
    }

    @Test
    public void segmentAliasesResolveToTheAccentFamily() {
        Context context = ApplicationProvider.getApplicationContext();
        // The ladder was authored against a stray blue before the palette existed; the alias keeps
        // it on the one accent hue the rest of the UI uses.
        assertEquals(ContextCompat.getColor(context, R.color.xr_accent),
                ContextCompat.getColor(context, R.color.xr_segment_filled));
    }

    @Test
    public void statusAndDangerStayDistinctFromTheAccentHue() {
        Context context = ApplicationProvider.getApplicationContext();
        int accent = ContextCompat.getColor(context, R.color.xr_accent);
        // "Good" and "about to destroy something" must never read as "selected".
        for (int token : new int[] {R.color.xr_status_ok, R.color.xr_status_warn,
                R.color.xr_danger, R.color.xr_danger}) {
            assertTrue("status/danger colour collides with the accent",
                    distance(accent, ContextCompat.getColor(context, token)) > 60);
        }
    }

    private static int distance(int left, int right) {
        int dr = Math.abs(((left >> 16) & 0xFF) - ((right >> 16) & 0xFF));
        int dg = Math.abs(((left >> 8) & 0xFF) - ((right >> 8) & 0xFF));
        int db = Math.abs((left & 0xFF) - (right & 0xFF));
        return Math.max(dr, Math.max(dg, db));
    }
}
