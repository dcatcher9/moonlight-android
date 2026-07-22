package com.limelight.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.limelight.Game;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.session.SessionSettingsStore;

/**
 * Small SceneCore-independent store for XR panel view state. Physical height is durable per PC.
 * Presentation mode belongs to the PC's one current-session record and is restored only when the
 * launch intent carries the host's authoritative same-session resume decision.
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
    private final SessionSettingsStore sessionSettingsStore;
    private final SessionSettingsStore.PcIdentity pcIdentity;

    XrViewStateStore(Context context, Intent intent) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        key = buildKey(intent);
        restorePresentationMode = intent != null
                && intent.getBooleanExtra(Game.EXTRA_RESUME_EXISTING_SESSION, false);
        sessionSettingsStore = new SessionSettingsStore(context);
        pcIdentity = buildPcIdentity(intent);
    }

    State restore() {
        float height = DEFAULT_HEIGHT_METERS;
        try {
            height = preferences.getFloat(key + HEIGHT_SUFFIX, DEFAULT_HEIGHT_METERS);
        } catch (ClassCastException ignored) {
            // Treat corrupt or old-schema preferences as absent.
        }

        Mode mode = Mode.NORMAL;
        if (restorePresentationMode && pcIdentity != null) {
            SessionSettingsStore.SessionRecord record =
                    sessionSettingsStore.getCurrentSession(pcIdentity);
            if (record != null) {
                try {
                    mode = Mode.valueOf(record.getLastSuccessfulMode().name());
                } catch (IllegalArgumentException ignored) {
                    mode = Mode.NORMAL;
                }
            }
        }
        return new State(clampHeight(height), mode);
    }

    void saveHeight(float panelHeightMeters) {
        preferences.edit()
                .putFloat(key + HEIGHT_SUFFIX, clampHeight(panelHeightMeters))
                .apply();
    }

    static int desiredHostSbsWireMode(Mode mode) {
        // These are compile-time int constants, so this does not initialize MoonBridge/JNI.
        return mode == Mode.HOST_SBS_AI ? MoonBridge.SBS_MODE_AI : MoonBridge.SBS_MODE_OFF;
    }

    static String buildKey(Intent intent) {
        String machine = intent != null ? intent.getStringExtra(Game.EXTRA_PC_UUID) : null;
        if (machine == null || machine.isEmpty()) {
            machine = intent != null ? intent.getStringExtra(Game.EXTRA_HOST) : null;
        }
        return "view." + String.valueOf(machine);
    }

    private static SessionSettingsStore.PcIdentity buildPcIdentity(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            return new SessionSettingsStore.PcIdentity(
                    intent.getStringExtra(Game.EXTRA_PC_UUID),
                    intent.getStringExtra(Game.EXTRA_HOST));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static float clampHeight(float height) {
        if (!Float.isFinite(height)) {
            return DEFAULT_HEIGHT_METERS;
        }
        return Math.max(MIN_HEIGHT_METERS, Math.min(MAX_HEIGHT_METERS, height));
    }
}
