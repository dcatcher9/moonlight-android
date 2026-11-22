package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.view.Surface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Characterizes StreamContainer's live Client SBS resize: admission and its exclusion with the
 * Client mode switch, coalescing while the detach is in flight, the detach, attach and swap
 * stages with their watchdogs and timeout boundaries, the post-ACK proof window and the bounded
 * cold-backend exception, the failure paths that cancel the decoder and abandon the attachment,
 * and teardown. Every assertion is about order, a stage, a deadline or which completion runs.
 *
 * <p>{@link ClientSbsContainerHarness#resizeState()} is the only resolver for where the resize
 * state lives; the container's own entry points, its production EGL factory and its renderer
 * listener drive everything else.</p>
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class ClientSbsResizeCoordinatorLifecycleTest {
    private static final String SUSPEND = "renderer.suspendPresentationForLiveStreamResize";
    private static final String POST_ACK_MATCHED = "INFO Client SBS post-ack decoder output "
            + "matched 2560x1440; allowing a fresh packed-presentation proof window";

    private ClientSbsContainerHarness h;

    @Before
    public void setUp() {
        h = new ClientSbsContainerHarness();
        h.setOperation(40);
    }

    @After
    public void tearDown() {
        h.close();
    }

    @Test
    public void admissionChecksTheSwitchAndStageBeforeSuspendingTheRenderer() {
        List<Boolean> results = new ArrayList<>();
        when(h.renderer.isClientSbs()).thenReturn(false);
        assertFalse(h.container.resizeClientSbsSurface(2560, 1440, results::add));
        when(h.renderer.isClientSbs()).thenReturn(true);
        assertFalse(h.container.resizeClientSbsSurface(2560, 1440, null));
        assertFalse(h.container.resizeClientSbsSurface(0, 1440, results::add));
        // The Client mode switch owns the surfaces until it settles.
        h.container.switchToClientSbs(true, false, result -> { });
        assertFalse(h.container.resizeClientSbsSurface(2560, 1440, results::add));
        assertFalse(h.events.toString().contains(SUSPEND));
        h.completeDecoderBind(true);
        h.createWindowOnEglThread();
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.idle();
        h.completeDecoderBind(true);
        h.events.clear();

        // A refused renderer suspension starts nothing and consumes no operation generation.
        h.suspendResult = false;
        assertFalse(h.container.resizeClientSbsSurface(2560, 1440, results::add));
        assertEquals(Collections.singletonList(SUSPEND + " 2560x1440"
                + " | op=41 attach=41 created=41 detach=0 expected=sceneCore"), h.events);
        assertEquals(ClientSbsResizePolicy.Stage.IDLE, h.resizeState().stage);
        assertEquals(41, h.operation());
        h.suspendResult = true;

        // An accepted resize refuses the Client mode switch without replacing either owner.
        assertTrue(h.container.resizeClientSbsSurface(2560, 1440, results::add));
        int switchGeneration = h.switchGeneration();
        List<Boolean> switched = new ArrayList<>();
        h.container.switchToClientSbs(false, false, switched::add);
        assertEquals(Collections.singletonList(false), switched);
        assertEquals(switchGeneration, h.switchGeneration());
        assertTrue(h.decoderBinds.isEmpty());
        assertTrue(results.isEmpty());

        ReflectionHelpers.setField(h.container, "mDestroyed", true);
        h.events.clear();
        assertFalse(h.container.resizeClientSbsSurface(1920, 1080, results::add));
        assertTrue(h.events.isEmpty());
    }

    @Test
    public void aRequestDuringTheDetachReplacesTheGeometryAndLaterStagesRefuseRequests() {
        h.enterClientSbs();
        List<Boolean> first = new ArrayList<>();
        List<Boolean> second = new ArrayList<>();
        assertTrue(h.container.resizeClientSbsSurface(2560, 1440, first::add));
        assertTrue(h.container.resizeClientSbsSurface(1920, 1080, success -> {
            h.record("resize.callback " + success);
            second.add(success);
        }));
        ClientSbsResizeTransaction resize = h.resizeState();
        assertEquals(ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH, resize.stage);
        assertEquals(43, resize.pendingGeneration);
        assertEquals(1920, resize.pendingWidth);
        assertEquals(1080, resize.pendingHeight);

        // The detach still in flight is the first request's; its ack attaches the newest values.
        h.destroyWindowOnEglThread();
        h.idle();
        assertEquals(ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH, resize.stage);
        assertFalse(h.container.resizeClientSbsSurface(2560, 1440, first::add));
        h.createWindowOnEglThread();
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.idle();
        assertEquals(ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP, resize.stage);
        assertFalse(h.container.resizeClientSbsSurface(2560, 1440, first::add));
        h.resizeSwapProof.run();
        h.idle();

        assertEquals(Arrays.asList(
                SUSPEND + " 2560x1440 | op=41 attach=41 created=41 detach=0 expected=sceneCore",
                "eglOwner.requestPause | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                SUSPEND + " 1920x1080 | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                "egl.destroy window | op=43 attach=41 created=41 detach=42 expected=sceneCore",
                "renderer.prepareLiveStreamResize 1920x1080 packed 3840x1080"
                        + " | op=43 attach=41 created=0 detach=0 expected=null",
                "presenter.setClientSbsSurfaceSize 1920x1080"
                        + " | op=43 attach=41 created=0 detach=0 expected=null",
                "eglOwner.requestResume | op=43 attach=43 created=0 detach=0 expected=sceneCore2",
                "egl.create sceneCore2 | op=43 attach=43 created=0 detach=0 expected=sceneCore2",
                "presenter.onClientSbsOutputCapabilityChanged"
                        + " | op=43 attach=43 created=43 detach=0 expected=sceneCore2",
                "renderer.completeLiveStreamResizeAfterSwap"
                        + " | op=43 attach=43 created=43 detach=0 expected=sceneCore2",
                // A proven swap completes without cancelling the decoder or the attachment.
                "resize.callback true | op=43 attach=43 created=43 detach=0 expected=sceneCore2"),
                h.events);
        // The replaced request is dropped, never completed; the newest completes once.
        assertTrue(first.isEmpty());
        assertEquals(Collections.singletonList(true), second);
        assertEquals(ClientSbsResizePolicy.Stage.IDLE, resize.stage);
        assertNull(resize.pendingCallback);
    }

    @Test
    public void detachWatchdogFailsAtTwoSecondsThenCancelsTheDecoderAndAbandons() {
        h.enterClientSbs();
        List<Boolean> results = new ArrayList<>();
        assertTrue(h.container.resizeClientSbsSurface(2560, 1440, success -> {
            h.record("resize.callback " + success);
            results.add(success);
        }));
        h.events.clear();
        h.idleFor(1999);
        assertTrue(results.isEmpty());
        h.idleFor(1);

        assertEquals(Collections.singletonList(false), results);
        assertTrue(h.logs.contains(
                "SEVERE Timed out waiting for Client SBS EGL detachment at 2560x1440"));
        // The decoder is cancelled, then the attachment abandoned, then the owner told.
        assertEquals(Arrays.asList(
                "decoder.cancelPending | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                "renderer.abandonPresentation | op=42 attach=0 created=0 detach=0 expected=null",
                "resize.callback false | op=42 attach=0 created=0 detach=0 expected=null"),
                h.events);
        assertEquals(ClientSbsResizePolicy.Stage.IDLE, h.resizeState().stage);

        // The late driver ack finds no detach request and changes nothing.
        h.events.clear();
        h.destroyWindowOnEglThread();
        h.idle();
        assertEquals(Collections.singletonList(
                "egl.destroy window | op=42 attach=0 created=0 detach=0 expected=null"), h.events);
        assertEquals(Collections.singletonList(false), results);
    }

    @Test
    public void attachWatchdogFailsAtTwoSecondsAfterTheAttachRequest() {
        h.enterClientSbs();
        List<Boolean> results = h.resizeThroughAttach(2560, 1440);
        assertEquals(ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH, h.resizeState().stage);
        h.idleFor(1999);
        assertTrue(results.isEmpty());
        h.idleFor(1);
        assertEquals(Collections.singletonList(false), results);
        assertTrue(h.logs.contains(
                "SEVERE Timed out waiting for Client SBS exact EGL attachment at 2560x1440"));
    }

    @Test
    public void swapWaitWithoutHostOutcomeUsesTheEightSecondFallback() {
        h.enterClientSbs();
        List<Boolean> results = resizeToSwap();
        h.idleFor(7999);
        assertTrue(results.isEmpty());
        h.idleFor(1);
        assertEquals(Collections.singletonList(false), results);
        assertTrue(h.logs.contains("SEVERE Timed out waiting for Client SBS packed presentation "
                + "or authoritative host outcome at 2560x1440"));
    }

    @Test
    public void matchingPostAckOutputNudgesTheProofAndRearmsAFreshTwoSecondWindow() {
        h.enterClientSbs();
        List<Boolean> results = resizeToSwap();
        h.idleFor(3000);
        h.events.clear();

        // Output at another geometry is not this resize's boundary.
        h.container.onClientSbsPostAckDecoderOutput(1920, 1080);
        assertTrue(h.events.isEmpty());
        assertFalse(h.logs.contains(POST_ACK_MATCHED));

        h.container.onClientSbsPostAckDecoderOutput(2560, 1440);
        assertTrue(h.logs.contains(POST_ACK_MATCHED));
        assertEquals(Collections.singletonList("renderer.requestLiveStreamResizeProofDraw"
                + " | op=42 attach=42 created=42 detach=0 expected=sceneCore2"), h.events);
        assertEquals(42, h.resizeState().postAckGeneration);

        // A repeated notification nudges again and restarts the short window from now.
        h.idleFor(1000);
        h.container.onClientSbsPostAckDecoderOutput(2560, 1440);
        h.idleFor(1999);
        assertTrue(results.isEmpty());
        h.idleFor(1);
        assertEquals(Collections.singletonList(false), results);
        assertTrue(h.logs.contains(
                "SEVERE Timed out waiting for Client SBS post-ack packed presentation at 2560x1440"));
        // The superseded eight-second watchdog never fires a second failure.
        h.idleFor(8000);
        assertEquals(Collections.singletonList(false), results);
    }

    @Test
    public void postAckOutputBeforeTheSwapStageShortensTheSwapWaitWithoutANudge() {
        h.enterClientSbs();
        List<Boolean> results = h.resizeThroughAttach(2560, 1440);
        h.events.clear();
        h.container.onClientSbsPostAckDecoderOutput(2560, 1440);
        assertTrue(h.events.isEmpty());
        assertTrue(h.resizeState().hasPostAckBoundaryForActiveResize());

        h.createWindowOnEglThread();
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.idle();
        assertEquals(ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP, h.resizeState().stage);
        h.idleFor(1999);
        assertTrue(results.isEmpty());
        h.idleFor(1);
        assertEquals(Collections.singletonList(false), results);
        assertTrue(h.logs.contains(
                "SEVERE Timed out waiting for Client SBS post-ack packed presentation at 2560x1440"));
    }

    @Test
    public void aStageWatchdogDefersToADecoderBindThatOwnsItsCompletion() {
        h.enterClientSbs();
        List<Boolean> results = h.resizeThroughAttach(2560, 1440);
        h.createWindowOnEglThread();
        Surface replacementInput = h.validSurface("rendererInput2");
        h.rendererReadyOnEglThread(replacementInput, 1);
        h.idle();
        assertEquals(1, h.decoderBinds.size());

        h.decoderCancelAtDeadline = false;
        h.idleFor(2000);
        assertTrue(results.isEmpty());
        assertFalse(h.logs.toString().contains("Timed out waiting for Client SBS"));
        assertEquals(ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH, h.resizeState().stage);

        h.completeDecoderBind(true);
        assertEquals(ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP, h.resizeState().stage);
        h.resizeSwapProof.run();
        h.idle();
        assertEquals(Collections.singletonList(true), results);
    }

    @Test
    public void coldBackendPollsThenGrantsExactlyOneReadyProofWindow() {
        h.enterClientSbs();
        List<Boolean> results = resizeToSwap();
        h.backendInitializing = true;
        h.events.clear();
        h.container.onClientSbsPostAckDecoderOutput(2560, 1440);

        h.idleFor(5000);
        assertTrue(results.isEmpty());
        h.backendInitializing = false;
        h.idleFor(1000);
        assertTrue(h.logs.contains("INFO Client SBS cold AI backend finished initializing at "
                + "2560x1440; granting one fresh 2000 ms post-ack packed-presentation proof window"));
        h.idleFor(1999);
        assertTrue(results.isEmpty());
        h.idleFor(1);

        assertEquals(Collections.singletonList(false), results);
        assertEquals(1, count(h.logs, "INFO Client SBS host/decoder geometry is confirmed at "
                + "2560x1440; extending packed-presentation proof while the cold AI backend "
                + "initializes (bounded to 30000 ms)"));
        assertTrue(h.logs.contains(
                "SEVERE Timed out waiting for Client SBS post-ack packed presentation at 2560x1440"));
        assertEquals(2, count(h.events, "renderer.requestLiveStreamResizeProofDraw"
                + " | op=42 attach=42 created=42 detach=0 expected=sceneCore2"));
    }

    @Test
    public void coldBackendThatNeverFinishesFailsAtTheThirtySecondCeiling() {
        h.enterClientSbs();
        List<Boolean> results = resizeToSwap();
        h.backendInitializing = true;
        h.container.onClientSbsPostAckDecoderOutput(2560, 1440);
        h.idleFor(29_999);
        assertTrue(results.isEmpty());
        h.idleFor(1);
        assertEquals(Collections.singletonList(false), results);
        assertTrue(h.logs.contains("SEVERE Timed out waiting for Client SBS bounded cold AI "
                + "initialization and post-ack packed presentation at 2560x1440"));
        assertEquals(1, count(h.logs, "INFO Client SBS host/decoder geometry is confirmed at "
                + "2560x1440; extending packed-presentation proof while the cold AI backend "
                + "initializes (bounded to 30000 ms)"));
    }

    @Test
    public void aFailedPreparationAbandonsWithoutResizingSceneCore() {
        h.enterClientSbs();
        h.prepareLiveStreamResizeResult = false;
        List<Boolean> results = h.resizeThroughAttach(2560, 1440);
        assertEquals(Collections.singletonList(false), results);
        assertEquals(Arrays.asList(
                SUSPEND + " 2560x1440 | op=41 attach=41 created=41 detach=0 expected=sceneCore",
                "eglOwner.requestPause | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                "egl.destroy window | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                "renderer.prepareLiveStreamResize 2560x1440 packed 5120x1440"
                        + " | op=42 attach=41 created=0 detach=0 expected=null",
                "decoder.cancelPending | op=42 attach=41 created=0 detach=0 expected=null",
                "renderer.abandonPresentation | op=42 attach=0 created=0 detach=0 expected=null"),
                h.events);
        assertEquals(ClientSbsResizePolicy.Stage.IDLE, h.resizeState().stage);
    }

    @Test
    public void validationFailureOfTheResizedOutputFailsTheResizeOnce() {
        h.enterClientSbs();
        List<Boolean> results = h.resizeThroughAttach(2560, 1440);
        h.createWindowOnEglThread();
        h.events.clear();
        h.validationFailedOnEglThread(1, "size mismatch");
        h.idle();
        assertEquals(Collections.singletonList(false), results);
        assertTrue(h.logs.contains("SEVERE Client SBS EGL output validation failed for generation "
                + "1: size mismatch"));
        assertEquals(Arrays.asList(
                "decoder.cancelPending | op=42 attach=42 created=42 detach=0 expected=sceneCore2",
                "renderer.abandonPresentation | op=42 attach=0 created=0 detach=0 expected=null"),
                h.events);
    }

    @Test
    public void aFailedDecoderBindFailsTheResize() {
        h.enterClientSbs();
        List<Boolean> results = h.resizeThroughAttach(2560, 1440);
        h.createWindowOnEglThread();
        h.rendererReadyOnEglThread(h.validSurface("rendererInput2"), 1);
        h.idle();
        h.completeDecoderBind(false);
        assertEquals(Collections.singletonList(false), results);
        assertEquals(ClientSbsResizePolicy.Stage.IDLE, h.resizeState().stage);
        assertEquals(0, h.requestedAttach());
    }

    @Test
    public void anUnarmableSwapProofFailsTheResize() {
        h.enterClientSbs();
        h.completeAfterSwapResult = false;
        List<Boolean> results = h.resizeThroughAttach(2560, 1440);
        h.createWindowOnEglThread();
        h.events.clear();
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.idle();
        assertEquals(Collections.singletonList(false), results);
        assertEquals(Arrays.asList(
                "presenter.onClientSbsOutputCapabilityChanged"
                        + " | op=42 attach=42 created=42 detach=0 expected=sceneCore2",
                "renderer.completeLiveStreamResizeAfterSwap"
                        + " | op=42 attach=42 created=42 detach=0 expected=sceneCore2",
                "decoder.cancelPending | op=42 attach=42 created=42 detach=0 expected=sceneCore2",
                "renderer.abandonPresentation | op=42 attach=0 created=0 detach=0 expected=null"),
                h.events);
    }

    @Test
    public void aSettledResizeIgnoresALateProofAndStartsTheNextOneFresh() {
        h.enterClientSbs();
        List<Boolean> results = resizeToSwap();
        h.container.onClientSbsPostAckDecoderOutput(2560, 1440);
        Runnable proof = h.resizeSwapProof;
        proof.run();
        h.idle();
        proof.run();
        h.idle();
        assertEquals(Collections.singletonList(true), results);
        ClientSbsResizeTransaction resize = h.resizeState();
        assertEquals(ClientSbsResizePolicy.Stage.IDLE, resize.stage);
        assertEquals(0, resize.pendingGeneration);
        assertEquals(0, resize.postAckGeneration);
        assertEquals(0L, resize.stageDeadlineMs);
        // No watchdog of the settled resize fires later.
        h.idleFor(10_000);
        assertEquals(Collections.singletonList(true), results);
        assertFalse(h.logs.toString().contains("Timed out waiting for Client SBS"));

        h.videoSurfaceAfterResize = h.sceneCore;
        List<Boolean> next = new ArrayList<>();
        assertTrue(h.container.resizeClientSbsSurface(1920, 1080, next::add));
        assertEquals(43, resize.pendingGeneration);
        assertFalse(resize.hasPostAckBoundaryForActiveResize());
    }

    @Test
    public void teardownDropsThePendingResizeWithoutCompletingIt() {
        h.enterClientSbs();
        List<Boolean> results = new ArrayList<>();
        assertTrue(h.container.resizeClientSbsSurface(2560, 1440, results::add));
        h.container.onDestroy();
        ClientSbsResizeTransaction resize = h.resizeState();
        assertEquals(ClientSbsResizePolicy.Stage.IDLE, resize.stage);
        assertNull(resize.pendingCallback);
        h.idleFor(10_000);
        assertTrue(results.isEmpty());
        assertFalse(h.logs.toString().contains("Timed out waiting for Client SBS"));
    }

    /** Starts a 2560x1440 resize and drives it through detach and attach into the swap wait. */
    private List<Boolean> resizeToSwap() {
        List<Boolean> results = h.resizeThroughAttach(2560, 1440);
        h.createWindowOnEglThread();
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.idle();
        assertEquals(ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP, h.resizeState().stage);
        assertTrue(results.isEmpty());
        return results;
    }

    private static int count(List<String> lines, String line) {
        synchronized (lines) {
            return Collections.frequency(lines, line);
        }
    }
}
