package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class XrStreamPresenterDebugDumpTest {
    @Test
    public void onlyHostSbsAiOwnsAHostDepthDump() {
        for (XrStreamPresenter.PresenterMode mode
                : XrStreamPresenter.PresenterMode.values()) {
            assertEquals(mode == XrStreamPresenter.PresenterMode.HOST_SBS_AI,
                    XrStreamPresenter.isHostDebugDumpAvailable(
                            mode, true, true, false, true));
        }
    }

    @Test
    public void hostDumpWaitsForAStableReadyPipeline() {
        XrStreamPresenter.PresenterMode host =
                XrStreamPresenter.PresenterMode.HOST_SBS_AI;

        assertTrue(XrStreamPresenter.isHostDebugDumpAvailable(
                host, true, true, false, true));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                host, false, true, false, true));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                host, true, false, false, true));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                host, true, true, true, true));
        assertFalse(XrStreamPresenter.isHostDebugDumpAvailable(
                host, true, true, false, false));
    }

    @Test
    public void hostDepthReadinessIsScopedToOneHostModeGeneration() {
        XrStreamPresenter.PresenterMode host =
                XrStreamPresenter.PresenterMode.HOST_SBS_AI;
        XrStreamPresenter.PresenterMode normal =
                XrStreamPresenter.PresenterMode.NORMAL;
        XrStreamPresenter.PresenterMode raw =
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW;

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
