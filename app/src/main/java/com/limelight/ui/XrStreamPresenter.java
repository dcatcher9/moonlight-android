package com.limelight.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.xr.runtime.Config;
import androidx.xr.runtime.DeviceTrackingMode;
import androidx.xr.runtime.Session;
import androidx.xr.runtime.SessionConfigureResult;
import androidx.xr.runtime.SessionCreateResult;
import androidx.xr.runtime.SessionCreateSuccess;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.FloatSize3d;
import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Ray;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.arcore.RenderViewpoint;
import androidx.xr.scenecore.Entity;
import androidx.xr.scenecore.EntityMoveListener;
import androidx.xr.scenecore.MovableComponent;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.ResizableComponent;
import androidx.xr.scenecore.ResizeEvent;
import androidx.xr.scenecore.Scene;
import androidx.xr.scenecore.ScenePose;
import androidx.xr.scenecore.SessionExt;
import androidx.xr.scenecore.Space;
import androidx.xr.scenecore.SpatialCapability;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.LimeLog;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.Game;
import com.limelight.binding.video.StreamPerformanceSnapshot;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Stereo3DRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Presentation owner for the single XR route ({@code MODE_XR}). Fresh host connections start in
 * Normal; only a host-confirmed resume of the same app restores its last successful per-machine/app
 * presentation preference. The user can switch Host SBS Raw/AI and Client SBS AI from the
 * in-headset control bar.
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
 * presentation modes (Normal / Host SBS / Client SBS) plus Machines and Disconnect actions.
 * Switching presentations re-pins the surface and, in the host AI depth mode, drives
 * the host's SBS pipeline on/off. See docs/android-xr-sbs.md. Session teardown and durable mode
 * persistence are handled in {@link #onDestroy()}.
 */
@SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
public class XrStreamPresenter {

    /** Nearest the user may drag the panel toward the eyes, in meters from the activity-space origin. */
    private static final float MIN_PANEL_DISTANCE_METERS = 0.4f;

    // Control-bar tile geometry (meters). Shared by build + reposition so the bar stays glued
    // beneath the quad as it changes size on a mode switch or a user resize.
    private static final float BAR_HEIGHT_METERS = 0.21f;     // also the per-tile size (square tiles)
    private static final float BAR_DIVIDER_METERS = 0.05f;    // extra width for the group divider
    private static final float BAR_GAP_METERS = 0.24f;        // quad bottom -> bar center
    private static final float BAR_Z_METERS = 0.02f;          // nudge toward viewer vs. the quad
    private static final int TILE_IDLE_COLOR = 0xCC1E2630;    // resting tile fill
    private static final int TILE_ACTIVE_COLOR = 0xFF2C72E0;  // active (selected) mode tile fill
    private static final int TILE_ACTIVE_BORDER_COLOR = 0xFFFFFFFF;  // border on the active mode tile
    // Keep Stats compact and place it beside the video. A single column is easier to scan in-headset
    // and cuts the Android panel raster from the former 9.1 MP two-column surface to 2.8 MP.
    private static final float STATS_WIDTH_METERS = 1.40f;
    private static final float STATS_HEIGHT_METERS = 1.05f;
    private static final int STATS_RASTER_WIDTH = 1920;
    private static final int STATS_RASTER_HEIGHT = 1440;
    // SceneCore alpha16 rasterizes at roughly 1728 px/m. Scale this capped 4:3 raster to the stated
    // physical size, and inversely scale Stats-only typography to preserve its apparent size.
    private static final float STATS_ENTITY_SCALE = 1.26f;
    private static final float STATS_CONTENT_SCALE = 1.0f / STATS_ENTITY_SCALE;
    private static final float STATS_GAP_METERS = 0.10f;
    private static final float STATS_MIN_INWARD_YAW_DEGREES = 8.0f;
    private static final float STATS_MAX_INWARD_YAW_DEGREES = 50.0f;
    private static final float STATS_HEAD_CLEARANCE_METERS = 0.45f;

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }

    public interface StatsVisibilityListener {
        void onStatsVisibilityChanged(boolean visible);
    }

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final OnSurfaceReadyListener listener;
    private final StatsVisibilityListener statsVisibilityListener;
    private final XrViewStateStore viewStateStore;

    private Session session;
    private SurfaceEntity surfaceEntity;
    /** Written by the UI/SceneCore thread and read by GLSurfaceView's EGL thread. */
    private volatile Surface videoSurface;

    /** The single PanelEntity hosting the whole row of buttons. */
    private PanelEntity barPanel;
    /** The control-bar items (one clickable tile each, all hosted in {@link #barPanel}). */
    private final List<BarItem> barItems = new ArrayList<>();

    /** Compact performance-stats panel wrapped inward from the screen's right edge. */
    private PanelEntity statsPanel;
    private TextView statsTitle;
    private TableLayout statsTable;
    private boolean reuseStatsRows;
    private int primaryStatsRowCursor;
    private BarItem statsItem;
    private volatile boolean statsVisible;
    private final DevicePerformanceSampler devicePerformanceSampler =
            new DevicePerformanceSampler();

    /** Small centered overlay shown in front of the video while the host prepares its engine or
     *  initializes the stream-specific 3D pipeline
     *  (driven by the host's 0x3006 depth-status push via {@link #onDepthStatus}). */
    private PanelEntity depthStatusPanel;
    private TextView depthStatusText;
    private static final float DEPTH_STATUS_WIDTH_METERS = 0.9f;
    private static final float DEPTH_STATUS_HEIGHT_METERS = 0.11f;

    /** Centered in-headset replacement for platform toasts, whose gravity is ignored on API 30+. */
    private PanelEntity transientMessagePanel;
    private TextView transientMessageText;
    private static final float TRANSIENT_MESSAGE_WIDTH_METERS = 1.2f;
    private static final float TRANSIENT_MESSAGE_HEIGHT_METERS = 0.18f;
    private final android.os.Handler transientMessageHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideTransientMessageRunnable = this::hideTransientMessage;

    private static final int STATS_LABEL_COLOR = 0xFF9FB3C8;  // muted blue-grey for row labels
    private static final int STATS_VALUE_COLOR = 0xFFFFFFFF;  // white for values
    private static final int STATS_ON_COLOR = 0xFF5CD65C;     // green for "on"/HDR active
    private static final int STATS_WARN_COLOR = 0xFFE0B020;
    private static final int STATS_ERROR_COLOR = 0xFFE05A5A;
    private static final int STATS_UNAVAILABLE_COLOR = 0xFF71808F;
    private static final float STATS_TEXT_SP = 21f;
    /** Comfortable cinema-preset quad height in meters; mode switches keep this height and vary
     *  width. Shared by the initial placement and Cinema View so both land at the same size. Tune
     *  by feel on the headset (at the ~2 m default distance, 2.0 m ≈ a large cinema screen). */
    private static final float DEFAULT_PANEL_HEIGHT_METERS =
            XrViewStateStore.DEFAULT_HEIGHT_METERS;
    private float panelHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;

    public enum PresenterMode {
        NORMAL,
        HOST_SBS_RAW,
        HOST_SBS_AI,
        CLIENT_SBS_AI
    }

    /** Host SBS AI makes the host emit a packed 2W' x H' side-by-side frame. */
    private static boolean isHostDepthPresenterMode(PresenterMode mode) {
        return mode == PresenterMode.HOST_SBS_AI;
    }

    /** True when a direct-decoder mode switch crosses the Host SBS AI packed-size boundary. */
    static boolean requiresHostSurfaceResize(PresenterMode previousMode, PresenterMode nextMode) {
        return isHostDepthPresenterMode(previousMode) != isHostDepthPresenterMode(nextMode);
    }

    /** True only when the decoder target or encoded dimensions change across the transition. */
    static boolean requiresDecoderTransition(PresenterMode previousMode, PresenterMode nextMode) {
        boolean crossesClientRenderer = (previousMode == PresenterMode.CLIENT_SBS_AI)
                != (nextMode == PresenterMode.CLIENT_SBS_AI);
        return crossesClientRenderer || requiresHostSurfaceResize(previousMode, nextMode);
    }

    /** Which mode the SurfaceEntity is currently presenting (defaults to NORMAL). */
    private PresenterMode currentPresenterMode = PresenterMode.NORMAL;
    /** A saved Client SBS presentation to re-apply once the decoder has produced a valid Normal
     *  frame. Restoring before then would split a still-mono startup frame. */
    private PresenterMode deferredPresenterMode = PresenterMode.NORMAL;

    /** Debounce window for mode-tile taps: a switch starts an async surface handoff, so ignore a
     *  second tap that lands within this window (double-tap / impatient re-tap). */
    private static final long MODE_SWITCH_DEBOUNCE_MS = 600L;
    private long lastModeSwitchMs;
    private boolean modeSwitchInProgress;
    /** Successful surface handoff awaiting the fresh-IDR output before it may be persisted/shown. */
    private PresenterMode pendingDecoderTransitionMode;
    /** Mode changes resize or hand off the decoder surface, so they remain disabled until the
     *  decoder confirms that the initial stream frame has reached the XR surface. */
    private boolean streamPresentationReady;

    // Quad aspect ratios (width/height) for the presentations, so the image isn't stretched.
    //  - fullAspect = w/h: the whole frame shown to both eyes (Normal/MONO), and the per-eye view
    //    in Host SBS AI and Client SBS AI, whose packed frame is a
    //    proportionally-scaled 2D so each eye keeps the 2D aspect.
    //  - perEyeAspect: Raw host SBS only — its packed frame is a fixed 2W-wide side-by-side at the
    //    negotiated width, so each eye is half as wide, (w/2)/h. (For the depth modes this is set
    //    equal to fullAspect; see init.)
    private float perEyeAspect;
    private float fullAspect;
    /** Kept so the resize affordance's bounds can be re-derived for the active mode's aspect. */
    private ResizableComponent resizable;

    public XrStreamPresenter(Activity activity, PreferenceConfiguration prefConfig,
                             OnSurfaceReadyListener listener,
                             StatsVisibilityListener statsVisibilityListener) {
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.listener = listener;
        this.statsVisibilityListener = statsVisibilityListener;
        this.viewStateStore = new XrViewStateStore(activity, activity.getIntent());
        restoreViewState();
        // On a host-confirmed resume, restore direct Host/Raw presentation immediately. Client SBS
        // is deferred until after frame 1 because it requires a live decoder-to-GL handoff. A
        // fresh connection's state store returns Normal regardless of any older saved mode.
    }

    /**
     * Create the XR session and SBS surface entity, then notify the listener with the surface.
     * Must be called on the main thread (SceneCore session creation is Activity-bound).
     */
    public boolean init() {
        SessionCreateResult result = Session.create(activity);
        if (!(result instanceof SessionCreateSuccess)) {
            LimeLog.severe("XR session creation failed: " + result.getClass().getSimpleName());
            return false;
        }
        session = ((SessionCreateSuccess) result).getSession();

        // Enable device (head) tracking so the "Cinema View" tile can place the panel in front of
        // the user's current head pose (via RenderViewpoint). The default session has it DISABLED,
        // which is why RenderViewpoint.mono(session) was null. Head pose needs no runtime permission.
        try {
            Config cfg = session.getConfig();
            SessionConfigureResult cr = session.configure(new Config.Builder(cfg)
                    .setDeviceTracking(DeviceTrackingMode.LAST_KNOWN)
                    .build());
            LimeLog.info("XR: device-tracking configure -> " + cr.getClass().getSimpleName());
        } catch (Throwable t) {
            LimeLog.warning("XR: device-tracking configure failed: " + t);
        }

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

        // This app has no non-spatial rendering route. Continuing would hide the main panel and
        // start decoding into a SurfaceEntity that cannot be shown correctly.
        if (!has3d) {
            session = null;
            return false;
        }

        // A saved direct Host/Raw preference can be correct from frame 1. Switching to a stereo
        // mode changes BOTH the compositor split AND the
        // frame the host sends — Host SBS AI -> a packed 2W' x H' side-by-side frame (capped to
        // the encoder max), Client SBS -> on-device depth packed at the negotiated stream size.
        // selectMode
        // re-pins the surface to the target frame size (see setHostSurfaceSize/setClientSbsSurfaceSize).
        //
        // Quad aspect handling differs by host-SBS flavor:
        //  - Host SBS AI: starts 2D (W x H) and switches to a packed SBS that is a
        //    proportionally-scaled 2D, so the per-eye aspect equals the 2D aspect (full == per-eye);
        //    the surface is re-pinned per mode (see setHostSurfaceSize).
        //  - Raw host SBS: the frame is an already-packed 2W-wide side-by-side at the negotiated
        //    width, so MONO shows the whole w/h frame and SBS shows each eye a half-width slot.
        fullAspect = (float) prefConfig.width / prefConfig.height;
        perEyeAspect = prefConfig.isHostDoubledWidthMode() ? fullAspect : (fullAspect / 2.0f);
        float panelWidthMeters = panelHeightMeters * aspectFor(currentPresenterMode);
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
                stereoModeFor(currentPresenterMode),
                SurfaceEntity.SuperSampling.NONE);

        // A saved Host SBS AI preference asks Apollo to start packed SBS in the launch/resume HTTP
        // transaction, so frame 1 must already target the matching packed surface size.
        // Raw SBS uses the negotiated W x H frame and only changes compositor interpretation.
        if (isHostDepthPresenterMode(currentPresenterMode)) {
            surfaceEntity.setSurfacePixelDimensions(
                    new IntSize2d(hostSbsPackedWidth(), hostSbsPackedHeight()));
        } else {
            surfaceEntity.setSurfacePixelDimensions(new IntSize2d(prefConfig.width, prefConfig.height));
        }
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
                session, /* scaleInZ= */ false, ContextCompat.getMainExecutor(activity),
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
                        repositionStatsPanel();
                    }
                });
        surfaceEntity.addComponent(movable);

        // The resizable affordance lets the user grab a corner to scale. Keep the aspect ratio
        // fixed so the SBS halves stay aligned, and apply the new size to the quad when resize ends.

        resizable = ResizableComponent.create(session, (ResizeEvent event) -> {
            if (event.getResizeState() == ResizeEvent.ResizeState.END) {
                FloatSize3d ns = event.getNewSize();
                panelHeightMeters = XrViewStateStore.clampHeight(ns.getHeight());
                surfaceEntity.setShape(
                        new SurfaceEntity.Shape.Quad(new FloatSize2d(
                                panelHeightMeters * aspectFor(currentPresenterMode),
                                panelHeightMeters)));
                // Keep the control bar glued beneath the (now resized) quad.
                repositionControlBar(panelHeightMeters);
                viewStateStore.saveHeight(panelHeightMeters);
            }
        });
        resizable.setFixedAspectRatioEnabled(true);
        applyResizeBounds(aspectFor(currentPresenterMode));
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
        return true;
    }

    /**
     * Build the floating control bar below the video quad: a horizontal row of icon+label tiles,
     * split by a divider into a single-select mode group ({@code Normal}/{@code SBS}) and one-shot
     * actions ({@code Machines}/{@code Disconnect}). Extend by appending more {@link BarItem}s.
     *
     * @param videoHeightMeters the quad's height, used to place the bar just beneath it.
     */
    private void buildControlBar(float videoHeightMeters) {
        BarItem normal = new BarItem(
                activity.getString(R.string.xr_bar_normal),
                R.drawable.ic_xr_mode_normal, PresenterMode.NORMAL);
        BarItem clientSbsAi = new BarItem(
                activity.getString(R.string.xr_bar_client_sbs_ai),
                R.drawable.ic_xr_mode_client_sbs, PresenterMode.CLIENT_SBS_AI);
        BarItem hostSbsRaw = new BarItem(
                activity.getString(R.string.xr_bar_host_sbs_raw),
                R.drawable.ic_xr_mode_host_sbs, PresenterMode.HOST_SBS_RAW);
        BarItem hostSbsAi = new BarItem(
                activity.getString(R.string.xr_bar_host_sbs_ai),
                R.drawable.ic_xr_mode_host_sbs, PresenterMode.HOST_SBS_AI);
        BarItem stats = new BarItem(
                activity.getString(R.string.xr_bar_stats),
                R.drawable.ic_xr_stats, /* selectsMode= */ null);
        BarItem cinemaView = new BarItem(
                activity.getString(R.string.xr_bar_cinema_view),
                R.drawable.ic_xr_cinema_view, /* selectsMode= */ null);
        BarItem dump = new BarItem(
                activity.getString(R.string.xr_bar_dump),
                R.drawable.ic_xr_dump, /* selectsMode= */ null);
        BarItem machines = new BarItem(
                activity.getString(R.string.xr_bar_machines),
                R.drawable.ic_computer, /* selectsMode= */ null);
        BarItem disconnect = new BarItem(
                activity.getString(R.string.xr_bar_disconnect),
                R.drawable.ic_xr_disconnect, /* selectsMode= */ null);

        normal.onTap = () -> selectMode(normal);
        clientSbsAi.onTap = () -> selectMode(clientSbsAi);
        hostSbsRaw.onTap = () -> selectMode(hostSbsRaw);
        hostSbsAi.onTap = () -> selectMode(hostSbsAi);
        stats.onTap = this::toggleStats;
        cinemaView.onTap = this::applyCinemaView;
        dump.onTap = XrStreamPresenter::requestHostDebugDump;
        machines.onTap = this::returnToMachineSelection;
        disconnect.onTap = activity::finish;
        statsItem = stats;

        barItems.clear();
        barItems.add(normal);
        barItems.add(hostSbsRaw);
        barItems.add(hostSbsAi);
        barItems.add(clientSbsAi);
        barItems.add(stats);
        barItems.add(cinemaView);
        barItems.add(dump);
        barItems.add(machines);
        barItems.add(disconnect);

        // One panel hosting a horizontal row of clickable tiles — like a normal toolbar. This is what
        // makes the platform draw the per-tile gaze highlight: a single panel whose View hierarchy
        // holds multiple clickable views highlights each one (the way several FABs on a 2D screen do),
        // whereas one interactable child PanelEntity per tile did NOT. Each tile handles its own tap.
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        boolean prevWasMode = false;
        boolean first = true;
        for (BarItem item : barItems) {
            boolean isMode = item.selectsMode != null;
            // Divider between the mode group (Normal/SBS) and the action group (Machines/Disconnect).
            if (!first && prevWasMode && !isMode) {
                bar.addView(makeDivider());
            }
            View tile = buildBarItemView(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            int m = dp(2);
            lp.setMargins(m, m, m, m);
            bar.addView(tile, lp);
            item.root = tile;
            if (isMode) {
                item.setEnabled(streamPresentationReady);
            }
            prevWasMode = isMode;
            first = false;
        }

        // Bake the initial highlights into the views before the panel is created.
        statsVisible = prefConfig.enablePerfOverlay;
        if (statsVisible) {
            devicePerformanceSampler.resetCpuBaseline();
        }
        updateModeSelection();
        statsItem.setSelected(statsVisible);

        // Width scales with the tile count so each tile stays square (tile size = bar height),
        // plus a little for the divider — adding tiles widens the bar instead of squeezing them.
        float barWidth = controlBarWidthMeters();
        barPanel = PanelEntity.create(
                session, bar, new FloatSize2d(barWidth, BAR_HEIGHT_METERS),
                "xr-control-bar", barPose(videoHeightMeters), surfaceEntity);
        barPanel.setEnabled(true);

        if (statsVisible) {
            createStatsPanel(videoHeightMeters);
        }
        createDepthStatusPanel(videoHeightMeters);
        createTransientMessagePanel();
    }

    /** Compact single-column performance panel beside the video. */
    private void createStatsPanel(float videoHeightMeters) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        // Fully opaque content avoids blending a second large translucent surface over video.
        root.setBackgroundColor(0xFF101418);
        int p = statsDp(14);
        root.setPadding(p, p, p, p);

        statsTitle = new TextView(activity);
        statsTitle.setText(R.string.xr_stats_title);
        statsTitle.setTextColor(TILE_ACTIVE_COLOR);
        statsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                (STATS_TEXT_SP + 3f) * STATS_CONTENT_SCALE);
        statsTitle.setTypeface(statsTitle.getTypeface(), android.graphics.Typeface.BOLD);
        statsTitle.setPadding(0, 0, 0, statsDp(6));
        root.addView(statsTitle);

        statsTable = createStatsTable();
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(statsTable, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        statsPanel = PanelEntity.create(
                session, root, new IntSize2d(STATS_RASTER_WIDTH, STATS_RASTER_HEIGHT),
                "xr-stats", statsPose(videoHeightMeters), surfaceEntity);
        statsPanel.setScale(STATS_ENTITY_SCALE);
        statsPanel.setEnabled(statsVisible);
    }

    private TableLayout createStatsTable() {
        TableLayout table = new TableLayout(activity);
        table.setColumnShrinkable(0, true);
        table.setColumnShrinkable(1, true);
        table.setColumnStretchable(1, true);
        return table;
    }

    /**
     * Centered overlay that appears while the host prepares depth and disappears once depth is
     * live. It distinguishes process-wide engine preparation from per-stream GPU setup and is
     * driven entirely by the host's {@link #onDepthStatus} phase pushes.
     */
    private void createDepthStatusPanel(float videoHeightMeters) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xE6101418);
        int p = dp(12);
        root.setPadding(p, p, p, p);

        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(28), dp(28));
        sp.setMargins(0, 0, dp(14), 0);
        root.addView(spinner, sp);

        depthStatusText = new TextView(activity);
        depthStatusText.setTextColor(Color.WHITE);
        depthStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        root.addView(depthStatusText);

        depthStatusPanel = PanelEntity.create(
                session, root, new FloatSize2d(DEPTH_STATUS_WIDTH_METERS, DEPTH_STATUS_HEIGHT_METERS),
                "xr-depth-status", depthStatusPose(), surfaceEntity);
        depthStatusPanel.setEnabled(false);  // hidden until the host reports a loading phase
    }

    /** Center the transient status on the video and nudge it toward the viewer as an overlay. */
    private Pose depthStatusPose() {
        return new Pose(new Vector3(0.0f, 0.0f, BAR_Z_METERS), Quaternion.Identity);
    }

    private void createTransientMessagePanel() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xE6101418);
        int padding = dp(14);
        root.setPadding(padding, padding, padding, padding);

        transientMessageText = new TextView(activity);
        transientMessageText.setTextColor(Color.WHITE);
        transientMessageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        transientMessageText.setGravity(Gravity.CENTER);
        transientMessageText.setMaxLines(3);
        root.addView(transientMessageText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        transientMessagePanel = PanelEntity.create(
                session, root,
                new FloatSize2d(TRANSIENT_MESSAGE_WIDTH_METERS,
                        TRANSIENT_MESSAGE_HEIGHT_METERS),
                "xr-transient-message", depthStatusPose(), surfaceEntity);
        transientMessagePanel.setEnabled(false);
    }

    /** Shows a message centered just in front of the video. Must be called on the UI thread. */
    public boolean showTransientMessage(CharSequence message, long durationMs) {
        if (transientMessagePanel == null || transientMessagePanel.isDisposed()
                || transientMessageText == null || message == null) {
            return false;
        }
        transientMessageHandler.removeCallbacks(hideTransientMessageRunnable);
        transientMessageText.setText(message);
        transientMessagePanel.setEnabled(true);
        transientMessageHandler.postDelayed(
                hideTransientMessageRunnable, Math.max(1L, durationMs));
        return true;
    }

    private void hideTransientMessage() {
        if (transientMessagePanel != null && !transientMessagePanel.isDisposed()) {
            transientMessagePanel.setEnabled(false);
        }
    }

    private final android.os.Handler depthStatusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int depthStatusPendingPhase;
    private int depthStatusPhase;
    // Method reference (not a field lambda) so the initializer doesn't read the not-yet-assigned
    // final `activity` field; the body runs later, once everything is constructed.
    private final Runnable showDepthStatusRunnable = this::showDepthStatusNow;

    private void showDepthStatusNow() {
        if (depthStatusPanel != null && !depthStatusPanel.isDisposed() && isDepthBusy()) {
            if (depthStatusText != null) {
                int message = depthStatusMessage(depthStatusPendingPhase);
                depthStatusText.setText(activity.getString(message));
            }
            depthStatusPanel.setEnabled(true);
        }
    }

    private boolean isDepthBusy() {
        return depthStatusPhase == 1 || depthStatusPhase == 3;
    }

    private static int depthStatusMessage(int phase) {
        return phase == 3 ? R.string.xr_depth_initializing : R.string.xr_depth_loading;
    }

    /**
     * Host depth-engine phase push (Apollo 0x3006), on the UI thread: 0 = idle/failure,
     * 1 = engine loading/building, 2 = ready, 3 = device-pipeline initialization. Show the
     * matching progress indicator on phase 1/3 and hide it on ready/idle. The show is
     * delayed ~600 ms so an already-cached model (which reports loading→ready almost instantly on
     * every switch) doesn't flash the panel; only genuinely slow first-use loads surface it.
     */
    public void onDepthStatus(int phase) {
        if (depthStatusPanel == null) {
            return;
        }
        depthStatusPhase = phase;
        depthStatusHandler.removeCallbacks(showDepthStatusRunnable);
        if (phase == 1 || phase == 3) {
            depthStatusPendingPhase = phase;
            depthStatusHandler.postDelayed(showDepthStatusRunnable, 600);
        } else {
            depthStatusPanel.setEnabled(false);
        }
    }

    public boolean isStatsVisible() {
        return statsVisible;
    }

    /** Toggle the performance-stats panel; also flips the pref so the decoder emits perf text. */
    public void toggleStats() {
        statsVisible = !statsVisible;
        prefConfig.enablePerfOverlay = statsVisible;
        if (statsVisible) {
            // Start CPU and Client-SBS windows at the moment the panel opens. Otherwise the first
            // value would average all work performed while the panel was hidden.
            devicePerformanceSampler.resetCpuBaseline();
            if (statsPanel == null) {
                createStatsPanel(panelHeightMeters);
            }
            repositionStatsPanel();
        }
        if (statsVisibilityListener != null) {
            statsVisibilityListener.onStatsVisibilityChanged(statsVisible);
        }
        if (statsPanel != null) {
            statsPanel.setEnabled(statsVisible);
        }
        if (statsItem != null) {
            statsItem.setSelected(statsVisible);
        }
    }

    /**
     * Rebuilds the XR table from typed snapshots captured on the same decoder tick. Rates are
     * completed work per second; latency rows are average / maximum for that sampling window.
     *
     * @param hdrActive whether the negotiated stream is actually HDR, rather than merely requested
     */
    public void setStats(StreamPerformanceSnapshot stream,
                         Stereo3DRenderer.ClientSbsPerformanceSnapshot clientSbs,
                         boolean hdrActive) {
        final boolean panelVisible = statsVisible && statsTable != null;
        final boolean clientSbsStatsActive = currentPresenterMode == PresenterMode.CLIENT_SBS_AI
                && clientSbs != null && clientSbs.active;
        final boolean clientSbsLoggingActive = prefConfig.enablePerfLogging
                && clientSbsStatsActive && clientSbs.backend.startsWith("LITERT_");
        if (!panelVisible && !prefConfig.enablePerfLogging) {
            return;
        }

        DevicePerformanceSampler.Snapshot device = (panelVisible || clientSbsLoggingActive)
                ? devicePerformanceSampler.sample() : null;
        if (clientSbsLoggingActive) {
            String depthHealth = clientSbs.depthHealthAvailable
                    ? String.format(Locale.US, "valid=%.1f%% range=%.4f pop=%.3f collapsed=%s",
                            clientSbs.validDepthFraction * 100.0f,
                            clientSbs.effectiveDepthRangeWidth,
                            clientSbs.stereoPopStrength,
                            clientSbs.rawDepthRangeCollapsed)
                    : "unavailable";
            // Machine-readable A/B output is intentionally separate from the visible panel. Log
            // formatting and logcat I/O are enabled only by the explicit performance-log switch.
            LimeLog.info(String.format(Locale.US,
                    "ClientSbsPerf %.2fs"
                            + " | model=%s input=%dx%d backend=%s priority=%s"
                            + " | stream decoder=%s sequence=%.1f received=%.1f"
                            + " output=%.1f release=%.1f presented=%.1f"
                            + " decode_ms=%.2f/%.2f"
                            + " | client_fps latch=%.1f depth=%.1f output=%.1f"
                            + " | litert_ms=%.2f/%.2f depth_age_ms=%.2f/%.2f"
                            + " | gl_gpu_ms pack=%.2f color=%.2f profile=%.2f compose=%.2f"
                            + " | faults color_busy=%d flat=%d"
                            + " | depth %s | thermal=%d gpu_busy=%s gpu_clock_mhz=%s",
                    clientSbs.windowSeconds,
                    clientSbs.modelId,
                    clientSbs.modelInputWidth,
                    clientSbs.modelInputHeight,
                    clientSbs.backend,
                    clientSbs.gpuPriorityHint.toLowerCase(Locale.US),
                    stream != null ? stream.getDecoderName() : "n/a",
                    stream != null ? stream.getStreamSequenceFps() : 0.0f,
                    stream != null ? stream.getReceivedFps() : 0.0f,
                    stream != null ? stream.getDecoderOutputFps() : 0.0f,
                    stream != null ? stream.getDecoderReleaseFps() : 0.0f,
                    stream != null ? stream.getDecoderPresentedFps() : Float.NaN,
                    stream != null ? stream.getDecodeAverageMs() : 0.0f,
                    stream != null ? stream.getDecodeMaxMs() : 0.0f,
                    clientSbs.glLatchFps,
                    clientSbs.depthAdoptFps,
                    clientSbs.glOutputSubmitFps,
                    clientSbs.averageNativeLiteRtRunWallMs,
                    clientSbs.maxNativeLiteRtRunWallMs,
                    clientSbs.averageDepthResultAgeMs,
                    clientSbs.maxDepthResultAgeMs,
                    clientSbs.averageGpuModelInputMs,
                    clientSbs.averageGpuMatchedColorMs,
                    clientSbs.averageGpuDepthProfileMs,
                    clientSbs.averageGpuSbsComposeMs,
                    clientSbs.colorSlotBusySkips,
                    clientSbs.flatSbsOutputs,
                    depthHealth,
                    clientSbs.thermalStatus,
                    device != null && device.deviceGpuUtilizationAvailable
                            ? String.format(Locale.US, "%.1f%%",
                                    device.deviceGpuUtilizationPercent)
                            : "n/a",
                    device != null && device.gpuFrequencyAvailable
                            ? String.format(Locale.US, "%.0f",
                                    device.gpuFrequencyHz / 1_000_000.0)
                            : "n/a"));
        } else if (prefConfig.enablePerfLogging && stream != null) {
            LimeLog.info(String.format(Locale.US,
                    "DecoderPerf %.2fs | mode=%s decoder=%s"
                            + " | fps sequence=%.1f received=%.1f output=%.1f"
                            + " release=%.1f presented=%.1f | decode_ms=%.2f/%.2f",
                    stream.getElapsedMs() / 1000.0f,
                    presenterModeName(currentPresenterMode),
                    stream.getDecoderName(),
                    stream.getStreamSequenceFps(),
                    stream.getReceivedFps(),
                    stream.getDecoderOutputFps(),
                    stream.getDecoderReleaseFps(),
                    stream.getDecoderPresentedFps(),
                    stream.getDecodeAverageMs(),
                    stream.getDecodeMaxMs()));
        }


        if (!panelVisible) {
            return;
        }

        // Head tracking is sampled only on this already-throttled Stats update. No pose polling runs
        // while the panel is hidden or in the per-frame video path.
        repositionStatsPanel();
        beginStatsRows();

        float streamWindowSeconds = stream != null ? stream.getElapsedMs() / 1000.0f : 0.0f;
        float clientSbsWindowSeconds = clientSbsStatsActive ? clientSbs.windowSeconds : 0.0f;
        if (statsTitle != null) {
            statsTitle.setText(formatStatsTitle(
                    currentPresenterMode, streamWindowSeconds, clientSbsWindowSeconds));
        }

        addStatsSection("STREAM");
        if (stream != null) {
            addStatsRow("Video",
                    String.format(Locale.US, "%dx%d | %s %s | %s",
                            stream.getSourceWidth(), stream.getSourceHeight(),
                            hdrActive ? "HDR" : "SDR", stream.getVideoRange(),
                            compactDecoderName(stream.getDecoderName())),
                    hdrActive ? STATS_ON_COLOR : STATS_VALUE_COLOR);
        } else {
            addStatsRow("Video", "Waiting for decoder sample", STATS_UNAVAILABLE_COLOR);
        }

        if (stream != null) {
            addStatsRow("FPS sender / receive",
                    String.format(Locale.US, "%.1f / %.1f",
                            stream.getStreamSequenceFps(), stream.getReceivedFps()),
                    STATS_VALUE_COLOR);
            String presentedFps = Float.isFinite(stream.getDecoderPresentedFps())
                    ? String.format(Locale.US, "%.1f", stream.getDecoderPresentedFps()) : "n/a";
            addStatsRow("Decoder output / release / surface",
                    String.format(Locale.US, "%.1f / %.1f / %s",
                            stream.getDecoderOutputFps(), stream.getDecoderReleaseFps(),
                            presentedFps),
                    Float.isFinite(stream.getDecoderPresentedFps())
                            ? STATS_VALUE_COLOR : STATS_UNAVAILABLE_COLOR);

            String bandwidth = stream.hasBandwidth()
                    ? String.format(Locale.US, "%.1f Mbps", stream.getBandwidthMbps()) : "n/a";
            String rtt = stream.hasEstimatedRtt()
                    ? String.format(Locale.US, "%d ms", stream.getEstimatedRttMs())
                    : "n/a";
            addStatsRow("Network",
                    String.format(Locale.US, "%s | loss %.2f%% | RTT %s",
                            bandwidth, stream.getNetworkLossPercent(), rtt),
                    stream.getNetworkLossPercent() > 1.0f
                            ? STATS_WARN_COLOR : STATS_VALUE_COLOR);

            String hostLatency = stream.hasHostProcessingLatency()
                    ? String.format(Locale.US, "%.2f / %.2f",
                            stream.getHostProcessingAverageMs(),
                            stream.getHostProcessingMaxMs())
                    : "n/a";
            String decodeLatency = stream.hasDecodeLatency()
                    ? String.format(Locale.US, "%.2f / %.2f",
                            stream.getDecodeAverageMs(), stream.getDecodeMaxMs())
                    : "n/a";
            addStatsRow("Host / decode avg / max",
                    "host " + hostLatency + " | decode " + decodeLatency + " ms",
                    stream.hasDecodeLatency() ? STATS_VALUE_COLOR : STATS_UNAVAILABLE_COLOR);
        }

        addStatsSection("DEVICE");
        if (device.appCpuAvailable) {
            addStatsRow("App CPU",
                    String.format(Locale.US, "%.2f cores", device.appCpuCoreEquivalent),
                    STATS_VALUE_COLOR);
        } else {
            addStatsRow("App CPU", "Warming up", STATS_UNAVAILABLE_COLOR);
        }

        String gpuBusy = device.deviceGpuUtilizationAvailable
                ? String.format(Locale.US, "%.1f%%", device.deviceGpuUtilizationPercent) : "n/a";
        String gpuClock = device.gpuFrequencyAvailable
                ? String.format(Locale.US, "%.0f MHz", device.gpuFrequencyHz / 1_000_000.0)
                : "n/a";
        addStatsRow("GPU busy / clock", gpuBusy + " | " + gpuClock,
                device.deviceGpuUtilizationAvailable
                        ? utilizationColor(device.deviceGpuUtilizationPercent)
                        : STATS_UNAVAILABLE_COLOR);
        if (clientSbsStatsActive) {
            addStatsRow("Thermal", thermalStatusName(clientSbs.thermalStatus),
                    thermalStatusColor(clientSbs.thermalStatus));
        }

        if (currentPresenterMode == PresenterMode.CLIENT_SBS_AI) {
            addStatsSection("CLIENT SBS");
            if (!clientSbsStatsActive) {
                addStatsRow("Depth pipeline", "Initializing", STATS_UNAVAILABLE_COLOR);
            } else {
                addStatsRow("Depth model",
                        clientSbs.modelId + " | " + clientSbs.modelInputWidth + "x"
                                + clientSbs.modelInputHeight + " | "
                                + depthBackendName(clientSbs.backend),
                        backendColor(clientSbs.backend));
                addStatsRow("Latch / depth / output FPS",
                        String.format(Locale.US, "%.1f / %.1f / %.1f",
                                clientSbs.glLatchFps, clientSbs.depthAdoptFps,
                                clientSbs.glOutputSubmitFps),
                        STATS_VALUE_COLOR);
                addStatsRow("Inference avg / max",
                        String.format(Locale.US, "LiteRT %.2f / %.2f ms",
                                clientSbs.averageNativeLiteRtRunWallMs,
                                clientSbs.maxNativeLiteRtRunWallMs),
                        STATS_VALUE_COLOR);
                addStatsRow("Depth age avg / max",
                        String.format(Locale.US, "%.2f / %.2f ms",
                                clientSbs.averageDepthResultAgeMs,
                                clientSbs.maxDepthResultAgeMs),
                        STATS_VALUE_COLOR);

                if (clientSbs.gpuTimersAvailable) {
                    addStatsRow("GL GPU averages",
                            String.format(Locale.US,
                                    "pack %.2f | color %.2f | profile %.2f | warp %.2f ms",
                                    clientSbs.averageGpuModelInputMs,
                                    clientSbs.averageGpuMatchedColorMs,
                                    clientSbs.averageGpuDepthProfileMs,
                                    clientSbs.averageGpuSbsComposeMs),
                            STATS_VALUE_COLOR);
                }

                long faults = clientSbs.colorSlotBusySkips + clientSbs.flatSbsOutputs;
                if (faults > 0L) {
                    addStatsRow("Faults",
                            String.format(Locale.US, "color busy %d | flat %d",
                                    clientSbs.colorSlotBusySkips,
                                    clientSbs.flatSbsOutputs),
                            STATS_WARN_COLOR);
                }

                if (clientSbs.depthHealthAvailable) {
                    addStatsRow("Depth health",
                            String.format(Locale.US,
                                    "valid %.1f%% | range %.4f | pop %.3f | collapsed %s",
                                    clientSbs.validDepthFraction * 100.0f,
                                    clientSbs.effectiveDepthRangeWidth,
                                    clientSbs.stereoPopStrength,
                                    clientSbs.rawDepthRangeCollapsed ? "yes" : "no"),
                            clientSbs.rawDepthRangeCollapsed
                                    ? STATS_WARN_COLOR : STATS_ON_COLOR);
                } else {
                    addStatsRow("Depth health", "Waiting for sample",
                            STATS_UNAVAILABLE_COLOR);
                }
            }
        }

        finishStatsRows();
    }

    private static String presenterModeName(PresenterMode mode) {
        switch (mode) {
            case HOST_SBS_RAW:
                return "Host SBS Raw";
            case HOST_SBS_AI:
                return "Host SBS AI";
            case CLIENT_SBS_AI:
                return "Client SBS AI";
            case NORMAL:
            default:
                return "Normal";
        }
    }

    static String formatStatsTitle(PresenterMode mode,
                                   float streamWindowSeconds,
                                   float clientSbsWindowSeconds) {
        String title = "Stats | " + presenterModeName(mode);
        boolean hasStreamWindow = Float.isFinite(streamWindowSeconds)
                && streamWindowSeconds > 0.0f;
        boolean hasClientSbsWindow = Float.isFinite(clientSbsWindowSeconds)
                && clientSbsWindowSeconds > 0.0f;
        if (hasStreamWindow && hasClientSbsWindow) {
            return String.format(Locale.US, "%s | stream %.1f s | SBS %.1f s",
                    title, streamWindowSeconds, clientSbsWindowSeconds);
        }
        if (hasStreamWindow) {
            return String.format(Locale.US, "%s | stream %.1f s",
                    title, streamWindowSeconds);
        }
        if (hasClientSbsWindow) {
            return String.format(Locale.US, "%s | SBS %.1f s",
                    title, clientSbsWindowSeconds);
        }
        return title;
    }

    private static String compactDecoderName(String decoderName) {
        if (decoderName == null || decoderName.isEmpty()) {
            return "Unknown decoder";
        }
        if (decoderName.startsWith("c2.qti.")) {
            return decoderName.substring("c2.qti.".length()).replace(".decoder", "");
        }
        if (decoderName.startsWith("c2.android.")) {
            return decoderName.substring("c2.android.".length()).replace(".decoder", "");
        }
        return decoderName;
    }

    private int utilizationColor(double percent) {
        if (percent >= 95.0) {
            return STATS_ERROR_COLOR;
        }
        if (percent >= 80.0) {
            return STATS_WARN_COLOR;
        }
        return STATS_ON_COLOR;
    }

    private int backendColor(String backend) {
        if (isLiteRtOpenClGlBackend(backend)) {
            return STATS_ON_COLOR;
        }
        if ("Unavailable".equals(backend) || "Failed".equals(backend)) {
            return STATS_WARN_COLOR;
        }
        if ("Inactive".equals(backend) || "Initializing".equals(backend)) {
            return STATS_UNAVAILABLE_COLOR;
        }
        return STATS_VALUE_COLOR;
    }

    private String depthBackendName(String backend) {
        if (backend != null && backend.startsWith("LITERT_OPENCL_FP32_GL_IO")) {
            return "LiteRT GPU | OpenCL FP32 | packed GL | complete graph";
        }
        if (backend != null && backend.startsWith("LITERT_OPENCL_FP16_GL_IO")) {
            return "LiteRT GPU | OpenCL FP16 | packed GL | complete graph";
        }
        return backend == null || backend.isEmpty() ? "Unavailable" : backend;
    }

    private boolean isLiteRtOpenClGlBackend(String backend) {
        return backend != null
                && (backend.startsWith("LITERT_OPENCL_FP16_GL_IO")
                || backend.startsWith("LITERT_OPENCL_FP32_GL_IO"));
    }

    private static final String STATS_ROW_METRIC = "stats-metric";
    private static final String STATS_ROW_SECTION = "stats-section";

    private void beginStatsRows() {
        reuseStatsRows = true;
        primaryStatsRowCursor = 0;
    }

    private void finishStatsRows() {
        trimStatsTable(statsTable, primaryStatsRowCursor);
        reuseStatsRows = false;
    }

    private static void trimStatsTable(TableLayout table, int rowsToKeep) {
        if (table == null) {
            return;
        }
        while (table.getChildCount() > rowsToKeep) {
            table.removeViewAt(table.getChildCount() - 1);
        }
    }

    private TableRow obtainStatsRow(boolean section) {
        String expectedTag = section ? STATS_ROW_SECTION : STATS_ROW_METRIC;
        if (!reuseStatsRows) {
            TableRow row = createStatsRow(section);
            statsTable.addView(row);
            return row;
        }

        int index = primaryStatsRowCursor++;
        View existing = index < statsTable.getChildCount()
                ? statsTable.getChildAt(index) : null;
        if (existing instanceof TableRow && expectedTag.equals(existing.getTag())) {
            return (TableRow) existing;
        }

        TableRow replacement = createStatsRow(section);
        if (existing != null) {
            statsTable.removeViewAt(index);
        }
        statsTable.addView(replacement, index);
        return replacement;
    }

    private TableRow createStatsRow(boolean section) {
        TableRow row = new TableRow(activity);
        row.setTag(section ? STATS_ROW_SECTION : STATS_ROW_METRIC);
        if (section) {
            TextView heading = new TextView(activity);
            heading.setTextColor(TILE_ACTIVE_COLOR);
            heading.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    (STATS_TEXT_SP - 1f) * STATS_CONTENT_SCALE);
            heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
            heading.setPadding(0, statsDp(7), 0, statsDp(2));
            TableRow.LayoutParams params = new TableRow.LayoutParams();
            params.span = 2;
            heading.setLayoutParams(params);
            row.addView(heading);
            return row;
        }

        TextView label = new TextView(activity);
        label.setTextColor(STATS_LABEL_COLOR);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP * STATS_CONTENT_SCALE);
        label.setPadding(0, statsDp(1), statsDp(16), statsDp(1));

        TextView value = new TextView(activity);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP * STATS_CONTENT_SCALE);
        value.setPadding(0, statsDp(1), 0, statsDp(1));

        row.addView(label);
        row.addView(value);
        return row;
    }

    private void addStatsSection(String label) {
        TableRow row = obtainStatsRow(true);
        TextView heading = (TextView) row.getChildAt(0);
        heading.setText(label);
    }

    private static String thermalStatusName(int status) {
        switch (status) {
            case 0:
                return "None (0)";
            case 1:
                return "Light (1)";
            case 2:
                return "Moderate (2)";
            case 3:
                return "Severe (3)";
            case 4:
                return "Critical (4)";
            case 5:
                return "Emergency (5)";
            case 6:
                return "Shutdown (6)";
            default:
                return "Unknown (" + status + ")";
        }
    }

    private static int thermalStatusColor(int status) {
        if (status >= 5) {
            return STATS_ERROR_COLOR;
        }
        if (status >= 3) {
            return STATS_WARN_COLOR;
        }
        return STATS_ON_COLOR;
    }

    private void addStatsRow(String label, String value, int valueColor) {
        TableRow row = obtainStatsRow(false);
        TextView labelView = (TextView) row.getChildAt(0);
        TextView valueView = (TextView) row.getChildAt(1);
        labelView.setText(label);
        valueView.setText(value);
        valueView.setTextColor(valueColor);
    }

    /** A thin vertical separator between button groups. */
    private View makeDivider() {
        View d = new View(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(2), LinearLayout.LayoutParams.MATCH_PARENT);
        int vm = dp(10);
        lp.topMargin = vm;
        lp.bottomMargin = vm;
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        d.setLayoutParams(lp);
        d.setBackgroundColor(0x55FFFFFF);
        return d;
    }

    /** Highlight whichever mode tile matches the current stereo mode (single-select). */
    private void updateModeSelection() {
        for (BarItem item : barItems) {
            if (item.selectsMode != null) {
                item.setSelected(item.selectsMode == currentPresenterMode);
            }
        }
    }

    /** Local pose of the control-bar panel: centered just beneath the quad of the given height. */
    private Pose barPose(float videoHeightMeters) {
        float y = -(videoHeightMeters / 2.0f) - BAR_GAP_METERS;
        return new Pose(new Vector3(0.0f, y, BAR_Z_METERS), Quaternion.Identity);
    }

    static final class StatsPanelPlacement {
        final float innerEdgeX;
        final float innerEdgeZ;
        final float centerX;
        final float centerY;
        final float centerZ;
        final float yawDegrees;

        StatsPanelPlacement(float innerEdgeX, float innerEdgeZ, float centerX,
                            float centerY, float centerZ, float yawDegrees) {
            this.innerEdgeX = innerEdgeX;
            this.innerEdgeZ = innerEdgeZ;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.yawDegrees = yawDegrees;
        }
    }

    /**
     * Pure right-side placement geometry. The panel's inner (local -X) edge remains anchored just
     * outside the video while a negative Y yaw wraps its outer edge toward the viewer. The yaw is
     * solved from the viewer position in video-local space and limited to preserve head clearance.
     */
    static StatsPanelPlacement calculateStatsPanelPlacement(float videoWidthMeters,
                                                             float panelWidthMeters,
                                                             float gapMeters,
                                                             float viewerX,
                                                             float viewerZ) {
        float safeVideoWidth = Float.isFinite(videoWidthMeters)
                ? Math.max(0.0f, videoWidthMeters) : 0.0f;
        float safePanelWidth = Float.isFinite(panelWidthMeters)
                ? Math.max(0.0f, panelWidthMeters) : 0.0f;
        float safeGap = Float.isFinite(gapMeters) ? Math.max(0.0f, gapMeters) : 0.0f;
        float safeViewerX = Float.isFinite(viewerX) ? viewerX : 0.0f;
        float safeViewerZ = Float.isFinite(viewerZ)
                && viewerZ > STATS_HEAD_CLEARANCE_METERS + BAR_Z_METERS
                ? viewerZ : 2.0f;

        float innerEdgeX = safeVideoWidth / 2.0f + safeGap;
        float innerEdgeZ = BAR_Z_METERS;
        float halfWidth = safePanelWidth / 2.0f;
        float yawDegrees = 0.0f;

        if (safePanelWidth > 0.0f) {
            float maxForward = Math.max(0.0f,
                    safeViewerZ - STATS_HEAD_CLEARANCE_METERS - innerEdgeZ);
            float clearanceRatio = Math.min(1.0f, maxForward / safePanelWidth);
            float clearanceYaw = (float) Math.toDegrees(Math.asin(clearanceRatio));
            float maxYaw = Math.min(STATS_MAX_INWARD_YAW_DEGREES, clearanceYaw);
            float minYaw = Math.min(STATS_MIN_INWARD_YAW_DEGREES, maxYaw);

            // Center depends on yaw because the inner edge is fixed. A few fixed-point iterations
            // are sufficient and run only on slow UI/pose events, never in the video frame loop.
            for (int i = 0; i < 3; i++) {
                double yawRadians = Math.toRadians(yawDegrees);
                float centerX = innerEdgeX + halfWidth * (float) Math.cos(yawRadians);
                float centerZ = innerEdgeZ - halfWidth * (float) Math.sin(yawRadians);
                float desiredYaw = (float) -Math.toDegrees(Math.atan2(
                        safeViewerX - centerX, safeViewerZ - centerZ));
                float inwardMagnitude = Math.max(minYaw, Math.min(maxYaw, desiredYaw));
                yawDegrees = -inwardMagnitude;
            }
        }

        double yawRadians = Math.toRadians(yawDegrees);
        float centerX = innerEdgeX + halfWidth * (float) Math.cos(yawRadians);
        float centerZ = innerEdgeZ - halfWidth * (float) Math.sin(yawRadians);
        return new StatsPanelPlacement(innerEdgeX, innerEdgeZ, centerX, 0.0f,
                centerZ, yawDegrees);
    }

    /** Current head position expressed in the video entity's local coordinate system. */
    private Vector3 statsViewerPositionLocal() {
        if (session != null && surfaceEntity != null) {
            try {
                Pose headPose = currentHeadPose();
                if (headPose != null) {
                    Scene scene = SessionExt.getScene(session);
                    ScenePose headScenePose = scene.getPerceptionSpace()
                            .getScenePoseFromPerceptionPose(headPose);
                    Vector3 local = headScenePose.transformPositionTo(
                            new Vector3(0.0f, 0.0f, 0.0f), surfaceEntity);
                    if (Float.isFinite(local.getX()) && Float.isFinite(local.getZ())
                            && local.getZ() > 0.05f) {
                        return local;
                    }
                }
            } catch (Throwable ignored) {
                // Use the stable activity-space fallback below until tracking is ready again.
            }
        }

        float fallbackDistance = 2.0f;
        if (surfaceEntity != null) {
            try {
                Vector3 t = surfaceEntity.getPose(Space.REAL_WORLD).getTranslation();
                float distance = (float) Math.sqrt(t.getX() * t.getX()
                        + t.getY() * t.getY() + t.getZ() * t.getZ());
                if (Float.isFinite(distance) && distance > 0.2f) {
                    fallbackDistance = distance;
                }
            } catch (Throwable ignored) {
            }
        }
        return new Vector3(0.0f, 0.0f, fallbackDistance);
    }

    /** Local pose of the right-side stats panel, dynamically yawed toward the viewer. */
    private Pose statsPose(float videoHeightMeters) {
        Vector3 viewer = statsViewerPositionLocal();
        StatsPanelPlacement placement = calculateStatsPanelPlacement(
                videoHeightMeters * aspectFor(currentPresenterMode),
                STATS_WIDTH_METERS, STATS_GAP_METERS, viewer.getX(), viewer.getZ());
        Quaternion rotation = Quaternion.fromAxisAngle(
                new Vector3(0.0f, 1.0f, 0.0f), placement.yawDegrees);
        return new Pose(new Vector3(placement.centerX, placement.centerY, placement.centerZ),
                rotation);
    }

    private void repositionStatsPanel() {
        if (statsVisible && statsPanel != null && !statsPanel.isDisposed()) {
            statsPanel.setPose(statsPose(panelHeightMeters));
        }
    }

    private float controlBarWidthMeters() {
        int dividers = 0;
        for (int i = 1; i < barItems.size(); i++) {
            if (barItems.get(i - 1).selectsMode != null
                    && barItems.get(i).selectsMode == null) {
                dividers++;
            }
        }
        return barItems.size() * BAR_HEIGHT_METERS + dividers * BAR_DIVIDER_METERS;
    }

    /** Enable presentation switching after MediaCodec has rendered the stream's first frame. */
    public void onFirstVideoFrameRendered() {
        if (streamPresentationReady) {
            return;
        }

        streamPresentationReady = true;
        for (BarItem item : barItems) {
            if (item.selectsMode != null) {
                item.setEnabled(true);
            }
        }
        LimeLog.info("XR: first video frame rendered; presentation switching enabled");

        // The initial Host/Raw mode is now proven to match a decoded frame. Mark it as the most
        // successful presentation. Client SBS still needs its guarded GL surface handoff.
        if (deferredPresenterMode == PresenterMode.NORMAL) {
            persistPresentationState();
        }

        PresenterMode modeToRestore = deferredPresenterMode;
        deferredPresenterMode = PresenterMode.NORMAL;
        if (modeToRestore != PresenterMode.NORMAL) {
            for (BarItem item : barItems) {
                if (item.selectsMode == modeToRestore) {
                    LimeLog.info("XR: restoring saved presentation mode " + modeToRestore);
                    selectMode(item);
                    break;
                }
            }
        }
    }

    /** Move the bar and stats panels when the quad height changes (mode switch). */
    private void repositionControlBar(float videoHeightMeters) {
        if (barPanel != null) {
            barPanel.setPose(barPose(videoHeightMeters));
        }
        repositionStatsPanel();
        if (depthStatusPanel != null) {
            depthStatusPanel.setPose(depthStatusPose());
        }
        if (transientMessagePanel != null) {
            transientMessagePanel.setPose(depthStatusPose());
        }
    }

    /**
     * Apply a presentation chosen from the bar. Sets the compositor eye split, drives the host SBS
     * pipeline on/off in Host SBS AI, re-pins the surface to the target
     * frame size, and reshapes the quad to the mode's aspect. The quad's height is preserved; only
     * the width changes (when the aspect changes), so the screen keeps its vertical size.
     */
    private void selectMode(BarItem item) {
        if (!streamPresentationReady || item.selectsMode == null || surfaceEntity == null
                || item.selectsMode == currentPresenterMode || modeSwitchInProgress) {
            return;
        }
        // A switch kicks off an async surface handoff (GL pause/resume + resize); ignore a second
        // mode tap landing right after one so overlapping handoffs can't interleave and glitch.
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastModeSwitchMs < MODE_SWITCH_DEBOUNCE_MS) {
            return;
        }
        lastModeSwitchMs = now;
        modeSwitchInProgress = true;
        PresenterMode previousMode = currentPresenterMode;
        PresenterMode nextMode = item.selectsMode;
        boolean wasClientSbs = (previousMode == PresenterMode.CLIENT_SBS_AI);
        boolean isClientSbs = (nextMode == PresenterMode.CLIENT_SBS_AI);

        com.limelight.Game game = activity instanceof com.limelight.Game
                ? (com.limelight.Game) activity : null;
        boolean decoderTransitionRequired = requiresDecoderTransition(previousMode, nextMode);
        if (decoderTransitionRequired
                && (game == null || !game.beginDecoderPresentationModeTransition())) {
            lastModeSwitchMs = 0;
            modeSwitchInProgress = false;
            reportModeSwitchFailure("decoder could not prepare for the transition");
            return;
        }

        // Honor the native send result before committing the UI. Otherwise a failed reliable
        // control send leaves the client stereo interpretation out of sync with the host layout.
        int previousWireMode = wireModeFor(previousMode);
        int nextWireMode = wireModeFor(nextMode);
        if (prefConfig.isHostDoubledWidthMode() && nextWireMode != previousWireMode
                && MoonBridge.sendSetSbsMode(nextWireMode) <= 0) {
            // No surface changed, so the existing target is immediately safe for the replacement
            // IDR that completes the decoder flush.
            if (decoderTransitionRequired) {
                game.completeDecoderPresentationModeTransition();
            }
            lastModeSwitchMs = 0;
            modeSwitchInProgress = false;
            reportModeSwitchFailure("host request could not be queued");
            return;
        }

        // SceneCore cannot synchronize StereoMode/Shape changes with producer buffers. Hide only
        // the video quad while the decoder is parked and the surface is resized/rebound, then
        // reveal it after the new mode owns the target surface. Controls and stats stay fixed.
        surfaceEntity.setAlpha(0.0f);

        StreamContainer streamContainer = activity instanceof com.limelight.Game
                ? ((com.limelight.Game) activity).getStreamContainer() : null;
        if (streamContainer != null) {
            streamContainer.setClientSbsActive(isClientSbs);
        }
        if (wasClientSbs != isClientSbs) {
            if (streamContainer == null) {
                finishModeSwitch(item, previousMode, nextMode, previousWireMode, nextWireMode,
                        wasClientSbs, isClientSbs, null, false);
            } else {
                streamContainer.switchToClientSbs(isClientSbs,
                        prefConfig.isHostDoubledWidthMode()
                                && isHostDepthPresenterMode(nextMode),
                        success -> finishModeSwitch(item, previousMode, nextMode,
                                previousWireMode, nextWireMode, wasClientSbs,
                                isClientSbs, streamContainer, success));
            }
            return;
        }

        finishModeSwitch(item, previousMode, nextMode, previousWireMode, nextWireMode,
                wasClientSbs, isClientSbs, streamContainer, true);
    }

    private void finishModeSwitch(BarItem item, PresenterMode previousMode, PresenterMode nextMode,
                                  int previousWireMode, int nextWireMode, boolean wasClientSbs,
                                  boolean isClientSbs, StreamContainer streamContainer,
                                  boolean surfaceSwitchSucceeded) {
        if (surfaceSwitchSucceeded && !isClientSbs && !wasClientSbs
                && prefConfig.isHostDoubledWidthMode()
                && requiresHostSurfaceResize(previousMode, nextMode)) {
            surfaceSwitchSucceeded = streamContainer != null && streamContainer
                    .resizeHostSbsSurface(isHostDepthPresenterMode(nextMode));
        }

        if (!surfaceSwitchSucceeded || surfaceEntity == null) {
            modeSwitchInProgress = false;
            if (activity instanceof com.limelight.Game) {
                ((com.limelight.Game) activity).cancelDecoderPresentationModeTransition();
            }
            if (streamContainer != null) {
                streamContainer.setClientSbsActive(wasClientSbs);
            }
            if (prefConfig.isHostDoubledWidthMode() && nextWireMode != previousWireMode
                    && MoonBridge.sendSetSbsMode(previousWireMode) <= 0) {
                LimeLog.severe("XR mode rollback could not restore the host SBS mode");
            }
            lastModeSwitchMs = 0;
            if (surfaceEntity != null) {
                surfaceEntity.setAlpha(1.0f);
            }
            if (surfaceEntity != null && activity instanceof com.limelight.Game) {
                ((com.limelight.Game) activity).handleDecoderSurfaceSwitchFailure();
            }
            return;
        }

        currentPresenterMode = nextMode;
        surfaceEntity.setStereoMode(stereoModeFor(currentPresenterMode));
        applyContentColorMetadata();
        LimeLog.info("XR: stereo mode -> " + item.label);

        float aspect = aspectFor(currentPresenterMode);
        SurfaceEntity.Shape shape = surfaceEntity.getShape();
        float height = (shape instanceof SurfaceEntity.Shape.Quad)
                ? ((SurfaceEntity.Shape.Quad) shape).getExtents().getHeight()
                : DEFAULT_PANEL_HEIGHT_METERS;
        float width = height * aspect;
        surfaceEntity.setShape(new SurfaceEntity.Shape.Quad(new FloatSize2d(width, height)));
        applyResizeBounds(aspect);
        repositionControlBar(height);
        updateModeSelection();
        if (requiresDecoderTransition(previousMode, nextMode)) {
            // Do not expose or persist the new interpretation until MediaCodec confirms that the
            // fresh transition IDR reached the new Surface. A lost IDR is retried and eventually
            // terminates the stream instead of leaving a durable black/half-switched mode.
            pendingDecoderTransitionMode = nextMode;
            ((com.limelight.Game) activity).completeDecoderPresentationModeTransition();
            LimeLog.info("XR: awaiting fresh-IDR output before completing mode " + nextMode);
        } else {
            surfaceEntity.setAlpha(1.0f);
            modeSwitchInProgress = false;
            persistPresentationState();
        }
    }

    /** Decoder callback: the fresh transition IDR is now being released to the target Surface. */
    public void onDecoderPresentationModeTransitionOpened() {
        PresenterMode pendingMode = pendingDecoderTransitionMode;
        if (pendingMode == null) {
            return;
        }
        if (surfaceEntity == null || currentPresenterMode != pendingMode) {
            LimeLog.warning("XR: ignoring stale decoder transition completion for " + pendingMode);
            return;
        }
        pendingDecoderTransitionMode = null;
        surfaceEntity.setAlpha(1.0f);
        modeSwitchInProgress = false;
        persistPresentationState();
        LimeLog.info("XR: fresh-IDR output completed mode " + pendingMode);
    }

    /** Decoder callback: preserve the last successful saved mode while the stream terminates. */
    public void onDecoderPresentationModeTransitionTimedOut() {
        if (pendingDecoderTransitionMode != null) {
            LimeLog.severe("XR: mode " + pendingDecoderTransitionMode
                    + " timed out before fresh-IDR output");
        }
        // Keep both the pending mode and switch guard set while Game terminates the stream. This
        // prevents another tile tap and keeps onDestroy() from persisting the failed mode.
    }

    private static int wireModeFor(PresenterMode mode) {
        return mode == PresenterMode.HOST_SBS_AI
                ? MoonBridge.SBS_MODE_AI : MoonBridge.SBS_MODE_OFF;
    }

    private void reportModeSwitchFailure(String reason) {
        LimeLog.severe("XR mode switch failed: " + reason);
        if (activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).displayMessage(
                    "Unable to switch 3D presentation mode. The previous mode is still active.");
        }
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

    /**
     * Apply the large, close cinema preset to the video panel. This intentionally does not restore
     * the panel's stream-start transform; the bar and stats panel follow the new placement.
     */
    private void applyCinemaView() {
        if (surfaceEntity == null) {
            return;
        }
        surfaceEntity.setScale(1.0f);
        float aspect = aspectFor(currentPresenterMode);
        float height = DEFAULT_PANEL_HEIGHT_METERS;
        panelHeightMeters = height;
        surfaceEntity.setShape(new SurfaceEntity.Shape.Quad(new FloatSize2d(height * aspect, height)));
        applyResizeBounds(aspect);

        // Place the quad ~2 m in front of the user's CURRENT head pose (not the activity-space
        // origin, which is fixed at session start — that's why a plain reset lands off to the side
        // after you've turned). Fall back to activity-space-forward if the head pose isn't available.
        Pose placed = null;
        try {
            Pose head = currentHeadPose();
            if (head != null) {
                // Level the placement: keep only the head's yaw (heading), discarding pitch and
                // roll. Otherwise clicking Cinema View while looking down (e.g. at the control bar
                // below the quad) tilts the panel up to face the eyes and drops it below eye level,
                // so it reads as "looking down at a tilted screen". Round-tripping the Y (yaw) euler
                // component zeroes pitch/roll regardless of angle units, and reusing the proven
                // compose(-2 m forward) keeps the panel facing the user at eye height, 2 m ahead.
                Vector3 euler = head.getRotation().getEulerAngles();
                Pose level = new Pose(head.getTranslation(),
                        Quaternion.fromEulerAngles(0.0f, euler.getY(), 0.0f));
                Pose inFront = level.compose(
                        new Pose(new Vector3(0.0f, 0.0f, -2.0f), Quaternion.Identity));
                surfaceEntity.setPose(inFront, Space.REAL_WORLD);
                placed = inFront;
            }
        } catch (Throwable t) {
            LimeLog.warning("XR cinema view: current head pose unavailable (" + t + ")");
        }
        if (placed == null) {
            surfaceEntity.setPose(new Pose(new Vector3(0.0f, 0.0f, -2.0f), Quaternion.Identity));
        }
        repositionControlBar(height);
        viewStateStore.saveHeight(panelHeightMeters);
    }

    private void restoreViewState() {
        XrViewStateStore.State state = viewStateStore.restore();
        panelHeightMeters = state.panelHeightMeters;
        PresenterMode savedMode = PresenterMode.valueOf(state.presentationMode.name());
        if (savedMode == PresenterMode.HOST_SBS_AI || savedMode == PresenterMode.HOST_SBS_RAW) {
            // These direct-decoder modes can be correct from frame 1. Host AI is also carried in
            // StreamConfiguration/NvHTTP so Apollo begins packed output before transport starts.
            currentPresenterMode = savedMode;
        } else if (savedMode == PresenterMode.CLIENT_SBS_AI) {
            // Client SBS requires a live decoder -> dummy -> GL handoff, so restore it after the
            // first Normal frame using the existing guarded asynchronous switch.
            deferredPresenterMode = savedMode;
        }
        LimeLog.info("XR: restored panel height " + panelHeightMeters + " m; initial mode "
                + currentPresenterMode + "; deferred mode " + deferredPresenterMode);
    }

    private void persistPresentationState() {
        viewStateStore.savePresentation(panelHeightMeters,
                XrViewStateStore.Mode.valueOf(currentPresenterMode.name()));
    }

    /** Host mode that must be part of launch/resume so decoder frame 1 matches the XR surface. */
    public int getInitialHostSbsWireMode() {
        return XrViewStateStore.desiredHostSbsWireMode(
                XrViewStateStore.Mode.valueOf(currentPresenterMode.name()));
    }

    /** Client "Dump 3D" button: ask the host to dump one SBS debug frame (2D source / depth /
     *  SBS result) to its configured debug dir, for offline diagnosis of the reprojection.
     *  Only produces files when a host depth-SBS mode is active on the host. */
    private static void requestHostDebugDump() {
        LimeLog.info("XR: requesting host SBS debug frame dump");
        MoonBridge.sendSbsDebugDump();
    }

    /** Current pose of a render viewpoint (eye), or null if unavailable. */
    private static Pose poseOf(RenderViewpoint vp) {
        if (vp == null || vp.getState() == null) {
            return null;
        }
        RenderViewpoint.State s = vp.getState().getValue();
        return s != null ? s.getPose() : null;
    }

    /** Midpoint head pose in perception space; stereo headsets generally expose no mono view. */
    private Pose currentHeadPose() {
        Pose left = poseOf(RenderViewpoint.left(session));
        Pose right = poseOf(RenderViewpoint.right(session));
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        Vector3 lt = left.getTranslation();
        Vector3 rt = right.getTranslation();
        return new Pose(new Vector3((lt.getX() + rt.getX()) / 2.0f,
                (lt.getY() + rt.getY()) / 2.0f,
                (lt.getZ() + rt.getZ()) / 2.0f), left.getRotation());
    }

    /** Quad aspect (width/height). Only the host SBS presentation uses perEyeAspect, which differs
     *  from fullAspect for Raw (half-width per eye); for Host SBS AI
     *  perEyeAspect is set equal to fullAspect. Normal and Client SBS always use fullAspect, so the
     *  quad keeps its physical size — only the surface resolution changes. */
    private float aspectFor(PresenterMode mode) {
        // Raw splits the host's single W-wide frame into two W/2 eyes -> half the aspect. Host SBS
        // AI shows each eye a full-width per-eye view; Normal and Client show the full frame.
        if (mode == PresenterMode.HOST_SBS_RAW) {
            return fullAspect / 2.0f;
        }
        return isHostDepthPresenterMode(mode) ? perEyeAspect : fullAspect;
    }

    private SurfaceEntity.StereoMode stereoModeFor(PresenterMode mode) {
        return (mode == PresenterMode.NORMAL) ? SurfaceEntity.StereoMode.MONO : SurfaceEntity.StereoMode.SIDE_BY_SIDE;
    }

    /**
     * Client SBS renders through GL, so its output buffers do not inherit MediaCodec's HDR
     * dataspace and need an explicit SceneCore color description. Normal and Host SBS render
     * MediaCodec buffers directly; their HardwareBuffer metadata is authoritative for transfer,
     * range, and HDR state and must not be overridden at the SurfaceEntity level.
     */
    private boolean isColorMetadataExplicit = false;

    /** Reapply color metadata immediately after a host SDR/HDR transition. Main thread only. */
    public void onHdrModeChanged() {
        applyContentColorMetadata();
    }

    /** Reconcile SceneCore metadata after EGL context creation or replacement. Main thread only. */
    public void onClientSbsOutputCapabilityChanged() {
        applyContentColorMetadata();
    }

    private void applyContentColorMetadata() {
        if (surfaceEntity == null) {
            return;
        }
        com.limelight.Game game = activity instanceof com.limelight.Game
                ? (com.limelight.Game) activity : null;
        boolean hdr = game != null && game.isStreamHdrActive();
        StreamContainer streamContainer = game != null ? game.getStreamContainer() : null;
        // Tell the AI-input shader to tonemap PQ->SDR for MiDaS when the stream is HDR.
        if (streamContainer != null) {
            streamContainer.setHdrInput(hdr);
        }
        if (currentPresenterMode == PresenterMode.CLIENT_SBS_AI) {
            boolean preserveHdr = hdr && streamContainer != null
                    && streamContainer.isClientSbsHdrOutputCapable();
            int maxContentLightLevel =
                    SurfaceEntity.ContentColorMetadata.MAX_CONTENT_LIGHT_LEVEL_UNKNOWN;
            if (preserveHdr && game != null) {
                int reportedMaxCll = game.getStreamHdrMaxContentLightLevel();
                if (reportedMaxCll > 0) {
                    maxContentLightLevel = reportedMaxCll;
                }
            }
            SurfaceEntity.ContentColorMetadata.ColorSpace outputColorSpace = preserveHdr
                    ? SurfaceEntity.ContentColorMetadata.ColorSpace.BT2020
                    : SurfaceEntity.ContentColorMetadata.ColorSpace.BT709;
            SurfaceEntity.ContentColorMetadata.ColorTransfer outputTransfer = preserveHdr
                    ? SurfaceEntity.ContentColorMetadata.ColorTransfer.ST2084
                    : (hdr ? SurfaceEntity.ContentColorMetadata.ColorTransfer.SRGB
                            : SurfaceEntity.ContentColorMetadata.ColorTransfer.SDR);
            surfaceEntity.setContentColorMetadata(new SurfaceEntity.ContentColorMetadata(
                    outputColorSpace,
                    outputTransfer,
                    // OES sampling has already converted the decoded YUV frame to normalized RGB.
                    // Advertising the decoder's input range here would make SceneCore apply a
                    // second limited-range interpretation to Artemis' GL-produced surface.
                    SurfaceEntity.ContentColorMetadata.ColorRange.FULL,
                    maxContentLightLevel));
            isColorMetadataExplicit = true;
            if (preserveHdr) {
                LimeLog.info("XR: Client SBS ContentColorMetadata BT2020/ST2084/FULL"
                        + "/MaxCLL=" + (maxContentLightLevel > 0
                                ? Integer.toString(maxContentLightLevel) : "unknown"));
            } else {
                LimeLog.info("XR: Client SBS ContentColorMetadata BT709/"
                        + (hdr ? "SRGB" : "SDR") + "/FULL"
                        + (hdr ? " (HDR tonemapped because output is not end-to-end 10-bit)"
                                : " (SDR input)"));
            }
        } else if (isColorMetadataExplicit) {
            // null calls SceneCore's resetContentColorMetadata(), allowing the decoder's
            // HardwareBuffer dataspace (including full-range HDR) to drive composition again.
            surfaceEntity.setContentColorMetadata(null);
            isColorMetadataExplicit = false;
            LimeLog.info("XR: ContentColorMetadata reset to MediaCodec buffer metadata"
                    + " (mode " + currentPresenterMode + ", HDR " + hdr + ")");
        } else if (currentPresenterMode != PresenterMode.CLIENT_SBS_AI) {
            LimeLog.info("XR: using MediaCodec buffer color metadata"
                    + " (mode " + currentPresenterMode + ", HDR " + hdr
                    + ", requested range " + (prefConfig.fullRange
                            ? "FULL" : "LIMITED") + ")");
        }
    }

    /** Bound the resize affordance to a height range, deriving width from the active aspect. */
    private void applyResizeBounds(float aspect) {
        if (resizable == null) {
            return;
        }
        resizable.setMinimumEntitySize(new FloatSize3d(0.5f * aspect, 0.5f, 0f));
        resizable.setMaximumEntitySize(new FloatSize3d(6.0f * aspect, 6.0f, 0f));
    }

    /** Build one control-bar tile. */
    private View buildBarItemView(BarItem item) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(TILE_IDLE_COLOR);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClickable(true);
        col.setFocusable(true);
        int pad = dp(3);
        col.setPadding(pad, pad, pad, pad);
        col.setOnClickListener(v -> {
            if (item.onTap != null) {
                item.onTap.run();
            }
        });
        item.tapTarget = col;
        applySelectableForeground(col);
        addBarItemContent(col, item);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(col, contentParams);

        return root;
    }

    /** Visible press/hover feedback on top of the dark fill. */
    private void applySelectableForeground(View view) {
        TypedValue fg = new TypedValue();
        if (activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, fg, true) && fg.resourceId != 0) {
            view.setForeground(ContextCompat.getDrawable(activity, fg.resourceId));
        }
    }

    private void addBarItemContent(LinearLayout col, BarItem item) {
        ImageView icon = new ImageView(activity);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        icon.setImageResource(item.iconRes);
        icon.setColorFilter(Color.WHITE);

        TextView text = new TextView(activity);
        text.setText(item.label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        text.setLayoutParams(tp);

        col.addView(icon);
        col.addView(text);
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private int statsDp(float v) {
        return dp(v * STATS_CONTENT_SCALE);
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
        final PresenterMode selectsMode;
        Runnable onTap;
        View root;
        View tapTarget;

        BarItem(String label, int iconRes, PresenterMode selectsMode) {
            this.label = label;
            this.iconRes = iconRes;
            this.selectsMode = selectsMode;
        }

        void setEnabled(boolean enabled) {
            if (root != null) {
                root.setAlpha(enabled ? 1.0f : 0.4f);
            }
            if (tapTarget != null) {
                tapTarget.setEnabled(enabled);
            }
        }


        /** Active mode tile gets a bright accent fill + white border so the current mode is
         *  unmistakable at a glance; everything else stays a flat dark fill. */
        void setSelected(boolean selected) {
            if (root == null) {
                return;
            }
            if (selected) {
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(TILE_ACTIVE_COLOR);
                bg.setStroke(dp(3), TILE_ACTIVE_BORDER_COLOR);
                bg.setCornerRadius(dp(4));
                root.setBackground(bg);
            } else {
                root.setBackgroundColor(TILE_IDLE_COLOR);
            }
            root.invalidate();  // force the XR panel to re-render the tile's new fill/border
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
     * Resize the XR surface for the client-side SBS path. The on-device renderer packs two
     * full-resolution eye views side by side. Its per-eye dimensions exactly match the client
     * stream request; callers that need a smaller GPU/compositor workload must request a smaller
     * stream instead of silently downscaling only this final surface.
     * Every other mode presents a single input-sized frame, so the surface is restored to
     * {@code W×H}.
     * Re-fetches the entity's surface in case the resize re-creates it. Main-thread only.
     */
    public void setClientSbsSurfaceSize(boolean fullStereo) {
        if (surfaceEntity == null) {
            return;
        }
        int width = fullStereo ? getClientSbsSurfaceWidth() : prefConfig.width;
        int height = fullStereo ? getClientSbsSurfaceHeight() : prefConfig.height;
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(width, height));
        videoSurface = surfaceEntity.getSurface();
    }

    /** Final XR swapchain width for Client SBS: two negotiated-size eye views side by side. */
    public int getClientSbsSurfaceWidth() {
        return prefConfig.width * 2;
    }

    /** Final XR height for Client SBS, identical to the negotiated stream height. */
    public int getClientSbsSurfaceHeight() {
        return prefConfig.height;
    }

    /** Capped per-eye width for Host SBS AI: the negotiated per-eye width,
     *  clamped to the encoder/decoder ceiling (MAX_HOST_SBS_EYE_WIDTH). */
    private int hostSbsEyeWidth() {
        return Math.min(prefConfig.width, PreferenceConfiguration.MAX_HOST_SBS_EYE_WIDTH);
    }

    /** Packed Host SBS frame dimensions (2W' x H'). When the per-eye width is capped, the height is
     *  scaled by the same factor so the per-eye aspect is preserved. Even dimensions. */
    private int hostSbsPackedWidth() {
        return (hostSbsEyeWidth() * 2) & ~1;
    }
    private int hostSbsPackedHeight() {
        return Math.round(prefConfig.height * (hostSbsEyeWidth() / (float) prefConfig.width)) & ~1;
    }

    /** Re-pin the XR surface for a host presentation: the packed SBS frame ({@code 2W' x H'}) when a
     *  host depth mode's SBS is active, or the plain 2D frame ({@code W x H}) for NORMAL. Re-fetches
     *  the surface in case the resize re-creates it. Main-thread only (SceneCore is Activity-bound). */
    public void setHostSurfaceSize(boolean sbs) {
        if (surfaceEntity == null) {
            return;
        }
        int w = sbs ? hostSbsPackedWidth() : prefConfig.width;
        int h = sbs ? hostSbsPackedHeight() : prefConfig.height;
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(w, h));
        videoSurface = surfaceEntity.getSurface();
    }

    /**
     * Tear down the entity/session. Mirrors {@code Stereo3DRenderer.onSurfaceDestroyed()} /
     * {@code StreamContainer.onDestroy()} ordering.
     */
    public void onDestroy() {
        if (streamPresentationReady && pendingDecoderTransitionMode == null) {
            persistPresentationState();
        } else {
            // Do not replace the last successful presentation preference with a mode from a
            // startup that never rendered frame 1. Panel size is independently durable.
            viewStateStore.saveHeight(panelHeightMeters);
        }
        if (surfaceEntity != null) {
            if (!surfaceEntity.isDisposed()) {
                surfaceEntity.dispose();
            }
            surfaceEntity = null;
        }
        if (barPanel != null) {
            if (!barPanel.isDisposed()) {
                barPanel.dispose();
            }
            barPanel = null;
        }
        if (statsPanel != null) {
            if (!statsPanel.isDisposed()) {
                statsPanel.dispose();
            }
            statsPanel = null;
        }
        if (depthStatusPanel != null) {
            depthStatusHandler.removeCallbacks(showDepthStatusRunnable);  // cancel a pending delayed show
            if (!depthStatusPanel.isDisposed()) {
                depthStatusPanel.dispose();
            }
            depthStatusPanel = null;
        }
        transientMessageHandler.removeCallbacks(hideTransientMessageRunnable);
        if (transientMessagePanel != null) {
            if (!transientMessagePanel.isDisposed()) {
                transientMessagePanel.dispose();
            }
            transientMessagePanel = null;
        }
        transientMessageText = null;
        videoSurface = null;
        statsTable = null;
        statsItem = null;
        pendingDecoderTransitionMode = null;
        barItems.clear();
        session = null;
    }
}
