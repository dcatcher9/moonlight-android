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
 * <p>The XR surfaces share five type steps, three corner radii, five spacing steps and three
 * control heights, all declared in {@code dimens.xml} as {@code xr_*} tokens. Nothing at runtime
 * enforces that — a new layout can hardcode {@code 17sp} and look almost right, and "almost right"
 * repeated a dozen times is exactly the unevenness the scales were introduced to remove. So this
 * reads the layouts as source and fails on raw literals where a token exists.</p>
 *
 * <p>Only XR-facing layouts are covered. The ~75 legacy phone and TV layouts inherited from
 * upstream are deliberately exempt: they are not on this scale and rewriting them buys nothing.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class XrDesignScaleTest {
    /** Layouts held to the scale: every {@code xr_*} file plus the reworked home cards. */
    private static boolean isXrLayout(String fileName) {
        return fileName.contains("xr")
                || fileName.equals("app_grid_item.xml")
                || fileName.equals("activity_app_view.xml")
                || fileName.equals("pc_grid_item_hero.xml");
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
    public void xrLayoutsDeclareTextSizesThroughTheTypeScale() throws IOException {
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
        if (!offenders.isEmpty()) {
            fail("XR layouts must use @dimen/xr_text_* rather than raw sp values:\n  "
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
    }
}
