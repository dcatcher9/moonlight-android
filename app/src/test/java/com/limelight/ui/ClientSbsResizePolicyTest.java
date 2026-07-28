package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClientSbsResizePolicyTest {
    @Test
    public void clampBeforeEglCreationQueuesInsteadOfWaitingForImpossibleDetach() {
        assertTrue(ClientSbsResizePolicy.queueSupersedingRequest(
                ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH));
        assertFalse(ClientSbsResizePolicy.queueSupersedingRequest(
                ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH));
    }

    @Test
    public void contextRecoveryCanRearmAnUnconfirmedSwap() {
        assertTrue(ClientSbsResizePolicy.acceptsRendererReady(
                ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP));
        assertTrue(ClientSbsResizePolicy.acceptsRendererReady(
                ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH));
        assertFalse(ClientSbsResizePolicy.acceptsRendererReady(
                ClientSbsResizePolicy.Stage.IDLE));
    }

    @Test
    public void eachLocalEglStageKeepsItsOwnShortWatchdog() {
        assertEquals(ClientSbsResizePolicy.EGL_STAGE_TIMEOUT_MS,
                ClientSbsResizePolicy.timeoutMillis(
                        ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH, false));
        assertEquals(ClientSbsResizePolicy.EGL_STAGE_TIMEOUT_MS,
                ClientSbsResizePolicy.timeoutMillis(
                        ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH, false));
        assertEquals(0L, ClientSbsResizePolicy.timeoutMillis(
                ClientSbsResizePolicy.Stage.IDLE, false));
    }

    @Test
    public void postAckDecoderBoundaryGetsAFreshSwapProofWindow() {
        assertEquals(ClientSbsResizePolicy.SWAP_FALLBACK_TIMEOUT_MS,
                ClientSbsResizePolicy.timeoutMillis(
                        ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP, false));
        assertEquals(ClientSbsResizePolicy.POST_ACK_SWAP_TIMEOUT_MS,
                ClientSbsResizePolicy.timeoutMillis(
                        ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP, true));
        assertTrue(ClientSbsResizePolicy.SWAP_FALLBACK_TIMEOUT_MS
                > ClientSbsResizePolicy.POST_ACK_SWAP_TIMEOUT_MS);
    }

    @Test
    public void coldBackendExtendsOnlyTheConfirmedPostAckSwapBoundary() {
        assertTrue(ClientSbsResizePolicy.shouldContinueWaitingForColdBackend(
                ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP,
                true, true, 2_000L));
        assertFalse(ClientSbsResizePolicy.shouldContinueWaitingForColdBackend(
                ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP,
                false, true, 2_000L));
        assertFalse(ClientSbsResizePolicy.shouldContinueWaitingForColdBackend(
                ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH,
                true, true, 2_000L));
        assertFalse(ClientSbsResizePolicy.shouldContinueWaitingForColdBackend(
                ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP,
                true, false, 2_000L));
    }

    @Test
    public void coldBackendWaitRemainsBoundedAndUsesShortPolls() {
        assertEquals(ClientSbsResizePolicy.POST_ACK_SWAP_TIMEOUT_MS,
                ClientSbsResizePolicy.boundedColdBackendPollMillis(0L));
        assertEquals(500L,
                ClientSbsResizePolicy.boundedColdBackendPollMillis(
                        ClientSbsResizePolicy.COLD_BACKEND_MAX_WAIT_MS - 500L));
        assertEquals(0L,
                ClientSbsResizePolicy.boundedColdBackendPollMillis(
                        ClientSbsResizePolicy.COLD_BACKEND_MAX_WAIT_MS));
        assertFalse(ClientSbsResizePolicy.shouldContinueWaitingForColdBackend(
                ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP,
                true, true, ClientSbsResizePolicy.COLD_BACKEND_MAX_WAIT_MS));
    }

    @Test
    public void staleOrInvalidGeometryCannotArmThePresentationBoundary() {
        assertTrue(ClientSbsResizePolicy.sameGeometry(1920, 1080, 1920, 1080));
        assertFalse(ClientSbsResizePolicy.sameGeometry(3840, 2160, 1920, 1080));
        assertFalse(ClientSbsResizePolicy.sameGeometry(0, 1080, 0, 1080));
    }

    @Test
    public void postAckRendererNudgeRequiresTheExactActiveSwapGeneration() {
        assertTrue(ClientSbsResizePolicy.shouldRequestPostAckProofDraw(
                ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP, 7, 7));
        assertFalse(ClientSbsResizePolicy.shouldRequestPostAckProofDraw(
                ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH, 7, 7));
        assertFalse(ClientSbsResizePolicy.shouldRequestPostAckProofDraw(
                ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP, 6, 7));
        assertFalse(ClientSbsResizePolicy.shouldRequestPostAckProofDraw(
                ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP, 0, 0));
    }
}
