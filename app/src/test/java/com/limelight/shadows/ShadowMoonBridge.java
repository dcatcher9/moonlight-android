package com.limelight.shadows;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.util.ArrayList;
import java.util.List;

@Implements(value = com.limelight.nvstream.jni.MoonBridge.class, isInAndroidSdk = false)
public class ShadowMoonBridge {

    // Static initializer override to prevent System.loadLibrary
    @Implementation
    protected static void __staticInitializer__() {
        // no-op
    }

    // Provide minimal nested AudioConfiguration
    public static class AudioConfiguration {
        public final int channelCount;
        public final int channelMask;
        public AudioConfiguration(int c, int m) {
            this.channelCount = c; this.channelMask = m;
        }
        public int toInt() { return 0; }
    }

    // Define constants minimally needed by code under test
    public static final AudioConfiguration AUDIO_CONFIGURATION_STEREO = new AudioConfiguration(2, 0x3);
    public static final AudioConfiguration AUDIO_CONFIGURATION_51_SURROUND = new AudioConfiguration(6, 0x3F);
    public static final AudioConfiguration AUDIO_CONFIGURATION_71_SURROUND = new AudioConfiguration(8, 0x63F);

    public static final int DR_OK = 0;

    public static int CAPABILITY_SLICES_PER_FRAME(byte s) { return 0; }

    public static int getPendingAudioDuration() { return 0; }

    private static final List<Integer> hostSbsTelemetryResults = new ArrayList<>();
    private static final List<Boolean> hostSbsTelemetryEnabledCalls = new ArrayList<>();
    private static int setSbsModeCallCount;
    private static int setVideoModeCallCount;
    private static int sbsDebugDumpCallCount;

    public static void setHostSbsTelemetryResults(int... results) {
        hostSbsTelemetryResults.clear();
        if (results != null) {
            for (int result : results) {
                hostSbsTelemetryResults.add(result);
            }
        }
        hostSbsTelemetryEnabledCalls.clear();
    }

    public static int getHostSbsTelemetryEnabledCallCount() {
        int count = 0;
        for (boolean enabled : hostSbsTelemetryEnabledCalls) {
            if (enabled) {
                count++;
            }
        }
        return count;
    }

    public static int getHostSbsTelemetryDisableCallCount() {
        return hostSbsTelemetryEnabledCalls.size()
                - getHostSbsTelemetryEnabledCallCount();
    }

    public static int getSetSbsModeCallCount() {
        return setSbsModeCallCount;
    }

    public static int getSetVideoModeCallCount() {
        return setVideoModeCallCount;
    }

    public static int getSbsDebugDumpCallCount() {
        return sbsDebugDumpCallCount;
    }

    @Implementation
    public static int sendHostSbsTelemetrySubscription(boolean enabled, boolean focused,
                                                        int requestId, int intervalMs) {
        hostSbsTelemetryEnabledCalls.add(enabled);
        if (hostSbsTelemetryResults.isEmpty()) {
            return 1;
        }
        if (hostSbsTelemetryResults.size() == 1) {
            return hostSbsTelemetryResults.get(0);
        }
        return hostSbsTelemetryResults.remove(0);
    }

    @Implementation
    public static int sendSetSbsMode(int mode) {
        setSbsModeCallCount++;
        return 1;
    }

    @Implementation
    public static int sendSetVideoMode(int width, int height, int framerateX100,
                                       int requestId, int bitrateKbps) {
        setVideoModeCallCount++;
        return 1;
    }

    @Implementation
    public static void sendSbsDebugDump() {
        sbsDebugDumpCallCount++;
    }

    @Resetter
    public static void reset() {
        hostSbsTelemetryResults.clear();
        hostSbsTelemetryEnabledCalls.clear();
        setSbsModeCallCount = 0;
        setVideoModeCallCount = 0;
        sbsDebugDumpCallCount = 0;
    }

    // stubbed methods used by code but not relevant to unit tests
    public static void cleanupBridge() {}
}
