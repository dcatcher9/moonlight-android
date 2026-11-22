package com.limelight.binding.video;

import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_FLAG_CHOREOGRAPHER;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_FLAG_INPUT_THREAD;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_FLAG_RENDER_THREAD;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_RECOVERY_TYPE_FLUSH;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_RECOVERY_TYPE_NONE;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_RECOVERY_TYPE_RESET;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_RECOVERY_TYPE_RESTART;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlaybackThreadPriority;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.TrafficStatsHelper;

import android.util.LongSparseArray;
import android.app.Activity;

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
    public interface OutputSurfaceSwitchCallback {
        void onComplete(boolean success);
    }

    /**
     * Small ownership token shared with the UI deadline that owns one asynchronous bind.
     * COMMITTED is published only after a guarded success callback is already in the main queue;
     * a timeout that wins PENDING -> CANCELLED therefore makes the codec worker roll back. Its
     * monitor covers only state transitions and a main-queue insertion, never a codec operation.
     */
    public static final class OutputSurfaceSwitchRequest {
        private static final int STATE_PENDING = 0;
        private static final int STATE_COMMITTED = 1;
        private static final int STATE_CANCELLED = 2;
        private static final int STATE_DELIVERED = 3;

        private final long epoch;
        private final AtomicInteger state = new AtomicInteger(STATE_PENDING);
        private final Object transitionLock = new Object();

        OutputSurfaceSwitchRequest(long epoch) {
            this.epoch = epoch;
        }

        boolean isPending() {
            return state.get() == STATE_PENDING;
        }

        /** Atomically queues success before making it visible to a competing UI deadline. */
        boolean enqueueCompletionAndCommit(BooleanSupplier enqueueCompletion) {
            synchronized (transitionLock) {
                if (state.get() != STATE_PENDING) {
                    return false;
                }
                if (!enqueueCompletion.getAsBoolean()) {
                    return false;
                }
                return state.compareAndSet(STATE_PENDING, STATE_COMMITTED);
            }
        }

        boolean tryCancelPending() {
            synchronized (transitionLock) {
                return state.compareAndSet(STATE_PENDING, STATE_CANCELLED);
            }
        }

        boolean tryDeliverCommitted() {
            synchronized (transitionLock) {
                return state.compareAndSet(STATE_COMMITTED, STATE_DELIVERED);
            }
        }

        /**
         * @return true when the deadline owns failure; false when queued success already owns it
         */
        public boolean cancelAtDeadline() {
            synchronized (transitionLock) {
                int current = state.get();
                if (current == STATE_COMMITTED || current == STATE_DELIVERED) {
                    return false;
                }
                state.compareAndSet(STATE_PENDING, STATE_CANCELLED);
                return true;
            }
        }

        /** Supersession/teardown cancels even a committed callback that has not been delivered. */
        public void cancel() {
            synchronized (transitionLock) {
                int current = state.get();
                if (current != STATE_CANCELLED && current != STATE_DELIVERED) {
                    state.set(STATE_CANCELLED);
                }
            }
        }
    }

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

    // Only the visible Stats panel needs expensive per-frame timing; normal streaming must not
    // pay for timestamp maps or their cross-thread lock contention.
    private volatile boolean performanceTelemetryEnabled;
    // Decode latency tracking: map PTS(us) -> enqueue time (ns)
    private final LongSparseArray<Long> enqueueNsByPtsUs = new LongSparseArray<>();

    // Largest real resolution in each orientation. Do not combine the independent long-axis
    // maxima into a synthetic 5120x5120 envelope that no quality row can request.
    static final int MAX_LIVE_LANDSCAPE_WIDTH = 5120;
    static final int MAX_LIVE_LANDSCAPE_HEIGHT = 2160;
    static final int MAX_LIVE_PORTRAIT_WIDTH = 2160;
    static final int MAX_LIVE_PORTRAIT_HEIGHT = 5120;

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
                activeWindowVideoStats.totalTimeMs += decodeNs / 1_000_000L;
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


    // The native VideoDec callback thread otherwise inherits ordinary app scheduling priority.
    private final PlaybackThreadPriority inputThreadPriority =
            new PlaybackThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
    private static final int DECODER_MAX_INPUT_SIZE_BYTES = 16 * 1024 * 1024;

    // Chosen once by DecoderSelection in the constructor.
    private final MediaCodecInfo avcDecoder;
    private final MediaCodecInfo hevcDecoder;
    private final MediaCodecInfo av1Decoder;

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
    private int pendingInputCommitFrameNumber;
    private int pendingInputCommitSourceId;
    private volatile DecodedSourceIdentityTracker decodedSourceIdentityTracker;
    private final DecoderModeTransitionGate.InputCommitter inputCommitter =
            this::commitPendingInputBuffer;

    private Activity activity;
    /** Published across codec recovery and the dedicated frame-rendered callback Looper. */
    private volatile MediaCodec videoDecoder;
    private Thread rendererThread;
    private boolean needsSpsBitstreamFixup, isExynos4;
    private boolean adaptivePlayback, fusedIdrFrame;
    private boolean constrainedHighProfile;
    private final boolean refFrameInvalidationAvc, refFrameInvalidationHevc, refFrameInvalidationAv1;
    private final byte optimalSlicesPerFrame;
    private boolean refFrameInvalidationActive;
    private int initialWidth, initialHeight;
    private final AtomicReference<DecodedVideoDimensions> currentOutputDimensions =
            new AtomicReference<>(new DecodedVideoDimensions(0, 0));
    // KEY_MAX_WIDTH/KEY_MAX_HEIGHT written into the configured MediaFormat. Zero until a codec
    // has been configured with adaptive playback.
    private volatile int adaptiveEnvelopeWidth;
    private volatile int adaptiveEnvelopeHeight;
    // Pre-size the envelope for live video-mode changes. Cleared when a decoder refuses it so the
    // very next configuration attempt uses exactly the pre-live-change (launch-sized) envelope.
    private boolean extendedAdaptiveEnvelope = true;
    private boolean invertResolution;
    private int videoFormat;
    private volatile Surface renderTarget;
    private volatile boolean stopping;
    private final AtomicBoolean stopPrepared = new AtomicBoolean();
    private CrashListener crashListener;
    private boolean reportedCrash;
    private int consecutiveCrashCount;
    private String glRenderer;
    private PerfOverlayListener perfListener;

    // The codec-recovery lock. CodecRecoveryCoordinator runs every recovery with it held, and
    // surface switches, geometry updates and cleanup take it around their codec operations.
    private final Object codecRecoveryMonitor = new Object();
    private final CodecRecoveryCoordinator codecRecovery = new CodecRecoveryCoordinator(
            codecRecoveryMonitor, () -> stopping, () -> this.choreographerHandlerThread != null);
    // Preallocated so the per-buffer recovery check does not allocate a method reference.
    private final CodecRecoveryCoordinator.Recovery codecRecoveryTransaction =
            this::performCodecRecoveryLocked;
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
    // Output rendering can race beginPresentationModeTransition() from another thread. Feed one
    // preallocated commit callback from fields guarded by modeTransitionStateLock so the final gate
    // check and MediaCodec release are one allocation-free transaction.
    private int pendingOutputCommitBufferIndex;
    private long pendingOutputCommitPresentationTimeUs;
    private long pendingOutputCommitRenderTimestampNs;
    private boolean pendingOutputCommitUsesTimestamp;
    private final DecoderModeTransitionGate.OutputCommitter outputCommitter =
            this::commitPendingOutputBufferForRender;

    private MediaFormat inputFormat;
    private volatile MediaFormat outputFormat;
    private MediaFormat configuredFormat;

    private boolean needsBaselineSpsHack;
    private SeqParameterSet savedSps;

    private RendererException initialException;
    private long initialExceptionTimestamp;
    private static final int EXCEPTION_REPORT_DELAY_MS = 3000;

    private VideoStats activeWindowVideoStats;
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
    /**
     * Input-thread-only: a recovery ran while the transition gate was dropping input, so the
     * decoder accepts nothing but an IDR until one is admitted.
     */
    private boolean idrRequiredAfterGatedRecovery;
    private int refreshRate;
    private PreferenceConfiguration prefs;

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
    private final Object frameRenderedCallbackLock = new Object();
    private HandlerThread frameRenderedCallbackThread;
    private Handler frameRenderedCallbackHandler;
    /** Invalidates callbacks posted by an earlier start/reset/recreation epoch. */
    private final AtomicLong frameRenderedCallbackEpoch = new AtomicLong();
    private final Object outputSurfaceSwitchThreadLock = new Object();
    private HandlerThread outputSurfaceSwitchThread;
    private Handler outputSurfaceSwitchHandler;
    /** Invalidates queued/live surface requests when a newer request or teardown takes ownership. */
    private final AtomicLong outputSurfaceSwitchEpoch = new AtomicLong();
    private final AtomicReference<OutputSurfaceSwitchRequest> activeOutputSurfaceSwitchRequest =
            new AtomicReference<>();
    private final AtomicBoolean firstFrameRendered = new AtomicBoolean();
    private volatile Runnable firstFrameRenderedListener;

    private int numSpsIn;
    private int numPpsIn;
    private int numVpsIn;
    private int numFramesIn;
    private int numFramesOut;

    /**
     * Decoder cadence and display preference are intentionally independent. XR panel/thermal
     * follow may temporarily lower the stream to 72 FPS while the user's durable display ceiling
     * remains 90 Hz; using the effective stream rate as a fixed-source Surface hint would pin the
     * panel low and prevent the display callback that restores the stream upward.
     */
    static final class StreamFrameRateState {
        private int effectiveStreamFps;
        private int surfaceFrameRateCeilingFps;

        void initialize(int effectiveFps, int preferredSurfaceCeilingFps) {
            updateEffectiveStreamFps(effectiveFps > 0 ? effectiveFps : 60);
            if (surfaceFrameRateCeilingFps <= 0) {
                updateSurfaceFrameRateCeilingFps(
                        preferredSurfaceCeilingFps > 0
                                ? preferredSurfaceCeilingFps : effectiveStreamFps);
            }
        }

        void updateEffectiveStreamFps(int fps) {
            if (fps > 0) {
                effectiveStreamFps = fps;
            }
        }

        void updateSurfaceFrameRateCeilingFps(int fps) {
            if (fps > 0) {
                surfaceFrameRateCeilingFps = fps;
            }
        }

        int getEffectiveStreamFps() {
            return effectiveStreamFps;
        }

        int getSurfaceFrameRateHintFps() {
            return surfaceFrameRateCeilingFps > 0
                    ? surfaceFrameRateCeilingFps : effectiveStreamFps;
        }
    }

    private final StreamFrameRateState streamFrameRateState = new StreamFrameRateState();

    /**
     * Sets the initial output surface before MediaCodec is configured.
     * Ordinary live XR handoffs must use {@link #setOutputSurfaceAsync}; the synchronous method is
     * reserved for the GL recovery park that must finish before its retired BufferQueue is freed.
     */
    public void setRenderTarget(Surface renderTarget) {
        resetDecodedSourceIdentity();
        this.renderTarget = renderTarget;
    }

    /** Binds the stream's fixed attribution owner before MediaCodec is configured. */
    public void setDecodedSourceIdentityTracker(DecodedSourceIdentityTracker tracker) {
        resetDecodedSourceIdentity();
        decodedSourceIdentityTracker = tracker;
        resetDecodedSourceIdentity();
    }

    private void resetDecodedSourceIdentity() {
        DecodedSourceIdentityTracker tracker = decodedSourceIdentityTracker;
        if (tracker != null) {
            tracker.reset();
        }
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

    /** Enables per-frame timing only for the visible Stats panel. */
    public void setPerformanceTelemetryEnabled(boolean enabled) {
        synchronized (videoStatsLock) {
            if (performanceTelemetryEnabled == enabled) {
                return;
            }

            // Samples captured under the old state must not leak into a later visible window.
            enqueueNsByPtsUs.clear();
            if (enabled) {
                restartPerformanceTelemetryWindow(
                        activeWindowVideoStats, globalVideoStats,
                        SystemClock.uptimeMillis());
            }

            // Publish the new state only after the new window and timestamp map are coherent.
            performanceTelemetryEnabled = enabled;
        }
    }

    static void restartPerformanceTelemetryWindow(VideoStats activeWindow,
                                                  VideoStats globalStats,
                                                  long nowMs) {
        if (activeWindow.measurementStartTimestamp != 0) {
            // Preserve aggregate stream/loss accounting from the partial window that is ending.
            globalStats.add(activeWindow);
        }
        activeWindow.clear();
        activeWindow.measurementStartTimestamp = nowMs;
    }

    /** Close a window under videoStatsLock; only visible Stats needs an immutable copy. */
    static VideoStats completePerformanceTelemetryWindow(VideoStats activeWindow,
                                                         VideoStats globalStats,
                                                         long nowMs,
                                                         boolean snapshotRequested) {
        VideoStats completedWindow = null;
        if (snapshotRequested) {
            completedWindow = new VideoStats();
            completedWindow.copy(activeWindow);
        }
        globalStats.add(activeWindow);
        activeWindow.clear();
        activeWindow.measurementStartTimestamp = nowMs;
        return completedWindow;
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

    private void handleFrameRendered(MediaCodec registeredCodec, long callbackEpoch,
                                     MediaCodec mediaCodec, long presentationTimeUs,
                                     long renderTimeNanos) {
        MediaCodec activeCodec = videoDecoder;
        if (!isFrameRenderedCallbackCurrent(
                callbackEpoch, frameRenderedCallbackEpoch.get(),
                mediaCodec == registeredCodec, registeredCodec == activeCodec, stopping)) {
            return;
        }
        notifyFirstFrameRendered();
        recordFramePresented();
    }

    static boolean isFrameRenderedCallbackCurrent(long callbackEpoch, long activeEpoch,
                                                   boolean callbackMatchesRegistration,
                                                   boolean registrationMatchesActiveCodec,
                                                   boolean stopping) {
        return callbackEpoch > 0L && callbackEpoch == activeEpoch
                && callbackMatchesRegistration && registrationMatchesActiveCodec && !stopping;
    }

    /**
     * Invalidates the application epoch and asks MediaCodec to remove queued framework callbacks.
     * This must run before stop/reset/release: MediaCodec.reset() does not clear pending
     * EVENT_FRAME_RENDERED messages, and those messages invoke the listener current at dispatch.
     */
    private void invalidateFrameRenderedCallbacks(MediaCodec decoder) {
        frameRenderedCallbackEpoch.incrementAndGet();
        if (decoder == null) {
            return;
        }
        try {
            decoder.setOnFrameRenderedListener(null, null);
        } catch (RuntimeException error) {
            // The epoch still rejects a callback whose listener was already snapshotted by the
            // framework. Some vendor codecs reject listener changes after entering an error state.
            LimeLog.warning("Unable to unregister decoder frame callback: " + error);
        }
    }

    /** Returns the lifecycle-owned urgent-display handler used for MediaCodec frame callbacks. */
    private Handler ensureFrameRenderedCallbackHandler() {
        synchronized (frameRenderedCallbackLock) {
            if (frameRenderedCallbackThread != null
                    && frameRenderedCallbackThread.isAlive()
                    && frameRenderedCallbackHandler != null) {
                return frameRenderedCallbackHandler;
            }
            if (stopping) {
                throw new IllegalStateException(
                        "Cannot start decoder frame callbacks while stopping");
            }

            HandlerThread thread = new HandlerThread(
                    "Video - Frame Rendered", Process.THREAD_PRIORITY_URGENT_DISPLAY);
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            frameRenderedCallbackThread = thread;
            frameRenderedCallbackHandler = handler;
            return handler;
        }
    }

    /** Requests callback-looper shutdown without adding another blocking teardown join. */
    private void shutdownFrameRenderedCallbackThread() {
        HandlerThread thread;
        synchronized (frameRenderedCallbackLock) {
            thread = frameRenderedCallbackThread;
            frameRenderedCallbackThread = null;
            frameRenderedCallbackHandler = null;
        }
        if (thread != null) {
            thread.quitSafely();
        }
    }

    /** Returns the lifecycle-owned serial worker for potentially blocking MediaCodec handoffs. */
    private Handler ensureOutputSurfaceSwitchHandler() {
        synchronized (outputSurfaceSwitchThreadLock) {
            if (outputSurfaceSwitchThread != null
                    && outputSurfaceSwitchThread.isAlive()
                    && outputSurfaceSwitchHandler != null) {
                return outputSurfaceSwitchHandler;
            }
            if (stopping) {
                return null;
            }

            HandlerThread thread = new HandlerThread(
                    "Video - Surface Handoff", Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            outputSurfaceSwitchThread = thread;
            outputSurfaceSwitchHandler = handler;
            return handler;
        }
    }

    /** Invalidates pending handoffs and requests worker shutdown without blocking lifecycle callers. */
    private void shutdownOutputSurfaceSwitchThread() {
        outputSurfaceSwitchEpoch.incrementAndGet();
        OutputSurfaceSwitchRequest request = activeOutputSurfaceSwitchRequest.getAndSet(null);
        if (request != null) {
            request.cancel();
        }
        HandlerThread thread;
        synchronized (outputSurfaceSwitchThreadLock) {
            thread = outputSurfaceSwitchThread;
            outputSurfaceSwitchThread = null;
            outputSurfaceSwitchHandler = null;
        }
        if (thread != null) {
            thread.quitSafely();
        }
    }

    public MediaCodecDecoderRenderer(Activity activity, PreferenceConfiguration prefs,
                                     CrashListener crashListener, int consecutiveCrashCount,
                                     boolean meteredData, boolean requestedHdr, boolean invertResolution,
                                     String glRenderer, PerfOverlayListener perfListener) {
        this.modeTransitionHandler = new Handler(Looper.getMainLooper());
        this.activity = activity;
        this.prefs = prefs;
        int configuredFpsCeiling = Float.isFinite(prefs.fps) && prefs.fps > 0f
                ? Math.round(prefs.fps) : 60;
        streamFrameRateState.initialize(configuredFpsCeiling, configuredFpsCeiling);
        this.performanceTelemetryEnabled = prefs.enablePerfOverlay;
        this.crashListener = crashListener;
        this.consecutiveCrashCount = consecutiveCrashCount;
        this.glRenderer = glRenderer;
        this.perfListener = perfListener;
        this.invertResolution = invertResolution;

        this.activeWindowVideoStats = new VideoStats();
        this.globalVideoStats = new VideoStats();

        // Decoders are chosen here, before setup() delivers the negotiated stream size, because
        // getCapabilities() can run first. Select against the requested size in the same
        // orientation that setup() will use.
        int requestedWidth = invertResolution ? prefs.height : prefs.width;
        int requestedHeight = invertResolution ? prefs.width : prefs.height;

        DecoderSelection selection = DecoderSelection.select(
                prefs, requestedHdr, requestedWidth, requestedHeight, consecutiveCrashCount);
        avcDecoder = selection.avcDecoder;
        hevcDecoder = selection.hevcDecoder;
        av1Decoder = selection.av1Decoder;
        refFrameInvalidationAvc = selection.refFrameInvalidationAvc;
        refFrameInvalidationHevc = selection.refFrameInvalidationHevc;
        refFrameInvalidationAv1 = selection.refFrameInvalidationAv1;
        optimalSlicesPerFrame = selection.optimalSlicesPerFrame;
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
                return activity.getString(R.string.video_range_full);
            case LIMITED:
                return activity.getString(R.string.video_range_limited);
            case UNRECOGNIZED:
                return "Unknown (decoder reported an unsupported value)";
            case UNKNOWN:
            default:
                String requestedRange = activity.getString(
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

    public int getActiveVideoFormat() {
        return this.videoFormat;
    }

    private MediaFormat createBaseMediaFormat(String mimeType) {
        MediaFormat videoFormat = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight);

        // Allow oversized UHQ/movie keyframes to remain a single access unit. This is a capacity
        // hint only; it does not add queued frames or change the selected decoder's latency mode.
        applyDecoderInputCapacity(videoFormat);

        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate);

        // Populate keys for adaptive playback
        if (adaptivePlayback) {
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, adaptiveEnvelopeWidth);
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, adaptiveEnvelopeHeight);
        }

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

        logColorFormat("Requested decoder color", videoFormat);

        return videoFormat;
    }

    static void applyDecoderInputCapacity(MediaFormat format) {
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DECODER_MAX_INPUT_SIZE_BYTES);
    }

    /**
     * Computes the adaptive-playback envelope.
     *
     * <p>Two independent things can raise the encoded frame size without a reconfigure:</p>
     * <ul>
     *   <li>Host depth SBS switches the encoded frame from {@code W x H} to a packed
     *       {@code 2W' x H'} side-by-side frame.</li>
     *   <li>A live video-mode change ({@code LiSendSetVideoMode}) raises the stream resolution
     *       with no reconnect. Because {@code KEY_MAX_*} is fixed for the life of the configured
     *       codec, the envelope must be pre-sized at launch to the largest resolution the XR mode
     *       rows can select in that orientation, mirroring {@code XrResolutionSelector}'s
     *       landscape and portrait rows without synthesizing a square Cartesian maximum. With host
     *       doubling that reaches 10240 wide, above every codec ceiling, so the clamp below caps
     *       HEVC/AV1 at 8192 — the host clamps its packed width to match.</li>
     * </ul>
     *
     * <p>{@code extended == false} reproduces the pre-live-change behavior byte for byte, so a
     * decoder that refuses the larger envelope simply falls back to it.</p>
     */
    static int[] adaptivePlaybackEnvelope(int initialWidth, int initialHeight,
                                          boolean hostDoubledWidth, int videoFormat,
                                          boolean extended,
                                          int decoderMaxWidth, int decoderMaxHeight) {
        return adaptivePlaybackEnvelope(initialWidth, initialHeight,
                initialHeight > initialWidth, hostDoubledWidth, videoFormat, extended,
                decoderMaxWidth, decoderMaxHeight);
    }

    static int[] adaptivePlaybackEnvelope(int initialWidth, int initialHeight,
                                          boolean portraitStream,
                                          boolean hostDoubledWidth, int videoFormat,
                                          boolean extended,
                                          int decoderMaxWidth, int decoderMaxHeight) {
        // Same per-codec ceiling used for host SBS packing: 4096 for H.264, 8192 for HEVC/AV1.
        int codecCap = PreferenceConfiguration.maxHostSbsPackedWidthForVideoFormat(videoFormat);
        int width = initialWidth;
        int height = initialHeight;
        if (extended) {
            width = Math.max(width, portraitStream
                    ? MAX_LIVE_PORTRAIT_WIDTH : MAX_LIVE_LANDSCAPE_WIDTH);
            height = Math.max(height, portraitStream
                    ? MAX_LIVE_PORTRAIT_HEIGHT : MAX_LIVE_LANDSCAPE_HEIGHT);
        }
        if (hostDoubledWidth) {
            width = Math.min(width * 2, codecCap);
        }
        width = Math.min(width, codecCap);
        height = Math.min(height, codecCap);
        if (decoderMaxWidth > 0) {
            width = Math.min(width, decoderMaxWidth);
        }
        if (decoderMaxHeight > 0) {
            height = Math.min(height, decoderMaxHeight);
        }
        // The launch geometry must always fit, even if a device advertises a smaller range.
        return new int[] {Math.max(width, initialWidth), Math.max(height, initialHeight)};
    }

    private void computeAdaptivePlaybackEnvelope(MediaCodecInfo decoderInfo, String mimeType) {
        int decoderMaxWidth = 0;
        int decoderMaxHeight = 0;
        try {
            MediaCodecInfo.VideoCapabilities caps = decoderInfo.getCapabilitiesForType(mimeType)
                    .getVideoCapabilities();
            if (caps != null) {
                decoderMaxWidth = caps.getSupportedWidths().getUpper();
                decoderMaxHeight = caps.getSupportedHeights().getUpper();
            }
        } catch (Exception e) {
            // Capability introspection is advisory; the configure-time fallback still applies.
            LimeLog.info("Decoder size capabilities unavailable: " + e);
        }
        boolean portraitStream = prefs != null && prefs.height > prefs.width;
        int[] envelope = adaptivePlaybackEnvelope(initialWidth, initialHeight, portraitStream,
                prefs != null && prefs.isHostDoubledWidthMode(), getActiveVideoFormat(),
                extendedAdaptiveEnvelope, decoderMaxWidth, decoderMaxHeight);
        adaptiveEnvelopeWidth = envelope[0];
        adaptiveEnvelopeHeight = envelope[1];
        LimeLog.info("Adaptive playback envelope: " + adaptiveEnvelopeWidth + "x"
                + adaptiveEnvelopeHeight + (extendedAdaptiveEnvelope ? "" : " (launch-sized)"));
    }

    /**
     * Largest {@code W x H} a live video-mode change can request, as configured on the codec.
     * Returns zero when the envelope cannot absorb any change (no adaptive playback, or no codec
     * configured yet), which callers must treat as "resolution changes need a reconnect".
     */
    public int getConfiguredAdaptiveMaxWidth() {
        return adaptivePlayback && videoDecoder != null ? adaptiveEnvelopeWidth : 0;
    }

    public int getConfiguredAdaptiveMaxHeight() {
        return adaptivePlayback && videoDecoder != null ? adaptiveEnvelopeHeight : 0;
    }

    /** Current visible decoder output size as {@code {width, height}}; zeros before the first format. */
    public int[] getCurrentOutputDimensions() {
        DecodedVideoDimensions dimensions = currentOutputDimensions.get();
        return new int[] {dimensions.width, dimensions.height};
    }

    /**
     * Adopts a live video-mode change that the host has already been asked to perform.
     *
     * <p>{@code initialWidth}/{@code initialHeight}/{@code refreshRate}, the effective stream FPS,
     * and the retained {@code configuredFormat} are replayed by the codec-recovery paths
     * ({@code configureAndStartDecoder(configuredFormat)} on RESTART/RESET, and
     * {@code initializeDecoder(true)} on recreation), so they must all move together. This holds
     * {@code codecRecoveryMonitor} — the same monitor {@link #setOutputSurface} uses — so a
     * recovery can never observe half-updated geometry. The Surface frame-rate ceiling is
     * intentionally not changed here: panel-follow FPS is temporary display evidence, not a new
     * durable display preference.</p>
     */
    public void updateStreamGeometry(int width, int height, int fps) {
        if (width <= 0 || height <= 0) {
            return;
        }
        synchronized (codecRecoveryMonitor) {
            int newWidth = invertResolution ? height : width;
            int newHeight = invertResolution ? width : height;
            if (adaptivePlayback && adaptiveEnvelopeWidth > 0
                    && (newWidth > adaptiveEnvelopeWidth || newHeight > adaptiveEnvelopeHeight)) {
                LimeLog.severe("Refusing live geometry " + newWidth + "x" + newHeight
                        + " outside the configured adaptive envelope " + adaptiveEnvelopeWidth
                        + "x" + adaptiveEnvelopeHeight);
                return;
            }

            initialWidth = newWidth;
            initialHeight = newHeight;
            if (fps > 0) {
                refreshRate = fps;
                streamFrameRateState.updateEffectiveStreamFps(fps);
            }
            if (configuredFormat != null) {
                configuredFormat.setInteger(MediaFormat.KEY_WIDTH, newWidth);
                configuredFormat.setInteger(MediaFormat.KEY_HEIGHT, newHeight);
                if (fps > 0) {
                    configuredFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate);
                }
            }
            LimeLog.info("Live stream geometry now " + newWidth + "x" + newHeight
                    + " @ " + streamFrameRateState.getEffectiveStreamFps()
                    + " FPS (Surface ceiling "
                    + streamFrameRateState.getSurfaceFrameRateHintFps() + " Hz)");
        }
    }

    /**
     * Updates the durable fixed-source display hint independently of effective stream cadence.
     * Called after a live-quality transaction settles; automatic panel following may reapply the
     * existing value but never replaces it with the temporary effective rate.
     */
    public void updateSurfaceFrameRateCeiling(int fps) {
        if (fps <= 0) {
            return;
        }
        synchronized (codecRecoveryMonitor) {
            streamFrameRateState.updateSurfaceFrameRateCeilingFps(fps);
            applySurfaceFrameRate(
                    renderTarget, streamFrameRateState.getSurfaceFrameRateHintFps());
        }
    }

    private void configureAndStartDecoder(MediaFormat format) {
        resetDecodedSourceIdentity();
        // Create the callback looper before entering any vendor configure/start call. Recovery can
        // invoke this method on a codec worker, so relying on its implicit Looper would make
        // callback latency and even callback delivery depend on the caller.
        Handler frameRenderedHandler = ensureFrameRenderedCallbackHandler();

        // Set HDR metadata if present
        if (currentHdrMetadata != null) {
            format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO,
                    HdrStaticInfo.pack(currentHdrMetadata));
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO);
        }

        LimeLog.info("Configuring with format: "+format);

        // A restarted/recreated decoder has not produced an output format yet. Never expose the
        // previous codec instance's color-range report while this configure transaction runs.
        outputFormat = null;
        videoDecoder.configure(format, renderTarget, null, 0);
        inputBufferCapacityLogged = false;

        try {
            applySurfaceFrameRate(
                    renderTarget, streamFrameRateState.getSurfaceFrameRateHintFps());
        } catch (Throwable ignored) {}

        try {
            MediaCodecInfo __info = videoDecoder.getCodecInfo();
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

        // This will contain the actual accepted input format attributes
        inputFormat = videoDecoder.getInputFormat();
        LimeLog.info("Input format: "+inputFormat);

        videoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        // Start the decoder
        videoDecoder.start();

        // Recovery can recreate the native codec instance. Re-register this listener after every
        // configure/start so the first-frame gate remains valid. A non-null explicit Handler keeps
        // per-frame callback work off the main/default Looper.
        MediaCodec callbackCodec = videoDecoder;
        long callbackEpoch = frameRenderedCallbackEpoch.incrementAndGet();
        callbackCodec.setOnFrameRenderedListener(
                (mediaCodec, presentationTimeUs, renderTimeNanos) -> handleFrameRendered(
                        callbackCodec, callbackEpoch, mediaCodec,
                        presentationTimeUs, renderTimeNanos),
                frameRenderedHandler);

// Diagnostics: dump negotiated input/output formats and check vendor keys acceptance
        try {
            MediaFormat __inF = videoDecoder.getInputFormat();
            MediaFormat __outF = videoDecoder.getOutputFormat();
            LimeLog.info("Decoder input format: " + (__inF != null ? __inF.toString() : "<null>"));
            LimeLog.info("Decoder output format: " + (__outF != null ? __outF.toString() : "<null>"));
        } catch (Throwable t) {
            LimeLog.info("Decoder formats unavailable after start");
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
                invalidateFrameRenderedCallbacks(videoDecoder);
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

        computeAdaptivePlaybackEnvelope(selectedDecoderInfo, mimeType);

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
            // Throw the underlying codec exception on the last attempt if the caller requested it.
            // The extended adaptive envelope still has a fallback left, so it is never the last one.
            boolean lastAttempt = !newFormat && !extendedAdaptiveEnvelope;
            if (tryConfigureDecoder(selectedDecoderInfo, mediaFormat,
                    lastAttempt && throwOnCodecError)) {
                // Success!
                break;
            }

            if (!newFormat) {
                if (extendedAdaptiveEnvelope) {
                    // This codec/device cannot be configured with an envelope pre-sized for live
                    // resolution changes. Fall back to exactly the launch-sized envelope used
                    // before live video-mode changes existed; live resolution changes then report
                    // as unsupported through getConfiguredAdaptiveMax*() and reconnect instead.
                    LimeLog.warning("Retrying decoder configuration with the launch-sized "
                            + "adaptive playback envelope");
                    extendedAdaptiveEnvelope = false;
                    computeAdaptivePlaybackEnvelope(selectedDecoderInfo, mimeType);
                    tryNumber = -1;
                    continue;
                }
                // We couldn't even configure a decoder without any low latency options
                return -5;
            }
        }

        return 0;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        streamFrameRateState.initialize(
                redrawRate > 0 ? redrawRate : 60,
                Float.isFinite(prefs.fps) && prefs.fps > 0f
                        ? Math.round(prefs.fps) : 60);
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
        // KEY_FRAME_RATE, the SPS patch and Choreographer pacing all divide by or encode this
        // value, so it takes the same positive fallback as the effective stream rate above.
        this.refreshRate = streamFrameRateState.getEffectiveStreamFps();

        return initializeDecoder(false);
    }

    /**
     * Queues an ordinary live XR output handoff away from the UI/GL threads.
     *
     * <p>The worker is deliberately distinct from the urgent frame-rendered callback thread. A
     * vendor {@link MediaCodec#setOutputSurface} call may block, and delaying frame callbacks would
     * add latency to every rendered frame. Completion is always dispatched on the main thread and
     * is successful only while this request still owns the latest surface epoch and completes
     * before its caller's absolute uptime deadline.</p>
     *
     * @return null when the request could not be queued; accepted requests complete once
     */
    public OutputSurfaceSwitchRequest setOutputSurfaceAsync(
            Surface surface, long ownerDeadlineMs, OutputSurfaceSwitchCallback callback) {
        if (callback == null || surface == null || !surface.isValid() || stopping
                || SystemClock.uptimeMillis() >= ownerDeadlineMs) {
            return null;
        }

        Handler worker = ensureOutputSurfaceSwitchHandler();
        if (worker == null) {
            return null;
        }
        final long requestEpoch = outputSurfaceSwitchEpoch.incrementAndGet();
        final OutputSurfaceSwitchRequest request =
                new OutputSurfaceSwitchRequest(requestEpoch);
        OutputSurfaceSwitchRequest previousRequest =
                activeOutputSurfaceSwitchRequest.getAndSet(request);
        if (previousRequest != null) {
            previousRequest.cancel();
        }
        final long queuedAtMs = SystemClock.uptimeMillis();
        boolean queued = worker.post(() -> {
            long startedAtMs = SystemClock.uptimeMillis();
            boolean success = switchDecoderOutputSurface(
                    surface, requestEpoch, ownerDeadlineMs, request, callback);
            if (!success && request.tryCancelPending()) {
                modeTransitionHandler.post(() -> callback.onComplete(false));
            }
            long completedAtMs = SystemClock.uptimeMillis();
            if (completedAtMs - queuedAtMs >= 16L) {
                LimeLog.info("Decoder output-surface handoff took "
                        + (completedAtMs - queuedAtMs) + " ms (queue "
                        + (startedAtMs - queuedAtMs) + " ms)");
            }
        });
        if (!queued) {
            activeOutputSurfaceSwitchRequest.compareAndSet(request, null);
            request.cancel();
            return null;
        }
        return request;
    }

    /** Nonblocking hard cancellation; an in-flight native call rolls back before committing. */
    public void cancelPendingOutputSurfaceSwitches() {
        outputSurfaceSwitchEpoch.incrementAndGet();
        OutputSurfaceSwitchRequest request = activeOutputSurfaceSwitchRequest.get();
        if (request != null) {
            request.cancel();
        }
    }

    /**
     * Synchronous handoff reserved for GL context recovery. It invalidates every queued ordinary
     * request before waiting so the recovery park is the last physical bind before GL releases the
     * retired BufferQueue.
     */
    public boolean setOutputSurface(Surface surface) {
        OutputSurfaceSwitchRequest request = activeOutputSurfaceSwitchRequest.getAndSet(null);
        if (request != null) {
            request.cancel();
        }
        long requestEpoch = outputSurfaceSwitchEpoch.incrementAndGet();
        return switchDecoderOutputSurface(
                surface, requestEpoch, Long.MAX_VALUE, null, null);
    }

    private boolean switchDecoderOutputSurface(Surface surface, long requestEpoch,
                                                long ownerDeadlineMs,
                                                OutputSurfaceSwitchRequest request,
                                                OutputSurfaceSwitchCallback callback) {
        if (surface == null || !surface.isValid()) {
            LimeLog.warning("Refusing invalid decoder output surface");
            return false;
        }

        // Codec recovery stops/resets the same MediaCodec instance. Serialize a live surface swap
        // with that recovery transaction so setOutputSurface() cannot race reset()/release().
        synchronized (codecRecoveryMonitor) {
            // A genuine codec-error recovery can overlap a UI mode request. Wait for that recovery
            // rather than racing setOutputSurface() against reset/release.
            long recoveryDeadlineMs = Math.min(
                    SystemClock.uptimeMillis() + 2000L, ownerDeadlineMs);
            while (ownsOutputSurfaceSwitchBeforeDeadline(
                    request, requestEpoch, ownerDeadlineMs)
                    && codecRecovery.isRecoveryPending()) {
                long remainingMs = recoveryDeadlineMs - SystemClock.uptimeMillis();
                if (remainingMs <= 0) {
                    LimeLog.warning("Timed out waiting to switch decoder output surface during recovery");
                    return false;
                }

                try {
                    // Poll the epoch at a low rate so a newer request can supersede a recovery wait
                    // without making its UI caller contend on this monitor merely to notify it.
                    codecRecoveryMonitor.wait(Math.min(remainingMs, 50L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LimeLog.warning("Interrupted while waiting to switch decoder output surface");
                    return false;
                }
            }

            if (!ownsOutputSurfaceSwitchBeforeDeadline(
                    request, requestEpoch, ownerDeadlineMs)
                    || videoDecoder == null
                    || codecRecovery.isRecoveryPending()) {
                LimeLog.warning("Cannot switch decoder output surface while decoder is unavailable");
                return false;
            }
            try {
                Surface previousSurface = renderTarget;
                resetDecodedSourceIdentity();
                videoDecoder.setOutputSurface(surface);
                if (!ownsOutputSurfaceSwitchBeforeDeadline(
                        request, requestEpoch, ownerDeadlineMs)) {
                    // The native call cannot be interrupted, but timeout/supersession must not
                    // leave its retired target installed. Surfaces remain owned until decoder
                    // cleanup serializes through this monitor, so the previous target is safe to
                    // restore here. A stopping decoder will be released as soon as we unlock.
                    return rollbackDecoderOutputSurface(
                            previousSurface, surface, "after the native bind");
                }
                renderTarget = surface;
                // SceneCore may return a replacement Surface after a resize or an EGL handoff.
                // Frame-rate metadata belongs to the Surface, so restore it after every live swap.
                applySurfaceFrameRate(
                        surface, streamFrameRateState.getSurfaceFrameRateHintFps());
                if (!ownsOutputSurfaceSwitchBeforeDeadline(
                        request, requestEpoch, ownerDeadlineMs)) {
                    // Cancellation can race the first post-native check because it deliberately
                    // does not take codecRecoveryMonitor. Recheck after publishing metadata and
                    // restore the retained target if ownership changed in that interval.
                    return rollbackDecoderOutputSurface(
                            previousSurface, surface, "after target publication");
                }

                if (request != null) {
                    // Queue guarded success and publish COMMITTED as one tiny request-token
                    // transaction. The UI deadline contends only with this MessageQueue insert,
                    // never with codecRecoveryMonitor or the vendor call. If deadline cancellation
                    // already won, this returns false and the physical target is restored here.
                    Runnable deliverSuccess = () -> {
                        if (request.tryDeliverCommitted()) {
                            callback.onComplete(true);
                        }
                    };
                    long completedAtMs = SystemClock.uptimeMillis();
                    long callbackAtMs = Math.min(completedAtMs, ownerDeadlineMs - 1L);
                    if (!request.enqueueCompletionAndCommit(
                            () -> SystemClock.uptimeMillis() < ownerDeadlineMs
                                    && modeTransitionHandler.postAtTime(
                                    deliverSuccess, callbackAtMs)
                                    && SystemClock.uptimeMillis() < ownerDeadlineMs)) {
                        return rollbackDecoderOutputSurface(
                                previousSurface, surface,
                                "after deadline cancellation or callback enqueue failure");
                    }
                }
                return true;
            } catch (Exception e) {
                LimeLog.warning("Decoder output-surface switch failed: " + e);
                return false;
            } finally {
                // Inputs and output releases can race the vendor surface call. Neither side of
                // that boundary may retain metadata for a buffer delivered to the other surface.
                resetDecodedSourceIdentity();
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
     * all-thread flush barrier. AV1 transitions only gate: a live flush or restart can leave
     * Qualcomm's AV1 Codec2 buffer channel discarding every completed work. AV1 HDR metadata
     * changes use an in-place reset instead (see setHdrMode()), and an AV1 codec failure
     * recreates the decoder.
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
            else if (codecRecovery.schedulePresentationModeFlush()) {
                LimeLog.info("XR mode transition: gating compressed frames and flushing decoder");
            }
            else {
                // A stronger HDR/error recovery already invalidates every queued codec buffer.
                LimeLog.info("XR mode transition: gating compressed frames; decoder recovery "
                        + codecRecovery.type() + " already pending");
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
                // Once the fresh output deadline expires, its eventual contents are no longer a
                // trustworthy completion proof. Invalidate anything racing this callback and keep
                // both sides closed until prepareForStop() tears down the stream.
                if (modeTransitionFrameGate.retainClosedAfterFailure(latestInputFrameNumber)) {
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

    /**
     * Revalidates and renders one decoded output atomically against a presentation transition.
     * DROP means the caller still owns the buffer and must release it without rendering.
     */
    private DecoderModeTransitionGate.OutputDecision commitOutputBufferForRender(
            int bufferIndex, long presentationTimeUs, boolean usesTimestamp,
            long renderTimestampNs) {
        IntConsumer opened = null;
        int openedTransitionGeneration = 0;
        DecoderModeTransitionGate.OutputDecision decision;
        synchronized (modeTransitionStateLock) {
            pendingOutputCommitBufferIndex = bufferIndex;
            pendingOutputCommitPresentationTimeUs = presentationTimeUs;
            pendingOutputCommitUsesTimestamp = usesTimestamp;
            pendingOutputCommitRenderTimestampNs = renderTimestampNs;
            decision = modeTransitionFrameGate.commitOutput(
                    presentationTimeUs, outputCommitter);
            if (decision != DecoderModeTransitionGate.OutputDecision.DROP
                    && modeTransitionFrameGate.consumeCompletedTransition()) {
                modeTransitionWatchdogActive = false;
                modeTransitionWatchdogGeneration++;
                opened = modeTransitionOpenedListener;
                openedTransitionGeneration = activePresentationTransitionGeneration;
                activePresentationTransitionGeneration = 0;
            }
        }
        if (decision == DecoderModeTransitionGate.OutputDecision.DROP) {
            return decision;
        }

        recordFrameReleasedForRender();
        if (opened != null && openedTransitionGeneration > 0) {
            try {
                opened.accept(openedTransitionGeneration);
            } catch (RuntimeException e) {
                LimeLog.warning("XR mode transition opened-listener failed: " + e);
            }
        }
        return decision;
    }

    private DecoderModeTransitionGate.OutputDecision commitOutputBufferForRender(
            int bufferIndex, long presentationTimeUs, long renderTimestampNs) {
        return commitOutputBufferForRender(
                bufferIndex, presentationTimeUs, true, renderTimestampNs);
    }

    private DecoderModeTransitionGate.OutputDecision commitOutputBufferForRender(
            int bufferIndex, long presentationTimeUs) {
        return commitOutputBufferForRender(bufferIndex, presentationTimeUs, false, 0L);
    }

    /** Runs only under modeTransitionStateLock and DecoderModeTransitionGate's output monitor. */
    private void commitPendingOutputBufferForRender() {
        DecodedSourceIdentityTracker tracker = decodedSourceIdentityTracker;
        if (tracker != null && !stopping) {
            // Publish first: SurfaceTexture may latch before releaseOutputBuffer returns.
            tracker.recordOutput(pendingOutputCommitPresentationTimeUs,
                    sourceIdentitySurfaceTimestampNs(
                            pendingOutputCommitPresentationTimeUs,
                            pendingOutputCommitUsesTimestamp,
                            pendingOutputCommitRenderTimestampNs));
        }
        try {
            if (pendingOutputCommitUsesTimestamp) {
                videoDecoder.releaseOutputBuffer(
                        pendingOutputCommitBufferIndex, pendingOutputCommitRenderTimestampNs);
            } else {
                videoDecoder.releaseOutputBuffer(pendingOutputCommitBufferIndex, true);
            }
        } catch (RuntimeException e) {
            resetDecodedSourceIdentity();
            throw e;
        }
    }

    static long sourceIdentitySurfaceTimestampNs(
            long presentationTimeUs, boolean usesTimestamp, long renderTimestampNs) {
        if (usesTimestamp) {
            return renderTimestampNs > 0L ? renderTimestampNs : 0L;
        }
        return presentationTimeUs > 0L && presentationTimeUs <= Long.MAX_VALUE / 1000L
                ? presentationTimeUs * 1000L : 0L;
    }

    /**
     * Ownership test repeated at every boundary of the output-surface switch: the request epoch
     * is still the live one, the decoder is not stopping, the owner deadline has not passed, and
     * the request token (when there is one) is still pending. Each call reads the live values.
     */
    private boolean ownsOutputSurfaceSwitchBeforeDeadline(
            OutputSurfaceSwitchRequest request, long requestEpoch, long ownerDeadlineMs) {
        return isOutputSurfaceSwitchCurrentBeforeDeadline(
                requestEpoch, outputSurfaceSwitchEpoch.get(), stopping,
                SystemClock.uptimeMillis(), ownerDeadlineMs)
                && isOutputSurfaceRequestPending(request, requestEpoch);
    }

    private static boolean isOutputSurfaceRequestPending(
            OutputSurfaceSwitchRequest request, long requestEpoch) {
        return request == null
                || (request.epoch == requestEpoch && request.isPending());
    }

    /** Must be called with codecRecoveryMonitor held after the attempted native bind. */
    private boolean rollbackDecoderOutputSurface(
            Surface previousSurface, Surface attemptedSurface, String boundary) {
        if (!stopping && previousSurface != null && previousSurface != attemptedSurface
                && previousSurface.isValid()) {
            try {
                videoDecoder.setOutputSurface(previousSurface);
            } catch (RuntimeException e) {
                // MediaCodec keeps the attempted surface. Record that binding so recovery and
                // teardown act on the surface the codec holds; the caller still reports failure.
                renderTarget = attemptedSurface;
                LimeLog.severe("Decoder output-surface rollback " + boundary
                        + " failed; the decoder remains bound to the abandoned surface: " + e);
                return false;
            }
            renderTarget = previousSurface;
            applySurfaceFrameRate(
                    previousSurface, streamFrameRateState.getSurfaceFrameRateHintFps());
            LimeLog.warning("Rolled back an expired or superseded decoder output-surface "
                    + "handoff " + boundary);
        } else {
            LimeLog.warning("Decoder output-surface handoff lost ownership " + boundary
                    + "; decoder teardown will release it");
        }
        return false;
    }

    static boolean isOutputSurfaceSwitchCurrent(long requestEpoch, long activeEpoch,
                                                boolean stopping) {
        return requestEpoch > 0L && requestEpoch == activeEpoch && !stopping;
    }

    static boolean isOutputSurfaceSwitchCurrentBeforeDeadline(
            long requestEpoch, long activeEpoch, boolean stopping,
            long nowMs, long ownerDeadlineMs) {
        return isOutputSurfaceSwitchCurrent(requestEpoch, activeEpoch, stopping)
                && nowMs < ownerDeadlineMs;
    }

    // All threads that interact with the MediaCodec instance must call this function regularly!
    private boolean doCodecRecoveryIfRequired(int quiescenceFlag) {
        return codecRecovery.awaitOrRecover(quiescenceFlag, codecRecoveryTransaction);
    }

    /**
     * Runs the pending recovery on the final quiesced decoder thread, with codecRecoveryMonitor
     * held. Returns false when the terminal token already released the barrier.
     */
    private boolean performCodecRecoveryLocked() {
        resetDecodedSourceIdentity();
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
        boolean applyingHdrMetadata = codecRecovery.isHdrMetadataRecoveryPending();
        boolean applyingPresentationModeTransition =
                codecRecovery.isPresentationModeRecoveryPending();

        // If we just need a flush, do so now with all threads quiesced.
        if (codecRecovery.type() == CR_RECOVERY_TYPE_FLUSH) {
            LimeLog.warning("Flushing decoder");
            try {
                videoDecoder.flush();
                codecRecovery.compareAndSetType(
                        CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_NONE);
                codecRecovery.clearPresentationModeRecoveryReason();
            } catch (IllegalStateException e) {
                e.printStackTrace();

                // The flush failed, so escalate to a restart.
                codecRecovery.compareAndSetType(
                        CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART);
            }
        }

        if (codecRecovery.finishCancellationIfStopping()) {
            return false;
        }

        // Flushes, HDR setup, and requested presentation transitions are not codec errors.
        if (codecRecovery.type() != CR_RECOVERY_TYPE_NONE
                && !applyingHdrMetadata
                && !applyingPresentationModeTransition) {
            LimeLog.info("Codec recovery attempt: "+codecRecovery.recordFailureAttempt());
        }

        // Keep the committed late-HDR path on an in-place reset. Full AV1 recreation is
        // reserved for an actual codec failure; it must not turn an expected metadata
        // update into a heavier lifecycle transaction.
        boolean recreateAv1Decoder = shouldRecreateAv1Decoder(
                videoFormat, applyingHdrMetadata)
                && (codecRecovery.type() == CR_RECOVERY_TYPE_RESTART
                || codecRecovery.type() == CR_RECOVERY_TYPE_RESET);

        // For recoverable H.264/HEVC exceptions, stop, reconfigure, and restart.
        if (codecRecovery.type() == CR_RECOVERY_TYPE_RESTART && !recreateAv1Decoder) {
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
                invalidateFrameRenderedCallbacks(videoDecoder);
                videoDecoder.stop();
                if (codecRecovery.finishCancellationIfStopping()) {
                    return false;
                }
                configureAndStartDecoder(configuredFormat);
                if (codecRecovery.finishCancellationIfStopping()) {
                    return false;
                }
                codecRecovery.compareAndSetType(
                        CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_NONE);
                codecRecovery.clearRecoveryReasons();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();

                // Our Surface is probably invalid, so just stop
                stopping = true;
                codecRecovery.finishCancellationIfStopping();
                return false;
            } catch (IllegalStateException e) {
                e.printStackTrace();

                // Something went wrong during the restart, let's use a bigger hammer
                // and try a reset instead.
                codecRecovery.compareAndSetType(
                        CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET);
                if (codecRecovery.finishCancellationIfStopping()) {
                    return false;
                }
            }
        }

        if (codecRecovery.finishCancellationIfStopping()) {
            return false;
        }

        // For non-recoverable exceptions, call reset() before escalating to recreation. This
        // retains the established, less expensive recovery path.
        if (codecRecovery.type() == CR_RECOVERY_TYPE_RESET
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
                invalidateFrameRenderedCallbacks(videoDecoder);
                videoDecoder.reset();
                if (codecRecovery.finishCancellationIfStopping()) {
                    return false;
                }
                configureAndStartDecoder(configuredFormat);
                if (codecRecovery.finishCancellationIfStopping()) {
                    return false;
                }
                codecRecovery.compareAndSetType(
                        CR_RECOVERY_TYPE_RESET, CR_RECOVERY_TYPE_NONE);
                codecRecovery.clearRecoveryReasons();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();

                // Our Surface is probably invalid, so just stop
                stopping = true;
                codecRecovery.finishCancellationIfStopping();
                return false;
            } catch (IllegalStateException e) {
                e.printStackTrace();

                // Something went wrong during the reset, we'll have to resort to
                // releasing and recreating the decoder now.
                if (codecRecovery.finishCancellationIfStopping()) {
                    return false;
                }
            }
        }

        if (codecRecovery.finishCancellationIfStopping()) {
            return false;
        }

        // If we _still_ haven't managed to recover, go for the nuclear option and just
        // throw away the old decoder and reinitialize a new one from scratch.
        if (recreateAv1Decoder || codecRecovery.type() == CR_RECOVERY_TYPE_RESET) {
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
                invalidateFrameRenderedCallbacks(oldDecoder);
                videoDecoder = null;
                if (oldDecoder != null) {
                    try {
                        oldDecoder.release();
                    } catch (RuntimeException e) {
                        LimeLog.warning("Failed to release old decoder during recreation: " + e);
                    }
                }

                if (codecRecovery.finishCancellationIfStopping()) {
                    return false;
                }
                int err = initializeDecoder(true);
                if (err != 0) {
                    throw new IllegalStateException("Decoder recreation failed: " + err);
                }
                if (codecRecovery.finishCancellationIfStopping()) {
                    return false;
                }
                if (!codecRecovery.compareAndSetType(
                        CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_NONE)) {
                    codecRecovery.compareAndSetType(
                            CR_RECOVERY_TYPE_RESET, CR_RECOVERY_TYPE_NONE);
                }
                codecRecovery.clearRecoveryReasons();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();

                // Our Surface is probably invalid, so just stop
                stopping = true;
                codecRecovery.finishCancellationIfStopping();
                return false;
            } catch (RuntimeException e) {
                if (codecRecovery.finishCancellationIfStopping()) {
                    return false;
                }
                // If we failed to recover after all of these attempts, just crash
                stopping = true;
                codecRecovery.finishCancellationIfStopping();
                if (!reportedCrash) {
                    reportedCrash = true;
                    crashListener.notifyCrash(e);
                }
                throw new RendererException(this, e);
            }
        }

        return true;
    }

    // Returns true if the exception is transient
    private boolean handleDecoderException(IllegalStateException e) {
        resetDecodedSourceIdentity();
        // Eat decoder exceptions if we're in the process of stopping
        if (stopping) {
            return false;
        }

        if (e instanceof CodecException) {
            CodecException codecExc = (CodecException) e;

            if (codecExc.isTransient()) {
                // We'll let transient exceptions go
                LimeLog.warning(codecExc.getDiagnosticInfo());
                return true;
            }

            LimeLog.severe(codecExc.getDiagnosticInfo());
            codecRecovery.noteCodecFailure();

            // We can attempt a recovery or reset at this stage to try to start decoding again
            if (codecRecovery.hasRecoveryBudget()) {
                codecRecovery.requestRecoveryForCodecException(codecExc.isRecoverable(), e);

                // The recovery will take place when all threads reach doCodecRecoveryIfRequired().
                return false;
            }
        }
        else {
            codecRecovery.noteCodecFailure();
            // IllegalStateException was primarily used prior to the introduction of CodecException.
            // Recovery from this requires a full decoder reset.
            //
            // NB: CodecException is an IllegalStateException, so we must check for it first.
            if (codecRecovery.hasRecoveryBudget()) {
                codecRecovery.requestResetForIllegalState(e);

                return false;
            }
        }

        // Only throw if we're not in the middle of codec recovery
        if (codecRecovery.type() == CR_RECOVERY_TYPE_NONE) {
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

        frameTimeNanos -= activity.getWindowManager().getDefaultDisplay().getAppVsyncOffsetNanos();

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
            DecodedOutputBuffer nextOutputBuffer = null;
            try {
                while ((nextOutputBuffer = outputBufferQueue.poll()) != null) {
                    DecoderModeTransitionGate.OutputDecision outputDecision =
                            commitOutputBufferForRender(
                                    nextOutputBuffer.index,
                                    nextOutputBuffer.presentationTimeUs,
                                    frameTimeNanos);
                    if (outputDecision == DecoderModeTransitionGate.OutputDecision.DROP) {
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer.index, false);
                        nextOutputBuffer = null;
                        // A fresh transition frame may already be queued. Consume consecutive
                        // stale buffers now instead of adding another display interval of latency.
                        continue;
                    }
                    else if (outputDecision
                            == DecoderModeTransitionGate.OutputDecision.ACCEPT_AND_OPEN) {
                        LimeLog.info("XR mode transition: fresh IDR output reached render gate");
                    }

                    lastRenderedFrameTimeNanos = frameTimeNanos;

                    // Frame pacing renders at most one accepted output per display callback.
                    break;
                }
            } catch (IllegalStateException ignored) {
                resetDecodedSourceIdentity();
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

                            // This is the precise end of the MediaCodec enqueue-to-output-dequeue
                            // interval. Presentation policy below must not affect decode latency.
                            updateDecodeLatencyStats(presentationTimeUs);

                            numFramesOut++;

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

                                // Only the newest candidate can render. Recheck it after the drain
                                // so a transition that began while dequeuing cannot be bypassed by
                                // a candidate that was newer than the first output.
                                DecoderModeTransitionGate.OutputDecision outputDecision;
                                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
                                    // A timestamp of zero asks SurfaceFlinger not to discard this frame.
                                    outputDecision = commitOutputBufferForRender(
                                            lastIndex, presentationTimeUs, 0L);
                                } else {
                                    // Minimum latency renders immediately. Timestamp scheduling is
                                    // reserved for the pacing modes above and Choreographer path.
                                    outputDecision = commitOutputBufferForRender(
                                            lastIndex, presentationTimeUs);
                                }
                                if (outputDecision
                                        == DecoderModeTransitionGate.OutputDecision.DROP) {
                                    videoDecoder.releaseOutputBuffer(lastIndex, false);
                                    continue;
                                }
                                if (outputDecision
                                        == DecoderModeTransitionGate.OutputDecision.ACCEPT_AND_OPEN) {
                                    LimeLog.info("XR mode transition: fresh IDR output reached render gate");
                                }
                            } else {
                                // For balanced frame pacing case, the Choreographer callback will handle rendering.
                                // We just put all frames into the output buffer queue and let it handle things.

                                DecoderModeTransitionGate.OutputDecision outputDecision =
                                        evaluatePresentationTransitionOutput(presentationTimeUs);
                                if (outputDecision
                                        == DecoderModeTransitionGate.OutputDecision.DROP) {
                                    videoDecoder.releaseOutputBuffer(lastIndex, false);
                                    continue;
                                }

                                // Discard the oldest buffer before adding if we've reached our limit.
                                //
                                // NB: We have to do this on the producer side because the consumer may not
                                // run for a while (if there is a huge mismatch between stream FPS and display
                                // refresh rate). poll() must remain nonblocking because the consumer can
                                // drain the queue between the size check and removal.
                                if (outputBufferQueue.size() >= OUTPUT_BUFFER_QUEUE_LIMIT) {
                                    DecodedOutputBuffer oldestOutputBuffer =
                                            outputBufferQueue.poll();
                                    if (oldestOutputBuffer != null) {
                                        videoDecoder.releaseOutputBuffer(
                                                oldestOutputBuffer.index, false);
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
            // If we don't have an input buffer index yet, fetch one now. A pending recovery ends
            // the wait: the quiesced output threads release no buffers until this thread joins
            // the barrier in the finally block below.
            while (nextInputBufferIndex < 0 && !stopping && !codecRecovery.isRecoveryPending()
                    && !inputDequeueHangExpired(startTime, SystemClock.uptimeMillis())) {
                nextInputBufferIndex = videoDecoder.dequeueInputBuffer(10000);
            }
            if (nextInputBufferIndex < 0 && !stopping && !codecRecovery.isRecoveryPending()
                    && inputDequeueHangExpired(startTime, SystemClock.uptimeMillis())) {
                handleDecoderException(new DecoderHungException(
                        (int) (SystemClock.uptimeMillis() - startTime)));
            }

            // Get the backing ByteBuffer for the input buffer index
            if (nextInputBufferIndex >= 0) {
                nextInputBuffer = videoDecoder.getInputBuffer(nextInputBufferIndex);
                if (nextInputBuffer == null) {
                    // According to the Android docs, getInputBuffer() can return null "if the
                    // index is not a dequeued input buffer". I don't think this ever should
                    // happen but if it does, let's try to get a new input buffer next time.
                    nextInputBufferIndex = -1;
                }
            }
        } catch (IllegalStateException e) {
            handleDecoderException(e);
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // This thread just took part in a recovery that invalidated every codec buffer. Return
        // false so the caller requests the IDR the recovered decoder needs.
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
        resetDecodedSourceIdentity();
        shutdownOutputSurfaceSwitchThread();
        cancelPresentationModeTransitionInternal();

        // Halt the rendering thread
        if (rendererThread != null) {
            rendererThread.interrupt();
        }

        // Publish recovery cancellation without entering codecRecoveryMonitor. A vendor
        // setOutputSurface() may be uninterruptibly blocked while holding it; prepareForStop() is
        // called from UI surface/failure paths and must remain nonblocking. Every monitor wait
        // polls the token at least every 50 ms (handoff and recovery barrier), and cleanup later
        // takes this monitor before releasing the codec, so teardown still serializes with an
        // in-flight native call.
        codecRecovery.markStopped();

        // Native codec events can still arrive until cleanup unregisters the listener and releases
        // the codec. Keep their looper alive until then; stopping already makes callbacks no-ops.

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
        // setup() failures can reach cleanup() without a normal prepareForStop() call. Publish the
        // terminal state before taking the codec monitor so queued handoffs cannot start afterward;
        // an already-running native handoff owns the same monitor and therefore finishes first.
        stopping = true;
        codecRecovery.markStopped();
        resetDecodedSourceIdentity();
        shutdownOutputSurfaceSwitchThread();
        MediaCodec decoderToRelease;
        synchronized (codecRecoveryMonitor) {
            decoderToRelease = videoDecoder;
            invalidateFrameRenderedCallbacks(decoderToRelease);
            videoDecoder = null;
        }
        if (decoderToRelease != null) {
            try {
                decoderToRelease.release();
            } catch (RuntimeException e) {
                LimeLog.warning("Failed to release decoder during cleanup: " + e);
            }
        }
        // Shut down only after the codec can no longer post events to this looper. This also covers
        // setup() failures that reach cleanup() without a normal stop transaction.
        shutdownFrameRenderedCallbackThread();
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
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
        codecRecovery.scheduleHdrMetadataRecovery(recoveryType);
    }

    private InputQueueResult queueNextInputBufferForAdmission(
            long timestampUs, int codecFlags, long admissionGeneration,
            int frameNumber, int frameSourceId, boolean idrFrame, boolean prepareTransitionIdr) {
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
            pendingInputCommitFrameNumber = frameNumber;
            pendingInputCommitSourceId = frameSourceId;
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

        // This thread just took part in a recovery that invalidated every codec buffer. Fail so
        // the caller requests the IDR the recovered decoder needs.
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
        DecodedSourceIdentityTracker tracker = decodedSourceIdentityTracker;
        if (tracker != null && !stopping
                && (pendingInputCommitCodecFlags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            tracker.recordInput(pendingInputCommitTimestampUs,
                    pendingInputCommitFrameNumber, pendingInputCommitSourceId);
        }
        try {
            videoDecoder.queueInputBuffer(nextInputBufferIndex,
                    0, nextInputBuffer.position(),
                    pendingInputCommitTimestampUs, pendingInputCommitCodecFlags);
        } catch (RuntimeException e) {
            resetDecodedSourceIdentity();
            throw e;
        }
        nextInputBufferIndex = -1;
        nextInputBuffer = null;
    }

    private H264SpsPatcher.Settings spsPatchSettings() {
        return new H264SpsPatcher.Settings(Build.VERSION.SDK_INT, refFrameInvalidationActive,
                initialWidth, initialHeight, refreshRate,
                hevcDecoder != null || av1Decoder != null,
                needsSpsBitstreamFixup || isExynos4, needsBaselineSpsHack,
                constrainedHighProfile);
    }

    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeMs, long enqueueTimeMs) {
        return submitDecodeUnit(decodeUnitData, decodeUnitLength, decodeUnitType,
                frameNumber, frameType, frameHostProcessingLatency, 0,
                receiveTimeMs, enqueueTimeMs);
    }

    @SuppressWarnings("deprecation")
    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                int frameSourceId, long receiveTimeMs, long enqueueTimeMs) {
        if (stopping) {
            // Don't bother if we're stopping
            return MoonBridge.DR_OK;
        }
        inputThreadPriority.apply();

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
            if (doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)) {
                // A transition cancelled before that request reopens the gate for P-frames.
                idrRequiredAfterGatedRecovery = true;
            }
            return transitionInputDecision == DecoderModeTransitionGate.InputDecision.NEED_IDR
                    ? MoonBridge.DR_NEED_IDR : MoonBridge.DR_OK;
        }
        if (idrRequiredAfterGatedRecovery) {
            if (!idrFrame) {
                // The flushed decoder cannot decode a P-frame. One DR_NEED_IDR makes
                // moonlight-common-c drop its queued frames and request the IDR.
                intentionalInputDiscontinuityPending = true;
                return MoonBridge.DR_NEED_IDR;
            }
            idrRequiredAfterGatedRecovery = false;
        }

        long statsNowMs = SystemClock.uptimeMillis();
        VideoStats completedWindowVideoStats = null;
        boolean collectPerformance;
        synchronized (videoStatsLock) {
            collectPerformance = performanceTelemetryEnabled;
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
                completedWindowVideoStats = completePerformanceTelemetryWindow(
                        activeWindowVideoStats, globalVideoStats, statsNowMs, collectPerformance);
                if (collectPerformance && enqueueNsByPtsUs.size() != 0) {
                    pruneStaleDecodeTimestampsLocked(System.nanoTime());
                }
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

                long rttInfo = MoonBridge.getEstimatedRttInfo();
                int estimatedRttMs = rttInfo == -1L
                        ? StreamPerformanceSnapshot.INT_UNAVAILABLE
                        : (int) (rttInfo >>> 32);
                float bandwidthMbps = sampleAppNetworkThroughputMbps(statsNowMs);

                // The XR panel consumes the just-completed active window.
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
                        describeOutputPacing(prefs.framePacing),
                        actualVideoRange);
                if (performanceTelemetryEnabled) {
                    perfListener.onPerfUpdate(performanceSnapshot);
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

                H264SpsPatcher.patch(sps, spsPatchSettings());
                if (needsBaselineSpsHack) {
                    savedSps = sps;
                }

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
                            transitionInputAdmission, frameNumber, 0, idrFrame, false);
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

            // The decode half of the pipeline time is measured only while performance telemetry
            // is enabled, so the receive half is counted under the same condition. A session
            // average then covers measured frames only, rather than mixing complete samples with
            // receive-only ones.
            if (performanceTelemetryEnabled) {
                // Count time from first packet received to enqueue time as receive time. We count
                // DU queue time as part of decoding because it is caused by a slow decoder.
                activeWindowVideoStats.totalTimeMs += enqueueTimeMs - receiveTimeMs;
                if (decoderQueueTimeMs >= 0) {
                    activeWindowVideoStats.totalTimeMs += decoderQueueTimeMs;
                }
                activeWindowVideoStats.pipelineLatencySamples++;
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
                frameNumber, frameSourceId, idrFrame, true);
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
        H264SpsPatcher.applyProfileConstraints(savedSps, constrainedHighProfile);

        // The H264Utils.writeSPS function safely handles
        // Annex B NALUs (including NALUs with escape sequences)
        ByteBuffer escapedNalu = H264Utils.writeSPS(savedSps, 128);
        nextInputBuffer.put(escapedNalu);

        // Queue the new SPS
        InputQueueResult result = queueNextInputBufferForAdmission(
                0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                admissionGeneration, frameNumber, 0, idrFrame, false);
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

        // CAPABILITY_DIRECT_SUBMIT is never requested. Direct submission would call MediaCodec
        // from the native receive thread, where a blocked codec input buffer stops UDP draining;
        // moonlight-common-c's decoder thread and bounded queue feed submitDecodeUnit() instead.
        return capabilities;
    }

    /**
     * Packet assembly through MediaCodec output dequeue. This excludes Surface release,
     * SceneCore composition, display vsync, and panel scanout. Averaged over the frames measured
     * while performance telemetry (the visible Stats panel) was enabled; 0 when none were.
     */
    public int getAverageFrameDecodePipelineLatency() {
        synchronized (videoStatsLock) {
            return averagePipelineLatencyMs(globalVideoStats);
        }
    }

    /** Hardware decode time, measured under the same telemetry condition; 0 without samples. */
    public int getAverageDecoderLatency() {
        synchronized (videoStatsLock) {
            return averageDecoderLatencyMs(globalVideoStats);
        }
    }

    static int averagePipelineLatencyMs(VideoStats stats) {
        if (stats.pipelineLatencySamples == 0) {
            return 0;
        }
        return (int) (stats.totalTimeMs / stats.pipelineLatencySamples);
    }

    static int averageDecoderLatencyMs(VideoStats stats) {
        if (stats.decoderLatencySamples == 0) {
            return 0;
        }
        return (int) (stats.decoderTimeNs / stats.decoderLatencySamples / 1_000_000L);
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
            if (renderer.avcDecoder != null) {
                Range<Integer> avcWidthRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();
                str += "AVC supported width range: "+avcWidthRange+DELIMITER;
                try {
                    Range<Double> avcFpsRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                    str += "AVC achievable FPS range: "+avcFpsRange+DELIMITER;
                } catch (IllegalArgumentException e) {
                    str += "AVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                }
            }
            if (renderer.hevcDecoder != null) {
                Range<Integer> hevcWidthRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();
                str += "HEVC supported width range: "+hevcWidthRange+DELIMITER;
                try {
                    Range<Double> hevcFpsRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                    str += "HEVC achievable FPS range: " + hevcFpsRange + DELIMITER;
                } catch (IllegalArgumentException e) {
                    str += "HEVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                }
            }
            if (renderer.av1Decoder != null) {
                Range<Integer> av1WidthRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getSupportedWidths();
                str += "AV1 supported width range: "+av1WidthRange+DELIMITER;
                try {
                    Range<Double> av1FpsRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                    str += "AV1 achievable FPS range: " + av1FpsRange + DELIMITER;
                } catch (IllegalArgumentException e) {
                    str += "AV1 achievable FPS range: UNSUPPORTED!"+DELIMITER;
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

            if (originalException instanceof CodecException) {
                CodecException ce = (CodecException) originalException;

                str += "Diagnostic Info: "+ce.getDiagnosticInfo()+DELIMITER;
                str += "Recoverable: "+ce.isRecoverable()+DELIMITER;
                str += "Transient: "+ce.isTransient()+DELIMITER;

                str += "Codec Error Code: "+ce.getErrorCode()+DELIMITER;
            }

            str += originalException.toString();

            return str;
        }
    }


    private void applySurfaceFrameRate(android.view.Surface surface, int surfaceFrameRateFps) {
        if (surface == null || surfaceFrameRateFps <= 0) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(
                        (float) surfaceFrameRateFps,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        android.view.Surface.CHANGE_FRAME_RATE_ALWAYS);
                LimeLog.info("Applied fixed-source Surface frame-rate ceiling: "
                        + surfaceFrameRateFps + " Hz");
            } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                surface.setFrameRate((float) surfaceFrameRateFps,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                LimeLog.info("Applied fixed-source Surface frame-rate ceiling: "
                        + surfaceFrameRateFps + " Hz");
            }
        } catch (Throwable t) {
            // best-effort
        }
    }
}
