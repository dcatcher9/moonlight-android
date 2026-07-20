package com.limelight.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.limelight.Game;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Small SceneCore-independent store for XR panel state. Physical height and the last successful
 * presentation mode are durable per-machine/app preferences, but the mode is returned only for a
 * host-confirmed resume of that same app. Whether a session is resumable is never inferred or
 * timed here; the launch intent carries the host's authoritative serverinfo decision.
 */
final class XrViewStateStore {
    static final String PREFS_NAME = "xr_stream_view_state";
    static final String HEIGHT_SUFFIX = ".panel_height";
    static final String MODE_SUFFIX = ".presenter_mode";
    static final float DEFAULT_HEIGHT_METERS = 2.0f;
    static final float MIN_HEIGHT_METERS = 0.5f;
    static final float MAX_HEIGHT_METERS = 6.0f;

    enum Mode {
        NORMAL,
        HOST_SBS_RAW,
        HOST_SBS_AI,
        CLIENT_SBS_AI
    }

    static final class State {
        final float panelHeightMeters;
        final Mode presentationMode;

        State(float panelHeightMeters, Mode presentationMode) {
            this.panelHeightMeters = panelHeightMeters;
            this.presentationMode = presentationMode;
        }
    }

    private final SharedPreferences preferences;
    private final String key;
    private final boolean restorePresentationMode;

    XrViewStateStore(Context context, Intent intent) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        key = buildKey(intent);
        restorePresentationMode = intent != null
                && intent.getBooleanExtra(Game.EXTRA_RESUME_EXISTING_SESSION, false);
    }

    State restore() {
        float height = DEFAULT_HEIGHT_METERS;
        try {
            height = preferences.getFloat(key + HEIGHT_SUFFIX, DEFAULT_HEIGHT_METERS);
        } catch (ClassCastException ignored) {
            // Treat corrupt or old-schema preferences as absent.
        }

        Mode mode = Mode.NORMAL;
        if (restorePresentationMode) {
            try {
                mode = Mode.valueOf(preferences.getString(
                        key + MODE_SUFFIX, Mode.NORMAL.name()));
            } catch (ClassCastException | IllegalArgumentException ignored) {
                mode = Mode.NORMAL;
            }
        }
        return new State(clampHeight(height), mode);
    }

    void saveHeight(float panelHeightMeters) {
        preferences.edit()
                .putFloat(key + HEIGHT_SUFFIX, clampHeight(panelHeightMeters))
                .apply();
    }

    void savePresentation(float panelHeightMeters, Mode mode) {
        preferences.edit()
                .putFloat(key + HEIGHT_SUFFIX, clampHeight(panelHeightMeters))
                .putString(key + MODE_SUFFIX, mode.name())
                .apply();
    }

    void resetPresentationToNormal(float panelHeightMeters) {
        savePresentation(panelHeightMeters, Mode.NORMAL);
    }

    static int desiredHostSbsWireMode(Mode mode) {
        // These are compile-time int constants, so this does not initialize MoonBridge/JNI.
        return mode == Mode.HOST_SBS_AI ? MoonBridge.SBS_MODE_AI : MoonBridge.SBS_MODE_OFF;
    }

    static String buildKey(Intent intent) {
        String machine = intent.getStringExtra(Game.EXTRA_PC_UUID);
        if (machine == null || machine.isEmpty()) {
            machine = intent.getStringExtra(Game.EXTRA_HOST);
        }
        String app = intent.getStringExtra(Game.EXTRA_APP_UUID);
        if (app == null || app.isEmpty()) {
            // Legacy shortcut paths wrote AppId as a String while current paths use int. Reading
            // through Bundle avoids getIntExtra() throwing ClassCastException for the legacy form.
            Bundle extras = intent.getExtras();
            Object appId = extras != null ? extras.get(Game.EXTRA_APP_ID) : null;
            app = appId != null ? String.valueOf(appId) : "-1";
        }
        return "view." + String.valueOf(machine) + "." + String.valueOf(app);
    }

    static float clampHeight(float height) {
        if (!Float.isFinite(height)) {
            return DEFAULT_HEIGHT_METERS;
        }
        return Math.max(MIN_HEIGHT_METERS, Math.min(MAX_HEIGHT_METERS, height));
    }
}
