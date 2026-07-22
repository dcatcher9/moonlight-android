package com.limelight.binding.video;

/**
 * Immutable, typed snapshot of one decoder statistics window.
 *
 * <p>Frame rates in this object are calculated from the just-completed active window rather than
 * the overlapping two-window average used by the legacy text overlay. This makes the values line
 * up with client-SBS stage counters sampled by the XR stats panel at the same callback.</p>
 */
public final class StreamPerformanceSnapshot {
    public static final int INT_UNAVAILABLE = -1;

    private final long elapsedMs;
    private final int sourceWidth;
    private final int sourceHeight;
    private final float streamSequenceFps;
    private final float receivedFps;
    private final float decoderOutputFps;
    private final float decoderReleaseFps;
    private final float decoderPresentedFps;
    private final float decodeAverageMs;
    private final float decodeMaxMs;
    private final float networkLossPercent;
    private final float bandwidthMbps;
    private final int estimatedRttMs;
    private final float hostProcessingAverageMs;
    private final float hostProcessingMaxMs;
    private final String decoderName;
    private final String videoRange;

    public StreamPerformanceSnapshot(long elapsedMs,
                                     int sourceWidth,
                                     int sourceHeight,
                                     float streamSequenceFps,
                                     float receivedFps,
                                     float decoderOutputFps,
                                     float decoderReleaseFps,
                                     float decoderPresentedFps,
                                     float decodeAverageMs,
                                     float decodeMaxMs,
                                     float networkLossPercent,
                                     float bandwidthMbps,
                                     int estimatedRttMs,
                                     float hostProcessingAverageMs,
                                     float hostProcessingMaxMs,
                                     String decoderName,
                                     String videoRange) {
        this.elapsedMs = elapsedMs;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.streamSequenceFps = streamSequenceFps;
        this.receivedFps = receivedFps;
        this.decoderOutputFps = decoderOutputFps;
        this.decoderReleaseFps = decoderReleaseFps;
        this.decoderPresentedFps = decoderPresentedFps;
        this.decodeAverageMs = decodeAverageMs;
        this.decodeMaxMs = decodeMaxMs;
        this.networkLossPercent = networkLossPercent;
        this.bandwidthMbps = bandwidthMbps;
        this.estimatedRttMs = estimatedRttMs;
        this.hostProcessingAverageMs = hostProcessingAverageMs;
        this.hostProcessingMaxMs = hostProcessingMaxMs;
        this.decoderName = decoderName;
        this.videoRange = videoRange;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public int getSourceWidth() {
        return sourceWidth;
    }

    public int getSourceHeight() {
        return sourceHeight;
    }

    /** Sender frame-sequence rate, including frame numbers inferred to have been lost. */
    public float getStreamSequenceFps() {
        return streamSequenceFps;
    }

    /** Complete video frames delivered to the decoder input path. */
    public float getReceivedFps() {
        return receivedFps;
    }

    /** MediaCodec output buffers released for rendering; this is not confirmed display FPS. */
    public float getDecoderReleaseFps() {
        return decoderReleaseFps;
    }

    /** Output buffers dequeued from MediaCodec before the latest-only release policy. */
    public float getDecoderOutputFps() {
        return decoderOutputFps;
    }

    /** MediaCodec callbacks confirming that released output reached its output Surface. */
    public float getDecoderPresentedFps() {
        return decoderPresentedFps;
    }

    public float getDecodeAverageMs() {
        return decodeAverageMs;
    }

    public float getDecodeMaxMs() {
        return decodeMaxMs;
    }

    /** Whether at least one valid MediaCodec enqueue-to-output-dequeue sample was observed. */
    public boolean hasDecodeLatency() {
        return Float.isFinite(decodeAverageMs) && Float.isFinite(decodeMaxMs);
    }

    public float getNetworkLossPercent() {
        return networkLossPercent;
    }

    /** App RX+TX throughput. NaN until two valid traffic samples have been observed. */
    public float getBandwidthMbps() {
        return bandwidthMbps;
    }

    public boolean hasBandwidth() {
        return Float.isFinite(bandwidthMbps);
    }

    public int getEstimatedRttMs() {
        return estimatedRttMs;
    }

    public boolean hasEstimatedRtt() {
        return estimatedRttMs != INT_UNAVAILABLE;
    }

    public float getHostProcessingAverageMs() {
        return hostProcessingAverageMs;
    }

    public float getHostProcessingMaxMs() {
        return hostProcessingMaxMs;
    }

    public boolean hasHostProcessingLatency() {
        return Float.isFinite(hostProcessingAverageMs)
                && Float.isFinite(hostProcessingMaxMs);
    }

    public String getDecoderName() {
        return decoderName;
    }

    public String getVideoRange() {
        return videoRange;
    }
}
