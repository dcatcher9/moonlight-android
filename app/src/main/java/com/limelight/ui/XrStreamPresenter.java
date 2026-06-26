package com.limelight.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

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
 * (with a minimum distance clamp). A floating control bar beneath it offers single-select
 * presentation modes (Normal/MONO &harr; SBS, which reshapes the quad to the matching aspect since
 * the surface always carries the same packed frame) plus a Disconnect action.
 * See docs/android-xr-sbs.md. Still open: session lifecycle on pause/resume (see {@link #onDestroy}).
 */
public class XrStreamPresenter {

    /** Nearest the user may drag the panel toward the eyes, in meters from the activity-space origin. */
    private static final float MIN_PANEL_DISTANCE_METERS = 0.75f;

    // Control-bar tile geometry (meters). Shared by build + reposition so the bar stays glued
    // beneath the quad as it changes size on a mode switch or a user resize.
    private static final float TILE_WIDTH_METERS = 0.34f;
    private static final float TILE_HEIGHT_METERS = 0.30f;
    private static final float TILE_SPACING_METERS = 0.40f;   // center-to-center
    private static final float BAR_GAP_METERS = 0.24f;        // quad bottom -> bar center
    private static final float BAR_Z_METERS = 0.02f;          // nudge toward viewer vs. the quad

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final OnSurfaceReadyListener listener;

    private Session session;
    private SurfaceEntity surfaceEntity;
    private Surface videoSurface;

    /** The control bar's panels (one PanelEntity per item), kept for teardown. */
    private final List<PanelEntity> controlPanels = new ArrayList<>();
    /** The control-bar items, kept so the mode group's selection highlight can be refreshed. */
    private final List<BarItem> barItems = new ArrayList<>();
    /** Comfortable default quad height in meters; mode switches keep this height and vary width. */
    private static final float DEFAULT_PANEL_HEIGHT_METERS = 1.2f;

    /** Which stereo mode the SurfaceEntity is currently presenting (defaults to MONO / flat). */
    private SurfaceEntity.StereoMode currentStereoMode = SurfaceEntity.StereoMode.MONO;

    // Quad aspect ratios for each presentation mode (the surface always carries the same packed
    // SBS frame; only the visible region differs). In SBS the compositor shows each eye the
    // half-width region — aspect (w/2)/h. In MONO it shows the whole frame to both eyes — aspect
    // w/h, i.e. twice as wide. The quad must match so the image isn't stretched.
    private float perEyeAspect;
    private float fullAspect;
    /** Kept so the resize affordance's bounds can be re-derived for the active mode's aspect. */
    private ResizableComponent resizable;

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

        // The surface always carries the packed SBS frame; the quad's aspect depends on the mode.
        // MONO (default) shows the whole frame to both eyes — full-frame aspect (w/h). SBS shows
        // each eye the half-width region — per-eye aspect (w/2)/h, i.e. half as wide. A mode switch
        // keeps the height and varies the width (see selectMode), so size from a default height here.
        // NOTE: if the stream shows black bars top/bottom in SBS, that is the host drawing a 16:9
        // image into the taller per-eye slot (letterbox baked into the stream) — the client aspect
        // can't remove it; stream wider so each eye is 16:9, or fix the host SBS layout.
        fullAspect = (float) prefConfig.width / prefConfig.height;
        perEyeAspect = fullAspect / 2.0f;
        float panelHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;
        float panelWidthMeters = panelHeightMeters * aspectFor(currentStereoMode);
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
                currentStereoMode);

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

        resizable = ResizableComponent.create(session, (ResizeEvent event) -> {
            if (event.getResizeState() == ResizeEvent.ResizeState.END) {
                FloatSize3d ns = event.getNewSize();
                surfaceEntity.setShape(
                        new SurfaceEntity.Shape.Quad(new FloatSize2d(ns.getWidth(), ns.getHeight())));
                // Keep the control bar glued beneath the (now resized) quad.
                repositionControlBar(ns.getHeight());
            }
        });
        resizable.setFixedAspectRatioEnabled(true);
        applyResizeBounds(aspectFor(currentStereoMode));
        surfaceEntity.addComponent(resizable);

        // Since the 2D main panel is hidden, the Android XR system orbiter (with its Close button)
        // isn't available, so we float our own control bar below the video — a row of icon+label
        // tiles, mirroring a virtual-desktop control strip. The mode tiles (Normal / SBS) form a
        // single-select group that flips the SurfaceEntity's StereoMode live; the Disconnect tile is
        // a one-shot action. The bar is parented to the quad so it follows when the user moves it.
        buildControlBar(panelHeightMeters);

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
     * Build the floating control bar below the video quad: a horizontal row of icon+label tiles.
     * The mode tiles ({@code Normal}/{@code SBS}) are a single-select group; {@code Disconnect} is a
     * one-shot action. Designed to be extended — append more {@link BarItem}s to grow the bar.
     *
     * @param videoHeightMeters the quad's height, used to place the bar just beneath it.
     */
    private void buildControlBar(float videoHeightMeters) {
        BarItem normal = new BarItem(
                activity.getString(R.string.xr_bar_normal),
                R.drawable.ic_xr_mode_normal, SurfaceEntity.StereoMode.MONO);
        BarItem sbs = new BarItem(
                activity.getString(R.string.xr_bar_sbs),
                R.drawable.ic_xr_mode_sbs, SurfaceEntity.StereoMode.SIDE_BY_SIDE);
        BarItem machines = new BarItem(
                activity.getString(R.string.xr_bar_machines),
                R.drawable.ic_computer, /* selectsMode= */ null);
        BarItem disconnect = new BarItem(
                activity.getString(R.string.xr_bar_disconnect),
                R.drawable.ic_xr_disconnect, /* selectsMode= */ null);

        normal.onTap = () -> selectMode(normal);
        sbs.onTap = () -> selectMode(sbs);
        machines.onTap = this::returnToMachineSelection;
        disconnect.onTap = activity::finish;

        barItems.clear();
        barItems.add(normal);
        barItems.add(sbs);
        barItems.add(machines);
        barItems.add(disconnect);

        for (BarItem item : barItems) {
            buildBarItemView(item);
            PanelEntity panel = PanelEntity.create(
                    session, item.root,
                    new FloatSize2d(TILE_WIDTH_METERS, TILE_HEIGHT_METERS),
                    "xr-bar-" + item.label, Pose.Identity, surfaceEntity);
            panel.setEnabled(true);
            controlPanels.add(panel);

            // The InteractableComponent's UP event reliably fires the tap (gaze + pinch). Note: the
            // platform does not draw a gaze hover highlight on these custom SurfaceEntity-child
            // panels (it does for the activity's main panel, which we hide), so there is no per-tile
            // hover animation — confirmed by reproducing the original Button recipe, which also
            // didn't highlight here. The video itself changing is the feedback for a mode switch.
            InteractableComponent interactable = InteractableComponent.create(
                    session, activity.getMainExecutor(), (InputEvent event) -> {
                        if (event.getAction() == InputEvent.Action.UP && item.onTap != null) {
                            item.onTap.run();
                        }
                    });
            panel.addComponent(interactable);
        }

        repositionControlBar(videoHeightMeters);
    }

    /** Lay the tiles out in a centered row just beneath the quad of the given height. */
    private void repositionControlBar(float videoHeightMeters) {
        int n = controlPanels.size();
        float barY = -(videoHeightMeters / 2.0f) - BAR_GAP_METERS;
        for (int i = 0; i < n; i++) {
            float x = (i - (n - 1) / 2.0f) * TILE_SPACING_METERS;
            controlPanels.get(i).setPose(
                    new Pose(new Vector3(x, barY, BAR_Z_METERS), Quaternion.Identity));
        }
    }

    /**
     * Apply a stereo mode chosen from the bar: switch the compositor's eye split and reshape the
     * quad to the mode's aspect (the surface always carries the same packed SBS frame — SBS shows
     * each eye the half-width region, MONO shows the whole double-wide frame). The quad's height is
     * preserved; only the width changes, so the screen keeps its vertical size across the switch.
     */
    private void selectMode(BarItem item) {
        if (item.selectsMode == null || surfaceEntity == null
                || item.selectsMode == currentStereoMode) {
            return;
        }
        surfaceEntity.setStereoMode(item.selectsMode);
        currentStereoMode = item.selectsMode;
        LimeLog.info("XR: stereo mode -> " + item.label);

        float aspect = aspectFor(currentStereoMode);
        SurfaceEntity.Shape shape = surfaceEntity.getShape();
        float height = (shape instanceof SurfaceEntity.Shape.Quad)
                ? ((SurfaceEntity.Shape.Quad) shape).getExtents().getHeight()
                : DEFAULT_PANEL_HEIGHT_METERS;
        float width = height * aspect;
        surfaceEntity.setShape(new SurfaceEntity.Shape.Quad(new FloatSize2d(width, height)));
        applyResizeBounds(aspect);
        repositionControlBar(height);
    }

    /**
     * End the stream and return to the machine-selection screen (PcView). The back stack is
     * PcView &rarr; AppView &rarr; Game, so CLEAR_TOP finishes AppView and this Game activity and
     * brings the existing PcView forward (SINGLE_TOP reuses it rather than recreating). Finishing
     * Game also tears the stream down via its normal lifecycle, same as Disconnect.
     */
    private void returnToMachineSelection() {
        Intent intent = new Intent(activity, PcView.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        activity.finish();
    }

    /** Quad aspect (width/height) for a mode: full frame for MONO, half-width region otherwise. */
    private float aspectFor(SurfaceEntity.StereoMode mode) {
        return mode == SurfaceEntity.StereoMode.MONO ? fullAspect : perEyeAspect;
    }

    /** Bound the resize affordance to a height range, deriving width from the active aspect. */
    private void applyResizeBounds(float aspect) {
        if (resizable == null) {
            return;
        }
        resizable.setMinimumEntitySize(new FloatSize3d(0.5f * aspect, 0.5f, 0f));
        resizable.setMaximumEntitySize(new FloatSize3d(6.0f * aspect, 6.0f, 0f));
    }

    /** Build the tile: icon centered above its label on a dark rounded background. Tap is handled by
     *  the panel's InteractableComponent (see {@link #buildControlBar}). */
    private void buildBarItemView(BarItem item) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int pad = dp(10);
        col.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xCC1E2630);
        col.setBackground(bg);

        ImageView icon = new ImageView(activity);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        icon.setImageResource(item.iconRes);
        icon.setColorFilter(Color.WHITE);

        TextView text = new TextView(activity);
        text.setText(item.label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        text.setLayoutParams(tp);

        col.addView(icon);
        col.addView(text);
        item.root = col;
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * One tile on the {@link #buildControlBar control bar}. Carries its icon+label, the tap action,
     * and — for the mode group — which {@link SurfaceEntity.StereoMode} it selects (null for actions
     * like Disconnect).
     */
    private final class BarItem {
        final String label;
        final int iconRes;
        /** Non-null for mode tiles (single-select group); null for one-shot action tiles. */
        final SurfaceEntity.StereoMode selectsMode;
        Runnable onTap;
        View root;

        BarItem(String label, int iconRes, SurfaceEntity.StereoMode selectsMode) {
            this.label = label;
            this.iconRes = iconRes;
            this.selectsMode = selectsMode;
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
        controlPanels.clear();
        barItems.clear();
        session = null;
    }
}
