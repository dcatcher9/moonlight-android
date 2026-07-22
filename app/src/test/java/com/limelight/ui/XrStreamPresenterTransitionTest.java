package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XrStreamPresenterTransitionTest {
    @Test
    public void normalAndRawKeepTheSameHostSurfaceSize() {
        assertFalse(XrStreamPresenter.requiresHostSurfaceResize(
                XrStreamPresenter.PresenterMode.NORMAL,
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW));
        assertFalse(XrStreamPresenter.requiresHostSurfaceResize(
                XrStreamPresenter.PresenterMode.HOST_SBS_RAW,
                XrStreamPresenter.PresenterMode.NORMAL));
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
}
