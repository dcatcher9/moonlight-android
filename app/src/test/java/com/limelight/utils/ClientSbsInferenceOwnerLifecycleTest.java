package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.sbs.ClientSbsFrameSlots;
import com.limelight.ui.ClientSbsRenderSurface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Characterizes the Client SBS inference owner with a mock native engine: which thread creates,
 * runs and destroys the engine, how a run's fences and result cross between the GL thread and the
 * worker, the renderer-finish handshake (live and destroyed context), and terminal cleanup with
 * its retained-engine retry. Every assertion is about thread affinity or order.
 *
 * <p>{@link #owner()} names the object that holds the owner's state; everything else drives the
 * renderer's own lifecycle entry points.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ClientSbsInferenceOwnerLifecycleTest {
    private static final long TIMEOUT_MS = 5_000L;
    private static final String WORKER = "TestInferenceOwner";
    private static final String GL = "FakeGlThread";
    private static final String RETRY = "ClientSbsGpuCleanup";

    private ClientSbsRenderSurface surface;
    private Stereo3DRenderer renderer;
    private ClientSbsGpuInferenceEngine engine;
    private final AtomicBoolean engineInitialized = new AtomicBoolean();
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch cleaned = new CountDownLatch(1);
    private boolean teardownStarted;

    @Before
    public void setUp() throws Exception {
        surface = mock(ClientSbsRenderSurface.class);
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.width = 1920;
        prefs.height = 1080;
        try (MockedStatic<ClientSbsModelAssetCache> cache =
                     mockStatic(ClientSbsModelAssetCache.class)) {
            renderer = new Stereo3DRenderer(surface,
                    mock(Stereo3DRenderer.OnSurfaceReadyListener.class),
                    RuntimeEnvironment.getApplication(), prefs, false);
        }
        ReflectionHelpers.setField(renderer, "clientSbs", true);
        engine = mock(ClientSbsGpuInferenceEngine.class);
        when(engine.isInitialized()).thenAnswer(call -> engineInitialized.get());
        doAnswer(call -> {
            record("initialize");
            engineInitialized.set(true);
            return null;
        }).when(engine).initialize(any(), any());
        doAnswer(call -> {
            record("close(" + call.getArgument(0) + "," + call.getArgument(1) + ","
                    + call.getArgument(2) + ")");
            return true;
        }).when(engine).close(anyLong(), anyLong(), anyBoolean());
        doAnswer(call -> {
            AtomicReference<?> result = field("latestGpuInferenceResult");
            record(result.get() != null ? "render(result)" : "render");
            return null;
        }).when(surface).requestRender();
    }

    @After
    public void tearDown() throws Exception {
        if (!teardownStarted) {
            terminalTeardown();
        }
    }

    /** The object holding the inference owner's fields, nested classes and methods. */
    private Object owner() {
        return renderer;
    }

    @Test
    public void workerInitializesAndClosesTheEngineOnItsOwnThread() throws Exception {
        startWorker();
        awaitEvent("initialize@" + WORKER);
        // The backend is published after the engine and before this draw request.
        awaitEvent("render@" + WORKER);
        assertSame(engine, field("gpuInferenceEngine"));

        verify(engine).initialize(RuntimeEnvironment.getApplication(),
                ReflectionHelpers.getField(renderer, "aiModel"));
        ClientSbsModelManifest model = ReflectionHelpers.getField(renderer, "aiModel");
        assertEquals(model.getGpuExecutionPolicy().getBackendId() + " | " + model.getId()
                        + " " + model.getInputWidth() + "x" + model.getInputHeight(),
                renderer.getClientSbsBackendStatus());
        assertNull(field("pendingGpuInferenceEngine"));
        assertTrue(events.indexOf("render@" + WORKER) > events.indexOf("initialize@" + WORKER));

        terminalTeardown();

        assertOrdered("close(0,0,false)@" + WORKER, "complete");
        verify(engine, never()).retryCloseOnCurrentWorker(anyBoolean());
        verify(surface, never()).queueEvent(any());
        assertNull(field("gpuInferenceEngine"));
        assertEquals("Unavailable", renderer.getClientSbsBackendStatus());
    }

    @Test
    public void initializationFailureClosesTheShellOnTheWorkerWithoutARendererFinish()
            throws Exception {
        doAnswer(call -> {
            record("initialize");
            throw new IOException("model unavailable");
        }).when(engine).initialize(any(), any());

        startWorker();
        awaitEvent("close(0,0,false)@" + WORKER);
        await(() -> field("aiTaskCleanupSucceeded"), "owner cleanup result");

        assertOrdered("initialize@" + WORKER, "close(0,0,false)@" + WORKER);
        assertEquals("Unavailable", renderer.getClientSbsBackendStatus());
        assertTrue(this.<AtomicBoolean>field("gpuShutdownRequested").get());
        assertFalse(this.<AtomicBoolean>field("gpuFailureNeedsRendererFinish").get());
        assertNull(field("gpuInferenceEngine"));
        verify(surface, never()).queueEvent(any());

        terminalTeardown();
        assertOrdered("close(0,0,false)@" + WORKER, "complete");
        verify(engine).close(0L, 0L, false);
    }

    @Test
    public void runTransfersBothFencesAndPublishesTheResultBeforeRequestingADraw()
            throws Exception {
        when(engine.run(anyInt(), anyLong(), anyLong(), anyBoolean(), anyInt(), anyInt(),
                anyLong(), anyInt())).thenAnswer(call -> {
                    record("run");
                    return 77L;
                });
        when(engine.getLastRunDisposition(0))
                .thenReturn(ClientSbsGpuInferenceEngine.RunDisposition.INFER);
        ClientSbsFrameSlots slots = ReflectionHelpers.getField(renderer, "colorFrameSlots");
        doAnswer(call -> {
            record("close(" + call.getArgument(0) + "," + call.getArgument(1) + ","
                    + call.getArgument(2) + ") slot0=" + slots.getState(0)
                    + " claim=" + this.<AtomicLong>field("inferenceClaim").get());
            return true;
        }).when(engine).close(anyLong(), anyLong(), anyBoolean());
        startWorker();
        awaitEvent("initialize@" + WORKER);

        long claim = ReflectionHelpers.callInstanceMethod(owner(), "tryClaimInference");
        ClientSbsFrameSlots.Lease lease = captureLease(slots);
        offer(renderResult(11L, 12L, 0, lease, claim));
        await(() -> this.<AtomicReference<?>>field("latestGpuInferenceResult").get() != null,
                "result publication");

        verify(engine).run(0, 11L, 12L, false, 0, 0, 0L, 0);
        Object result = this.<AtomicReference<?>>field("latestGpuInferenceResult").get();
        assertEquals(77L, (long) ReflectionHelpers.getField(result, "outputReadyFence"));
        assertEquals(0, (int) ReflectionHelpers.getField(result, "bufferSlot"));
        assertSame(lease, ReflectionHelpers.getField(result, "colorFrameLease"));
        assertEquals(1, (int) ReflectionHelpers.getField(result, "generation"));
        assertEquals(claim, (long) ReflectionHelpers.getField(result, "inferenceClaimToken"));
        assertFalse(ReflectionHelpers.getField(result, "reusedDepth"));
        awaitEvent("render(result)@" + WORKER);
        assertOrdered("run@" + WORKER, "render(result)@" + WORKER);
        assertEquals(ClientSbsFrameSlots.State.INFERENCE, slots.getState(0));
        assertEquals(claim, this.<AtomicLong>field("inferenceClaim").get());

        // Terminal cleanup folds the unadopted ready fence into slot zero's close fence after
        // releasing its color lease and claim.
        terminalTeardown();
        assertOrdered("close(77,0,false) slot0=FREE claim=0@" + WORKER, "complete");
        assertNull(this.<AtomicReference<?>>field("latestGpuInferenceResult").get());
    }

    @Test
    public void runFailureUsesTheDestroyedRendererContextAsItsFinish() throws Exception {
        when(engine.run(anyInt(), anyLong(), anyLong(), anyBoolean(), anyInt(), anyInt(),
                anyLong(), anyInt())).thenAnswer(call -> {
                    record("run");
                    throw new IllegalStateException("GPU lost");
                });
        ClientSbsFrameSlots slots = ReflectionHelpers.getField(renderer, "colorFrameSlots");
        ClientSbsSlotFenceLedger ledger = field("gpuSlotFences");
        assertTrue(ledger.storeConsumedFence(1, 55L));
        // Context-loss recovery found the renderer's previous context destroyed.
        ReflectionHelpers.setField(owner(), "rendererOwnerContextDestroyed", true);
        startWorker();
        awaitEvent("initialize@" + WORKER);

        long claim = ReflectionHelpers.callInstanceMethod(owner(), "tryClaimInference");
        offer(renderResult(11L, 0L, 0, captureLease(slots), claim));
        awaitEvent("close(0,0,true)@" + WORKER);
        await(() -> field("aiTaskCleanupSucceeded"), "owner cleanup result");

        assertOrdered("run@" + WORKER, "close(0,0,true)@" + WORKER);
        verify(surface, never()).queueEvent(any());
        assertTrue(this.<AtomicBoolean>field("rendererFinishConfirmed").get());
        assertTrue(this.<AtomicBoolean>field("gpuFailureNeedsRendererFinish").get());
        assertEquals(0L, ledger.fence(1));
        assertEquals(ClientSbsFrameSlots.State.FREE, slots.getState(0));
        assertEquals(0L, this.<AtomicLong>field("inferenceClaim").get());
        assertEquals("Unavailable", renderer.getClientSbsBackendStatus());

        terminalTeardown();
        assertOrdered("close(0,0,true)@" + WORKER, "complete");
    }

    @Test
    public void gpuShutdownClosesOnlyAfterTheLiveRendererContextFinishes() throws Exception {
        EGLContext ownerContext = mock(EGLContext.class);
        ClientSbsSlotFenceLedger ledger = field("gpuSlotFences");
        assertTrue(ledger.storeConsumedFence(1, 55L));
        ReflectionHelpers.setField(owner(), "rendererOwnerContext", ownerContext);
        try (FakeGlThread gl = new FakeGlThread(ownerContext, events)) {
            doAnswer(call -> {
                record("queueEvent");
                gl.post(call.getArgument(0));
                return null;
            }).when(surface).queueEvent(any());
            startWorker();
            awaitEvent("initialize@" + WORKER);

            // Strict-route failures request shutdown from the GL thread.
            gl.post(() -> ReflectionHelpers.callInstanceMethod(owner(), "requestGpuShutdown",
                    ClassParameter.from(String.class, "strict route unavailable")));
            awaitEvent("close(0,0,true)@" + WORKER);
            await(() -> field("aiTaskCleanupSucceeded"), "owner cleanup result");
        }

        assertOrdered("queueEvent@" + WORKER, "glFinish@" + GL);
        assertOrdered("glFinish@" + GL, "deleteSync:55@" + GL);
        assertOrdered("deleteSync:55@" + GL, "close(0,0,true)@" + WORKER);
        assertTrue(this.<AtomicBoolean>field("rendererFinishConfirmed").get());
        assertNull(this.<AtomicReference<?>>field("rendererFinishRequest").get());
        assertEquals("Unavailable", renderer.getClientSbsBackendStatus());

        terminalTeardown();
        assertOrdered("close(0,0,true)@" + WORKER, "complete");
    }

    @Test
    public void failedOwnerCloseIsRetriedOnTheCleanupThreadBeforeCompletion() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        doAnswer(call -> {
            record("close");
            return closes.incrementAndGet() >= 2;
        }).when(engine).close(anyLong(), anyLong(), anyBoolean());
        when(engine.retryCloseOnCurrentWorker(anyBoolean())).thenAnswer(call -> {
            record("retry");
            return false;
        });
        startWorker();
        awaitEvent("initialize@" + WORKER);

        terminalTeardown();

        List<String> closing = new ArrayList<>();
        synchronized (events) {
            for (String event : events) {
                if (event.startsWith("close@") || event.startsWith("retry@")
                        || event.equals("complete")) {
                    closing.add(event);
                }
            }
        }
        assertEquals(List.of("close@" + WORKER, "retry@" + WORKER, "retry@" + WORKER,
                "close@" + RETRY, "complete"), closing);
        assertEquals(2, this.<AtomicInteger>field("aiTaskOwnership").get());
        assertNull(field("gpuInferenceEngine"));
        assertNull(field("executorService"));
    }

    @Test
    public void contextReplacementJoinsThePreviousOwnerAfterClearingItsClaim()
            throws Exception {
        doAnswer(call -> {
            record("close(" + call.getArgument(0) + "," + call.getArgument(1) + ","
                    + call.getArgument(2) + ") claim="
                    + this.<AtomicLong>field("inferenceClaim").get());
            return true;
        }).when(engine).close(anyLong(), anyLong(), anyBoolean());
        startWorker();
        awaitEvent("initialize@" + WORKER);
        long claim = ReflectionHelpers.callInstanceMethod(owner(), "tryClaimInference");
        assertTrue(claim != 0L);

        boolean stopped = ReflectionHelpers.callInstanceMethod(renderer, "stopAiWorkers");

        assertTrue(stopped);
        assertTrue(events.contains("close(0,0,false) claim=0@" + WORKER));
        assertNull(field("executorService"));
        assertNull(field("gpuInferenceEngine"));
        assertTrue(field("aiTaskCleanupSucceeded"));
        assertTrue(this.<AtomicBoolean>field("shuttingDown").get());
    }

    @Test
    public void shellWhoseWorkerNeverRanIsClosedOnTheCleanupThread() throws Exception {
        ReflectionHelpers.setField(owner(), "pendingGpuInferenceEngine", engine);
        this.<AtomicInteger>field("aiTaskOwnership").set(0);
        ReflectionHelpers.setField(owner(), "aiTaskCleanupSucceeded", false);

        boolean stopped = ReflectionHelpers.callInstanceMethod(renderer, "stopAiWorkers");

        assertTrue(stopped);
        assertEquals(List.of("close(0,0,false)@" + RETRY), new ArrayList<>(events));
        assertEquals(2, this.<AtomicInteger>field("aiTaskOwnership").get());
        assertNull(field("pendingGpuInferenceEngine"));
        verify(engine, never()).initialize(any(), any());
    }

    /** Installs the shell and starts its worker as the replacement GL context does. */
    private void startWorker() throws Exception {
        Object owner = owner();
        ReflectionHelpers.setField(owner, "inferenceInputQueue", new ArrayBlockingQueue<>(1));
        ReflectionHelpers.setField(owner, "pendingGpuInferenceEngine", engine);
        this.<AtomicInteger>field("aiTaskOwnership").set(0);
        ReflectionHelpers.setField(owner, "aiTaskCleanupSucceeded", false);
        ExecutorService workers = Executors.newSingleThreadExecutor(
                task -> new Thread(task, WORKER));
        ReflectionHelpers.setField(owner, "executorService", workers);
        Class<?> taskType = Class.forName(owner.getClass().getName() + "$AiTask");
        Constructor<?> constructor = taskType.getDeclaredConstructor(owner.getClass());
        constructor.setAccessible(true);
        workers.execute((Runnable) constructor.newInstance(owner));
    }

    private void terminalTeardown() throws Exception {
        teardownStarted = true;
        renderer.onSurfaceDestroyedAsync(Runnable::run, () -> {
            events.add("complete");
            cleaned.countDown();
        });
        assertTrue("terminal cleanup did not complete",
                cleaned.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private ClientSbsFrameSlots.Lease captureLease(ClientSbsFrameSlots slots) {
        ClientSbsFrameSlots.Lease lease = slots.tryAcquireForCapture(1, 5L, 1_000L);
        assertNotNull(lease);
        assertEquals(0, lease.getSlot());
        assertTrue(slots.markInference(lease));
        return lease;
    }

    private Object renderResult(long inputReadyFence, long previousOutputConsumedFence,
                                int slot, ClientSbsFrameSlots.Lease lease, long claim)
            throws Exception {
        Class<?> type = Class.forName(owner().getClass().getName() + "$RenderResult");
        return ReflectionHelpers.callConstructor(type,
                ClassParameter.from(long.class, inputReadyFence),
                ClassParameter.from(long.class, previousOutputConsumedFence),
                ClassParameter.from(int.class, slot),
                ClassParameter.from(ClientSbsFrameSlots.Lease.class, lease),
                ClassParameter.from(long.class, claim),
                ClassParameter.from(com.limelight.sbs.ClientSbsGpuNearReuseClassifier.class, null),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(int.class, 0),
                ClassParameter.from(int.class, 0),
                ClassParameter.from(long.class, 0L),
                ClassParameter.from(int.class, 0));
    }

    @SuppressWarnings("unchecked")
    private void offer(Object task) {
        BlockingQueue<Object> queue = field("inferenceInputQueue");
        assertTrue(queue.offer(task));
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) {
        if ("shuttingDown".equals(name)) {
            return ReflectionHelpers.getField(renderer, name);
        }
        return ReflectionHelpers.getField(owner(), name);
    }

    private void record(String event) {
        events.add(event + "@" + Thread.currentThread().getName());
    }

    private void awaitEvent(String event) throws InterruptedException {
        await(() -> events.contains(event), event);
    }

    private static void await(BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for " + what);
            }
            Thread.sleep(5L);
        }
    }

    private void assertOrdered(String first, String second) {
        List<String> snapshot = new ArrayList<>(events);
        int firstIndex = snapshot.indexOf(first);
        int secondIndex = snapshot.indexOf(second);
        assertTrue("Missing " + first + " in " + snapshot, firstIndex >= 0);
        assertTrue("Missing " + second + " in " + snapshot, secondIndex >= 0);
        assertTrue(first + " must precede " + second + " in " + snapshot,
                firstIndex < secondIndex);
    }

    /** A stand-in GL thread whose static GL/EGL mocks are active only on that thread. */
    private static final class FakeGlThread implements AutoCloseable {
        private final ExecutorService executor =
                Executors.newSingleThreadExecutor(task -> new Thread(task, GL));
        private MockedStatic<EGL14> egl;
        private MockedStatic<GLES20> gles20;
        private MockedStatic<GLES30> gles30;

        FakeGlThread(EGLContext current, List<String> events) throws Exception {
            executor.submit(() -> {
                egl = mockStatic(EGL14.class);
                gles20 = mockStatic(GLES20.class);
                gles30 = mockStatic(GLES30.class);
                egl.when(EGL14::eglGetCurrentContext).thenReturn(current);
                gles20.when(GLES20::glFinish).thenAnswer(call -> {
                    events.add("glFinish@" + Thread.currentThread().getName());
                    return null;
                });
                gles30.when(() -> GLES30.glDeleteSync(anyLong())).thenAnswer(call -> {
                    events.add("deleteSync:" + call.getArgument(0) + "@"
                            + Thread.currentThread().getName());
                    return null;
                });
                return null;
            }).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        void post(Runnable event) {
            executor.execute(event);
        }

        @Override
        public void close() throws Exception {
            executor.submit(() -> {
                gles30.close();
                gles20.close();
                egl.close();
                return null;
            }).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            executor.shutdownNow();
        }
    }
}
