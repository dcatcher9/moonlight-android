package com.limelight.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.opengl.GLSurfaceView;

import com.limelight.binding.video.DecodedSourceIdentityTracker;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.sbs.ClientSbsFrameSlots;
import com.limelight.sbs.ClientSbsGpuSceneCutDetector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class Stereo3DRendererHostSourceReuseTest {
    private Stereo3DRenderer renderer;
    private GLSurfaceView view;
    private DecodedSourceIdentityTracker tracker;
    private DecodedSourceIdentityTracker.Sample candidate;
    private ClientSbsFrameSlots.Lease originalColor;

    @Before
    public void setUp() {
        view = mock(GLSurfaceView.class);
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.width = 1920;
        prefs.height = 1080;
        try (MockedStatic<ClientSbsModelAssetCache> ignored = mockStatic(ClientSbsModelAssetCache.class)) {
            renderer = new Stereo3DRenderer(view, mock(Stereo3DRenderer.OnSurfaceReadyListener.class),
                    RuntimeEnvironment.getApplication(), prefs, false);
        }
        tracker = new DecodedSourceIdentityTracker();
        renderer.setDecodedSourceIdentityTracker(tracker);
        set("clientSbs", true);
        set("surfaceLifecycleReady", true);
        set("outputSurfaceValidated", true);
        set("matchedOutputPresented", true);
        set("sourceSettled", true);
        set("sourceRepeatOwnerConfirmed", true);
        set("gpuSceneCutDetector", mock(ClientSbsGpuSceneCutDetector.class));
        set("gpuDepthActive", true);
        set("activeClientSbsGeneration", 1);
        AtomicInteger generation = ReflectionHelpers.getField(renderer, "clientSbsGeneration");
        generation.set(1);
        ClientSbsFrameSlots slots = ReflectionHelpers.getField(renderer, "colorFrameSlots");
        originalColor = slots.tryAcquireForCapture(1, 10, 1_000L);
        assertTrue(slots.markInference(originalColor));
        assertTrue(slots.markPublished(originalColor));
        assertTrue(slots.markActive(originalColor));
        set("activeColorFrameLease", originalColor);
        set("lastCapturedFrameSequence", 10L);
        set("latchedFrameSequence", 11L);
        set("lastModelInferenceFrameSequence", 8L);
        set("lastModelInferenceCapturedAtNs", 500L);
        DecodedSourceIdentityTracker.Sample presented =
                ReflectionHelpers.getField(renderer, "presentedSourceIdentity");
        presented.epoch = tracker.getEpoch();
        presented.sourceId = 7;
        presented.frameNumber = 100;
        candidate = ReflectionHelpers.getField(renderer, "latchedSourceIdentity");
        candidate.copyFrom(presented);
        candidate.frameNumber = 101;
        float[] transform = ReflectionHelpers.getField(renderer, "videoTextureTransform");
        float[] presentedTransform = ReflectionHelpers.getField(renderer, "presentedSourceTransform");
        System.arraycopy(transform, 0, presentedTransform, 0, 16);
    }

    @After
    public void tearDown() {
        if (renderer != null) invoke("shutdownFrameCallbackThread", void.class);
    }

    @Test
    public void longStaticSequenceRetainsColorAndInferenceOwnerWithoutDrawRequests() {
        for (int i = 0; i < 1000; i++) {
            candidate.frameNumber = 101 + i;
            set("latchedFrameSequence", 11L + i);
            assertTrue(consume());
        }
        assertSame(originalColor, ReflectionHelpers.getField(renderer, "activeColorFrameLease"));
        assertEquals(8L, (long) ReflectionHelpers.getField(renderer, "lastModelInferenceFrameSequence"));
        assertEquals(500L, (long) ReflectionHelpers.getField(renderer, "lastModelInferenceCapturedAtNs"));
        assertEquals(1010L, (long) ReflectionHelpers.getField(renderer, "lastCapturedFrameSequence"));
        assertFalse((boolean) invoke("shouldPresentCurrentFlatForStaleDepth", boolean.class,
                60_000_000_000L));
        verifyNoInteractions(view);
    }

    @Test
    public void allocatedDepthWithoutConfirmedGpuHistoryStillNeedsOrdinaryArbitration() {
        set("sourceRepeatOwnerConfirmed", false);
        assertFalse(consume());
        assertEquals(10L, (long) ReflectionHelpers.getField(renderer, "lastCapturedFrameSequence"));
        set("sourceRepeatOwnerConfirmed", true);
        assertTrue(consume());
    }

    @Test
    public void lostOrResetPendingGpuHistoryCannotRetainOutput() {
        set("gpuSceneCutResetPending", true);
        assertFalse(consume());
        set("gpuSceneCutResetPending", false);
        set("gpuSceneCutDetector", null);
        assertFalse(consume());
    }

    @Test
    public void changedOrUnknownSourceLeavesCandidateForOrdinaryArbitration() {
        candidate.sourceId = 8;
        assertFalse(consume());
        candidate.sourceId = 0;
        assertFalse(consume());
        assertEquals(10L, (long) ReflectionHelpers.getField(renderer, "lastCapturedFrameSequence"));
    }

    @Test
    public void decoderResetAndCropChangeRejectOtherwiseMatchingSource() {
        float[] transform = ReflectionHelpers.getField(renderer, "videoTextureTransform");
        transform[12] += 0.25f;
        assertFalse(consume());
        transform[12] -= 0.25f;
        tracker.reset();
        assertFalse(consume());
    }

    @Test
    public void pendingInferenceAndPresentationCannotBeSkipped() {
        AtomicLong claim = ReflectionHelpers.getField(renderer, "inferenceClaim");
        claim.set(99L);
        assertFalse(consume());
        claim.set(0L);
        ClientSbsPresentationTransaction transaction =
                ReflectionHelpers.getField(renderer, "presentationCompletion");
        transaction.arm(ClientSbsPresentationTransaction.Kind.RESIZE, 1, 1, () -> { });
        assertFalse(consume());
        transaction.cancel();
        assertTrue(consume());
    }

    @Test
    public void flatOutputAndNewRendererGenerationRequireRealPresentation() {
        set("matchedOutputPresented", false);
        assertFalse(consume());
        set("matchedOutputPresented", true);
        AtomicInteger generation = ReflectionHelpers.getField(renderer, "clientSbsGeneration");
        generation.incrementAndGet();
        assertFalse(consume());
    }

    @Test
    public void initialSettlingKeepsDecodedColorUpdatesAndHasNoRecurringExpiry() {
        set("sourceSettled", false);
        set("sourceSettlingStartedNs", System.nanoTime());
        assertFalse(consume());
        assertEquals(10L, (long) ReflectionHelpers.getField(renderer, "lastCapturedFrameSequence"));
        assertFalse(Stereo3DRenderer.hasHostSourceSettled(100L,
                100L + Stereo3DRenderer.HOST_SOURCE_SETTLING_NS - 1L));
        assertTrue(Stereo3DRenderer.hasHostSourceSettled(100L,
                100L + Stereo3DRenderer.HOST_SOURCE_SETTLING_NS));
        assertTrue(Stereo3DRenderer.hasHostSourceSettled(100L, Long.MAX_VALUE));
        assertFalse(Stereo3DRenderer.hasHostSourceSettled(0L, Long.MAX_VALUE));
        assertFalse(Stereo3DRenderer.hasHostSourceSettled(100L, 99L));
    }

    private boolean consume() {
        return (boolean) invoke("consumeProvenSourceRepeat", boolean.class);
    }

    private Object invoke(String method, Class<?> returnType, Long... args) {
        // Resolve only the requested method. ReflectionHelpers enumerates every renderer method,
        // including legacy GL10/EGL types absent from Robolectric's host JVM class loader.
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    Stereo3DRenderer.class, MethodHandles.lookup());
            if (args.length == 0) {
                return lookup.findVirtual(Stereo3DRenderer.class, method,
                        MethodType.methodType(returnType)).invoke(renderer);
            }
            return lookup.findVirtual(Stereo3DRenderer.class, method,
                    MethodType.methodType(returnType, long.class)).invoke(renderer, args[0].longValue());
        } catch (Throwable failure) {
            throw new AssertionError(method, failure);
        }
    }

    private void set(String name, Object value) {
        ReflectionHelpers.setField(renderer, name, value);
    }
}
