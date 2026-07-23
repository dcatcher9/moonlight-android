package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class XrStreamPresenterTransitionTest {
    @Test
    public void rawBoundaryReconnectsBeforeAnyLiveSurfaceSwitch() {
        assertTrue(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW));
        assertTrue(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.NORMAL));
        assertTrue(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.CLIENT_SBS_AI));
        assertTrue(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW));
        assertFalse(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_AI));
    }

    @Test
    public void rawStartupUsesExactDoubleWidthAndLogicalPerEyeAspect() {
        assertEquals(16.0f / 9.0f,
                XrStreamPresenter.presentationAspect(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        16.0f / 9.0f),
                0.0001f);
        assertEquals(7680,
                XrStreamPresenter.initialSurfacePixelDimensions(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        3840, 2160, MoonBridge.VIDEO_FORMAT_H264)[0]);
        assertEquals(2160,
                XrStreamPresenter.initialSurfacePixelDimensions(
                        XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                        3840, 2160, MoonBridge.VIDEO_FORMAT_H264)[1]);
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
}
