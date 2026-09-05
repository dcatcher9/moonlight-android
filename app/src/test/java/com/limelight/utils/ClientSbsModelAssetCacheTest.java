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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientSbsModelAssetCacheTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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
