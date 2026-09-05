package com.limelight.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Process;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/**
 * JNI-free extraction and verification for Client SBS model assets.
 *
 * <p>Keeping this separate from {@link ClientSbsGpuInferenceEngine} lets stream construction stage
 * the immutable aspect graph on a background CPU thread without loading LiteRT, creating EGL
 * state, or touching the GPU. The authoritative engine initialization uses the same lock and
 * integrity path, so it either reuses the completed file or waits for the single in-flight
 * extraction.</p>
 */
final class ClientSbsModelAssetCache {
    static final String PRODUCTION_MODEL_CACHE = "client-sbs-model-assets";
    static final String BENCHMARK_MODEL_CACHE = "client-sbs-benchmark-model-assets";

    private static final int MODEL_IO_BUFFER_BYTES = 64 * 1024;
    private static final Object CACHE_LOCK = new Object();
    private static final Set<String> VERIFIED_MODELS = new HashSet<>();
    private static final Set<String> AUTHORITATIVELY_VERIFIED_MODELS = new HashSet<>();
    private static final Set<String> ASYNC_PRESTAGES = new HashSet<>();

    interface ArchiveInputOpener {
        InputStream open() throws IOException;
    }

    private ClientSbsModelAssetCache() {
    }

    /**
     * Starts one nonfatal, CPU-only production-cache pre-stage for this immutable graph.
     *
     * <p>Speculative staging deliberately retains other verified buckets. A later authoritative
     * engine initialization prunes the production directory to its selected graph.</p>
     */
    static void prestageProductionModelAsync(Context context,
                                             ClientSbsModelManifest manifest) {
        if (context == null || manifest == null) {
            return;
        }
        // Root APKs intentionally package neither this archive nor LiteRT. Avoid even a failed
        // background asset lookup so their existing direct-render behavior is unchanged.
        if (BuildConfig.ROOT_BUILD) {
            return;
        }
        Context applicationContext = context.getApplicationContext();
        Context storageContext = applicationContext != null ? applicationContext : context;
        File codeCacheDirectory = storageContext.getCodeCacheDir();
        if (codeCacheDirectory == null) {
            LimeLog.info("Client SBS model asset pre-stage deferred: code cache unavailable");
            return;
        }
        String requestKey = codeCacheDirectory.getAbsolutePath() + ':'
                + manifest.getAssetName() + ':' + manifest.getAssetSha256();
        synchronized (CACHE_LOCK) {
            if (!ASYNC_PRESTAGES.add(requestKey)) {
                return;
            }
        }

        Thread prestageThread = new Thread(() -> {
            try {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } catch (RuntimeException ignored) {
                    // Thread priority is only an optimization; cache integrity is independent.
                }
                long startedNs = System.nanoTime();
                LimeLog.info("Client SBS model asset pre-stage started: " + manifest.getId());
                File stagedFile = prepareVerifiedModelFile(
                        storageContext, storageContext.getAssets(), manifest,
                        PRODUCTION_MODEL_CACHE, false);
                LimeLog.info("Client SBS model asset pre-staged: " + manifest.getId()
                        + " bytes=" + stagedFile.length() + " elapsed="
                        + String.format(java.util.Locale.ROOT, "%.1f ms",
                        Math.max(0L, System.nanoTime() - startedNs) / 1_000_000.0));
            } catch (IOException | RuntimeException error) {
                // Normal and Host SBS remain independent. Authoritative Client SBS initialization
                // reports its own failure if the user later requests the unavailable path.
                LimeLog.info("Client SBS model asset pre-stage deferred: "
                        + error.getMessage());
            } finally {
                synchronized (CACHE_LOCK) {
                    ASYNC_PRESTAGES.remove(requestKey);
                }
            }
        }, "ClientSbsModelPrestage");
        try {
            prestageThread.setDaemon(true);
            prestageThread.start();
        } catch (RuntimeException error) {
            synchronized (CACHE_LOCK) {
                ASYNC_PRESTAGES.remove(requestKey);
            }
            LimeLog.info("Client SBS model asset pre-stage could not start: "
                    + error.getMessage());
        }
    }

    static File prepareVerifiedModelFile(Context storageContext,
                                         AssetManager modelAssets,
                                         ClientSbsModelManifest manifest,
                                         String modelCacheName,
                                         boolean pruneOtherModels) throws IOException {
        File modelDirectory = new File(storageContext.getCodeCacheDir(), modelCacheName);
        return prepareVerifiedModelFile(
                modelDirectory,
                manifest.getId(),
                manifest.getAssetName(),
                manifest.getAssetSha256(),
                () -> modelAssets.open(
                        manifest.getModelArchiveAssetName(), AssetManager.ACCESS_STREAMING),
                pruneOtherModels);
    }

    /** Package-private deterministic core used by cache and concurrency JVM tests. */
    static File prepareVerifiedModelFile(File modelDirectory,
                                         String modelId,
                                         String targetAsset,
                                         String expectedSha256,
                                         ArchiveInputOpener archiveInputOpener,
                                         boolean pruneOtherModels) throws IOException {
        synchronized (CACHE_LOCK) {
            if (!modelDirectory.isDirectory() && !modelDirectory.mkdirs()
                    && !modelDirectory.isDirectory()) {
                throw new IOException("Unable to create Client SBS model staging directory");
            }
            String safeId = modelId.replaceAll("[^A-Za-z0-9._-]", "_");
            File verifiedFile = new File(modelDirectory, safeId + '-'
                    + expectedSha256.substring(0, 16) + ".tflite");
            if (pruneOtherModels) {
                pruneStagedModelDirectory(modelDirectory, verifiedFile);
            }

            String verificationKey = modelDirectory.getAbsolutePath() + ':'
                    + targetAsset + ':' + expectedSha256;
            boolean requiresAuthoritativeRevalidation = pruneOtherModels
                    && !AUTHORITATIVELY_VERIFIED_MODELS.contains(verificationKey);
            if (VERIFIED_MODELS.contains(verificationKey) && verifiedFile.isFile()
                    && !requiresAuthoritativeRevalidation) {
                return verifiedFile;
            }
            if (verifiedFile.isFile() && digestMatches(verifiedFile, expectedSha256)) {
                VERIFIED_MODELS.add(verificationKey);
                if (pruneOtherModels) {
                    AUTHORITATIVELY_VERIFIED_MODELS.add(verificationKey);
                }
                return verifiedFile;
            }
            if (verifiedFile.exists() && !verifiedFile.delete()) {
                throw new IOException("Unable to replace corrupt Client SBS staged model");
            }

            File temporaryFile = File.createTempFile(safeId + '-', ".partial", modelDirectory);
            boolean published = false;
            MessageDigest digest = newSha256Digest();
            try {
                try (InputStream archiveInput = archiveInputOpener.open();
                     FileOutputStream fileOutput = new FileOutputStream(temporaryFile);
                     BufferedOutputStream output = new BufferedOutputStream(
                             fileOutput, MODEL_IO_BUFFER_BYTES)) {
                    extractArchivedModel(archiveInput, targetAsset, output, digest);
                    output.flush();
                    fileOutput.getFD().sync();
                }
                String actual = toHex(digest.digest());
                if (!expectedSha256.equals(actual)) {
                    throw new IOException("Client SBS model " + modelId
                            + " SHA-256 mismatch: expected " + expectedSha256
                            + ", got " + actual);
                }
                if (!temporaryFile.renameTo(verifiedFile)) {
                    throw new IOException("Unable to publish verified Client SBS model file");
                }
                published = true;
            } finally {
                if (!published && temporaryFile.exists() && !temporaryFile.delete()) {
                    LimeLog.warning("Unable to remove incomplete Client SBS model staging file");
                }
            }
            VERIFIED_MODELS.add(verificationKey);
            if (pruneOtherModels) {
                AUTHORITATIVELY_VERIFIED_MODELS.add(verificationKey);
            }
            return verifiedFile;
        }
    }

    /** Package-private deterministic archive core used by JVM tests. */
    static void extractArchivedModel(InputStream archiveInput,
                                     String targetAsset,
                                     OutputStream output,
                                     MessageDigest digest) throws IOException {
        boolean targetFound = false;
        try (BufferedInputStream bufferedInput = new BufferedInputStream(
                     archiveInput, MODEL_IO_BUFFER_BYTES);
             XZCompressorInputStream decompressed = new XZCompressorInputStream(bufferedInput);
             TarArchiveInputStream archive = new TarArchiveInputStream(decompressed)) {
            TarArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.isFile() && targetAsset.equals(entry.getName())) {
                    copyArchiveEntry(archive, output, digest);
                    targetFound = true;
                    break;
                }
            }
        }

        if (!targetFound) {
            throw new IOException("Client SBS model archive is missing " + targetAsset);
        }
    }

    private static void copyArchiveEntry(InputStream input, OutputStream output,
                                         MessageDigest digest) throws IOException {
        byte[] chunk = new byte[MODEL_IO_BUFFER_BYTES];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (digest != null) {
                digest.update(chunk, 0, count);
            }
            output.write(chunk, 0, count);
        }
    }

    /** Keeps authoritative initialization bounded to its selected verified graph. */
    private static void pruneStagedModelDirectory(File modelDirectory, File selectedFile) {
        File[] stagedFiles = modelDirectory.listFiles();
        if (stagedFiles == null) {
            return;
        }
        for (File stagedFile : stagedFiles) {
            if (stagedFile.equals(selectedFile)) {
                continue;
            }
            String name = stagedFile.getName();
            if (stagedFile.isFile()
                    && (name.endsWith(".partial") || name.endsWith(".tflite"))
                    && !stagedFile.delete()) {
                LimeLog.warning("Unable to prune stale Client SBS model cache file: " + name);
            }
        }
    }

    /** Serializes benchmark asset deletion with verification/extraction of that same namespace. */
    static boolean clearBenchmarkModelCache(Context context) {
        synchronized (CACHE_LOCK) {
            File codeCache = context.getCodeCacheDir();
            File child = new File(codeCache, BENCHMARK_MODEL_CACHE);
            if (!child.exists()) {
                return true;
            }
            try {
                File canonicalRoot = codeCache.getCanonicalFile();
                File canonicalChild = child.getCanonicalFile();
                if (!canonicalRoot.equals(canonicalChild.getParentFile())) {
                    LimeLog.warning("Refusing to remove Client SBS benchmark model cache outside "
                            + "code cache: " + canonicalChild);
                    return false;
                }
                return deleteCacheTree(canonicalChild);
            } catch (IOException error) {
                LimeLog.warning("Unable to resolve Client SBS benchmark model cache: "
                        + error.getMessage());
                return false;
            }
        }
    }

    private static boolean deleteCacheTree(File entry) {
        if (entry.isDirectory()) {
            File[] children = entry.listFiles();
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!deleteCacheTree(child)) {
                    return false;
                }
            }
        }
        return !entry.exists() || entry.delete();
    }

    static boolean digestMatches(File file, String expectedSha256) throws IOException {
        MessageDigest digest = newSha256Digest();
        byte[] chunk = new byte[MODEL_IO_BUFFER_BYTES];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(chunk)) != -1) {
                digest.update(chunk, 0, count);
            }
        }
        return expectedSha256.equals(toHex(digest.digest()));
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }

    private static String toHex(byte[] hash) {
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xFF));
        }
        return result.toString();
    }
}
