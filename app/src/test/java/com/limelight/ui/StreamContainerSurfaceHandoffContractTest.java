package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class StreamContainerSurfaceHandoffContractTest {
    @Test
    public void completionRequiresExactLiveGenerationAndTarget() {
        assertTrue(StreamContainer.isDecoderSurfaceHandoffCurrent(
                4, 4, true, false, true));
        assertFalse(StreamContainer.isDecoderSurfaceHandoffCurrent(
                3, 4, true, false, true));
        assertFalse(StreamContainer.isDecoderSurfaceHandoffCurrent(
                4, 4, false, false, true));
        assertFalse(StreamContainer.isDecoderSurfaceHandoffCurrent(
                4, 4, true, true, true));
        assertFalse(StreamContainer.isDecoderSurfaceHandoffCurrent(
                4, 4, true, false, false));
    }

    @Test
    public void ordinaryHandoffsAreAsyncBoundedAndTimeoutInvalidatesDecoder() throws Exception {
        String source = readSource("StreamContainer.java");
        String bind = methodBody(
                source, "private void bindDecoderSurfaceAsync(Surface surface, long ownerDeadlineMs,");
        String complete = methodBody(
                source, "private void completeDecoderSurfaceHandoff(int handoffGeneration,");

        assertTrue(bind.contains("game.setDecoderOutputSurfaceAsync("));
        assertTrue(bind.contains("surface, ownerDeadlineMs"));
        assertTrue(bind.contains("mDeadlineHandler.postAtTime("));
        assertTrue(bind.contains("ownerDeadlineMs"));
        assertTrue(bind.contains("mDecoderSurfaceHandoffRequest = queuedRequest"));
        assertFalse(bind.contains("setDecoderOutputSurfaceForGlRecovery"));
        assertTrue(complete.contains("isDecoderSurfaceHandoffCurrent("));
        assertTrue(complete.contains("cancelDecoderSurfaceHandoffAfterFailure()"));
    }

    @Test
    public void glRecoveryRemainsSynchronousAndInvalidatesOrdinaryOwnerFirst() throws Exception {
        String source = readSource("StreamContainer.java");
        String recovery = methodBody(source,
                "private boolean parkDecoderForClientSbsContextRecovery(Surface oldSurface,");

        int invalidation = recovery.indexOf("invalidateDecoderSurfaceHandoff(true)");
        int synchronousPark = recovery.indexOf(
                "game.setDecoderOutputSurfaceForGlRecovery(mDummySurface)");
        assertTrue(invalidation >= 0 && synchronousPark > invalidation);
        assertTrue(recovery.contains("mClientSbsContextRecoveryParked = true"));
    }

    @Test
    public void asyncQueuePublicationIsLinearizedWithGlRecoveryPark() throws Exception {
        String source = readSource("StreamContainer.java");
        String bind = methodBody(
                source, "private void bindDecoderSurfaceAsync(Surface surface, long ownerDeadlineMs,");
        String recovery = methodBody(source,
                "private boolean parkDecoderForClientSbsContextRecovery(Surface oldSurface,");

        int queue = bind.indexOf("game.setDecoderOutputSurfaceAsync(");
        int bindLock = bind.lastIndexOf("synchronized (mDecoderSurfaceHandoffLock)", queue);
        assertTrue(queue >= 0 && bindLock >= 0);
        String bindTransition = blockBody(bind,
                bind.indexOf('{', bindLock + "synchronized (mDecoderSurfaceHandoffLock)".length()));
        int parkedCheck = bindTransition.indexOf("!mClientSbsContextRecoveryParked");
        int lockedQueue = bindTransition.indexOf("game.setDecoderOutputSurfaceAsync(");
        int requestPublication = bindTransition.indexOf(
                "mDecoderSurfaceHandoffRequest = queuedRequest");
        assertTrue(parkedCheck >= 0 && lockedQueue > parkedCheck
                && requestPublication > lockedQueue);

        int recoveryPublication = recovery.indexOf("mClientSbsContextRecoveryParked = true");
        int recoveryLock = recovery.lastIndexOf(
                "synchronized (mDecoderSurfaceHandoffLock)", recoveryPublication);
        assertTrue(recoveryPublication >= 0 && recoveryLock >= 0);
        String recoveryTransition = blockBody(recovery,
                recovery.indexOf('{', recoveryLock
                        + "synchronized (mDecoderSurfaceHandoffLock)".length()));
        assertTrue(recoveryTransition.contains("mClientSbsContextRecoveryParked = true"));
        assertTrue(recovery.indexOf("invalidateDecoderSurfaceHandoff(true)")
                > recoveryPublication);
        assertTrue(recovery.indexOf("game.setDecoderOutputSurfaceForGlRecovery(mDummySurface)")
                > recovery.indexOf("invalidateDecoderSurfaceHandoff(true)"));
    }

    @Test
    public void clientSwitchRejectsNullAndOverlapWithoutReplacingActiveOwner() throws Exception {
        String source = readSource("StreamContainer.java");
        String clientSwitch = methodBody(source, "public void switchToClientSbs(boolean enable,");

        int nullGuard = clientSwitch.indexOf("if (callback == null)");
        int overlapGuard = clientSwitch.indexOf("if (mPendingClientSbsSwitch != null)");
        int newOwner = clientSwitch.indexOf("mPendingClientSbsSwitch = callback");
        String overlap = blockBody(clientSwitch,
                clientSwitch.indexOf('{', overlapGuard
                        + "if (mPendingClientSbsSwitch != null)".length()));
        assertTrue(nullGuard >= 0 && overlapGuard > nullGuard && newOwner > overlapGuard);
        assertTrue(overlap.contains("callback.onComplete(false)"));
        assertFalse(overlap.contains("mPendingClientSbsSwitch ="));
        assertFalse(overlap.contains("completeClientSbsSwitch("));
    }

    @Test
    public void hostResizeHasOneOverallDeadlineAndTwoOrderedAsyncBinds() throws Exception {
        String source = readSource("StreamContainer.java");
        String resize = methodBody(source,
                "public boolean resizeHostSbsSurface(boolean sbs, int logicalWidth,");

        int deadline = resize.indexOf("mDeadlineHandler.postAtTime(");
        int park = resize.indexOf(
                "bindDecoderSurfaceAsync(mDummySurface, resizeDeadlineMs");
        int resizeSceneCore = resize.indexOf("mXrPresenter.setHostSurfaceSize(", park);
        int finalBind = resize.indexOf(
                "bindDecoderSurfaceAsync(target, resizeDeadlineMs", resizeSceneCore);
        assertTrue(deadline >= 0 && park > deadline);
        assertTrue(resizeSceneCore > park && finalBind > resizeSceneCore);
        assertTrue(resize.contains("SystemClock.uptimeMillis() >= resizeDeadlineMs"));
        assertTrue(resize.contains("catch (RuntimeException error)"));
        assertTrue(resize.contains("surfaceReady ? mXrPresenter.getVideoSurface() : null"));
    }

    @Test
    public void ownerAndPerBindWatchdogsUseExactAbsoluteDeadlines() throws Exception {
        String source = readSource("StreamContainer.java");
        String clientSwitch = methodBody(source, "public void switchToClientSbs(boolean enable,");
        String bind = methodBody(
                source, "private void bindDecoderSurfaceAsync(Surface surface, long ownerDeadlineMs,");
        String hostResize = methodBody(source,
                "public boolean resizeHostSbsSurface(boolean sbs, int logicalWidth,");

        assertTrue(clientSwitch.contains(
                "mDeadlineHandler.postAtTime("));
        assertTrue(clientSwitch.contains("}, switchDeadlineMs)"));
        assertTrue(bind.contains("ownerDeadlineMs)"));
        assertFalse(bind.contains("postDelayed("));
        assertTrue(hostResize.contains("}, resizeDeadlineMs)"));
    }

    @Test
    public void clientEntryFailsOwningGenerationWhenSceneCoreResizeThrows() throws Exception {
        String source = readSource("StreamContainer.java");
        String continuation = methodBody(source,
                "private void continueClientSbsSwitchAfterDecoderPark(");

        assertTrue(continuation.contains("catch (RuntimeException error)"));
        assertTrue(continuation.contains("surfaceReady ? mXrPresenter.getVideoSurface() : null"));
        assertTrue(continuation.contains("completeClientSbsSwitch(switchGeneration, false)"));
    }

    @Test
    public void teardownInvalidatesBeforeSurfaceRelease() throws Exception {
        String source = readSource("StreamContainer.java");
        String destroy = methodBody(source, "public void onDestroy(Runnable onCleanupComplete)");
        String finish = methodBody(source, "private void finishContainerCleanup()");

        assertTrue(destroy.contains("mDestroyed = true"));
        assertTrue(destroy.indexOf("invalidateDecoderSurfaceHandoff(false)")
                > destroy.indexOf("mDestroyed = true"));
        assertTrue(finish.contains("mDummySurface.release()"));
    }

    @Test
    public void decoderGateOpensOnlyInSuccessfulSurfaceCompletionContinuation() throws Exception {
        String source = readSource("XrStreamPresenter.java");
        String start = methodBody(source,
                "private void beginPostAckLiveQualityDecoderConfirmation(");
        String completion = methodBody(source,
                "private void finishPostAckGeometryAdoption(");
        String modeStart = methodBody(source, "private void finishModeSwitch(BarItem item,");
        String modeCompletion = methodBody(source,
                "private void finishModeSwitchAfterSurfaceHandoff(");

        assertTrue(start.contains("finishPostAckGeometryAdoption("));
        assertFalse(start.contains("game.completeDecoderPresentationModeTransition()"));
        assertTrue(completion.indexOf("if (!success)")
                < completion.indexOf("game.completeDecoderPresentationModeTransition()"));
        assertTrue(modeStart.contains("resizeHostSbsSurface("));
        assertFalse(modeStart.contains("completeDecoderPresentationModeTransition()"));
        assertTrue(modeCompletion.contains("completeDecoderPresentationModeTransition()"));
        assertFalse(modeCompletion.contains("cancelDecoderPresentationModeTransition()"));
    }

    private static String readSource(String name) throws IOException {
        File file = new File("src/main/java/com/limelight/ui/" + name);
        assertTrue(name + " source is missing", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int signatureStart = source.indexOf(signature);
        assertTrue("Missing method signature: " + signature, signatureStart >= 0);
        int bodyStart = source.indexOf('{', signatureStart + signature.length());
        assertTrue("Missing method body: " + signature, bodyStart >= 0);

        return blockBody(source, bodyStart);
    }

    private static String blockBody(String source, int bodyStart) {
        assertTrue("Missing block body", bodyStart >= 0);
        int depth = 0;
        for (int index = bodyStart; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return source.substring(bodyStart + 1, index);
            }
        }
        throw new AssertionError("Unterminated block body");
    }
}
