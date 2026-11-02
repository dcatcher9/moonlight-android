package com.limelight.ui;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.xr.arcore.ArDevice;
import androidx.xr.arcore.Eye;
import androidx.xr.arcore.PerceptionState;
import androidx.xr.arcore.PerceptionStateExt;
import androidx.xr.arcore.TrackingState;
import androidx.xr.runtime.Config;
import androidx.xr.runtime.EyeTrackingMode;
import androidx.xr.runtime.Session;
import androidx.xr.runtime.SessionConfigureResult;
import androidx.xr.runtime.SessionConfigureSuccess;
import androidx.xr.runtime.XrDevice;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.Scene;
import androidx.xr.scenecore.ScenePose;
import androidx.xr.scenecore.SessionExt;

import com.limelight.LimeLog;
import com.limelight.BuildConfig;
import com.limelight.ui.xrcontrols.XrGazeTarget;

import java.util.Objects;
import java.util.function.Consumer;

/** Optional, permission-gated eye input used only while the dock's reveal pill is visible. */
final class XrDockGazeTracker implements AutoCloseable {
    private static final long SAMPLE_INTERVAL_MS = 33L;
    private static final long TRACKING_LOSS_GRACE_MS = 150L;

    private final Activity activity;
    private final Session session;
    private final PanelEntity target;
    private final Consumer<Boolean> onGazeChanged;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sampleRunnable = this::sample;
    private final LifecycleEventObserver lifecycleObserver;
    private boolean active;
    private boolean closed;
    private boolean supportChecked;
    private boolean supported;
    private boolean trackingEnabled;
    private boolean configurationFailed;
    private boolean lookingAt;
    private long lastValidGazeMs;
    private int diagnosticSamples;
    private int diagnosticMissingSnapshots;
    private int diagnosticTrackedHeads;
    private int diagnosticPresentEyes;
    private int diagnosticValidEyes;
    private int diagnosticHits;
    private int diagnosticReportsRemaining;

    XrDockGazeTracker(Activity activity, Session session, PanelEntity target,
                      Consumer<Boolean> onGazeChanged) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.session = Objects.requireNonNull(session, "session");
        this.target = Objects.requireNonNull(target, "target");
        this.onGazeChanged = Objects.requireNonNull(onGazeChanged, "onGazeChanged");
        lifecycleObserver = (source, event) -> {
            if (closed) {
                return;
            }
            if ((event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME)
                    && active) {
                // Game.onStart() may run before ComponentActivity dispatches STARTED. Preserve
                // the caller's intent and retry once lifecycle eligibility has caught up.
                startIfReady();
            }
            else if (event == Lifecycle.Event.ON_STOP) {
                handler.removeCallbacks(sampleRunnable);
                publish(false);
                disableTracking();
            }
            else if (event == Lifecycle.Event.ON_DESTROY) {
                close();
            }
        };
        if (activity instanceof ComponentActivity) {
            ((ComponentActivity) activity).getLifecycle().addObserver(lifecycleObserver);
        }
    }

    void setActive(boolean active) {
        if (closed || this.active == active) {
            return;
        }
        this.active = active;
        handler.removeCallbacks(sampleRunnable);
        if (active) {
            startIfReady();
        }
        else {
            publish(false);
            disableTracking();
        }
    }

    private void startIfReady() {
        if (closed || !active || !isForeground() || configurationFailed || !isSupported()) {
            return;
        }
        // Home owns permission setup. A stream only consumes its saved OS grant and must never
        // interrupt playback by opening a consent dialog when the dock first collapses.
        if (!hasPermission()) {
            return;
        }
        if (!trackingEnabled) {
            try {
                SessionConfigureResult result = session.configure(
                        new Config.Builder(session.getConfig())
                                .setEyeTracking(EyeTrackingMode.FINE_TRACKING).build());
                if (!(result instanceof SessionConfigureSuccess)) {
                    configurationFailed = true;
                    LimeLog.warning("XR: dock eye-tracking configure unavailable: "
                            + result.getClass().getSimpleName());
                    return;
                }
                trackingEnabled = true;
                diagnosticReportsRemaining = BuildConfig.DEBUG ? 6 : 0;
                LimeLog.info("XR: dock eye tracking enabled");
            } catch (RuntimeException | LinkageError e) {
                configurationFailed = true;
                LimeLog.warning("XR: dock eye-tracking configure failed: "
                        + e.getClass().getSimpleName());
                return;
            }
        }
        handler.removeCallbacks(sampleRunnable);
        handler.post(sampleRunnable);
    }

    private boolean isSupported() {
        if (!supportChecked) {
            supportChecked = true;
            try {
                supported = activity.getPackageManager().hasSystemFeature(
                                XrEyeTrackingPermission.FEATURE)
                        && XrDevice.getCurrentDevice(activity)
                                .isEyeTrackingModeSupported(EyeTrackingMode.FINE_TRACKING);
            } catch (RuntimeException | LinkageError e) {
                LimeLog.warning("XR: dock eye-tracking support check failed: "
                        + e.getClass().getSimpleName());
            }
            if (!supported) {
                LimeLog.info("XR: dock gaze expansion is unavailable on this device");
            }
        }
        return supported;
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(activity, XrEyeTrackingPermission.PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isForeground() {
        return !activity.isFinishing() && !activity.isDestroyed()
                && activity instanceof ComponentActivity
                && ((ComponentActivity) activity).getLifecycle().getCurrentState()
                        .isAtLeast(Lifecycle.State.STARTED);
    }

    private void sample() {
        if (closed || !active) {
            return;
        }
        if (!isForeground() || !hasPermission() || target.isDisposed()) {
            publish(false);
            disableTracking();
            return;
        }
        try {
            // All poses come from one CoreState timestamp. Reading Eye and ArDevice's separate
            // latest StateFlows can combine different head/eye frames during head movement.
            PerceptionState snapshot = PerceptionStateExt.getPerceptionState(
                    session.getState().getValue());
            boolean hit = false;
            boolean validGaze = false;
            if (snapshot != null) {
                ArDevice.State device = snapshot.getArDeviceState();
                if (TrackingState.TRACKING.equals(device.getTrackingState())) {
                    validGaze = isEyeTracked(snapshot.getLeftEyeState())
                            || isEyeTracked(snapshot.getRightEyeState());
                    Scene scene = SessionExt.getScene(session);
                    ScenePose head = scene.getPerceptionSpace()
                            .getScenePoseFromPerceptionPose(device.getDevicePose());
                    FloatSize2d size = target.getSize();
                    hit = eyeHitsTarget(snapshot.getLeftEyeState(), head, size)
                            || eyeHitsTarget(snapshot.getRightEyeState(), head, size);
                }
            }
            updateGaze(validGaze, hit);
            recordDiagnostics(snapshot, validGaze, hit);
        } catch (RuntimeException | LinkageError e) {
            // Optional gaze input must never interrupt decoding or leave an endless failing poll.
            configurationFailed = true;
            publish(false);
            disableTracking();
            LimeLog.warning("XR: dock eye-tracking sampling stopped: "
                    + e.getClass().getSimpleName());
            return;
        }
        if (!closed && active && trackingEnabled) {
            handler.postDelayed(sampleRunnable, SAMPLE_INTERVAL_MS);
        }
    }

    private boolean eyeHitsTarget(Eye.State eye, ScenePose head, FloatSize2d size) {
        if (!isEyeTracked(eye)) {
            return false;
        }
        Pose pose = eye.getPose();
        // OpenXR eye poses use +Z into the eye, so the outward gaze ray is local -Z.
        // The native runtime copies that orientation directly into Pose.
        return XrGazeTarget.isLookingAt(
                head.transformPositionTo(pose.getTranslation(), target),
                head.transformDirectionTo(pose.getForward(), target),
                size.getWidth(), size.getHeight());
    }

    private void recordDiagnostics(PerceptionState snapshot, boolean validGaze, boolean hit) {
        if (diagnosticReportsRemaining == 0) {
            return;
        }
        diagnosticSamples++;
        if (snapshot == null) {
            diagnosticMissingSnapshots++;
        }
        else {
            if (TrackingState.TRACKING.equals(snapshot.getArDeviceState().getTrackingState())) {
                diagnosticTrackedHeads++;
            }
            if (snapshot.getLeftEyeState() != null || snapshot.getRightEyeState() != null) {
                diagnosticPresentEyes++;
            }
        }
        if (validGaze) {
            diagnosticValidEyes++;
        }
        if (hit) {
            diagnosticHits++;
        }
        if (diagnosticSamples >= 60) {
            LimeLog.info("XR: dock gaze diagnostics samples=" + diagnosticSamples
                    + " missingSnapshots=" + diagnosticMissingSnapshots
                    + " trackedHeads=" + diagnosticTrackedHeads
                    + " eyesPresent=" + diagnosticPresentEyes
                    + " validEyes=" + diagnosticValidEyes
                    + " pillHits=" + diagnosticHits);
            diagnosticReportsRemaining--;
            diagnosticSamples = 0;
            diagnosticMissingSnapshots = 0;
            diagnosticTrackedHeads = 0;
            diagnosticPresentEyes = 0;
            diagnosticValidEyes = 0;
            diagnosticHits = 0;
        }
    }

    private static boolean isEyeTracked(Eye.State eye) {
        return eye != null && eye.isOpen()
                && TrackingState.TRACKING.equals(eye.getTrackingState());
    }

    private void updateGaze(boolean validGaze, boolean hit) {
        long now = SystemClock.uptimeMillis();
        if (validGaze) {
            lastValidGazeMs = now;
            // An observed look away takes effect immediately. Only short gaps from blinks or
            // temporarily unavailable tracking retain the last hit to avoid pulsing the pill.
            publish(hit);
        }
        else if (!lookingAt || now - lastValidGazeMs >= TRACKING_LOSS_GRACE_MS) {
            publish(false);
        }
    }

    private void publish(boolean lookingAt) {
        if (this.lookingAt != lookingAt) {
            this.lookingAt = lookingAt;
            onGazeChanged.accept(lookingAt);
        }
    }

    private void disableTracking() {
        if (!trackingEnabled) {
            return;
        }
        // Clear ownership before configuring, so even a runtime failure cannot cause repeated
        // configure calls on every visibility refresh. Session teardown remains the final owner.
        trackingEnabled = false;
        try {
            SessionConfigureResult result = session.configure(
                    new Config.Builder(session.getConfig())
                            .setEyeTracking(EyeTrackingMode.DISABLED).build());
            if (!(result instanceof SessionConfigureSuccess)) {
                configurationFailed = true;
                LimeLog.warning("XR: dock eye-tracking disable unavailable: "
                        + result.getClass().getSimpleName());
            }
        } catch (RuntimeException | LinkageError e) {
            configurationFailed = true;
            LimeLog.warning("XR: dock eye-tracking disable failed: "
                    + e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        if (activity instanceof ComponentActivity) {
            ((ComponentActivity) activity).getLifecycle().removeObserver(lifecycleObserver);
        }
        handler.removeCallbacks(sampleRunnable);
        publish(false);
        disableTracking();
    }
}
