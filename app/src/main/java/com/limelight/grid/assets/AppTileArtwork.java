package com.limelight.grid.assets;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.LruCache;

/**
 * Generated artwork for apps the host has no box art for.
 *
 * <p>The shipped {@code no_app_image} is a 200x266 raster shown on a 240x320dp card, so on a
 * headset it is both upscaled and identical for every such app — a list of them reads as a wall of
 * one grey tile. This draws a tile at the card's real pixel size instead, deriving its colour from
 * the app name so each app is recognisable by shape and colour before the label is read.</p>
 *
 * <p>Tiles stay dark: the card already lays a scrim, a bold white name and a status line over the
 * bottom of the artwork, and those must stay legible. Only the hue varies per app; lightness and
 * saturation are fixed.</p>
 */
public final class AppTileArtwork {
    /**
     * Keep generated fallback art materially smaller than the ordinary box-art cache.
     *
     * <p>An entry-counted cache is unsafe here because the key includes the density-scaled card
     * size: 32 ARGB_8888 cards can retain tens of MiB on an XR-density display. Android's
     * {@link LruCache} accepts arbitrary units, so account in actual allocated bytes.</p>
     */
    static final int CACHE_MAX_BYTES = 8 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(CACHE_MAX_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap != null ? bitmap.getAllocationByteCount() : 0;
                }
            };

    private AppTileArtwork() {
    }

    public static Bitmap forApp(String appName, int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) {
            return null;
        }
        String name = appName == null ? "" : appName.trim();
        String key = widthPx + "x" + heightPx + ":" + name;
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap tile = draw(name, widthPx, heightPx);
        CACHE.put(key, tile);
        return tile;
    }

    static int cacheSizeBytes() {
        return CACHE.size();
    }

    static void clearCache() {
        CACHE.evictAll();
    }

    private static Bitmap draw(String name, int widthPx, int heightPx) {
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float hue = hueFor(name);
        int top = Color.HSVToColor(new float[] {hue, 0.42f, 0.30f});
        int bottom = Color.HSVToColor(new float[] {(hue + 18f) % 360f, 0.55f, 0.16f});

        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setShader(new LinearGradient(0f, 0f, widthPx * 0.35f, heightPx,
                top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, widthPx, heightPx, background);

        String initials = initialsFor(name);
        if (initials.isEmpty()) {
            return bitmap;
        }

        // A watermark, not a label: the card draws the real name over the lower third, so the
        // glyph sits high and stays faint enough that it never competes with it.
        Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        glyph.setColor(Color.WHITE);
        glyph.setAlpha(38);
        glyph.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        glyph.setTextAlign(Paint.Align.CENTER);
        glyph.setTextSize(heightPx * 0.42f);

        Rect bounds = new Rect();
        glyph.getTextBounds(initials, 0, initials.length(), bounds);
        canvas.drawText(initials, widthPx / 2f,
                heightPx * 0.38f + bounds.height() / 2f, glyph);
        return bitmap;
    }

    /**
     * Stable hue in [0, 360). Uses the whole string so that names sharing a prefix — a launcher's
     * worth of "Steam ..." entries — still separate.
     */
    static float hueFor(String name) {
        int hash = 0;
        for (int i = 0; i < name.length(); i++) {
            hash = hash * 31 + name.charAt(i);
        }
        // Golden-angle stride spreads adjacent hashes across the wheel instead of clustering them.
        return Math.abs(hash % 360) * 137.508f % 360f;
    }

    /**
     * Up to two initials, taken from words beginning with a letter.
     *
     * <p>A digit only counts as an initial in the leading word, so a brand keeps its number
     * ("2K Music" gives 2M) while a version or year does not become one ("Cyberpunk 2077" gives C,
     * not the meaningless C2). Sequel numbering is extremely common in this list.</p>
     */
    static String initialsFor(String name) {
        StringBuilder initials = new StringBuilder(2);
        boolean seeking = true;
        boolean firstWord = true;
        for (int i = 0; i < name.length() && initials.length() < 2; i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c)) {
                seeking = true;
                continue;
            }
            if (!seeking) {
                continue;
            }
            if (!Character.isLetterOrDigit(c)) {
                // Bracket or punctuation opening a word: keep looking inside it rather than
                // treating the symbol as the initial and skipping the letter behind it.
                continue;
            }
            if (Character.isLetter(c) || firstWord) {
                initials.append(Character.toUpperCase(c));
            }
            seeking = false;
            firstWord = false;
        }
        return initials.toString();
    }
}
