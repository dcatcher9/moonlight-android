package com.limelight.ui;

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
}
