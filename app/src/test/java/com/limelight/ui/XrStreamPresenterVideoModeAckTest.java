package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        // no-op rather than a second application.
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.IGNORE_STALE,
                XrStreamPresenter.videoModeAckOutcome(-1, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_APPLIED));
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.IGNORE_STALE,
                XrStreamPresenter.videoModeAckOutcome(0, OUTSTANDING,
                        MoonBridge.VIDEO_MODE_ACK_APPLIED));
    }

    @Test
    public void resolutionAckFirstWaitsForMatchingDecoderOutput() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(true);

        assertFalse(gate.onAppliedAck());
        assertTrue(gate.hasAppliedAck());
        assertFalse(gate.canSettle());
        assertTrue(gate.beginPostAckDecoderConfirmation());
        assertFalse(gate.beginPostAckDecoderConfirmation());

        assertTrue(gate.onDecoderOutput(4096, 1728, 4096, 1728));
        assertTrue(gate.hasMatchingDecoderOutput());
        assertTrue(gate.canSettle());
    }

    @Test
    public void resolutionDecoderFirstIsInvalidatedAtTheAckBoundary() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(true);

        assertFalse(gate.onDecoderOutput(3840, 2160, 3840, 2160));
        assertTrue(gate.hasDecoderOutput());
        assertTrue(gate.hasMatchingDecoderOutput());
        assertFalse(gate.canSettle());

        assertFalse(gate.onAppliedAck());
        assertTrue(gate.beginPostAckDecoderConfirmation());
        assertFalse(gate.hasDecoderOutput());
        assertFalse(gate.hasMatchingDecoderOutput());
        assertFalse(gate.canSettle());

        assertTrue(gate.onDecoderOutput(3840, 2160, 3840, 2160));
        assertTrue(gate.canSettle());
    }

    @Test
    public void clientSbsWaitsForExactPackedPresentationAfterAckAndFreshIdr() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(true, true);

        assertFalse(gate.onAppliedAck());
        assertTrue(gate.beginPostAckDecoderConfirmation());
        assertFalse(gate.onDecoderOutput(1920, 1080, 1920, 1080));
        assertFalse(gate.canSettle());
        assertFalse(gate.isPresentationReady());

        assertTrue(gate.onPresentationReady());
        assertTrue(gate.canSettle());
    }

    @Test
    public void clientSbsRetainsEarlyPresentationReadyAndRearmsForHostClamp() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(true, true);

        assertFalse(gate.onPresentationReady());
        assertTrue(gate.isPresentationReady());
        gate.expectPresentationConfirmation();
        assertFalse(gate.isPresentationReady());

        assertFalse(gate.onAppliedAck());
        assertTrue(gate.beginPostAckDecoderConfirmation());
        assertFalse(gate.onDecoderOutput(1920, 1080, 1920, 1080));
        assertTrue(gate.onPresentationReady());
    }

    @Test
    public void staleFourKIdrBeforeFullHdAckWaitsForPostAckFullHdIdr() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(true);

        // Exact ordering from the field failure: the old 4K encoder satisfies the first decoder
        // transition while the client is requesting a 1080p mode.
        assertFalse(gate.onDecoderOutput(3840, 2160, 1920, 1080));
        assertFalse(XrStreamPresenter.decoderMismatchRequiresMandatoryResync(true, gate));

        assertFalse(gate.onAppliedAck());
        assertTrue(gate.beginPostAckDecoderConfirmation());
        assertFalse(gate.hasDecoderOutput());
        assertFalse(XrStreamPresenter.decoderMismatchRequiresMandatoryResync(true, gate));

        assertTrue(gate.onDecoderOutput(1920, 1080, 1920, 1080));
        assertTrue(gate.canSettle());
    }

    @Test
    public void clampedAckRequiresOutputAfterFinalGeometryIsAdopted() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(true);

        // The fresh IDR already carries the host's clamp, while the client still expects its
        // requested 5120x2160 until the authoritative ACK arrives.
        assertFalse(gate.onDecoderOutput(4096, 1728, 5120, 2160));
        assertTrue(gate.hasDecoderOutput());
        assertFalse(gate.hasMatchingDecoderOutput());
        assertFalse(gate.onAppliedAck());

        assertTrue(gate.beginPostAckDecoderConfirmation());
        assertFalse(gate.hasDecoderOutput());
        assertFalse(gate.canSettle());
        assertTrue(gate.onDecoderOutput(4096, 1728, 4096, 1728));
        assertTrue(gate.hasMatchingDecoderOutput());
    }

    @Test
    public void fastAppliedAckNeedsNoDecoderConfirmation() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(false);

        assertTrue(gate.onAppliedAck());
        assertFalse(gate.beginPostAckDecoderConfirmation());
        assertFalse(gate.hasDecoderOutput());
        assertTrue(gate.canSettle());
    }

    @Test
    public void postAckConfirmationIsUnavailableBeforeAckAndResetsPerRequest() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(true);

        assertFalse(gate.beginPostAckDecoderConfirmation());
        gate.onAppliedAck();
        assertTrue(gate.beginPostAckDecoderConfirmation());
        assertFalse(gate.beginPostAckDecoderConfirmation());

        gate.clear();
        gate.begin(true);
        gate.onAppliedAck();
        assertTrue(gate.beginPostAckDecoderConfirmation());
    }

    @Test
    public void decoderCallbackWithoutUsableDimensionsCannotSettleResolution() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(true);

        assertFalse(gate.onAppliedAck());
        assertTrue(gate.beginPostAckDecoderConfirmation());
        assertFalse(gate.onDecoderOutput(0, 0, 3840, 2160));
        assertTrue(gate.hasDecoderOutput());
        assertFalse(gate.hasMatchingDecoderOutput());
        assertFalse(gate.canSettle());
    }

    @Test
    public void authoritativeAckWithMismatchedFreshIdrRequiresHiddenResync() {
        XrStreamPresenter.LiveQualityConfirmationGate gate =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        gate.begin(true);
        gate.onAppliedAck();
        gate.beginPostAckDecoderConfirmation();
        gate.onDecoderOutput(1920, 1080, 3840, 2160);

        assertTrue(XrStreamPresenter.decoderMismatchRequiresMandatoryResync(
                true, gate));
        assertFalse(XrStreamPresenter.shouldRevealSurfaceDuringMandatoryResync(
                XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION,
                gate.hasMatchingDecoderOutput(), false));

        XrStreamPresenter.LiveQualityConfirmationGate matching =
                new XrStreamPresenter.LiveQualityConfirmationGate();
        matching.begin(true);
        matching.onAppliedAck();
        matching.beginPostAckDecoderConfirmation();
        matching.onDecoderOutput(3840, 2160, 3840, 2160);
        assertFalse(XrStreamPresenter.decoderMismatchRequiresMandatoryResync(
                true, matching));
    }

    @Test
    public void everyOutstandingLiveQualityPathBlocksAnotherTransaction() {
        assertTrue(XrStreamPresenter.liveQualityTransactionBusy(OUTSTANDING, false));
        // After a resolution ACK consumes its request id, the decoder half still owns the guard.
        assertTrue(XrStreamPresenter.liveQualityTransactionBusy(-1, true));
        assertFalse(XrStreamPresenter.liveQualityTransactionBusy(-1, false));
    }

    @Test
    public void everyMissingApplicationAckFailsClosedToReconnect() {
        assertEquals(XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_FAST_USER,
                XrStreamPresenter.liveQualityAckTimeoutDisposition(
                        false, XrStreamPresenter.LiveQualityRequestOrigin.USER));
        assertEquals(
                XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_FAST_PANEL_FOLLOW,
                XrStreamPresenter.liveQualityAckTimeoutDisposition(
                        false, XrStreamPresenter.LiveQualityRequestOrigin.PANEL_FOLLOW));
        assertEquals(XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION,
                XrStreamPresenter.liveQualityAckTimeoutDisposition(
                        true, XrStreamPresenter.LiveQualityRequestOrigin.USER));
        assertEquals(XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION,
                XrStreamPresenter.liveQualityAckTimeoutDisposition(
                        true, XrStreamPresenter.LiveQualityRequestOrigin.PANEL_FOLLOW));
        assertTrue(XrStreamPresenter.shouldRevealSurfaceAfterAckTimeout(
                XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_FAST_USER, false));
        assertTrue(XrStreamPresenter.shouldRevealSurfaceAfterAckTimeout(
                XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_FAST_PANEL_FOLLOW,
                false));
        assertFalse(XrStreamPresenter.shouldRevealSurfaceAfterAckTimeout(
                XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION, false));
        assertTrue(XrStreamPresenter.shouldRevealSurfaceAfterAckTimeout(
                XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION, true));
        assertFalse(XrStreamPresenter.shouldRevealSurfaceDuringMandatoryResync(
                XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION,
                true, false));
        assertTrue(XrStreamPresenter.shouldRevealSurfaceDuringMandatoryResync(
                XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION,
                true, true));
    }

    @Test
    public void missingPanelAckDoesNotConsumeRetryOrBlockPostReconnectFollow() {
        XrStreamPresenter.PanelRefreshRateState state =
                new XrStreamPresenter.PanelRefreshRateState(90);
        state.observe(72);

        assertEquals(72, state.nextTarget(90, false));
        assertEquals(
                XrStreamPresenter.LiveQualityAckTimeoutDisposition.RECONNECT_FAST_PANEL_FOLLOW,
                XrStreamPresenter.liveQualityAckTimeoutDisposition(
                        false, XrStreamPresenter.LiveQualityRequestOrigin.PANEL_FOLLOW));
        state.requestAbandonedForReconnect();
        assertEquals(72, state.nextTarget(90, false));
    }

    @Test
    public void anUnknownStatusRequiresMandatoryResynchronization() {
        // Only the defined FAILED status proves rollback. A future status must not publish the
        // previous tuple or consume panel-follow's retry budget.
        assertEquals(XrStreamPresenter.VideoModeAckOutcome.AMBIGUOUS_RESYNC,
                XrStreamPresenter.videoModeAckOutcome(OUTSTANDING, OUTSTANDING, 99));
    }

    @Test
    public void malformedAppliedAndUnknownStatusNeverClaimARollbackTuple() {
        XrStreamPresenter.AcknowledgedVideoMode valid =
                XrStreamPresenter.acknowledgedVideoMode(
                        new StreamQualityTuple("1920x1080", "90", 100000),
                        1920, 1080, 9000, 90000);

        assertFalse(XrStreamPresenter.videoModeAckRequiresMandatoryResync(
                XrStreamPresenter.VideoModeAckOutcome.ADOPT_APPLIED, valid));
        assertTrue(XrStreamPresenter.videoModeAckRequiresMandatoryResync(
                XrStreamPresenter.VideoModeAckOutcome.ADOPT_APPLIED, null));
        assertTrue(XrStreamPresenter.videoModeAckRequiresMandatoryResync(
                XrStreamPresenter.VideoModeAckOutcome.AMBIGUOUS_RESYNC, valid));
        assertFalse(XrStreamPresenter.videoModeAckRequiresMandatoryResync(
                XrStreamPresenter.VideoModeAckOutcome.FAILED_RETRYABLE, null));
    }

    @Test
    public void acknowledgedGeometryMustBeAdoptedBeforeTheAckCanSettle() {
        // FPS/bitrate-only ACKs need no Surface resize and retain their fast path.
        assertTrue(XrStreamPresenter.acknowledgedGeometryAdoptionSucceeded(
                false, false));
        assertTrue(XrStreamPresenter.acknowledgedGeometryAdoptionSucceeded(
                true, true));
        // A host clamp that the client Surface cannot adopt is terminal, not local success.
        assertFalse(XrStreamPresenter.acknowledgedGeometryAdoptionSucceeded(
                true, false));
    }

    @Test
    public void clientResizeFailureAfterReliableSendCannotClaimHostRollback() {
        assertTrue(XrStreamPresenter.postSendGeometryFailureRequiresMandatoryResync(1));
        assertFalse(XrStreamPresenter.postSendGeometryFailureRequiresMandatoryResync(0));
        assertFalse(XrStreamPresenter.postSendGeometryFailureRequiresMandatoryResync(-1));
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
        // The raw ACK tuple carries the host's post-budget encoder value.
        assertEquals(118000, applied.bitrateKbps);
    }

    @Test
    public void repeatedAcksKeepTheRequestedWireBudgetSeparateFromEffectiveEncoderBitrate() {
        StreamQualityTuple firstRequest =
                new StreamQualityTuple("1920x1080", "29.97", 130000);

        XrStreamPresenter.AcknowledgedVideoMode firstAck =
                XrStreamPresenter.acknowledgedVideoMode(
                        firstRequest, 1920, 1080, 2997, 118000);

        assertEquals(new StreamQualityTuple("1920x1080", "29.97", 130000),
                firstAck.requestedWireQuality);
        assertEquals(118000, firstAck.effectiveEncoderBitrateKbps);

        // A later FPS/resolution change starts from the reconciled requested tuple. Apollo may
        // deduct audio/FEC again, but that effective value must never become the next wire budget.
        StreamQualityTuple secondRequest = new StreamQualityTuple(
                "3840x2160", "23.976", firstAck.requestedWireQuality.bitrateKbps);
        XrStreamPresenter.AcknowledgedVideoMode secondAck =
                XrStreamPresenter.acknowledgedVideoMode(
                        secondRequest, 3840, 2160, 2398, 105000);

        assertEquals("3840x2160", secondAck.requestedWireQuality.resolution);
        assertEquals("23.98", secondAck.requestedWireQuality.frameRate);
        assertEquals(130000, secondAck.requestedWireQuality.bitrateKbps);
        assertEquals(105000, secondAck.effectiveEncoderBitrateKbps);
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
    public void appliedFrameRateRetainsAckHundredthsOfAHz() {
        assertEquals("29.97",
                XrStreamPresenter.appliedTuple(1920, 1080, 2997, 20000).frameRate);
        assertEquals("23.98",
                XrStreamPresenter.appliedTuple(1920, 1080, 2398, 20000).frameRate);
        assertEquals("59.94",
                XrStreamPresenter.appliedTuple(1920, 1080, 5994, 20000).frameRate);
        assertEquals("60",
                XrStreamPresenter.appliedTuple(1920, 1080, 6000, 20000).frameRate);
    }

    @Test
    public void frameRateSurvivesTheHundredthsOfAHzRoundTrip() {
        assertEquals(6000, XrStreamPresenter.frameRateX100("60", 30));
        assertEquals(2997, XrStreamPresenter.frameRateX100("29.97", 30));
        assertEquals(2398, XrStreamPresenter.frameRateX100("23.976", 30));
        // Unparseable values fall back to the live frame rate rather than sending zero.
        assertEquals(9000, XrStreamPresenter.frameRateX100("bogus", 90));
    }

    @Test
    public void panelFollowSnapsOntoTheOfferedFrameRateLadder() {
        // The headset's own panel modes land exactly on the ladder.
        assertEquals(90, XrStreamPresenter.snapToOfferedFrameRate(90));
        assertEquals(72, XrStreamPresenter.snapToOfferedFrameRate(72));
        assertEquals(60, XrStreamPresenter.snapToOfferedFrameRate(60));

        // A system frame-rate override is not restricted to those, so anything between rungs is
        // snapped DOWN: requesting a rate the host's virtual display has no mode for would fail.
        assertEquals(72, XrStreamPresenter.snapToOfferedFrameRate(89));
        assertEquals(60, XrStreamPresenter.snapToOfferedFrameRate(71));
        assertEquals(30, XrStreamPresenter.snapToOfferedFrameRate(45));

        // Below the slowest offered rate the floor holds rather than chasing the override down.
        assertEquals(30, XrStreamPresenter.snapToOfferedFrameRate(24));
        assertEquals(30, XrStreamPresenter.snapToOfferedFrameRate(1));
        assertEquals(30, XrStreamPresenter.snapToOfferedFrameRate(0));
    }

    @Test
    public void panelFollowCoalescesTheNewestObservationWhileBusy() {
        XrStreamPresenter.PanelRefreshRateState state =
                new XrStreamPresenter.PanelRefreshRateState(90);

        state.observe(72);
        assertEquals(-1, state.nextTarget(90, true));
        assertTrue(state.isReconcilePending());

        // The intermediate 72 Hz event is superseded rather than marked handled.
        state.observe(60);
        assertEquals(60, state.nextTarget(90, false));
        assertEquals(60, state.getInFlightTargetHz());
    }

    @Test
    public void panelFollowReturnsUpwardToTheDurableNinetyFpsCeiling() {
        XrStreamPresenter.PanelRefreshRateState state =
                new XrStreamPresenter.PanelRefreshRateState(90);

        state.observe(72);
        assertEquals(72, state.nextTarget(90, false));
        state.automaticRequestSucceeded(72);
        assertEquals(90, state.getUserCeilingHz());

        state.observe(90);
        assertEquals(90, state.nextTarget(72, false));
    }

    @Test
    public void sceneCoreSurfaceVoteSurvivesTemporaryPanelThrottling() {
        XrStreamPresenter.PanelRefreshRateState state =
                new XrStreamPresenter.PanelRefreshRateState(90);

        state.observe(72);
        assertEquals(72, state.nextTarget(90, false));
        state.automaticRequestSucceeded(72);

        // A recreated SceneCore surface still votes the durable ceiling, not effective 72.
        assertEquals(90, XrStreamPresenter.durableSurfaceFrameRateVoteHz(state));
    }

    @Test
    public void explicitUserCeilingImmediatelyChangesTheSceneCoreSurfaceVote() {
        XrStreamPresenter.PanelRefreshRateState state =
                new XrStreamPresenter.PanelRefreshRateState(90);

        state.userRequestSucceeded(60);

        assertEquals(60, XrStreamPresenter.durableSurfaceFrameRateVoteHz(state));
    }

    @Test
    public void surfaceFrameRateFeedbackDoesNotRelockTheFollower() {
        XrStreamPresenter.PanelRefreshRateState state =
                new XrStreamPresenter.PanelRefreshRateState(90);

        state.observe(72);
        assertEquals(72, state.nextTarget(90, false));
        // Surface/display feedback for the same effective panel mode is a duplicate.
        state.observe(72);
        state.automaticRequestSucceeded(72);

        assertEquals(-1, state.nextTarget(72, false));
        assertEquals(-1, state.getInFlightTargetHz());
        assertFalse(state.isReconcilePending());
    }

    @Test
    public void automaticFailureRetriesOnceThenWaitsForNewEvidence() {
        XrStreamPresenter.PanelRefreshRateState state =
                new XrStreamPresenter.PanelRefreshRateState(90);

        state.observe(72);
        assertEquals(72, state.nextTarget(90, false));
        state.automaticRequestFailed(true);
        assertEquals(72, state.nextTarget(90, false));
        state.automaticRequestFailed(true);
        assertEquals(-1, state.nextTarget(90, false));

        // A genuinely new panel mode resets the retry block.
        state.observe(60);
        assertEquals(60, state.nextTarget(90, false));
    }

    @Test
    public void userCeilingChangesOnlyAfterSuccessfulSettlement() {
        XrStreamPresenter.PanelRefreshRateState state =
                new XrStreamPresenter.PanelRefreshRateState(90);

        // Merely considering/queuing a 60 FPS user request has no state-machine mutation.
        assertEquals(90, state.getUserCeilingHz());
        state.otherTransactionSettled();
        assertEquals(90, state.getUserCeilingHz());

        state.userRequestSucceeded(60);
        assertEquals(60, state.getUserCeilingHz());
    }

    @Test
    public void panelCappedApplyPersistsCeilingButNotTheThrottledRate() {
        StreamQualityTuple durable =
                new StreamQualityTuple("3840x2160", "90", 100000);
        StreamQualityTuple applied =
                new StreamQualityTuple("4096x1728", "72", 100000);

        StreamQualityTuple persisted = XrStreamPresenter.durableUserQuality(
                applied, durable);
        assertEquals("4096x1728", persisted.resolution);
        assertEquals("90", persisted.frameRate);
        assertEquals(100000, persisted.bitrateKbps);
    }

    @Test
    public void hostFpsClampUnderPanelRequestChangesOnlyEffectiveRate() {
        StreamQualityTuple durable =
                new StreamQualityTuple("3840x2160", "90", 100000);
        StreamQualityTuple hostApplied =
                new StreamQualityTuple("4096x1728", "60", 100000);

        StreamQualityTuple persisted = XrStreamPresenter.durableUserQuality(
                hostApplied, durable);
        // Geometry and requested wire bitrate adopt the ACK. Its 60 FPS is effective-only because
        // both the 72 request and the host's further clamp are below the explicit 90 FPS ceiling.
        assertEquals("4096x1728", persisted.resolution);
        assertEquals("90", persisted.frameRate);
        assertEquals(100000, persisted.bitrateKbps);
    }

    @Test
    public void automaticPanelFollowNeverEntersTheDurableSettingsPath() {
        assertTrue(XrStreamPresenter.shouldPersistLiveQualityRequest(
                XrStreamPresenter.LiveQualityRequestOrigin.USER));
        assertFalse(XrStreamPresenter.shouldPersistLiveQualityRequest(
                XrStreamPresenter.LiveQualityRequestOrigin.PANEL_FOLLOW));
        assertTrue(XrStreamPresenter.shouldCommitStagedSettingsForResync(
                XrStreamPresenter.LiveQualityRequestOrigin.USER));
        assertFalse(XrStreamPresenter.shouldCommitStagedSettingsForResync(
                XrStreamPresenter.LiveQualityRequestOrigin.PANEL_FOLLOW));
    }

    @Test
    public void directNinetyRequestClampKeepsTheExplicitCeiling() {
        StreamQualityTuple durable =
                new StreamQualityTuple("3840x2160", "90", 100000);
        StreamQualityTuple applied =
                new StreamQualityTuple("3840x2160", "60", 100000);

        assertEquals("90", XrStreamPresenter.durableUserQuality(
                applied, durable).frameRate);
    }

    @Test
    public void directLowerCeilingClampAlsoKeepsTheExplicitCeiling() {
        StreamQualityTuple durable =
                new StreamQualityTuple("3840x2160", "60", 100000);
        StreamQualityTuple applied =
                new StreamQualityTuple("3840x2160", "30", 100000);

        assertEquals("60", XrStreamPresenter.durableUserQuality(
                applied, durable).frameRate);
    }
}
