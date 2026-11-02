package com.limelight.ui;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;

/** Requests optional eye input once from Home, before a stream can be interrupted. */
public final class XrEyeTrackingPermission {
    public static final String PERMISSION = "android.permission.EYE_TRACKING_FINE";
    public static final String FEATURE = "android.hardware.xr.input.eye_tracking";
    private static final String PROMPT_HANDLED = "xr_eye_tracking_permission_prompt_handled";

    private final ComponentActivity activity;
    private final SharedPreferences preferences;
    private final ActivityResultLauncher<String> launcher;

    public XrEyeTrackingPermission(ComponentActivity activity) {
        this.activity = activity;
        preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        launcher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> { });
    }

    public void requestOnce() {
        if (activity.isFinishing() || activity.isDestroyed()
                || !activity.getPackageManager().hasSystemFeature(FEATURE)) {
            return;
        }
        boolean handled = preferences.getBoolean(PROMPT_HANDLED, false);
        if (ContextCompat.checkSelfPermission(activity, PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            // Also remember grants from earlier app versions or Android's app settings.
            if (!handled) {
                preferences.edit().putBoolean(PROMPT_HANDLED, true).apply();
            }
            return;
        }
        if (handled) {
            return;
        }
        // Record the attempt before launching: denial and Activity recreation must not cause
        // another automatic prompt. Permission can still be changed in Android app settings.
        preferences.edit().putBoolean(PROMPT_HANDLED, true).apply();
        try {
            launcher.launch(PERMISSION);
        } catch (RuntimeException e) {
            preferences.edit().remove(PROMPT_HANDLED).apply();
            LimeLog.warning("XR: Home eye-tracking permission request failed: "
                    + e.getClass().getSimpleName());
        }
    }
}
