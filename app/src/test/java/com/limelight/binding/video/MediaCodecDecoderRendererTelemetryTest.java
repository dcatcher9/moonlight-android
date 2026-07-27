package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.MediaFormat;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

public class MediaCodecDecoderRendererTelemetryTest {
    @Test
    public void temporaryStreamThrottleDoesNotLowerTheSurfaceCeiling() {
        MediaCodecDecoderRenderer.StreamFrameRateState rates =
                new MediaCodecDecoderRenderer.StreamFrameRateState();
        rates.initialize(90, 90);

        rates.updateEffectiveStreamFps(72);

        assertEquals(72, rates.getEffectiveStreamFps());
        assertEquals(90, rates.getSurfaceFrameRateHintFps());
    }

    @Test
    public void userCeilingAndSurfaceRecoveryReuseTheDurableHint() {
        MediaCodecDecoderRenderer.StreamFrameRateState rates =
                new MediaCodecDecoderRenderer.StreamFrameRateState();
        rates.initialize(90, 90);
        rates.updateEffectiveStreamFps(72);

        // A replacement output Surface reads this same retained hint.
        assertEquals(90, rates.getSurfaceFrameRateHintFps());

        // Only a successfully settled user ceiling changes the fixed-source preference.
        rates.updateSurfaceFrameRateCeilingFps(60);
        assertEquals(72, rates.getEffectiveStreamFps());
        assertEquals(60, rates.getSurfaceFrameRateHintFps());
    }

    @Test
    public void codecDescriptionReportsNegotiatedCodecAndProfile() {
        assertEquals("AV1 Main, 8-bit", MediaCodecDecoderRenderer.describeVideoCodec(
                MoonBridge.VIDEO_FORMAT_AV1_MAIN8));
        assertEquals("AV1 Main, 10-bit", MediaCodecDecoderRenderer.describeVideoCodec(
                MoonBridge.VIDEO_FORMAT_AV1_MAIN10));
        assertEquals("HEVC Main 10, 10-bit", MediaCodecDecoderRenderer.describeVideoCodec(
                MoonBridge.VIDEO_FORMAT_H265_MAIN10));
    }

    @Test
    public void outputPacingDescriptionDoesNotPretendToBeTheDecoderMode() {
        assertEquals("Lowest latency (latest frame)",
                MediaCodecDecoderRenderer.describeOutputPacing(
                        PreferenceConfiguration.FRAME_PACING_MIN_LATENCY));
        assertEquals("Balanced (vsync queue)",
                MediaCodecDecoderRenderer.describeOutputPacing(
                        PreferenceConfiguration.FRAME_PACING_BALANCED));
    }

    @Test
    public void hiddenPaneStillDispatchesWhenExplicitLoggingIsEnabled() {
        assertTrue(MediaCodecDecoderRenderer.shouldDispatchPerformanceSnapshot(false, true));
    }

    @Test
    public void hiddenPaneWithoutLoggingDoesNotDispatch() {
        assertFalse(MediaCodecDecoderRenderer.shouldDispatchPerformanceSnapshot(false, false));
    }

    @Test
    public void visiblePaneDispatchesWithoutLogging() {
        assertTrue(MediaCodecDecoderRenderer.shouldDispatchPerformanceSnapshot(true, false));
    }

    @Test
    public void directSubmitRemainsDisabledWhenCodecSupportsIt() {
        assertFalse(MediaCodecDecoderRenderer.shouldUseDirectSubmit(true));
        assertFalse(MediaCodecDecoderRenderer.shouldUseDirectSubmit(false));
    }

    @Test
    public void inputBufferHangDeadlineIsEnforcedInsideTheDequeueLoop() {
        assertFalse(MediaCodecDecoderRenderer.inputDequeueHangExpired(1_000, 5_999));
        assertTrue(MediaCodecDecoderRenderer.inputDequeueHangExpired(1_000, 6_000));
    }

    @Test
    public void absentDecoderColorRangeRemainsExplicitlyUnknown() {
        MediaCodecDecoderRenderer.ActualColorRange range =
                MediaCodecDecoderRenderer.resolveActualColorRange(null);

        assertEquals(MediaCodecDecoderRenderer.ActualColorRange.UNKNOWN, range);
        assertFalse(range.hasDecoderEvidence());
        assertFalse(range.isKnown());
    }

    @Test
    public void decoderColorRangeRequiresRecognizedOutputEvidence() {
        MediaCodecDecoderRenderer.ActualColorRange full =
                MediaCodecDecoderRenderer.resolveActualColorRange(MediaFormat.COLOR_RANGE_FULL);
        MediaCodecDecoderRenderer.ActualColorRange limited =
                MediaCodecDecoderRenderer.resolveActualColorRange(MediaFormat.COLOR_RANGE_LIMITED);
        MediaCodecDecoderRenderer.ActualColorRange unrecognized =
                MediaCodecDecoderRenderer.resolveActualColorRange(Integer.MAX_VALUE);

        assertEquals(MediaCodecDecoderRenderer.ActualColorRange.FULL, full);
        assertEquals(MediaCodecDecoderRenderer.ActualColorRange.LIMITED, limited);
        assertEquals(MediaCodecDecoderRenderer.ActualColorRange.UNRECOGNIZED, unrecognized);
        assertTrue(full.hasDecoderEvidence());
        assertTrue(limited.hasDecoderEvidence());
        assertTrue(unrecognized.hasDecoderEvidence());
        assertTrue(full.isKnown());
        assertTrue(limited.isKnown());
        assertFalse(unrecognized.isKnown());
    }

    @Test
    public void effectiveColorRangeMakesTheRequestedFallbackExplicit() {
        assertEquals(MoonBridge.COLOR_RANGE_FULL,
                MediaCodecDecoderRenderer.resolveEffectiveColorRange(
                        MediaCodecDecoderRenderer.ActualColorRange.UNKNOWN,
                        MoonBridge.COLOR_RANGE_FULL));
        assertEquals(MoonBridge.COLOR_RANGE_LIMITED,
                MediaCodecDecoderRenderer.resolveEffectiveColorRange(
                        MediaCodecDecoderRenderer.ActualColorRange.UNRECOGNIZED,
                        MoonBridge.COLOR_RANGE_LIMITED));
        assertEquals(MoonBridge.COLOR_RANGE_LIMITED,
                MediaCodecDecoderRenderer.resolveEffectiveColorRange(
                        MediaCodecDecoderRenderer.ActualColorRange.LIMITED,
                        MoonBridge.COLOR_RANGE_FULL));
    }

    @Test
    public void enablingTelemetryStartsACoherentWindowWithoutLosingAggregateStats() {
        VideoStats active = new VideoStats();
        active.measurementStartTimestamp = 100;
        active.totalFrames = 30;
        active.totalFramesReceived = 29;
        active.framesLost = 1;

        VideoStats last = new VideoStats();
        last.measurementStartTimestamp = 50;
        last.totalFrames = 60;
        last.totalFramesDecoded = 60;

        VideoStats global = new VideoStats();
        global.measurementStartTimestamp = 1;
        global.totalFrames = 100;
        global.totalFramesReceived = 98;
        global.framesLost = 2;

        MediaCodecDecoderRenderer.restartPerformanceTelemetryWindow(
                active, last, global, 500);

        assertEquals(130, global.totalFrames);
        assertEquals(127, global.totalFramesReceived);
        assertEquals(3, global.framesLost);
        assertEquals(0, active.totalFrames);
        assertEquals(0, active.totalFramesReceived);
        assertEquals(0, active.totalFramesDecoded);
        assertEquals(500, active.measurementStartTimestamp);
        assertEquals(0, last.totalFrames);
        assertEquals(0, last.totalFramesDecoded);
        assertEquals(0, last.measurementStartTimestamp);
    }

    @Test
    public void enablingTelemetryDoesNotFoldAnUnstartedWindowIntoGlobalStats() {
        VideoStats active = new VideoStats();
        VideoStats last = new VideoStats();
        VideoStats global = new VideoStats();
        global.measurementStartTimestamp = 25;
        global.totalFrames = 7;

        MediaCodecDecoderRenderer.restartPerformanceTelemetryWindow(
                active, last, global, 500);

        assertEquals(7, global.totalFrames);
        assertEquals(25, global.measurementStartTimestamp);
        assertEquals(500, active.measurementStartTimestamp);
    }

    @Test
    public void intentionalTransitionGapIsNotReportedAsNetworkLoss() {
        assertEquals(4, MediaCodecDecoderRenderer.countMissingFrames(100, 105, false));
        assertEquals(0, MediaCodecDecoderRenderer.countMissingFrames(100, 105, true));
        assertEquals(2, MediaCodecDecoderRenderer.countMissingFrames(105, 108, false));
    }

    @Test
    public void frameNumberWrapDoesNotCreateNegativeOrSyntheticLoss() {
        assertEquals(0, MediaCodecDecoderRenderer.countMissingFrames(
                Integer.MAX_VALUE, Integer.MIN_VALUE, false));
    }

    @Test
    public void sameFrameNaluDoesNotConsumePendingTransitionSuppression() {
        assertFalse(MediaCodecDecoderRenderer.consumesIntentionalDiscontinuity(100, 100));
        assertTrue(MediaCodecDecoderRenderer.consumesIntentionalDiscontinuity(100, 105));
        assertEquals(0, MediaCodecDecoderRenderer.countMissingFrames(100, 105, true));
    }

    @Test
    public void decoderQueueHistogramReportsTailLatencyWithoutPerFrameAllocation() {
        VideoStats stats = new VideoStats();
        for (int i = 0; i < 94; i++) {
            stats.recordDecoderQueueLatency(1, 0);
        }
        for (int i = 0; i < 6; i++) {
            stats.recordDecoderQueueLatency(20, 2);
        }

        assertEquals(20.0f, stats.getDecoderQueueP95Ms(), 0.0f);
        assertEquals(20, stats.maxDecoderQueueTimeMs);
        assertEquals(2, stats.maxPendingDecoderFrames);

        VideoStats copy = new VideoStats();
        copy.copy(stats);
        assertEquals(20.0f, copy.getDecoderQueueP95Ms(), 0.0f);
        copy.clear();
        assertTrue(Float.isNaN(copy.getDecoderQueueP95Ms()));
    }
}
