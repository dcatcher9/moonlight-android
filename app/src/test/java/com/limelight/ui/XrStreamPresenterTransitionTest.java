package com.limelight.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.RawSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class XrStreamPresenterTransitionTest {
    private static final PreferenceConfiguration.RawSbsPerEyeResolution FULL =
            PreferenceConfiguration.RawSbsPerEyeResolution.FULL;
    private static final PreferenceConfiguration.RawSbsPerEyeResolution HALF =
            PreferenceConfiguration.RawSbsPerEyeResolution.HALF;

    @Test
    public void rawFullBoundaryReconnectsBeforeAnyLiveSurfaceSwitch() {
        // Raw Full negotiates 2W x H, which no other mode uses.
        assertTrue(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, FULL));
        assertTrue(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.NORMAL, FULL));
        assertTrue(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI, FULL));
        assertTrue(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, FULL));
        assertFalse(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_AI, FULL));
    }

    @Test
    public void rawHalfCrossesNoTransportBoundaryAtAll() {
        // Raw Half is W x H, byte-for-byte the stream Normal negotiates, sent with sbs_mode 0.
        // Entering or leaving it renegotiates nothing, so it switches live.
        assertFalse(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, HALF));
        assertFalse(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.NORMAL, HALF));
        assertFalse(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, HALF));
        assertFalse(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI, HALF));
    }

    @Test
    public void onlyRawFullOwnsItsOwnTransport() {
        assertTrue(XrStreamPresenter.usesRawPackedTransport(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, FULL));
        assertFalse(XrStreamPresenter.usesRawPackedTransport(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, HALF));
        assertFalse(XrStreamPresenter.usesRawPackedTransport(
                XrStreamPresenter.PresenterMode.NORMAL, FULL));
    }

    @Test
    public void bitrateCostTracksTheEncodedWidthRatherThanTheRawModeName() {
        assertTrue(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI, HALF));
        assertTrue(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, FULL));
        assertFalse(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, HALF));
        assertFalse(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI, FULL));
        assertFalse(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.NORMAL, FULL));
    }

    @Test
    public void bitrateCostUsesTheStagedRawChoiceBeforeApply() {
        RawSbsModeSettingsModel stagedHalf = new RawSbsModeSettingsModel(
                FULL, HALF, SessionSettingsModel.Source.CURRENT_SESSION);
        RawSbsModeSettingsModel stagedFull = new RawSbsModeSettingsModel(
                HALF, FULL, SessionSettingsModel.Source.CURRENT_SESSION);

        // The applied fallback deliberately disagrees with the staged value in both directions.
        assertFalse(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, stagedHalf, FULL));
        assertTrue(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, stagedFull, HALF));
        assertTrue(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI, stagedHalf, FULL));
        assertTrue(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, null, null));
        assertFalse(XrStreamPresenter.usesPackedBitrateCost(
                XrStreamPresenter.PresenterMode.NORMAL, null, null));
    }

    @Test
    public void rawHalfResizesLiveWhileRawFullDoesNot() {
        assertTrue(XrStreamPresenter.supportsLiveResolutionChange(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, HALF));
        assertFalse(XrStreamPresenter.supportsLiveResolutionChange(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW, FULL));
        assertTrue(XrStreamPresenter.supportsLiveResolutionChange(
                XrStreamPresenter.PresenterMode.NORMAL, FULL));
        assertTrue(XrStreamPresenter.supportsLiveResolutionChange(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI, FULL));
    }

    @Test
    public void normalToRawHalfNeedsNoDecoderTransitionOrHostMessage() {
        // Both modes are sbs_mode 0 at W x H, so nothing about the stream changes: no IDR gate,
        // no surface resize, no 0x3003/0x3007 round trip. Only the SceneCore stereo mode and the
        // quad aspect move, and finishModeSwitch already applies both live.
        assertFalse(XrStreamPresenter.requiresDecoderTransition(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW));
        assertFalse(XrStreamPresenter.requiresHostSurfaceResize(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW));
    }

    @Test
    public void rawStartupUsesSelectedPackingAndLogicalPerEyeAspect() {
        assertEquals(16.0f / 9.0f,
                XrStreamPresenter.presentationAspect(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        16.0f / 9.0f),
                0.0001f);
        assertEquals(8.0f / 9.0f,
                XrStreamPresenter.presentationAspect(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        16.0f / 9.0f,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF),
                0.0001f);
        assertEquals(16.0f / 9.0f,
                XrStreamPresenter.presentationAspect(
                        XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                        16.0f / 9.0f,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF),
                0.0001f);
        assertEquals(16.0f / 9.0f,
                XrStreamPresenter.presentationAspect(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        16.0f / 9.0f,
                        PreferenceConfiguration.RawSbsPerEyeResolution.FULL),
                0.0001f);
        assertEquals(7680,
                XrStreamPresenter.initialSurfacePixelDimensions(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        3840, 2160, MoonBridge.VIDEO_FORMAT_H264,
                        PreferenceConfiguration.RawSbsPerEyeResolution.FULL)[0]);
        assertEquals(2160,
                XrStreamPresenter.initialSurfacePixelDimensions(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        3840, 2160, MoonBridge.VIDEO_FORMAT_H264,
                        PreferenceConfiguration.RawSbsPerEyeResolution.FULL)[1]);
        assertEquals(3840,
                XrStreamPresenter.initialSurfacePixelDimensions(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        3840, 2160, MoonBridge.VIDEO_FORMAT_H264,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF)[0]);
        assertEquals(2160,
                XrStreamPresenter.initialSurfacePixelDimensions(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        3840, 2160, MoonBridge.VIDEO_FORMAT_H264,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF)[1]);
    }

    @Test
    public void clientSbsLiveResizePacksTwoNewFullHdEyesInsteadOfOldFourKEyes() {
        assertArrayEquals(new int[] {3840, 1080},
                XrStreamPresenter.clientSbsPackedDimensions(1920, 1080));
        assertNull(XrStreamPresenter.clientSbsPackedDimensions(
                Integer.MAX_VALUE, 1080));
    }

    @Test
    public void crossingHostAiBoundaryRequiresHostSurfaceResize() {
        assertTrue(XrStreamPresenter.requiresHostSurfaceResize(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_AI));
        assertTrue(XrStreamPresenter.requiresHostSurfaceResize(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.HOST_SBS_AI));
        assertTrue(XrStreamPresenter.requiresHostSurfaceResize(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                XrStreamPresenter.PresenterMode.NORMAL));
        assertTrue(XrStreamPresenter.requiresHostSurfaceResize(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW));
    }

    @Test
    public void unchangedDirectModeDoesNotResizeHostSurface() {
        for (XrStreamPresenter.PresenterMode mode : new XrStreamPresenter.PresenterMode[] {
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.HOST_SBS_AI}) {
            assertFalse(XrStreamPresenter.requiresHostSurfaceResize(mode, mode));
        }
    }

    @Test
    public void onlySurfaceOrDimensionChangesRequireDecoderTransition() {
        assertFalse(XrStreamPresenter.requiresDecoderTransition(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW));
        assertFalse(XrStreamPresenter.requiresDecoderTransition(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.NORMAL));

        assertTrue(XrStreamPresenter.requiresDecoderTransition(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_AI));
        assertTrue(XrStreamPresenter.requiresDecoderTransition(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW));
        assertTrue(XrStreamPresenter.requiresDecoderTransition(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI));
        assertTrue(XrStreamPresenter.requiresDecoderTransition(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW));
    }

    @Test
    public void onlyClientRendererCrossingsRetainTheOldPictureUntilFreshTargetFrame() {
        assertTrue(XrStreamPresenter.retainsOldPictureUntilFreshTargetFrame(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI));
        assertTrue(XrStreamPresenter.retainsOldPictureUntilFreshTargetFrame(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                XrStreamPresenter.PresenterMode.HOST_SBS_AI));
        assertFalse(XrStreamPresenter.retainsOldPictureUntilFreshTargetFrame(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_AI));
        assertFalse(XrStreamPresenter.retainsOldPictureUntilFreshTargetFrame(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.NORMAL));
    }

    @Test
    public void clientRendererRemainsActiveUntilSurfaceSwitchOutcome() {
        // Enter pre-stages the renderer before EGL resumes; exit retains the previous packed frame
        // until MediaCodec has parked and the asynchronous detach completes.
        assertTrue(XrStreamPresenter.clientSbsActiveDuringSurfaceSwitch(false, true));
        assertTrue(XrStreamPresenter.clientSbsActiveDuringSurfaceSwitch(true, false));

        assertTrue(XrStreamPresenter.clientSbsActiveAfterSurfaceSwitch(false, true, true));
        assertFalse(XrStreamPresenter.clientSbsActiveAfterSurfaceSwitch(false, true, false));
        assertFalse(XrStreamPresenter.clientSbsActiveAfterSurfaceSwitch(true, false, true));
        assertTrue(XrStreamPresenter.clientSbsActiveAfterSurfaceSwitch(true, false, false));
    }

    @Test
    public void onlyClientEntryWaitsForAPackedEglSwap() {
        assertTrue(XrStreamPresenter.requiresClientPackedSwapProof(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI));
        assertTrue(XrStreamPresenter.requiresClientPackedSwapProof(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI));
        assertFalse(XrStreamPresenter.requiresClientPackedSwapProof(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                XrStreamPresenter.PresenterMode.NORMAL));
        assertFalse(XrStreamPresenter.requiresClientPackedSwapProof(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_AI));
    }

    @Test
    public void inactiveClientSavedQualityFusesIntoOneAckFirstModeSwitch() {
        ModeStreamQualityModel liveTarget = targetQuality(false);

        assertTrue(XrStreamPresenter.shouldFuseClientModeEntryQuality(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                true, false, liveTarget));
        assertFalse(XrStreamPresenter.shouldReconnectBeforeClientModeEntry(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                false, liveTarget));

        // No fusion for a same-mode update, a standard host, or unrelated staged reconnect work.
        assertFalse(XrStreamPresenter.shouldFuseClientModeEntryQuality(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                true, false, liveTarget));
        assertFalse(XrStreamPresenter.shouldFuseClientModeEntryQuality(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                false, false, liveTarget));
        assertFalse(XrStreamPresenter.shouldFuseClientModeEntryQuality(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                true, true, liveTarget));
    }

    @Test
    public void incompatibleOrLegacyClientSavedQualityReconnectsBeforeHandoff() {
        ModeStreamQualityModel reconnectTarget = targetQuality(true);

        assertTrue(XrStreamPresenter.shouldReconnectBeforeClientModeEntry(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                false, reconnectTarget));
        assertFalse(XrStreamPresenter.shouldFuseClientModeEntryQuality(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                true, false, reconnectTarget));
    }

    @Test
    public void restoredHostAiRefreshesSurfaceWhenActualCodecChangesPackedGeometry() {
        assertTrue(XrStreamPresenter.hostSbsFormatChangeRequiresResize(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                5120, 1440,
                MoonBridge.VIDEO_FORMAT_H265,
                MoonBridge.VIDEO_FORMAT_H264));
        assertFalse(XrStreamPresenter.hostSbsFormatChangeRequiresResize(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                1920, 1080,
                MoonBridge.VIDEO_FORMAT_H265,
                MoonBridge.VIDEO_FORMAT_H264));
    }

    @Test
    public void inactiveHostAiAndEquivalentCodecGeometryDoNotResizeAtStartup() {
        assertFalse(XrStreamPresenter.hostSbsFormatChangeRequiresResize(
                XrStreamPresenter.PresenterMode.NORMAL,
                5120, 1440,
                MoonBridge.VIDEO_FORMAT_H265,
                MoonBridge.VIDEO_FORMAT_H264));
        assertFalse(XrStreamPresenter.hostSbsFormatChangeRequiresResize(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                5120, 1440,
                MoonBridge.VIDEO_FORMAT_H265,
                MoonBridge.VIDEO_FORMAT_AV1_MAIN8));
    }

    @Test
    public void onlyAnActiveClientSbsStreamStartsAStandaloneHdrBoundary() {
        assertTrue(XrStreamPresenter.canSynchronizeClientSbsHdrTransition(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                true, false, false));
        assertFalse(XrStreamPresenter.canSynchronizeClientSbsHdrTransition(
                XrStreamPresenter.PresenterMode.NORMAL,
                true, false, false));
        assertFalse(XrStreamPresenter.canSynchronizeClientSbsHdrTransition(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                false, false, false));
    }

    @Test
    public void hdrBoundaryCanBeSupersededButCannotOverlapAModeSwitch() {
        assertTrue(XrStreamPresenter.canSynchronizeClientSbsHdrTransition(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                true, true, true));
        assertFalse(XrStreamPresenter.canSynchronizeClientSbsHdrTransition(
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                true, true, false));
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
