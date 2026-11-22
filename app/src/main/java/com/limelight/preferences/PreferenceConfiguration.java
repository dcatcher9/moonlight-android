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

    public enum ScaleMode {
        FIT,
        FILL,
        STRETCH
    }

    public enum FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264,
    };

    public enum AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT
    }

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
    private static final String USE_VIRTUAL_DISPLAY_PREF_STRING = "checkbox_use_virtual_display";
    private static final String VIDEO_SCALE_MODE_PREF_STRING = "list_video_scale_mode";
    private static final String DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings";
    public static final String HOST_AUDIO_PREF_STRING = "checkbox_host_audio";
    private static final String DEADZONE_PREF_STRING = "seekbar_deadzone";
    private static final String OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity";
    private static final String LANGUAGE_PREF_STRING = "list_languages";
    private static final String MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller";
    public static final String AUDIO_CONFIG_PREF_STRING = "list_audio_config";
    public static final String AUDIO_BOOST_PREF_STRING = "list_audio_boost_db";
    private static final String USB_DRIVER_PREF_SRING = "checkbox_usb_driver";
    public static final String VIDEO_FORMAT_PREF_STRING = "video_format";
    /** Retired selector key retained only so the one-time migration can remove old values. */
    public static final String CLIENT_SBS_DEPTH_MODEL_PREF_STRING =
            "list_client_sbs_depth_model";
    public static final String CINEMA_ENVIRONMENT_PREF_STRING = "list_cinema_environment";
    private static final String ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls";
    private static final String CHECKBOX_HIDE_OSC_WHEN_HAS_GAMEPAD = "checkbox_hide_osc_when_has_gamepad";
    private static final String ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3";
    private static final String SHOW_GUIDE_BUTTON_PREF_STRING = "checkbox_show_guide_button";
    public static final String ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr";
    private static final String ENABLE_PIP_PREF_STRING = "checkbox_enable_pip";
    private static final String ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay";
    private static final String BIND_ALL_USB_STRING = "checkbox_usb_bind_all";
    private static final String MOUSE_EMULATION_STRING = "checkbox_mouse_emulation";
    private static final String REMEMBER_MOUSE_MODE_PREF_STRING = "checkbox_remember_mouse_mode";
    private static final String ANALOG_SCROLLING_PREF_STRING = "analog_scrolling";
    private static final String MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons";
    private static final String VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc";
    private static final String VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback";
    private static final String VIBRATE_FALLBACK_STRENGTH_PREF_STRING = "seekbar_vibrate_fallback_strength";
    private static final String FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons";
//    static final String TOUCHSCREEN_TRACKPAD_PREF_STRING = "checkbox_touchscreen_trackpad";
    private static final String LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast";
    public static final String FRAME_PACING_PREF_STRING = "frame_pacing";
    private static final String ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode";
    public static final String VIRTUAL_DISPLAY_ONLY_PREF_STRING =
            "checkbox_virtual_display_only";
    public static final String FULL_RANGE_PREF_STRING = "checkbox_full_range";
    public static final String STREAM_GAMMA_PREF_STRING = "stream_gamma";
    private static final String GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse";
    private static final String GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors";
    private static final String GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback";
    private static final String FORCE_MOTION_SENSORS_FALLBACK_PREF_STRING = "checkbox_force_device_motion";
    private static final String FULL_SCREEN_PREF_STRING = "checkbox_full_screen";

    private static final String ENABLE_RUMBLE_PREF_STRING = "checkbox_enable_rumble";

    private static final String LIST_ONSCREEN_KEYBOARD_ALIGN_MODE = "list_onscreen_keyboard_align_mode";

    private static final String CHECKBOX_ENABLE_BATTERY_REPORT = "checkbox_gamepad_enable_battery_report";
    private static final String CHECKBOX_FORCE_QWERTY = "checkbox_force_qwerty";
    private static final String CHECKBOX_BACK_AS_META = "checkbox_back_as_meta";
    private static final String CHECKBOX_IGNORE_SYNTH_EVENTS = "checkbox_ignore_synth_events";
    private static final String CHECKBOX_BACK_AS_GUIDE = "checkbox_back_as_guide";
    private static final String CHECKBOX_SMART_CLIPBOARD_SYNC = "checkbox_smart_clipboard_sync";
    private static final String CHECKBOX_SMART_CLIPBOARD_SYNC_TOAST = "checkbox_smart_clipboard_sync_toast";
    private static final String CHECKBOX_HIDE_CLIPBOARD_CONTENT = "checkbox_hide_clipboard_content";

    private static final String CHECKBOX_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD = "checkbox_enable_sticky_modifier_key_virtual_keyboard";

    private static final String CHECKBOX_ENABLE_QUIT_DIALOG = "checkbox_enable_quit_dialog";

    private static final String CHECKBOX_ENABLE_FLOATING_BUTTON = "checkbox_enable_floating_button";

    private static final String CHECKBOX_SHOW_OVERLAY_ZOOM_TOGGLE_BUTTON = "checkbox_show_overlay_zoom_toggle_button";

    //竖屏模式
    private static final String CHECKBOX_AUTO_ORIENTATION = "checkbox_auto_orientation";
    //屏幕特殊按键
    private static final String CHECKBOX_ENABLE_KEYBOARD = "checkbox_enable_keyboard";

    //屏幕特殊按键 震动
    private static final String CHECKBOX_ENABLE_KEYBOARD_VIBRATE = "checkbox_vibrate_keyboard";

    //自动摇杆
    private static final String CHECKBOX_CHECKBOX_ENABLE_ANALOG_STICK_NEW = "checkbox_enable_analog_stick_new";

    //触控屏幕灵敏度
    private static final String SEEKBAR_TOUCH_SENSITIVITY = "seekbar_touch_sensitivity_opacity_x";
    private static final String SEEKBAR_TRACKPAD_SENSITIVITY_X = "seekbar_trackpad_sensitivity_x";
    private static final String SEEKBAR_TRACKPAD_SENSITIVITY_Y = "seekbar_trackpad_sensitivity_y";
    private static final String CHECKBOX_TRACKPAD_DRAG_DROP_VIBRATION = "checkbox_trackpad_drag_drop_vibration";
    private static final String SEEKBAR_TRACKPAD_DRAG_DROP_THRESHOLD = "seekbar_trackpad_drag_drop_threshold";
    private static final String CHECKBOX_TRACKPAD_SWAP_AXIS = "checkbox_trackpad_swap_axis";

    private static final String CHECKBOX_ENABLE_COMMIT_TEXT = "checkbox_enable_commit_text";

    static final String DEFAULT_RESOLUTION = "3840x2160";
    static final String DEFAULT_FPS = "60";
    static final int DEFAULT_BITRATE_KBPS = 200000;
    private static final boolean DEFAULT_USE_VIRTUAL_DISPLAY = false;
    private static final String DEFAULT_VIDEO_SCALE_MODE = "fit";
    private static final boolean DEFAULT_DISABLE_TOASTS = false;
    static final boolean DEFAULT_HOST_AUDIO = false;
    private static final int DEFAULT_DEADZONE = 5;
    private static final int DEFAULT_OPACITY = 90;
    public static final String DEFAULT_LANGUAGE = "default";
    private static final boolean DEFAULT_MULTI_CONTROLLER = true;
    private static final boolean DEFAULT_USB_DRIVER = true;
    static final String DEFAULT_VIDEO_FORMAT = "forceh265";
    /** Historical model ids retained for offline evaluation; production always uses ZipDepth. */
    public static final String CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC =
            "depth-anything-v2-small-static-performance";
    public static final String CLIENT_SBS_DEPTH_MODEL_MIDAS_V2 = "midas-v2-float";
    public static final String CLIENT_SBS_DEPTH_MODEL_DEPTHART_S448_FP16 =
            "depthart-s448-fp16";
    public static final String CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16 =
            "zipdepth-base-fp16";
    public static final String DEFAULT_CLIENT_SBS_DEPTH_MODEL =
            CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16;

    /**
     * Compatibility boundary for retired Client SBS model preferences.
     *
     * <p>ZipDepth Base is the sole production model. Historical, corrupt, and future values all
     * resolve to it; the one-time startup migration removes the retired persisted selector.</p>
     */
    public static String normalizeClientSbsDepthModelId(String modelId) {
        return CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16;
    }

    public static int normalizeAudioBoostDb(String storedValue) {
        if ("0".equals(storedValue)) {
            return 0;
        }
        if ("6".equals(storedValue)) {
            return 6;
        }
        return DEFAULT_AUDIO_BOOST_DB;
    }

    private static final boolean DEFAULT_ONSCREEN_CONTROLLER = false;
    private static final boolean DEFAULT_HIDE_OSC_WHEN_HAS_GAMEPAD = true;
    private static final boolean ONLY_L3_R3_DEFAULT = false;
    private static final boolean SHOW_GUIDE_BUTTON_DEFAULT = true;
    static final boolean DEFAULT_ENABLE_HDR = true;
    private static final boolean DEFAULT_ENABLE_PIP = false;
    private static final boolean DEFAULT_ENABLE_PERF_OVERLAY = false;
    private static final boolean DEFAULT_PERF_OVERLAY_BOTTOM = false;
    private static final boolean DEFAULT_BIND_ALL_USB = false;
    private static final boolean DEFAULT_MOUSE_EMULATION = true;
    private static final boolean DEFAULT_REMEMBER_MOUSE_MODE = false;
    private static final String DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right";
    private static final boolean DEFAULT_MOUSE_NAV_BUTTONS = false;
    private static final boolean DEFAULT_VIBRATE_OSC = true;
    private static final boolean DEFAULT_VIBRATE_FALLBACK = false;
    private static final int DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100;
    private static final boolean DEFAULT_FLIP_FACE_BUTTONS = false;
    private static final boolean DEFAULT_TOUCHSCREEN_TRACKPAD = true;
    static final String DEFAULT_AUDIO_CONFIG = "2"; // Stereo
    static final int DEFAULT_AUDIO_BOOST_DB = 3;
    private static final boolean DEFAULT_LATENCY_TOAST = false;
    static final String DEFAULT_FRAME_PACING = "latency";
    private static final boolean DEFAULT_ABSOLUTE_MOUSE_MODE = false;
    public static final boolean DEFAULT_VIRTUAL_DISPLAY_ONLY = true;
    public static final boolean DEFAULT_FULL_RANGE = true;
    private static final boolean DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false;
    private static final boolean DEFAULT_GAMEPAD_MOTION_SENSORS = true;
    private static final boolean DEFAULT_GAMEPAD_MOTION_FALLBACK = false;
    private static final boolean DEFAULT_FORCE_MOTION_SENSORS_FALLBACK = false;
    private static final boolean DEFAULT_ENABLE_RUMBLE = true;
    private static final boolean DEFAULT_GAMEPAD_ENABLE_BATTERY_REPORT = true;
    private static final boolean DEFAULT_FORCE_QWERTY = true;
    private static final boolean DEFAULT_SEND_META_ON_PHYSICAL_BACK = false;
    private static final boolean DEFAULT_IGNORE_SYNTH_EVENTS = false;
    private static final boolean DEFAULT_ENABLE_FLOATING_BUTTON = false;
    private static final boolean DEFAULT_BACK_AS_GUIDE = false;
    private static final boolean DEFAULT_SMART_CLIPBOARD_SYNC = false;
    private static final boolean DEFAULT_SMART_CLIPBOARD_SYNC_TOAST = true;
    private static final boolean DEFAULT_HIDE_CLIPBOARD_CONTENT = true;
    private static final boolean DEFAULT_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD = true;
    private static final int DEFAULT_TRACKPAD_SENSITIVITY_X = 100;
    private static final int DEFAULT_TRACKPAD_SENSITIVITY_Y = 100;
    private static final boolean DEFAULT_TRACKPAD_DRAG_DROP_VIBRATION = false;
    private static final int DEFAULT_TRACKPAD_DRAG_DROP_THRESHOLD = 250;
    private static final boolean DEFAULT_TRACKPAD_SWAP_AXIS = false;
    private static final boolean DEFAULT_ENABLE_COMMIT_TEXT = false;
    private static final String DEFAULT_ONSCREEN_KEYBOARD_ALIGN_MODE = "center";
    private static final boolean DEFAULT_SHOW_OVERLAY_TOGGLE_BUTTON = false;

    private static final boolean DEFAULT_REMEMBER_ZOOM_PAN = false;
    private static final float DEFAULT_ZOOM_SCALE = 1.0f;
    private static final float DEFAULT_PAN_OFFSET = 0.0f;
    private static final boolean DEFAULT_FULL_SCREEN = true;

    public static final int FRAME_PACING_MIN_LATENCY = 0;
    public static final int FRAME_PACING_BALANCED = 1;
    public static final int FRAME_PACING_CAP_FPS = 2;
    public static final int FRAME_PACING_MAX_SMOOTHNESS = 3;

    // The render-mode preference is deprecated: streaming always uses the single XR route (starts
    // in Normal/2D, modes switched from the in-headset bar). The per-variant render modes are gone.

    // Conservative per-axis Host SBS encode limits. The host doubles the logical width to 2W,
    // then fits that packed raster inside both codec axes with one aspect-preserving scale.
    // Runtime capability discovery and the applied-state ACK remain authoritative when lower.
    public static final int MAX_HOST_SBS_PACKED_WIDTH_H264 = 4096;
    public static final int MAX_HOST_SBS_PACKED_WIDTH_HEVC_AV1 = 8192;

    public static int maxHostSbsPackedWidthForVideoFormat(int videoFormat) {
        return (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0
                ? MAX_HOST_SBS_PACKED_WIDTH_H264
                : MAX_HOST_SBS_PACKED_WIDTH_HEVC_AV1;
    }

    public static int[] hostSbsPackedDimensions(int eyeWidth, int eyeHeight,
                                                 int videoFormat) {
        if (eyeWidth <= 0 || eyeHeight <= 0) {
            return new int[] {0, 0};
        }

        int axisCap = maxHostSbsPackedWidthForVideoFormat(videoFormat) & ~1;
        long requestedPackedWidth = (long) eyeWidth * 2L;
        if (requestedPackedWidth <= axisCap && eyeHeight <= axisCap) {
            return new int[] {
                    Math.max(2, (int) requestedPackedWidth & ~1),
                    Math.max(2, eyeHeight & ~1)
            };
        }

        double scale = Math.min(
                axisCap / (double) requestedPackedWidth,
                axisCap / (double) eyeHeight);
        return new int[] {
                Math.max(2, ((int) Math.round(requestedPackedWidth * scale)) & ~1),
                Math.max(2, ((int) Math.round(eyeHeight * scale)) & ~1)
        };
    }

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
    public int oscOpacity;
    public int oscKeyboardOpacity;
    public int onscreenKeyboardHeight;
    public boolean onscreenKeyboardAutoFitDisabled;
    public int onscreenKeyboardWidth;
    public String onscreenKeyboardAlignMode;
    public boolean useVirtualDisplay, playHostAudio, disableWarnings, fullScreen;
    public ScaleMode videoScaleMode;
    public String language;
    public boolean multiController, usbDriver, flipFaceButtons;
    public boolean onscreenController;
    public boolean hideOSCWhenHasGamepad;
    public boolean enableBatteryReport;
    public boolean forceQwerty;
    public boolean backAsMeta;
    public boolean ignoreSynthEvents;
    public boolean backAsGuide;
    public boolean smartClipboardSync;
    public boolean smartClipboardSyncToast;
    public boolean hideClipboardContent;
    public boolean stickyModifierKey;
    public boolean onlyL3R3;
    public boolean showGuideButton;
    public boolean enableHdr;
    public com.limelight.nvstream.StreamGamma streamGamma;
    public boolean enablePip;

    public boolean enablePerfOverlay;
    //简化版性能信息
    public boolean enablePerfOverlayLite;

    public boolean enablePerfOverlayLiteDialog;

    public boolean enablePerfOverlayBottom;

    public boolean enableLatencyToast;
    public boolean enableBackMenu;
    public boolean enableFloatingButton;
    public boolean showOverlayZoomToggleButton;

    //竖屏模式
    public boolean autoOrientation;
    //虚拟屏幕键盘按键
    public boolean enableKeyboard;
    //修复JoyCon十字键
    public boolean enableJoyConFix;

    //自由摇杆啊
    public boolean enableNewAnalogStick;

    public boolean enableFullExDisplay;

    //串流画面顶部居中显示
    public boolean alignDisplayTopCenter;

    //触控屏幕灵敏度
    public int touchSensitivityX;
    public int touchSensitivityY;
    //超出边界自动回中心点
    public boolean touchSensitivityRotationAuto;

    //触控灵敏度调节范围
    public boolean touchSensitivityGlobal;

    //多点触控灵敏度调节
    public boolean enableTouchSensitivity;

    //触控板模式灵敏度
    public int touchPadSensitivity;

    public int touchPadYSensitity;

    //多点触控模式
    public boolean enableMultiTouchScreen;

    //物理光标捕获
    public boolean enableMouseLocalCursor;

    public boolean enableMultiTouchGestures;

    //禁用内置的特殊指令
    public boolean disableDefaultExtraKeys;

    //强制使用设备自身的震动马达
    public boolean enableDeviceRumble;

    // Enable forwarding of commitText from soft keyboard
    public boolean enableCommitText;

    public boolean enableKeyboardVibrate;

    public boolean enableKeyboardSquare;

    //官方虚拟按钮风格
    public boolean enableOnScreenStyleOfficial;

    //自由摇杆背景透明度
    public int enableNewAnalogStickOpacity;

    public int trackpadSensitivityX;
    public int trackpadSensitivityY;
    public boolean trackpadDragDropVibration;
    public int trackpadDragDropThreshold;
    public boolean trackpadSwapAxis;

    public boolean bindAllUsb;
    public boolean mouseEmulation;
    public AnalogStickForScrolling analogStickForScrolling;
    public boolean mouseNavButtons;
    public boolean rememberMouseMode;

    public boolean vibrateOsc;
    public boolean vibrateFallbackToDevice;
    public int vibrateFallbackToDeviceStrength;
    public boolean touchscreenTrackpad;
    public MoonBridge.AudioConfiguration audioConfiguration;
    public int audioBoostDb;
    public int framePacing;
    public boolean absoluteMouseMode;
    public boolean virtualDisplayOnly;
    public boolean fullRange;
    public boolean gamepadMotionSensors;
    public boolean gamepadTouchpadAsMouse;
    public boolean gamepadMotionSensorsFallbackToDevice;
    public boolean forceMotionSensorsFallbackToDevice;
    public boolean enableRumble;

    public boolean rememberZoomPan;
    public float zoomScale;
    public float panOffsetX;
    public float panOffsetY;

    private static final String CHECKBOX_REMEMBER_ZOOM_PAN = "checkbox_remember_zoom_pan";
    private static final String NUMBER_ZOOM_SCALE = "number_zoom_scale";

    private static final String NUMBER_PAN_OFFSET_X = "number_pan_offset_x";
    private static final String NUMBER_PAN_OFFSET_Y = "number_pan_offset_y";

    /**
     * The XR route is always stereoscopic: it maintains the configured resolution, forces STRETCH
     * scaling, and skips portrait resolution inversion. (Kept as a method so existing call sites
     * in Game.java stay unchanged.)
     */
    public boolean isStereoMode() {
        return true;
    }

    /**
     * The XR route can switch to Host SBS AI at any time via the bar, so the
     * host may double the encoded width to a packed 2W frame; the decoder always pre-sizes for it.
     */
    public boolean isHostDoubledWidthMode() {
        return true;
    }

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

    public static int getDefaultBitrate(String resString, String fpsString) {
        return DEFAULT_BITRATE_KBPS;
    }

    public static int getDefaultBitrate(Context context) {
        return DEFAULT_BITRATE_KBPS;
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

    private static ScaleMode getVideoScaleMode(SharedPreferences prefs) {
        String str = prefs.getString(VIDEO_SCALE_MODE_PREF_STRING, DEFAULT_VIDEO_SCALE_MODE);
        if (str.equals("fit")) {
            return ScaleMode.FIT;
        }
        else if (str.equals("fill")) {
            return ScaleMode.FILL;
        }
        else if (str.equals("stretch")) {
            return ScaleMode.STRETCH;
        }
        else {
            // Should never get here
            return ScaleMode.FIT;
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

    private static AnalogStickForScrolling getAnalogStickForScrollingValue(SharedPreferences prefs) {
        String str = prefs.getString(ANALOG_SCROLLING_PREF_STRING, DEFAULT_ANALOG_STICK_FOR_SCROLLING);
        if (str.equals("right")) {
            return AnalogStickForScrolling.RIGHT;
        }
        else if (str.equals("left")) {
            return AnalogStickForScrolling.LEFT;
        }
        else {
            return AnalogStickForScrolling.NONE;
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

        config.videoScaleMode = getVideoScaleMode(prefs);

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

        config.analogStickForScrolling = getAnalogStickForScrollingValue(prefs);

        config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE);

        config.oscOpacity = prefs.getInt(OSC_OPACITY_PREF_STRING, DEFAULT_OPACITY);

        config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE);

        // Checkbox preferences
        config.disableWarnings = prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS);
        config.useVirtualDisplay = prefs.getBoolean(USE_VIRTUAL_DISPLAY_PREF_STRING, DEFAULT_USE_VIRTUAL_DISPLAY);
        config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO);
        config.multiController = prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER);
        config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER);
        config.fullScreen = prefs.getBoolean(FULL_SCREEN_PREF_STRING, DEFAULT_FULL_SCREEN);

        // Read mouse mode and set touch settings accordingly
        String mouseMode = prefs.getString("mouse_mode_list", "0");
        int mouseModeInt = Integer.parseInt(mouseMode);
        switch (mouseModeInt) {
            case 0: // Multi-touch
                config.enableMultiTouchScreen = true;
                config.touchscreenTrackpad = false;
                break;
            case 1: // Normal mouse
            case 5: // Normal mouse with swapped buttons
                config.enableMultiTouchScreen = false;
                config.touchscreenTrackpad = false;
                break;
            case 2: // Trackpad (natural)
            case 3: // Trackpad (gaming)
                config.enableMultiTouchScreen = false;
                config.touchscreenTrackpad = true;
                break;
            case 4: // Touch mouse disabled
                config.enableMultiTouchScreen = false;
                config.touchscreenTrackpad = false;
                break;
        }
        config.onscreenController = prefs.getBoolean(ONSCREEN_CONTROLLER_PREF_STRING, DEFAULT_ONSCREEN_CONTROLLER);
        config.hideOSCWhenHasGamepad = prefs.getBoolean(CHECKBOX_HIDE_OSC_WHEN_HAS_GAMEPAD, DEFAULT_HIDE_OSC_WHEN_HAS_GAMEPAD);
        config.onlyL3R3 = prefs.getBoolean(ONLY_L3_R3_PREF_STRING, ONLY_L3_R3_DEFAULT);
        config.showGuideButton = prefs.getBoolean(SHOW_GUIDE_BUTTON_PREF_STRING, SHOW_GUIDE_BUTTON_DEFAULT);
        config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR) && !isShieldAtvFirmwareWithBrokenHdr();
        config.streamGamma = com.limelight.nvstream.StreamGamma.fromPreference(
                prefs.getString(STREAM_GAMMA_PREF_STRING, "default"));
        config.enablePip = prefs.getBoolean(ENABLE_PIP_PREF_STRING, DEFAULT_ENABLE_PIP);
        config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY);
        config.enablePerfOverlayLite = prefs.getBoolean("checkbox_enable_perf_overlay_lite",DEFAULT_ENABLE_PERF_OVERLAY);
        config.enablePerfOverlayBottom = prefs.getBoolean("checkbox_enable_perf_overlay_bottom",DEFAULT_PERF_OVERLAY_BOTTOM);
        config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB);
        config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION);
        config.mouseNavButtons = prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS);
        config.rememberMouseMode = prefs.getBoolean(REMEMBER_MOUSE_MODE_PREF_STRING, DEFAULT_REMEMBER_MOUSE_MODE);
        config.vibrateOsc = prefs.getBoolean(VIBRATE_OSC_PREF_STRING, DEFAULT_VIBRATE_OSC);
        config.vibrateFallbackToDevice = prefs.getBoolean(VIBRATE_FALLBACK_PREF_STRING, DEFAULT_VIBRATE_FALLBACK);
        config.vibrateFallbackToDeviceStrength = prefs.getInt(VIBRATE_FALLBACK_STRENGTH_PREF_STRING, DEFAULT_VIBRATE_FALLBACK_STRENGTH);
        config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS);
//        config.touchscreenTrackpad = prefs.getBoolean(TOUCHSCREEN_TRACKPAD_PREF_STRING, DEFAULT_TOUCHSCREEN_TRACKPAD);
        config.enableLatencyToast = prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST);
        config.enableBackMenu = prefs.getBoolean(CHECKBOX_ENABLE_QUIT_DIALOG,true);
        config.enableFloatingButton = prefs.getBoolean(CHECKBOX_ENABLE_FLOATING_BUTTON,DEFAULT_ENABLE_FLOATING_BUTTON);
        config.showOverlayZoomToggleButton = prefs.getBoolean(CHECKBOX_SHOW_OVERLAY_ZOOM_TOGGLE_BUTTON, DEFAULT_SHOW_OVERLAY_TOGGLE_BUTTON);
        config.autoOrientation = prefs.getBoolean(CHECKBOX_AUTO_ORIENTATION,false);


        config.enableKeyboard = prefs.getBoolean(CHECKBOX_ENABLE_KEYBOARD,false);

        config.enableKeyboardVibrate = prefs.getBoolean(CHECKBOX_ENABLE_KEYBOARD_VIBRATE,false);
        //兼容joycon手柄
        config.enableJoyConFix = prefs.getBoolean("checkbox_joycon_fix",false);
        //全键盘透明度
        config.oscKeyboardOpacity = prefs.getInt("seekbar_keyboard_axi_opacity",DEFAULT_OPACITY);

        config.enableOnScreenStyleOfficial = prefs.getBoolean("checkbox_onscreen_style_official",false);

        config.enableNewAnalogStickOpacity = prefs.getInt("seekbar_osc_free_analog_stick_opacity",20);

        config.onscreenKeyboardHeight = prefs.getInt("seekbar_onscreen_keyboard_height",200);
        config.onscreenKeyboardAutoFitDisabled = prefs.getBoolean("onscreen_keyboard_autofit",false);
        config.onscreenKeyboardWidth = prefs.getInt("seekbar_onscreen_keyboard_width",1000);
        config.onscreenKeyboardAlignMode = prefs.getString(LIST_ONSCREEN_KEYBOARD_ALIGN_MODE, DEFAULT_ONSCREEN_KEYBOARD_ALIGN_MODE);

        config.enableNewAnalogStick=prefs.getBoolean(CHECKBOX_CHECKBOX_ENABLE_ANALOG_STICK_NEW,false);

        config.enableFullExDisplay=prefs.getBoolean("checkbox_enable_fullexdisplay",false);

        config.alignDisplayTopCenter =prefs.getBoolean("checkbox_enable_view_top_center",false);

        config.touchSensitivityX =prefs.getInt(SEEKBAR_TOUCH_SENSITIVITY,100);

        config.touchSensitivityY=prefs.getInt("seekbar_touch_sensitivity_opacity_y",100);

        config.touchSensitivityRotationAuto=prefs.getBoolean("checkbox_enable_touch_sensitivity_rotation_auto",true);

        config.touchSensitivityGlobal=prefs.getBoolean("checkbox_enable_global_touch_sensitivity",false);

        config.enableTouchSensitivity=prefs.getBoolean("checkbox_enable_touch_sensitivity",false);

        config.enableMouseLocalCursor=prefs.getBoolean("checkbox_mouse_local_cursor",false);

        config.enableMultiTouchGestures = prefs.getBoolean("checkbox_multi_touch_gestures", false);


        config.enablePerfOverlayLiteDialog=prefs.getBoolean("checkbox_enable_perf_overlay_lite_dialog",false);

        config.disableDefaultExtraKeys =prefs.getBoolean("checkbox_enable_clear_default_special_button", false);

        config.enableDeviceRumble=prefs.getBoolean("checkbox_enable_device_rumble", false);

        config.enableCommitText = prefs.getBoolean(CHECKBOX_ENABLE_COMMIT_TEXT, DEFAULT_ENABLE_COMMIT_TEXT);

        config.enableKeyboardSquare=prefs.getBoolean("checkbox_enable_keyboard_square",false);

        config.touchPadSensitivity=prefs.getInt("seekbar_touchpad_sensitivity_opacity",100);

        config.touchPadYSensitity=prefs.getInt("seekbar_touchpad_sensitivity_y_opacity",100);

        config.trackpadSensitivityX = prefs.getInt(SEEKBAR_TRACKPAD_SENSITIVITY_X, DEFAULT_TRACKPAD_SENSITIVITY_X);
        config.trackpadSensitivityY = prefs.getInt(SEEKBAR_TRACKPAD_SENSITIVITY_Y, DEFAULT_TRACKPAD_SENSITIVITY_Y);
        config.trackpadDragDropVibration = prefs.getBoolean(CHECKBOX_TRACKPAD_DRAG_DROP_VIBRATION, DEFAULT_TRACKPAD_DRAG_DROP_VIBRATION);
        config.trackpadDragDropThreshold = prefs.getInt(SEEKBAR_TRACKPAD_DRAG_DROP_THRESHOLD, DEFAULT_TRACKPAD_DRAG_DROP_THRESHOLD);
        config.trackpadSwapAxis = prefs.getBoolean(CHECKBOX_TRACKPAD_SWAP_AXIS, DEFAULT_TRACKPAD_SWAP_AXIS);

        config.absoluteMouseMode = prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE);
        config.virtualDisplayOnly = prefs.getBoolean(
                VIRTUAL_DISPLAY_ONLY_PREF_STRING, DEFAULT_VIRTUAL_DISPLAY_ONLY);
        config.enableBatteryReport = prefs.getBoolean(CHECKBOX_ENABLE_BATTERY_REPORT, DEFAULT_GAMEPAD_ENABLE_BATTERY_REPORT);
        config.forceQwerty = prefs.getBoolean(CHECKBOX_FORCE_QWERTY, DEFAULT_FORCE_QWERTY);
        config.backAsMeta = prefs.getBoolean(CHECKBOX_BACK_AS_META, DEFAULT_SEND_META_ON_PHYSICAL_BACK);
        config.ignoreSynthEvents = prefs.getBoolean(CHECKBOX_IGNORE_SYNTH_EVENTS, DEFAULT_IGNORE_SYNTH_EVENTS);
        config.backAsGuide = prefs.getBoolean(CHECKBOX_BACK_AS_GUIDE, DEFAULT_BACK_AS_GUIDE);
        config.smartClipboardSync = prefs.getBoolean(CHECKBOX_SMART_CLIPBOARD_SYNC, DEFAULT_SMART_CLIPBOARD_SYNC);
        config.smartClipboardSyncToast = prefs.getBoolean(CHECKBOX_SMART_CLIPBOARD_SYNC_TOAST, DEFAULT_SMART_CLIPBOARD_SYNC_TOAST);
        config.hideClipboardContent = prefs.getBoolean(CHECKBOX_HIDE_CLIPBOARD_CONTENT, DEFAULT_HIDE_CLIPBOARD_CONTENT);
        config.stickyModifierKey = prefs.getBoolean(CHECKBOX_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD, DEFAULT_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD);
        config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE);
        config.gamepadTouchpadAsMouse = prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE);
        config.gamepadMotionSensors = prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS);
        config.gamepadMotionSensorsFallbackToDevice = prefs.getBoolean(GAMEPAD_MOTION_FALLBACK_PREF_STRING, DEFAULT_GAMEPAD_MOTION_FALLBACK);
        config.forceMotionSensorsFallbackToDevice = prefs.getBoolean(FORCE_MOTION_SENSORS_FALLBACK_PREF_STRING, DEFAULT_FORCE_MOTION_SENSORS_FALLBACK);
        config.enableRumble = prefs.getBoolean(ENABLE_RUMBLE_PREF_STRING, DEFAULT_ENABLE_RUMBLE);

        config.rememberZoomPan = prefs.getBoolean(CHECKBOX_REMEMBER_ZOOM_PAN, DEFAULT_REMEMBER_ZOOM_PAN);
        config.zoomScale = prefs.getFloat(NUMBER_ZOOM_SCALE, DEFAULT_ZOOM_SCALE);
        config.panOffsetX = prefs.getFloat(NUMBER_PAN_OFFSET_X, DEFAULT_PAN_OFFSET);
        config.panOffsetY = prefs.getFloat(NUMBER_PAN_OFFSET_Y, DEFAULT_PAN_OFFSET);

        return config;
    }
}
