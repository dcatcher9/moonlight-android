package com.limelight.ui;

import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.Window;

import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.LimeLog;

/**
 * The video entity's SceneCore presentation {@link Surface} and the frame-rate preferences it
 * carries.
 *
 * <p>SceneCore may replace the Surface whenever the entity's pixel dimensions change, so every
 * resize goes through {@link #resize(int, int)}, which adopts the Surface SceneCore publishes.
 * Each adoption reapplies the durable panel-rate vote to that Surface and the scoped Client SBS
 * window preference to the Activity window; the temporary effective stream FPS is never used.
 * {@link XrStreamPresenter} decides the pixel dimensions and supplies the vote through
 * {@link Host}.</p>
 *
 * <p>Every method runs on the main thread, as SceneCore requires. The Client SBS EGL owner thread
 * also reads {@link #surface()}; the field is volatile for that read.</p>
 */
final class XrVideoSurfaceOwner {
    /** What the owner reads from the presenter on every call. */
    interface Host {
        SurfaceEntity videoEntity();

        Window window();

        Display activeDisplay();

        /** Whether the scoped Client SBS 72 Hz window preference applies now. */
        boolean preferClientPanelRate();

        /** Durable SceneCore Surface frame-rate vote in Hz; zero or less requests none. */
        int surfaceFrameRateVoteHz();
    }

    private final Host host;
    private final ClientPanelRefreshRatePreference clientPanelRefreshRatePreference =
            new ClientPanelRefreshRatePreference();
    /** Written on the main thread; read by the Client SBS EGL owner. */
    private volatile Surface surface;

    XrVideoSurfaceOwner(Host host) {
        this.host = host;
    }

    Surface surface() {
        return surface;
    }

    /** Adopts a newly obtained Surface and immediately restores its durable frame-rate vote. */
    void adopt(Surface replacement) {
        surface = replacement;
        applyFrameRatePreference();
    }

    /**
     * Re-pins the entity's pixel dimensions and adopts the Surface SceneCore then publishes.
     *
     * @return true when the entity exists and returned a valid, possibly replacement, Surface
     */
    boolean resize(int width, int height) {
        SurfaceEntity entity = host.videoEntity();
        if (entity == null || entity.isDisposed()) {
            return false;
        }
        entity.setSurfacePixelDimensions(new IntSize2d(width, height));
        adopt(entity.getSurface());
        Surface adopted = surface;
        return adopted != null && adopted.isValid();
    }

    /**
     * Reapplies mode-aware panel preferences to the window and the current SceneCore Surface.
     *
     * <p>Best effort: the Surface may already be retiring during a mode transition. The next
     * {@link #adopt(Surface)} reapplies the same durable value to its replacement.</p>
     */
    void applyFrameRatePreference() {
        // Prefer the advertised 72 Hz panel mode for Client SBS's low stream ceilings. The
        // durable ceiling decides, so a new 90 FPS request releases the preference even if its
        // first ACK remains capped to the current panel rate.
        clientPanelRefreshRatePreference.apply(host.window(), host.activeDisplay(),
                host.preferClientPanelRate());
        Surface current = surface;
        int ceilingHz = host.surfaceFrameRateVoteHz();
        if (current == null || !current.isValid() || ceilingHz <= 0
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                current.setFrameRate(
                        (float) ceilingHz,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS);
            } else {
                current.setFrameRate(
                        (float) ceilingHz,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
            LimeLog.info("XR: SceneCore presentation Surface requests "
                    + ceilingHz + " Hz");
        } catch (Throwable error) {
            // Surface replacement races are recoverable: the next adopted Surface retries.
            LimeLog.warning("XR: unable to apply SceneCore Surface frame-rate ceiling: "
                    + error.getMessage());
        }
    }

    /** Restores the window hints that the Client SBS preference replaced, if it applied. */
    void releaseWindowPreference() {
        clientPanelRefreshRatePreference.apply(host.window(), null, false);
    }

    /** Forgets the Surface after the entity is disposed. */
    void clear() {
        surface = null;
    }
}
