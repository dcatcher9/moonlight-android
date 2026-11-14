package com.limelight.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class XrStreamPresenterTransitionTest {
    @Test
    public void onlyHostAiIsCostedAsAPackedEncodedFrame() {
        for (PresentationMode mode : PresentationMode.values()) {
            assertEquals(mode.name(), mode == PresentationMode.HOST_SBS_AI,
                    XrPresentationPolicy.usesPackedBitrateCost(mode));
        }
    }

    @Test
    public void clientSbsLiveResizePacksTwoNewFullHdEyesInsteadOfOldFourKEyes() {
        assertArrayEquals(new int[] {3840, 1080},
                XrPresentationPolicy.clientSbsPackedDimensions(1920, 1080));
        assertNull(XrPresentationPolicy.clientSbsPackedDimensions(
                Integer.MAX_VALUE, 1080));
    }

    @Test
    public void crossingHostAiBoundaryRequiresHostSurfaceResize() {
        assertTrue(XrPresentationPolicy.requiresHostSurfaceResize(
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_AI));
        assertTrue(XrPresentationPolicy.requiresHostSurfaceResize(
                PresentationMode.HOST_SBS_RAW,
                PresentationMode.HOST_SBS_AI));
        assertTrue(XrPresentationPolicy.requiresHostSurfaceResize(
                PresentationMode.HOST_SBS_AI,
                PresentationMode.NORMAL));
        assertTrue(XrPresentationPolicy.requiresHostSurfaceResize(
                PresentationMode.HOST_SBS_AI,
                PresentationMode.HOST_SBS_RAW));
    }

    @Test
    public void unchangedDirectModeDoesNotResizeHostSurface() {
        for (PresentationMode mode : new PresentationMode[] {
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_RAW,
                PresentationMode.HOST_SBS_AI}) {
            assertFalse(XrPresentationPolicy.requiresHostSurfaceResize(mode, mode));
        }
    }

    @Test
    public void onlySurfaceOrDimensionChangesRequireDecoderTransition() {
        assertFalse(XrPresentationPolicy.requiresDecoderTransition(
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_RAW));
        assertFalse(XrPresentationPolicy.requiresDecoderTransition(
                PresentationMode.HOST_SBS_RAW,
                PresentationMode.NORMAL));

        assertTrue(XrPresentationPolicy.requiresDecoderTransition(
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_AI));
        assertTrue(XrPresentationPolicy.requiresDecoderTransition(
                PresentationMode.HOST_SBS_AI,
                PresentationMode.HOST_SBS_RAW));
        assertTrue(XrPresentationPolicy.requiresDecoderTransition(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI));
        assertTrue(XrPresentationPolicy.requiresDecoderTransition(
                PresentationMode.CLIENT_SBS_AI,
                PresentationMode.HOST_SBS_RAW));
    }

    @Test
    public void onlyClientRendererCrossingsRetainTheOldPictureUntilFreshTargetFrame() {
        assertTrue(XrPresentationPolicy.retainsOldPictureUntilFreshTargetFrame(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI));
        assertTrue(XrPresentationPolicy.retainsOldPictureUntilFreshTargetFrame(
                PresentationMode.CLIENT_SBS_AI,
                PresentationMode.HOST_SBS_AI));
        assertFalse(XrPresentationPolicy.retainsOldPictureUntilFreshTargetFrame(
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_AI));
        assertFalse(XrPresentationPolicy.retainsOldPictureUntilFreshTargetFrame(
                PresentationMode.HOST_SBS_RAW,
                PresentationMode.NORMAL));
    }

    @Test
    public void clientRendererRemainsActiveUntilSurfaceSwitchOutcome() {
        // Enter pre-stages the renderer before EGL resumes; exit retains the previous packed frame
        // until MediaCodec has parked and the asynchronous detach completes.
        assertTrue(XrPresentationPolicy.clientSbsActiveDuringSurfaceSwitch(false, true));
        assertTrue(XrPresentationPolicy.clientSbsActiveDuringSurfaceSwitch(true, false));

    }

    @Test
    public void onlyClientEntryWaitsForAPackedEglSwap() {
        assertTrue(XrPresentationPolicy.requiresClientPackedSwapProof(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI));
        assertTrue(XrPresentationPolicy.requiresClientPackedSwapProof(
                PresentationMode.HOST_SBS_AI,
                PresentationMode.CLIENT_SBS_AI));
        assertFalse(XrPresentationPolicy.requiresClientPackedSwapProof(
                PresentationMode.CLIENT_SBS_AI,
                PresentationMode.NORMAL));
        assertFalse(XrPresentationPolicy.requiresClientPackedSwapProof(
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_AI));
    }

    @Test
    public void inactiveClientSavedQualityFusesIntoOneAckFirstModeSwitch() {
        ModeStreamQualityModel liveTarget = targetQuality(false);

        assertTrue(XrPresentationPolicy.shouldFuseClientModeEntryQuality(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI,
                true, false, liveTarget));
        assertFalse(XrPresentationPolicy.shouldReconnectBeforeModeEntry(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI,
                false, liveTarget));

        // No fusion for a same-mode update, a standard host, or unrelated staged reconnect work.
        assertFalse(XrPresentationPolicy.shouldFuseClientModeEntryQuality(
                PresentationMode.CLIENT_SBS_AI,
                PresentationMode.CLIENT_SBS_AI,
                true, false, liveTarget));
        assertFalse(XrPresentationPolicy.shouldFuseClientModeEntryQuality(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI,
                false, false, liveTarget));
        assertFalse(XrPresentationPolicy.shouldFuseClientModeEntryQuality(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI,
                true, true, liveTarget));
    }

    @Test
    public void incompatibleOrLegacyClientSavedQualityReconnectsBeforeHandoff() {
        ModeStreamQualityModel reconnectTarget = targetQuality(true);

        assertTrue(XrPresentationPolicy.shouldReconnectBeforeModeEntry(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI,
                false, reconnectTarget));
        assertFalse(XrPresentationPolicy.shouldFuseClientModeEntryQuality(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI,
                true, false, reconnectTarget));
    }

    @Test
    public void everyModeReconnectsBeforeIncompatibleSavedQualityCanBeOverwritten() {
        ModeStreamQualityModel reconnectTarget = targetQuality(true);
        for (PresentationMode previous : PresentationMode.values()) {
            for (PresentationMode next : PresentationMode.values()) {
                assertEquals(previous + " -> " + next, previous != next,
                        XrPresentationPolicy.shouldReconnectBeforeModeEntry(
                                previous, next, false, reconnectTarget));
            }
        }
    }

    @Test
    public void liveTargetWithOtherStagedReconnectWorkDoesNotStartInterimAck() {
        ModeStreamQualityModel liveTarget = targetQuality(false);
        assertTrue(XrPresentationPolicy.shouldReconnectBeforeModeEntry(
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_AI, true, liveTarget));
        assertTrue(XrPresentationPolicy.shouldReconnectBeforeModeEntry(
                PresentationMode.HOST_SBS_AI,
                PresentationMode.NORMAL, true, liveTarget));
        assertFalse(XrPresentationPolicy.shouldReconnectBeforeModeEntry(
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_AI, false, liveTarget));
    }

    @Test
    public void restoredHostAiRefreshesSurfaceWhenActualCodecChangesPackedGeometry() {
        assertTrue(XrPresentationPolicy.hostSbsFormatChangeRequiresResize(
                PresentationMode.HOST_SBS_AI,
                5120, 1440,
                MoonBridge.VIDEO_FORMAT_H265,
                MoonBridge.VIDEO_FORMAT_H264));
        assertFalse(XrPresentationPolicy.hostSbsFormatChangeRequiresResize(
                PresentationMode.HOST_SBS_AI,
                1920, 1080,
                MoonBridge.VIDEO_FORMAT_H265,
                MoonBridge.VIDEO_FORMAT_H264));
    }

    @Test
    public void inactiveHostAiAndEquivalentCodecGeometryDoNotResizeAtStartup() {
        assertFalse(XrPresentationPolicy.hostSbsFormatChangeRequiresResize(
                PresentationMode.NORMAL,
                5120, 1440,
                MoonBridge.VIDEO_FORMAT_H265,
                MoonBridge.VIDEO_FORMAT_H264));
        assertFalse(XrPresentationPolicy.hostSbsFormatChangeRequiresResize(
                PresentationMode.HOST_SBS_AI,
                5120, 1440,
                MoonBridge.VIDEO_FORMAT_H265,
                MoonBridge.VIDEO_FORMAT_AV1_MAIN8));
    }

    @Test
    public void cinemaPresetHeightIsNotTheDurableHeight() {
        assertEquals(1.2f, XrStreamPresenter.durableHeightMeters(true, 1.2f, 2.0f), 0f);
        assertEquals(2.0f, XrStreamPresenter.durableHeightMeters(false, 1.2f, 2.0f), 0f);
        assertEquals(2.0f, XrStreamPresenter.durableHeightMeters(true, 0f, 2.0f), 0f);
    }

    @Test
    public void onlyAnActiveClientSbsStreamStartsAStandaloneHdrBoundary() {
        assertTrue(XrPresentationPolicy.canSynchronizeClientSbsHdrTransition(
                PresentationMode.CLIENT_SBS_AI,
                true, false, false, false));
        assertFalse(XrPresentationPolicy.canSynchronizeClientSbsHdrTransition(
                PresentationMode.NORMAL,
                true, false, false, false));
        assertFalse(XrPresentationPolicy.canSynchronizeClientSbsHdrTransition(
                PresentationMode.CLIENT_SBS_AI,
                false, false, false, false));
    }

    @Test
    public void hdrBoundaryCanBeSupersededButCannotOverlapAModeSwitch() {
        assertTrue(XrPresentationPolicy.canSynchronizeClientSbsHdrTransition(
                PresentationMode.CLIENT_SBS_AI,
                true, true, false, true));
        assertFalse(XrPresentationPolicy.canSynchronizeClientSbsHdrTransition(
                PresentationMode.CLIENT_SBS_AI,
                true, true, false, false));
    }

    @Test
    public void hdrBoundaryCannotOverlapALiveQualityOrPendingDecoderTransaction() {
        assertFalse(XrPresentationPolicy.canSynchronizeClientSbsHdrTransition(
                PresentationMode.CLIENT_SBS_AI,
                true, false, true, false));
        assertTrue(XrPresentationPolicy.canSynchronizeClientSbsHdrTransition(
                PresentationMode.CLIENT_SBS_AI,
                true, true, true, true));
        assertFalse(XrPresentationPolicy.canSynchronizeClientSbsHdrTransition(
                PresentationMode.NORMAL,
                true, false, true, true));
    }

    @Test
    public void staleHdrCompletionCannotCommitSupersedingTransition() {
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                new XrStreamPresenter.DecoderTransitionGenerationGate();
        AtomicInteger commits = new AtomicInteger();

        assertTrue(gate.beginHdr(41));
        assertTrue(gate.beginHdr(42));
        assertFalse(gate.dispatchHdrIfCurrent(41, commits::incrementAndGet));
        assertEquals(0, commits.get());

        assertTrue(gate.dispatchHdrIfCurrent(42, () -> {
            commits.incrementAndGet();
            gate.clearHdr();
        }));
        assertEquals(1, commits.get());
        assertFalse(gate.dispatchHdrIfCurrent(42, commits::incrementAndGet));
        assertEquals(1, commits.get());
    }

    @Test
    public void staleTimeoutCannotTerminateSupersedingTransition() {
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                new XrStreamPresenter.DecoderTransitionGenerationGate();
        AtomicInteger terminations = new AtomicInteger();

        assertTrue(gate.beginHdr(7));
        assertTrue(gate.beginHdr(8));
        assertFalse(gate.dispatchAnyIfCurrent(7, terminations::incrementAndGet));
        assertEquals(0, terminations.get());
        assertTrue(gate.dispatchAnyIfCurrent(8, terminations::incrementAndGet));
        assertEquals(1, terminations.get());
    }

    @Test
    public void solePostAckModeGenerationRejectsOlderCallbacks() {
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                new XrStreamPresenter.DecoderTransitionGenerationGate();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();

        // ACK-first flow creates no speculative generation 319. The one generation created after
        // ACK owns both decoder output and the Client packed-swap proof.
        assertTrue(gate.beginMode(320));
        assertFalse(gate.dispatchModeIfCurrent(319, completions::incrementAndGet));
        assertFalse(gate.dispatchAnyIfCurrent(319, terminations::incrementAndGet));
        assertEquals(0, completions.get());
        assertEquals(0, terminations.get());

        assertTrue(gate.dispatchModeIfCurrent(320, completions::incrementAndGet));
        assertEquals(1, completions.get());
    }

    @Test
    public void ackFirstClientGatePrecedesGeometryAndIsReusedBySurfaceHandoff() {
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                new XrStreamPresenter.DecoderTransitionGenerationGate();
        AtomicInteger decoderBegins = new AtomicInteger();
        List<String> order = new ArrayList<>();

        int ackGeneration =
                XrStreamPresenter.beginAckFirstClientDecoderTransitionBeforeGeometry(
                        gate,
                        () -> {
                            decoderBegins.incrementAndGet();
                            order.add("decoder-gate");
                            return 320;
                        },
                        () -> {
                            order.add("geometry");
                            return true;
                        });
        int surfaceGeneration = XrStreamPresenter.reuseOrBeginModeDecoderTransition(
                gate, true,
                () -> {
                    decoderBegins.incrementAndGet();
                    order.add("second-decoder-gate");
                    return 321;
                });

        assertEquals(Arrays.asList("decoder-gate", "geometry"), order);
        assertEquals(1, decoderBegins.get());
        assertEquals(320, ackGeneration);
        assertEquals(ackGeneration, surfaceGeneration);
    }

    @Test
    public void failedAckFirstGeometryStillLeavesGateOwnedForReconnectCleanup() {
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                new XrStreamPresenter.DecoderTransitionGenerationGate();

        assertEquals(0, XrStreamPresenter.beginAckFirstClientDecoderTransitionBeforeGeometry(
                gate, () -> 77, () -> false));
        assertEquals(77, gate.currentModeGeneration());
    }

    private static ModeStreamQualityModel targetQuality(boolean reconnectRequired) {
        StreamQualityTuple live = new StreamQualityTuple("3840x2160", "90", 200000);
        StreamQualityTuple target = new StreamQualityTuple("1920x1080", "30", 100000);
        SessionSettingsModel.Value value = new SessionSettingsModel.Value(
                "live", "target", SessionSettingsModel.Source.CURRENT_SESSION, true);
        return ModeStreamQualityModel.builder(target, target, live, false)
                .setQualityDeltaRequiresReconnect(reconnectRequired)
                .put(SessionSettingsModel.Key.RESOLUTION, value)
                .put(SessionSettingsModel.Key.FRAME_RATE, value)
                .put(SessionSettingsModel.Key.BITRATE, value)
                .build();
    }
}
