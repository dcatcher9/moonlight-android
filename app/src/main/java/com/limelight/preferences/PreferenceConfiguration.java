package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Display;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.session.SessionSettingsStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PreferenceConfiguration {

    public enum FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264,
    };

    public enum CinemaEnvironment {
        BLACK("black"),
        SYSTEM("system"),
        PASSTHROUGH("passthrough");

        public final String preferenceValue;

        CinemaEnvironment(String preferenceValue) {
            this.preferenceValue = preferenceValue;
        }

        public static CinemaEnvironment fromPreferenceValue(String value) {
            for (CinemaEnvironment environment : values()) {
                if (environment.preferenceValue.equals(value)) {
                    return environment;
                }
            }
            return BLACK;
        }
    }

    public static final String RESOLUTION_PREF_STRING = "list_resolution";
    public static final String FPS_PREF_STRING = "list_fps";
    public static final String BITRATE_PREF_STRING = "seekbar_bitrate_kbps";
    public static final String HOST_AUDIO_PREF_STRING = "checkbox_host_audio";
    private static final String DEADZONE_PREF_STRING = "seekbar_deadzone";
    private static final String LANGUAGE_PREF_STRING = "list_languages";
    public static final String AUDIO_CONFIG_PREF_STRING = "list_audio_config";
    public static final String AUDIO_BOOST_PREF_STRING = "list_audio_boost_db";
    public static final String VIDEO_FORMAT_PREF_STRING = "video_format";
    /** Retired selector key retained only so the one-time migration can remove old values. */
    public static final String CLIENT_SBS_DEPTH_MODEL_PREF_STRING =
            "list_client_sbs_depth_model";
    public static final String CINEMA_ENVIRONMENT_PREF_STRING = "list_cinema_environment";
    public static final String ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr";
    private static final String ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay";
    private static final String MOUSE_EMULATION_STRING = "checkbox_mouse_emulation";
    private static final String FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons";
    public static final String FRAME_PACING_PREF_STRING = "frame_pacing";
    private static final String ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode";
    public static final String VIRTUAL_DISPLAY_ONLY_PREF_STRING =
            "checkbox_virtual_display_only";
    public static final String FULL_RANGE_PREF_STRING = "checkbox_full_range";
    public static final String STREAM_GAMMA_PREF_STRING = "stream_gamma";
    private static final String GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse";
    private static final String GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors";

    private static final String ENABLE_RUMBLE_PREF_STRING = "checkbox_enable_rumble";

    private static final String CHECKBOX_SMART_CLIPBOARD_SYNC = "checkbox_smart_clipboard_sync";
    private static final String CHECKBOX_HIDE_CLIPBOARD_CONTENT = "checkbox_hide_clipboard_content";

    static final String DEFAULT_RESOLUTION = "3840x2160";
    static final String DEFAULT_FPS = "60";
    static final int DEFAULT_BITRATE_KBPS = 200000;
    static final boolean DEFAULT_HOST_AUDIO = false;
    private static final int DEFAULT_DEADZONE = 5;
    public static final String DEFAULT_LANGUAGE = "default";
    static final String DEFAULT_VIDEO_FORMAT = "forceh265";
    /** The sole Client SBS depth model. */
    public static final String CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16 =
            "zipdepth-base-fp16";

    public static int normalizeAudioBoostDb(String storedValue) {
        if ("0".equals(storedValue)) {
            return 0;
        }
        if ("6".equals(storedValue)) {
            return 6;
        }
        return DEFAULT_AUDIO_BOOST_DB;
    }

    static final boolean DEFAULT_ENABLE_HDR = true;
    private static final boolean DEFAULT_ENABLE_PERF_OVERLAY = false;
    private static final boolean DEFAULT_MOUSE_EMULATION = true;
    private static final boolean DEFAULT_FLIP_FACE_BUTTONS = false;
    static final String DEFAULT_AUDIO_CONFIG = "2"; // Stereo
    static final int DEFAULT_AUDIO_BOOST_DB = 3;
    static final String DEFAULT_FRAME_PACING = "latency";
    private static final boolean DEFAULT_ABSOLUTE_MOUSE_MODE = false;
    public static final boolean DEFAULT_VIRTUAL_DISPLAY_ONLY = true;
    public static final boolean DEFAULT_FULL_RANGE = true;
    private static final boolean DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false;
    private static final boolean DEFAULT_GAMEPAD_MOTION_SENSORS = true;
    private static final boolean DEFAULT_ENABLE_RUMBLE = true;
    private static final boolean DEFAULT_SMART_CLIPBOARD_SYNC = false;
    private static final boolean DEFAULT_HIDE_CLIPBOARD_CONTENT = true;

    public static final int FRAME_PACING_MIN_LATENCY = 0;
    public static final int FRAME_PACING_BALANCED = 1;
    public static final int FRAME_PACING_CAP_FPS = 2;
    public static final int FRAME_PACING_MAX_SMOOTHNESS = 3;

    // The render-mode preference is deprecated: streaming always uses the single XR route (starts
    // in Normal/2D, modes switched from the in-headset bar). The per-variant render modes are gone.

    public static final String RES_360P = "640x360";
    public static final String RES_480P = "854x480";
    public static final String RES_720P = "1280x720";
    public static final String RES_1080P = "1920x1080";
    public static final String RES_1440P = "2560x1440";
    public static final String RES_4K = "3840x2160";
    // 21:9 ultrawide.
    public static final String RES_5K2K = "5120x2160";

    /**
     * Lowest selectable resolution since 720p was retired from the XR ladder.
     *
     * <p>A persisted {@code 1280x720} — from global prefs, a per-machine/app session record, or a
     * legacy profile string — would otherwise leave an install pointing at a value the selector no
     * longer offers. Everything that reads a stored resolution runs it through
     * {@link #migrateRetiredResolution} so it lands on the new floor instead.</p>
     */
    public static final String MIN_SELECTABLE_RESOLUTION = RES_1080P;

    /** Maps a retired resolution onto the current ladder. Other values pass through unchanged. */
    public static String migrateRetiredResolution(String resolution) {
        return RES_720P.equals(resolution) ? MIN_SELECTABLE_RESOLUTION : resolution;
    }

    public int width, height, bitrate;
    public float fps;
    public FormatOption videoFormat;
    public CinemaEnvironment cinemaEnvironment = CinemaEnvironment.BLACK;
    public int framePacingWarpFactor = 0;
    public int deadzonePercentage;
    public boolean playHostAudio;
    public String language;
    public boolean flipFaceButtons;
    public boolean onscreenController;
    public boolean smartClipboardSync;
    public boolean hideClipboardContent;
    public boolean enableHdr;
    public com.limelight.nvstream.StreamGamma streamGamma;

    public boolean enablePerfOverlay;


    //多点触控模式
    public boolean enableMultiTouchScreen;

    //物理光标捕获
    public boolean enableMouseLocalCursor;

    public boolean mouseEmulation;

    public boolean touchscreenTrackpad;
    public MoonBridge.AudioConfiguration audioConfiguration;
    public int audioBoostDb;
    public int framePacing;
    public boolean absoluteMouseMode;
    public boolean virtualDisplayOnly;
    public boolean fullRange;
    public boolean gamepadMotionSensors;
    public boolean gamepadTouchpadAsMouse;
    public boolean enableRumble;

    public static boolean isNativeResolution(int width, int height) {
        // It's not a native resolution if it matches an existing resolution option.
        if (width == 640 && height == 360) {
            return false;
        }
        else if (width == 854 && height == 480) {
            return false;
        }
        else if (width == 1280 && height == 720) {
            return false;
        }
        return !XrResolutionOptions.isStandardId(width + "x" + height);
    }

    // If we have a screen that has semi-square dimensions, we may want to change our behavior
    // to allow any orientation and vertical+horizontal resolutions.
    public static boolean isSquarishScreen(int width, int height) {
        float longDim = Math.max(width, height);
        float shortDim = Math.min(width, height);

        // We just put the arbitrary cutoff for a square-ish screen at 1.3
        return longDim / shortDim < 1.3f;
    }

    public static boolean isSquarishScreen(Display display) {
        int width, height;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            width = display.getMode().getPhysicalWidth();
            height = display.getMode().getPhysicalHeight();
        }
        else {
            width = display.getWidth();
            height = display.getHeight();
        }

        return isSquarishScreen(width, height);
    }

    private static String convertFromLegacyResolutionString(String resString) {
        if (resString.equalsIgnoreCase("360p")) {
            return RES_360P;
        }
        else if (resString.equalsIgnoreCase("480p")) {
            return RES_480P;
        }
        else if (resString.equalsIgnoreCase("720p")) {
            // Retired from the ladder; land on the current floor.
            return MIN_SELECTABLE_RESOLUTION;
        }
        else if (resString.equalsIgnoreCase("1080p")) {
            return RES_1080P;
        }
        else if (resString.equalsIgnoreCase("1440p")) {
            return RES_1440P;
        }
        else if (resString.equalsIgnoreCase("4K")) {
            return RES_4K;
        }
        else {
            // Should be unreachable
            return MIN_SELECTABLE_RESOLUTION;
        }
    }

    private static int getWidthFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[0]);
    }

    private static int getHeightFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[1]);
    }

    /** Persist the in-headset Stats choice so an activity reconnect/recreate cannot lose it. */
    public static void setPerformanceOverlayEnabled(Context context, boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(ENABLE_PERF_OVERLAY_STRING, enabled)
                .apply();
    }

    /** Persists the Cinema background selected in either Global Settings or the XR dock. */
    public static void setCinemaEnvironment(Context context, CinemaEnvironment environment) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(CINEMA_ENVIRONMENT_PREF_STRING,
                        (environment != null ? environment : CinemaEnvironment.BLACK).preferenceValue)
                .apply();
    }

    private static FormatOption getVideoFormatValue(SharedPreferences prefs) {
        String str = prefs.getString(VIDEO_FORMAT_PREF_STRING, DEFAULT_VIDEO_FORMAT);
        if (str.equals("auto")) {
            return FormatOption.AUTO;
        }
        else if (str.equals("forceav1")) {
            return FormatOption.FORCE_AV1;
        }
        else if (str.equals("forceh265")) {
            return FormatOption.FORCE_HEVC;
        }
        else if (str.equals("neverh265")) {
            return FormatOption.FORCE_H264;
        }
        else {
            // Should never get here
            return FormatOption.AUTO;
        }
    }

    public static String getSelectedFramePacingName(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING);
    }

    private static int getFramePacingValue(SharedPreferences prefs) {
        String str = prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING);
        if (str.equals("latency")) {
            return FRAME_PACING_MIN_LATENCY;
        }
        else if (str.equals("balanced")) {
            return FRAME_PACING_BALANCED;
        }
        else if (str.equals("cap-fps")) {
            return FRAME_PACING_CAP_FPS;
        }
        else if (str.equals("smoothness")) {
            return FRAME_PACING_MAX_SMOOTHNESS;
        }
        else {
            // Should never get here
            return FRAME_PACING_MIN_LATENCY;
        }
    }

    /** Settings that configure the decoder, which the decoder-crash reset returns to defaults. */
    private static final List<String> STREAMING_SETTING_KEYS = Collections.unmodifiableList(
            Arrays.asList(
                    BITRATE_PREF_STRING,
                    RESOLUTION_PREF_STRING,
                    FPS_PREF_STRING,
                    VIDEO_FORMAT_PREF_STRING,
                    ENABLE_HDR_PREF_STRING,
                    FULL_RANGE_PREF_STRING,
                    STREAM_GAMMA_PREF_STRING));

    /**
     * Returns the streaming settings to their defaults in Global Settings and removes the same
     * settings from every app/PC profile and active session record. A profile override takes
     * precedence over the global value, so a crashing override would otherwise be re-applied on
     * the next launch.
     */
    public static void resetStreamingSettings(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : STREAMING_SETTING_KEYS) {
            editor.remove(key);
        }
        editor.apply();
        if (!new SessionSettingsStore(context)
                .clearValueOverridesForAllSessions(STREAMING_SETTING_KEYS)) {
            LimeLog.warning("Unable to reset the streaming settings saved for each app and PC");
        }
    }

//    public static void completeLanguagePreferenceMigration(Context context) {
//        // Put our language option back to default which tells us that we've already migrated it
//        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
//        prefs.edit().putString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE).apply();
//    }

    public static boolean isShieldAtvFirmwareWithBrokenHdr() {
        // This particular Shield TV firmware crashes when using HDR
        // https://www.nvidia.com/en-us/geforce/forums/notifications/comment/155192/
        return Build.MANUFACTURER.equalsIgnoreCase("NVIDIA") &&
                Build.FINGERPRINT.contains("PPR1.180610.011/4079208_2235.1395");
    }

    public static PreferenceConfiguration readPreferences(Context context) {
        return readPreferences(context, null);
    }

    public static PreferenceConfiguration readPreferences(Context context, SharedPreferences prefs) {
        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context);
        }
        PreferenceConfiguration config = new PreferenceConfiguration();

        // The retired upstream keys (combined resolution/fps, 5.1 checkbox, stretch,
        // enforce-refresh-rate, Mbps bitrate, never-drop-frames) are removed from the global
        // preferences by LegacyProfileMigration before any read, so they are not read here.
        String resStr = prefs.getString(RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);

        // Convert legacy resolution strings to the new style
        if (!resStr.contains("x")) {
            resStr = PreferenceConfiguration.convertFromLegacyResolutionString(resStr);
            prefs.edit().putString(RESOLUTION_PREF_STRING, resStr).apply();
        }

        // Retire a stored 720p in place so the stored value and the ladder stay consistent.
        String migratedRes = PreferenceConfiguration.migrateRetiredResolution(resStr);
        if (!migratedRes.equals(resStr)) {
            resStr = migratedRes;
            prefs.edit().putString(RESOLUTION_PREF_STRING, resStr).apply();
        }

        config.width = PreferenceConfiguration.getWidthFromResolutionString(resStr);
        config.height = PreferenceConfiguration.getHeightFromResolutionString(resStr);
        config.fps = Float.parseFloat(prefs.getString(FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS));

        if (!prefs.contains(GAMEPAD_MOTION_SENSORS_PREF_STRING) && Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
            // Android 12 has a nasty bug that causes crashes when the app touches the InputDevice's
            // associated InputDeviceSensorManager (just calling getSensorManager() is enough).
            // As a workaround, we will override the default value for the gamepad motion sensor
            // option to disabled on Android 12 to reduce the impact of this bug.
            // https://cs.android.com/android/_/android/platform/frameworks/base/+/8970010a5e9f3dc5c069f56b4147552accfcbbeb
            prefs.edit().putBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, false).apply();
        }

        // This must happen after the preferences migration to ensure the preferences are populated
        config.bitrate = prefs.getInt(BITRATE_PREF_STRING, 0);
        if (config.bitrate == 0) {
            config.bitrate = DEFAULT_BITRATE_KBPS;
        }

        String audioConfig = prefs.getString(AUDIO_CONFIG_PREF_STRING, DEFAULT_AUDIO_CONFIG);
        if (audioConfig.equals("71")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_71_SURROUND;
        }
        else if (audioConfig.equals("51")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_51_SURROUND;
        }
        else /* if (audioConfig.equals("2")) */ {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        }

        String storedAudioBoostDb = null;
        try {
            storedAudioBoostDb = prefs.getString(AUDIO_BOOST_PREF_STRING,
                    Integer.toString(DEFAULT_AUDIO_BOOST_DB));
        }
        catch (ClassCastException ignored) {
            // Treat values written with the wrong SharedPreferences type as corrupt.
        }
        config.audioBoostDb = normalizeAudioBoostDb(storedAudioBoostDb);

        config.videoFormat = getVideoFormatValue(prefs);
        String cinemaEnvironment = null;
        try {
            cinemaEnvironment = prefs.getString(CINEMA_ENVIRONMENT_PREF_STRING,
                    CinemaEnvironment.BLACK.preferenceValue);
        } catch (ClassCastException invalidStoredType) {
            // A corrupt persisted type must not prevent the stream from starting.
        }
        config.cinemaEnvironment = CinemaEnvironment.fromPreferenceValue(cinemaEnvironment);
        config.framePacing = getFramePacingValue(prefs);

        String warpFactorStr = prefs.getString(FRAME_PACING_PREF_STRING, "");
        if (warpFactorStr.equals("warp")) {
            config.framePacingWarpFactor = 2;
        } else if (warpFactorStr.equals("warp2")) {
            config.framePacingWarpFactor = 4;
        }

        config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE);

        config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE);

        // Checkbox preferences
        config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO);

        // Touch starts in multi-touch mode; the in-stream mouse-mode menu changes these flags.
        config.enableMultiTouchScreen = true;
        config.touchscreenTrackpad = false;
        config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR) && !isShieldAtvFirmwareWithBrokenHdr();
        config.streamGamma = com.limelight.nvstream.StreamGamma.fromPreference(
                prefs.getString(STREAM_GAMMA_PREF_STRING, "default"));
        config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY);
        config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION);
        config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS);


        config.enableMouseLocalCursor=prefs.getBoolean("checkbox_mouse_local_cursor",false);

        config.absoluteMouseMode = prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE);
        config.virtualDisplayOnly = prefs.getBoolean(
                VIRTUAL_DISPLAY_ONLY_PREF_STRING, DEFAULT_VIRTUAL_DISPLAY_ONLY);
        config.smartClipboardSync = prefs.getBoolean(CHECKBOX_SMART_CLIPBOARD_SYNC, DEFAULT_SMART_CLIPBOARD_SYNC);
        config.hideClipboardContent = prefs.getBoolean(CHECKBOX_HIDE_CLIPBOARD_CONTENT, DEFAULT_HIDE_CLIPBOARD_CONTENT);
        config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE);
        config.gamepadTouchpadAsMouse = prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE);
        config.gamepadMotionSensors = prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS);
        config.enableRumble = prefs.getBoolean(ENABLE_RUMBLE_PREF_STRING, DEFAULT_ENABLE_RUMBLE);

        return config;
    }
}
