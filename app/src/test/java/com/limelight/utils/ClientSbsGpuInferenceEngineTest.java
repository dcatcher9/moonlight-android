package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientSbsGpuInferenceEngineTest {
    @Test
    public void processModelSlotAllowsExactlyOneOwner() {
        ClientSbsGpuInferenceEngine.ProcessModelSlot slot =
                new ClientSbsGpuInferenceEngine.ProcessModelSlot();
        Object first = new Object();
        Object second = new Object();

        assertFalse(slot.isClaimed());
        slot.claim(first);
        assertTrue(slot.isClaimed());
        assertThrows(IllegalStateException.class, () -> slot.claim(first));
        assertThrows(IllegalStateException.class, () -> slot.claim(second));
        assertThrows(IllegalStateException.class, () -> slot.release(second));
        slot.release(first);
        assertFalse(slot.isClaimed());

        slot.claim(second);
        assertTrue(slot.isClaimed());
        slot.release(second);
    }

    @Test
    public void processModelSlotHandsOffAfterPreviousOwnerReleases() throws Exception {
        ClientSbsGpuInferenceEngine.ProcessModelSlot slot =
                new ClientSbsGpuInferenceEngine.ProcessModelSlot();
        Object first = new Object();
        Object second = new Object();
        CountDownLatch waitingClaimStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            slot.claim(first);
            Future<Boolean> claimed = executor.submit(() -> {
                waitingClaimStarted.countDown();
                return slot.claimWhenAvailable(second, 2L, TimeUnit.SECONDS);
            });

            assertTrue(waitingClaimStarted.await(1L, TimeUnit.SECONDS));
            assertTrue(slot.isClaimed());
            slot.release(first);
            assertTrue(claimed.get(1L, TimeUnit.SECONDS));
            assertTrue(slot.isClaimed());
            slot.release(second);
            assertFalse(slot.isClaimed());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void processModelSlotRetriesAnAlreadyDeferredOwnerUntilItCloses() throws Exception {
        ClientSbsGpuInferenceEngine.ProcessModelSlot slot =
                new ClientSbsGpuInferenceEngine.ProcessModelSlot();
        Object oldEngine = new Object();
        Object reconnectEngine = new Object();
        AtomicInteger closePolls = new AtomicInteger();
        slot.claim(oldEngine);

        boolean claimed = ClientSbsGpuInferenceEngine.claimModelSlotWithDeferredCloseRetries(
                slot,
                reconnectEngine,
                2L,
                TimeUnit.SECONDS,
                1L,
                TimeUnit.MILLISECONDS,
                () -> {
                    if (closePolls.incrementAndGet() == 2) {
                        slot.release(oldEngine);
                    }
                });

        assertTrue(claimed);
        assertEquals(2, closePolls.get());
        assertTrue(slot.isClaimed());
        slot.release(reconnectEngine);
        assertFalse(slot.isClaimed());
    }

    @Test
    public void gpuPriorityHintsMatchLiteRtWireContract() {
        assertEquals(1, ClientSbsGpuInferenceEngine.GpuPriorityHint.LOW.wireValue);
        assertEquals("Low", ClientSbsGpuInferenceEngine.GpuPriorityHint.LOW.label);
        assertEquals(2, ClientSbsGpuInferenceEngine.GpuPriorityHint.NORMAL.wireValue);
        assertEquals("Normal", ClientSbsGpuInferenceEngine.GpuPriorityHint.NORMAL.label);

        assertEquals(ClientSbsGpuInferenceEngine.GpuPriorityHint.LOW,
                ClientSbsGpuInferenceEngine.GpuPriorityHint.fromNativeValue(1));
        assertEquals(ClientSbsGpuInferenceEngine.GpuPriorityHint.NORMAL,
                ClientSbsGpuInferenceEngine.GpuPriorityHint.fromNativeValue(2));
    }

    @Test
    public void unknownGpuPriorityHintIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> ClientSbsGpuInferenceEngine.GpuPriorityHint.fromNativeValue(3));
    }

    @Test
    public void compilerCacheKeySeparatesModelExecutionPolicies() {
        assertEquals(
                "midas-v2-static-352x192-2a3ee0a1e818-352x192-opencl-auto-v2",
                ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.MIDAS_V2_STATIC_16_9));
        assertEquals(
                "depth-anything-v2-small-static-322x182-82f8594f4ee6-"
                        + "322x182-opencl-auto-v2",
                ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_16_9));
        assertEquals(
                "depth-anything-v2-small-static-350x154-2739f306ce71-"
                        + "350x154-opencl-auto-v2",
                ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_21_9));
        assertEquals(
                "depth-anything-v2-small-static-434x126-353eb80fd6b9-"
                        + "434x126-opencl-auto-v2",
                ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_32_9));
    }
}
