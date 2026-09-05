package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientSbsGpuInferenceEngineTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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
    public void runDispositionMatchesNativeWireContract() {
        assertEquals(1, ClientSbsGpuInferenceEngine.RunDisposition.INFER.wireValue);
        assertEquals(2, ClientSbsGpuInferenceEngine.RunDisposition.REUSE.wireValue);
        assertEquals(ClientSbsGpuInferenceEngine.RunDisposition.INFER,
                ClientSbsGpuInferenceEngine.RunDisposition.fromNativeValue(1));
        assertEquals(ClientSbsGpuInferenceEngine.RunDisposition.REUSE,
                ClientSbsGpuInferenceEngine.RunDisposition.fromNativeValue(2));
        assertThrows(IllegalStateException.class,
                () -> ClientSbsGpuInferenceEngine.RunDisposition.fromNativeValue(0));
    }

    @Test
    public void compilerCacheKeysPinAllProductionZipDepthBuckets() {
        assertEquals(
                "zipdepth-base-static-672x384-6296d5c2e4f8-672x384-opencl-auto-v2",
                ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9));
        assertEquals(
                "zipdepth-base-static-896x384-31467ab0cd18-896x384-opencl-auto-v2",
                ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9));
        assertEquals(
                "zipdepth-base-static-928x384-169d5e8802be-928x384-opencl-auto-v2",
                ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9));
    }

    @Test
    public void productionInitializeRejectsRetiredManifestBeforeContextOrNativeWork()
            throws Exception {
        Constructor<ClientSbsGpuInferenceEngine> constructor =
                ClientSbsGpuInferenceEngine.class.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        ClientSbsGpuInferenceEngine engine = constructor.newInstance(1L);

        assertThrows(IllegalArgumentException.class,
                () -> engine.initialize(null, ClientSbsModelManifest.MIDAS_V2_STATIC_16_9));
    }

    @Test
    public void productionCachePruneKeepsEveryZipBucketAndOnlyTouchesExactRoot()
            throws Exception {
        File codeCacheRoot = temporaryFolder.newFolder("code-cache");
        File cacheRoot = new File(codeCacheRoot, "client-sbs-litert-gpu");
        assertTrue(cacheRoot.mkdir());

        File[] retained = {
                new File(cacheRoot, ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9)),
                new File(cacheRoot, ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9)),
                new File(cacheRoot, ClientSbsGpuInferenceEngine.compilerCacheDirectoryName(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9)),
        };
        for (File directory : retained) {
            assertTrue(directory.mkdir());
            Files.write(new File(directory, "compiled.bin").toPath(), new byte[] {1});
        }
        File retiredDirectory = new File(cacheRoot, "midas-v2-static-352x192-old");
        assertTrue(retiredDirectory.mkdir());
        Files.write(new File(retiredDirectory, "compiled.bin").toPath(), new byte[] {2});
        File retiredFile = new File(cacheRoot, "depthart-stale.bin");
        Files.write(retiredFile.toPath(), new byte[] {3});
        File outsideExactRoot = new File(codeCacheRoot, "unrelated-cache");
        assertTrue(outsideExactRoot.mkdir());
        Files.write(new File(outsideExactRoot, "keep.bin").toPath(), new byte[] {4});

        Method prune = ClientSbsGpuInferenceEngine.class.getDeclaredMethod(
                "pruneRetiredProductionCompilerCaches", File.class, File.class);
        prune.setAccessible(true);
        prune.invoke(null, codeCacheRoot, cacheRoot);

        for (File directory : retained) {
            assertTrue(directory.isDirectory());
            assertTrue(new File(directory, "compiled.bin").isFile());
        }
        assertFalse(retiredDirectory.exists());
        assertFalse(retiredFile.exists());
        assertTrue(new File(outsideExactRoot, "keep.bin").isFile());
    }
}
