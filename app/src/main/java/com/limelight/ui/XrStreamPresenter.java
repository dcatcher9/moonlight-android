package com.limelight.ui;

import android.app.Activity;
import android.graphics.Color;
import android.view.Surface;
import android.widget.Button;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.SessionCreateResult;
import androidx.xr.runtime.SessionCreateSuccess;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.FloatSize3d;
import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Ray;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.Entity;
import androidx.xr.scenecore.EntityMoveListener;
import androidx.xr.scenecore.InputEvent;
import androidx.xr.scenecore.InteractableComponent;
import androidx.xr.scenecore.MovableComponent;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.ResizableComponent;
import androidx.xr.scenecore.ResizeEvent;
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
 * <p>The quad is placed ~2 m in front, sized to one eye's aspect, and the user can move/resize it
 * (with a minimum distance clamp); an always-visible spatial "Disconnect" button exits the stream.
 * See docs/android-xr-sbs.md. Still open: session lifecycle on pause/resume (see {@link #onDestroy}).
 */
public class XrStreamPresenter {

    /** Nearest the user may drag the panel toward the eyes, in meters from the activity-space origin. */
    private static final float MIN_PANEL_DISTANCE_METERS = 0.75f;

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final OnSurfaceReadyListener listener;

    private Session session;
    private SurfaceEntity surfaceEntity;
    private PanelEntity disconnectPanel;
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

        // Quad sized to one eye's pixel aspect. In a packed SBS frame each eye occupies half the
        // width, so the per-eye region is (width/2) x height pixels — for a 1280x720 stream that's
        // 640x720 (portrait, 0.889). Using the full-frame aspect instead stretches this region ~2x
        // horizontally. NOTE: if the stream shows black bars top/bottom, that is the host drawing a
        // 16:9 image into this taller per-eye slot (letterbox baked into the stream) — the client
        // aspect can't remove it; stream wider so each eye is 16:9, or fix the host SBS layout.
        float perEyeAspect = (prefConfig.width / 2.0f) / prefConfig.height;
        float panelWidthMeters = 2.0f; // larger default; user can grab-resize via ResizableComponent.
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

        // Pin the entity's surface to the full SBS frame size so the L/R split lands on the half
        // boundary.
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(prefConfig.width, prefConfig.height));
        // Parent to the activity space (the rendered scene root) and make visibility explicit.
        // Without the explicit parent the entity isn't attached to the rendered scene graph, so the
        // quad never appears even though its surface is being fed/consumed.
        surfaceEntity.setParent(scene.getActivitySpace());
        surfaceEntity.setEnabled(true);
        surfaceEntity.setAlpha(1.0f);
        LimeLog.info("XR: SurfaceEntity created; dimensions=" + surfaceEntity.getDimensions());

        // Hide the activity's 2D main panel. In full-space mode it's rendered as an opaque panel
        // (it hosts the placeholder SurfaceView, which carries no video) and sits in front of the
        // SBS quad, occluding it. We present everything through the SurfaceEntity, so hide it.
        scene.getMainPanelEntity().setEnabled(false);

        // Let the user reposition and resize the panel in-headset. Use a CUSTOM movable (not
        // system-movable) so we can clamp how close the panel may be dragged — otherwise it can be
        // pulled right up against the eyes. We apply the proposed pose ourselves, pushing it back
        // out to MIN_PANEL_DISTANCE_METERS from the activity-space origin (~the initial head pose).
        MovableComponent movable = MovableComponent.createCustomMovable(
                session, /* scaleInZ= */ false, activity.getMainExecutor(),
                new EntityMoveListener() {
                    @Override
                    public void onMoveUpdate(Entity entity, Ray currentRay, Pose proposedPose,
                                             float scale) {
                        surfaceEntity.setPose(clampToMinDistance(proposedPose));
                    }

                    @Override
                    public void onMoveEnd(Entity entity, Ray currentRay, Pose proposedPose,
                                          float scale, Entity updatedParent) {
                        surfaceEntity.setPose(clampToMinDistance(proposedPose));
                    }
                });
        surfaceEntity.addComponent(movable);

        // The resizable affordance lets the user grab a corner to scale. Keep the aspect ratio
        // fixed so the SBS halves stay aligned, and apply the new size to the quad when resize ends.

        ResizableComponent resizable = ResizableComponent.create(session, (ResizeEvent event) -> {
            if (event.getResizeState() == ResizeEvent.ResizeState.END) {
                FloatSize3d ns = event.getNewSize();
                surfaceEntity.setShape(
                        new SurfaceEntity.Shape.Quad(new FloatSize2d(ns.getWidth(), ns.getHeight())));
            }
        });
        resizable.setFixedAspectRatioEnabled(true);
        resizable.setMinimumEntitySize(new FloatSize3d(0.5f, 0.5f / perEyeAspect, 0f));
        resizable.setMaximumEntitySize(new FloatSize3d(8.0f, 8.0f / perEyeAspect, 0f));
        surfaceEntity.addComponent(resizable);

        // Since the 2D main panel is hidden, the Android XR system orbiter (with its Close button)
        // isn't available, so provide our own always-visible "Disconnect" button below the video.
        // It's a real Android Button hosted in a PanelEntity, parented to the quad so it follows when
        // the user moves the panel. Tapping (gaze + pinch) exits via the InteractableComponent's UP
        // event (the Button's own click is kept as a fallback); finish() is the same clean exit as
        // the controller quit combo.
        Button disconnectButton = new Button(activity);
        disconnectButton.setText("Disconnect");
        disconnectButton.setAllCaps(false);
        disconnectButton.setTextColor(Color.WHITE);
        disconnectButton.setTextSize(28f);
        disconnectButton.setBackgroundColor(0xCC222222);
        disconnectButton.setOnClickListener(v -> activity.finish());

        Pose buttonPose = new Pose(
                new Vector3(0.0f, -(panelHeightMeters / 2.0f) - 0.12f, 0.02f), Quaternion.Identity);
        disconnectPanel = PanelEntity.create(
                session, disconnectButton, new FloatSize2d(0.6f, 0.18f), "xr-disconnect",
                buttonPose, surfaceEntity);
        disconnectPanel.setEnabled(true);

        InteractableComponent interactable = InteractableComponent.create(
                session, activity.getMainExecutor(), (InputEvent event) -> {
                    if (event.getAction() == InputEvent.Action.UP) {
                        activity.finish();
                    }
                });
        disconnectPanel.addComponent(interactable);

        // Hand the decoder the entity's surface directly. The hardware decoder feeds the
        // SurfaceEntity with no extra GL pass, which keeps latency minimal. (An earlier "black
        // quad" symptom was misattributed to a codec/consumer buffer stall; the real causes were
        // the missing setParent above and the occluding 2D main panel — both fixed here — so the
        // direct path works and no GL bridge is needed.)
        videoSurface = surfaceEntity.getSurface();

        if (listener != null) {
            listener.onSurfaceReady(videoSurface);
        }
    }

    /**
     * Clamp a proposed panel pose so it can't be dragged closer than {@link #MIN_PANEL_DISTANCE_METERS}
     * from the activity-space origin (≈ the initial head position), preserving direction and rotation.
     */
    private static Pose clampToMinDistance(Pose pose) {
        Vector3 t = pose.getTranslation();
        float len = t.getLength();
        if (len >= MIN_PANEL_DISTANCE_METERS) {
            return pose;
        }
        if (len < 1e-4f) {
            // Degenerate (panel essentially at the origin) — push straight forward.
            return new Pose(new Vector3(0f, 0f, -MIN_PANEL_DISTANCE_METERS), pose.getRotation());
        }
        float s = MIN_PANEL_DISTANCE_METERS / len;
        Vector3 clamped = new Vector3(t.getX() * s, t.getY() * s, t.getZ() * s);
        return new Pose(clamped, pose.getRotation());
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
        disconnectPanel = null;
        session = null;
    }
}
