package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One-time bridge from the retired profile overlay to Artemis' single global preference layer.
 *
 * <p>The legacy file is deliberately left in place after migration. It is no longer read by the
 * application, but keeping it makes the migration recoverable if a malformed value is discovered
 * later. Only the active profile is flattened because that is the configuration the user was
 * actually running before profiles were removed.</p>
 */
public final class LegacyProfileMigration {
    static final String MIGRATION_COMPLETE_KEY = "legacy_profiles_flattened_v1";
    static final String RETIRED_SETTINGS_CLEANED_KEY = "xr_retired_settings_cleaned_v1";
    static final String FULL_RANGE_RECOVERY_COMPLETE_KEY = "xr_full_range_recovered_v2";
    static final String DEBUG_LOGGING_DEFAULT_COMPLETE_KEY =
            "debug_perf_logging_default_applied_v1";
    private static final String PROFILES_DIR = "profiles";
    private static final String PROFILES_FILE = "profiles.json";

    /** Only settings still visible in the XR Global Defaults UI may leave a legacy profile. */
    private static final Set<String> RETAINED_XR_KEYS = new HashSet<>(Arrays.asList(
            "list_resolution", "list_fps", "seekbar_bitrate_kbps",
            "checkbox_enable_hdr", "checkbox_full_range", "video_format", "frame_pacing",
            "list_client_sbs_depth_model", "list_raw_sbs_per_eye_resolution",
            "list_audio_config", "checkbox_host_audio",
            "seekbar_deadzone", "checkbox_enable_rumble", "checkbox_flip_face_buttons",
            "checkbox_gamepad_touchpad_as_mouse", "checkbox_mouse_emulation",
            "checkbox_absolute_mouse_mode", "checkbox_mouse_local_cursor",
            "checkbox_smart_clipboard_sync", "checkbox_hide_clipboard_content",
            "list_languages", "checkbox_enable_perf_logging"));

    /**
     * Former phone/tablet controls that must not keep affecting an update-installed XR build.
     * Removing a key restores the explicit safe default in PreferenceConfiguration.
     */
    private static final String[] RETIRED_XR_KEYS = {
            "analog_scrolling",
            "checkbox_auto_invert_video_resolution",
            "checkbox_auto_orientation",
            "checkbox_back_as_guide",
            "checkbox_back_as_meta",
            "checkbox_disable_warnings",
            "checkbox_enable_analog_stick_new",
            "checkbox_enable_audiofx",
            "checkbox_enable_clear_default_special_button",
            "checkbox_enable_commit_text",
            "checkbox_enable_device_rumble",
            "checkbox_enable_floating_button",
            "checkbox_enable_fullexdisplay",
            "checkbox_enable_global_touch_sensitivity",
            "checkbox_enable_joyconfix",
            "checkbox_enable_keyboard",
            "checkbox_enable_keyboard_square",
            "checkbox_enable_perf_overlay",
            "checkbox_enable_perf_overlay_bottom",
            "checkbox_enable_perf_overlay_lite",
            "checkbox_enable_perf_overlay_lite_dialog",
            "checkbox_enable_pip",
            "checkbox_enable_post_stream_toast",
            "checkbox_enable_quit_dialog",
            "checkbox_enable_sops",
            "checkbox_enable_sticky_modifier_key_virtual_keyboard",
            "checkbox_enable_touch_sensitivity",
            "checkbox_enable_touch_sensitivity_rotation_auto",
            "checkbox_enable_view_top_center",
            "checkbox_enforce_display_mode",
            "checkbox_force_device_motion",
            "checkbox_force_qwerty",
            "checkbox_full_screen",
            "checkbox_gamepad_enable_battery_report",
            "checkbox_gamepad_motion_fallback",
            "checkbox_gamepad_motion_sensors",
            "checkbox_hide_osc_when_has_gamepad",
            "checkbox_ignore_synth_events",
            "checkbox_mouse_nav_buttons",
            "checkbox_multi_controller",
            "checkbox_multi_touch_gestures",
            "checkbox_only_show_L3R3",
            "checkbox_onscreen_style_official",
            "checkbox_prevent_packet_loss",
            "checkbox_reduce_refresh_rate",
            "checkbox_remember_mouse_mode",
            "checkbox_remember_zoom_pan",
            "checkbox_resume_without_confirm",
            "checkbox_show_guide_button",
            "checkbox_show_onscreen_controls",
            "checkbox_show_overlay_zoom_toggle_button",
            "checkbox_small_icon_mode",
            "checkbox_smart_clipboard_sync_toast",
            "checkbox_trackpad_drag_drop_vibration",
            "checkbox_trackpad_swap_axis",
            "checkbox_ultra_low_latency",
            "checkbox_unlock_fps",
            "checkbox_usb_bind_all",
            "checkbox_usb_driver",
            "checkbox_use_virtual_display",
            "checkbox_vibrate_fallback",
            "checkbox_vibrate_keyboard",
            "checkbox_vibrate_osc",
            "custom_refresh_rate",
            "edit_diy_bitrate",
            "edit_diy_w_h",
            "keyboard_axi_list",
            "list_onscreen_keyboard_align_mode",
            "list_video_scale_mode",
            "mouse_mode_list",
            "onscreen_keyboard_autofit",
            "seekbar_keyboard_axi_opacity",
            "seekbar_metered_bitrate_kbps",
            "seekbar_onscreen_keyboard_height",
            "seekbar_onscreen_keyboard_width",
            "seekbar_osc_free_analog_stick_opacity",
            "seekbar_osc_opacity",
            "seekbar_resolution_scale_factor",
            "seekbar_touch_sensitivity_opacity_x",
            "seekbar_touch_sensitivity_opacity_y",
            "seekbar_touchpad_sensitivity_opacity",
            "seekbar_touchpad_sensitivity_y_opacity",
            "seekbar_trackpad_drag_drop_threshold",
            "seekbar_trackpad_sensitivity_x",
            "seekbar_trackpad_sensitivity_y",
            "seekbar_vibrate_fallback_strength",
            // Older upstream migrations that predate the final split controls.
            "seekbar_bitrate",
            "list_resolution_fps",
            "checkbox_51_surround",
            "checkbox_stretch_video",
            "checkbox_enforce_refresh_rate",
            "checkbox_disable_frame_drop"
    };

    private LegacyProfileMigration() {
    }

    public static void migrateActiveProfile(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        cleanRetiredSettings(preferences);
        recoverFullRangeFromLegacyProfile(context, preferences);
        if (preferences.getBoolean(MIGRATION_COMPLETE_KEY, false)) {
            return;
        }

        File profileFile = new File(new File(context.getFilesDir(), PROFILES_DIR), PROFILES_FILE);
        if (!profileFile.isFile()) {
            preferences.edit().putBoolean(MIGRATION_COMPLETE_KEY, true).apply();
            return;
        }

        try (Reader reader = new FileReader(profileFile)) {
            JsonObject activeOptions = findActiveProfileOptions(JsonParser.parseReader(reader));

            if (activeOptions != null) {
                SharedPreferences.Editor editor = preferences.edit();
                Map<String, ?> existingValues = preferences.getAll();
                for (Map.Entry<String, JsonElement> entry : activeOptions.entrySet()) {
                    if (RETAINED_XR_KEYS.contains(entry.getKey())) {
                        putValue(editor, entry.getKey(), entry.getValue(),
                                existingValues.get(entry.getKey()));
                    }
                }
                editor.putBoolean(MIGRATION_COMPLETE_KEY, true).apply();
                LimeLog.info("Migrated the active legacy settings profile into XR defaults");
            } else {
                markComplete(preferences);
            }
        } catch (Exception e) {
            // A corrupt legacy profile must never prevent the XR-only app from starting. Mark the
            // attempt complete and retain the source file so it remains recoverable for diagnostics.
            LimeLog.warning("Unable to migrate legacy settings profile: " + e);
            markComplete(preferences);
        }
    }

    /** Applies the new debug-only default once, including to update-installed legacy profiles. */
    public static void applyDebugBuildDefaults(Context context) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getBoolean(DEBUG_LOGGING_DEFAULT_COMPLETE_KEY, false)) {
            return;
        }
        preferences.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_PERF_LOGGING_PREF_STRING, true)
                .putBoolean(DEBUG_LOGGING_DEFAULT_COMPLETE_KEY, true)
                .apply();
    }

    private static void markComplete(SharedPreferences preferences) {
        preferences.edit().putBoolean(MIGRATION_COMPLETE_KEY, true).apply();
    }

    /**
     * The first XR settings cleanup accidentally classified Full video range as phone-only. Since
     * the legacy profile file is intentionally retained, recover only that key for update-installed
     * users without replaying the whole profile over newer global choices.
     */
    private static void recoverFullRangeFromLegacyProfile(Context context,
                                                           SharedPreferences preferences) {
        if (preferences.getBoolean(FULL_RANGE_RECOVERY_COMPLETE_KEY, false)) {
            return;
        }
        if (preferences.contains(PreferenceConfiguration.FULL_RANGE_PREF_STRING)) {
            preferences.edit().putBoolean(FULL_RANGE_RECOVERY_COMPLETE_KEY, true).apply();
            return;
        }

        SharedPreferences.Editor editor = preferences.edit();
        File profileFile = new File(new File(context.getFilesDir(), PROFILES_DIR), PROFILES_FILE);
        if (profileFile.isFile()) {
            try (Reader reader = new FileReader(profileFile)) {
                JsonObject activeOptions = findActiveProfileOptions(JsonParser.parseReader(reader));
                JsonElement value = activeOptions != null
                        ? activeOptions.get(PreferenceConfiguration.FULL_RANGE_PREF_STRING) : null;
                if (value != null && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isBoolean()) {
                    editor.putBoolean(PreferenceConfiguration.FULL_RANGE_PREF_STRING,
                            value.getAsBoolean());
                    LimeLog.info("Recovered Full video range from the retired active profile");
                }
            } catch (Exception e) {
                LimeLog.warning("Unable to recover Full video range from legacy profile: " + e);
            }
        }
        editor.putBoolean(FULL_RANGE_RECOVERY_COMPLETE_KEY, true).apply();
    }

    private static JsonObject findActiveProfileOptions(JsonElement rootElement) {
        if (rootElement == null || !rootElement.isJsonObject()) {
            return null;
        }
        JsonObject root = rootElement.getAsJsonObject();
        JsonElement activeIdElement = root.get("activeProfileId");
        JsonArray profiles = root.has("profiles") && root.get("profiles").isJsonArray()
                ? root.getAsJsonArray("profiles") : null;
        if (activeIdElement == null || activeIdElement.isJsonNull() || profiles == null) {
            return null;
        }

        String activeId = activeIdElement.getAsString();
        for (JsonElement profileElement : profiles) {
            if (!profileElement.isJsonObject()) {
                continue;
            }
            JsonObject profile = profileElement.getAsJsonObject();
            if (profile.has("uuid") && activeId.equals(profile.get("uuid").getAsString())
                    && profile.has("options") && profile.get("options").isJsonObject()) {
                return profile.getAsJsonObject("options");
            }
        }
        return null;
    }

    private static void cleanRetiredSettings(SharedPreferences preferences) {
        if (preferences.getBoolean(RETIRED_SETTINGS_CLEANED_KEY, false)) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : RETIRED_XR_KEYS) {
            editor.remove(key);
        }
        editor.putBoolean(RETIRED_SETTINGS_CLEANED_KEY, true).apply();
    }

    private static void putValue(SharedPreferences.Editor editor, String key, JsonElement value,
                                 Object existingValue) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isBoolean()) {
                editor.putBoolean(key, value.getAsBoolean());
            } else if (value.getAsJsonPrimitive().isString()) {
                editor.putString(key, value.getAsString());
            } else if (value.getAsJsonPrimitive().isNumber()) {
                if (existingValue instanceof Long) {
                    editor.putLong(key, value.getAsLong());
                } else if (existingValue instanceof Float) {
                    editor.putFloat(key, value.getAsFloat());
                } else {
                    // Preference seek bars and the legacy profile implementation use integer
                    // values. Unknown integral values are therefore safest as ints.
                    editor.putInt(key, value.getAsInt());
                }
            }
            return;
        }
        if (value.isJsonArray()) {
            Set<String> strings = new HashSet<>();
            for (JsonElement item : value.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    return;
                }
                strings.add(item.getAsString());
            }
            editor.putStringSet(key, strings);
        }
    }
}
