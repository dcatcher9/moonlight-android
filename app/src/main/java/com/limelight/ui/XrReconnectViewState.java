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
 */
final class XrReconnectViewState {
    private static final String EXTRA_HEIGHT =
            "com.limelight.extra.XR_RECONNECT_PANEL_HEIGHT";
    private static final String EXTRA_POSE =
            "com.limelight.extra.XR_RECONNECT_PANEL_POSE";
    private static final int POSE_VALUE_COUNT = 7;
    private static final float MIN_QUATERNION_LENGTH_SQUARED = 0.000001f;

    final float panelHeightMeters;
    final Pose realWorldPose;

    XrReconnectViewState(float panelHeightMeters, Pose realWorldPose) {
        this.panelHeightMeters = XrViewStateStore.clampHeight(panelHeightMeters);
        this.realWorldPose = sanitizePose(realWorldPose);
    }

    void writeTo(Intent intent) {
        if (intent == null) {
            return;
        }
        intent.putExtra(EXTRA_HEIGHT, panelHeightMeters);
        if (realWorldPose == null) {
            intent.removeExtra(EXTRA_POSE);
            return;
        }
        Vector3 translation = realWorldPose.getTranslation();
        Quaternion rotation = realWorldPose.getRotation();
        intent.putExtra(EXTRA_POSE, new float[] {
                translation.getX(), translation.getY(), translation.getZ(),
                rotation.getX(), rotation.getY(), rotation.getZ(), rotation.getW()
        });
    }

    static XrReconnectViewState consumeFrom(Intent intent) {
        XrReconnectViewState state = readFrom(intent);
        if (intent != null) {
            intent.removeExtra(EXTRA_HEIGHT);
            intent.removeExtra(EXTRA_POSE);
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
        Pose pose = poseFrom(intent.getFloatArrayExtra(EXTRA_POSE));
        return new XrReconnectViewState(height, pose);
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
