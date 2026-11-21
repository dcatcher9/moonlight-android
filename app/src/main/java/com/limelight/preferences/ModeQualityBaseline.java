package com.limelight.preferences;

import com.limelight.ui.PresentationMode;
import com.limelight.ui.xrcontrols.SessionSettingsModel;

import java.util.EnumMap;

/**
 * The stream quality a presentation mode starts from when the current app has no saved override
 * for it. Every mode inherits the Global Settings value, except that Client SBS starts its
 * resolution and frame rate at a lighter 1920 x 1080 at 30 FPS; its bitrate still follows
 * Global Settings.
 *
 * <p>Stateless: every method is a pure function of its arguments, so it is safe from any thread.
 * Callers supply the global value; nothing here reads preferences or the session store.</p>
 */
final class ModeQualityBaseline {
    static final String CLIENT_SBS_RESOLUTION = "1920x1080";
    static final String CLIENT_SBS_FRAME_RATE = "30";

    private ModeQualityBaseline() {
    }

    /** The mode's own starting value for {@code key}, or null when it inherits the global one. */
    static Object ownBaseline(PresentationMode mode, SessionSettingsModel.Key key) {
        if (mode == PresentationMode.CLIENT_SBS_AI) {
            if (key == SessionSettingsModel.Key.RESOLUTION) {
                return CLIENT_SBS_RESOLUTION;
            }
            if (key == SessionSettingsModel.Key.FRAME_RATE) {
                return CLIENT_SBS_FRAME_RATE;
            }
        }
        return null;
    }

    /** What {@code mode} uses for {@code key} when nothing is saved: its own value or the global. */
    static Object inheritedValue(PresentationMode mode, SessionSettingsModel.Key key,
                                 Object globalValue) {
        Object own = ownBaseline(mode, key);
        return own != null ? own : globalValue;
    }

    /** Replaces each value in {@code values} for which {@code mode} has its own baseline. */
    static void applyOwnBaselines(PresentationMode mode,
                                  EnumMap<SessionSettingsModel.Key, Object> values) {
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            Object own = ownBaseline(mode, key);
            if (own != null) {
                values.put(key, own);
            }
        }
    }
}
