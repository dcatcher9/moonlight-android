package com.limelight.preferences;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.ui.PresentationMode;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.Test;

/** A resolution the shared parser cannot size is never classified as a live change. */
public final class XrLiveApplyClassifierTest {
    private static final StreamQualityTuple LIVE =
            new StreamQualityTuple("3840x2160", "90", 200000);

    private static boolean requiresReconnect(String pendingResolution, StreamQualityTuple live) {
        return XrLiveApplyClassifier.qualityDeltaRequiresReconnect(
                PresentationMode.NORMAL,
                new StreamQualityTuple(pendingResolution, "90", 200000),
                live, true, 8192, 8192, XrSettingChoices.CODEC_HEVC);
    }

    @Test
    public void usableSizeInsideTheEnvelopeAppliesLive() {
        assertFalse(requiresReconnect("1920x1080", LIVE));
        assertFalse(requiresReconnect(" 2560 x 1440 ", LIVE));
    }

    @Test
    public void unusablePendingSizeReconnectsInsteadOfBeingCheckedAsAnotherSize() {
        // A substituted 1280x720, or a zero axis, would fit the envelope and read as live.
        assertTrue(requiresReconnect("not-a-size", LIVE));
        assertTrue(requiresReconnect("1920x0", LIVE));
        assertTrue(requiresReconnect("1920x1080x2", LIVE));
    }

    @Test
    public void unusableLiveSizeReconnects() {
        assertTrue(requiresReconnect("1920x1080",
                new StreamQualityTuple("unknown", "90", 200000)));
    }
}
