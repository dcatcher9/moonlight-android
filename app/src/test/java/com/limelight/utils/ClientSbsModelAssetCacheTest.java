package com.limelight.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientSbsModelAssetCacheTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void authoritativePruneRetainsOnlyCurrentProductionBucketNames() throws Exception {
        File cache = temporaryFolder.newFolder("production-buckets");
        ClientSbsModelManifest[] models = {
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9,
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9,
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9};
        for (ClientSbsModelManifest model : models) {
            Files.write(new File(cache, ClientSbsModelAssetCache.stagedFileName(
                    model.getId(), model.getAssetSha256())).toPath(), new byte[] {7});
        }
        File obsolete = new File(cache, "zipdepth-base-static-672x384-oldhash.tflite");
        File partial = new File(cache, ClientSbsModelAssetCache.stagedFileName(
                models[1].getId(), models[1].getAssetSha256()) + ".partial");
        Files.write(obsolete.toPath(), new byte[] {8});
        Files.write(partial.toPath(), new byte[] {9});
        File unrelated = new File(cache, "notes.txt");
        Files.write(unrelated.toPath(), new byte[] {10});
        byte[] modelBytes = {1, 2, 3};
        byte[] archive = createArchive("test.model", modelBytes);
        ClientSbsModelAssetCache.prepareVerifiedModelFile(cache, "test", "test.model",
                sha256(modelBytes), () -> new ByteArrayInputStream(archive), true);
        for (ClientSbsModelManifest model : models) {
            File retained = new File(cache, ClientSbsModelAssetCache.stagedFileName(
                    model.getId(), model.getAssetSha256()));
            assertTrue(retained.isFile());
            // Retention is not proof of integrity. Authoritative initialization still hashes it.
            assertFalse(ClientSbsModelAssetCache.digestMatches(retained, model.getAssetSha256()));
        }
        assertFalse(obsolete.exists());
        assertFalse(partial.exists());
        assertTrue(unrelated.isFile());
    }

    @Test
    public void speculativeStageRetainsOtherBucketButAuthoritativeReusePrunesIt()
            throws Exception {
        File cacheDirectory = temporaryFolder.newFolder("model-cache");
        File otherBucket = new File(cacheDirectory, "other-bucket.tflite");
        Files.write(otherBucket.toPath(), new byte[] {9});
        byte[] model = new byte[] {1, 2, 3, 4};
        byte[] archive = createArchive("selected.model", model);
        String sha256 = sha256(model);
        AtomicInteger archiveOpens = new AtomicInteger();

        File staged = ClientSbsModelAssetCache.prepareVerifiedModelFile(
                cacheDirectory, "selected", "selected.model", sha256,
                () -> {
                    archiveOpens.incrementAndGet();
                    return new ByteArrayInputStream(archive);
                }, false);

        assertTrue(otherBucket.isFile());
        assertArrayEquals(model, Files.readAllBytes(staged.toPath()));

        File reused = ClientSbsModelAssetCache.prepareVerifiedModelFile(
                cacheDirectory, "selected", "selected.model", sha256,
                () -> {
                    archiveOpens.incrementAndGet();
                    return new ByteArrayInputStream(archive);
                }, true);

        assertEquals(staged.getCanonicalFile(), reused.getCanonicalFile());
        assertEquals(1, archiveOpens.get());
        assertFalse(otherBucket.exists());
    }

    @Test
    public void authoritativeUseRevalidatesAndReplacesOverwrittenSpeculativeFile()
            throws Exception {
        File cacheDirectory = temporaryFolder.newFolder("overwritten-cache");
        byte[] model = new byte[] {1, 2, 3, 4};
        byte[] archive = createArchive("selected.model", model);
        String sha256 = sha256(model);
        AtomicInteger archiveOpens = new AtomicInteger();
        ClientSbsModelAssetCache.ArchiveInputOpener archiveInputOpener = () -> {
            archiveOpens.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };

        File staged = ClientSbsModelAssetCache.prepareVerifiedModelFile(
                cacheDirectory, "selected", "selected.model", sha256,
                archiveInputOpener, false);
        assertEquals(1, archiveOpens.get());

        Files.write(staged.toPath(), new byte[] {9});
        File authoritative = ClientSbsModelAssetCache.prepareVerifiedModelFile(
                cacheDirectory, "selected", "selected.model", sha256,
                archiveInputOpener, true);

        assertEquals(staged.getCanonicalFile(), authoritative.getCanonicalFile());
        assertArrayEquals(model, Files.readAllBytes(authoritative.toPath()));
        assertEquals(2, archiveOpens.get());

        File reused = ClientSbsModelAssetCache.prepareVerifiedModelFile(
                cacheDirectory, "selected", "selected.model", sha256,
                archiveInputOpener, true);
        assertEquals(authoritative.getCanonicalFile(), reused.getCanonicalFile());
        assertEquals(2, archiveOpens.get());
    }

    @Test
    public void concurrentRequestsPublishOneVerifiedFileAndExtractOnce() throws Exception {
        File cacheDirectory = temporaryFolder.newFolder("concurrent-cache");
        byte[] model = new byte[32 * 1024];
        for (int index = 0; index < model.length; index++) {
            model[index] = (byte) (index * 31);
        }
        byte[] archive = createArchive("selected.model", model);
        String sha256 = sha256(model);
        AtomicInteger archiveOpens = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<File> request = () ->
                    ClientSbsModelAssetCache.prepareVerifiedModelFile(
                            cacheDirectory, "selected", "selected.model", sha256,
                            () -> {
                                archiveOpens.incrementAndGet();
                                return new ByteArrayInputStream(archive);
                            }, false);
            Future<File> first = executor.submit(request);
            Future<File> second = executor.submit(request);

            File firstFile = first.get(10, TimeUnit.SECONDS);
            File secondFile = second.get(10, TimeUnit.SECONDS);
            assertEquals(firstFile.getCanonicalFile(), secondFile.getCanonicalFile());
            assertEquals(1, archiveOpens.get());
            assertArrayEquals(model, Files.readAllBytes(firstFile.toPath()));
            File[] partials = cacheDirectory.listFiles(
                    file -> file.getName().endsWith(".partial"));
            assertTrue(partials != null && partials.length == 0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void asyncAdmissionReturnsWhileExtractionOwnsTheCacheLock() throws Exception {
        File cacheDirectory = temporaryFolder.newFolder("blocked-cache");
        byte[] model = new byte[] {1, 2, 3, 4};
        byte[] archive = createArchive("selected.model", model);
        String digest = sha256(model);
        String requestKey = cacheDirectory.getAbsolutePath() + ':' + digest;
        CountDownLatch extractionEntered = new CountDownLatch(1);
        CountDownLatch releaseExtraction = new CountDownLatch(1);
        AtomicInteger archiveOpens = new AtomicInteger();
        CompletableFuture<File> staged = new CompletableFuture<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<File> authoritative = executor.submit(() ->
                    ClientSbsModelAssetCache.prepareVerifiedModelFile(
                            cacheDirectory, "selected", "selected.model", digest,
                            () -> {
                                archiveOpens.incrementAndGet();
                                extractionEntered.countDown();
                                try {
                                    if (!releaseExtraction.await(10, TimeUnit.SECONDS)) {
                                        throw new IOException("Test did not release extraction");
                                    }
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException(error);
                                }
                                return new ByteArrayInputStream(archive);
                            }, true));
            assertTrue(extractionEntered.await(5, TimeUnit.SECONDS));

            // This is the same admission operation called by UI-side renderer construction.
            // The worker must wait for cache integrity, but the caller must already have returned.
            Future<Boolean> admitted = executor.submit(() -> ClientSbsModelAssetCache.startPrestage(
                    requestKey, () -> {
                        try {
                            staged.complete(ClientSbsModelAssetCache.prepareVerifiedModelFile(
                                    cacheDirectory, "selected", "selected.model", digest,
                                    () -> {
                                        archiveOpens.incrementAndGet();
                                        return new ByteArrayInputStream(archive);
                                    }, false));
                        } catch (Throwable error) {
                            staged.completeExceptionally(error);
                        }
                    }));
            assertTrue(admitted.get(2, TimeUnit.SECONDS));
            assertFalse(staged.isDone());
            assertFalse(ClientSbsModelAssetCache.startPrestage(requestKey,
                    () -> { throw new AssertionError("Duplicate staging was admitted"); }));

            releaseExtraction.countDown();
            assertEquals(authoritative.get(5, TimeUnit.SECONDS), staged.get(5, TimeUnit.SECONDS));
            assertEquals(1, archiveOpens.get());
        } finally {
            releaseExtraction.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static byte[] createArchive(String name, byte[] model) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XZCompressorOutputStream compressed = new XZCompressorOutputStream(bytes);
             TarArchiveOutputStream archive = new TarArchiveOutputStream(compressed)) {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(model.length);
            archive.putArchiveEntry(entry);
            archive.write(model);
            archive.closeArchiveEntry();
            archive.finish();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte current : digest) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", current & 0xff));
        }
        return hex.toString();
    }
}
