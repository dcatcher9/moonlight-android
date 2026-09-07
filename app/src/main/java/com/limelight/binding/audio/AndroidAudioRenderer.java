package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.SystemClock;
import android.os.Process;

import com.limelight.LimeLog;
import com.limelight.binding.PlaybackThreadPriority;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class AndroidAudioRenderer implements AudioRenderer {
    private static final long DROP_LOG_INTERVAL_MS = 5_000;
    private static final int MAX_WRITE_WAIT_MS = AudioBacklogPolicy.MAX_PENDING_AUDIO_MS;

    private final Context context;
    private final boolean enableAudioFx;
    private final int audioBoostDb;
    private final AudioManager audioManager;
    private final Object stateLock = new Object();
    private volatile AudioTrack track;
    // Written only by the single native AudioDec callback owner. Never follows a replacement.
    private AudioTrack writingTrack;
    private long writingGeneration;
    private volatile long playbackGeneration;
    private volatile boolean started;
    private volatile boolean hasAudioFocus;
    // Guarded by stateLock. Focus ownership does not start an empty MODE_STREAM track.
    private boolean trackPlaying;
    private int primedShortCount;
    private int startThresholdShortCount;
    private final PlaybackThreadPriority playbackPriority =
            new PlaybackThreadPriority(Process.THREAD_PRIORITY_AUDIO);
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener =
            this::handleAudioFocusChange;
    private final PcmWriter trackWriter = (audioData, offset, shortCount) -> {
        synchronized (stateLock) {
            if (!started || !hasAudioFocus || track != writingTrack || writingTrack == null
                    || playbackGeneration != writingGeneration) {
                return 0;
            }
            // A nonblocking write keeps stop/focus callbacks and overload recovery independent
            // of a stalled mixer. The lock covers only the immediate write, never a retry wait.
            int written = writingTrack.write(audioData, offset, shortCount, AudioTrack.WRITE_NON_BLOCKING);
            if (!trackPlaying && written >= 0 && written <= shortCount
                    && written % this.selectedChannelCount == 0
                    && started && hasAudioFocus
                    && track == writingTrack && playbackGeneration == writingGeneration) {
                primedShortCount = (int)Math.min(Integer.MAX_VALUE, (long)primedShortCount + written);
                // A short nonblocking transfer also proves the current route's buffer is full.
                // This avoids waiting forever if a route change reduced the configured capacity.
                if (primedShortCount > 0 && (primedShortCount >= startThresholdShortCount
                        || written < shortCount)) {
                    try {
                        writingTrack.play();
                        trackPlaying = true;
                    } catch (IllegalStateException e) {
                        LimeLog.warning("Unable to start primed AudioTrack: " + e.getMessage());
                        return AudioTrack.ERROR_INVALID_OPERATION;
                    }
                }
            }
            return written;
        }
    };
    private final WriteControl writeControl = new WriteControl() {
        @Override public boolean canContinue() {
            // A mid-write overload must keep recovery active for subsequent callbacks too.
            return started && hasAudioFocus && writingTrack != null && track == writingTrack
                    && playbackGeneration == writingGeneration
                    && !backlogPolicy.shouldDrop(MoonBridge.getPendingAudioDuration());
        }
        @Override public long nowMs() { return SystemClock.uptimeMillis(); }
        @Override public void awaitRetry() throws InterruptedException { Thread.sleep(1L); }
    };

    private AudioFocusRequest audioFocusRequest;
    private boolean audioFocusRequested;
    private boolean audioFxSessionOpen;

    private int selectedChannelConfig;
    private int selectedChannelCount;
    private int selectedSampleRate;
    private int selectedBufferSize;
    private boolean selectedLowLatency;
    private Pcm16AudioProcessor audioProcessor;
    private AudioBacklogPolicy backlogPolicy;

    private long droppedAudioBlocks;
    private long droppedAudioDurationMs;
    private long nextDropLogTimeMs;
    private boolean invalidShortCountLogged;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this(context, enableAudioFx, 0);
    }

    public AndroidAudioRenderer(Context context, boolean enableAudioFx, int audioBoostDb) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.audioBoostDb = audioBoostDb;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private AudioAttributes createPlaybackAttributes(boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                // XR uses the content type to distinguish an audiovisual soundtrack from a
                // positional sound effect, including when USAGE_GAME is retained for focus.
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && lowLatency) {
            attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
        }
        return attributesBuilder.build();
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize,
                                        boolean lowLatency) {
        AudioAttributes attributes = createPlaybackAttributes(lowLatency);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributes, format, bufferSize, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate,
                     int samplesPerFrame) {
        int channelConfig;

        switch (audioConfiguration.channelCount) {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // CHANNEL_OUT_7POINT1_SURROUND was unavailable on the app's old minSdk.
                channelConfig = 0x000018fc;
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: " + String.format("0x%X", channelConfig));
        int bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // Try small/large buffers in low-latency mode, then repeat in standard mode.
        for (int i = 0; i < 4; i++) {
            boolean lowLatency = i < 2;
            int bufferSize;
            if (i == 0 || i == 2) {
                bufferSize = bytesPerFrame * 2;
            }
            else {
                bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate, channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT), bytesPerFrame * 2);
                bufferSize = ((bufferSize + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame;
            }

            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate
                    && lowLatency) {
                continue;
            }
            if (enableAudioFx && lowLatency) {
                continue;
            }

            AudioTrack candidate = null;
            try {
                candidate = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                if (candidate.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioTrack failed to initialize");
                }

                selectedChannelConfig = channelConfig;
                selectedChannelCount = audioConfiguration.channelCount;
                selectedSampleRate = sampleRate;
                selectedBufferSize = bufferSize;
                selectedLowLatency = lowLatency;
                int actualBufferFrames;
                synchronized (stateLock) {
                    playbackGeneration++;
                    track = candidate;
                    actualBufferFrames = preparePlaybackLocked(candidate);
                }
                audioProcessor = new Pcm16AudioProcessor(audioBoostDb);
                backlogPolicy = new AudioBacklogPolicy((int) (actualBufferFrames * 1000L / sampleRate));
                logAudioTrackConfiguration(candidate, actualBufferFrames);
                break;
            }
            catch (Exception e) {
                LimeLog.warning("Audio track setup attempt failed: " + e.getMessage());
                if (candidate != null) {
                    try {
                        candidate.release();
                    }
                    catch (Exception ignored) {}
                }
                track = null;
            }
        }

        return track != null ? 0 : -2;
    }

    private void logAudioTrackConfiguration(AudioTrack audioTrack, int actualBufferFrames) {
        String grantedPerformanceMode = "unavailable";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                switch (audioTrack.getPerformanceMode()) {
                    case AudioTrack.PERFORMANCE_MODE_LOW_LATENCY:
                        grantedPerformanceMode = "LOW_LATENCY";
                        break;
                    case AudioTrack.PERFORMANCE_MODE_POWER_SAVING:
                        grantedPerformanceMode = "POWER_SAVING";
                        break;
                    case AudioTrack.PERFORMANCE_MODE_NONE:
                        grantedPerformanceMode = "NONE";
                        break;
                }
            } catch (IllegalStateException e) {
                // A route or track failure must not turn this one-time report into setup failure.
            }
        }
        LimeLog.info("Audio track configuration: requested buffer " + selectedBufferSize
                + " bytes, requested low latency " + selectedLowLatency
                + ", granted performance mode " + grantedPerformanceMode
                + ", actual buffer " + actualBufferFrames + " frames, start threshold "
                + startThresholdShortCount / selectedChannelCount + " frames"
                + ", client boost " + audioBoostDb + " dB");
    }

    @Override
    public void playDecodedAudio(short[] audioData, int validShortCount) {
        final AudioTrack failedTrack;
        final Pcm16AudioProcessor processor;
        final long generation;
        synchronized (stateLock) {
            if (!started || !hasAudioFocus || track == null || audioData == null
                    || selectedChannelCount <= 0 || audioProcessor == null || backlogPolicy == null) {
                return;
            }
            failedTrack = track;
            processor = audioProcessor;
            generation = playbackGeneration;
        }
        playbackPriority.apply();

        int boundedShortCount = Math.min(Math.max(validShortCount, 0), audioData.length);
        if (boundedShortCount != validShortCount && !invalidShortCountLogged) {
            invalidShortCountLogged = true;
            LimeLog.warning("Invalid decoded audio length " + validShortCount
                    + " for buffer of " + audioData.length + " shorts; clamping");
        }
        // Never submit a partial interleaved channel frame, including a malformed callback.
        boundedShortCount -= boundedShortCount % selectedChannelCount;
        if (boundedShortCount == 0) {
            return;
        }

        int pendingDurationMs = MoonBridge.getPendingAudioDuration();
        if (backlogPolicy.shouldDrop(pendingDurationMs)) {
            recordDroppedAudio(boundedShortCount, pendingDurationMs);
            return;
        }
        processor.process(audioData, boundedShortCount);
        writingTrack = failedTrack;
        writingGeneration = generation;
        int writeResult;
        try {
            writeResult = writeFully(audioData, boundedShortCount, selectedChannelCount,
                    trackWriter, writeControl);
        } finally {
            writingTrack = null;
        }
        if (writeResult >= 0
                && started && hasAudioFocus && track == failedTrack
                && playbackGeneration == generation) {
            if (writeResult < boundedShortCount) {
                recordDroppedAudio(boundedShortCount - writeResult, MoonBridge.getPendingAudioDuration());
            }
            else {
                // Reaching the native queue target alone does not establish playback recovery:
                // an incomplete write can immediately begin another discard. Retain the episode
                // totals until this generation completes a PCM write, without another queue read.
                logBacklogRecoveryIfNeeded(pendingDurationMs);
            }
        }
        if (writeResult < 0) {
            LimeLog.warning("AudioTrack.write failed with " + writeResult
                    + "; dropping the unwritten remainder");
            if (writeResult == AudioTrack.ERROR_DEAD_OBJECT) {
                recoverDeadAudioTrack(failedTrack);
            }
        }
    }

    private void recordDroppedAudio(int shortCount, int pendingDurationMs) {
        long durationMs = Math.max(1L,
                shortCount * 1_000L / Math.max(1L,
                        (long) selectedSampleRate * selectedChannelCount));
        droppedAudioBlocks++;
        droppedAudioDurationMs += durationMs;

        long now = SystemClock.elapsedRealtime();
        if (nextDropLogTimeMs == 0) {
            nextDropLogTimeMs = now + DROP_LOG_INTERVAL_MS;
        }
        else if (now >= nextDropLogTimeMs) {
            LimeLog.warning("Audio backlog remains high (" + pendingDurationMs
                    + " ms); dropped " + droppedAudioBlocks + " blocks (~"
                    + droppedAudioDurationMs + " ms) so far");
            nextDropLogTimeMs = now + DROP_LOG_INTERVAL_MS;
        }
    }

    private void logBacklogRecoveryIfNeeded(int admissionPendingDurationMs) {
        if (droppedAudioBlocks == 0) {
            return;
        }
        LimeLog.warning("Audio backlog recovered after a complete PCM write; queue before write: "
                + admissionPendingDurationMs + " ms; dropped "
                + droppedAudioBlocks + " blocks (~" + droppedAudioDurationMs + " ms)");
        resetDropCounters();
    }

    private void resetDropCounters() {
        droppedAudioBlocks = 0;
        droppedAudioDurationMs = 0;
        nextDropLogTimeMs = 0;
    }

    @Override
    public void start() {
        synchronized (stateLock) {
            if (started || track == null) {
                return;
            }
            started = true;
        }

        int focusResult = requestAudioFocus();
        synchronized (stateLock) {
            audioFocusRequested = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    || focusResult == AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
        }

        if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            handleAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        }
        else if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            LimeLog.info("Audio focus delayed; playback will begin when focus is granted");
        }
        else {
            LimeLog.severe("Unable to acquire audio focus; local playback remains paused");
        }

        synchronized (stateLock) {
            openAudioEffectSessionLocked();
        }
    }

    private int requestAudioFocus() {
        if (audioManager == null) {
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            synchronized (stateLock) {
                if (audioFocusRequest == null) {
                    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(createPlaybackAttributes(false))
                            .setAcceptsDelayedFocusGain(true)
                            .setWillPauseWhenDucked(true)
                            .setOnAudioFocusChangeListener(audioFocusChangeListener)
                            .build();
                }
                return audioManager.requestAudioFocus(audioFocusRequest);
            }
        }
        else {
            return audioManager.requestAudioFocus(audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    /** Prime the existing device-sized buffer; this never enlarges the track or queues silence. */
    private int preparePlaybackLocked(AudioTrack audioTrack) {
        int bufferFrames = Math.max(1, selectedBufferSize / Math.max(1, selectedChannelCount * 2));
        try {
            int actualFrames = audioTrack.getBufferSizeInFrames();
            if (actualFrames > 0) {
                bufferFrames = actualFrames;
            }
        } catch (IllegalStateException e) {
            LimeLog.warning("AudioTrack buffer size unavailable; using requested size: " + e.getMessage());
        }
        int thresholdFrames = bufferFrames;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                int actualThreshold = audioTrack.getStartThresholdInFrames();
                if (actualThreshold > 0) {
                    thresholdFrames = Math.min(bufferFrames, actualThreshold);
                }
            } catch (IllegalStateException e) {
                LimeLog.warning("AudioTrack start threshold unavailable; priming its buffer: " + e.getMessage());
            }
        }
        startThresholdShortCount = (int)Math.min(Integer.MAX_VALUE,
                (long)thresholdFrames * Math.max(1, selectedChannelCount));
        clearPlaybackPrimingLocked();
        return bufferFrames;
    }

    private void clearPlaybackPrimingLocked() {
        trackPlaying = false;
        primedShortCount = 0;
    }

    private void handleAudioFocusChange(int focusChange) {
        boolean gainedFocus = focusChange == AudioManager.AUDIOFOCUS_GAIN;
        synchronized (stateLock) {
            if (!started) {
                return;
            }
            hasAudioFocus = gainedFocus;
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                audioFocusRequested = false;
            }
            if (track == null) {
                return;
            }

            // Keep the state transition and AudioTrack operation under the same lock. Android
            // delivers focus callbacks on a different thread than Moonlight's stop path, so
            // releasing the lock here could let a stale gain callback restart playback after
            // stop() has already paused and flushed the track.
            if (gainedFocus) {
                // The native stream may still be in its initial resync/discard interval. Wait
                // for current-generation PCM before play(), including after pause/flush.
                LimeLog.info("Audio focus granted");
            }
            else {
                playbackGeneration++;
                pauseAndFlush(track);
                clearPlaybackPrimingLocked();
                if (backlogPolicy != null) {
                    backlogPolicy.reset();
                }
                LimeLog.info("Audio focus lost (" + focusChange + "); playback paused");
            }
        }
    }

    @Override
    public void stop() {
        boolean abandonFocus;
        synchronized (stateLock) {
            if (!started && !audioFocusRequested) {
                return;
            }
            started = false;
            hasAudioFocus = false;
            playbackGeneration++;
            clearPlaybackPrimingLocked();
            if (backlogPolicy != null) {
                backlogPolicy.reset();
            }
            abandonFocus = audioFocusRequested;
            audioFocusRequested = false;
            closeAudioEffectSessionLocked();
            if (track != null) {
                pauseAndFlush(track);
            }
        }
        if (abandonFocus) {
            abandonAudioFocus();
        }
        if (droppedAudioBlocks != 0) {
            LimeLog.warning("Audio stopped after dropping " + droppedAudioBlocks + " blocks (~"
                    + droppedAudioDurationMs + " ms) for backlog control");
            resetDropCounters();
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
    }

    @Override
    public void cleanup() {
        stop();

        AudioTrack activeTrack;
        synchronized (stateLock) {
            activeTrack = track;
            playbackGeneration++;
            track = null;
            clearPlaybackPrimingLocked();
            audioProcessor = null;
        }
        if (activeTrack != null) {
            pauseAndFlush(activeTrack);
            try {
                activeTrack.release();
            }
            catch (Exception ignored) {}
        }
    }

    private void recoverDeadAudioTrack(AudioTrack failedTrack) {
        synchronized (stateLock) {
            if (failedTrack == null || track != failedTrack) {
                return;
            }

            closeAudioEffectSessionLocked();
            playbackGeneration++;
            try {
                failedTrack.release();
            }
            catch (Exception ignored) {}

            try {
                AudioTrack replacement = createAudioTrack(selectedChannelConfig,
                        selectedSampleRate, selectedBufferSize, selectedLowLatency);
                if (replacement.getState() != AudioTrack.STATE_INITIALIZED) {
                    replacement.release();
                    throw new IllegalStateException("replacement AudioTrack failed to initialize");
                }
                track = replacement;
                int actualBufferFrames = preparePlaybackLocked(replacement);
                backlogPolicy = new AudioBacklogPolicy((int) (actualBufferFrames * 1000L
                        / selectedSampleRate));
                openAudioEffectSessionLocked();
                LimeLog.info("Recovered dead AudioTrack");
                logAudioTrackConfiguration(replacement, actualBufferFrames);
            }
            catch (Exception e) {
                track = null;
                LimeLog.severe("Unable to recover dead AudioTrack: " + e.getMessage());
            }
        }
    }

    private void openAudioEffectSessionLocked() {
        if (!enableAudioFx || audioFxSessionOpen || !started || track == null) {
            return;
        }
        Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
        context.sendBroadcast(intent);
        audioFxSessionOpen = true;
    }

    private void closeAudioEffectSessionLocked() {
        if (!audioFxSessionOpen || track == null) {
            return;
        }
        Intent intent = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
        context.sendBroadcast(intent);
        audioFxSessionOpen = false;
    }

    private static void pauseAndFlush(AudioTrack audioTrack) {
        try {
            audioTrack.pause();
        }
        catch (IllegalStateException ignored) {}
        try {
            audioTrack.flush();
        }
        catch (IllegalStateException ignored) {}
    }

    interface PcmWriter {
        int write(short[] audioData, int offset, int shortCount);
    }

    interface WriteControl {
        boolean canContinue();
        long nowMs();
        void awaitRetry() throws InterruptedException;
    }

    static int writeFully(short[] audioData, int validShortCount, int channelCount,
                          PcmWriter writer, WriteControl control) {
        if (channelCount <= 0 || validShortCount % channelCount != 0) {
            return AudioTrack.ERROR_BAD_VALUE;
        }
        int offset = 0;
        long startedAtMs = control.nowMs();
        while (offset < validShortCount) {
            if (!control.canContinue() || control.nowMs() - startedAtMs >= MAX_WRITE_WAIT_MS) {
                return offset;
            }
            int remaining = validShortCount - offset;
            int written = writer.write(audioData, offset, remaining);
            if (written > remaining || (written > 0 && written % channelCount != 0)) {
                return AudioTrack.ERROR_BAD_VALUE;
            }
            if (written > 0) {
                offset += written;
            }
            else if (written < 0) {
                return written;
            }
            else {
                try {
                    control.awaitRetry();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return offset;
                }
            }
        }
        return offset;
    }
}
