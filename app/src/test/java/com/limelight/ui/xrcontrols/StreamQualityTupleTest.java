package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pins the one resolution-id parser that the settings, classifier and presenter share. */
public final class StreamQualityTupleTest {
    @Test
    public void wellFormedIdsParseToWidthAndHeight() {
        assertArrayEquals(new int[] {3840, 2160},
                StreamQualityTuple.parseResolutionSize("3840x2160"));
        assertArrayEquals(new int[] {1080, 1920},
                StreamQualityTuple.parseResolutionSize("1080x1920"));
        // Each side is trimmed.
        assertArrayEquals(new int[] {1920, 1080},
                StreamQualityTuple.parseResolutionSize(" 1920 x 1080 "));
    }

    @Test
    public void unusableIdsAreNullRatherThanASubstitutedSize() {
        assertNull(StreamQualityTuple.parseResolutionSize(null));
        assertNull(StreamQualityTuple.parseResolutionSize(""));
        assertNull(StreamQualityTuple.parseResolutionSize("1080p"));
        assertNull(StreamQualityTuple.parseResolutionSize("1920"));
        assertNull(StreamQualityTuple.parseResolutionSize("x1080"));
        assertNull(StreamQualityTuple.parseResolutionSize("1920x"));
        assertNull(StreamQualityTuple.parseResolutionSize("1920x1080x2"));
        assertNull(StreamQualityTuple.parseResolutionSize("1920X1080"));
        assertNull(StreamQualityTuple.parseResolutionSize("wxh"));
        assertNull(StreamQualityTuple.parseResolutionSize("1920x0"));
        assertNull(StreamQualityTuple.parseResolutionSize("0x1080"));
        assertNull(StreamQualityTuple.parseResolutionSize("-1920x1080"));
        assertNull(StreamQualityTuple.parseResolutionSize("99999999999x1080"));
    }
}
