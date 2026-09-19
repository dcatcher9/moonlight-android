package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GameStereoSourceStateTest {
    @Test
    public void entryNeedsConfirmedGenerationAndExactProviderProofBeforeWidening() {
        GameStereoSourceState state = new GameStereoSourceState();
        assertFalse(ready(state, 7, 1));
        state.enterGame();
        assertEquals(GameStereoSourceState.Status.WAITING, state.status(true, 2));
        assertFalse(ready(state, 7, 1));
        state.confirmPresentation(7, 1920, 1080);
        assertFalse(state.isReady());
        assertFalse(state.acceptStatus(7, 1, 1, 0, 1920, 1080, 3840, 1080));
        assertFalse(state.acceptStatus(7, 1, 1, 1, 1920, 1080, 1920, 1080));
        assertFalse(state.acceptStatus(7, 1, 1, 1, 1920, 1080, 3840, 720));
        assertTrue(ready(state, 7, 1));
        assertTrue(state.isReady());
        assertTrue(state.shouldAutoWiden(true, 2));
        assertFalse(state.shouldAutoWiden(false, 2));
        assertFalse(state.shouldAutoWiden(true, 0));
        assertFalse(state.shouldAutoWiden(true, 3));
        assertEquals(GameStereoSourceState.Status.READY, state.status(true, 2));
    }

    @Test
    public void oldGenerationWrongSizeAndMalformedStatusCannotAuthorizeStereo() {
        GameStereoSourceState state = entered(9);
        assertFalse(ready(state, 8, 100));
        assertFalse(state.acceptStatus(9, 1, 1, 1, 3840, 2160, 7680, 2160));
        assertFalse(state.acceptStatus(9, 1, 3, 1, 1920, 1080, 3840, 1080));
        assertFalse(state.acceptStatus(9, 1, 1, 2, 1920, 1080, 3840, 1080));
        assertFalse(ready(state, 9, -1));
        assertFalse(ready(state, 9, 0));
        assertFalse(ready(state, 9, 0x1_0000_0000L));
        assertFalse(state.isReady());
        assertTrue(ready(state, 9, 1));
        state.invalidateSourceProof();
        assertFalse(ready(state, 9, 2));
        assertFalse(state.isReady());
        state.confirmPresentation(10, 1920, 1080);
        assertFalse(ready(state, 9, 3));
        assertTrue(ready(state, 10, 1));
    }

    @Test
    public void newerHostPresentationRequiresConfirmationWithoutCreatingSourceProof() {
        GameStereoSourceState state = entered(10);
        assertTrue(reconfirm(state, 11, 1));
        assertFalse(state.isReady());
        assertFalse(ready(state, 11, 1));
        assertFalse(reconfirm(state, 10, 1));
        assertFalse(reconfirm(state, 9, 1));
        assertFalse(reconfirm(state, 0x8000_000AL, 1));
        assertFalse(reconfirm(state, 11, 0));
        assertFalse(state.requiresPresentationReconfirmation(11, 1, 1, 0,
                1920, 1080, 3840, 1080));
        assertFalse(state.requiresPresentationReconfirmation(11, 1, 3, 1,
                1920, 1080, 3840, 1080));
        assertFalse(state.requiresPresentationReconfirmation(11, 1, 1, 1,
                1920, 1080, 1920, 1080));
        assertFalse(state.requiresPresentationReconfirmation(11, 1, 1, 1,
                3840, 2160, 7680, 2160));
        state.invalidateSourceProof();
        assertFalse(reconfirm(state, 11, 1));
        state.confirmPresentation(12, 1920, 1080);
        assertFalse(ready(state, 11, 2));
        assertTrue(ready(state, 12, 1));
    }

    @Test
    public void reconfirmationHandlesGenerationWrapAndNeverClearsFailureLatch() {
        GameStereoSourceState state = entered(0xFFFF_FFFFL);
        assertFalse(reconfirm(state, 0, 1));
        assertFalse(reconfirm(state, 0x1_0000_0000L, 1));
        assertTrue(reconfirm(state, 1, 1));
        state.recordWidenFailure();
        assertFalse(reconfirm(state, 1, 1));
        assertTrue(state.hasWidenFailure());
        state.confirmPresentation(1, 1920, 1080);
        assertTrue(ready(state, 1, 1));
        assertFalse(state.shouldAutoWiden(true, 2));
        state.leaveGame();
        assertFalse(reconfirm(state, 2, 1));
    }

    @Test
    public void revisionsRejectDuplicateAndOlderProofIncludingUnsignedWrap() {
        GameStereoSourceState state = entered(0xFFFF_FFFFL);
        assertTrue(ready(state, 0xFFFF_FFFFL, 0xFFFF_FFFEL));
        assertTrue(waiting(state, 0xFFFF_FFFFL, 0xFFFF_FFFFL));
        assertFalse(ready(state, 0xFFFF_FFFFL, 0xFFFF_FFFFL));
        assertFalse(ready(state, 0xFFFF_FFFFL, 0xFFFF_FFFEL));
        assertFalse(state.isReady());
        assertFalse(ready(state, 0xFFFF_FFFFL, 0));
        assertTrue(ready(state, 0xFFFF_FFFFL, 1));
        assertTrue(waiting(state, 0xFFFF_FFFFL, 2));
        assertFalse(ready(state, 0xFFFF_FFFFL, 0x8000_0002L));
        assertFalse(ready(state, 0xFFFF_FFFFL, 0xFFFF_FFFFL));
        assertFalse(state.isReady());
    }

    @Test
    public void temporarySourceLossKeepsPackedFallbackWithoutAnotherWideningRequest() {
        GameStereoSourceState state = entered(1);
        assertTrue(ready(state, 1, 1));
        state.invalidateSourceProof();
        state.confirmPresentation(2, 1920, 1080);
        assertEquals(GameStereoSourceState.Status.FALLBACK, state.status(true, 3));
        assertTrue(waiting(state, 2, 1));
        assertFalse(state.shouldAutoWiden(true, 3));
        assertTrue(ready(state, 2, 2));
        assertEquals(GameStereoSourceState.Status.READY, state.status(true, 3));
        assertFalse(state.shouldAutoWiden(true, 3));
        assertTrue(waiting(state, 2, 3));
        assertEquals(GameStereoSourceState.Status.FALLBACK, state.status(true, 3));
    }

    @Test
    public void failedAttemptSurvivesReceiverAndPresentationChangesUntilExplicitRetry() {
        GameStereoSourceState state = entered(1);
        assertTrue(ready(state, 1, 1));
        state.recordWidenFailure();
        assertTrue(waiting(state, 1, 2));
        assertTrue(ready(state, 1, 3));
        assertFalse(state.shouldAutoWiden(true, 2));
        state.invalidateSourceProof();
        state.confirmPresentation(2, 1920, 1080);
        assertTrue(ready(state, 2, 1));
        assertTrue(state.hasWidenFailure());
        assertFalse(state.shouldAutoWiden(true, 2));

        state.onRelevantQualityChanged();
        assertFalse(state.hasWidenFailure());
        assertFalse(state.isReady());
        state.confirmPresentation(3, 1920, 1080);
        assertTrue(ready(state, 3, 1));
        assertTrue(state.shouldAutoWiden(true, 2));
        state.recordWidenFailure();
        state.leaveGame();
        assertTrue(state.hasWidenFailure());
        assertFalse(ready(state, 3, 2));
        state.enterGame();
        assertFalse(state.hasWidenFailure());
        assertFalse(state.isReady());
    }

    @Test
    public void reconfirmingSamePresentationPreservesProofAndRevisionButResizeInvalidatesIt() {
        GameStereoSourceState state = entered(4);
        assertTrue(ready(state, 4, 10));
        state.confirmPresentation(4, 1920, 1080);
        assertTrue(state.isReady());
        assertFalse(waiting(state, 4, 9));
        state.confirmPresentation(4, 3840, 2160);
        assertFalse(state.isReady());
        assertFalse(ready(state, 4, 11));
        assertTrue(state.acceptStatus(4, 1, 1, 1, 3840, 2160, 7680, 2160));
        state.confirmPresentation(0, 3840, 2160);
        assertFalse(state.isReady());
    }

    @Test
    public void unsupportedCapabilityOrSourceNeverAllowsAutomaticWidening() {
        GameStereoSourceState state = entered(5);
        assertEquals(GameStereoSourceState.Status.UNSUPPORTED, state.status(false, 2));
        assertFalse(state.acceptStatus(5, 1, 2, 0, 1920, 1080, 0, 0));
        assertTrue(state.acceptStatus(5, 1, 2, 0, 1920, 1080, 3840, 1080));
        assertEquals(GameStereoSourceState.Status.UNSUPPORTED, state.status(true, 2));
        assertFalse(state.isReady());
        assertFalse(state.shouldAutoWiden(true, 2));
        assertFalse(state.acceptStatus(5, 2, 0, 0, 1920, 1080, 3840, 0));
        assertTrue(waiting(state, 5, 2));
        assertEquals(GameStereoSourceState.Status.WAITING, state.status(true, 2));
    }

    private static GameStereoSourceState entered(long generation) {
        GameStereoSourceState state = new GameStereoSourceState();
        state.enterGame();
        state.confirmPresentation(generation, 1920, 1080);
        return state;
    }

    private static boolean ready(GameStereoSourceState state, long generation, long revision) {
        return state.acceptStatus(generation, revision, 1, 1, 1920, 1080, 3840, 1080);
    }

    private static boolean reconfirm(GameStereoSourceState state, long generation, long revision) {
        return state.requiresPresentationReconfirmation(
                generation, revision, 1, 1, 1920, 1080, 3840, 1080);
    }

    private static boolean waiting(GameStereoSourceState state, long generation, long revision) {
        return state.acceptStatus(generation, revision, 0, 0, 1920, 1080, 3840, 1080);
    }
}
