package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.Test;

/**
 * Correlation and status handling for the 0x3008 video-mode ack.
 *
 * <p>Correlation is strictly by the opaque {@code request_id} the client generated, because the
 * host may legitimately apply values that differ from the request (it clamps an oversized width to
 * the codec ceiling), so the echoed geometry cannot be used to match the ack to its request.</p>
 */
public class XrStreamPresenterVideoModeAckTest {
    private static final int OUTSTANDING = 7;

    @Test
    public void appliedAckForTheOutstandingRequestAdoptsTheAppliedValues() {
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.ADOPT_APPLIED,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_APPLIED));
    }

    @Test
    public void needsReconnectStopsWaitingAndReconnects() {
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.NEEDS_RECONNECT,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT));
    }

    @Test
    public void rejectedInvalidRevertsWithoutRetry() {
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.REJECTED_NO_RETRY,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_REJECTED_INVALID));
    }

    @Test
    public void transientFailureRevertsButPermitsRetry() {
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.FAILED_RETRYABLE,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_FAILED));
    }

    @Test
    public void anAckForAnotherRequestIdIsStale() {
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.IGNORE_STALE,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING - 1,
                        MoonBridge.VIDEO_MODE_ACK_APPLIED));
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.IGNORE_STALE,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING + 1,
                        MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT));
    }

    @Test
    public void anAckArrivingAfterTheRequestSettledIsStale() {
        // A settled or timed-out request clears the outstanding id, so a late duplicate ack is a
        // no-op rather than a second application. This is what makes ack/SPS order-independent.
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.IGNORE_STALE,
                XrStreamPresenter.videoModeAckOutcome(-1, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_APPLIED));
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.IGNORE_STALE,
                XrStreamPresenter.videoModeAckOutcome(0, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_APPLIED));
    }

    @Test
    public void anUnknownStatusIsTreatedAsATransientFailure() {
        // Never silently adopt a mode the host may not be running.
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.FAILED_RETRYABLE,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING, 99));
    }

    @Test
    public void aClampedApplyIsAdoptedRatherThanReverted() {
        // The host clamps an oversized width to the codec ceiling and scales height to preserve
        // aspect. That is still status=applied, so the outcome must adopt rather than fail.
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.ADOPT_APPLIED,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_APPLIED));

        // Requested 5120x2160; the host packs 10240 wide, clamps to 8192 and reports 4096x1728.
        StreamQualityTuple applied = XrStreamPresenter.appliedTuple(4096, 1728, 6000, 118000);
        assertEquals("4096x1728", applied.resolution);
        assertEquals("60", applied.frameRate);
        // The applied bitrate is the host's post-budget encoder value, surfaced as effective.
        assertEquals(118000, applied.bitrateKbps);
    }

    @Test
    public void fiveKTwoKHostSbsAiIsAdoptedAtTheClampedSize() {
        // 5120x2160 in Host SBS AI packs to 10240 wide, over the 8192 codec ceiling, so the host
        // clamps the packed width and scales height to preserve aspect: per-eye lands near
        // 4096x1728. That is status=applied, and the UI must show the clamped values rather than
        // the request it did not get.
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.ADOPT_APPLIED,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_APPLIED));

        StreamQualityTuple applied = XrStreamPresenter.appliedTuple(4096, 1728, 9000, 130000);
        assertEquals("4096x1728", applied.resolution);
        assertEquals("90", applied.frameRate);
        assertEquals(130000, applied.bitrateKbps);
        // The clamped per-eye size keeps the requested 21:9 aspect within rounding.
        assertEquals(5120.0 / 2160.0, 4096.0 / 1728.0, 0.001);
    }

    @Test
    public void anUnusableAppliedTupleIsNull() {
        assertNull(XrStreamPresenter.appliedTuple(0, 1080, 6000, 20000));
        assertNull(XrStreamPresenter.appliedTuple(1920, 0, 6000, 20000));
        assertNull(XrStreamPresenter.appliedTuple(1920, 1080, 0, 20000));
        assertNull(XrStreamPresenter.appliedTuple(1920, 1080, 6000, 0));
    }

    @Test
    public void appliedFrameRateRoundsBackFromHundredthsOfAHz() {
        assertEquals("30", XrStreamPresenter.appliedTuple(1920, 1080, 2997, 20000).frameRate);
        assertEquals("24", XrStreamPresenter.appliedTuple(1920, 1080, 2398, 20000).frameRate);
    }

    @Test
    public void frameRateSurvivesTheHundredthsOfAHzRoundTrip() {
        assertEquals(6000, XrStreamPresenter.frameRateX100("60", 30));
        assertEquals(2997, XrStreamPresenter.frameRateX100("29.97", 30));
        assertEquals(2398, XrStreamPresenter.frameRateX100("23.976", 30));
        // Unparseable values fall back to the live frame rate rather than sending zero.
        assertEquals(9000, XrStreamPresenter.frameRateX100("bogus", 90));
    }
}
