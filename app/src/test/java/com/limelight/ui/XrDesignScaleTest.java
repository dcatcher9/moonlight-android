package com.limelight.ui;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import com.limelight.R;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the XR design scales against drift.
 *
 * <p>The XR surfaces share five type steps, three corner radii, five spacing steps and four
 * control-height roles, all declared in {@code dimens.xml} as {@code xr_*} tokens. Nothing at runtime
 * enforces that — a new layout can hardcode {@code 17sp} and look almost right, and "almost right"
 * repeated a dozen times is exactly the unevenness the scales were introduced to remove. So this
 * reads the layouts as source and fails on raw literals where a token exists.</p>
 *
 * <p>Only XR-facing layouts are covered. The ~75 legacy phone and TV layouts inherited from
 * upstream are deliberately exempt: they are not on this scale and rewriting them buys nothing.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class XrDesignScaleTest {
    /**
     * Layouts held to the scale. Names alone are not enough: the XR-only Home and connection
     * screens predate the {@code xr_*} prefix, so keep every active spatial surface explicit.
     */
    private static boolean isXrLayout(String fileName) {
        return fileName.contains("xr")
                || fileName.equals("activity_pc_view.xml")
                || fileName.equals("activity_add_computer_manually.xml")
                || fileName.equals("pc_grid_view.xml")
                || fileName.equals("pc_grid_item.xml")
                || fileName.equals("app_grid_item.xml")
                || fileName.equals("app_grid_view.xml")
                || fileName.equals("activity_app_view.xml")
                || fileName.equals("pc_grid_item_hero.xml")
                || fileName.equals("activity_stream_settings.xml");
    }

    private static final Pattern RAW_TEXT_SIZE =
            Pattern.compile("android:textSize=\"([0-9.]+)sp\"");

    private File layoutDir() {
        // Unit tests run with the module directory as the working directory.
        File dir = new File("src/main/res/layout");
        assertTrue("layout sources not found at " + dir.getAbsolutePath()
                + " -- this test reads resources as source and needs the module working directory",
                dir.isDirectory());
        return dir;
    }

    @Test
    public void xrLayoutsAndStylesDeclareTextSizesThroughTheTypeScale() throws IOException {
        List<String> offenders = new ArrayList<>();
        File[] files = layoutDir().listFiles();
        if (files == null) {
            fail("layout directory could not be listed");
            return;
        }
        for (File file : files) {
            if (!isXrLayout(file.getName())) {
                continue;
            }
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = RAW_TEXT_SIZE.matcher(source);
            while (matcher.find()) {
                offenders.add(file.getName() + ": " + matcher.group(0));
            }
        }
        File styles = new File("src/main/res/values/styles.xml");
        assertTrue("style source not found", styles.isFile());
        String styleSource =
                new String(Files.readAllBytes(styles.toPath()), StandardCharsets.UTF_8);
        Matcher styleMatcher = Pattern.compile(
                "name=\"android:textSize\">([0-9.]+)sp<").matcher(styleSource);
        while (styleMatcher.find()) {
            offenders.add("styles.xml: " + styleMatcher.group(0));
        }
        if (!offenders.isEmpty()) {
            fail("XR layouts/styles must use @dimen/xr_text_* rather than raw sp values:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    public void xrLayoutsAndStylesDeclareSmallSpacingThroughTheSpacingScale()
            throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern layoutSpacing = Pattern.compile(
                "android:(?:padding(?:Start|Top|End|Bottom)?"
                        + "|layout_margin(?:Start|Top|End|Bottom)?"
                        + "|drawablePadding|horizontalSpacing|verticalSpacing)"
                        + "=\"([0-9.]+)dp\"");
        for (File file : xrLayoutFiles()) {
            collectSmallRawDimensions(file, layoutSpacing, offenders);
        }

        File styles = new File("src/main/res/values/styles.xml");
        assertTrue("style source not found", styles.isFile());
        Pattern styleSpacing = Pattern.compile(
                "name=\"android:(?:padding(?:Start|Top|End|Bottom)?"
                        + "|layout_margin(?:Start|Top|End|Bottom)?"
                        + "|drawablePadding)\">([0-9.]+)dp<");
        collectSmallRawDimensions(styles, styleSpacing, offenders);

        if (!offenders.isEmpty()) {
            fail("XR spacing at 24dp or below must use @dimen/xr_space_* rather than a raw"
                    + " value:\n  " + String.join("\n  ", offenders));
        }
    }

    @Test
    public void xrLayoutsAndStylesUseControlHeightTokens() throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern layoutHeight = Pattern.compile(
                "android:(?:layout_height|minHeight)=\"(40|56|64|80)dp\"");
        for (File file : xrLayoutFiles()) {
            collectMatches(file, layoutHeight, offenders);
        }

        File styles = new File("src/main/res/values/styles.xml");
        assertTrue("style source not found", styles.isFile());
        Pattern styleHeight = Pattern.compile(
                "name=\"android:minHeight\">(40|56|64|80)dp<");
        collectMatches(styles, styleHeight, offenders);

        if (!offenders.isEmpty()) {
            fail("XR controls must use @dimen/xr_control_* for standard heights:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    public void xrDrawablesDeclareCornersThroughTheRadiusScale() throws IOException {
        List<String> offenders = new ArrayList<>();
        File dir = new File("src/main/res/drawable");
        assertTrue("drawable sources not found", dir.isDirectory());
        File[] files = dir.listFiles();
        if (files == null) {
            fail("drawable directory could not be listed");
            return;
        }
        Pattern rawRadius = Pattern.compile(
                "android:(?:radius|top(?:Left|Right)Radius|bottom(?:Left|Right)Radius)"
                        + "=\"([0-9.]+)dp\"");
        for (File file : files) {
            if (!file.getName().startsWith("xr_")) {
                continue;
            }
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = rawRadius.matcher(source);
            while (matcher.find()) {
                offenders.add(file.getName() + ": " + matcher.group(0));
            }
        }
        if (!offenders.isEmpty()) {
            fail("XR drawables must use @dimen/xr_radius_* rather than raw dp values:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    public void xrLayoutsUseTheMaskedSelectionHighlight() throws IOException {
        // The platform highlight has no mask, so on a rounded XR surface it fills the corners
        // square inside the border. xr_selectable_overlay is the same highlight, masked.
        List<String> offenders = new ArrayList<>();
        File[] files = layoutDir().listFiles();
        if (files == null) {
            fail("layout directory could not be listed");
            return;
        }
        for (File file : files) {
            if (!isXrLayout(file.getName())) {
                continue;
            }
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (source.contains("selectableItemBackground")) {
                offenders.add(file.getName());
            }
        }
        if (!offenders.isEmpty()) {
            fail("XR layouts must use @drawable/xr_selectable_overlay so the highlight follows the"
                    + " rounded outline:\n  " + String.join("\n  ", offenders));
        }
    }

    @Test
    public void xrIconsAreDrawnInTheAccentColour() throws IOException {
        // Every xr_* icon is accent. The control bar looks white because it force-filters its
        // tiles at runtime, not because those drawables differ -- so this rule has no exceptions
        // to encode, and an icon that opts out here is a mistake rather than a special case.
        List<String> offenders = new ArrayList<>();
        File dir = new File("src/main/res/drawable");
        assertTrue("drawable sources not found", dir.isDirectory());
        File[] files = dir.listFiles();
        if (files == null) {
            fail("drawable directory could not be listed");
            return;
        }
        for (File file : files) {
            if (!file.getName().startsWith("ic_xr_")) {
                continue;
            }
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!source.contains("@color/xr_accent")
                    || source.contains("@color/xr_text_primary")) {
                offenders.add(file.getName());
            }
        }
        if (!offenders.isEmpty()) {
            fail("XR icons must be tinted @color/xr_accent:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    public void typeScaleStepsAreDistinctAndOrdered() {
        int[] steps = {
                R.dimen.xr_text_caption,
                R.dimen.xr_text_body,
                R.dimen.xr_text_emphasis,
                R.dimen.xr_text_title,
                R.dimen.xr_text_display,
        };
        float previous = 0f;
        for (int step : steps) {
            float size = RuntimeEnvironment.getApplication()
                    .getResources().getDimension(step);
            assertTrue("type scale must increase strictly", size > previous);
            previous = size;
        }
    }

    @Test
    public void controlHeightsCoverTheGazeTargetMinimum() {
        // Gaze-and-pinch samples the eye only at the pinch instant, so even the compact step has
        // to stay at or above the 40dp target the rest of the XR chrome assumes.
        float density = RuntimeEnvironment.getApplication()
                .getResources().getDisplayMetrics().density;
        float compact = RuntimeEnvironment.getApplication()
                .getResources().getDimension(R.dimen.xr_control_compact);
        assertTrue("compact control height must stay >= 40dp", compact >= 40 * density);
        float standard = RuntimeEnvironment.getApplication()
                .getResources().getDimension(R.dimen.xr_control_standard);
        float primary = RuntimeEnvironment.getApplication()
                .getResources().getDimension(R.dimen.xr_control_primary);
        float choice = RuntimeEnvironment.getApplication()
                .getResources().getDimension(R.dimen.xr_control_choice);
        assertTrue("control-height roles must increase", compact < standard
                && standard < primary && primary < choice);
        assertTrue("wrapped connected choices must stay >= 80dp", choice >= 80 * density);
    }

    private List<File> xrLayoutFiles() {
        List<File> result = new ArrayList<>();
        File[] files = layoutDir().listFiles();
        if (files == null) {
            fail("layout directory could not be listed");
            return result;
        }
        for (File file : files) {
            if (isXrLayout(file.getName())) {
                result.add(file);
            }
        }
        return result;
    }

    private static void collectSmallRawDimensions(File file, Pattern pattern,
                                                  List<String> offenders)
            throws IOException {
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            float value = Float.parseFloat(matcher.group(1));
            if (value > 0f && value <= 24f) {
                offenders.add(file.getName() + ": " + matcher.group(0));
            }
        }
    }

    private static void collectMatches(File file, Pattern pattern, List<String> offenders)
            throws IOException {
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            offenders.add(file.getName() + ": " + matcher.group(0));
        }
    }
}
