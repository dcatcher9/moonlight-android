package com.limelight.ui;

import androidx.xr.scenecore.Scene;
import androidx.xr.scenecore.SpatialCapability;
import androidx.xr.scenecore.SpatialEnvironment;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration.CinemaEnvironment;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-thread public SceneCore preference override. Its caller owns its lifetime and restoration. */
final class XrCinemaEnvironmentOverride {
    private final Scene scene;
    private final SpatialEnvironment environment;
    private final CinemaEnvironment selection;
    private final SpatialEnvironment.SpatialEnvironmentPreference previousEnvironment;
    private final float previousPassthrough;
    private final SpatialEnvironment.SpatialEnvironmentPreference requestedEnvironment;
    private final float requestedPassthrough;
    private final Runnable invalidated;
    private final BooleanSupplier stillOwned;
    private final Consumer<Float> passthroughListener = ignored -> observeAppliedState();
    private final Consumer<Boolean> environmentListener = ignored -> observeAppliedState();
    private final Consumer<Set<SpatialCapability>> capabilityListener = ignored -> observeAppliedState();
    private boolean listening;
    private boolean environmentChanged;
    private boolean passthroughChanged;
    private boolean requested;
    private boolean applied;
    private boolean invalidating;
    private boolean ended;

    XrCinemaEnvironmentOverride(Scene scene, CinemaEnvironment selection,
            BooleanSupplier stillOwned, Runnable invalidated) {
        this.scene = scene;
        this.environment = scene.getSpatialEnvironment();
        this.selection = Objects.requireNonNull(selection);
        this.invalidated = invalidated;
        this.stillOwned = stillOwned;
        // Preserve the preference, including NO_PASSTHROUGH_OPACITY_PREFERENCE, not the
        // system-controlled visible opacity. Do not dispose resources owned by the old preference.
        previousEnvironment = environment.getPreferredSpatialEnvironment();
        previousPassthrough = environment.getPreferredPassthroughOpacity();
        // null selects the user's system environment; an empty non-null preference is black.
        requestedEnvironment = selection == CinemaEnvironment.BLACK
                ? new SpatialEnvironment.SpatialEnvironmentPreference(null, null) : null;
        requestedPassthrough = selection == CinemaEnvironment.PASSTHROUGH ? 1f : 0f;
    }

    boolean hasRequiredCapabilities() {
        Set<SpatialCapability> capabilities = scene.getSpatialCapabilities();
        return capabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)
                && (selection == CinemaEnvironment.PASSTHROUGH
                    || capabilities.contains(SpatialCapability.APP_ENVIRONMENT));
    }

    boolean belongsTo(Scene currentScene) {
        return currentScene == scene && currentScene.getSpatialEnvironment() == environment;
    }

    boolean begin() {
        if (ended || listening) {
            return false;
        }
        try {
            if (!isCurrentOwner()) {
                return false;
            }
            if (!hasRequiredCapabilities()) {
                LimeLog.info("Cinema background unavailable: environment/passthrough capability missing");
                return false;
            }
            // Mark before registering so partial registration failures still remove every listener.
            listening = true;
            environment.addPassthroughOpacityChangedListener(passthroughListener);
            environment.addSpatialEnvironmentChangedListener(environmentListener);
            scene.addSpatialCapabilitiesChangedListener(capabilityListener);
            if (!isCurrentOwner()) {
                restore(belongsTo(scene));
                return false;
            }
            // Full passthrough completely obscures the environment: do not take ownership of it.
            if (selection != CinemaEnvironment.PASSTHROUGH) {
                environmentChanged = true;
                environment.setPreferredSpatialEnvironment(requestedEnvironment);
            }
            if (!isCurrentOwner()) {
                restore(belongsTo(scene));
                return false;
            }
            // Mark before writing: an SDK setter can mutate its preference and then throw.
            passthroughChanged = true;
            environment.setPreferredPassthroughOpacity(requestedPassthrough);
            requested = true;
            observeAppliedState();
            return !ended;
        } catch (RuntimeException | LinkageError e) {
            LimeLog.warning("Could not apply Cinema background: " + e);
            // Listener cleanup must still happen if scene identity is no longer readable.
            attempt(() -> restore(belongsTo(scene)));
            restore(false);
            return false;
        }
    }

    private boolean isCurrentOwner() {
        return !ended && stillOwned.getAsBoolean() && belongsTo(scene);
    }

    private void observeAppliedState() {
        if (ended || invalidating || !requested) {
            return;
        }
        try {
            if (!isCurrentOwner()) {
                invalidate("scene or owner changed");
                return;
            }
            if (!hasRequiredCapabilities()) {
                invalidate("capability lost");
                return;
            }
            if ((environmentChanged
                    && !Objects.equals(requestedEnvironment, environment.getPreferredSpatialEnvironment()))
                    || environment.getPreferredPassthroughOpacity() != requestedPassthrough) {
                invalidate("preference changed");
                return;
            }
            float opacity = environment.getCurrentPassthroughOpacity();
            boolean environmentMatches = selection == CinemaEnvironment.PASSTHROUGH
                    || environment.isPreferredSpatialEnvironmentActive()
                        == (selection == CinemaEnvironment.BLACK);
            if (environmentMatches && opacity == requestedPassthrough) {
                applied = true;
            } else if (applied) {
                invalidate("applied state lost: environmentMatches=" + environmentMatches
                        + " actualPassthrough=" + opacity);
            }
        } catch (RuntimeException | LinkageError e) {
            invalidate("runtime state unavailable: " + e);
        }
    }

    private void invalidate(String reason) {
        invalidating = true;
        LimeLog.info("Cinema background released: " + reason);
        try {
            invalidated.run();
        } catch (RuntimeException | LinkageError e) {
            LimeLog.warning("Could not release Cinema background: " + e);
        } finally {
            // The caller restores after checking its current scene. If it fails, at least remove
            // listeners without mutating preferences on a scene whose ownership is now unknown.
            restore(false);
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
        if (sameScene) {
            // Attempt each independently: a failed SDK call must not prevent the other restoration.
            // Preserve a newer app preference even if its queued state callback has not run yet.
            attempt(() -> {
                if (environmentChanged
                        && Objects.equals(requestedEnvironment, environment.getPreferredSpatialEnvironment())) {
                    environment.setPreferredSpatialEnvironment(previousEnvironment);
                }
            });
            attempt(() -> {
                if (passthroughChanged
                        && environment.getPreferredPassthroughOpacity() == requestedPassthrough) {
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
