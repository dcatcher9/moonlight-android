package com.limelight.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.limelight.Game;

/**
 * Small SceneCore-independent store for XR panel view state. Physical height is durable per PC.
 * Presentation mode is supplied separately by the session settings owner.
 */
final class XrViewStateStore {
    static final String PREFS_NAME = "xr_stream_view_state";
    static final String HEIGHT_SUFFIX = ".panel_height";
    static final float DEFAULT_HEIGHT_METERS = 2.0f;
    static final float MIN_HEIGHT_METERS = 0.5f;
    static final float MAX_HEIGHT_METERS = 6.0f;

    private final SharedPreferences preferences;
    private final String key;

    XrViewStateStore(Context context, Intent intent) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        key = buildKey(intent);
    }

    float restoreHeight() {
        float height = DEFAULT_HEIGHT_METERS;
        try {
            height = preferences.getFloat(key + HEIGHT_SUFFIX, DEFAULT_HEIGHT_METERS);
        } catch (ClassCastException ignored) {
            // Treat corrupt preferences as absent.
        }
        return clampHeight(height);
    }

    void saveHeight(float panelHeightMeters) {
        preferences.edit()
                .putFloat(key + HEIGHT_SUFFIX, clampHeight(panelHeightMeters))
                .apply();
    }

    static String buildKey(Intent intent) {
        String machine = intent != null ? intent.getStringExtra(Game.EXTRA_PC_UUID) : null;
        if (machine == null || machine.isEmpty()) {
            machine = intent != null ? intent.getStringExtra(Game.EXTRA_HOST) : null;
        }
        return "view." + String.valueOf(machine);
    }

    static float clampHeight(float height) {
        if (!Float.isFinite(height)) {
            return DEFAULT_HEIGHT_METERS;
        }
        return Math.max(MIN_HEIGHT_METERS, Math.min(MAX_HEIGHT_METERS, height));
    }
}
