package com.limelight.binding.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.TrafficStatsHelper;

import android.annotation.SuppressLint;
import android.util.LongSparseArray;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.os.Bundle;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodec.CodecException;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Range;
import android.view.Choreographer;
import android.view.Surface;

public class MediaCodecDecoderRenderer extends VideoDecoderRenderer implements Choreographer.FrameCallback {
    public enum ActualColorRange {
        /** MediaCodec has not reported a usable output color range. */
        UNKNOWN(false),
        LIMITED(true),
        FULL(true),
        /** MediaCodec reported a color-range value that Android does not define. */
        UNRECOGNIZED(true);

        private final boolean decoderReported;

        ActualColorRange(boolean decoderReported) {
            this.decoderReported = decoderReported;
        }

        public boolean hasDecoderEvidence() {
            return decoderReported;
        }

        public boolean isKnown() {
            return this == LIMITED || this == FULL;
        }
    }

    // Expensive per-frame timing is opt-in. The Stats panel and explicit performance logging are
    // diagnostic tools; normal streaming must not pay for timestamp maps or their cross-thread
    // lock contention.
    private volatile boolean performanceTelemetryEnabled;
    // Decode latency tracking: map PTS(us) -> enqueue time (ns)
    private final LongSparseArray<Long> enqueueNsByPtsUs = new LongSparseArray<>();

    private static final int OUTPUT_DEQUEUE_TIMEOUT_US = 2000;
    private static final int INPUT_DEQUEUE_HANG_TIMEOUT_MS = 5000;
    private static final String MEDIA_FORMAT_KEY_CROP_LEFT = "crop-left";
    private static final String MEDIA_FORMAT_KEY_CROP_TOP = "crop-top";
    private static final String MEDIA_FORMAT_KEY_CROP_RIGHT = "crop-right";
    private static final String MEDIA_FORMAT_KEY_CROP_BOTTOM = "crop-bottom";
    private static final String[] DECODER_LOW_LATENCY_FORMAT_KEYS = {
            "low-latency",
            "vdec-lowlatency",
            "media.low-latency.enable",
            "vendor.low-latency.enable",
            "vendor.qti-ext-dec-low-latency.enable",
            "vendor.mtk.vdec.low-latency.mode",
            "vendor.mtk.vdec.ultra-low-latency",
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req",
            "vendor.rtc-ext-dec-low-latency.enable",
    };

    private void handleOutputFormatChanged() {
        LimeLog.info("Output format changed");
        outputFormat = videoDecoder.getOutputFormat();
        DecodedVideoDimensions previousDimensions = currentOutputDimensions.get();
        currentOutputDimensions.set(DecodedVideoDimensions.resolve(
                previousDimensions,
                getOptionalFormatInteger(outputFormat, MediaFormat.KEY_WIDTH),
                getOptionalFormatInteger(outputFormat, MediaFormat.KEY_HEIGHT),
                getOptionalFormatInteger(outputFormat, MEDIA_FORMAT_KEY_CROP_LEFT),
                getOptionalFormatInteger(outputFormat, MEDIA_FORMAT_KEY_CROP_TOP),
                getOptionalFormatInteger(outputFormat, MEDIA_FORMAT_KEY_CROP_RIGHT),
                getOptionalFormatInteger(outputFormat, MEDIA_FORMAT_KEY_CROP_BOTTOM)));
        LimeLog.info("New output format: " + outputFormat);
        logColorFormat("Actual decoder output color", outputFormat);
    }

    private static Integer getOptionalFormatInteger(MediaFormat format, String key) {
        if (format == null || !format.containsKey(key)) {
            return null;
        }
        try {
            return format.getInteger(key);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    static boolean requestsDecoderLowLatency(MediaFormat format) {
        for (String key : DECODER_LOW_LATENCY_FORMAT_KEYS) {
            Integer value = getOptionalFormatInteger(format, key);
            if (value != null && value != 0) {
                return true;
            }
        }
        return false;
    }

    static String describeVideoCodec(int format) {
        switch (format) {
            case MoonBridge.VIDEO_FORMAT_H264:
                return "H.264 High, 8-bit";
            case MoonBridge.VIDEO_FORMAT_H265:
                return "HEVC Main, 8-bit";
            case MoonBridge.VIDEO_FORMAT_H265_MAIN10:
                return "HEVC Main 10, 10-bit";
            case MoonBridge.VIDEO_FORMAT_AV1_MAIN8:
                return "AV1 Main, 8-bit";
            case MoonBridge.VIDEO_FORMAT_AV1_MAIN10:
                return "AV1 Main, 10-bit";
            default:
                if ((format & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                    return String.format(java.util.Locale.US, "AV1 profile 0x%04x", format);
                }
                if ((format & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                    return String.format(java.util.Locale.US, "HEVC profile 0x%04x", format);
                }
                if ((format & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                    return String.format(java.util.Locale.US, "H.264 profile 0x%04x", format);
                }
                return String.format(java.util.Locale.US, "Unknown format 0x%04x", format);
        }
    }

    static String describeOutputPacing(int framePacing) {
        switch (framePacing) {
            case PreferenceConfiguration.FRAME_PACING_BALANCED:
                return "Balanced (vsync queue)";
            case PreferenceConfiguration.FRAME_PACING_CAP_FPS:
                return "FPS cap";
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
                return "Maximum smoothness";
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
            default:
                return "Lowest latency (latest frame)";
        }
    }

    // Update stats using real decode time: enqueue->dequeue, instead of uptime - PTS
    private void updateDecodeLatencyStats(long presentationTimeUs) {
        if (!performanceTelemetryEnabled) {
            return;
        }
        long nowNs = System.nanoTime();
        synchronized (videoStatsLock) {
            activeWindowVideoStats.totalFramesDecoded++;
            Long enqNs = enqueueNsByPtsUs.get(presentationTimeUs);
            if (enqNs == null) {
                return;
            }

            enqueueNsByPtsUs.delete(presentationTimeUs);
            long decodeNs = nowNs - enqNs;
            if (decodeNs >= 0 && decodeNs < 1_000_000_000L) {
                activeWindowVideoStats.decoderTimeNs += decodeNs;
                activeWindowVideoStats.maxDecoderTimeNs = Math.max(
                        activeWindowVideoStats.maxDecoderTimeNs, decodeNs);
                activeWindowVideoStats.decoderLatencySamples++;
                if (!USE_FRAME_RENDER_TIME) {
                    activeWindowVideoStats.totalTimeMs += decodeNs / 1_000_000L;
                }
            }
        }
    }

    private void recordFrameReleasedForRender() {
        if (!performanceTelemetryEnabled) {
            return;
        }
        synchronized (videoStatsLock) {
            activeWindowVideoStats.totalFramesRendered++;
        }
    }

    private void recordFramePresented() {
        if (!performanceTelemetryEnabled) {
            return;
        }
        synchronized (videoStatsLock) {
            activeWindowVideoStats.totalFramesPresented++;
        }
    }

    private void releaseOutputBufferForRender(int bufferIndex, long renderTimestampNs) {
        videoDecoder.releaseOutputBuffer(bufferIndex, renderTimestampNs);
        acknowledgePresentationTransitionRendered();
        recordFrameReleasedForRender();
    }

    private void releaseOutputBufferForRender(int bufferIndex) {
        videoDecoder.releaseOutputBuffer(bufferIndex, true);
        acknowledgePresentationTransitionRendered();
        recordFrameReleasedForRender();
    }

    /** Removes inputs for which MediaCodec never produced an output buffer. Lock must be held. */
    private void pruneStaleDecodeTimestampsLocked(long nowNs) {
        long staleBeforeNs = nowNs - 2_000_000_000L;
        for (int i = enqueueNsByPtsUs.size() - 1; i >= 0; i--) {
            Long enqueueNs = enqueueNsByPtsUs.valueAt(i);
            if (enqueueNs != null && enqueueNs < staleBeforeNs) {
                enqueueNsByPtsUs.removeAt(i);
            }
        }
    }

    private static final boolean USE_FRAME_RENDER_TIME = false;
    private static final boolean FRAME_RENDER_TIME_ONLY = USE_FRAME_RENDER_TIME && false;

    // Direct submission invokes MediaCodec from the native receive thread. A blocked codec input
    // buffer then prevents that thread from draining UDP, so use moonlight-common-c's dedicated
    // decoder thread and bounded queue instead. The selected hardware decoder and its low-latency
    // MediaFormat options are unchanged.
    private static final boolean ENABLE_DIRECT_SUBMIT = false;
    private static final int DECODER_MAX_INPUT_SIZE_BYTES = 16 * 1024 * 1024;

    // Used on versions < 5.0
    private ByteBuffer[] legacyInputBuffers;

    private MediaCodecInfo avcDecoder;
    private MediaCodecInfo hevcDecoder;
    private MediaCodecInfo av1Decoder;

    private final ArrayList<byte[]> vpsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> spsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> ppsBuffers = new ArrayList<>();
    private boolean submittedCsd;
    private byte[] currentHdrMetadata;

    private int nextInputBufferIndex = -1;
    private ByteBuffer nextInputBuffer;
    private volatile boolean inputBufferCapacityLogged;
    // Decoder input is single-threaded. These two fields feed one preallocated commit callback so
    // the atomic transition-admission path does not allocate a capturing lambda for every frame.
    private long pendingInputCommitTimestampUs;
    private int pendingInputCommitCodecFlags;
    private final DecoderModeTransitionGate.InputCommitter inputCommitter =
            this::commitPendingInputBuffer;

    private Context context;
    private Activity activity;
    private MediaCodec videoDecoder;
    private Thread rendererThread;
    private boolean needsSpsBitstreamFixup, isExynos4;
    private boolean adaptivePlayback, directSubmit, fusedIdrFrame;
    private boolean constrainedHighProfile;
    private boolean refFrameInvalidationAvc, refFrameInvalidationHevc, refFrameInvalidationAv1;
    private byte optimalSlicesPerFrame;
    private boolean refFrameInvalidationActive;
    private int initialWidth, initialHeight;
    private final AtomicReference<DecodedVideoDimensions> currentOutputDimensions =
            new AtomicReference<>(new DecodedVideoDimensions(0, 0));
    private boolean invertResolution;
    private int videoFormat;
    private Surface renderTarget;
    private volatile boolean stopping;
    private final AtomicBoolean stopPrepared = new AtomicBoolean();
    private CrashListener crashListener;
    private boolean reportedCrash;
    private int consecutiveCrashCount;
    private String glRenderer;
    private boolean foreground = true;
    private PerfOverlayListener perfListener;

    private static final int CR_MAX_TRIES = 10;
    private static final int CR_RECOVERY_TYPE_NONE = 0;
    private static final int CR_RECOVERY_TYPE_FLUSH = 1;
    private static final int CR_RECOVERY_TYPE_RESTART = 2;
    private static final int CR_RECOVERY_TYPE_RESET = 3;
    private AtomicInteger codecRecoveryType = new AtomicInteger(CR_RECOVERY_TYPE_NONE);
    private final Object codecRecoveryMonitor = new Object();
    private final DecoderModeTransitionGate modeTransitionFrameGate =
            new DecoderModeTransitionGate();
    private static final long MODE_TRANSITION_IDR_RETRY_MS = 500L;
    private static final long MODE_TRANSITION_TIMEOUT_MS = 2_500L;
    private final Object modeTransitionStateLock = new Object();
    private final Handler modeTransitionHandler;
    private int modeTransitionWatchdogGeneration;
    private boolean modeTransitionWatchdogActive;
    private long modeTransitionWatchdogDeadlineMs;
    private int nextPresentationTransitionGeneration;
    private int activePresentationTransitionGeneration;
    private volatile IntConsumer modeTransitionOpenedListener;
    private volatile IntConsumer modeTransitionTimedOutListener;
    private volatile IntConsumer activeVideoFormatListener;
    // Guarded by codecRecoveryMonitor. Presentation-mode recovery is expected and must not consume
    // the limited codec-error recovery budget.
    private boolean presentationModeRecoveryPending;

    // Each thread that touches the MediaCodec object or any associated buffers must have a flag
    // here and must call doCodecRecoveryIfRequired() on a regular basis.
    private static final int CR_FLAG_INPUT_THREAD = 0x1;
    private static final int CR_FLAG_RENDER_THREAD = 0x2;
    private static final int CR_FLAG_CHOREOGRAPHER = 0x4;
    private static final int CR_FLAG_ALL = CR_FLAG_INPUT_THREAD | CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER;
    private int codecRecoveryThreadQuiescedFlags = 0;
    private int codecRecoveryAttempts = 0;
    // HDR metadata arrives over the control stream after the decoder is initially configured.
    // Track that expected reset separately so it isn't reported or counted as a codec failure.
    private volatile boolean hdrMetadataRecoveryPending;

    private MediaFormat inputFormat;
    private volatile MediaFormat outputFormat;
    private MediaFormat configuredFormat;

    private boolean needsBaselineSpsHack;
    private SeqParameterSet savedSps;

    private RendererException initialException;
    private long initialExceptionTimestamp;
    private static final int EXCEPTION_REPORT_DELAY_MS = 3000;

    private VideoStats activeWindowVideoStats;
    private VideoStats lastWindowVideoStats;
    private VideoStats globalVideoStats;
    private final Object videoStatsLock = new Object();

    private long lastTimestampUs;
    private volatile int lastFrameNumber;
    /** Newest frame observed at submitDecodeUnit() entry, before any transition-gate decision. */
    private volatile int latestInputFrameNumber;
    /** Input-thread-only queue sample captured at the first callback for each native frame. */
    private boolean hasDecoderQueueSample;
    private int decoderQueueSampleFrameNumber;
    private long decoderQueueSampleTimeMs;
    private int decoderQueueSamplePendingFrames;
    /** Input-thread-only: the next accepted serial gap was intentionally gated by a mode switch. */
    private boolean intentionalInputDiscontinuityPending;
    private int refreshRate;
    private PreferenceConfiguration prefs;

    private float minDecodeTime = Float.MAX_VALUE;
    private String minDecodeTimeFullLog = "";

    private long lastNetDataNum;
    private long lastNetDataSampleTimestampMs;
    private boolean hasLastNetDataSample;
    private static final class DecodedOutputBuffer {
        final int index;
        final long presentationTimeUs;

        DecodedOutputBuffer(int index, long presentationTimeUs) {
            this.index = index;
            this.presentationTimeUs = presentationTimeUs;
        }
    }

    private enum InputQueueResult {
        QUEUED,
        FAILED,
        TRANSITION_DROPPED
    }

    private LinkedBlockingQueue<DecodedOutputBuffer> outputBufferQueue = new LinkedBlockingQueue<>();
    private static final int OUTPUT_BUFFER_QUEUE_LIMIT = 2;
    private long lastRenderedFrameTimeNanos;
    private HandlerThread choreographerHandlerThread;
    private Handler choreographerHandler;
    private final AtomicBoolean firstFrameRendered = new AtomicBoolean();
    private volatile Runnable firstFrameRenderedListener;

    private int numSpsIn;
    private int numPpsIn;
    private int numVpsIn;
    private int numFramesIn;
    private int numFramesOut;

    private int targetFps = 0;

    private MediaCodecInfo findAvcDecoder() {
        MediaCodecInfo decoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        if (decoder == null) {
            decoder = MediaCodecHelper.findFirstDecoder("video/avc");
        }
        return decoder;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private boolean decoderCanMeetPerformancePoint(MediaCodecInfo.VideoCapabilities caps, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecInfo.VideoCapabilities.PerformancePoint targetPerfPoint = new MediaCodecInfo.VideoCapabilities.PerformancePoint(initialWidth, initialHeight, Math.round(prefs.fps));
            List<MediaCodecInfo.VideoCapabilities.PerformancePoint> perfPoints = caps.getSupportedPerformancePoints();
            if (perfPoints != null) {
                for (MediaCodecInfo.VideoCapabilities.PerformancePoint perfPoint : perfPoints) {
                    // If we find a performance point that covers our target, we're good to go
                    if (perfPoint.covers(targetPerfPoint)) {
                        return true;
                    }
                }

                // We had performance point data but none met the specified streaming settings
                return false;
            }

            // Fall-through to try the Android M API if there's no performance point data
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // We'll ask the decoder what it can do for us at this resolution and see if our
                // requested frame rate falls below or inside the range of achievable frame rates.
                Range<Double> fpsRange = caps.getAchievableFrameRatesFor(initialWidth, initialHeight);
                if (fpsRange != null) {
                    return prefs.fps <= fpsRange.getUpper();
                }

                // Fall-through to try the Android L API if there's no performance point data
            } catch (IllegalArgumentException e) {
                // Video size not supported at any frame rate
                return false;
            }
        }

        // As a last resort, we will use areSizeAndRateSupported() which is explicitly NOT a
        // performance metric, but it can work at least for the purpose of determining if
        // the codec is going to die when given a stream with the specified settings.
        return caps.areSizeAndRateSupported(initialWidth, initialHeight, prefs.fps);
    }

    private boolean decoderCanMeetPerformancePointWithHevcAndNotAvc(MediaCodecInfo hevcDecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
            MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

            return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(hevcCaps, prefs);
        }
        else {
            // No performance data
            return false;
        }
    }

    private boolean decoderCanMeetPerformancePointWithAv1AndNotHevc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo hevcDecoderInfo, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();
            MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

            return !decoderCanMeetPerformancePoint(hevcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
        }
        else {
            // No performance data
            return false;
        }
    }

    private boolean decoderCanMeetPerformancePointWithAv1AndNotAvc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
            MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();

            return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
        }
        else {
            // No performance data
            return false;
        }
    }

    private MediaCodecInfo findHevcDecoder(PreferenceConfiguration prefs, boolean meteredNetwork, boolean requestedHdr) {
        // Don't return anything if H.264 is forced
        if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return null;
        }

        // We don't try the first HEVC decoder. We'd rather fall back to hardware accelerated AVC instead
        //
        // We need HEVC Main profile, so we could pass that constant to findProbableSafeDecoder, however
        // some decoders (at least Qualcomm's Snapdragon 805) don't properly report support
        // for even required levels of HEVC.
        // Dedicated ".low_latency" components couple network drain to fragile synchronous codec
        // behavior on Qualcomm XR devices. Use the standard HEVC component; we still request the
        // platform low-latency feature through MediaFormat where the regular component supports it.
        MediaCodecInfo hevcDecoderInfo =
                MediaCodecHelper.findProbableSafeRegularDecoder("video/hevc", -1);
        if (hevcDecoderInfo != null) {
            if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
                LimeLog.info("Found HEVC decoder, but it's not whitelisted - "+hevcDecoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
                    LimeLog.info("Forcing HEVC enabled despite non-whitelisted decoder");
                }
                // HDR implies HEVC forced on, since HEVCMain10HDR10 is required for HDR.
                else if (requestedHdr) {
                    LimeLog.info("Forcing HEVC enabled for HDR streaming");
                }
                // > 4K streaming also requires HEVC, so force it on there too.
                else if (initialWidth > 4096 || initialHeight > 4096) {
                    LimeLog.info("Forcing HEVC enabled for over 4K streaming");
                }
                // Use HEVC if the H.264 decoder is unable to meet the performance point
                else if (avcDecoder != null && decoderCanMeetPerformancePointWithHevcAndNotAvc(hevcDecoderInfo, avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted HEVC decoder to meet performance point");
                }
                else {
                    return null;
                }
            }
        }

        return hevcDecoderInfo;
    }

    private MediaCodecInfo findAv1Decoder(PreferenceConfiguration prefs) {
        // For now, don't use AV1 unless explicitly requested
        if (prefs.videoFormat != PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return null;
        }

        MediaCodecInfo decoderInfo = MediaCodecHelper.findProbableSafeRegularDecoder(
                "video/av01", -1);
        if (decoderInfo != null) {
            if (!MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
                LimeLog.info("Found AV1 decoder, but it's not whitelisted - "+decoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1) {
                    LimeLog.info("Forcing AV1 enabled despite non-whitelisted decoder");
                }
                // Use AV1 if the HEVC decoder is unable to meet the performance point
                else if (hevcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotHevc(decoderInfo, hevcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                // Use AV1 if the H.264 decoder is unable to meet the performance point and we have no HEVC decoder
                else if (hevcDecoder == null && decoderCanMeetPerformancePointWithAv1AndNotAvc(decoderInfo, avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                else {
                    return null;
                }
            }
        }

        return decoderInfo;
    }

    public void setRenderTarget(Surface renderTarget) {
        this.renderTarget = renderTarget;
    }

    /** Called once MediaCodec confirms that the first video frame reached its output surface. */
    public void setFirstFrameRenderedListener(Runnable listener) {
        firstFrameRenderedListener = listener;
        if (listener != null && firstFrameRendered.get()) {
            listener.run();
        }
    }

    /** Completion callbacks for the decoder side of an XR presentation-mode transaction. */
    public void setPresentationModeTransitionListeners(IntConsumer opened, IntConsumer timedOut) {
        modeTransitionOpenedListener = opened;
        modeTransitionTimedOutListener = timedOut;
    }

    public void setActiveVideoFormatListener(IntConsumer listener) {
        activeVideoFormatListener = listener;
    }

    /** Enables per-frame timing only for the visible Stats panel or explicit perf logging. */
    public void setPerformanceTelemetryEnabled(boolean enabled) {
        synchronized (videoStatsLock) {
            if (performanceTelemetryEnabled == enabled) {
                return;
            }

            // Samples captured under the old state must not leak into a later visible window.
            enqueueNsByPtsUs.clear();
            if (enabled) {
                restartPerformanceTelemetryWindow(
                        activeWindowVideoStats, lastWindowVideoStats, globalVideoStats,
                        SystemClock.uptimeMillis());
            }

            // Publish the new state only after the new window and timestamp map are coherent.
            performanceTelemetryEnabled = enabled;
        }
    }

    static void restartPerformanceTelemetryWindow(VideoStats activeWindow,
                                                  VideoStats lastWindow,
                                                  VideoStats globalStats,
                                                  long nowMs) {
        if (activeWindow.measurementStartTimestamp != 0) {
            // Preserve aggregate stream/loss accounting from the partial window that is ending.
            globalStats.add(activeWindow);
        }
        activeWindow.clear();
        activeWindow.measurementStartTimestamp = nowMs;
        // The first enabled sample must not average against a telemetry-disabled window.
        lastWindow.clear();
    }

    /** Samples this process's combined RX+TX throughput using the exact interval between reads. */
    private float sampleAppNetworkThroughputMbps(long nowMs) {
        long rxBytes = TrafficStatsHelper.getPackageRxBytes(Process.myUid());
        long txBytes = TrafficStatsHelper.getPackageTxBytes(Process.myUid());
        if (rxBytes == TrafficStats.UNSUPPORTED || txBytes == TrafficStats.UNSUPPORTED) {
            hasLastNetDataSample = false;
            return Float.NaN;
        }

        long totalBytes = rxBytes + txBytes;
        float throughputMbps = Float.NaN;
        if (hasLastNetDataSample
                && nowMs > lastNetDataSampleTimestampMs
                && totalBytes >= lastNetDataNum) {
            long elapsedMs = nowMs - lastNetDataSampleTimestampMs;
            long transferredBytes = totalBytes - lastNetDataNum;
            throughputMbps = (float) ((transferredBytes * 8.0) / (elapsedMs * 1000.0));
        }

        lastNetDataNum = totalBytes;
        lastNetDataSampleTimestampMs = nowMs;
        hasLastNetDataSample = true;
        return throughputMbps;
    }

    private void notifyFirstFrameRendered() {
        if (!firstFrameRendered.compareAndSet(false, true)) {
            return;
        }

        Runnable listener = firstFrameRenderedListener;
        if (listener != null) {
            listener.run();
        }
    }

    public MediaCodecDecoderRenderer(Activity activity, PreferenceConfiguration prefs,
                                     CrashListener crashListener, int consecutiveCrashCount,
                                     boolean meteredData, boolean requestedHdr, boolean invertResolution,
                                     String glRenderer, PerfOverlayListener perfListener) {
        //dumpDecoders();

        this.modeTransitionHandler = new Handler(Looper.getMainLooper());
        this.context = activity;
        this.activity = activity;
        this.prefs = prefs;
        this.performanceTelemetryEnabled = shouldDispatchPerformanceSnapshot(
                prefs.enablePerfOverlay, prefs.enablePerfLogging);
        this.crashListener = crashListener;
        this.consecutiveCrashCount = consecutiveCrashCount;
        this.glRenderer = glRenderer;
        this.perfListener = perfListener;
        this.invertResolution = invertResolution;

        this.activeWindowVideoStats = new VideoStats();
        this.lastWindowVideoStats = new VideoStats();
        this.globalVideoStats = new VideoStats();

        avcDecoder = findAvcDecoder();
        if (avcDecoder != null) {
            LimeLog.info("Selected AVC decoder: "+avcDecoder.getName());
        }
        else {
            LimeLog.warning("No AVC decoder found");
        }

        hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr);
        if (hevcDecoder != null) {
            LimeLog.info("Selected HEVC decoder: "+hevcDecoder.getName());
        }
        else {
            LimeLog.info("No HEVC decoder found");
        }

        av1Decoder = findAv1Decoder(prefs);
        if (av1Decoder != null) {
            LimeLog.info("Selected AV1 decoder: "+av1Decoder.getName());
        }
        else {
            LimeLog.info("No AV1 decoder found");
        }

        // Set attributes that are queried in getCapabilities(). This must be done here
        // because getCapabilities() may be called before setup() in current versions of the common
        // library. The limitation of this is that we don't know whether we're using HEVC or AVC.
        int avcOptimalSlicesPerFrame = 0;
        int hevcOptimalSlicesPerFrame = 0;
        if (avcDecoder != null) {
            directSubmit = shouldUseDirectSubmit(
                    MediaCodecHelper.decoderCanDirectSubmit(avcDecoder.getName()));
            refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(avcDecoder.getName(), initialHeight);
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avcDecoder.getName());

            if (directSubmit) {
                LimeLog.info("Decoder "+avcDecoder.getName()+" will use direct submit");
            }
            if (refFrameInvalidationAvc) {
                LimeLog.info("Decoder "+avcDecoder.getName()+" will use reference frame invalidation for AVC");
            }
            LimeLog.info("Decoder "+avcDecoder.getName()+" wants "+avcOptimalSlicesPerFrame+" slices per frame");
        }
        LimeLog.info("Video decoder submission: "
                + (directSubmit ? "direct" : "buffered (network receive decoupled)"));

        if (hevcDecoder != null) {
            refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevcDecoder);
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevcDecoder.getName());

            if (refFrameInvalidationHevc) {
                LimeLog.info("Decoder "+hevcDecoder.getName()+" will use reference frame invalidation for HEVC");
            }

            LimeLog.info("Decoder "+hevcDecoder.getName()+" wants "+hevcOptimalSlicesPerFrame+" slices per frame");
        }

        if (av1Decoder != null) {
            refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1Decoder);

            if (refFrameInvalidationAv1) {
                LimeLog.info("Decoder "+av1Decoder.getName()+" will use reference frame invalidation for AV1");
            }
        }

        // Use the larger of the two slices per frame preferences
        optimalSlicesPerFrame = (byte)Math.max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame);
        LimeLog.info("Requesting "+optimalSlicesPerFrame+" slices per frame");

        if (consecutiveCrashCount % 2 == 1) {
            refFrameInvalidationAvc = refFrameInvalidationHevc = false;
            LimeLog.warning("Disabling RFI due to previous crash");
        }
    }

    public boolean isHevcSupported() {
        return hevcDecoder != null;
    }

    public boolean isAvcSupported() {
        return avcDecoder != null;
    }

    public boolean isHevcMain10Hdr10Supported() {
        if (hevcDecoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : hevcDecoder.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                LimeLog.info("HEVC decoder "+hevcDecoder.getName()+" supports HEVC Main10 HDR10");
                return true;
            }
        }

        return false;
    }

    public boolean isAv1Supported() {
        return av1Decoder != null;
    }

    public boolean isAv1Main10Supported() {
        if (av1Decoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : av1Decoder.getCapabilitiesForType("video/av01").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                LimeLog.info("AV1 decoder "+av1Decoder.getName()+" supports AV1 Main 10 HDR10");
                return true;
            }
        }

        return false;
    }

    static boolean isMain10Hdr10SupportedForPreference(
            PreferenceConfiguration.FormatOption format,
            boolean hevcMain10Supported,
            boolean av1Main10Supported) {
        switch (format) {
            case FORCE_AV1:
                return av1Main10Supported;
            case AUTO:
            case FORCE_HEVC:
                return hevcMain10Supported;
            case FORCE_H264:
            default:
                return false;
        }
    }

    /** Main10 support for the codec this preference causes RTSP to prioritize. */
    public boolean isPreferredMain10Hdr10Supported() {
        return isMain10Hdr10SupportedForPreference(
                prefs.videoFormat,
                isHevcMain10Hdr10Supported(),
                isAv1Main10Supported());
    }

    public int getPreferredColorSpace() {
        // Default to Rec 709 which is probably better supported on modern devices.
        //
        // We are sticking to Rec 601 on older devices unless the device has an HEVC decoder
        // to avoid possible regressions (and they are < 5% of installed devices). If we have
        // an HEVC decoder, we will use Rec 709 (even for H.264) since we can't choose a
        // colorspace by codec (and it's probably safe to say a SoC with HEVC decoding is
        // plenty modern enough to handle H.264 VUI colorspace info).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || hevcDecoder != null || av1Decoder != null) {
            return MoonBridge.COLORSPACE_REC_709;
        }
        else {
            return MoonBridge.COLORSPACE_REC_601;
        }
    }

    public int getPreferredColorRange() {
        if (prefs.fullRange) {
            return MoonBridge.COLOR_RANGE_FULL;
        }
        else {
            return MoonBridge.COLOR_RANGE_LIMITED;
        }
    }

    static ActualColorRange resolveActualColorRange(Integer decoderReportedRange) {
        if (decoderReportedRange == null) {
            return ActualColorRange.UNKNOWN;
        }
        if (decoderReportedRange == MediaFormat.COLOR_RANGE_FULL) {
            return ActualColorRange.FULL;
        }
        if (decoderReportedRange == MediaFormat.COLOR_RANGE_LIMITED) {
            return ActualColorRange.LIMITED;
        }
        return ActualColorRange.UNRECOGNIZED;
    }

    /**
     * Returns only the range reported in MediaCodec's output format. The requested input range is
     * deliberately not used as a fallback because it is not evidence of the decoder's output.
     */
    public ActualColorRange getActualColorRange() {
        MediaFormat currentOutputFormat = outputFormat;
        return resolveActualColorRange(getOptionalFormatInteger(
                currentOutputFormat, MediaFormat.KEY_COLOR_RANGE));
    }

    static int resolveEffectiveColorRange(ActualColorRange actualRange, int preferredRange) {
        switch (actualRange) {
            case FULL:
                return MoonBridge.COLOR_RANGE_FULL;
            case LIMITED:
                return MoonBridge.COLOR_RANGE_LIMITED;
            case UNKNOWN:
            case UNRECOGNIZED:
            default:
                return preferredRange;
        }
    }

    /**
     * Returns the decoder-reported range when known, otherwise the requested range. Rendering code
     * that must choose a concrete range should use this explicitly named fallback API.
     */
    public int getEffectiveColorRange() {
        return resolveEffectiveColorRange(getActualColorRange(), getPreferredColorRange());
    }

    private String describeActualColorRange() {
        switch (getActualColorRange()) {
            case FULL:
                return context.getString(R.string.video_range_full);
            case LIMITED:
                return context.getString(R.string.video_range_limited);
            case UNRECOGNIZED:
                return "Unknown (decoder reported an unsupported value)";
            case UNKNOWN:
            default:
                String requestedRange = context.getString(
                        getPreferredColorRange() == MoonBridge.COLOR_RANGE_FULL
                                ? R.string.video_range_full : R.string.video_range_limited);
                return "Unknown (decoder did not report; requested " + requestedRange + ")";
        }
    }

    private static String colorRangeName(MediaFormat format) {
        Integer range = getOptionalFormatInteger(format, MediaFormat.KEY_COLOR_RANGE);
        if (range == null) {
            return "unset";
        }
        if (range == MediaFormat.COLOR_RANGE_FULL) {
            return "FULL";
        }
        if (range == MediaFormat.COLOR_RANGE_LIMITED) {
            return "LIMITED";
        }
        return "unknown(" + range + ")";
    }

    private static String colorStandardName(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
            return "unset";
        }
        int standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD);
        if (standard == MediaFormat.COLOR_STANDARD_BT709) {
            return "BT709";
        }
        if (standard == MediaFormat.COLOR_STANDARD_BT2020) {
            return "BT2020";
        }
        if (standard == MediaFormat.COLOR_STANDARD_BT601_NTSC) {
            return "BT601_NTSC";
        }
        if (standard == MediaFormat.COLOR_STANDARD_BT601_PAL) {
            return "BT601_PAL";
        }
        return "unknown(" + standard + ")";
    }

    private static String colorTransferName(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            return "unset";
        }
        int transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
        if (transfer == MediaFormat.COLOR_TRANSFER_ST2084) {
            return "ST2084";
        }
        if (transfer == MediaFormat.COLOR_TRANSFER_HLG) {
            return "HLG";
        }
        if (transfer == MediaFormat.COLOR_TRANSFER_LINEAR) {
            return "LINEAR";
        }
        if (transfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) {
            return "SDR";
        }
        return "unknown(" + transfer + ")";
    }

    private static void logColorFormat(String label, MediaFormat format) {
        LimeLog.info(label + ": range=" + colorRangeName(format)
                + ", standard=" + colorStandardName(format)
                + ", transfer=" + colorTransferName(format)
                + ", hdr-static=" + (format != null
                        && format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)));
    }

    public void notifyVideoForeground() {
        foreground = true;
    }

    public void notifyVideoBackground() {
        foreground = false;
    }

    public int getActiveVideoFormat() {
        return this.videoFormat;
    }

    private MediaFormat createBaseMediaFormat(String mimeType) {
        MediaFormat videoFormat = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight);

        // Allow oversized UHQ/movie keyframes to remain a single access unit. This is a capacity
        // hint only; it does not add queued frames or change the selected decoder's latency mode.
        applyDecoderInputCapacity(videoFormat);

        // Avoid setting KEY_FRAME_RATE on Lollipop and earlier to reduce compatibility risk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate);
        }

        // Populate keys for adaptive playback
        if (adaptivePlayback) {
            // Host depth SBS makes the host switch the encoded frame from W x H (2D) to a packed
            // 2W' x H' side-by-side frame on the fly. Pre-size the adaptive-playback max so
            // MediaCodec absorbs it without a reconfigure. The packed width is capped at the
            // selected codec's packed-width ceiling, and the packed height never exceeds
            // the 2D height, so max height stays initialHeight.
            int maxWidth = initialWidth;
            if (prefs != null && prefs.isHostDoubledWidthMode()) {
                maxWidth = Math.min(initialWidth * 2,
                        PreferenceConfiguration.maxHostSbsPackedWidthForVideoFormat(
                                getActiveVideoFormat()));
            }
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, maxWidth);
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, initialHeight);
        }

        // Android 7.0 adds color options to the MediaFormat
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            videoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                    getPreferredColorRange() == MoonBridge.COLOR_RANGE_FULL ?
                            MediaFormat.COLOR_RANGE_FULL : MediaFormat.COLOR_RANGE_LIMITED);

            // If the stream is HDR-capable, the decoder will detect transitions in color standards
            // rather than us hardcoding them into the MediaFormat.
            if ((getActiveVideoFormat() & MoonBridge.VIDEO_FORMAT_MASK_10BIT) == 0) {
                // Set color format keys when not in HDR mode, since we know they won't change
                videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                switch (getPreferredColorSpace()) {
                    case MoonBridge.COLORSPACE_REC_601:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
                        break;
                    case MoonBridge.COLORSPACE_REC_709:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                        break;
                    case MoonBridge.COLORSPACE_REC_2020:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                        break;
                }
            }
        }

        logColorFormat("Requested decoder color", videoFormat);

        return videoFormat;
    }

    static void applyDecoderInputCapacity(MediaFormat format) {
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DECODER_MAX_INPUT_SIZE_BYTES);
    }

    private void configureAndStartDecoder(MediaFormat format) {
        // Set HDR metadata if present
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (currentHdrMetadata != null) {
                ByteBuffer hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer hdrMetadata = ByteBuffer.wrap(currentHdrMetadata).order(ByteOrder.LITTLE_ENDIAN);

                // Create a HDMI Dynamic Range and Mastering InfoFrame as defined by CTA-861.3
                hdrStaticInfo.put((byte) 0); // Metadata type
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // RX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // RY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // GX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // GY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // BX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // BY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // White X
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // White Y
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max mastering luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Min mastering luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max content luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max frame average luminance

                hdrStaticInfo.rewind();
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo);
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO);
            }
        }

        LimeLog.info("Configuring with format: "+format);

        // A restarted/recreated decoder has not produced an output format yet. Never expose the
        // previous codec instance's color-range report while this configure transaction runs.
        outputFormat = null;
        videoDecoder.configure(format, renderTarget, null, 0);
        inputBufferCapacityLogged = false;

        try { applySurfaceFrameRate(renderTarget, targetFps); } catch (Throwable ignored) {}

        try {
            MediaCodecInfo __info = (android.os.Build.VERSION.SDK_INT >= 21) ? videoDecoder.getCodecInfo() : null;
            String __name = (__info != null) ? __info.getName() : "<unknown>";
            LimeLog.info("Decoder name: " + __name);
        } catch (Throwable t) {
            LimeLog.info("Decoder name: <unavailable>");
        }


        configuredFormat = format;

        // After reconfiguration, we must resubmit CSD buffers
        submittedCsd = false;
        vpsBuffers.clear();
        spsBuffers.clear();
        ppsBuffers.clear();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // This will contain the actual accepted input format attributes
            inputFormat = videoDecoder.getInputFormat();
            LimeLog.info("Input format: "+inputFormat);
        }

        videoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        // Start the decoder
        videoDecoder.start();

        // Recovery can recreate the native codec instance. Re-register this listener after every
        // configure/start so the first-frame gate remains valid.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoDecoder.setOnFrameRenderedListener(new MediaCodec.OnFrameRenderedListener() {
                @Override
                public void onFrameRendered(MediaCodec mediaCodec, long presentationTimeUs,
                                            long renderTimeNanos) {
                    if (mediaCodec != videoDecoder) {
                        return;
                    }
                    notifyFirstFrameRendered();
                    recordFramePresented();
                    long delta = (renderTimeNanos / 1000000L) - (presentationTimeUs / 1000);
                    if (delta >= 0 && delta < 1000 && USE_FRAME_RENDER_TIME) {
                        synchronized (videoStatsLock) {
                            activeWindowVideoStats.totalTimeMs += delta;
                        }
                    }
                }
            }, null);
        }

// Diagnostics: dump negotiated input/output formats and check vendor keys acceptance
        try {
            MediaFormat __inF = videoDecoder.getInputFormat();
            MediaFormat __outF = videoDecoder.getOutputFormat();
            LimeLog.info("Decoder input format: " + (__inF != null ? __inF.toString() : "<null>"));
            LimeLog.info("Decoder output format: " + (__outF != null ? __outF.toString() : "<null>"));
        } catch (Throwable t) {
            LimeLog.info("Decoder formats unavailable after start");
        }


        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            legacyInputBuffers = videoDecoder.getInputBuffers();
        }
    }

    private boolean tryConfigureDecoder(MediaCodecInfo selectedDecoderInfo, MediaFormat format, boolean throwOnCodecError) {
        boolean configured = false;
        try {
            videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo.getName());
            configureAndStartDecoder(format);
            LimeLog.info("Using codec " + selectedDecoderInfo.getName() + " for hardware decoding " + format.getString(MediaFormat.KEY_MIME));
            configured = true;
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw e;
            }
        } catch (IllegalStateException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw e;
            }
        } catch (IOException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw new RuntimeException(e);
            }
        } finally {
            if (!configured && videoDecoder != null) {
                videoDecoder.release();
                videoDecoder = null;
            }
        }
        return configured;
    }

    public int initializeDecoder(boolean throwOnCodecError) {
        String mimeType;
        MediaCodecInfo selectedDecoderInfo;

        if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
            mimeType = "video/avc";
            selectedDecoderInfo = avcDecoder;

            if (avcDecoder == null) {
                LimeLog.severe("No available AVC decoder!");
                return -1;
            }

            if (initialWidth > 4096 || initialHeight > 4096) {
                LimeLog.severe("> 4K streaming only supported on HEVC");
                return -1;
            }

            // These fixups only apply to H264 decoders
            needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo.getName());
            needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.getName());
            constrainedHighProfile = MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.getName());
            isExynos4 = MediaCodecHelper.isExynos4Device();
            if (needsSpsBitstreamFixup) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs SPS bitstream restrictions fixup");
            }
            if (needsBaselineSpsHack) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs baseline SPS hack");
            }
            if (constrainedHighProfile) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs constrained high profile");
            }
            if (isExynos4) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" is on Exynos 4");
            }

            refFrameInvalidationActive = refFrameInvalidationAvc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            mimeType = "video/hevc";
            selectedDecoderInfo = hevcDecoder;

            if (hevcDecoder == null) {
                LimeLog.severe("No available HEVC decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationHevc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            mimeType = "video/av01";
            selectedDecoderInfo = av1Decoder;

            if (av1Decoder == null) {
                LimeLog.severe("No available AV1 decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationAv1;
        }
        else {
            // Unknown format
            LimeLog.severe("Unknown format");
            return -3;
        }
        adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo, mimeType);
        fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType);

        for (int tryNumber = 0;; tryNumber++) {
            LimeLog.info("Decoder configuration try: "+tryNumber);

            MediaFormat mediaFormat = createBaseMediaFormat(mimeType);
            // This will try low latency options until we find one that works (or we give up).
            boolean newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(
                    mediaFormat, selectedDecoderInfo, prefs.enableUltraLowLatency, tryNumber);
            //todo 色彩格式
//            MediaCodecInfo.CodecCapabilities codecCapabilities = selectedDecoderInfo.getCapabilitiesForType(mimeType);
//            int[] colorFormats=codecCapabilities.colorFormats;
//            for (int colorFormat : colorFormats) {
//                LimeLog.info("Decoder configuration colorFormats: "+colorFormat);
//            }
            // Throw the underlying codec exception on the last attempt if the caller requested it
            if (tryConfigureDecoder(selectedDecoderInfo, mediaFormat, !newFormat && throwOnCodecError)) {
                // Success!
                break;
            }

            if (!newFormat) {
                // We couldn't even configure a decoder without any low latency options
                return -5;
            }
        }

        return 0;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        this.targetFps = (redrawRate > 0 ? redrawRate : 60);
        this.initialWidth = invertResolution ? height : width;
        this.initialHeight = invertResolution ? width : height;
        currentOutputDimensions.set(
                new DecodedVideoDimensions(this.initialWidth, this.initialHeight));
        this.videoFormat = format;
        IntConsumer formatListener = activeVideoFormatListener;
        if (formatListener != null) {
            formatListener.accept(format);
        }
        if (renderTarget == null || !renderTarget.isValid()) {
            LimeLog.severe("Decoder setup aborted because its prepared output surface is invalid");
            return -4;
        }
        this.refreshRate = redrawRate;

        return initializeDecoder(false);
    }

    // Swap the decoder's output Surface live (used by the XR client-SBS path to move the decoder
    // between the XR compositor surface and the on-device renderer's surface).
    @TargetApi(Build.VERSION_CODES.M)
    public boolean setOutputSurface(Surface surface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || surface == null || !surface.isValid()) {
            LimeLog.warning("Refusing invalid decoder output surface");
            return false;
        }

        // Codec recovery stops/resets the same MediaCodec instance. Serialize a live surface swap
        // with that recovery transaction so setOutputSurface() cannot race reset()/release().
        synchronized (codecRecoveryMonitor) {
            // A genuine codec-error recovery can overlap a UI mode request. Wait for that recovery
            // rather than racing setOutputSurface() against reset/release.
            long recoveryDeadlineMs = SystemClock.uptimeMillis() + 2000;
            while (!stopping && codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                long remainingMs = recoveryDeadlineMs - SystemClock.uptimeMillis();
                if (remainingMs <= 0) {
                    LimeLog.warning("Timed out waiting to switch decoder output surface during recovery");
                    return false;
                }

                try {
                    codecRecoveryMonitor.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LimeLog.warning("Interrupted while waiting to switch decoder output surface");
                    return false;
                }
            }

            if (videoDecoder == null || stopping
                    || codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                LimeLog.warning("Cannot switch decoder output surface while decoder is unavailable");
                return false;
            }
            try {
                videoDecoder.setOutputSurface(surface);
                renderTarget = surface;
                // SceneCore may return a replacement Surface after a resize or an EGL handoff.
                // Frame-rate metadata belongs to the Surface, so restore it after every live swap.
                applySurfaceFrameRate(surface, targetFps);
                return true;
            } catch (Exception e) {
                LimeLog.warning("Decoder output-surface switch failed: " + e);
                return false;
            }
        }
    }

    static boolean shouldRecoverCodecForPresentationModeTransition(int videoFormat) {
        return (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) == 0;
    }

    static boolean hdrMetadataUsesInPlaceReset(int videoFormat) {
        return (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0;
    }

    static boolean shouldRecreateAv1Decoder(int videoFormat, boolean applyingHdrMetadata) {
        return (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0
                && !applyingHdrMetadata;
    }

    static boolean shouldDispatchPerformanceSnapshot(boolean overlayEnabled,
                                                     boolean loggingEnabled) {
        return overlayEnabled || loggingEnabled;
    }

    static boolean shouldUseDirectSubmit(boolean decoderSupportsDirectSubmit) {
        return ENABLE_DIRECT_SUBMIT && decoderSupportsDirectSubmit;
    }

    static int countMissingFrames(int previousFrameNumber, int frameNumber,
                                  boolean intentionalDiscontinuity) {
        if (intentionalDiscontinuity || previousFrameNumber == 0) {
            return 0;
        }
        int serialDelta = frameNumber - previousFrameNumber;
        return serialDelta > 1 ? serialDelta - 1 : 0;
    }

    static boolean consumesIntentionalDiscontinuity(int previousFrameNumber,
                                                     int frameNumber) {
        return previousFrameNumber == 0 || frameNumber != previousFrameNumber;
    }

    /**
     * Begin an XR presentation-mode transaction. Compressed frames are gated immediately and the
     * output is PTS-gated until the transition IDR emerges. AVC/HEVC also use the existing
     * all-thread flush barrier. Qualcomm AV1 must not be flushed, restarted, reset, or recreated:
     * live lifecycle changes can leave its Codec2 buffer channel discarding every completed work.
     */
    public int beginPresentationModeTransition() {
        synchronized (codecRecoveryMonitor) {
            if (videoDecoder == null || stopping) {
                LimeLog.warning("Cannot prepare decoder for presentation-mode transition");
                return 0;
            }

            final int transitionGeneration;
            synchronized (modeTransitionStateLock) {
                modeTransitionWatchdogGeneration++;
                modeTransitionWatchdogActive = false;
                modeTransitionFrameGate.begin(latestInputFrameNumber);
                int nextGeneration = ++nextPresentationTransitionGeneration;
                if (nextGeneration <= 0) {
                    nextPresentationTransitionGeneration = nextGeneration = 1;
                }
                activePresentationTransitionGeneration = nextGeneration;
                transitionGeneration = nextGeneration;
            }
            if (!shouldRecoverCodecForPresentationModeTransition(videoFormat)) {
                LimeLog.info("XR mode transition: gating AV1 input/output without codec recovery");
            }
            else if (codecRecoveryType.compareAndSet(
                    CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_FLUSH)) {
                presentationModeRecoveryPending = true;
                LimeLog.info("XR mode transition: gating compressed frames and flushing decoder");
            }
            else {
                // A stronger HDR/error recovery already invalidates every queued codec buffer.
                LimeLog.info("XR mode transition: gating compressed frames; decoder recovery "
                        + codecRecoveryType.get() + " already pending");
            }
            return transitionGeneration;
        }
    }

    /**
     * Open the transition gate far enough to accept a new IDR, after the target Surface is bound.
     * P-frames and any IDR that was already in flight when the transition began remain discarded.
     */
    public void completePresentationModeTransition() {
        int watchdogGeneration = 0;
        synchronized (modeTransitionStateLock) {
            if (modeTransitionFrameGate.markTargetSurfaceReady()) {
                watchdogGeneration = ++modeTransitionWatchdogGeneration;
                modeTransitionWatchdogActive = true;
                modeTransitionWatchdogDeadlineMs = SystemClock.uptimeMillis()
                        + MODE_TRANSITION_TIMEOUT_MS;
            }
        }
        if (watchdogGeneration != 0) {
            LimeLog.info("XR mode transition: target surface ready; requesting fresh IDR");
            MoonBridge.requestIdrFrame();
            final int scheduledGeneration = watchdogGeneration;
            modeTransitionHandler.postDelayed(
                    () -> runPresentationModeTransitionWatchdog(scheduledGeneration),
                    MODE_TRANSITION_IDR_RETRY_MS);
        }
    }

    /** Release the compressed-frame gate when the enclosing mode transaction is being aborted. */
    public void cancelPresentationModeTransition() {
        if (cancelPresentationModeTransitionInternal()) {
            LimeLog.warning("XR mode transition aborted; compressed-frame gate released");
        }
    }

    private boolean cancelPresentationModeTransitionInternal() {
        synchronized (modeTransitionStateLock) {
            modeTransitionWatchdogGeneration++;
            modeTransitionWatchdogActive = false;
            activePresentationTransitionGeneration = 0;
            return modeTransitionFrameGate.cancel();
        }
    }

    private void runPresentationModeTransitionWatchdog(int generation) {
        IntConsumer timedOut = null;
        int timedOutTransitionGeneration = 0;
        boolean timeoutTriggered = false;
        boolean retry = false;
        synchronized (modeTransitionStateLock) {
            if (!modeTransitionWatchdogActive
                    || generation != modeTransitionWatchdogGeneration) {
                return;
            }
            if (SystemClock.uptimeMillis() >= modeTransitionWatchdogDeadlineMs) {
                modeTransitionWatchdogActive = false;
                modeTransitionWatchdogGeneration++;
                if (modeTransitionFrameGate.cancel()) {
                    timeoutTriggered = true;
                    timedOut = modeTransitionTimedOutListener;
                    timedOutTransitionGeneration = activePresentationTransitionGeneration;
                    activePresentationTransitionGeneration = 0;
                }
            } else {
                retry = true;
            }
        }

        if (timeoutTriggered) {
            LimeLog.severe("XR mode transition timed out waiting for the fresh IDR output");
            if (timedOut != null && timedOutTransitionGeneration > 0) {
                timedOut.accept(timedOutTransitionGeneration);
            }
            return;
        }
        if (retry) {
            MoonBridge.requestIdrFrame();
            modeTransitionHandler.postDelayed(
                    () -> runPresentationModeTransitionWatchdog(generation),
                    MODE_TRANSITION_IDR_RETRY_MS);
        }
    }

    private DecoderModeTransitionGate.OutputDecision evaluatePresentationTransitionOutput(
            long presentationTimeUs) {
        synchronized (modeTransitionStateLock) {
            return modeTransitionFrameGate.evaluateOutput(presentationTimeUs);
        }
    }

    /** Publish transition completion only after MediaCodec accepts a render release. */
    private void acknowledgePresentationTransitionRendered() {
        IntConsumer opened = null;
        int openedTransitionGeneration = 0;
        synchronized (modeTransitionStateLock) {
            modeTransitionFrameGate.acknowledgeRenderedOutput();
            if (modeTransitionFrameGate.consumeCompletedTransition()) {
                modeTransitionWatchdogActive = false;
                modeTransitionWatchdogGeneration++;
                opened = modeTransitionOpenedListener;
                openedTransitionGeneration = activePresentationTransitionGeneration;
                activePresentationTransitionGeneration = 0;
            }
        }
        if (opened != null && openedTransitionGeneration > 0) {
            opened.accept(openedTransitionGeneration);
        }
    }

    // All threads that interact with the MediaCodec instance must call this function regularly!
    private boolean doCodecRecoveryIfRequired(int quiescenceFlag) {
        // NB: We cannot check 'stopping' here because we could end up bailing in a partially
        // quiesced state that will cause the quiesced threads to never wake up.
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            // Common case
            return false;
        }

        // We need some sort of recovery, so quiesce all threads before starting that
        synchronized (codecRecoveryMonitor) {
            if (choreographerHandlerThread == null) {
                // If we have no choreographer thread, we can just mark that as quiesced right now.
                codecRecoveryThreadQuiescedFlags |= CR_FLAG_CHOREOGRAPHER;
            }

            codecRecoveryThreadQuiescedFlags |= quiescenceFlag;

            // This is the final thread to quiesce, so let's perform the codec recovery now.
            if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL) {
                // Input and output buffers are invalidated by stop() and reset().
                nextInputBuffer = null;
                nextInputBufferIndex = -1;
                outputBufferQueue.clear();
                synchronized (videoStatsLock) {
                    // These outputs were invalidated by the recovery transaction and can never
                    // produce a matching dequeue callback.
                    enqueueNsByPtsUs.clear();
                }

                // Snapshot expected recovery reasons before any operation changes the token.
                boolean applyingHdrMetadata = hdrMetadataRecoveryPending;
                boolean applyingPresentationModeTransition = presentationModeRecoveryPending;

                // If we just need a flush, do so now with all threads quiesced.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
                    LimeLog.warning("Flushing decoder");
                    try {
                        videoDecoder.flush();
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                        presentationModeRecoveryPending = false;
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART);
                    }
                }

                // Flushes, HDR setup, and requested presentation transitions are not codec errors.
                if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE
                        && !applyingHdrMetadata
                        && !applyingPresentationModeTransition) {
                    codecRecoveryAttempts++;
                    LimeLog.info("Codec recovery attempt: "+codecRecoveryAttempts);
                }

                // Keep the committed late-HDR path on an in-place reset. Full AV1 recreation is
                // reserved for an actual codec failure; it must not turn an expected metadata
                // update into a heavier lifecycle transaction.
                boolean recreateAv1Decoder = shouldRecreateAv1Decoder(
                        videoFormat, applyingHdrMetadata)
                        && (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART
                        || codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET);

                // For recoverable H.264/HEVC exceptions, stop, reconfigure, and restart.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART && !recreateAv1Decoder) {
                    if (applyingHdrMetadata) {
                        LimeLog.info("Restarting decoder to apply HDR metadata");
                    }
                    else if (applyingPresentationModeTransition) {
                        LimeLog.info("Restarting decoder for presentation-mode transition");
                    }
                    else {
                        LimeLog.warning("Trying to restart decoder after codec failure");
                    }
                    try {
                        videoDecoder.stop();
                        configureAndStartDecoder(configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                        hdrMetadataRecoveryPending = false;
                        presentationModeRecoveryPending = false;
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                        presentationModeRecoveryPending = false;
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESET);
                    }
                }

                // For other non-recoverable exceptions on L+, call reset() before escalating to
                // recreation. This retains the established, less expensive recovery path.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                        && !recreateAv1Decoder) {
                    if (applyingHdrMetadata) {
                        LimeLog.info("Resetting decoder to apply HDR metadata");
                    }
                    else if (applyingPresentationModeTransition) {
                        LimeLog.info("Resetting decoder for presentation-mode transition");
                    }
                    else {
                        LimeLog.warning("Trying to reset decoder after codec failure");
                    }
                    try {
                        videoDecoder.reset();
                        configureAndStartDecoder(configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                        hdrMetadataRecoveryPending = false;
                        presentationModeRecoveryPending = false;
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                        presentationModeRecoveryPending = false;
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the reset, we'll have to resort to
                        // releasing and recreating the decoder now.
                    }
                }

                // If we _still_ haven't managed to recover, go for the nuclear option and just
                // throw away the old decoder and reinitialize a new one from scratch.
                if (recreateAv1Decoder || codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    if (recreateAv1Decoder && applyingHdrMetadata) {
                        LimeLog.info("Recreating AV1 decoder to apply HDR metadata");
                    }
                    else if (recreateAv1Decoder && applyingPresentationModeTransition) {
                        LimeLog.info("Recreating AV1 decoder for presentation-mode transition");
                    }
                    else if (recreateAv1Decoder) {
                        LimeLog.warning("Recreating AV1 decoder after codec failure");
                    }
                    else {
                        LimeLog.warning("Trying to recreate decoder after failed reset");
                    }

                    try {
                        // Detach the old codec before release. If creation of the replacement fails,
                        // tryConfigureDecoder() must never release this stale object a second time.
                        MediaCodec oldDecoder = videoDecoder;
                        videoDecoder = null;
                        if (oldDecoder != null) {
                            try {
                                oldDecoder.release();
                            } catch (RuntimeException e) {
                                LimeLog.warning("Failed to release old decoder during recreation: " + e);
                            }
                        }

                        int err = initializeDecoder(true);
                        if (err != 0) {
                            throw new IllegalStateException("Decoder recreation failed: " + err);
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                        hdrMetadataRecoveryPending = false;
                        presentationModeRecoveryPending = false;
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                        presentationModeRecoveryPending = false;
                        hdrMetadataRecoveryPending = false;
                    } catch (RuntimeException e) {
                        // If we failed to recover after all of these attempts, just crash
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                        hdrMetadataRecoveryPending = false;
                        presentationModeRecoveryPending = false;
                        codecRecoveryThreadQuiescedFlags = 0;
                        codecRecoveryMonitor.notifyAll();
                        if (!reportedCrash) {
                            reportedCrash = true;
                            crashListener.notifyCrash(e);
                        }
                        throw new RendererException(this, e);
                    }
                }

                // Wake all quiesced threads and allow them to begin work again
                codecRecoveryThreadQuiescedFlags = 0;
                codecRecoveryMonitor.notifyAll();
            }
            else {
                // If we haven't quiesced all threads yet, wait to be signalled after recovery.
                // The final thread to be quiesced will handle the codec recovery.
                while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    try {
                        LimeLog.info("Waiting to quiesce decoder threads: "+codecRecoveryThreadQuiescedFlags);
                        codecRecoveryMonitor.wait(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();

                        // InterruptedException clears the thread's interrupt status. Since we can't
                        // handle that here, we will re-interrupt the thread to set the interrupt
                        // status back to true.
                        Thread.currentThread().interrupt();

                        break;
                    }
                }
            }
        }

        return true;
    }

    // Returns true if the exception is transient
    private boolean handleDecoderException(IllegalStateException e) {
        // Eat decoder exceptions if we're in the process of stopping
        if (stopping) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && e instanceof CodecException) {
            CodecException codecExc = (CodecException) e;

            if (codecExc.isTransient()) {
                // We'll let transient exceptions go
                LimeLog.warning(codecExc.getDiagnosticInfo());
                return true;
            }

            LimeLog.severe(codecExc.getDiagnosticInfo());
            hdrMetadataRecoveryPending = false;

            // We can attempt a recovery or reset at this stage to try to start decoding again
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                // If the exception is non-recoverable or we already require a reset, perform a reset.
                // If we have no prior unrecoverable failure, we will try a restart instead.
                if (codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder requires restart for recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder flush promoted to restart for recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET && codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                }
                else if (!codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder requires reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder flush promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder restart promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                }

                // The recovery will take place when all threads reach doCodecRecoveryIfRequired().
                return false;
            }
        }
        else {
            hdrMetadataRecoveryPending = false;
            // IllegalStateException was primarily used prior to the introduction of CodecException.
            // Recovery from this requires a full decoder reset.
            //
            // NB: CodecException is an IllegalStateException, so we must check for it first.
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder requires reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder flush promoted to reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder restart promoted to reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                    throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                }

                return false;
            }
        }

        // Only throw if we're not in the middle of codec recovery
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            //
            // There seems to be a race condition with decoder/surface teardown causing some
            // decoders to to throw IllegalStateExceptions even before 'stopping' is set.
            // To workaround this while allowing real exceptions to propagate, we will eat the
            // first exception. If we are still receiving exceptions 3 seconds later, we will
            // throw the original exception again.
            //
            if (initialException != null) {
                // This isn't the first time we've had an exception processing video
                if (SystemClock.uptimeMillis() - initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    // It's been over 3 seconds and we're still getting exceptions. Throw the original now.
                    if (!reportedCrash) {
                        reportedCrash = true;
                        crashListener.notifyCrash(initialException);
                    }
                    throw initialException;
                }
            }
            else {
                // This is the first exception we've hit
                initialException = new RendererException(this, e);
                initialExceptionTimestamp = SystemClock.uptimeMillis();
            }
        }

        // Not transient
        return false;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        // Do nothing if we're stopping
        if (stopping) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            frameTimeNanos -= activity.getWindowManager().getDefaultDisplay().getAppVsyncOffsetNanos();
        }

        // Don't render unless a new frame is due. This prevents microstutter when streaming
        // at a frame rate that doesn't match the display (such as 60 FPS on 120 Hz).
        long actualFrameTimeDeltaNs = frameTimeNanos - lastRenderedFrameTimeNanos;
        long expectedFrameTimeDeltaNs = 800000000 / refreshRate; // within 80% of the next frame
        if (actualFrameTimeDeltaNs >= expectedFrameTimeDeltaNs) {
            // Render up to one frame when in frame pacing mode.
            //
            // NB: Since the queue limit is 2, we won't starve the decoder of output buffers
            // by holding onto them for too long. This also ensures we will have that 1 extra
            // frame of buffer to smooth over network/rendering jitter.
            DecodedOutputBuffer nextOutputBuffer = outputBufferQueue.poll();
            if (nextOutputBuffer != null) {
                try {
                    DecoderModeTransitionGate.OutputDecision outputDecision =
                            evaluatePresentationTransitionOutput(
                                    nextOutputBuffer.presentationTimeUs);
                    if (outputDecision == DecoderModeTransitionGate.OutputDecision.DROP) {
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer.index, false);
                        nextOutputBuffer = null;
                    }
                    else if (outputDecision
                            == DecoderModeTransitionGate.OutputDecision.ACCEPT_AND_OPEN) {
                        LimeLog.info("XR mode transition: fresh IDR output reached render gate");
                    }

                    if (nextOutputBuffer != null) {
                        boolean releasedForRender;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            releaseOutputBufferForRender(nextOutputBuffer.index, frameTimeNanos);
                            releasedForRender = true;
                        } else {
                            releaseOutputBufferForRender(nextOutputBuffer.index);
                            releasedForRender = true;
                        }

                        if (releasedForRender) {
                            lastRenderedFrameTimeNanos = frameTimeNanos;
                        }
                    }
                } catch (IllegalStateException ignored) {
                    try {
                        // Try to avoid leaking the output buffer by releasing it without rendering
                        if (nextOutputBuffer != null) {
                            videoDecoder.releaseOutputBuffer(nextOutputBuffer.index, false);
                        }
                    } catch (IllegalStateException e) {
                        // This will leak nextOutputBuffer, but there's really nothing else we can do
                        e.printStackTrace();
                        handleDecoderException(e);
                    }
                }
            }
        }

        // Attempt codec recovery even if we have nothing to render right now. Recovery can still
        // be required even if the codec died before giving any output.
        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);

        // Request another callback for next frame
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void startChoreographerThread() {
        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
            // Not using Choreographer in this pacing mode
            return;
        }

        // We use a separate thread to avoid any main thread delays from delaying rendering
        choreographerHandlerThread = new HandlerThread("Video - Choreographer", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        choreographerHandlerThread.start();

        // Start the frame callbacks
        choreographerHandler = new Handler(choreographerHandlerThread.getLooper());
        choreographerHandler.post(new Runnable() {
            @Override
            public void run() {
                Choreographer.getInstance().postFrameCallback(MediaCodecDecoderRenderer.this);
            }
        });
    }

    private void startRendererThread() {
        rendererThread = new Thread() {
            @Override
            public void run() {
                // Boost thread priority to reduce decoding latency
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);

                BufferInfo info = new BufferInfo();
                while (!stopping) {
                    try {
                        // MediaCodec wakes this call as soon as output is available. A small blocking
                        // timeout therefore preserves minimum latency without polling the codec on an
                        // urgent-display thread thousands of times per second between frames.
                        int outIndex = videoDecoder.dequeueOutputBuffer(
                                info, OUTPUT_DEQUEUE_TIMEOUT_US);

                        if (outIndex >= 0) {
                            long presentationTimeUs = info.presentationTimeUs;
                            int lastIndex = outIndex;
                            boolean releasedForRender = false;

                            // This is the precise end of the MediaCodec enqueue-to-output-dequeue
                            // interval. Presentation policy below must not affect decode latency.
                            updateDecodeLatencyStats(presentationTimeUs);

                            numFramesOut++;

                            DecoderModeTransitionGate.OutputDecision outputDecision =
                                    evaluatePresentationTransitionOutput(presentationTimeUs);
                            if (outputDecision == DecoderModeTransitionGate.OutputDecision.DROP) {
                                videoDecoder.releaseOutputBuffer(lastIndex, false);
                                continue;
                            }
                            if (outputDecision
                                    == DecoderModeTransitionGate.OutputDecision.ACCEPT_AND_OPEN) {
                                LimeLog.info("XR mode transition: fresh IDR output reached render gate");
                            }

                            // Direct pacing modes keep only the newest output already waiting.
                            if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                                while ((outIndex = videoDecoder.dequeueOutputBuffer(info, 0)) >= 0) {
                                    videoDecoder.releaseOutputBuffer(lastIndex, false);

                                    numFramesOut++;
                                    lastIndex = outIndex;
                                    presentationTimeUs = info.presentationTimeUs;
                                    updateDecodeLatencyStats(presentationTimeUs);
                                }

                                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    handleOutputFormatChanged();
                                }

                                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
                                    // A timestamp of zero asks SurfaceFlinger not to discard this frame.
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        releaseOutputBufferForRender(lastIndex, 0);
                                        releasedForRender = true;
                                    } else {
                                        releaseOutputBufferForRender(lastIndex);
                                        releasedForRender = true;
                                    }
                                } else {
                                    // Minimum latency renders immediately. Timestamp scheduling is
                                    // reserved for the pacing modes above and Choreographer path.
                                    releaseOutputBufferForRender(lastIndex);
                                    releasedForRender = true;
                                }
                            } else {
                                // For balanced frame pacing case, the Choreographer callback will handle rendering.
                                // We just put all frames into the output buffer queue and let it handle things.

                                // Discard the oldest buffer if we've exceeded our limit.
                                //
                                // NB: We have to do this on the producer side because the consumer may not
                                // run for a while (if there is a huge mismatch between stream FPS and display
                                // refresh rate).
                                if (outputBufferQueue.size() == OUTPUT_BUFFER_QUEUE_LIMIT) {
                                    try {
                                        videoDecoder.releaseOutputBuffer(
                                                outputBufferQueue.take().index, false);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                }

                                // Add this buffer
                                outputBufferQueue.add(new DecodedOutputBuffer(
                                        lastIndex, presentationTimeUs));
                            }

                        } else {
                            switch (outIndex) {
                                case MediaCodec.INFO_TRY_AGAIN_LATER:
                                    break;
                                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                                    handleOutputFormatChanged();
                                    break;
                                default:
                                    break;
                            }
                        }
                    } catch (IllegalStateException e) {
                        handleDecoderException(e);
                    } finally {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD);
                    }
                }
            }
        };
        rendererThread.setName("Video - Renderer (MediaCodec)");
        rendererThread.setPriority(Thread.NORM_PRIORITY + 2);
        rendererThread.start();
    }

    private boolean fetchNextInputBuffer() {
        long startTime;
        boolean codecRecovered;

        if (nextInputBuffer != null) {
            // We already have an input buffer
            return true;
        }

        startTime = SystemClock.uptimeMillis();

        try {
            // If we don't have an input buffer index yet, fetch one now
            while (nextInputBufferIndex < 0 && !stopping
                    && !inputDequeueHangExpired(startTime, SystemClock.uptimeMillis())) {
                nextInputBufferIndex = videoDecoder.dequeueInputBuffer(10000);
            }
            if (nextInputBufferIndex < 0 && !stopping
                    && inputDequeueHangExpired(startTime, SystemClock.uptimeMillis())) {
                handleDecoderException(new DecoderHungException(
                        (int) (SystemClock.uptimeMillis() - startTime)));
            }

            // Get the backing ByteBuffer for the input buffer index
            if (nextInputBufferIndex >= 0) {
                // Using the new getInputBuffer() API on Lollipop allows
                // the framework to do some performance optimizations for us
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    nextInputBuffer = videoDecoder.getInputBuffer(nextInputBufferIndex);
                    if (nextInputBuffer == null) {
                        // According to the Android docs, getInputBuffer() can return null "if the
                        // index is not a dequeued input buffer". I don't think this ever should
                        // happen but if it does, let's try to get a new input buffer next time.
                        nextInputBufferIndex = -1;
                    }
                }
                else {
                    nextInputBuffer = legacyInputBuffers[nextInputBufferIndex];

                    // Clear old input data pre-Lollipop
                    nextInputBuffer.clear();
                }
            }
        } catch (IllegalStateException e) {
            handleDecoderException(e);
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return false;
        }

        int deltaMs = (int)(SystemClock.uptimeMillis() - startTime);

        if (deltaMs >= 20) {
            LimeLog.warning("Dequeue input buffer ran long: " + deltaMs + " ms");
        }

        if (nextInputBuffer == null) {
            return false;
        }

        if (!inputBufferCapacityLogged) {
            LimeLog.info("Decoder input buffer capacity: " + nextInputBuffer.capacity() + " bytes");
            inputBufferCapacityLogged = true;
        }

        return true;
    }

    static boolean inputDequeueHangExpired(long startTimeMs, long nowMs) {
        return nowMs - startTimeMs >= INPUT_DEQUEUE_HANG_TIMEOUT_MS;
    }

    @Override
    public void start() {
        startRendererThread();
        startChoreographerThread();
    }

    // !!! May be called even if setup()/start() fails !!!
    public void prepareForStop() {
        // surfaceDestroyed() asks the decoder to stop immediately, then moonlight-common invokes
        // stop() as part of native teardown. Only the first caller may post the Choreographer quit
        // callback; posting it again after the looper exits produces a dead-Handler exception.
        if (!stopPrepared.compareAndSet(false, true)) {
            return;
        }

        // Let the decoding code know to ignore codec exceptions now
        stopping = true;
        cancelPresentationModeTransitionInternal();

        // Halt the rendering thread
        if (rendererThread != null) {
            rendererThread.interrupt();
        }

        // Stop any active codec recovery operations
        synchronized (codecRecoveryMonitor) {
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
            codecRecoveryMonitor.notifyAll();
        }

        // Post a quit message to the Choreographer looper (if we have one)
        Handler handler = choreographerHandler;
        HandlerThread handlerThread = choreographerHandlerThread;
        if (handler != null && handlerThread != null && handlerThread.isAlive()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    // Don't allow any further messages to be queued
                    handlerThread.quit();

                    // Deregister the frame callback (if registered)
                    Choreographer.getInstance().removeFrameCallback(MediaCodecDecoderRenderer.this);
                }
            });
        }
    }

    @Override
    public void stop() {
        // May be called already, but we'll call it now to be safe
        prepareForStop();

        // Wait for the Choreographer looper to shut down (if we have one)
        HandlerThread handlerThread = choreographerHandlerThread;
        if (handlerThread != null) {
            try {
                handlerThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }
        }

        // Wait for the renderer thread to shut down
        Thread renderThread = rendererThread;
        if (renderThread != null) {
            try {
                renderThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void cleanup() {
        MediaCodec decoderToRelease;
        synchronized (codecRecoveryMonitor) {
            decoderToRelease = videoDecoder;
            videoDecoder = null;
        }
        if (decoderToRelease != null) {
            try {
                decoderToRelease.release();
            } catch (RuntimeException e) {
                LimeLog.warning("Failed to release decoder during cleanup: " + e);
            }
        }
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        // HDR metadata is only supported in Android 7.0 and later, so don't bother
        // restarting the codec on anything earlier than that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (currentHdrMetadata != null && (!enabled || hdrMetadata == null)) {
                currentHdrMetadata = null;
            }
            else if (enabled && hdrMetadata != null && !Arrays.equals(currentHdrMetadata, hdrMetadata)) {
                currentHdrMetadata = hdrMetadata;
            }
            else {
                // Nothing to do
                return;
            }

            // If we reach this point, we need to restart the MediaCodec instance to
            // pick up the HDR metadata change. This will happen on the next input
            // or output buffer.

            // Qualcomm's AV1 Codec2 implementation can leave its buffer channel in a flushed
            // state after stop()/configure()/start(), causing every subsequent work item to be
            // ignored. Use a full reset for AV1 while retaining the established restart path for
            // the other codecs.
            final int recoveryType = hdrMetadataUsesInPlaceReset(videoFormat) ?
                    CR_RECOVERY_TYPE_RESET : CR_RECOVERY_TYPE_RESTART;
            synchronized (codecRecoveryMonitor) {
                // A second metadata update can arrive before the already scheduled recovery.
                // Keep that recovery marked as expected; it will consume currentHdrMetadata.
                if (!hdrMetadataRecoveryPending) {
                    // Publish the reason before the recovery type. Recovery reads the reason only
                    // after taking this lock, while exception paths race safely through the CAS.
                    hdrMetadataRecoveryPending = true;
                    boolean scheduled = codecRecoveryType.compareAndSet(
                            CR_RECOVERY_TYPE_NONE, recoveryType) ||
                            codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, recoveryType);
                    if (!scheduled) {
                        // A concurrent real codec failure owns the reason and must still count.
                        hdrMetadataRecoveryPending = false;
                        if (recoveryType == CR_RECOVERY_TYPE_RESET) {
                            codecRecoveryType.compareAndSet(
                                    CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET);
                        }
                    }
                }
            }
        }
    }

    private InputQueueResult queueNextInputBufferForAdmission(
            long timestampUs, int codecFlags, long admissionGeneration,
            int frameNumber, boolean idrFrame, boolean prepareTransitionIdr) {
        boolean codecRecovered;
        boolean trackDecodeLatency = performanceTelemetryEnabled
                && (codecFlags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
        long enqueueNs = trackDecodeLatency ? System.nanoTime() : 0L;

        // Publish the timestamp before queueing. A very fast decoder can return the output on the
        // renderer thread before queueInputBuffer() returns to this thread.
        if (trackDecodeLatency) {
            synchronized (videoStatsLock) {
                // Recheck under the lock so disabling telemetry cannot race a stale insertion.
                if (performanceTelemetryEnabled) {
                    enqueueNsByPtsUs.put(timestampUs, enqueueNs);
                } else {
                    trackDecodeLatency = false;
                }
            }
        }

        try {
            pendingInputCommitTimestampUs = timestampUs;
            pendingInputCommitCodecFlags = codecFlags;
            DecoderModeTransitionGate.InputCommitDecision commitDecision =
                    modeTransitionFrameGate.commitInput(
                            admissionGeneration, frameNumber, idrFrame, timestampUs,
                            prepareTransitionIdr, inputCommitter);
            if (commitDecision
                    == DecoderModeTransitionGate.InputCommitDecision.STALE_ADMISSION) {
                if (trackDecodeLatency) {
                    synchronized (videoStatsLock) {
                        Long trackedEnqueueNs = enqueueNsByPtsUs.get(timestampUs);
                        if (trackedEnqueueNs != null && trackedEnqueueNs == enqueueNs) {
                            enqueueNsByPtsUs.delete(timestampUs);
                        }
                    }
                }
                nextInputBuffer.clear();
                return InputQueueResult.TRANSITION_DROPPED;
            }
            if (commitDecision
                    == DecoderModeTransitionGate.InputCommitDecision.COMMITTED_TRANSITION_IDR) {
                LimeLog.info("XR mode transition: fresh IDR " + frameNumber
                        + " accepted; compressed-frame gate opened");
            }
        } catch (IllegalStateException e) {
            if (trackDecodeLatency) {
                synchronized (videoStatsLock) {
                    Long trackedEnqueueNs = enqueueNsByPtsUs.get(timestampUs);
                    if (trackedEnqueueNs != null && trackedEnqueueNs == enqueueNs) {
                        enqueueNsByPtsUs.delete(timestampUs);
                    }
                }
            }
            if (handleDecoderException(e)) {
                // We encountered a transient error. In this case, just hold onto the buffer
                // (to avoid leaking it), clear it, and keep it for the next frame. We'll return
                // false to trigger an IDR frame to recover.
                nextInputBuffer.clear();
            }
            else {
                // We encountered a non-transient error. In this case, we will simply leak the
                // buffer because we cannot be sure we will ever succeed in queuing it.
                nextInputBufferIndex = -1;
                nextInputBuffer = null;
            }
            return InputQueueResult.FAILED;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return InputQueueResult.FAILED;
        }

        // Fetch a new input buffer now while we have some time between frames
        // to have it ready immediately when the next frame arrives.
        //
        // We must propagate the return value here in order to properly handle
        // codec recovery happening in fetchNextInputBuffer(). If we don't, we'll
        // never get an IDR frame to complete the recovery process.
        return fetchNextInputBuffer()
                ? InputQueueResult.QUEUED : InputQueueResult.FAILED;
    }

    /** Runs only under DecoderModeTransitionGate's admission monitor on the input thread. */
    private void commitPendingInputBuffer() {
        videoDecoder.queueInputBuffer(nextInputBufferIndex,
                0, nextInputBuffer.position(),
                pendingInputCommitTimestampUs, pendingInputCommitCodecFlags);
        nextInputBufferIndex = -1;
        nextInputBuffer = null;
    }

    private void doProfileSpecificSpsPatching(SeqParameterSet sps) {
        // Some devices benefit from setting constraint flags 4 & 5 to make this Constrained
        // High Profile which allows the decoder to assume there will be no B-frames and
        // reduce delay and buffering accordingly. Some devices (Marvell, Exynos 4) don't
        // like it so we only set them on devices that are confirmed to benefit from it.
        if (sps.profileIdc == 100 && constrainedHighProfile) {
            LimeLog.info("Setting constraint set flags for constrained high profile");
            sps.constraintSet4Flag = true;
            sps.constraintSet5Flag = true;
        }
        else {
            // Force the constraints unset otherwise (some may be set by default)
            sps.constraintSet4Flag = false;
            sps.constraintSet5Flag = false;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeMs, long enqueueTimeMs) {
        if (stopping) {
            // Don't bother if we're stopping
            return MoonBridge.DR_OK;
        }

        long decoderQueueTimeMs = -1;
        int pendingDecoderFrames = -1;
        if (performanceTelemetryEnabled) {
            if (!hasDecoderQueueSample || decoderQueueSampleFrameNumber != frameNumber) {
                long queueTimeMs = SystemClock.uptimeMillis() - enqueueTimeMs;
                decoderQueueSampleTimeMs = queueTimeMs >= 0 && queueTimeMs < 10_000
                        ? queueTimeMs : -1;
                decoderQueueSamplePendingFrames = MoonBridge.getPendingVideoFrames();
                decoderQueueSampleFrameNumber = frameNumber;
                hasDecoderQueueSample = true;
            }
            if (decodeUnitType == MoonBridge.BUFFER_TYPE_PICDATA) {
                decoderQueueTimeMs = decoderQueueSampleTimeMs;
                pendingDecoderFrames = decoderQueueSamplePendingFrames;
            }
        }

        // Publish the boundary before consulting the transition gate. If beginTransition() races
        // immediately after this write, it snapshots this frame and therefore cannot later admit
        // the same already-in-flight IDR as a fresh post-transition frame.
        latestInputFrameNumber = frameNumber;
        boolean idrFrame = frameType == MoonBridge.FRAME_TYPE_IDR;
        final long transitionInputAdmission;
        final DecoderModeTransitionGate.InputDecision transitionInputDecision;
        synchronized (modeTransitionStateLock) {
            transitionInputAdmission = modeTransitionFrameGate.getInputAdmissionGeneration();
            transitionInputDecision = modeTransitionFrameGate.evaluateInput(
                    frameNumber, idrFrame);
        }
        if (transitionInputDecision != DecoderModeTransitionGate.InputDecision.ACCEPT) {
            intentionalInputDiscontinuityPending = true;
            // Transition recovery still needs the native input/callback thread to enter the codec
            // recovery barrier. Do that while dropping the compressed frame so setOutputSurface()
            // cannot wait behind an idle input thread. DR_OK deliberately avoids an early IDR
            // storm; completePresentationModeTransition() requests one after the target is bound.
            doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
            return transitionInputDecision == DecoderModeTransitionGate.InputDecision.NEED_IDR
                    ? MoonBridge.DR_NEED_IDR : MoonBridge.DR_OK;
        }

        long statsNowMs = SystemClock.uptimeMillis();
        VideoStats completedWindowVideoStats = null;
        VideoStats lastTwo = null;
        boolean collectPerformance = performanceTelemetryEnabled;
        synchronized (videoStatsLock) {
            int missingFrames = countMissingFrames(lastFrameNumber, frameNumber,
                    intentionalInputDiscontinuityPending);
            if (lastFrameNumber == 0) {
                activeWindowVideoStats.measurementStartTimestamp = statsNowMs;
            } else if (missingFrames > 0) {
                // We can receive the same "frame" multiple times if it's an IDR frame.
                // In that case, each frame start NALU is submitted independently.
                activeWindowVideoStats.framesLost += missingFrames;
                activeWindowVideoStats.totalFrames += missingFrames;
                activeWindowVideoStats.frameLossEvents++;
            }
            if (consumesIntentionalDiscontinuity(lastFrameNumber, frameNumber)) {
                intentionalInputDiscontinuityPending = false;
            }

            if (statsNowMs >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
                completedWindowVideoStats = new VideoStats();
                completedWindowVideoStats.copy(activeWindowVideoStats);

                if (collectPerformance) {
                    lastTwo = new VideoStats();
                    lastTwo.add(lastWindowVideoStats);
                    lastTwo.add(completedWindowVideoStats);
                }

                globalVideoStats.add(completedWindowVideoStats);
                lastWindowVideoStats.copy(completedWindowVideoStats);
                activeWindowVideoStats.clear();
                activeWindowVideoStats.measurementStartTimestamp = statsNowMs;
                pruneStaleDecodeTimestampsLocked(System.nanoTime());
            }
        }

        // Reset CSD data for each IDR frame
        if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
            vpsBuffers.clear();
            spsBuffers.clear();
            ppsBuffers.clear();
        }

        lastFrameNumber = frameNumber;

        // Build the immutable completed-window snapshot after releasing the stats lock. Rendering
        // and decoding continue while the listener consumes it.
        if (completedWindowVideoStats != null) {
            if (collectPerformance) {
                float smoothedFps = lastTwo.getFps().totalFps;
                String decoder;

                if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                    decoder = avcDecoder.getName();
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                    decoder = hevcDecoder.getName();
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                    decoder = av1Decoder.getName();
                } else {
                    decoder = "(unknown)";
                }

                float decodeTimeMs = lastTwo.decoderLatencySamples > 0
                        ? (float) (lastTwo.decoderTimeNs / 1_000_000.0)
                        / lastTwo.decoderLatencySamples
                        : Float.NaN;
                long rttInfo = MoonBridge.getEstimatedRttInfo();
                int estimatedRttMs = rttInfo == -1L
                        ? StreamPerformanceSnapshot.INT_UNAVAILABLE
                        : (int) (rttInfo >>> 32);
                float bandwidthMbps = sampleAppNetworkThroughputMbps(statsNowMs);

                // The XR panel consumes the just-completed active window. The overlapping
                // two-window average above remains only for selecting a stable best-latency sample.
                long activeElapsedMs = Math.max(0L,
                        statsNowMs - completedWindowVideoStats.measurementStartTimestamp);
                float activeSeconds = activeElapsedMs > 0
                        ? activeElapsedMs / 1000.0f : Float.NaN;
                float activeStreamSequenceFps = Float.isFinite(activeSeconds)
                        ? completedWindowVideoStats.totalFrames / activeSeconds : Float.NaN;
                float activeReceivedFps = Float.isFinite(activeSeconds)
                        ? completedWindowVideoStats.totalFramesReceived / activeSeconds : Float.NaN;
                float activeDecoderOutputFps = Float.isFinite(activeSeconds)
                        ? completedWindowVideoStats.totalFramesDecoded / activeSeconds : Float.NaN;
                float activeDecoderReleaseFps = Float.isFinite(activeSeconds)
                        ? completedWindowVideoStats.totalFramesRendered / activeSeconds : Float.NaN;
                float activeDecoderPresentedFps = Float.isFinite(activeSeconds)
                        ? completedWindowVideoStats.totalFramesPresented / activeSeconds : Float.NaN;
                float activeDecodeAverageMs = completedWindowVideoStats.decoderLatencySamples > 0
                        ? (float) (completedWindowVideoStats.decoderTimeNs / 1_000_000.0)
                        / completedWindowVideoStats.decoderLatencySamples
                        : Float.NaN;
                float activeDecodeMaxMs = completedWindowVideoStats.decoderLatencySamples > 0
                        ? completedWindowVideoStats.maxDecoderTimeNs / 1_000_000.0f : Float.NaN;
                float activeDecoderQueueAverageMs =
                        completedWindowVideoStats.decoderQueueLatencySamples > 0
                                ? (float) completedWindowVideoStats.decoderQueueTimeMs
                                / completedWindowVideoStats.decoderQueueLatencySamples
                                : Float.NaN;
                float activeDecoderQueueMaxMs =
                        completedWindowVideoStats.decoderQueueLatencySamples > 0
                                ? completedWindowVideoStats.maxDecoderQueueTimeMs : Float.NaN;
                float activeNetworkLossPercent = completedWindowVideoStats.totalFrames > 0
                        ? (float) completedWindowVideoStats.framesLost
                        / completedWindowVideoStats.totalFrames * 100.0f
                        : Float.NaN;
                float hostProcessingAverageMs = Float.NaN;
                float hostProcessingMaxMs = Float.NaN;
                if (completedWindowVideoStats.framesWithHostProcessingLatency > 0) {
                    hostProcessingAverageMs = completedWindowVideoStats.totalHostProcessingLatency
                            / 10.0f / completedWindowVideoStats.framesWithHostProcessingLatency;
                    hostProcessingMaxMs = completedWindowVideoStats.maxHostProcessingLatency / 10.0f;
                }
                String actualVideoRange = describeActualColorRange();
                DecodedVideoDimensions outputDimensions = currentOutputDimensions.get();
                StreamPerformanceSnapshot performanceSnapshot = new StreamPerformanceSnapshot(
                        activeElapsedMs,
                        outputDimensions.width,
                        outputDimensions.height,
                        activeStreamSequenceFps,
                        activeReceivedFps,
                        activeDecoderOutputFps,
                        activeDecoderReleaseFps,
                        activeDecoderPresentedFps,
                        activeDecodeAverageMs,
                        activeDecodeMaxMs,
                        activeDecoderQueueAverageMs,
                        completedWindowVideoStats.getDecoderQueueP95Ms(),
                        activeDecoderQueueMaxMs,
                        completedWindowVideoStats.maxPendingDecoderFrames,
                        activeNetworkLossPercent,
                        bandwidthMbps,
                        estimatedRttMs,
                        hostProcessingAverageMs,
                        hostProcessingMaxMs,
                        describeVideoCodec(videoFormat),
                        decoder,
                        MediaCodecHelper.isDedicatedLowLatencyDecoderName(decoder),
                        requestsDecoderLowLatency(configuredFormat),
                        directSubmit,
                        describeOutputPacing(prefs.framePacing),
                        actualVideoRange);
                boolean targetFpsMatched = ((int) smoothedFps == (int) prefs.fps);
                boolean newBestDecodeSample = minDecodeTime > decodeTimeMs && targetFpsMatched;
                String fullLog = "";
                if (newBestDecodeSample) {
                    VideoStatsFps fps = lastTwo.getFps();
                    float legacyNetworkLossPercent = lastTwo.totalFrames > 0
                            ? (float) lastTwo.framesLost / lastTwo.totalFrames * 100.0f
                            : Float.NaN;
                    int estimatedRttVarianceMs = rttInfo == -1L
                            ? StreamPerformanceSnapshot.INT_UNAVAILABLE
                            : (int) rttInfo;
                    StringBuilder sb = new StringBuilder();
                if(prefs.enablePerfOverlayLite){
                    if (Float.isFinite(bandwidthMbps)) {
                        sb.append(context.getString(R.string.perf_overlay_lite_bandwidth))
                                .append(": ")
                                .append(String.format("%.2f Mbps\t ", bandwidthMbps));
                    }
//                    sb.append("分辨率：");
//                    sb.append(initialWidth + "x" + initialHeight);
                    sb.append(context.getString(R.string.perf_overlay_lite_network_decoding_delay) + ": ");
                    sb.append(context.getString(R.string.perf_overlay_lite_net, estimatedRttMs));
                    sb.append(" / ");
                    sb.append(context.getString(R.string.perf_overlay_lite_dectime,decodeTimeMs));
                    sb.append("\t");
                    sb.append(context.getString(R.string.perf_overlay_lite_packet_loss) + ": ");
                    sb.append(context.getString(R.string.perf_overlay_lite_netdrops,
                            legacyNetworkLossPercent));
                    sb.append("\t FPS：");
                    sb.append(context.getString(R.string.perf_overlay_lite_fps, fps.totalFps));
                    sb.append("\t Range: ");
                    sb.append(describeActualColorRange());
                }else{
                    sb.append(context.getString(R.string.perf_overlay_streamdetails,
                            initialWidth + "x" + initialHeight, fps.totalFps));
                    sb.append('\n');
                    sb.append(context.getString(
                            R.string.perf_overlay_video_range,
                            describeActualColorRange())).append('\n');
                    sb.append(context.getString(R.string.perf_overlay_decoder, decoder)).append('\n');
                    sb.append(context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)).append('\n');
                    sb.append(context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)).append('\n');
                    sb.append(context.getString(R.string.perf_overlay_netdrops,
                            legacyNetworkLossPercent)).append('\n');
                    if (Float.isFinite(bandwidthMbps)) {
                        sb.append(context.getString(R.string.perf_overlay_lite_bandwidth))
                                .append(": ")
                                .append(String.format("%.2f Mbps\n", bandwidthMbps));
                    }
                    if (estimatedRttMs != StreamPerformanceSnapshot.INT_UNAVAILABLE) {
                        sb.append(context.getString(R.string.perf_overlay_netlatency,
                                estimatedRttMs, estimatedRttVarianceMs)).append('\n');
                    }
                    if (lastTwo.framesWithHostProcessingLatency > 0) {
                        sb.append(context.getString(R.string.perf_overlay_hostprocessinglatency,
                                (float)lastTwo.minHostProcessingLatency / 10,
                                (float)lastTwo.maxHostProcessingLatency / 10,
                                (float)lastTwo.totalHostProcessingLatency / 10 / lastTwo.framesWithHostProcessingLatency)).append('\n');
                    }
                    sb.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs));
                }
                    fullLog = sb.toString();
                }
                if (performanceTelemetryEnabled) {
                    perfListener.onPerfUpdate(performanceSnapshot, "");
                }
                // Best latency is only met at requested highest fps, rest can be ignored
                if (newBestDecodeSample) {
                    minDecodeTime = decodeTimeMs;
                    minDecodeTimeFullLog = fullLog;
                }
            }
        }

        boolean csdSubmittedForThisFrame = false;

        // IDR frames require special handling for CSD buffer submission
        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            // H264 SPS
            if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS && (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                numSpsIn++;

                ByteBuffer spsBuf = ByteBuffer.wrap(decodeUnitData);
                int startSeqLen = decodeUnitData[2] == 0x01 ? 3 : 4;

                // Skip to the start of the NALU data
                spsBuf.position(startSeqLen + 1);

                // The H264Utils.readSPS function safely handles
                // Annex B NALUs (including NALUs with escape sequences)
                SeqParameterSet sps = H264Utils.readSPS(spsBuf);

                // Some decoders rely on H264 level to decide how many buffers are needed
                // Since we only need one frame buffered, we'll set the level as low as we can
                // for known resolution combinations. Reference frame invalidation may need
                // these, so leave them be for those decoders.
                if (!refFrameInvalidationActive) {
                    if (initialWidth <= 720 && initialHeight <= 480 && refreshRate <= 60) {
                        // Max 5 buffered frames at 720x480x60
                        LimeLog.info("Patching level_idc to 31");
                        sps.levelIdc = 31;
                    }
                    else if (initialWidth <= 1280 && initialHeight <= 720 && refreshRate <= 60) {
                        // Max 5 buffered frames at 1280x720x60
                        LimeLog.info("Patching level_idc to 32");
                        sps.levelIdc = 32;
                    }
                    else if (initialWidth <= 1920 && initialHeight <= 1080 && refreshRate <= 60) {
                        // Max 4 buffered frames at 1920x1080x64
                        LimeLog.info("Patching level_idc to 42");
                        sps.levelIdc = 42;
                    }
                    else {
                        // Leave the profile alone (currently 5.0)
                    }
                }

                // TI OMAP4 requires a reference frame count of 1 to decode successfully. Exynos 4
                // also requires this fixup.
                //
                // I'm doing this fixup for all devices because I haven't seen any devices that
                // this causes issues for. At worst, it seems to do nothing and at best it fixes
                // issues with video lag, hangs, and crashes.
                //
                // It does break reference frame invalidation, so we will not do that for decoders
                // where we've enabled reference frame invalidation.
                if (!refFrameInvalidationActive) {
                    LimeLog.info("Patching num_ref_frames in SPS");
                    sps.numRefFrames = 1;
                }

                // GFE 2.5.11 changed the SPS to add additional extensions. Some devices don't like these
                // so we remove them here on old devices unless these devices also support HEVC.
                // See getPreferredColorSpace() for further information.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                        sps.vuiParams != null &&
                        hevcDecoder == null &&
                        av1Decoder == null) {
                    sps.vuiParams.videoSignalTypePresentFlag = false;
                    sps.vuiParams.colourDescriptionPresentFlag = false;
                    sps.vuiParams.chromaLocInfoPresentFlag = false;
                }

                // Some older devices used to choke on a bitstream restrictions, so we won't provide them
                // unless explicitly whitelisted. For newer devices, leave the bitstream restrictions present.
                if (needsSpsBitstreamFixup || isExynos4 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // The SPS that comes in the current H264 bytestream doesn't set bitstream_restriction_flag
                    // or max_dec_frame_buffering which increases decoding latency on Tegra.

                    // If the encoder didn't include VUI parameters in the SPS, add them now
                    if (sps.vuiParams == null) {
                        LimeLog.info("Adding VUI parameters");
                        sps.vuiParams = new VUIParameters();
                    }

                    // GFE 2.5.11 started sending bitstream restrictions
                    if (sps.vuiParams.bitstreamRestriction == null) {
                        LimeLog.info("Adding bitstream restrictions");
                        sps.vuiParams.bitstreamRestriction = new VUIParameters.BitstreamRestriction();
                        sps.vuiParams.bitstreamRestriction.motionVectorsOverPicBoundariesFlag = true;
                        sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2;
                        sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1;
                        sps.vuiParams.bitstreamRestriction.log2MaxMvLengthHorizontal = 16;
                        sps.vuiParams.bitstreamRestriction.log2MaxMvLengthVertical = 16;
                        sps.vuiParams.bitstreamRestriction.numReorderFrames = 0;
                    }
                    else {
                        LimeLog.info("Patching bitstream restrictions");
                    }

                    // Some devices throw errors if maxDecFrameBuffering < numRefFrames
                    sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames;

                    // These values are the defaults for the fields, but they are more aggressive
                    // than what GFE sends in 2.5.11, but it doesn't seem to cause picture problems.
                    // We'll leave these alone for "modern" devices just in case they care.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2;
                        sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1;
                    }

                    // log2_max_mv_length_horizontal and log2_max_mv_length_vertical are set to more
                    // conservative values by GFE 2.5.11. We'll let those values stand.
                }
                else if (sps.vuiParams != null) {
                    // Devices that didn't/couldn't get bitstream restrictions before GFE 2.5.11
                    // will continue to not receive them now
                    sps.vuiParams.bitstreamRestriction = null;
                }

                // If we need to hack this SPS to say we're baseline, do so now
                if (needsBaselineSpsHack) {
                    LimeLog.info("Hacking SPS to baseline");
                    sps.profileIdc = 66;
                    savedSps = sps;
                }

                // Patch the SPS constraint flags
                doProfileSpecificSpsPatching(sps);

                // The H264Utils.writeSPS function safely handles
                // Annex B NALUs (including NALUs with escape sequences)
                ByteBuffer escapedNalu = H264Utils.writeSPS(sps, decodeUnitLength);

                // Construct the patched SPS
                byte[] naluBuffer = new byte[startSeqLen + 1 + escapedNalu.limit()];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, startSeqLen + 1);
                escapedNalu.get(naluBuffer, startSeqLen + 1, escapedNalu.limit());

                // Batch this to submit together with other CSD per AOSP docs
                spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
                numVpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                vpsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            // Only the HEVC SPS hits this path (H.264 is handled above)
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
                numSpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
                numPpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                ppsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if ((videoFormat & (MoonBridge.VIDEO_FORMAT_MASK_H264 | MoonBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
                // If this is the first CSD blob or we aren't supporting fused IDR frames, we will
                // submit the CSD blob in a separate input buffer for each IDR frame.
                if (!submittedCsd || !fusedIdrFrame) {
                    if (!fetchNextInputBuffer()) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Submit all CSD when we receive the first non-CSD blob in an IDR frame
                    for (byte[] vpsBuffer : vpsBuffers) {
                        nextInputBuffer.put(vpsBuffer);
                    }
                    for (byte[] spsBuffer : spsBuffers) {
                        nextInputBuffer.put(spsBuffer);
                    }
                    for (byte[] ppsBuffer : ppsBuffers) {
                        nextInputBuffer.put(ppsBuffer);
                    }

                    InputQueueResult csdQueueResult = queueNextInputBufferForAdmission(
                            0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                            transitionInputAdmission, frameNumber, idrFrame, false);
                    if (csdQueueResult != InputQueueResult.QUEUED) {
                        if (csdQueueResult == InputQueueResult.TRANSITION_DROPPED) {
                            intentionalInputDiscontinuityPending = true;
                            return MoonBridge.DR_OK;
                        }
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Remember that we already submitted CSD for this frame, so we don't do it
                    // again in the fused IDR case below.
                    csdSubmittedForThisFrame = true;

                    // Remember that we submitted CSD globally for this MediaCodec instance
                    submittedCsd = true;

                    if (needsBaselineSpsHack) {
                        InputQueueResult replayResult = replaySps(
                                transitionInputAdmission, frameNumber, idrFrame);
                        if (replayResult == InputQueueResult.TRANSITION_DROPPED) {
                            intentionalInputDiscontinuityPending = true;
                            return MoonBridge.DR_OK;
                        }
                        if (replayResult != InputQueueResult.QUEUED) {
                            return MoonBridge.DR_NEED_IDR;
                        }
                        needsBaselineSpsHack = false;

                        LimeLog.info("SPS replay complete");
                    }
                }
            }
        }

        synchronized (videoStatsLock) {
            if (frameHostProcessingLatency != 0) {
                if (activeWindowVideoStats.minHostProcessingLatency != 0) {
                    activeWindowVideoStats.minHostProcessingLatency = (char) Math.min(
                            activeWindowVideoStats.minHostProcessingLatency,
                            frameHostProcessingLatency);
                } else {
                    activeWindowVideoStats.minHostProcessingLatency = frameHostProcessingLatency;
                }
                activeWindowVideoStats.framesWithHostProcessingLatency += 1;
            }
            activeWindowVideoStats.maxHostProcessingLatency = (char) Math.max(
                    activeWindowVideoStats.maxHostProcessingLatency,
                    frameHostProcessingLatency);
            activeWindowVideoStats.totalHostProcessingLatency += frameHostProcessingLatency;

            activeWindowVideoStats.totalFramesReceived++;
            activeWindowVideoStats.totalFrames++;

            if (decoderQueueTimeMs >= 0) {
                activeWindowVideoStats.recordDecoderQueueLatency(
                        decoderQueueTimeMs, pendingDecoderFrames);
            }

            if (!FRAME_RENDER_TIME_ONLY) {
                // Count time from first packet received to enqueue time as receive time. We count
                // DU queue time as part of decoding because it is caused by a slow decoder.
                activeWindowVideoStats.totalTimeMs += enqueueTimeMs - receiveTimeMs;
                if (decoderQueueTimeMs >= 0) {
                    activeWindowVideoStats.totalTimeMs += decoderQueueTimeMs;
                }
            }
        }

        if (!fetchNextInputBuffer()) {
            return MoonBridge.DR_NEED_IDR;
        }

        int codecFlags = 0;

        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            codecFlags |= MediaCodec.BUFFER_FLAG_SYNC_FRAME;

            // If we are using fused IDR frames, submit the CSD with each IDR frame
            if (fusedIdrFrame && !csdSubmittedForThisFrame) {
                for (byte[] vpsBuffer : vpsBuffers) {
                    nextInputBuffer.put(vpsBuffer);
                }
                for (byte[] spsBuffer : spsBuffers) {
                    nextInputBuffer.put(spsBuffer);
                }
                for (byte[] ppsBuffer : ppsBuffers) {
                    nextInputBuffer.put(ppsBuffer);
                }
            }
        }

        long timestampUs = enqueueTimeMs * 1000;
        if (timestampUs <= lastTimestampUs) {
            // We can't submit multiple buffers with the same timestamp
            // so bump it up by one before queuing
            timestampUs = lastTimestampUs + 1;
        }
        lastTimestampUs = timestampUs;
        final long queuedTimestampUs = timestampUs;

        numFramesIn++;

        if (decodeUnitLength > nextInputBuffer.limit() - nextInputBuffer.position()) {
            IllegalArgumentException exception = new IllegalArgumentException(
                    "Decode unit length "+decodeUnitLength+" too large for input buffer "+nextInputBuffer.limit());
            if (!reportedCrash) {
                reportedCrash = true;
                crashListener.notifyCrash(exception);
            }
            throw new RendererException(this, exception);
        }

        // Copy data from our buffer list into the input buffer
        nextInputBuffer.put(decodeUnitData, 0, decodeUnitLength);

        InputQueueResult frameQueueResult = queueNextInputBufferForAdmission(
                queuedTimestampUs, codecFlags, transitionInputAdmission,
                frameNumber, idrFrame, true);
        if (frameQueueResult == InputQueueResult.TRANSITION_DROPPED) {
            intentionalInputDiscontinuityPending = true;
            return MoonBridge.DR_OK;
        }
        if (frameQueueResult != InputQueueResult.QUEUED) {
            return MoonBridge.DR_NEED_IDR;
        }

        return MoonBridge.DR_OK;
    }

    private InputQueueResult replaySps(long admissionGeneration,
                                       int frameNumber, boolean idrFrame) {
        if (!fetchNextInputBuffer()) {
            return InputQueueResult.FAILED;
        }

        // Write the Annex B header
        nextInputBuffer.put(new byte[]{0x00, 0x00, 0x00, 0x01, 0x67});

        // Switch the H264 profile back to high
        savedSps.profileIdc = 100;

        // Patch the SPS constraint flags
        doProfileSpecificSpsPatching(savedSps);

        // The H264Utils.writeSPS function safely handles
        // Annex B NALUs (including NALUs with escape sequences)
        ByteBuffer escapedNalu = H264Utils.writeSPS(savedSps, 128);
        nextInputBuffer.put(escapedNalu);

        // Queue the new SPS
        InputQueueResult result = queueNextInputBufferForAdmission(
                0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                admissionGeneration, frameNumber, idrFrame, false);
        if (result == InputQueueResult.QUEUED) {
            savedSps = null;
        }
        return result;
    }

    @Override
    public int getCapabilities() {
        int capabilities = 0;

        // Request the optimal number of slices per frame for this decoder
        capabilities |= MoonBridge.CAPABILITY_SLICES_PER_FRAME(optimalSlicesPerFrame);

        // Enable reference frame invalidation on supported hardware
        if (refFrameInvalidationAvc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC;
        }
        if (refFrameInvalidationHevc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC;
        }
        if (refFrameInvalidationAv1) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1;
        }

        // Enable direct submit on supported hardware
        if (directSubmit) {
            capabilities |= MoonBridge.CAPABILITY_DIRECT_SUBMIT;
        }

        return capabilities;
    }

    /**
     * Packet assembly through MediaCodec output dequeue. This excludes Surface release,
     * SceneCore composition, display vsync, and panel scanout.
     */
    public int getAverageFrameDecodePipelineLatency() {
        synchronized (videoStatsLock) {
            if (globalVideoStats.totalFramesReceived == 0) {
                return 0;
            }
            return (int)(globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived);
        }
    }

    public int getAverageDecoderLatency() {
        synchronized (videoStatsLock) {
            if (globalVideoStats.decoderLatencySamples == 0) {
                return 0;
            }
            return (int)(globalVideoStats.decoderTimeNs
                    / globalVideoStats.decoderLatencySamples / 1_000_000L);
        }
    }

    public Boolean performanceWasTracked() {
        return minDecodeTime < Float.MAX_VALUE;
    }

    @SuppressLint("DefaultLocale")
    public String getMinDecoderLatency() {
        return String.format("%1$.2f", minDecodeTime);
    }

    public String getMinDecoderLatencyFullLog() {
        return minDecodeTimeFullLog;
    }

    static class DecoderHungException extends IllegalStateException {
        private int hangTimeMs;

        DecoderHungException(int hangTimeMs) {
            this.hangTimeMs = hangTimeMs;
        }

        public String toString() {
            String str = "";

            str += "Hang time: "+hangTimeMs+" ms"+ RendererException.DELIMITER;
            str += super.toString();

            return str;
        }
    }

    static class RendererException extends RuntimeException {
        private static final long serialVersionUID = 8985937536997012406L;
        protected static final String DELIMITER = BuildConfig.DEBUG ? "\n" : " | ";

        private String text;

        RendererException(MediaCodecDecoderRenderer renderer, Exception e) {
            this.text = generateText(renderer, e);
        }

        public String toString() {
            return text;
        }

        private String generateText(MediaCodecDecoderRenderer renderer, Exception originalException) {
            String str;

            if (renderer.numVpsIn == 0 && renderer.numSpsIn == 0 && renderer.numPpsIn == 0) {
                str = "PreSPSError";
            }
            else if (renderer.numSpsIn > 0 && renderer.numPpsIn == 0) {
                str = "PrePPSError";
            }
            else if (renderer.numPpsIn > 0 && renderer.numFramesIn == 0) {
                str = "PreIFrameError";
            }
            else if (renderer.numFramesIn > 0 && renderer.outputFormat == null) {
                str = "PreOutputConfigError";
            }
            else if (renderer.outputFormat != null && renderer.numFramesOut == 0) {
                str = "PreOutputError";
            }
            else if (renderer.numFramesOut <= renderer.refreshRate * 30) {
                str = "EarlyOutputError";
            }
            else {
                str = "ErrorWhileStreaming";
            }

            str += "Format: "+String.format("%x", renderer.videoFormat)+DELIMITER;
            str += "AVC Decoder: "+((renderer.avcDecoder != null) ? renderer.avcDecoder.getName():"(none)")+DELIMITER;
            str += "HEVC Decoder: "+((renderer.hevcDecoder != null) ? renderer.hevcDecoder.getName():"(none)")+DELIMITER;
            str += "AV1 Decoder: "+((renderer.av1Decoder != null) ? renderer.av1Decoder.getName():"(none)")+DELIMITER;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.avcDecoder != null) {
                Range<Integer> avcWidthRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();
                str += "AVC supported width range: "+avcWidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> avcFpsRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                        str += "AVC achievable FPS range: "+avcFpsRange+DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "AVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.hevcDecoder != null) {
                Range<Integer> hevcWidthRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();
                str += "HEVC supported width range: "+hevcWidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> hevcFpsRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                        str += "HEVC achievable FPS range: " + hevcFpsRange + DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "HEVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.av1Decoder != null) {
                Range<Integer> av1WidthRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getSupportedWidths();
                str += "AV1 supported width range: "+av1WidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> av1FpsRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                        str += "AV1 achievable FPS range: " + av1FpsRange + DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "AV1 achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            str += "Configured format: "+renderer.configuredFormat+DELIMITER;
            str += "Input format: "+renderer.inputFormat+DELIMITER;
            str += "Output format: "+renderer.outputFormat+DELIMITER;
            str += "Adaptive playback: "+renderer.adaptivePlayback+DELIMITER;
            str += "GL Renderer: "+renderer.glRenderer+DELIMITER;
            //str += "Build fingerprint: "+Build.FINGERPRINT+DELIMITER;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                str += "SOC: "+Build.SOC_MANUFACTURER+" - "+Build.SOC_MODEL+DELIMITER;
                str += "Performance class: "+Build.VERSION.MEDIA_PERFORMANCE_CLASS+DELIMITER;
                /*str += "Vendor params: ";
                List<String> params = renderer.videoDecoder.getSupportedVendorParameters();
                if (params.isEmpty()) {
                    str += "NONE";
                }
                else {
                    for (String param : params) {
                        str += param + " ";
                    }
                }
                str += DELIMITER;*/
            }
            str += "Consecutive crashes: "+renderer.consecutiveCrashCount+DELIMITER;
            str += "RFI active: "+renderer.refFrameInvalidationActive+DELIMITER;
            str += "Using modern SPS patching: "+(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)+DELIMITER;
            str += "Fused IDR frames: "+renderer.fusedIdrFrame+DELIMITER;
            str += "Video dimensions: "+renderer.initialWidth+"x"+renderer.initialHeight+DELIMITER;
            str += "FPS target: "+renderer.refreshRate+DELIMITER;
            str += "Bitrate: "+renderer.prefs.bitrate+" Kbps"+DELIMITER;
            str += "CSD stats: "+renderer.numVpsIn+", "+renderer.numSpsIn+", "+renderer.numPpsIn+DELIMITER;
            str += "Frames in-out: "+renderer.numFramesIn+", "+renderer.numFramesOut+DELIMITER;
            str += "Total frames received: "+renderer.globalVideoStats.totalFramesReceived+DELIMITER;
            str += "Total frames rendered: "+renderer.globalVideoStats.totalFramesRendered+DELIMITER;
            str += "Frame losses: "+renderer.globalVideoStats.framesLost+" in "+renderer.globalVideoStats.frameLossEvents+" loss events"+DELIMITER;
            str += "Average frame decode-pipeline latency: "
                    + renderer.getAverageFrameDecodePipelineLatency() + "ms" + DELIMITER;
            str += "Average hardware decoder latency: "+renderer.getAverageDecoderLatency()+"ms"+DELIMITER;
            str += "Frame pacing mode: "+renderer.prefs.framePacing+DELIMITER;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (originalException instanceof CodecException) {
                    CodecException ce = (CodecException) originalException;

                    str += "Diagnostic Info: "+ce.getDiagnosticInfo()+DELIMITER;
                    str += "Recoverable: "+ce.isRecoverable()+DELIMITER;
                    str += "Transient: "+ce.isTransient()+DELIMITER;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        str += "Codec Error Code: "+ce.getErrorCode()+DELIMITER;
                    }
                }
            }

            str += originalException.toString();

            return str;
        }
    }


    private void applySurfaceFrameRate(android.view.Surface surface, int targetFps) {
        if (surface == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(
                        (float) targetFps,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        android.view.Surface.CHANGE_FRAME_RATE_ALWAYS);
                LimeLog.info("Applied fixed-source Surface frame rate: " + targetFps + " Hz");
            } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                surface.setFrameRate((float) targetFps,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                LimeLog.info("Applied fixed-source Surface frame rate: " + targetFps + " Hz");
            }
        } catch (Throwable t) {
            // best-effort
        }
    }



    private boolean isMTKDecoderName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.startsWith("c2.mtk") || n.startsWith("omx.mtk");
    }

}
