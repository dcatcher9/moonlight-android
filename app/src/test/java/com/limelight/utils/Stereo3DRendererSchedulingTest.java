package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Stereo3DRendererSchedulingTest {
    @Test
    public void refinementDoublesOnlyTheHorizontalWarpMapLattice() {
        assertEquals(2, Stereo3DRenderer.WARP_MAP_HORIZONTAL_SCALE);
        assertEquals(1, Stereo3DRenderer.WARP_MAP_VERTICAL_SCALE);
    }

    @Test
    public void liveResizeDiscardsPendingImageBeforeGenerationAdvance() {
        AtomicInteger generation = new AtomicInteger(7);
        StringBuilder order = new StringBuilder();

        int advancedGeneration = Stereo3DRenderer.advanceLiveResizeFrameBoundary(
                generation,
                () -> {
                    assertEquals(7, generation.get());
                    order.append("invalidate>");
                },
                () -> {
                    assertEquals(7, generation.get());
                    order.append("discard>");
                },
                () -> {
                    assertEquals(7, generation.get());
                    order.append("clear");
                });

        assertEquals("invalidate>discard>clear", order.toString());
        assertEquals(8, advancedGeneration);
        assertEquals(8, generation.get());
    }

    @Test
    public void failedLiveResizeDiscardDoesNotAdvanceGenerationOrClearPendingFrame() {
        AtomicInteger generation = new AtomicInteger(7);
        boolean[] cleared = {false};

        try {
            Stereo3DRenderer.advanceLiveResizeFrameBoundary(
                    generation,
                    () -> { },
                    () -> { throw new IllegalStateException("no EGL image"); },
                    () -> cleared[0] = true);
        } catch (IllegalStateException expected) {
            assertEquals("no EGL image", expected.getMessage());
        }

        assertEquals(7, generation.get());
        assertFalse(cleared[0]);
    }

    @Test
    public void staleResultReleaseCannotClearNewOverlapClaim() {
        AtomicLong claim = new AtomicLong(41L);

        assertTrue(Stereo3DRenderer.releaseInferenceClaimToken(claim, 41L));
        assertTrue(claim.compareAndSet(0L, 42L));
        assertFalse(Stereo3DRenderer.releaseInferenceClaimToken(claim, 41L));
        assertEquals(42L, claim.get());
    }

    @Test
    public void postSwapCaptureTicketBindsGenerationAndOutputAttachment() {
        long ticket = Stereo3DRenderer.postSwapCaptureTicket(9, 4);

        assertTrue(Stereo3DRenderer.isPostSwapCaptureTicketCurrent(ticket, 9, 4));
        assertFalse(Stereo3DRenderer.isPostSwapCaptureTicketCurrent(ticket, 10, 4));
        assertFalse(Stereo3DRenderer.isPostSwapCaptureTicketCurrent(ticket, 9, 5));
        assertFalse(Stereo3DRenderer.isPostSwapCaptureTicketCurrent(0L, 9, 4));
        assertEquals(0L, Stereo3DRenderer.postSwapCaptureTicket(0, 4));
        assertEquals(0L, Stereo3DRenderer.postSwapCaptureTicket(9, 0));
    }

    @Test
    public void decoderCallbackRequiresTheExactLiveRegistrationAndSurfaceGeneration() {
        assertTrue(Stereo3DRenderer.isFrameCallbackCurrent(
                41L, 41L, true, true, false, false, 7, 7));
        assertFalse(Stereo3DRenderer.isFrameCallbackCurrent(
                40L, 41L, true, true, false, false, 7, 7));
        assertFalse(Stereo3DRenderer.isFrameCallbackCurrent(
                41L, 41L, false, true, false, false, 7, 7));
        assertFalse(Stereo3DRenderer.isFrameCallbackCurrent(
                41L, 41L, true, false, false, false, 7, 7));
        assertFalse(Stereo3DRenderer.isFrameCallbackCurrent(
                41L, 41L, true, true, true, false, 7, 7));
        assertFalse(Stereo3DRenderer.isFrameCallbackCurrent(
                41L, 41L, true, true, false, true, 7, 7));
        assertFalse(Stereo3DRenderer.isFrameCallbackCurrent(
                41L, 41L, true, true, false, false, 6, 7));
    }

    @Test
    public void surfaceTextureCallbacksUseOneExplicitUrgentDisplayLooper() throws IOException {
        String source = rendererSource();
        String constructor = source.substring(
                source.indexOf("public Stereo3DRenderer(GLSurfaceView view,"),
                source.indexOf("/** Immutable copy of the processor's reused"));
        assertTrue(constructor.contains("new HandlerThread("));
        assertTrue(constructor.contains("Process.THREAD_PRIORITY_URGENT_DISPLAY"));
        assertTrue(constructor.contains("new Handler(frameCallbackThread.getLooper())"));
        assertTrue(constructor.contains("In Normal/Host modes this Looper remains idle"));

        String registration = source.substring(
                source.indexOf("private void registerFrameAvailableListener"),
                source.indexOf("private void invalidateFrameCallbackRegistration"));
        assertTrue(registration.contains(
                "texture.setOnFrameAvailableListener(listener, frameCallbackHandler)"));
        assertFalse(source.contains("setOnFrameAvailableListener(this)"));

        String surfaceCreation = source.substring(
                source.indexOf("private void onSurfaceCreatedLocked"),
                source.indexOf("private void logGlCapabilities"));
        String surfaceReplacement = source.substring(
                source.indexOf("private void onSurfaceChangedLocked"),
                source.indexOf("private boolean initializeFbo()"));
        assertTrue(surfaceCreation.contains(
                "registerFrameAvailableListener(videoSurfaceTexture)"));
        assertTrue(surfaceReplacement.contains(
                "registerFrameAvailableListener(videoSurfaceTexture)"));
    }

    @Test
    public void callbackBoundariesRetokenizeBeforeDiscardAndTerminalShutdownNeverJoins()
            throws IOException {
        String source = rendererSource();
        String callback = source.substring(
                source.indexOf("private void onFrameAvailable("),
                source.indexOf("static boolean isFrameCallbackCurrent"));
        assertEquals(2, occurrences(callback, "isFrameCallbackCurrent("));
        assertTrue(callback.contains("queueFrameDrain(surfaceTexture, callbackGeneration)"));
        assertFalse(callback.contains("GLES"));
        assertFalse(callback.contains("updateTexImage"));
        assertFalse(callback.contains("captureLatestFrameIfReady"));
        assertFalse(callback.contains("presentClientSbs"));

        String resize = source.substring(
                source.indexOf("private int discardPendingFrameAndAdvanceLiveResizeGeneration"),
                source.indexOf("static int advanceLiveResizeFrameBoundary"));
        assertTrue(resize.indexOf("registerFrameAvailableListener(texture)")
                < resize.indexOf("advanceLiveResizeFrameBoundary("));

        String hdrCommit = source.substring(
                source.indexOf("private void commitHdrInputTransitionOnGlThread"),
                source.indexOf("public boolean isHdrOutputCapable"));
        int hdrRegistration = hdrCommit.indexOf("registerFrameAvailableListener(texture)");
        int hdrDiscard = hdrCommit.indexOf("texture.updateTexImage()");
        int hdrCommitState = hdrCommit.indexOf("hdrInputTransition.commit(transitionGeneration)");
        assertTrue(hdrRegistration >= 0 && hdrRegistration < hdrDiscard);
        assertTrue(hdrDiscard < hdrCommitState);

        String shutdown = source.substring(
                source.indexOf("private void shutdownFrameCallbackThread"),
                source.indexOf("private void invalidateQueuedFrameDrain"));
        assertTrue(shutdown.contains(
                "frameCallbackThreadStopped.compareAndSet(false, true)"));
        assertTrue(shutdown.indexOf("invalidateFrameCallbackRegistration()")
                < shutdown.indexOf("frameCallbackThread.quitSafely()"));
        assertTrue(shutdown.contains("frameCallbackThread.quitSafely()"));
        assertFalse(shutdown.contains("join("));

        String terminalEntry = source.substring(
                source.indexOf("public void onSurfaceDestroyedAsync"),
                source.indexOf("private boolean awaitTerminalWorkerCleanup"));
        assertTrue(terminalEntry.contains("shutdownFrameCallbackThread()"));
    }

    @Test
    public void adoptedResultCannotStartNextCaptureBeforePresentation() throws IOException {
        String source = Files.readString(new File(
                "src/main/java/com/limelight/utils/Stereo3DRenderer.java").toPath(),
                StandardCharsets.UTF_8);
        String draw = source.substring(
                source.indexOf("private void onDrawFrameLocked"),
                source.indexOf("private void scheduleClientSbsModeSwitchCompletionAfterSwap"));
        assertTrue(draw.contains("if (!resultAdopted)"));
        assertTrue(draw.indexOf("presentClientSbs();")
                < draw.indexOf("scheduleCaptureAfterSwap("));

        String inferenceAdoption = source.substring(
                source.indexOf("private boolean adoptLatestGpuInferenceResultLocked"),
                source.indexOf("private boolean adoptNearIdenticalReuseLocked"));
        String reuseAdoption = source.substring(
                source.indexOf("private boolean adoptNearIdenticalReuseLocked"),
                source.indexOf("private boolean closeGpuInferenceOnWorker"));
        assertFalse(inferenceAdoption.contains("captureLatestFrameIfReady();"));
        assertFalse(reuseAdoption.contains("captureLatestFrameIfReady();"));
    }

    @Test
    public void frameDrainHotPathReusesOneRunnableAndSnapshotsAGuardedTicket()
            throws IOException {
        String source = rendererSource();
        assertTrue(source.contains(
                "private final Runnable frameDrainRunnable = this::drainQueuedFrameWithoutSwap"));

        String enqueue = source.substring(
                source.indexOf("private void queueFrameDrain("),
                source.indexOf("private void drainQueuedFrameWithoutSwap()"));
        assertTrue(enqueue.contains("synchronized (frameLock)"));
        assertTrue(enqueue.contains("glSurfaceView.queueEvent(frameDrainRunnable)"));
        assertFalse(enqueue.contains("queueEvent(() ->"));
        assertFalse(enqueue.contains("new Runnable"));

        String drain = source.substring(
                source.indexOf("private void drainQueuedFrameWithoutSwap()"),
                source.indexOf("private void drainLatestFrameWithoutSwap("));
        assertTrue(drain.contains("synchronized (frameLock)"));
        assertTrue(drain.indexOf("token = queuedFrameDrainToken")
                < drain.indexOf("clearQueuedFrameDrainTicketLocked()"));
        assertTrue(drain.indexOf("clearQueuedFrameDrainTicketLocked()")
                < drain.indexOf("drainLatestFrameWithoutSwap("));
    }

    @Test
    public void packedSingleDrawRequiresTheFullViewportToFit() {
        assertTrue(Stereo3DRenderer.packedSingleDrawFitsViewport(7680, 16384));
        assertFalse(Stereo3DRenderer.packedSingleDrawFitsViewport(7680, 4096));
        assertFalse(Stereo3DRenderer.packedSingleDrawFitsViewport(0, 16384));
        assertFalse(Stereo3DRenderer.packedSingleDrawFitsViewport(7680, 0));
    }

    @Test
    public void performanceSamplesCannotCrossAStatsEpochBoundary() {
        assertTrue(Stereo3DRenderer.isPerformanceSamplingEpochCurrent(true, 12L, 12L));
        assertFalse(Stereo3DRenderer.isPerformanceSamplingEpochCurrent(true, 11L, 12L));
        assertFalse(Stereo3DRenderer.isPerformanceSamplingEpochCurrent(true, 0L, 12L));
        assertFalse(Stereo3DRenderer.isPerformanceSamplingEpochCurrent(false, 12L, 12L));
    }

    @Test
    public void unchangedDecodedSourceNeverExpiresMatchedPresentation() {
        assertFalse(Stereo3DRenderer.shouldPresentCurrentFlatForStaleDepth(
                41L, 41L, TimeUnit.HOURS.toNanos(1L)));
    }

    @Test
    public void newerDecodedSourceUsesStrictHostPresentationAgeBoundary() {
        long boundaryNs = Stereo3DRenderer.MAX_STALE_DEPTH_PRESENTATION_AGE_NS;

        assertFalse(Stereo3DRenderer.shouldPresentCurrentFlatForStaleDepth(
                41L, 42L, boundaryNs));
        assertTrue(Stereo3DRenderer.shouldPresentCurrentFlatForStaleDepth(
                41L, 42L, boundaryNs + 1L));
    }

    @Test
    public void modeEntryAcceptsFreshFrameAlreadyLatchedByQueuedDrain() {
        assertTrue(Stereo3DRenderer.hasFreshModeEntryFrame(false, true, 9, 9));
        assertFalse(Stereo3DRenderer.hasFreshModeEntryFrame(false, true, 8, 9));
        assertTrue(Stereo3DRenderer.hasFreshModeEntryFrame(true, false, 8, 9));
        assertFalse(Stereo3DRenderer.hasFreshModeEntryFrame(false, false, 9, 9));
    }

    @Test
    public void staleDepthWatchdogSchedulesAfterInclusiveBoundary() {
        assertEquals(251L, Stereo3DRenderer.staleDepthWatchdogDelayMillis(0L));
        assertEquals(1L, Stereo3DRenderer.staleDepthWatchdogDelayMillis(
                Stereo3DRenderer.MAX_STALE_DEPTH_PRESENTATION_AGE_NS));
    }

    @Test
    public void hdrTransitionBlocksUntilItsExactFreshFrameCommit() {
        Stereo3DRenderer.HdrInputTransitionState state =
                new Stereo3DRenderer.HdrInputTransitionState();

        int hdrGeneration = state.begin(true);
        assertTrue(state.isActive());
        assertTrue(state.isBlockingFrames());
        assertTrue(state.getTargetHdr());
        assertFalse(state.commit(hdrGeneration + 1));
        assertTrue(state.commit(hdrGeneration));
        assertFalse(state.isBlockingFrames());
        assertTrue(state.isCommitted(hdrGeneration));
        assertTrue(state.finish(hdrGeneration));
        assertFalse(state.isActive());
    }

    @Test
    public void newerHdrTransitionSupersedesStaleCommitAndCompletion() {
        Stereo3DRenderer.HdrInputTransitionState state =
                new Stereo3DRenderer.HdrInputTransitionState();

        int hdrGeneration = state.begin(true);
        int sdrGeneration = state.begin(false);
        assertFalse(state.getTargetHdr());
        assertFalse(state.commit(hdrGeneration));
        assertTrue(state.commit(sdrGeneration));
        assertFalse(state.finish(hdrGeneration));
        assertTrue(state.finish(sdrGeneration));
    }

    @Test
    public void shutdownControlWaitsForAFullQueueToDrain() throws Exception {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        assertTrue(queue.offer("frame"));
        CountDownLatch consumerStarted = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            consumerStarted.countDown();
            try {
                Thread.sleep(25L);
                queue.take();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        assertTrue(consumerStarted.await(1, TimeUnit.SECONDS));

        assertTrue(Stereo3DRenderer.offerControlMessage(
                queue, "shutdown", 1, TimeUnit.SECONDS));
        consumer.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(consumer.isAlive());
        assertEquals("shutdown", queue.poll());
    }

    @Test
    public void shutdownControlReportsAQueueThatNeverDrains() throws Exception {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        assertTrue(queue.offer("frame"));

        assertFalse(Stereo3DRenderer.offerControlMessage(
                queue, "shutdown", 1, TimeUnit.MILLISECONDS));
        assertEquals("frame", queue.poll());
    }

    private static String rendererSource() throws IOException {
        return Files.readString(new File(
                "src/main/java/com/limelight/utils/Stereo3DRenderer.java").toPath(),
                StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
