package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaCodecDecoderRendererTelemetryTest {
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
}
