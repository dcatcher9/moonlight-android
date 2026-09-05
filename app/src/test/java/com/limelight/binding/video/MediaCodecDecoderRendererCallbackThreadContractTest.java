package com.limelight.binding.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class MediaCodecDecoderRendererCallbackThreadContractTest {
    @Test
    public void explicitUrgentDisplayHandlerExistsBeforeEveryCodecConfigure() throws Exception {
        String source = readRendererSource();
        String ensure = methodBody(
                source, "private Handler ensureFrameRenderedCallbackHandler()");
        String configure = methodBody(
                source, "private void configureAndStartDecoder(MediaFormat format)");

        assertTrue(ensure.contains("new HandlerThread("));
        assertTrue(ensure.contains("\"Video - Frame Rendered\""));
        assertTrue(ensure.contains("Process.THREAD_PRIORITY_URGENT_DISPLAY"));
        assertTrue(ensure.contains("new Handler(thread.getLooper())"));

        int ensureHandler = configure.indexOf("ensureFrameRenderedCallbackHandler()");
        int configureCodec = configure.indexOf("videoDecoder.configure(");
        int startCodec = configure.indexOf("videoDecoder.start()");
        int registerListener = configure.indexOf("callbackCodec.setOnFrameRenderedListener(");
        assertTrue(ensureHandler >= 0 && configureCodec > ensureHandler);
        assertTrue(startCodec > configureCodec && registerListener > startCodec);
        assertTrue(configure.substring(registerListener).contains(
                "callbackCodec, callbackEpoch, mediaCodec"));
        assertTrue(configure.substring(registerListener).contains(
                "frameRenderedHandler"));
        assertFalse(configure.substring(registerListener).contains(
                "presentationTimeUs, renderTimeNanos), null"));
    }

    @Test
    public void codecIdentityAndEpochRejectLateCallbacks() {
        assertTrue(MediaCodecDecoderRenderer.isFrameRenderedCallbackCurrent(
                7L, 7L, true, true, false));
        assertFalse(MediaCodecDecoderRenderer.isFrameRenderedCallbackCurrent(
                6L, 7L, true, true, false));
        assertFalse(MediaCodecDecoderRenderer.isFrameRenderedCallbackCurrent(
                7L, 7L, false, true, false));
        assertFalse(MediaCodecDecoderRenderer.isFrameRenderedCallbackCurrent(
                7L, 7L, true, false, false));
        assertFalse(MediaCodecDecoderRenderer.isFrameRenderedCallbackCurrent(
                7L, 7L, true, true, true));
    }

    @Test
    public void recoveryInvalidatesFrameworkCallbacksBeforeCodecLifecycleChanges()
            throws Exception {
        String source = readRendererSource();
        String invalidate = methodBody(
                source, "private void invalidateFrameRenderedCallbacks(MediaCodec decoder)");
        assertTrue(invalidate.indexOf("frameRenderedCallbackEpoch.incrementAndGet()")
                < invalidate.indexOf("decoder.setOnFrameRenderedListener(null, null)"));

        assertInvalidationPrecedes(source, "videoDecoder.stop()", "videoDecoder");
        assertInvalidationPrecedes(source, "videoDecoder.reset()", "videoDecoder");
        int recreation = source.indexOf("MediaCodec oldDecoder = videoDecoder");
        int recreateInvalidation = source.indexOf(
                "invalidateFrameRenderedCallbacks(oldDecoder)", recreation);
        int detach = source.indexOf("videoDecoder = null", recreation);
        assertTrue(recreation >= 0 && recreateInvalidation > recreation
                && detach > recreateInvalidation);
    }

    @Test
    public void teardownDropsLateCallbacksAndNeverJoinsCallbackThread() throws Exception {
        String source = readRendererSource();
        String callback = methodBody(
                source, "private void handleFrameRendered(MediaCodec registeredCodec,");
        String prepareForStop = methodBody(source, "public void prepareForStop()");
        String shutdown = methodBody(
                source, "private void shutdownFrameRenderedCallbackThread()");
        String cleanup = methodBody(source, "public void cleanup()");

        assertTrue(callback.contains("frameRenderedCallbackEpoch.get()"));
        assertTrue(callback.contains("registeredCodec == activeCodec"));
        assertTrue(prepareForStop.indexOf("stopping = true")
                < prepareForStop.indexOf("shutdownFrameRenderedCallbackThread()"));
        assertTrue(shutdown.contains("thread.quitSafely()"));
        assertFalse(shutdown.contains("join("));
        assertTrue(cleanup.contains("shutdownFrameRenderedCallbackThread()"));
    }

    @Test
    public void surfaceHandoffsUseDedicatedSerializedWorkerAndLifecycleEpoch() throws Exception {
        String source = readRendererSource();
        String ensure = methodBody(
                source, "private Handler ensureOutputSurfaceSwitchHandler()");
        String async = methodBody(
                source, "public OutputSurfaceSwitchRequest setOutputSurfaceAsync(");
        String switchSurface = methodBody(
                source, "private boolean switchDecoderOutputSurface(Surface surface,");
        String rollbackSurface = methodBody(
                source, "private boolean rollbackDecoderOutputSurface(");
        String commitToken = methodBody(
                source, "boolean enqueueCompletionAndCommit(BooleanSupplier enqueueCompletion)");
        String cancelAtDeadline = methodBody(
                source, "public boolean cancelAtDeadline()");
        String prepareForStop = methodBody(source, "public void prepareForStop()");
        String cleanup = methodBody(source, "public void cleanup()");

        assertTrue(ensure.contains("\"Video - Surface Handoff\""));
        assertTrue(ensure.contains("Process.THREAD_PRIORITY_DISPLAY"));
        assertFalse(ensure.contains("frameRenderedCallbackHandler"));
        assertTrue(async.indexOf("worker.post(")
                < async.indexOf("switchDecoderOutputSurface("));
        assertTrue(async.contains("surface, requestEpoch, ownerDeadlineMs"));
        assertTrue(async.contains("outputSurfaceSwitchEpoch.incrementAndGet()"));
        assertTrue(prepareForStop.indexOf("stopping = true")
                < prepareForStop.indexOf("shutdownOutputSurfaceSwitchThread()"));
        assertFalse(prepareForStop.contains("synchronized (codecRecoveryMonitor)"));
        assertTrue(prepareForStop.contains(
                "codecRecoveryType.set(CR_RECOVERY_TYPE_STOPPED)"));
        assertTrue(cleanup.indexOf("stopping = true")
                < cleanup.indexOf("synchronized (codecRecoveryMonitor)"));

        int nativeBind = switchSurface.indexOf("videoDecoder.setOutputSurface(surface)");
        int staleCheck = switchSurface.indexOf(
                "isOutputSurfaceSwitchCurrentBeforeDeadline(", nativeBind);
        int javaCommit = switchSurface.indexOf("renderTarget = surface", staleCheck);
        int finalDeadlineCheck = switchSurface.indexOf(
                "isOutputSurfaceSwitchCurrentBeforeDeadline(", javaCommit);
        assertTrue(nativeBind >= 0 && staleCheck > nativeBind);
        assertTrue(javaCommit > staleCheck);
        assertTrue(finalDeadlineCheck > javaCommit);
        assertTrue(switchSurface.contains("rollbackDecoderOutputSurface("));
        assertTrue(rollbackSurface.contains("videoDecoder.setOutputSurface(previousSurface)"));

        int commitTransaction = switchSurface.indexOf(
                "request.enqueueCompletionAndCommit(");
        int enqueue = switchSurface.indexOf(
                "modeTransitionHandler.postAtTime(", commitTransaction);
        assertTrue(commitTransaction > finalDeadlineCheck && enqueue > commitTransaction);
        assertTrue(commitToken.contains("synchronized (transitionLock)"));
        assertTrue(commitToken.indexOf("enqueueCompletion.getAsBoolean()")
                < commitToken.indexOf(
                "state.compareAndSet(STATE_PENDING, STATE_COMMITTED)"));
        assertTrue(cancelAtDeadline.contains("synchronized (transitionLock)"));
    }

    @Test
    public void terminalStopTokenCannotBeOverwrittenByLateRecoveryCompletion() throws Exception {
        String source = readRendererSource();
        String recovery = methodBody(
                source, "private boolean doCodecRecoveryIfRequired(int quiescenceFlag)");
        String cancel = methodBody(
                source, "private boolean finishCodecRecoveryCancellationIfStopping()");

        assertTrue(cancel.contains("CR_RECOVERY_TYPE_STOPPED"));
        assertTrue(cancel.contains("codecRecoveryMonitor.notifyAll()"));
        assertTrue(recovery.contains("finishCodecRecoveryCancellationIfStopping()"));
        assertTrue(recovery.contains("codecRecoveryType.compareAndSet("));
        assertFalse(recovery.contains("codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)"));
        assertTrue(recovery.contains("codecRecoveryType.get() != CR_RECOVERY_TYPE_STOPPED"));
    }

    @Test
    public void surfaceHandoffEpochRejectsTimeoutSupersessionAndStop() {
        assertTrue(MediaCodecDecoderRenderer.isOutputSurfaceSwitchCurrent(
                11L, 11L, false));
        assertFalse(MediaCodecDecoderRenderer.isOutputSurfaceSwitchCurrent(
                10L, 11L, false));
        assertFalse(MediaCodecDecoderRenderer.isOutputSurfaceSwitchCurrent(
                11L, 11L, true));
        assertTrue(MediaCodecDecoderRenderer.isOutputSurfaceSwitchCurrentBeforeDeadline(
                11L, 11L, false, 99L, 100L));
        assertFalse(MediaCodecDecoderRenderer.isOutputSurfaceSwitchCurrentBeforeDeadline(
                11L, 11L, false, 100L, 100L));
        assertFalse(MediaCodecDecoderRenderer.isOutputSurfaceSwitchCurrentBeforeDeadline(
                11L, 11L, false, 101L, 100L));
    }

    @Test
    public void deadlineCancellationWinsBeforeWorkerCanCommitQueuedCompletion() {
        MediaCodecDecoderRenderer.OutputSurfaceSwitchRequest request =
                new MediaCodecDecoderRenderer.OutputSurfaceSwitchRequest(21L);

        // A completion probe may run before the worker publishes COMMITTED and must do nothing.
        assertTrue(request.cancelAtDeadline());
        assertFalse(request.enqueueCompletionAndCommit(() -> true));
        assertFalse(request.tryDeliverCommitted());
    }

    @Test
    public void committedWorkerMakesDeadlineDeferToAlreadyQueuedSuccess() {
        MediaCodecDecoderRenderer.OutputSurfaceSwitchRequest request =
                new MediaCodecDecoderRenderer.OutputSurfaceSwitchRequest(22L);

        assertTrue(request.enqueueCompletionAndCommit(() -> true));
        assertFalse(request.cancelAtDeadline());
        assertTrue(request.tryDeliverCommitted());
        assertFalse(request.tryDeliverCommitted());
    }

    @Test
    public void supersessionCancelsCommittedButUndeliveredSuccess() {
        MediaCodecDecoderRenderer.OutputSurfaceSwitchRequest request =
                new MediaCodecDecoderRenderer.OutputSurfaceSwitchRequest(23L);

        assertTrue(request.enqueueCompletionAndCommit(() -> true));
        request.cancel();
        assertFalse(request.tryDeliverCommitted());
        assertTrue(request.cancelAtDeadline());
    }

    private static void assertInvalidationPrecedes(
            String source, String lifecycleOperation, String expectedCodecVariable) {
        int operation = source.indexOf(lifecycleOperation);
        assertTrue("Missing lifecycle operation: " + lifecycleOperation, operation >= 0);
        int invalidation = source.lastIndexOf(
                "invalidateFrameRenderedCallbacks(" + expectedCodecVariable + ")", operation);
        assertTrue("Callback invalidation must precede " + lifecycleOperation,
                invalidation >= 0 && invalidation < operation);
    }

    private static String readRendererSource() throws IOException {
        File file = new File(
                "src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java");
        assertTrue("MediaCodecDecoderRenderer source is missing", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int signatureStart = source.indexOf(signature);
        assertTrue("Missing method signature: " + signature, signatureStart >= 0);
        int bodyStart = source.indexOf('{', signatureStart + signature.length());
        assertTrue("Missing method body: " + signature, bodyStart >= 0);

        int depth = 0;
        for (int index = bodyStart; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return source.substring(bodyStart + 1, index);
            }
        }
        throw new AssertionError("Unterminated method body: " + signature);
    }
}
