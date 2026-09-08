package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class XrResolutionOptionsTest {
    @Test
    public void everyLandscapeOptionHasOneOrderedSwappedPortraitCounterpart() {
        List<XrResolutionOptions.Option> options = XrResolutionOptions.standardOptions();

        assertEquals(36, options.size());
        int landscapeCount = options.size() / 2;
        for (int i = 0; i < landscapeCount; i++) {
            XrResolutionOptions.Option landscape = options.get(i);
            XrResolutionOptions.Option portrait = options.get(i + landscapeCount);
            assertFalse(landscape.portrait);
            assertTrue(portrait.portrait);
            assertEquals(landscape.height, portrait.width);
            assertEquals(landscape.width, portrait.height);
            assertEquals(portrait.width + "x" + portrait.height, portrait.id);
            assertEquals(landscape.label + " Portrait", portrait.label);
        }
    }

    @Test
    public void phoneAndTabletSourcesExtendExistingLandscapesWithEvenCodecDimensions() {
        String[] expectedLandscapeIds = {
                "1920x1080", "2560x1440", "3840x2160",
                "2560x1080", "3440x1440", "5120x2160",
                "2160x1080", "2340x1080", "2400x1080", "2424x1080",
                "1920x1200", "2560x1600", "2048x1536", "2732x2048",
                "2160x1440", "2360x1640", "2388x1668", "2420x1668",
        };
        List<XrResolutionOptions.Option> options = XrResolutionOptions.standardOptions();
        assertEquals(expectedLandscapeIds.length * 2, options.size());
        for (int i = 0; i < expectedLandscapeIds.length; i++) {
            assertEquals(expectedLandscapeIds[i], options.get(i).id);
        }
        for (XrResolutionOptions.Option option : options) {
            assertEquals(option.id, 0, option.width % 2);
            assertEquals(option.id, 0, option.height % 2);
            assertTrue(option.id, Math.max(option.width, option.height) <= 5120);
            assertTrue(option.id, Math.min(option.width, option.height) <= 2160);
        }
    }

    @Test
    public void portraitGenerationIsDeterministicAndDeduplicatesSquaresAndExistingSwaps() {
        XrResolutionOptions.Option landscape =
                new XrResolutionOptions.Option("1600x900", "Test", 1600, 900, false);
        XrResolutionOptions.Option existingPortrait =
                new XrResolutionOptions.Option("900x1600", "Existing", 900, 1600, true);
        XrResolutionOptions.Option square =
                new XrResolutionOptions.Option("1000x1000", "Square", 1000, 1000, false);

        List<XrResolutionOptions.Option> options =
                XrResolutionOptions.buildWithPortraitCounterparts(
                        Arrays.asList(landscape, existingPortrait, square));

        assertEquals(3, options.size());
        assertEquals("1600x900", options.get(0).id);
        assertEquals("900x1600", options.get(1).id);
        assertEquals("1000x1000", options.get(2).id);
        assertEquals("Existing", options.get(1).label);
        assertEquals("Square", options.get(2).label);
    }

    @Test
    public void allExplicitXrOptionsAreNonNativeSoTheyDoNotDriveDisplayRotation() {
        for (XrResolutionOptions.Option option : XrResolutionOptions.standardOptions()) {
            assertFalse(option.id,
                    PreferenceConfiguration.isNativeResolution(option.width, option.height));
        }
        assertTrue(PreferenceConfiguration.isNativeResolution(1234, 2345));
    }
}
