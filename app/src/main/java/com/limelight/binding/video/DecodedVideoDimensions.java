package com.limelight.binding.video;

/** Immutable visible decoder dimensions published atomically to the stats path. */
final class DecodedVideoDimensions {
    final int width;
    final int height;

    DecodedVideoDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * Resolves the visible frame size from coded dimensions and an optional inclusive crop.
     * Invalid or partial format metadata falls back without publishing a torn width/height pair.
     */
    static DecodedVideoDimensions resolve(DecodedVideoDimensions fallback,
                                          Integer codedWidth, Integer codedHeight,
                                          Integer cropLeft, Integer cropTop,
                                          Integer cropRight, Integer cropBottom) {
        int fallbackWidth = fallback != null ? fallback.width : 0;
        int fallbackHeight = fallback != null ? fallback.height : 0;
        boolean completeCodedSize = codedWidth != null && codedWidth > 0
                && codedHeight != null && codedHeight > 0;
        int width = completeCodedSize ? codedWidth : fallbackWidth;
        int height = completeCodedSize ? codedHeight : fallbackHeight;

        if (cropLeft != null && cropTop != null && cropRight != null && cropBottom != null
                && cropLeft >= 0 && cropTop >= 0
                && cropRight >= cropLeft && cropBottom >= cropTop) {
            long croppedWidth = (long) cropRight - cropLeft + 1L;
            long croppedHeight = (long) cropBottom - cropTop + 1L;
            if (croppedWidth > 0L && croppedHeight > 0L
                    && cropRight < width && cropBottom < height) {
                width = (int) croppedWidth;
                height = (int) croppedHeight;
            }
        }

        if (fallback != null && fallback.width == width && fallback.height == height) {
            return fallback;
        }
        return new DecodedVideoDimensions(width, height);
    }
}
