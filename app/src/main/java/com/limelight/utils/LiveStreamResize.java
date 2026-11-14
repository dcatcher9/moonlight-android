package com.limelight.utils;

/**
 * Immutable source and packed-output geometry of one staged Client SBS live stream resize.
 * {@link Stereo3DRenderer} publishes it from the UI thread and consumes it on the GL thread.
 */
final class LiveStreamResize {
    final int width;
    final int height;
    final int packedWidth;
    final int packedHeight;

    LiveStreamResize(int width, int height, int packedWidth, int packedHeight) {
        this.width = width;
        this.height = height;
        this.packedWidth = packedWidth;
        this.packedHeight = packedHeight;
    }
}
