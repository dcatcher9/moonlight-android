package com.limelight.utils;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Small, dependency-light helpers for Android XR (Galaxy XR) detection.
 *
 * <p>This class deliberately touches <b>no</b> Jetpack XR classes so it can be called from
 * anywhere (e.g. {@code PreferenceConfiguration}, {@code Game}) without loading SceneCore on
 * a non-XR device. Only {@code XrStreamPresenter} imports the XR SDK, and it is constructed
 * exclusively behind {@link #isXrDevice(Context)}.
 *
 * <p>This is an XR-only build (the manifest requires {@code android.software.xr.api.spatial}),
 * so {@link #isXrDevice(Context)} is expected to return {@code true} on every device that can
 * install the app. The guard remains a safety net (e.g. emulators, future config changes).
 */
public final class XrUtils {

    // The Jetpack XR (SceneCore) spatial feature. Matches the <uses-feature> in the manifest.
    private static final String FEATURE_XR_SPATIAL = "android.software.xr.api.spatial";

    private XrUtils() {}

    /** True when running on an Android XR device that advertises the spatial feature. */
    public static boolean isXrDevice(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm != null && pm.hasSystemFeature(FEATURE_XR_SPATIAL);
    }
}
