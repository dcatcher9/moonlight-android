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

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class AndroidAudioRenderer implements AudioRenderer {
    private static final int MAX_PENDING_AUDIO_MS = 40;
    private static final long DROP_LOG_INTERVAL_MS = 5_000;
    private static final int MAX_ZERO_LENGTH_WRITES = 3;

    private final Context context;
    private final boolean enableAudioFx;
    private final int audioBoostDb;
    private final AudioManager audioManager;
    private final Object stateLock = new Object();
    private volatile AudioTrack track;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener =
            this::handleAudioFocusChange;
    private final PcmWriter trackWriter = (audioData, offset, shortCount) -> {
        AudioTrack activeTrack = track;
        if (activeTrack == null) {
            return AudioTrack.ERROR_INVALID_OPERATION;
        }
        return activeTrack.write(audioData, offset, shortCount, AudioTrack.WRITE_BLOCKING);
    };

    private volatile boolean started;
    private volatile boolean hasAudioFocus;
    private AudioFocusRequest audioFocusRequest;
    private boolean audioFocusRequested;
    private boolean audioFxSessionOpen;

    private int selectedChannelConfig;
    private int selectedChannelCount;
    private int selectedSampleRate;
    private int selectedBufferSize;
    private boolean selectedLowLatency;
    private Pcm16AudioProcessor audioProcessor;

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
                .setUsage(AudioAttributes.USAGE_GAME);
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

                track = candidate;
                selectedChannelConfig = channelConfig;
                selectedChannelCount = audioConfiguration.channelCount;
                selectedSampleRate = sampleRate;
                selectedBufferSize = bufferSize;
                selectedLowLatency = lowLatency;
                audioProcessor = new Pcm16AudioProcessor(audioBoostDb, sampleRate,
                        audioConfiguration.channelCount);
                LimeLog.info("Audio track configuration: " + bufferSize + " " + lowLatency
                        + ", client boost " + audioBoostDb + " dB");
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

    @Override
    public void playDecodedAudio(short[] audioData, int validShortCount) {
        if (!started || !hasAudioFocus || track == null || audioData == null) {
            return;
        }

        int boundedShortCount = Math.min(Math.max(validShortCount, 0), audioData.length);
        if (boundedShortCount != validShortCount && !invalidShortCountLogged) {
            invalidShortCountLogged = true;
            LimeLog.warning("Invalid decoded audio length " + validShortCount
                    + " for buffer of " + audioData.length + " shorts; clamping");
        }
        if (boundedShortCount == 0) {
            return;
        }

        int pendingDurationMs = MoonBridge.getPendingAudioDuration();
        if (pendingDurationMs >= MAX_PENDING_AUDIO_MS) {
            recordDroppedAudio(boundedShortCount, pendingDurationMs);
            return;
        }
        logBacklogRecoveryIfNeeded(pendingDurationMs);

        audioProcessor.process(audioData, boundedShortCount);
        AudioTrack failedTrack = track;
        int writeResult = writeFully(audioData, boundedShortCount, trackWriter);
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

    private void logBacklogRecoveryIfNeeded(int pendingDurationMs) {
        if (droppedAudioBlocks == 0) {
            return;
        }
        LimeLog.warning("Audio backlog recovered at " + pendingDurationMs + " ms after dropping "
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
                try {
                    track.play();
                    LimeLog.info("Audio focus granted");
                }
                catch (IllegalStateException e) {
                    LimeLog.warning("Unable to start AudioTrack after focus gain: " + e.getMessage());
                }
            }
            else {
                pauseAndFlush(track);
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
            track = null;
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
                if (started && hasAudioFocus) {
                    replacement.play();
                }
                openAudioEffectSessionLocked();
                LimeLog.info("Recovered dead AudioTrack");
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

    static int writeFully(short[] audioData, int validShortCount, PcmWriter writer) {
        int offset = 0;
        int zeroLengthWrites = 0;
        while (offset < validShortCount) {
            int remaining = validShortCount - offset;
            int written = writer.write(audioData, offset, remaining);
            if (written > remaining) {
                return AudioTrack.ERROR_BAD_VALUE;
            }
            if (written > 0) {
                offset += written;
                zeroLengthWrites = 0;
            }
            else if (written < 0) {
                return written;
            }
            else if (++zeroLengthWrites >= MAX_ZERO_LENGTH_WRITES) {
                return AudioTrack.ERROR_INVALID_OPERATION;
            }
        }
        return offset;
    }
}
