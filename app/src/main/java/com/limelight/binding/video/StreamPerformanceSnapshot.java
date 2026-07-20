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
    private final float decoderReleaseFps;
    private final float decodeAverageMs;
    private final float decodeMaxMs;
    private final float networkLossPercent;
    private final float bandwidthMbps;
    private final int estimatedRttMs;
    private final int estimatedRttVarianceMs;
    private final float hostProcessingMinMs;
    private final float hostProcessingAverageMs;
    private final float hostProcessingMaxMs;
    private final String decoderName;
    private final String videoRange;

    public StreamPerformanceSnapshot(long elapsedMs,
                                     int sourceWidth,
                                     int sourceHeight,
                                     float streamSequenceFps,
                                     float receivedFps,
                                     float decoderReleaseFps,
                                     float decodeAverageMs,
                                     float decodeMaxMs,
                                     float networkLossPercent,
                                     float bandwidthMbps,
                                     int estimatedRttMs,
                                     int estimatedRttVarianceMs,
                                     float hostProcessingMinMs,
                                     float hostProcessingAverageMs,
                                     float hostProcessingMaxMs,
                                     String decoderName,
                                     String videoRange) {
        this.elapsedMs = elapsedMs;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.streamSequenceFps = streamSequenceFps;
        this.receivedFps = receivedFps;
        this.decoderReleaseFps = decoderReleaseFps;
        this.decodeAverageMs = decodeAverageMs;
        this.decodeMaxMs = decodeMaxMs;
        this.networkLossPercent = networkLossPercent;
        this.bandwidthMbps = bandwidthMbps;
        this.estimatedRttMs = estimatedRttMs;
        this.estimatedRttVarianceMs = estimatedRttVarianceMs;
        this.hostProcessingMinMs = hostProcessingMinMs;
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

    public int getEstimatedRttVarianceMs() {
        return estimatedRttVarianceMs;
    }

    public boolean hasEstimatedRtt() {
        return estimatedRttMs != INT_UNAVAILABLE
                && estimatedRttVarianceMs != INT_UNAVAILABLE;
    }

    public float getHostProcessingMinMs() {
        return hostProcessingMinMs;
    }

    public float getHostProcessingAverageMs() {
        return hostProcessingAverageMs;
    }

    public float getHostProcessingMaxMs() {
        return hostProcessingMaxMs;
    }

    public boolean hasHostProcessingLatency() {
        return Float.isFinite(hostProcessingMinMs)
                && Float.isFinite(hostProcessingAverageMs)
                && Float.isFinite(hostProcessingMaxMs);
    }

    public String getDecoderName() {
        return decoderName;
    }

    public String getVideoRange() {
        return videoRange;
    }
}
