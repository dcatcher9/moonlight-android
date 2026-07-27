package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the presenter's colour constants equal to the palette they mirror.
 *
 * <p>The presenter used to declare these as {@code static final int} hex literals duplicated from
 * {@code colors_xr.xml}, and this test existed to catch the two copies drifting apart. They are
 * instance fields bound from the resources now, so drift is no longer possible -- but a field can
 * still be bound to the <em>wrong</em> token, which is what remains worth asserting. The mapping
 * below is therefore the specification of which token each surface uses.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public class XrPaletteTest {
    /**
     * The presenter must not carry colour literals. It used to declare fourteen of them as
     * {@code static final int} hex duplicated from {@code colors_xr.xml}, most with a comment
     * naming the token they mirrored -- and a duplicate kept in sync by comment is a divergence
     * waiting to happen. They read the palette directly now, so the rule to enforce is simply
     * that no new literal creeps back in.
     */
    @Test
    public void presenterCarriesNoColourLiterals() throws IOException {
        File source = new File("src/main/java/com/limelight/ui/XrStreamPresenter.java");
        assertTrue("presenter source not found at " + source.getAbsolutePath(), source.isFile());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        List<String> offenders = new ArrayList<>();
        Matcher matcher = Pattern.compile("0x[0-9A-Fa-f]{8}\b").matcher(text);
        while (matcher.find()) {
            // The alpha helper masks a colour it was already given; it names no colour itself.
            if (!"0x00FFFFFF".equals(matcher.group())) {
                offenders.add(matcher.group());
            }
        }
        assertEquals("XrStreamPresenter must take colours from the palette, not literals: "
                + offenders, 0, offenders.size());
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
