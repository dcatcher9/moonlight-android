package com.limelight.ui;

import android.app.Activity;
import android.view.Surface;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.SessionCreateResult;
import androidx.xr.runtime.SessionCreateSuccess;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.Scene;
import androidx.xr.scenecore.SessionExt;
import androidx.xr.scenecore.SpatialCapability;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Presentation owner for the <b>host-side SBS</b> render mode ({@code MODE_XR_SBS}).
 *
 * <p>Unlike the on-device AI 2D&rarr;3D path ({@code Stereo3DRenderer}), here the PC already
 * produced a side-by-side stereo frame; the device does no inference. We create a Jetpack XR
 * (SceneCore) {@link SurfaceEntity} in {@link SurfaceEntity.StereoMode#SIDE_BY_SIDE} and hand
 * the decoder that entity's {@link Surface}. The XR compositor splits the left/right halves to
 * each eye — the decoder renders straight into the surface exactly like the plain 2D path.
 *
 * <p>Mirrors the contract {@code Stereo3DRenderer} exposes to {@link StreamContainer}: it
 * obtains a video {@link Surface} and notifies via {@link OnSurfaceReadyListener}, so
 * {@code StreamContainer} can wire {@code decoderRenderer.setRenderTarget(...)} identically.
 *
 * <p>This class is the <i>only</i> one that imports the Jetpack XR SDK, and it is constructed
 * exclusively behind {@code XrUtils.isXrDevice(...)}.
 *
 * <p>See docs/android-xr-sbs.md. This is a scaffold; several decisions there are still open
 * (panel sizing/placement, in-game menu affordance, session lifecycle on pause/resume).
 */
public class XrStreamPresenter {

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final OnSurfaceReadyListener listener;

    private Session session;
    private SurfaceEntity surfaceEntity;
    private Surface videoSurface;

    public XrStreamPresenter(Activity activity, PreferenceConfiguration prefConfig,
                             OnSurfaceReadyListener listener) {
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.listener = listener;
    }

    /**
     * Create the XR session and SBS surface entity, then notify the listener with the surface.
     * Must be called on the main thread (SceneCore session creation is Activity-bound).
     */
    public void init() {
        SessionCreateResult result = Session.create(activity);
        if (!(result instanceof SessionCreateSuccess)) {
            // TODO: surface this to the user (dialog / fall back to a 2D panel). For the
            //  scaffold we just log; the stream simply won't get a render target.
            LimeLog.severe("XR session creation failed: " + result.getClass().getSimpleName());
            return;
        }
        session = ((SessionCreateSuccess) result).getSession();

        // Stereo SurfaceEntity content only renders when the activity has the SPATIAL_3D_CONTENT
        // capability, which requires Full Space mode. scenecore alpha13 has no runtime request
        // for it; the Game activity opts in via PROPERTY_XR_ACTIVITY_START_MODE =
        // XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED in AndroidManifest.xml. Log the capability
        // so a Full-Space misconfiguration is diagnosable on-device.
        Scene scene = SessionExt.getScene(session);
        boolean has3d = scene.getSpatialCapabilities().contains(SpatialCapability.SPATIAL_3D_CONTENT);
        LimeLog.info("XR: SPATIAL_3D_CONTENT capability = " + has3d);
        if (!has3d) {
            LimeLog.warning("XR: SPATIAL_3D_CONTENT capability missing — SBS will not render "
                    + "stereoscopically (activity likely not in Full Space mode).");
        }

        // Quad sized to a single eye's aspect ratio. In a packed SBS frame each eye gets half
        // the horizontal resolution, so the per-eye aspect is (width/2):height.
        float perEyeAspect = (prefConfig.width / 2.0f) / prefConfig.height;
        float panelWidthMeters = 1.0f; // TODO: make placement/size configurable.
        float panelHeightMeters = panelWidthMeters / perEyeAspect;
        SurfaceEntity.Shape quad =
                new SurfaceEntity.Shape.Quad(new FloatSize2d(panelWidthMeters, panelHeightMeters));

        // Place the quad ~2 m directly in front of the viewer. At Pose.Identity the entity
        // sits at the activity-space origin, which is colocated with the initial head pose, so
        // the panel ends up at/behind the eyes and is never visible. -Z is forward in SceneCore.
        Pose panelPose = new Pose(new Vector3(0.0f, 0.0f, -2.0f), Quaternion.Identity);
        surfaceEntity = SurfaceEntity.create(
                session,
                panelPose,
                quad,
                SurfaceEntity.StereoMode.SIDE_BY_SIDE);

        // Tell the entity the producer buffer size so it matches the full decoded SBS frame
        // (decoder renders at prefConfig.width x height; the compositor splits L/R from it).
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(prefConfig.width, prefConfig.height));

        // Make attachment/visibility explicit rather than relying on create() defaults:
        // parent the quad to the activity space (the rendered scene root), and ensure it is
        // enabled and fully opaque so the compositor presents it.
        surfaceEntity.setParent(scene.getActivitySpace());
        surfaceEntity.setEnabled(true);
        surfaceEntity.setAlpha(1.0f);
        LimeLog.info("XR: SurfaceEntity created and attached; dimensions=" + surfaceEntity.getDimensions());

        videoSurface = surfaceEntity.getSurface();
        if (listener != null) {
            listener.onSurfaceReady(videoSurface);
        }
    }

    public Surface getVideoSurface() {
        return videoSurface;
    }

    /**
     * Tear down the entity/session. Mirrors {@code Stereo3DRenderer.onSurfaceDestroyed()} /
     * {@code StreamContainer.onDestroy()} ordering.
     */
    public void onDestroy() {
        // TODO: confirm the correct SceneCore teardown for the entity and session lifecycle
        //  (pause/resume vs. full dispose). For now just drop references.
        videoSurface = null;
        surfaceEntity = null;
        session = null;
    }
}
