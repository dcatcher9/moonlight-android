package com.limelight.grid.assets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AppTileArtworkTest {
    @Test
    public void initialsComeFromTheFirstTwoWordsThatStartWithAnAlphanumeric() {
        assertEquals("SB", AppTileArtwork.initialsFor("Steam Big Picture"));
        assertEquals("D", AppTileArtwork.initialsFor("Desktop"));
        // Leading punctuation is skipped rather than becoming the initial.
        assertEquals("HL", AppTileArtwork.initialsFor("  [Half] Life"));
        // A brand keeps its leading number...
        assertEquals("2M", AppTileArtwork.initialsFor("2K Music"));
        // ...but a year or sequel number never becomes the second initial.
        assertEquals("C", AppTileArtwork.initialsFor("Cyberpunk 2077"));
        assertEquals("P", AppTileArtwork.initialsFor("Portal 2"));
        assertEquals("MF", AppTileArtwork.initialsFor("Microsoft Flight Simulator 2024"));
    }

    @Test
    public void initialsDegradeQuietlyOnUnusableNames() {
        assertEquals("", AppTileArtwork.initialsFor(""));
        assertEquals("", AppTileArtwork.initialsFor("   "));
        assertEquals("", AppTileArtwork.initialsFor("-- ---"));
    }

    @Test
    public void hueIsStableAndInRange() {
        for (String name : new String[] {"Desktop", "Steam", "", "Cyberpunk 2077"}) {
            float hue = AppTileArtwork.hueFor(name);
            assertTrue(name + " hue " + hue, hue >= 0f && hue < 360f);
            assertEquals(hue, AppTileArtwork.hueFor(name), 0.0001f);
        }
    }

    @Test
    public void namesSharingAPrefixStillSeparateOnTheColourWheel() {
        // A launcher's worth of same-prefix entries is the case a prefix hash would collapse.
        float a = AppTileArtwork.hueFor("Steam Big Picture");
        float b = AppTileArtwork.hueFor("Steam Library");
        float c = AppTileArtwork.hueFor("Steam VR");
        assertNotEquals(a, b, 8f);
        assertNotEquals(b, c, 8f);
        assertNotEquals(a, c, 8f);
    }

    @Test
    public void genericHostArtworkIsClassifiedForPerAppFallbackOnColdAndWarmPaths() {
        assertTrue(CachedAppAssetLoader.isBitmapPlaceholder(
                new ScaledBitmap(130, 180, null)));
        assertTrue(CachedAppAssetLoader.isBitmapPlaceholder(
                new ScaledBitmap(628, 888, null)));
        assertFalse(CachedAppAssetLoader.isBitmapPlaceholder(
                new ScaledBitmap(600, 900, null)));
    }

    @Test
    public void generatedArtworkCacheIsByteBoundedAndExplicitlyTrimmable() {
        AppTileArtwork.clearCache();
        try {
            for (int index = 0; index < 12; index++) {
                AppTileArtwork.forApp("Generated fallback " + index, 480, 640);
                assertTrue(AppTileArtwork.cacheSizeBytes()
                        <= AppTileArtwork.CACHE_MAX_BYTES);
            }
            assertTrue(AppTileArtwork.cacheSizeBytes() > 0);
        }
        finally {
            AppTileArtwork.clearCache();
        }
        assertEquals(0, AppTileArtwork.cacheSizeBytes());
    }
}
