package com.limelight.nvstream.jni;

import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;

public class MoonBridge {
    /* See documentation in Limelight.h for information about these functions and constants */

    public static final AudioConfiguration AUDIO_CONFIGURATION_STEREO = new AudioConfiguration(2, 0x3);
    public static final AudioConfiguration AUDIO_CONFIGURATION_51_SURROUND = new AudioConfiguration(6, 0x3F);
    public static final AudioConfiguration AUDIO_CONFIGURATION_71_SURROUND = new AudioConfiguration(8, 0x63F);

    public static final int VIDEO_FORMAT_H264 = 0x0001;
    public static final int VIDEO_FORMAT_H265 = 0x0100;
    public static final int VIDEO_FORMAT_H265_MAIN10 = 0x0200;
    public static final int VIDEO_FORMAT_AV1_MAIN8 = 0x1000;
    public static final int VIDEO_FORMAT_AV1_MAIN10 = 0x2000;

    public static final int VIDEO_FORMAT_MASK_H264 = 0x000F;
    public static final int VIDEO_FORMAT_MASK_H265 = 0x0F00;
    public static final int VIDEO_FORMAT_MASK_AV1 = 0xF000;
    public static final int VIDEO_FORMAT_MASK_10BIT = 0x2200;

    public static final int SERVER_CODEC_MODE_H264 = 0x00000001;
    public static final int SERVER_CODEC_MODE_HEVC = 0x00000100;
    public static final int SERVER_CODEC_MODE_HEVC_MAIN10 = 0x00000200;
    public static final int SERVER_CODEC_MODE_AV1_MAIN8 = 0x00010000;
    public static final int SERVER_CODEC_MODE_AV1_MAIN10 = 0x00020000;
    public static final int SERVER_CODEC_MODE_MASK_HEVC =
            SERVER_CODEC_MODE_HEVC | SERVER_CODEC_MODE_HEVC_MAIN10;
    public static final int SERVER_CODEC_MODE_MASK_AV1 =
            SERVER_CODEC_MODE_AV1_MAIN8 | SERVER_CODEC_MODE_AV1_MAIN10;

    public static final int BUFFER_TYPE_PICDATA = 0;
    public static final int BUFFER_TYPE_SPS = 1;
    public static final int BUFFER_TYPE_PPS = 2;
    public static final int BUFFER_TYPE_VPS = 3;

    public static final int FRAME_TYPE_PFRAME = 0;
    public static final int FRAME_TYPE_IDR = 1;

    public static final int COLORSPACE_REC_601 = 0;
    public static final int COLORSPACE_REC_709 = 1;
    public static final int COLORSPACE_REC_2020 = 2;

    public static final int COLOR_RANGE_LIMITED = 0;
    public static final int COLOR_RANGE_FULL = 1;

    public static final int CAPABILITY_DIRECT_SUBMIT = 1;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC = 2;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC = 4;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1 = 0x40;

    public static final int DR_OK = 0;
    public static final int DR_NEED_IDR = -1;

    public static final int CONN_STATUS_OKAY = 0;
    public static final int CONN_STATUS_POOR = 1;

    public static final int ML_ERROR_GRACEFUL_TERMINATION = 0;
    public static final int ML_ERROR_NO_VIDEO_TRAFFIC = -100;
    public static final int ML_ERROR_NO_VIDEO_FRAME = -101;
    public static final int ML_ERROR_UNEXPECTED_EARLY_TERMINATION = -102;
    public static final int ML_ERROR_PROTECTED_CONTENT = -103;
    public static final int ML_ERROR_FRAME_CONVERSION = -104;

    public static final int ML_PORT_INDEX_TCP_47984 = 0;
    public static final int ML_PORT_INDEX_TCP_47989 = 1;
    public static final int ML_PORT_INDEX_TCP_48010 = 2;
    public static final int ML_PORT_INDEX_UDP_47998 = 8;
    public static final int ML_PORT_INDEX_UDP_47999 = 9;
    public static final int ML_PORT_INDEX_UDP_48000 = 10;
    public static final int ML_PORT_INDEX_UDP_48010 = 11;

    public static final int ML_PORT_FLAG_ALL = 0xFFFFFFFF;
    public static final int ML_PORT_FLAG_TCP_47984 = 0x0001;
    public static final int ML_PORT_FLAG_TCP_47989 = 0x0002;
    public static final int ML_PORT_FLAG_TCP_48010 = 0x0004;
    public static final int ML_PORT_FLAG_UDP_47998 = 0x0100;
    public static final int ML_PORT_FLAG_UDP_47999 = 0x0200;
    public static final int ML_PORT_FLAG_UDP_48000 = 0x0400;
    public static final int ML_PORT_FLAG_UDP_48010 = 0x0800;

    public static final int ML_TEST_RESULT_INCONCLUSIVE = 0xFFFFFFFF;

    public static final byte SS_KBE_FLAG_NON_NORMALIZED = 0x01;

    public static final int LI_ERR_UNSUPPORTED = -5501;

    public static final byte LI_TOUCH_EVENT_HOVER       = 0x00;
    public static final byte LI_TOUCH_EVENT_DOWN        = 0x01;
    public static final byte LI_TOUCH_EVENT_UP          = 0x02;
    public static final byte LI_TOUCH_EVENT_MOVE        = 0x03;
    public static final byte LI_TOUCH_EVENT_CANCEL      = 0x04;
    public static final byte LI_TOUCH_EVENT_BUTTON_ONLY = 0x05;
    public static final byte LI_TOUCH_EVENT_HOVER_LEAVE = 0x06;
    public static final byte LI_TOUCH_EVENT_CANCEL_ALL  = 0x07;

    public static final byte LI_TOOL_TYPE_UNKNOWN = 0x00;
    public static final byte LI_TOOL_TYPE_PEN = 0x01;
    public static final byte LI_TOOL_TYPE_ERASER = 0x02;

    public static final byte LI_PEN_BUTTON_PRIMARY = 0x01;
    public static final byte LI_PEN_BUTTON_SECONDARY = 0x02;
    public static final byte LI_PEN_BUTTON_TERTIARY = 0x04;

    public static final byte LI_TILT_UNKNOWN = (byte)0xFF;
    public static final short LI_ROT_UNKNOWN = (short)0xFFFF;

    public static final byte LI_CTYPE_UNKNOWN  = 0x00;
    public static final byte LI_CTYPE_XBOX     = 0x01;
    public static final byte LI_CTYPE_PS       = 0x02;
    public static final byte LI_CTYPE_NINTENDO = 0x03;

    public static final short LI_CCAP_ANALOG_TRIGGERS = 0x01;
    public static final short LI_CCAP_RUMBLE          = 0x02;
    public static final short LI_CCAP_TRIGGER_RUMBLE  = 0x04;
    public static final short LI_CCAP_TOUCHPAD        = 0x08;
    public static final short LI_CCAP_ACCEL           = 0x10;
    public static final short LI_CCAP_GYRO            = 0x20;
    public static final short LI_CCAP_BATTERY_STATE   = 0x40;
    public static final short LI_CCAP_RGB_LED         = 0x80;

    public static final byte LI_MOTION_TYPE_ACCEL = 0x01;
    public static final byte LI_MOTION_TYPE_GYRO  = 0x02;

    public static final byte LI_BATTERY_STATE_UNKNOWN      = 0x00;
    public static final byte LI_BATTERY_STATE_NOT_PRESENT  = 0x01;
    public static final byte LI_BATTERY_STATE_DISCHARGING  = 0x02;
    public static final byte LI_BATTERY_STATE_CHARGING     = 0x03;
    public static final byte LI_BATTERY_STATE_NOT_CHARGING = 0x04; // Connected to power but not charging
    public static final byte LI_BATTERY_STATE_FULL         = 0x05;

    public static final byte LI_BATTERY_PERCENTAGE_UNKNOWN = (byte)0xFF;

    private static final class BridgeSession {
        final AudioRenderer audioRenderer;
        final VideoDecoderRenderer videoRenderer;
        final NvConnectionListener connectionListener;

        BridgeSession(VideoDecoderRenderer videoRenderer, AudioRenderer audioRenderer,
                      NvConnectionListener connectionListener) {
            this.audioRenderer = audioRenderer;
            this.videoRenderer = videoRenderer;
            this.connectionListener = connectionListener;
        }
    }

    // Native callback threads snapshot one immutable session. This prevents cleanup/setup races
    // from mixing a renderer from one session with a listener from another.
    private static volatile BridgeSession bridgeSession;

    static {
        System.loadLibrary("moonlight-core");
        init();
    }

    public static int CAPABILITY_SLICES_PER_FRAME(byte slices) {
        return slices << 24;
    }

    public static class AudioConfiguration {
        public final int channelCount;
        public final int channelMask;

        public AudioConfiguration(int channelCount, int channelMask) {
            this.channelCount = channelCount;
            this.channelMask = channelMask;
        }

        // Creates an AudioConfiguration from the integer value returned by moonlight-common-c
        // See CHANNEL_COUNT_FROM_AUDIO_CONFIGURATION() and CHANNEL_MASK_FROM_AUDIO_CONFIGURATION()
        // in Limelight.h
        private AudioConfiguration(int audioConfiguration) {
            // Check the magic byte before decoding to make sure we got something that's actually
            // a MAKE_AUDIO_CONFIGURATION()-based value and not something else like an older version
            // hardcoded AUDIO_CONFIGURATION value from an earlier version of moonlight-common-c.
            if ((audioConfiguration & 0xFF) != 0xCA) {
                throw new IllegalArgumentException("Audio configuration has invalid magic byte!");
            }

            this.channelCount = (audioConfiguration >> 8) & 0xFF;
            this.channelMask = (audioConfiguration >> 16) & 0xFFFF;
        }

        // See SURROUNDAUDIOINFO_FROM_AUDIO_CONFIGURATION() in Limelight.h
        public int getSurroundAudioInfo() {
            return channelMask << 16 | channelCount;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof AudioConfiguration) {
                AudioConfiguration that = (AudioConfiguration)obj;
                return this.toInt() == that.toInt();
            }

            return false;
        }

        @Override
        public int hashCode() {
            return toInt();
        }

        // Returns the integer value expected by moonlight-common-c
        // See MAKE_AUDIO_CONFIGURATION() in Limelight.h
        public int toInt() {
            return ((channelMask) << 16) | (channelCount << 8) | 0xCA;
        }
    }

    public static int bridgeDrSetup(int videoFormat, int width, int height, int redrawRate) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            return session.videoRenderer.setup(videoFormat, width, height, redrawRate);
        }
        else {
            return -1;
        }
    }

    public static void bridgeDrStart() {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.videoRenderer.start();
        }
    }

    public static void bridgeDrStop() {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.videoRenderer.stop();
        }
    }

    public static void bridgeDrCleanup() {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.videoRenderer.cleanup();
        }
    }

    //todo 不显示画面
    public static int bridgeDrSubmitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                               int frameNumber, int frameType, char frameHostProcessingLatency,
                                               long receiveTimeMs, long enqueueTimeMs) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            return session.videoRenderer.submitDecodeUnit(decodeUnitData, decodeUnitLength,
                    decodeUnitType, frameNumber, frameType, frameHostProcessingLatency, receiveTimeMs, enqueueTimeMs);
        }
        else {
            return DR_OK;
        }
    }

    public static int bridgeArInit(int audioConfiguration, int sampleRate, int samplesPerFrame) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            return session.audioRenderer.setup(
                    new AudioConfiguration(audioConfiguration), sampleRate, samplesPerFrame);
        }
        else {
            return -1;
        }
    }

    public static void bridgeArStart() {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.audioRenderer.start();
        }
    }

    public static void bridgeArStop() {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.audioRenderer.stop();
        }
    }

    public static void bridgeArCleanup() {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.audioRenderer.cleanup();
        }
    }

    //静音 todo
    public static void bridgeArPlaySample(short[] pcmData) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.audioRenderer.playDecodedAudio(pcmData);
        }
    }

    public static void bridgeClStageStarting(int stage) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.stageStarting(getStageName(stage));
        }
    }

    public static void bridgeClStageComplete(int stage) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.stageComplete(getStageName(stage));
        }
    }

    public static void bridgeClStageFailed(int stage, int errorCode) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.stageFailed(
                    getStageName(stage), getPortFlagsFromStage(stage), errorCode);
        }
    }

    public static void bridgeClConnectionStarted() {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.connectionStarted();
        }
    }

    public static void bridgeClConnectionTerminated(int errorCode) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.connectionTerminated(errorCode);
        }
    }

    public static void bridgeClRumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.rumble(controllerNumber, lowFreqMotor, highFreqMotor);
        }
    }

    public static void bridgeClConnectionStatusUpdate(int connectionStatus) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.connectionStatusUpdate(connectionStatus);
        }
    }

    public static void bridgeClSetHdrMode(boolean enabled, byte[] hdrMetadata) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.setHdrMode(enabled, hdrMetadata);
        }
    }

    public static void bridgeClRumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.rumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
        }
    }

    public static void bridgeClSetMotionEventState(short controllerNumber, byte eventType, short sampleRateHz) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.setMotionEventState(controllerNumber, eventType, sampleRateHz);
        }
    }

    public static void bridgeClSetControllerLED(short controllerNumber, byte r, byte g, byte b) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.setControllerLED(controllerNumber, r, g, b);
        }
    }

    // Host SBS depth-engine phase (Apollo extension 0x3006): 0 = idle, 1 = loading, 2 = ready.
    public static void bridgeClDepthStatus(int phase) {
        BridgeSession session = bridgeSession;
        if (session != null) {
            session.connectionListener.depthStatus(phase);
        }
    }

    public static void setupBridge(VideoDecoderRenderer videoRenderer, AudioRenderer audioRenderer, NvConnectionListener connectionListener) {
        bridgeSession = new BridgeSession(videoRenderer, audioRenderer, connectionListener);
    }

    public static void cleanupBridge() {
        bridgeSession = null;
    }

    public static native int startConnection(String address, String appVersion, String gfeVersion,
                                              String rtspSessionUrl, int serverCodecModeSupport,
                                              int width, int height, int fps,
                                              int bitrate, int packetSize, int streamingRemotely,
                                              int audioConfiguration, int supportedVideoFormats,
                                              int clientRefreshRateX100,
                                              byte[] riAesKey, byte[] riAesIv,
                                              int videoCapabilities,
                                              int colorSpace, int colorRange);

    public static native void stopConnection();

    public static native void interruptConnection();

    // Host-side SBS modes for sendSetSbsMode (Apollo protocol extension). Must match the
    // SBS_MODE_* values in moonlight-common-c's Limelight.h.
    public static final int SBS_MODE_OFF = 0; // No host depth; plain W x H frame.
    public static final int SBS_MODE_AI = 1;  // Enable Apollo's selected SBS profile; 2W x H frame.

    // Ask the host (Apollo protocol extension) to switch host-side SBS 3D mode mid-stream.
    /** Returns positive on successful enqueue, zero on send failure, or negative if unsupported. */
    public static native int sendSetSbsMode(int mode);

    /** Request a fresh video IDR after a client-side decoder/surface transition. */
    public static native void requestIdrFrame();

    // Ask the host (Apollo protocol extension) to dump one SBS debug frame (source/depth/SBS)
    // to the host's configured debug dir. For diagnosing 2D->3D reprojection artifacts.
    public static native void sendSbsDebugDump();

    public static native void sendEmptyPayload();

    public static native void sendMouseMove(short deltaX, short deltaY);

    public static native void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight);

    public static native void sendMouseMoveAsMousePosition(short deltaX, short deltaY, short referenceWidth, short referenceHeight);

    public static native void sendMouseButton(byte buttonEvent, byte mouseButton);

    public static native void sendMultiControllerInput(short controllerNumber,
                                    short activeGamepadMask, int buttonFlags,
                                    byte leftTrigger, byte rightTrigger,
                                    short leftStickX, short leftStickY,
                                    short rightStickX, short rightStickY);

    public static native int sendTouchEvent(byte eventType, int pointerId, float x, float y, float pressure,
                                            float contactAreaMajor, float contactAreaMinor, short rotation);

    public static native int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x, float y,
                                          float pressure, float contactAreaMajor, float contactAreaMinor,
                                          short rotation, byte tilt);

    public static native int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask, byte type, int supportedButtonFlags, short capabilities);

    public static native int sendControllerTouchEvent(byte controllerNumber, byte eventType, int pointerId, float x, float y, float pressure);

    public static native int sendControllerMotionEvent(byte controllerNumber, byte motionType, float x, float y, float z);

    public static native int sendControllerBatteryEvent(byte controllerNumber, byte batteryState, byte batteryPercentage);

    public static native void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags);

    public static native void sendMouseHighResScroll(short scrollAmount);

    public static native void sendMouseHighResHScroll(short scrollAmount);

    public static native void sendUtf8Text(String text);

    public static native String getStageName(int stage);

    public static native String findExternalAddressIP4(String stunHostName, int stunPort);

    public static native int getPendingAudioDuration();

    public static native int getPendingVideoFrames();

    public static native int testClientConnectivity(String testServerHostName, int referencePort, int testFlags);

    public static native int getPortFlagsFromStage(int stage);

    public static native int getPortFlagsFromTerminationErrorCode(int errorCode);

    public static native String stringifyPortFlags(int portFlags, String separator);

    // The RTT is in the top 32 bits, and the RTT variance is in the bottom 32 bits
    public static native long getEstimatedRttInfo();

    public static native String getLaunchUrlQueryParameters();

    public static native byte guessControllerType(int vendorId, int productId);

    public static native boolean guessControllerHasPaddles(int vendorId, int productId);

    public static native boolean guessControllerHasShareButton(int vendorId, int productId);

    public static native void init();
}
