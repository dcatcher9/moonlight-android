package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.res.AssetManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses the packaged archive in a disposable directory, never the production model cache. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsModelAssetCacheInstrumentedTest {
    @Test
    public void aspectRoundTripKeepsVerifiedFilesAndRepairsCorruptSpeculation() throws Exception {
        File directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "model-retention-test-").toFile();
        AssetManager assets = InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        AtomicInteger opens = new AtomicInteger();
        ClientSbsModelManifest a = ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9;
        ClientSbsModelManifest b = ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9;
        ClientSbsModelManifest c = ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9;
        try {
            File first = prepare(directory, assets, a, opens, true);
            File second = prepare(directory, assets, b, opens, true);
            assertEquals(first, prepare(directory, assets, a, opens, true));
            assertTrue(second.isFile());
            assertEquals("A -> B -> A must extract only twice", 2, opens.get());
            File speculative = prepare(directory, assets, c, opens, false);
            Files.write(speculative.toPath(), new byte[] {9});
            File repaired = prepare(directory, assets, c, opens, true);
            assertEquals(speculative, repaired);
            assertEquals("Corrupt speculative C must be extracted again", 4, opens.get());
            assertTrue(ClientSbsModelAssetCache.digestMatches(first, a.getAssetSha256()));
            assertTrue(ClientSbsModelAssetCache.digestMatches(second, b.getAssetSha256()));
            assertTrue(ClientSbsModelAssetCache.digestMatches(repaired, c.getAssetSha256()));
            assertEquals(3, directory.listFiles().length);
        } finally {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) Files.deleteIfExists(file.toPath());
            }
            Files.deleteIfExists(directory.toPath());
        }
    }

    private static File prepare(File directory, AssetManager assets, ClientSbsModelManifest model,
            AtomicInteger opens, boolean authoritative) throws Exception {
        return ClientSbsModelAssetCache.prepareVerifiedModelFile(directory, model.getId(),
                model.getAssetName(), model.getAssetSha256(), () -> {
                    opens.incrementAndGet();
                    return assets.open(model.getModelArchiveAssetName(), AssetManager.ACCESS_STREAMING);
                }, authoritative);
    }
}
