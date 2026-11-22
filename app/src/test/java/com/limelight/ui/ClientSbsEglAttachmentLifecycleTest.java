package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

import javax.microedition.khronos.egl.EGL10;

/**
 * Characterizes StreamContainer's Client SBS EGL attachment: the operation counter shared by the
 * Client mode switch and the live resize, the attach and detach requests written before the EGL
 * owner is resumed or paused, the window-surface factory's reads and writes on the EGL thread,
 * the attachment generation each renderer callback captures, the classifyRendererCallback()
 * outcomes, and the abandon and teardown zeroing. Every assertion is about order, the thread a
 * value is read on, or which generation pair an owner accepts.
 *
 * <p>{@link ClientSbsContainerHarness#eglState()} and its field-name table are the only
 * resolvers for where the attachment state lives; the container's own entry points, its
 * production factory and its renderer listener drive everything else.</p>
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class ClientSbsEglAttachmentLifecycleTest {
    /** Keeps operation generations distinct from the switch's renderer decoder generations. */
    private static final int OPERATION_BASE = 40;

    private ClientSbsContainerHarness h;

    @Before
    public void setUp() {
        h = new ClientSbsContainerHarness();
        h.setOperation(OPERATION_BASE);
    }

    @After
    public void tearDown() {
        h.close();
    }

    @Test
    public void modeEntryPublishesTheAttachmentBeforeResumingAndPairsTheCreatedGeneration() {
        List<Boolean> results = new ArrayList<>();
        h.container.switchToClientSbs(true, false, results::add);
        h.completeDecoderBind(true);
        assertSame(h.window, h.createWindowOnEglThread());
        assertEquals(41, h.createdAttach());
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.idle();
        h.completeDecoderBind(true);

        assertEquals(Collections.singletonList(true), results);
        assertEquals(Arrays.asList(
                "renderer.prepareLiveStreamResize 1920x1080 packed 3840x1080"
                        + " | op=41 attach=0 created=0 detach=0 expected=null",
                "decoder.bind dummy | op=41 attach=0 created=0 detach=0 expected=null",
                "presenter.setClientSbsSurfaceSize(true)"
                        + " | op=41 attach=0 created=0 detach=0 expected=null",
                "renderer.prepareDecoderSurfaceGeneration 1"
                        + " | op=41 attach=41 created=0 detach=0 expected=sceneCore",
                "eglOwner.requestResume | op=41 attach=41 created=0 detach=0 expected=sceneCore",
                "egl.create sceneCore | op=41 attach=41 created=0 detach=0 expected=sceneCore",
                "presenter.onClientSbsOutputCapabilityChanged"
                        + " | op=41 attach=41 created=41 detach=0 expected=sceneCore",
                "decoder.bind rendererInput"
                        + " | op=41 attach=41 created=41 detach=0 expected=sceneCore"),
                h.events);
        assertEquals(1, h.activeDecoderGeneration());
    }

    @Test
    public void modeExitRequestsTheExactDetachBeforePausingAndItsAckClearsTheAttachment() {
        h.enterClientSbs();
        List<Boolean> results = new ArrayList<>();
        h.container.switchToClientSbs(false, false, results::add);
        h.completeDecoderBind(true);
        assertEquals(42, h.requestedDetach());
        h.destroyWindowOnEglThread();
        h.idle();
        h.completeDecoderBind(true);

        assertEquals(Collections.singletonList(true), results);
        assertEquals(Arrays.asList(
                "decoder.bind dummy | op=42 attach=41 created=41 detach=0 expected=sceneCore",
                "eglOwner.requestPause | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                "egl.destroy window | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                // The exact ack clears detach, created and the surface; the request survives.
                "presenter.setHostSurfaceSize(false)"
                        + " | op=42 attach=41 created=0 detach=0 expected=null",
                "decoder.bind sceneCore | op=42 attach=41 created=0 detach=0 expected=null"),
                h.events);
        assertEquals(0, h.activeDecoderGeneration());
    }

    @Test
    public void factoryAcquiresOnlyTheExactRequestedValidSceneCoreSurface() {
        h.setRequestedAttach(7);
        h.setExpectedSurface(h.sceneCore);
        h.setCreatedAttach(5);

        h.videoSurface = null;
        assertSame(EGL10.EGL_NO_SURFACE, h.createWindowOnEglThread());
        Surface invalid = h.validSurface("invalid");
        org.mockito.Mockito.when(invalid.isValid()).thenReturn(false);
        h.videoSurface = invalid;
        h.setExpectedSurface(invalid);
        assertSame(EGL10.EGL_NO_SURFACE, h.createWindowOnEglThread());
        h.videoSurface = h.sceneCore;
        h.setExpectedSurface(h.resizedSceneCore);
        assertSame(EGL10.EGL_NO_SURFACE, h.createWindowOnEglThread());
        h.setExpectedSurface(h.sceneCore);
        h.setRequestedAttach(0);
        assertSame(EGL10.EGL_NO_SURFACE, h.createWindowOnEglThread());
        h.setRequestedAttach(7);
        XrStreamPresenter presenter = ReflectionHelpers.getField(h.container, "mXrPresenter");
        ReflectionHelpers.setField(h.container, "mXrPresenter", null);
        assertSame(EGL10.EGL_NO_SURFACE, h.createWindowOnEglThread());
        ReflectionHelpers.setField(h.container, "mXrPresenter", presenter);
        // A refused attachment calls no driver and leaves the last created generation alone.
        assertTrue(h.events.isEmpty());
        assertEquals(5, h.createdAttach());

        assertSame(h.window, h.createWindowOnEglThread());
        assertEquals(Collections.singletonList(
                "egl.create sceneCore | op=40 attach=7 created=5 detach=0 expected=sceneCore"),
                h.events);
        assertEquals(7, h.createdAttach());
    }

    @Test
    public void factoryReadsTheRequestOnceBeforeCreationAndRechecksTheSurfaceAfterIt() {
        h.setRequestedAttach(7);
        h.setExpectedSurface(h.sceneCore);

        // A newer request written during the driver call is not the generation just created.
        h.duringCreate = () -> h.setRequestedAttach(8);
        assertSame(h.window, h.createWindowOnEglThread());
        assertEquals(7, h.createdAttach());

        // A replaced expected surface during the driver call leaves no created attachment,
        // although the driver's window is still returned to the EGL owner.
        h.setRequestedAttach(9);
        h.duringCreate = () -> h.setExpectedSurface(h.resizedSceneCore);
        assertSame(h.window, h.createWindowOnEglThread());
        assertEquals(0, h.createdAttach());

        // A failed driver call records no attachment either.
        h.setExpectedSurface(h.sceneCore);
        h.setCreatedAttach(5);
        h.duringCreate = null;
        h.createResult = EGL10.EGL_NO_SURFACE;
        assertSame(EGL10.EGL_NO_SURFACE, h.createWindowOnEglThread());
        assertEquals(0, h.createdAttach());
    }

    @Test
    public void factoryAcknowledgesOnlyASuccessfulDestroyAndReadsTheDetachRequestAfterIt() {
        h.setRequestedAttach(7);
        h.setCreatedAttach(7);
        h.setExpectedSurface(h.sceneCore);

        h.setRequestedDetach(9);
        h.destroyResult = false;
        try {
            h.destroyWindowOnEglThread();
            fail("A failed eglDestroySurface must not acknowledge detachment");
        } catch (IllegalStateException error) {
            assertEquals("Client SBS EGL window release failed", error.getMessage());
        }
        h.idle();
        assertEquals("op=40 attach=7 created=7 detach=9 expected=sceneCore", h.egl());

        // No detach request: a successful destroy posts nothing.
        h.destroyResult = true;
        h.setRequestedDetach(0);
        h.destroyWindowOnEglThread();
        h.idle();
        assertEquals("op=40 attach=7 created=7 detach=0 expected=sceneCore", h.egl());

        // The acknowledged generation is the request current when eglDestroySurface() returns.
        h.setRequestedDetach(9);
        h.duringDestroy = () -> h.setRequestedDetach(10);
        h.destroyWindowOnEglThread();
        assertEquals("op=40 attach=7 created=7 detach=10 expected=sceneCore", h.egl());
        h.idle();
        assertEquals("op=40 attach=7 created=0 detach=0 expected=null", h.egl());
    }

    @Test
    public void rendererCallbacksCaptureTheAttachmentCreatedBeforeTheCallbackOnTheEglThread() {
        h.container.switchToClientSbs(true, false, result -> { });
        h.completeDecoderBind(true);
        h.events.clear();

        // Before the factory created the window, a ready callback pairs with no attachment.
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.createWindowOnEglThread();
        h.idle();
        assertEquals(Collections.singletonList(
                "egl.create sceneCore | op=41 attach=41 created=0 detach=0 expected=sceneCore"),
                h.events);

        // The generation is read on the EGL thread at the callback, not when main runs it.
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.setCreatedAttach(99);
        h.idle();
        assertEquals(Arrays.asList(
                "egl.create sceneCore | op=41 attach=41 created=0 detach=0 expected=sceneCore",
                "presenter.onClientSbsOutputCapabilityChanged"
                        + " | op=41 attach=41 created=99 detach=0 expected=sceneCore",
                "decoder.bind rendererInput"
                        + " | op=41 attach=41 created=99 detach=0 expected=sceneCore"),
                h.events);
    }

    @Test
    public void validationFailureCapturesTheCreatedGenerationAndFailsOnlyItsOwner() {
        List<Boolean> results = new ArrayList<>();
        h.container.switchToClientSbs(true, false, results::add);
        h.completeDecoderBind(true);
        h.createWindowOnEglThread();
        h.events.clear();

        h.setCreatedAttach(0);
        h.validationFailedOnEglThread(1, "stale");
        h.setCreatedAttach(41);
        h.idle();
        assertTrue(results.isEmpty());
        assertTrue(h.events.isEmpty());

        h.validationFailedOnEglThread(1, "mismatch");
        h.setCreatedAttach(0);
        h.idle();
        assertEquals(Collections.singletonList(false), results);
        assertEquals(Collections.singletonList(
                "decoder.cancelPending | op=41 attach=41 created=0 detach=0 expected=sceneCore"),
                h.events);
    }

    @Test
    public void classifyRequiresTheExactRequestedAttachmentForTheExpectedSurface() {
        h.container.switchToClientSbs(true, false, result -> { });
        h.completeDecoderBind(true);

        assertEquals("ENABLE", h.classify(1, 41));
        assertEquals("NONE", h.classify(1, 40));
        assertEquals("NONE", h.classify(1, 0));
        assertEquals("NONE", h.classify(2, 41));
        h.videoSurface = h.resizedSceneCore;
        assertEquals("NONE", h.classify(1, 41));
        h.videoSurface = h.sceneCore;
        XrStreamPresenter presenter = ReflectionHelpers.getField(h.container, "mXrPresenter");
        ReflectionHelpers.setField(h.container, "mXrPresenter", null);
        assertEquals("NONE", h.classify(1, 41));
        ReflectionHelpers.setField(h.container, "mXrPresenter", presenter);
        ReflectionHelpers.setField(h.container, "mDestroyed", true);
        assertEquals("NONE", h.classify(1, 41));
        ReflectionHelpers.setField(h.container, "mDestroyed", false);
        assertEquals("ENABLE", h.classify(1, 41));
    }

    @Test
    public void classifyNamesRecoveryOnlyWithNeitherOwnerPendingAndResizeOnlyAfterItsAttach() {
        h.enterClientSbs();
        assertEquals("RECOVERY", h.classify(1, 41));
        assertEquals("NONE", h.classify(2, 41));
        org.mockito.Mockito.when(h.renderer.isClientSbs()).thenReturn(false);
        assertEquals("NONE", h.classify(1, 41));
        org.mockito.Mockito.when(h.renderer.isClientSbs()).thenReturn(true);

        // While the resize waits for its detach, the older attachment belongs to no owner.
        assertTrue(h.container.resizeClientSbsSurface(2560, 1440, result -> { }));
        assertEquals("NONE", h.classify(1, 41));
        h.destroyWindowOnEglThread();
        h.idle();
        assertEquals("RESIZE", h.classify(1, 42));
        assertEquals("NONE", h.classify(1, 41));
        assertEquals("NONE", h.classify(2, 42));
    }

    @Test
    public void classifyTestsEntryBeforeResize() {
        h.container.switchToClientSbs(true, false, result -> { });
        h.completeDecoderBind(true);
        ClientSbsResizeTransaction resize = h.resizeState();
        resize.pendingCallback = result -> { };
        resize.pendingGeneration = 41;
        resize.stage = ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH;
        ReflectionHelpers.setField(h.container, "mActiveClientSbsDecoderGeneration", 1);

        assertEquals("ENABLE", h.classify(1, 41));
    }

    @Test
    public void operationCounterIsSharedByTheSwitchAndTheResizeAndSkipsZero() {
        h.enterClientSbs();
        assertEquals(41, h.requestedAttach());
        List<Boolean> resized = h.resizeThroughAttach(2560, 1440);
        assertEquals(42, h.requestedAttach());
        h.createWindowOnEglThread();
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.idle();
        h.idle();
        h.resizeSwapProof.run();
        h.idle();
        assertEquals(Collections.singletonList(true), resized);

        h.container.switchToClientSbs(false, false, result -> { });
        h.completeDecoderBind(true);
        assertEquals(43, h.requestedDetach());
        h.destroyWindowOnEglThread();
        h.idle();
        h.completeDecoderBind(true);

        h.setOperation(Integer.MAX_VALUE);
        h.container.switchToClientSbs(true, false, result -> { });
        h.completeDecoderBind(true);
        assertEquals(1, h.operation());
        assertEquals(1, h.requestedAttach());
    }

    @Test
    public void resizeDetachesThenAttachesItsReplacementWithItsOwnGeneration() {
        h.enterClientSbs();
        List<Boolean> results = new ArrayList<>();
        assertTrue(h.container.resizeClientSbsSurface(2560, 1440, results::add));
        h.destroyWindowOnEglThread();
        h.idle();
        assertSame(h.window, h.createWindowOnEglThread());
        assertEquals(42, h.createdAttach());
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.idle();
        h.idle();
        assertTrue(results.isEmpty());
        h.resizeSwapProof.run();
        h.idle();

        assertEquals(Collections.singletonList(true), results);
        assertEquals(Arrays.asList(
                "renderer.suspendPresentationForLiveStreamResize 2560x1440"
                        + " | op=41 attach=41 created=41 detach=0 expected=sceneCore",
                "eglOwner.requestPause | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                "egl.destroy window | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                "renderer.prepareLiveStreamResize 2560x1440 packed 5120x1440"
                        + " | op=42 attach=41 created=0 detach=0 expected=null",
                "presenter.setClientSbsSurfaceSize 2560x1440"
                        + " | op=42 attach=41 created=0 detach=0 expected=null",
                "eglOwner.requestResume | op=42 attach=42 created=0 detach=0 expected=sceneCore2",
                "egl.create sceneCore2 | op=42 attach=42 created=0 detach=0 expected=sceneCore2",
                "presenter.onClientSbsOutputCapabilityChanged"
                        + " | op=42 attach=42 created=42 detach=0 expected=sceneCore2",
                // The decoder is already bound to the retained renderer input: no codec call.
                "renderer.completeLiveStreamResizeAfterSwap"
                        + " | op=42 attach=42 created=42 detach=0 expected=sceneCore2"),
                h.events);
    }

    @Test
    public void failedResizeCancelsTheDecoderThenZeroesTheAttachmentBeforeAbandoning() {
        h.enterClientSbs();
        h.clientSurfaceSizeResult = false;
        List<Boolean> results = h.resizeThroughAttach(2560, 1440);

        assertEquals(Collections.singletonList(false), results);
        assertEquals(Arrays.asList(
                "renderer.suspendPresentationForLiveStreamResize 2560x1440"
                        + " | op=41 attach=41 created=41 detach=0 expected=sceneCore",
                "eglOwner.requestPause | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                "egl.destroy window | op=42 attach=41 created=41 detach=42 expected=sceneCore",
                "renderer.prepareLiveStreamResize 2560x1440 packed 5120x1440"
                        + " | op=42 attach=41 created=0 detach=0 expected=null",
                "presenter.setClientSbsSurfaceSize 2560x1440"
                        + " | op=42 attach=41 created=0 detach=0 expected=null",
                "decoder.cancelPending | op=42 attach=41 created=0 detach=0 expected=null",
                "renderer.abandonPresentation | op=42 attach=0 created=0 detach=0 expected=null"),
                h.events);
    }

    @Test
    public void abandonZeroesTheAttachmentBeforeTheRendererAndKeepsTheOperationCounter() {
        h.enterClientSbs();
        h.container.abandonClientSbsPresentation();
        assertEquals(Collections.singletonList(
                "renderer.abandonPresentation | op=41 attach=0 created=0 detach=0 expected=null"),
                h.events);

        // A late factory call or renderer callback from the abandoned attachment has no owner.
        h.events.clear();
        assertSame(EGL10.EGL_NO_SURFACE, h.createWindowOnEglThread());
        h.setCreatedAttach(41);
        h.rendererReadyOnEglThread(h.rendererInput, 1);
        h.idle();
        assertTrue(h.events.isEmpty());

        h.container.switchToClientSbs(false, false, result -> { });
        h.completeDecoderBind(true);
        assertEquals(42, h.requestedDetach());
    }

    @Test
    public void teardownZeroesTheAttachmentBeforeTerminalRendererCleanupAndIgnoresLateAcks() {
        h.enterClientSbs();
        assertTrue(h.container.resizeClientSbsSurface(2560, 1440, result -> { }));
        h.events.clear();
        h.container.onDestroy();
        assertEquals(Collections.singletonList(
                "renderer.onSurfaceDestroyedAsync | op=42 attach=0 created=0 detach=0 expected=null"),
                h.events);

        h.events.clear();
        h.setRequestedDetach(42);
        h.destroyWindowOnEglThread();
        h.idle();
        // The late ack is ignored after teardown: no clear, no SceneCore resize, no decoder bind.
        assertEquals(Collections.singletonList(
                "egl.destroy window | op=42 attach=0 created=0 detach=42 expected=null"),
                h.events);
        assertEquals(42, h.requestedDetach());
        assertNull(h.expectedSurface());
        assertTrue(h.decoderBinds.isEmpty());
    }
}
