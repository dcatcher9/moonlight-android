package com.limelight.ui;

import android.content.Intent;

import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;

/**
 * Transient handoff for an Apply-driven Activity recreation.
 *
 * <p>The durable per-PC view store intentionally remembers only physical height. Apply must be
 * visually seamless, however, so it also carries the live real-world pose to the replacement
 * SceneCore session. Entity scale is folded into the canonical height before the handoff.</p>
 *
 * <p>When the captured quad is the Cinema View preset, the handoff also carries the screen that
 * Exit Cinema returns to: its per-PC height and its real-world pose. The replacement presenter
 * therefore resumes Cinema View, and its durable height stays the pre-Cinema one.</p>
 */
final class XrReconnectViewState {
    private static final String EXTRA_HEIGHT =
            "com.limelight.extra.XR_RECONNECT_PANEL_HEIGHT";
    private static final String EXTRA_POSE =
            "com.limelight.extra.XR_RECONNECT_PANEL_POSE";
    private static final String EXTRA_CINEMA_RESTORE_HEIGHT =
            "com.limelight.extra.XR_RECONNECT_CINEMA_RESTORE_HEIGHT";
    private static final String EXTRA_CINEMA_RESTORE_POSE =
            "com.limelight.extra.XR_RECONNECT_CINEMA_RESTORE_POSE";
    private static final int POSE_VALUE_COUNT = 7;
    private static final float MIN_QUATERNION_LENGTH_SQUARED = 0.000001f;

    final float panelHeightMeters;
    final Pose realWorldPose;
    /** The captured quad is the Cinema View preset; the restore fields below are meaningful. */
    final boolean cinemaViewExpanded;
    /** Height Exit Cinema restores, in the same local units as the durable per-PC height. */
    final float cinemaRestoreHeightMeters;
    /** Real-world pose Exit Cinema restores, or null when the pre-Cinema pose was unavailable. */
    final Pose cinemaRestorePose;

    XrReconnectViewState(float panelHeightMeters, Pose realWorldPose) {
        this(panelHeightMeters, realWorldPose, false,
                XrViewStateStore.DEFAULT_HEIGHT_METERS, null);
    }

    private XrReconnectViewState(float panelHeightMeters, Pose realWorldPose,
                                 boolean cinemaViewExpanded, float cinemaRestoreHeightMeters,
                                 Pose cinemaRestorePose) {
        this.panelHeightMeters = XrViewStateStore.clampHeight(panelHeightMeters);
        this.realWorldPose = sanitizePose(realWorldPose);
        this.cinemaViewExpanded = cinemaViewExpanded;
        this.cinemaRestoreHeightMeters = XrViewStateStore.clampHeight(cinemaRestoreHeightMeters);
        this.cinemaRestorePose = cinemaViewExpanded ? sanitizePose(cinemaRestorePose) : null;
    }

    /** The same captured quad, marked as the Cinema View preset with its restore target. */
    XrReconnectViewState withCinemaRestore(float restoreHeightMeters, Pose restorePose) {
        return new XrReconnectViewState(panelHeightMeters, realWorldPose, true,
                restoreHeightMeters, restorePose);
    }

    void writeTo(Intent intent) {
        if (intent == null) {
            return;
        }
        intent.putExtra(EXTRA_HEIGHT, panelHeightMeters);
        putPose(intent, EXTRA_POSE, realWorldPose);
        if (cinemaViewExpanded) {
            intent.putExtra(EXTRA_CINEMA_RESTORE_HEIGHT, cinemaRestoreHeightMeters);
            putPose(intent, EXTRA_CINEMA_RESTORE_POSE, cinemaRestorePose);
        } else {
            intent.removeExtra(EXTRA_CINEMA_RESTORE_HEIGHT);
            intent.removeExtra(EXTRA_CINEMA_RESTORE_POSE);
        }
    }

    private static void putPose(Intent intent, String key, Pose pose) {
        if (pose == null) {
            intent.removeExtra(key);
            return;
        }
        Vector3 translation = pose.getTranslation();
        Quaternion rotation = pose.getRotation();
        intent.putExtra(key, new float[] {
                translation.getX(), translation.getY(), translation.getZ(),
                rotation.getX(), rotation.getY(), rotation.getZ(), rotation.getW()
        });
    }

    static XrReconnectViewState consumeFrom(Intent intent) {
        XrReconnectViewState state = readFrom(intent);
        if (intent != null) {
            intent.removeExtra(EXTRA_HEIGHT);
            intent.removeExtra(EXTRA_POSE);
            intent.removeExtra(EXTRA_CINEMA_RESTORE_HEIGHT);
            intent.removeExtra(EXTRA_CINEMA_RESTORE_POSE);
        }
        return state;
    }

    private static XrReconnectViewState readFrom(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_HEIGHT)) {
            return null;
        }
        float height = intent.getFloatExtra(
                EXTRA_HEIGHT, XrViewStateStore.DEFAULT_HEIGHT_METERS);
        if (!Float.isFinite(height) || height <= 0.0f) {
            return null;
        }
        XrReconnectViewState state = new XrReconnectViewState(
                height, poseFrom(intent.getFloatArrayExtra(EXTRA_POSE)));
        if (!intent.hasExtra(EXTRA_CINEMA_RESTORE_HEIGHT)) {
            return state;
        }
        float restoreHeight = intent.getFloatExtra(
                EXTRA_CINEMA_RESTORE_HEIGHT, XrViewStateStore.DEFAULT_HEIGHT_METERS);
        if (!Float.isFinite(restoreHeight) || restoreHeight <= 0.0f) {
            return state;
        }
        return state.withCinemaRestore(restoreHeight,
                poseFrom(intent.getFloatArrayExtra(EXTRA_CINEMA_RESTORE_POSE)));
    }

    static float effectiveHeight(float shapeHeight, float realWorldScaleY, float fallbackHeight) {
        float effective = shapeHeight * Math.abs(realWorldScaleY);
        if (!Float.isFinite(effective) || effective <= 0.0f) {
            effective = fallbackHeight;
        }
        return XrViewStateStore.clampHeight(effective);
    }

    static float localHeight(float realWorldHeight, float parentRealWorldScaleY) {
        float scale = Math.abs(parentRealWorldScaleY);
        if (!Float.isFinite(scale) || scale <= 0.0f) {
            return XrViewStateStore.clampHeight(realWorldHeight);
        }
        return XrViewStateStore.clampHeight(realWorldHeight / scale);
    }

    private static Pose poseFrom(float[] values) {
        if (values == null || values.length != POSE_VALUE_COUNT) {
            return null;
        }
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return null;
            }
        }
        return sanitizePose(new Pose(
                new Vector3(values[0], values[1], values[2]),
                new Quaternion(values[3], values[4], values[5], values[6])));
    }

    private static Pose sanitizePose(Pose pose) {
        if (pose == null) {
            return null;
        }
        Vector3 translation = pose.getTranslation();
        Quaternion rotation = pose.getRotation();
        if (!isFinite(translation.getX(), translation.getY(), translation.getZ(),
                rotation.getX(), rotation.getY(), rotation.getZ(), rotation.getW())) {
            return null;
        }
        float lengthSquared = rotation.getX() * rotation.getX()
                + rotation.getY() * rotation.getY()
                + rotation.getZ() * rotation.getZ()
                + rotation.getW() * rotation.getW();
        if (!Float.isFinite(lengthSquared)
                || lengthSquared < MIN_QUATERNION_LENGTH_SQUARED) {
            return null;
        }
        float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
        return new Pose(translation, new Quaternion(
                rotation.getX() * inverseLength,
                rotation.getY() * inverseLength,
                rotation.getZ() * inverseLength,
                rotation.getW() * inverseLength));
    }

    private static boolean isFinite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
