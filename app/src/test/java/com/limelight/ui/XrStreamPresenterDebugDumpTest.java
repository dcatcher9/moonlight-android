package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class XrStreamPresenterDebugDumpTest {
    @Test
    public void onlyHostAiAndGameSupportHostDumps() {
        for (PresentationMode mode
                : PresentationMode.values()) {
            assertEquals(mode == PresentationMode.HOST_SBS_AI || mode == PresentationMode.GAME_3D,
                    XrStreamPresenter.isHostDebugDumpAvailable(
                            mode, true, true, false, true, true));
        }
    }

    @Test
    public void hostDumpWaitsForAStableReadyPipeline() {
        PresentationMode host =
                PresentationMode.HOST_SBS_AI;

        assertTrue(XrStreamPresenter.isHostDebugDumpAvailable(
                host, true, true, false, true, false));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                host, false, true, false, true, false));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                host, true, false, false, true, false));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                host, true, true, true, true, false));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                host, true, true, false, false, true));
    }

    @Test
    public void gameDumpNeedsNegotiatedSupportAndStableStreamButNotAiDepth() {
        PresentationMode game = PresentationMode.GAME_3D;
        assertTrue(XrStreamPresenter.isHostDebugDumpAvailable(
                game, true, true, false, false, true));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                game, true, true, false, true, false));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                game, false, true, false, false, true));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                game, true, false, false, false, true));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                game, true, true, true, false, true));
    }

    @Test
    public void hostDepthReadinessIsScopedToOneHostModeGeneration() {
        PresentationMode host =
                PresentationMode.HOST_SBS_AI;
        PresentationMode normal =
                PresentationMode.NORMAL;
        PresentationMode raw =
                PresentationMode.HOST_SBS_RAW;

        assertTrue(XrStreamPresenter.resetsHostDepthStatusAtTransitionStart(normal, host));
        assertTrue(XrStreamPresenter.resetsHostDepthStatusAtTransitionStart(raw, host));
        assertFalse(XrStreamPresenter.resetsHostDepthStatusAtTransitionStart(host, normal));
        assertFalse(XrStreamPresenter.resetsHostDepthStatusAtTransitionStart(host, host));

        assertTrue(XrStreamPresenter.resetsHostDepthStatusAtTransitionCommit(host, normal));
        assertTrue(XrStreamPresenter.resetsHostDepthStatusAtTransitionCommit(host, raw));
        assertFalse(XrStreamPresenter.resetsHostDepthStatusAtTransitionCommit(normal, host));
        assertFalse(XrStreamPresenter.resetsHostDepthStatusAtTransitionCommit(host, host));
    }
}
