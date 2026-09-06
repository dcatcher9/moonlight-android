package com.limelight.ui;

import androidx.xr.scenecore.Scene;
import androidx.xr.scenecore.SpatialCapability;
import androidx.xr.scenecore.SpatialEnvironment;

import com.limelight.LimeLog;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-thread public SceneCore preference override. Its caller owns its lifetime and restoration. */
final class XrBlackEnvironmentOverride {
    private final Scene scene;
    private final SpatialEnvironment environment;
    private final SpatialEnvironment.SpatialEnvironmentPreference previousEnvironment;
    private final float previousPassthrough;
    private final SpatialEnvironment.SpatialEnvironmentPreference blackEnvironment =
            new SpatialEnvironment.SpatialEnvironmentPreference(null, null);
    private final Runnable invalidated;
    private final BooleanSupplier stillOwned;
    private final Consumer<Float> passthroughListener = ignored -> observeAppliedState();
    private final Consumer<Boolean> environmentListener = ignored -> observeAppliedState();
    private final Consumer<Set<SpatialCapability>> capabilityListener = ignored -> observeAppliedState();
    private boolean listening;
    private boolean preferencesChanged;
    private boolean requested;
    private boolean applied;
    private boolean ended;

    XrBlackEnvironmentOverride(Scene scene, BooleanSupplier stillOwned, Runnable invalidated) {
        this.scene = scene;
        this.environment = scene.getSpatialEnvironment();
        this.invalidated = invalidated;
        this.stillOwned = stillOwned;
        // Preserve the preference, including NO_PASSTHROUGH_OPACITY_PREFERENCE, not the
        // system-controlled visible opacity. Do not dispose resources owned by the old preference.
        previousEnvironment = environment.getPreferredSpatialEnvironment();
        previousPassthrough = environment.getPreferredPassthroughOpacity();
    }

    boolean hasRequiredCapabilities() {
        Set<SpatialCapability> capabilities = scene.getSpatialCapabilities();
        return capabilities.contains(SpatialCapability.APP_ENVIRONMENT)
                && capabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL);
    }

    boolean belongsTo(Scene currentScene) {
        return currentScene == scene && currentScene.getSpatialEnvironment() == environment;
    }

    boolean begin() {
        if (ended || listening) {
            return false;
        }
        try {
            if (!hasRequiredCapabilities()) {
                LimeLog.info("Cinema background unavailable: environment/passthrough capability missing");
                return false;
            }
            // Mark before registering so partial registration failures still remove every listener.
            listening = true;
            environment.addPassthroughOpacityChangedListener(passthroughListener);
            environment.addSpatialEnvironmentChangedListener(environmentListener);
            scene.addSpatialCapabilitiesChangedListener(capabilityListener);
            preferencesChanged = true;
            // null itself would select the system environment; an empty non-null preference is black.
            environment.setPreferredSpatialEnvironment(blackEnvironment);
            environment.setPreferredPassthroughOpacity(0f);
            requested = true;
            observeAppliedState();
            return !ended;
        } catch (RuntimeException | LinkageError e) {
            LimeLog.warning("Could not apply Cinema background: " + e);
            restore(true);
            return false;
        }
    }

    private void observeAppliedState() {
        if (ended || !requested) {
            return;
        }
        try {
            if (!stillOwned.getAsBoolean()) {
                invalidate("scene or owner changed");
                return;
            }
            if (!hasRequiredCapabilities()) {
                invalidate("capability lost");
                return;
            }
            if (!blackEnvironment.equals(environment.getPreferredSpatialEnvironment())
                    || environment.getPreferredPassthroughOpacity() != 0f) {
                invalidate("preference changed");
                return;
            }
            boolean active = environment.isPreferredSpatialEnvironmentActive();
            float opacity = environment.getCurrentPassthroughOpacity();
            if (active && opacity == 0f) {
                applied = true;
            } else if (applied) {
                invalidate("applied state lost: environmentActive=" + active + " actualPassthrough=" + opacity);
            }
        } catch (RuntimeException | LinkageError e) {
            invalidate("runtime state unavailable: " + e);
        }
    }

    private void invalidate(String reason) {
        LimeLog.info("Cinema background released: " + reason);
        try {
            invalidated.run();
        } catch (RuntimeException | LinkageError e) {
            LimeLog.warning("Could not release Cinema background: " + e);
        }
    }

    /** Remove only this controller's listeners, then restore preferences only on its current scene. */
    void restore(boolean sameScene) {
        if (ended) {
            return;
        }
        ended = true;
        if (listening) {
            listening = false;
            attempt(() -> environment.removePassthroughOpacityChangedListener(passthroughListener));
            attempt(() -> environment.removeSpatialEnvironmentChangedListener(environmentListener));
            attempt(() -> scene.removeSpatialCapabilitiesChangedListener(capabilityListener));
        }
        if (!preferencesChanged) {
            return;
        }
        if (sameScene) {
            // Attempt each independently: a failed SDK call must not prevent the other restoration.
            // Preserve a newer app preference even if its queued state callback has not run yet.
            attempt(() -> {
                if (!requested || blackEnvironment.equals(environment.getPreferredSpatialEnvironment())) {
                    environment.setPreferredSpatialEnvironment(previousEnvironment);
                }
            });
            attempt(() -> {
                if (!requested || environment.getPreferredPassthroughOpacity() == 0f) {
                    environment.setPreferredPassthroughOpacity(previousPassthrough);
                }
            });
        }
    }

    private void attempt(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            LimeLog.warning("Cinema background cleanup failed: " + e);
        }
    }
}
