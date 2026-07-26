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

        assertEquals(12, options.size());
        for (int i = 0; i < 6; i++) {
            XrResolutionOptions.Option landscape = options.get(i);
            XrResolutionOptions.Option portrait = options.get(i + 6);
            assertFalse(landscape.portrait);
            assertTrue(portrait.portrait);
            assertEquals(landscape.height, portrait.width);
            assertEquals(landscape.width, portrait.height);
            assertEquals(portrait.width + "x" + portrait.height, portrait.id);
            assertEquals(landscape.label + " Portrait", portrait.label);
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
