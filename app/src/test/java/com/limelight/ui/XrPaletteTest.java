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
        Matcher matcher = Pattern.compile(
                "0x[0-9A-Fa-f]{8}\\b"
                        + "|Color\\.(?:WHITE|BLACK|RED|GREEN|BLUE|YELLOW|GRAY|LTGRAY|DKGRAY)"
                        + "|Color\\.(?:rgb|argb)\\s*\\(").matcher(text);
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
    public void xrResourcesReferenceThePaletteInsteadOfInliningHexColours() throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern inlineHex = Pattern.compile("#[0-9A-Fa-f]{3,8}\\b");

        File layouts = new File("src/main/res/layout");
        assertTrue("layout sources not found", layouts.isDirectory());
        File[] layoutFiles = layouts.listFiles();
        if (layoutFiles != null) {
            for (File file : layoutFiles) {
                if (isXrLayout(file.getName())) {
                    collectMatches(file, inlineHex, offenders);
                }
            }
        }

        File drawables = new File("src/main/res/drawable");
        assertTrue("drawable sources not found", drawables.isDirectory());
        File[] drawableFiles = drawables.listFiles();
        if (drawableFiles != null) {
            for (File file : drawableFiles) {
                if (file.getName().startsWith("xr_")
                        || file.getName().startsWith("ic_xr_")) {
                    collectMatches(file, inlineHex, offenders);
                }
            }
        }

        File colors = new File("src/main/res/color");
        assertTrue("color-state sources not found", colors.isDirectory());
        File[] colorFiles = colors.listFiles();
        if (colorFiles != null) {
            for (File file : colorFiles) {
                if (file.getName().startsWith("xr_")) {
                    collectMatches(file, inlineHex, offenders);
                }
            }
        }

        collectMatches(new File("src/main/res/values/styles.xml"), inlineHex, offenders);
        assertEquals("XR resources must use colors_xr.xml tokens rather than inline hex: "
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
    public void translucentRolesDeriveTheirRgbFromAnOpaqueBase() {
        Context context = ApplicationProvider.getApplicationContext();
        assertSameRgb(context, R.color.xr_surface, R.color.xr_panel_surface);
        assertSameRgb(context, R.color.xr_surface_raised, R.color.xr_surface_raised_80);
        assertSameRgb(context, R.color.xr_surface_raised, R.color.xr_surface_raised_90);
        assertSameRgb(context, R.color.xr_accent, R.color.xr_accent_pressed_overlay);
        assertSameRgb(context, R.color.xr_accent, R.color.xr_accent_focus_overlay);
        assertSameRgb(context, R.color.xr_surface_sunken, R.color.xr_scrim_strong);
        assertSameRgb(context, R.color.xr_surface_sunken, R.color.xr_scrim_medium);
        assertSameRgb(context, R.color.xr_surface_sunken, R.color.xr_scrim_clear);
        assertSameRgb(context, R.color.xr_surface_sunken, R.color.xr_shadow_strong);
    }

    @Test
    public void statusAndDangerStayDistinctFromTheAccentHue() {
        Context context = ApplicationProvider.getApplicationContext();
        int accent = ContextCompat.getColor(context, R.color.xr_accent);
        // "Good" and "about to destroy something" must never read as "selected".
        for (int token : new int[] {R.color.xr_status_ok, R.color.xr_status_warn,
                R.color.xr_danger, R.color.xr_danger_container}) {
            assertTrue("status/danger colour collides with the accent",
                    distance(accent, ContextCompat.getColor(context, token)) > 60);
        }
    }

    private static boolean isXrLayout(String fileName) {
        return fileName.contains("xr")
                || fileName.equals("activity_pc_view.xml")
                || fileName.equals("activity_add_computer_manually.xml")
                || fileName.equals("pc_grid_view.xml")
                || fileName.equals("pc_grid_item.xml")
                || fileName.equals("pc_grid_item_hero.xml")
                || fileName.equals("activity_app_view.xml")
                || fileName.equals("app_grid_view.xml")
                || fileName.equals("app_grid_item.xml")
                || fileName.equals("activity_stream_settings.xml");
    }

    private static void collectMatches(File file, Pattern pattern, List<String> offenders)
            throws IOException {
        assertTrue("resource source not found: " + file.getAbsolutePath(), file.isFile());
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            offenders.add(file.getName() + ": " + matcher.group());
        }
    }

    private static int distance(int left, int right) {
        int dr = Math.abs(((left >> 16) & 0xFF) - ((right >> 16) & 0xFF));
        int dg = Math.abs(((left >> 8) & 0xFF) - ((right >> 8) & 0xFF));
        int db = Math.abs((left & 0xFF) - (right & 0xFF));
        return Math.max(dr, Math.max(dg, db));
    }

    private static void assertSameRgb(Context context, int opaqueResource, int alphaResource) {
        assertEquals(ContextCompat.getColor(context, opaqueResource) & 0x00FFFFFF,
                ContextCompat.getColor(context, alphaResource) & 0x00FFFFFF);
    }
}
