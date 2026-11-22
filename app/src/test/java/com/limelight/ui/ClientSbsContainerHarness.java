package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Stereo3DRenderer;

import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * A StreamContainer wired by its own production {@code init()}. XrStreamPresenter,
 * ClientSbsRenderSurface and Stereo3DRenderer are construction mocks, so {@link #factory} and
 * {@link #listener} are exactly the EGL window-surface factory and renderer listener init() hands
 * to them. Factory and renderer-listener calls run on a dedicated "ClientSbsEgl" thread, as on the
 * device; everything else runs on the paused main Looper.
 *
 * <p>Every collaborator call is recorded in {@link #events} as {@code "<call> | <egl()>"}, where
 * {@link #egl()} is the EGL attachment state at that moment. {@link #eglState()},
 * {@link #EGL_FIELDS} and {@link #resizeState()} are the resolvers that name where that state
 * lives; nothing else reaches into the container's fields for it.</p>
 */
final class ClientSbsContainerHarness implements AutoCloseable {
    /** The container's Game, recording the decoder-handoff calls StreamContainer makes. */
    public static final class TestGame extends Game {
        ClientSbsContainerHarness harness;

        @Override
        public DecoderOutputSurfaceSwitchRequest setDecoderOutputSurfaceAsync(
                Surface surface, long ownerDeadlineMs,
                StreamContainer.SurfaceSwitchCallback callback) {
            harness.record("decoder.bind " + harness.name(surface));
            harness.decoderBinds.add(callback);
            DecoderOutputSurfaceSwitchRequest request =
                    mock(DecoderOutputSurfaceSwitchRequest.class);
            when(request.cancelAtDeadline()).thenAnswer(call -> harness.decoderCancelAtDeadline);
            return request;
        }

        @Override
        public void cancelPendingDecoderOutputSurfaceSwitches() {
            harness.record("decoder.cancelPending");
        }

        @Override
        public void handleDecoderSurfaceSwitchFailure() {
            harness.record("game.handleDecoderSurfaceSwitchFailure");
        }

        @Override
        public boolean setDecoderOutputSurfaceForGlRecovery(Surface surface) {
            harness.record("decoder.recoveryPark " + harness.name(surface));
            return true;
        }
    }

    /** The production container is attached, so its View.post() reaches the main Looper. */
    static final class PostingStreamContainer extends StreamContainer {
        private final Handler main = new Handler(Looper.getMainLooper());

        PostingStreamContainer(Context context) {
            super(context, null);
        }

        @Override
        public boolean post(Runnable action) {
            return main.post(action);
        }
    }

    /** Resolver: the object that holds the Client SBS EGL attachment state. */
    Object eglState() {
        return ReflectionHelpers.getField(container, "mEglAttachment");
    }

    /** Resolver: that state's field names, in the order operation, attach, created, detach, surface. */
    private static final String[] EGL_FIELDS = {"operationGeneration",
            "requestedAttachGeneration", "createdAttachGeneration",
            "requestedDetachGeneration", "expectedOutputSurface"};

    /** Resolver: the live Client SBS resize transaction. */
    ClientSbsResizeTransaction resizeState() {
        return ReflectionHelpers.getField(container, "mClientSbsResize");
    }

    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final TestGame game;
    final PostingStreamContainer container;
    final XrStreamPresenter presenter;
    final Stereo3DRenderer renderer;
    final ClientSbsRenderSurface eglOwner;
    final GLSurfaceView.EGLWindowSurfaceFactory factory;
    final Stereo3DRenderer.OnSurfaceReadyListener listener;

    final EGL10 egl = mock(EGL10.class);
    final EGLDisplay display = mock(EGLDisplay.class);
    final EGLConfig config = mock(EGLConfig.class);
    final EGLSurface window = mock(EGLSurface.class);

    private final Map<Object, String> names = Collections.synchronizedMap(new IdentityHashMap<>());
    final Surface dummy = validSurface("dummy");
    final Surface sceneCore = validSurface("sceneCore");
    final Surface resizedSceneCore = validSurface("sceneCore2");
    final Surface rendererInput = validSurface("rendererInput");

    /** What the presenter publishes as its SceneCore video surface. */
    volatile Surface videoSurface = sceneCore;
    /** What a Client SBS SceneCore resize publishes. */
    volatile Surface videoSurfaceAfterResize = resizedSceneCore;
    volatile boolean clientSurfaceSizeResult = true;
    volatile boolean suspendResult = true;
    volatile boolean prepareLiveStreamResizeResult = true;
    /** False: a queued decoder bind already owns completion when an owner deadline fires. */
    volatile boolean decoderCancelAtDeadline = true;
    volatile boolean completeAfterSwapResult = true;
    volatile boolean backendInitializing;
    volatile EGLSurface createResult = window;
    volatile boolean destroyResult = true;
    /** Runs inside eglCreateWindowSurface()/eglDestroySurface(), on the EGL thread. */
    volatile Runnable duringCreate;
    volatile Runnable duringDestroy;

    /** Decoder binds in the order DecoderSurfaceHandoff queued them; tests complete them. */
    final List<StreamContainer.SurfaceSwitchCallback> decoderBinds = new ArrayList<>();
    /** The renderer's live-resize swap-proof completion, as the container armed it. */
    volatile Runnable resizeSwapProof;
    /** The decoder surface generation the container last prepared for the renderer. */
    volatile int preparedDecoderGeneration;

    /** LimeLog output while the harness is open, as {@code "<LEVEL> <message>"}. */
    final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final Logger limeLog = Logger.getLogger(LimeLog.class.getName());
    private final java.util.logging.Handler logCapture = new java.util.logging.Handler() {
        @Override public void publish(LogRecord record) {
            logs.add(record.getLevel() + " " + record.getMessage());
        }
        @Override public void flush() { }
        @Override public void close() { }
    };

    private final ExecutorService eglThread =
            Executors.newSingleThreadExecutor(task -> new Thread(task, "ClientSbsEgl"));
    private final MockedConstruction<XrStreamPresenter> presenters;
    private final MockedConstruction<ClientSbsRenderSurface> surfaces;
    private final MockedConstruction<Stereo3DRenderer> renderers;

    ClientSbsContainerHarness() {
        limeLog.addHandler(logCapture);
        names.put(window, "window");
        game = Robolectric.buildActivity(TestGame.class).get();
        game.harness = this;
        Context context = RuntimeEnvironment.getApplication();
        container = new PostingStreamContainer(context);
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.width = 1920;
        prefs.height = 1080;
        AtomicReference<Stereo3DRenderer.OnSurfaceReadyListener> createdListener =
                new AtomicReference<>();
        presenters = mockConstruction(XrStreamPresenter.class,
                (mock, construction) -> when(mock.init()).thenReturn(true));
        surfaces = mockConstruction(ClientSbsRenderSurface.class);
        renderers = mockConstruction(Stereo3DRenderer.class,
                (mock, construction) -> createdListener.set(
                        (Stereo3DRenderer.OnSurfaceReadyListener) construction.arguments().get(1)));
        assertTrue(container.init(game, prefs));

        presenter = presenters.constructed().get(0);
        eglOwner = surfaces.constructed().get(0);
        renderer = renderers.constructed().get(0);
        listener = createdListener.get();
        ArgumentCaptor<GLSurfaceView.EGLWindowSurfaceFactory> createdFactory =
                ArgumentCaptor.forClass(GLSurfaceView.EGLWindowSurfaceFactory.class);
        verify(eglOwner).initialize(same(renderer), any(), createdFactory.capture(), any());
        // init() leaves the EGL owner paused until an explicit Client request.
        verify(eglOwner).requestPause();
        factory = createdFactory.getValue();
        // Robolectric's dummy Surface is not a valid producer; substitute a valid one.
        ReflectionHelpers.setField(container, "mDummySurface", dummy);
        stubCollaborators();
    }

    private void stubCollaborators() {
        when(presenter.getVideoSurface()).thenAnswer(call -> videoSurface);
        when(presenter.getClientSbsSurfaceWidth()).thenReturn(3840);
        when(presenter.getClientSbsSurfaceHeight()).thenReturn(1080);
        when(presenter.setClientSbsSurfaceSize(anyBoolean())).thenAnswer(call -> {
            record("presenter.setClientSbsSurfaceSize(" + call.getArgument(0) + ")");
            return true;
        });
        when(presenter.setClientSbsSurfaceSize(anyInt(), anyInt())).thenAnswer(call -> {
            record("presenter.setClientSbsSurfaceSize " + call.getArgument(0) + "x"
                    + call.getArgument(1));
            videoSurface = videoSurfaceAfterResize;
            return clientSurfaceSizeResult;
        });
        when(presenter.setHostSurfaceSize(anyBoolean())).thenAnswer(call -> {
            record("presenter.setHostSurfaceSize(" + call.getArgument(0) + ")");
            return true;
        });
        doAnswer(call -> {
            record("presenter.onClientSbsOutputCapabilityChanged");
            return null;
        }).when(presenter).onClientSbsOutputCapabilityChanged();

        when(renderer.isClientSbs()).thenReturn(true);
        when(renderer.isClientSbsBackendInitializing()).thenAnswer(call -> backendInitializing);
        when(renderer.prepareLiveStreamResize(anyInt(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    record("renderer.prepareLiveStreamResize " + call.getArgument(0) + "x"
                            + call.getArgument(1) + " packed " + call.getArgument(2) + "x"
                            + call.getArgument(3));
                    return prepareLiveStreamResizeResult;
                });
        doAnswer(call -> {
            preparedDecoderGeneration = call.getArgument(0);
            record("renderer.prepareDecoderSurfaceGeneration " + call.getArgument(0));
            return null;
        }).when(renderer).prepareDecoderSurfaceGeneration(anyInt());
        doAnswer(call -> {
            record("renderer.abandonPresentation");
            return null;
        }).when(renderer).abandonPresentation();
        when(renderer.suspendPresentationForLiveStreamResize(anyInt(), anyInt()))
                .thenAnswer(call -> {
                    record("renderer.suspendPresentationForLiveStreamResize "
                            + call.getArgument(0) + "x" + call.getArgument(1));
                    return suspendResult;
                });
        when(renderer.completeLiveStreamResizeAfterSwap(any())).thenAnswer(call -> {
            record("renderer.completeLiveStreamResizeAfterSwap");
            resizeSwapProof = call.getArgument(0);
            return completeAfterSwapResult;
        });
        when(renderer.requestLiveStreamResizeProofDraw()).thenAnswer(call -> {
            record("renderer.requestLiveStreamResizeProofDraw");
            return true;
        });
        doAnswer(call -> {
            record("renderer.onSurfaceDestroyedAsync");
            return null;
        }).when(renderer).onSurfaceDestroyedAsync(any(), any());

        doAnswer(call -> {
            record("eglOwner.requestPause");
            return null;
        }).when(eglOwner).requestPause();
        doAnswer(call -> {
            record("eglOwner.requestResume");
            return null;
        }).when(eglOwner).requestResume();

        when(egl.eglCreateWindowSurface(same(display), same(config), any(), any()))
                .thenAnswer(call -> {
                    record("egl.create " + name(call.getArgument(2))
                            + (call.getArgument(3) == null ? "" : " with attributes"));
                    Runnable hook = duringCreate;
                    if (hook != null) {
                        hook.run();
                    }
                    return createResult;
                });
        when(egl.eglDestroySurface(same(display), any())).thenAnswer(call -> {
            record("egl.destroy " + name(call.getArgument(1)));
            Runnable hook = duringDestroy;
            if (hook != null) {
                hook.run();
            }
            return destroyResult;
        });
    }

    Surface validSurface(String name) {
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        names.put(surface, name);
        return surface;
    }

    String name(Object value) {
        if (value == null) {
            return "null";
        }
        if (value == EGL10.EGL_NO_SURFACE) {
            return "EGL_NO_SURFACE";
        }
        String name = names.get(value);
        return name != null ? name : value.getClass().getSimpleName();
    }

    void record(String call) {
        events.add(call + " | " + egl());
    }

    /** The EGL attachment state, read through the resolvers. */
    String egl() {
        return "op=" + operation() + " attach=" + requestedAttach()
                + " created=" + createdAttach() + " detach=" + requestedDetach()
                + " expected=" + name(expectedSurface());
    }

    int operation() {
        return ReflectionHelpers.getField(eglState(), EGL_FIELDS[0]);
    }

    int requestedAttach() {
        return ReflectionHelpers.getField(eglState(), EGL_FIELDS[1]);
    }

    int createdAttach() {
        return ReflectionHelpers.getField(eglState(), EGL_FIELDS[2]);
    }

    int requestedDetach() {
        return ReflectionHelpers.getField(eglState(), EGL_FIELDS[3]);
    }

    Surface expectedSurface() {
        return ReflectionHelpers.getField(eglState(), EGL_FIELDS[4]);
    }

    void setOperation(int value) {
        ReflectionHelpers.setField(eglState(), EGL_FIELDS[0], value);
    }

    void setRequestedAttach(int value) {
        ReflectionHelpers.setField(eglState(), EGL_FIELDS[1], value);
    }

    void setCreatedAttach(int value) {
        ReflectionHelpers.setField(eglState(), EGL_FIELDS[2], value);
    }

    void setRequestedDetach(int value) {
        ReflectionHelpers.setField(eglState(), EGL_FIELDS[3], value);
    }

    void setExpectedSurface(Surface value) {
        ReflectionHelpers.setField(eglState(), EGL_FIELDS[4], value);
    }

    /** The Client mode switch generation, which is also the renderer's decoder generation. */
    int switchGeneration() {
        OwnedTransaction modeSwitch = ReflectionHelpers.getField(container, "mClientSbsSwitch");
        return modeSwitch.generation;
    }

    int activeDecoderGeneration() {
        return ReflectionHelpers.getField(container, "mActiveClientSbsDecoderGeneration");
    }

    /** The container's renderer-callback owner for a generation pair, by enum constant name. */
    String classify(int surfaceGeneration, int eglAttachGeneration) {
        Object owner = ReflectionHelpers.callInstanceMethod(container, "classifyRendererCallback",
                ReflectionHelpers.ClassParameter.from(int.class, surfaceGeneration),
                ReflectionHelpers.ClassParameter.from(int.class, eglAttachGeneration));
        return ((Enum<?>) owner).name();
    }

    void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    void idleFor(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** Completes the oldest queued decoder bind as the decoder worker would, then drains main. */
    void completeDecoderBind(boolean success) {
        assertTrue("No decoder bind is queued", !decoderBinds.isEmpty());
        decoderBinds.remove(0).onComplete(success);
        idle();
    }

    EGLSurface createWindowOnEglThread() {
        return onEglThread(() -> factory.createWindowSurface(egl, display, config, null));
    }

    void destroyWindowOnEglThread() {
        onEglThread(() -> {
            factory.destroySurface(egl, display, window);
            return null;
        });
    }

    void rendererReadyOnEglThread(Surface surface, int surfaceGeneration) {
        onEglThread(() -> {
            listener.onStereo3DSurfaceReady(surface, surfaceGeneration);
            return null;
        });
    }

    void validationFailedOnEglThread(int surfaceGeneration, String reason) {
        onEglThread(() -> {
            listener.onStereo3DOutputSurfaceValidationFailed(surfaceGeneration, reason);
            return null;
        });
    }

    /** Runs one EGL-owner call on the EGL thread and rethrows what it threw. */
    <T> T onEglThread(java.util.concurrent.Callable<T> call) {
        try {
            return eglThread.submit(call).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException) {
                throw (RuntimeException) error.getCause();
            }
            throw new AssertionError(error.getCause());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    /**
     * Drives a complete Client SBS entry: decoder park, attachment request and EGL resume, the
     * factory's window creation, the renderer's ready callback and the final decoder bind.
     */
    void enterClientSbs() {
        List<Boolean> results = new ArrayList<>();
        container.switchToClientSbs(true, false, results::add);
        completeDecoderBind(true);
        createWindowOnEglThread();
        rendererReadyOnEglThread(rendererInput, preparedDecoderGeneration);
        idle();
        completeDecoderBind(true);
        assertEquals(Collections.singletonList(true), results);
        events.clear();
    }

    /** Starts a live resize, delivers its exact EGL detach and runs the resulting attach request. */
    List<Boolean> resizeThroughAttach(int width, int height) {
        List<Boolean> results = new ArrayList<>();
        assertTrue(container.resizeClientSbsSurface(width, height, results::add));
        destroyWindowOnEglThread();
        idle();
        return results;
    }

    @Override
    public void close() {
        limeLog.removeHandler(logCapture);
        eglThread.shutdownNow();
        presenters.close();
        surfaces.close();
        renderers.close();
    }
}
