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
 * the host's SBS pipeline on/off. See docs/android-xr-sbs.md. Still open: session lifecycle on
 * pause/resume (see {@link #onDestroy}).
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
    // The stats panel sits below the unchanged control bar. Match the cinema panel's landscape
    // shape so both metric columns remain in the user's central field of view instead of extending
    // off to the far right.
    private static final float STATS_WIDTH_METERS = 3.40f;
    private static final float STATS_HEIGHT_METERS = 0.90f;
    private static final float STATS_GAP_METERS = 0.10f;

    // SceneCore must copy every pixel submitted to the packed SBS surface. Rendering two native
    // 4K eye images would create a 7680x2160 swapchain and make the mobile GPU move more than
    // 60 MiB for every decoder-driven draw, even when the matched depth/color pair is unchanged.
    // A 1920-wide eye still exceeds the useful angular resolution of the virtual cinema panel and
    // cuts reprojection, cache, blit, and compositor work by 4x for a 4K stream.
    private static final int MAX_CLIENT_SBS_EYE_WIDTH = 1920;

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final OnSurfaceReadyListener listener;
    private final XrViewStateStore viewStateStore;

    private Session session;
    private SurfaceEntity surfaceEntity;
    private Surface videoSurface;

    /** The single PanelEntity hosting the whole row of buttons. */
    private PanelEntity barPanel;
    /** The control-bar items (one clickable tile each, all hosted in {@link #barPanel}). */
    private final List<BarItem> barItems = new ArrayList<>();

    /** Wide performance-stats panel below the control bar; toggled by the Stats tile. */
    private PanelEntity statsPanel;
    private TextView statsTitle;
    private TableLayout statsTable;
    private TableLayout statsTableSecondary;
    private TableLayout activeStatsTable;
    private boolean reuseStatsRows;
    private int primaryStatsRowCursor;
    private int secondaryStatsRowCursor;
    private BarItem statsItem;
    private boolean statsVisible;
    private final DevicePerformanceSampler devicePerformanceSampler =
            new DevicePerformanceSampler();

    /** Small centered panel shown above the quad while the host loads an engine or initializes
     *  the device-specific 3D pipeline
     *  (driven by the host's 0x3006 depth-status push via {@link #onDepthStatus}). */
    private PanelEntity depthStatusPanel;
    private TextView depthStatusText;
    private static final float DEPTH_STATUS_WIDTH_METERS = 0.9f;
    private static final float DEPTH_STATUS_HEIGHT_METERS = 0.11f;

    // CPU/GPU thermal-zone temp files under /sys/class/thermal (readable by the app on this device),
    // discovered once and bucketed by zone type ("cpu*"/"gpu*"). Temps are reported in milli-°C.
    private java.util.List<java.io.File> cpuThermalFiles;
    private java.util.List<java.io.File> gpuThermalFiles;
    private boolean thermalScanned;
    private static final int TEMP_UNKNOWN = Integer.MIN_VALUE;

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
                             OnSurfaceReadyListener listener) {
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.listener = listener;
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
        // the encoder max), Client SBS -> on-device depth packed into its capped surface. selectMode
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
                stereoModeFor(currentPresenterMode));

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
                R.drawable.ic_xr_reset, /* selectsMode= */ null);
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
        updateModeSelection();
        statsItem.setSelected(statsVisible);

        // Width scales with the tile count so each tile stays square (tile size = bar height),
        // plus a little for the divider — adding tiles widens the bar instead of squeezing them.
        float barWidth = controlBarWidthMeters();
        barPanel = PanelEntity.create(
                session, bar, new FloatSize2d(barWidth, BAR_HEIGHT_METERS),
                "xr-control-bar", barPose(videoHeightMeters), surfaceEntity);
        barPanel.setEnabled(true);

        createStatsPanel(videoHeightMeters);
        createDepthStatusPanel(videoHeightMeters);
    }

    /**
     * Wide performance panel below the unchanged control bar. Unlike the legacy 2D overlay, this consumes
     * typed stream and Client-SBS snapshots so every rate and latency keeps its real unit and
     * stage meaning. Hidden until the Stats tile toggles it.
     */
    private void createStatsPanel(float videoHeightMeters) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xCC101418);
        int p = dp(14);
        root.setPadding(p, p, p, p);

        statsTitle = new TextView(activity);
        statsTitle.setText("Performance");
        statsTitle.setTextColor(TILE_ACTIVE_COLOR);
        statsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP + 3f);
        statsTitle.setTypeface(statsTitle.getTypeface(), android.graphics.Typeface.BOLD);
        statsTitle.setPadding(0, 0, 0, dp(6));
        root.addView(statsTitle);

        LinearLayout columns = new LinearLayout(activity);
        columns.setOrientation(LinearLayout.HORIZONTAL);

        statsTable = createStatsTable();
        statsTableSecondary = createStatsTable();
        activeStatsTable = statsTable;
        LinearLayout.LayoutParams leftColumn = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        leftColumn.setMarginEnd(dp(18));
        columns.addView(statsTable, leftColumn);
        columns.addView(statsTableSecondary, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(columns, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        statsPanel = PanelEntity.create(
                session, root, new FloatSize2d(STATS_WIDTH_METERS, STATS_HEIGHT_METERS),
                "xr-stats", statsPose(videoHeightMeters), surfaceEntity);
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
     * Centered panel above the quad that appears while the host is spinning up a depth engine
     * (build/load/warmup) and disappears once depth is live. Shows an indeterminate spinner (no
     * real progress is available from TensorRT) plus a "Loading &lt;model&gt; depth…" line. Driven
     * entirely by the host's {@link #onDepthStatus} phase pushes, so it reflects actual host state.
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
                "xr-depth-status", depthStatusPose(videoHeightMeters), surfaceEntity);
        depthStatusPanel.setEnabled(false);  // hidden until the host reports a loading phase
    }

    /** Keep transient depth-engine status above the video so it cannot cover the control bar or
     *  the performance panel stacked beneath it. */
    private Pose depthStatusPose(float videoHeightMeters) {
        float y = (videoHeightMeters / 2.0f) + BAR_GAP_METERS
                + (DEPTH_STATUS_HEIGHT_METERS / 2.0f);
        return new Pose(new Vector3(0.0f, y, BAR_Z_METERS), Quaternion.Identity);
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

    /** Toggle the performance-stats panel; also flips the pref so the decoder emits perf text. */
    public void toggleStats() {
        statsVisible = !statsVisible;
        prefConfig.enablePerfOverlay = statsVisible;
        if (statsVisible) {
            // Start CPU and Client-SBS windows at the moment the panel opens. Otherwise the first
            // value would average all work performed while the panel was hidden.
            devicePerformanceSampler.resetCpuBaseline();
            if (activity instanceof Game) {
                StreamContainer container = ((Game) activity).getStreamContainer();
                if (container != null) {
                    container.sampleClientSbsPerformance();
                }
            }
        }
        if (statsPanel != null) {
            statsPanel.setEnabled(statsVisible);
        }
        if (statsItem != null) {
            statsItem.setSelected(statsVisible);
        }
    }

    /**
     * Rebuild the stats table from the decoder's perf string. Called (on the UI thread) from
     * {@code Game.onPerfUpdate}. The string is a set of {@code Label: value} entries separated by
     * newlines/tabs; we split them into colored rows and append an HDR row.
     *
     * @param hdrActive whether the <i>negotiated stream</i> is actually HDR (10-bit) — this reflects
     *                  what the host is really sending, not just the {@code enableHdr} request setting.
     */
    /**
     * Rebuilds the XR table from typed snapshots captured on the same decoder tick. Rates are
     * completed work per second; latency rows are average / maximum for that sampling window.
     * GPU command-submit timings are explicitly named because SceneCore does not expose actual
     * compositor presentation timestamps.
     */
    public void setStats(StreamPerformanceSnapshot stream,
                         Stereo3DRenderer.ClientSbsPerformanceSnapshot clientSbs,
                         String legacyText, boolean hdrActive) {
        if (statsTable == null) {
            return;
        }
        DevicePerformanceSampler.Snapshot device = devicePerformanceSampler.sample();

        beginStatsRows();
        float windowSeconds = stream != null ? stream.getElapsedMs() / 1000.0f
                : clientSbs != null ? clientSbs.windowSeconds : 0.0f;
        if (statsTitle != null) {
            statsTitle.setText(windowSeconds > 0.0f
                    ? String.format(Locale.US, "Performance | %s | %.2f s",
                    presenterModeName(currentPresenterMode), windowSeconds)
                    : "Performance | " + presenterModeName(currentPresenterMode));
        }

        addStatsSection("DEVICE LOAD");
        if (device.appCpuAvailable) {
            addStatsRow("App CPU",
                    String.format(Locale.US, "%.2f cores | %.1f%% of %d",
                            device.appCpuCoreEquivalent, device.appCpuPercentOfCapacity,
                            device.cpuCapacityCores),
                    utilizationColor(device.appCpuPercentOfCapacity));
        } else {
            addStatsRow("App CPU", "Warming up", STATS_UNAVAILABLE_COLOR);
        }
        if (device.deviceGpuUtilizationAvailable) {
            addStatsRow("GPU busy (device)",
                    String.format(Locale.US, "%.1f%%", device.deviceGpuUtilizationPercent),
                    utilizationColor(device.deviceGpuUtilizationPercent));
        } else {
            addStatsRow("GPU busy (device)", "No readable utilization metric",
                    STATS_UNAVAILABLE_COLOR);
        }
        if (device.gpuFrequencyAvailable) {
            addStatsRow("GPU clock",
                    String.format(Locale.US, "%.0f MHz", device.gpuFrequencyHz / 1_000_000.0),
                    STATS_LABEL_COLOR);
        }

        String backend = clientSbs != null ? clientSbs.backend
                : currentPresenterMode == PresenterMode.CLIENT_SBS_AI ? "Initializing" : "Inactive";
        if (device.deviceNpuUtilizationAvailable) {
            addStatsRow("NPU busy (device)",
                    String.format(Locale.US, "%.1f%% global (not app-attributed)",
                            device.deviceNpuUtilizationPercent),
                    utilizationColor(device.deviceNpuUtilizationPercent));
        } else {
            addStatsRow("NPU busy (device)", "No readable utilization metric",
                    STATS_UNAVAILABLE_COLOR);
        }
        addStatsRow("Depth backend", depthBackendName(backend), backendColor(backend));
        if (clientSbs != null && clientSbs.active && clientSbs.inferenceCompleteFps > 0.0f) {
            double inferenceDuty = clientSbs.inferenceCompleteFps
                    * clientSbs.averageInferenceMs / 10.0;
            addStatsRow("LiteRT run wall duty",
                    String.format(Locale.US, "%.1f%% worker wall incl. GL dependencies",
                            Math.max(0.0, Math.min(100.0, inferenceDuty))),
                    utilizationColor(inferenceDuty));
        }

        ensureThermalScanned();
        int cpuTemp = maxThermalC(cpuThermalFiles);
        int gpuTemp = maxThermalC(gpuThermalFiles);
        if (cpuTemp != TEMP_UNKNOWN) {
            addStatsRow("CPU temperature", cpuTemp + "\u00B0C", tempColor(cpuTemp));
        }
        if (gpuTemp != TEMP_UNKNOWN) {
            addStatsRow("GPU temperature", gpuTemp + "\u00B0C", tempColor(gpuTemp));
        }

        addStatsSection("STREAM / NETWORK");
        addStatsRow("Mode", presenterModeName(currentPresenterMode), STATS_VALUE_COLOR);
        addStatsRow("Negotiated ceiling",
                    String.format(Locale.US, "%.0f fps | %.1f Mbps max",
                            prefConfig.fps, prefConfig.bitrate / 1000.0f),
                STATS_VALUE_COLOR);
        float refreshRate = displayRefreshRate();
        if (refreshRate > 0.0f) {
            addStatsRow("Display refresh",
                    String.format(Locale.US, "%.1f Hz (not present FPS)", refreshRate),
                    STATS_VALUE_COLOR);
        }

        if (stream != null) {
            addStatsRow("Sample windows",
                    clientSbs != null
                            ? String.format(Locale.US, "stream %.2f s | SBS %.2f s",
                                    stream.getElapsedMs() / 1000.0f,
                                    clientSbs.windowSeconds)
                            : String.format(Locale.US, "stream %.2f s",
                                    stream.getElapsedMs() / 1000.0f),
                    STATS_LABEL_COLOR);
            addStatsRow("Source",
                    String.format(Locale.US, "%d x %d | %s | %s range",
                            stream.getSourceWidth(), stream.getSourceHeight(),
                            hdrActive ? "HDR" : "SDR", stream.getVideoRange()),
                    hdrActive ? STATS_ON_COLOR : STATS_VALUE_COLOR);
            addStatsRow("Decoder", stream.getDecoderName(), STATS_VALUE_COLOR);
            addStatsRow("App network throughput",
                    stream.hasBandwidth()
                            ? String.format(Locale.US, "%.2f Mbps RX+TX",
                                    stream.getBandwidthMbps())
                            : "Warming up",
                    stream.hasBandwidth() ? STATS_VALUE_COLOR : STATS_UNAVAILABLE_COLOR);
            addStatsRow("Network frame loss",
                    String.format(Locale.US, "%.3f%%", stream.getNetworkLossPercent()),
                    lossColor(stream.getNetworkLossPercent()));
            addStatsRow("Network RTT",
                    stream.hasEstimatedRtt()
                            ? String.format(Locale.US, "%d ms | variance %d ms",
                                    stream.getEstimatedRttMs(),
                                    stream.getEstimatedRttVarianceMs())
                            : "Unavailable",
                    stream.hasEstimatedRtt() ? STATS_VALUE_COLOR : STATS_UNAVAILABLE_COLOR);
        } else {
            addStatsRow("Decoder metrics", "Warming up", STATS_UNAVAILABLE_COLOR);
            appendLegacyStatsFallback(legacyText);
        }

        addStatsSection("PIPELINE EVENTS / SECOND");
        if (stream != null) {
            addRateRow("Sender sequence (includes loss)", stream.getStreamSequenceFps());
            addRateRow("Network receive", stream.getReceivedFps());
            addRateRow("MediaCodec release (not present)", stream.getDecoderReleaseFps());
        }
        if (clientSbs != null && clientSbs.active) {
            addRateRow("Surface callback", clientSbs.surfaceCallbackFps);
            addRateRow("GL video latch", clientSbs.glLatchFps);
            addRateRow("Matched color capture", clientSbs.captureSubmitFps);
            addRateRow("LiteRT worker start", clientSbs.inferenceInputStartFps);
            addRateRow("GPU input-pack submit", clientSbs.preprocessCompleteFps);
            addRateRow("LiteRT worker complete", clientSbs.inferenceCompleteFps);
            addRateRow("GL output-fence consume", clientSbs.postprocessStartFps);
            addRateRow("GPU depth/profile dispatch", clientSbs.postprocessCompleteFps);
            addRateRow("Depth pair adopt", clientSbs.depthAdoptFps);
            addRateRow("New SBS compose", clientSbs.newSbsComposeFps);
            addRateRow("GL output submit", clientSbs.glOutputSubmitFps);
        } else {
            addStatsRow("Client SBS stages", "Inactive", STATS_UNAVAILABLE_COLOR);
        }

        if (clientSbs != null && clientSbs.active) {
            addStatsSection("CLIENT SBS DEPTH / WARP");
            if (clientSbs.depthRenderingActive) {
                addStatsRow("Depth profile",
                        "GPU-resident and active | no synchronous numeric readback",
                        STATS_ON_COLOR);
            } else {
                addStatsRow("Depth profile", "Not ready; duplicated mono output",
                        STATS_UNAVAILABLE_COLOR);
            }
        }

        activeStatsTable = statsTableSecondary;
        addStatsSection("STAGE LATENCY (AVERAGE / MAXIMUM)");
        if (stream != null) {
            if (stream.hasHostProcessingLatency()) {
                addStatsRow("Host processing",
                        String.format(Locale.US, "%.2f / %.2f ms | min %.2f",
                                stream.getHostProcessingAverageMs(),
                                stream.getHostProcessingMaxMs(),
                                stream.getHostProcessingMinMs()),
                        STATS_VALUE_COLOR);
            } else {
                addStatsRow("Host processing", "Unavailable", STATS_UNAVAILABLE_COLOR);
            }
            addStatsRow("MediaCodec enqueue -> output",
                    stream.hasDecodeLatency()
                            ? String.format(Locale.US, "%.2f / %.2f ms",
                                    stream.getDecodeAverageMs(), stream.getDecodeMaxMs())
                            : "No samples",
                    stream.hasDecodeLatency()
                            ? STATS_VALUE_COLOR : STATS_UNAVAILABLE_COLOR);
        }
        if (clientSbs != null && clientSbs.active) {
            addLatencyRow("Surface callback -> GL latch",
                    clientSbs.averageCallbackToGlLatchMs,
                    clientSbs.maxCallbackToGlLatchMs,
                    clientSbs.glLatchFps);
            addLatencyRow("Matched color capture (CPU submit)",
                    clientSbs.averageCaptureSubmitMs,
                    clientSbs.maxCaptureSubmitMs, clientSbs.captureSubmitFps);
            addLatencyRow("LiteRT worker queue", clientSbs.averageInferenceQueueMs,
                    clientSbs.maxInferenceQueueMs, clientSbs.inferenceInputStartFps);
            addLatencyRow("GPU model-input pack (CPU submit)",
                    clientSbs.averagePreprocessMs,
                    clientSbs.maxPreprocessMs, clientSbs.preprocessCompleteFps);
            addLatencyRow("LiteRT worker wall (incl. GL dependencies)",
                    clientSbs.averageInferenceMs,
                    clientSbs.maxInferenceMs, clientSbs.inferenceCompleteFps);
            addLatencyRow("Inference -> GL fence consume",
                    clientSbs.averageResultQueueWaitMs,
                    clientSbs.maxResultQueueWaitMs,
                    clientSbs.postprocessStartFps);
            addLatencyRow("GPU depth/profile dispatch (CPU submit)",
                    clientSbs.averagePostprocessMs,
                    clientSbs.maxPostprocessMs, clientSbs.postprocessCompleteFps);
            addLatencyRow("Capture -> depth dispatch/adopt", clientSbs.averageDepthResultAgeMs,
                    clientSbs.maxDepthResultAgeMs, clientSbs.depthAdoptFps);
            addLatencyRow("SBS compose (CPU submit)", clientSbs.averageComposeMs,
                    clientSbs.maxComposeMs, clientSbs.newSbsComposeFps);
            addLatencyRow("GL output (CPU submit)", clientSbs.averageGlCpuSubmitMs,
                    clientSbs.maxGlCpuSubmitMs, clientSbs.glOutputSubmitFps);
            addStatsRow("EGL / SceneCore present", "Not exposed by XR API",
                    STATS_UNAVAILABLE_COLOR);

            addStatsSection("BACKPRESSURE / REUSE (THIS WINDOW)");
            addCountRow("Surface callbacks coalesced", clientSbs.surfaceCallbacksCoalesced,
                    clientSbs.windowSeconds);
            addCountRow("AI-busy capture skips", clientSbs.aiBusySkips,
                    clientSbs.windowSeconds);
            addCountRow("Color-slot busy skips", clientSbs.colorSlotBusySkips,
                    clientSbs.windowSeconds);
            addStatsRow("SBS output cache reuse",
                    String.format(Locale.US, "%d | %.1f%% of output submits",
                            clientSbs.reusedSbsOutputs, clientSbs.reusedSbsOutputPercent),
                    STATS_VALUE_COLOR);
            addCountRow("Flat outputs (depth not ready)", clientSbs.flatSbsOutputs,
                    clientSbs.windowSeconds);
        }
        finishStatsRows();
    }

    public void setStatsText(String text, boolean hdrActive) {
        if (statsTable == null) {
            return;
        }
        statsTable.removeAllViews();
        if (statsTableSecondary != null) {
            statsTableSecondary.removeAllViews();
        }
        reuseStatsRows = false;
        activeStatsTable = statsTable;

        // The live stream counters below are content-driven: Desktop Duplication and Apollo may
        // encode fewer frames than the negotiated maximum when the desktop is static or the source
        // video has a lower cadence. Show the cap and the XR display refresh separately so a 30 FPS
        // movie is not mistaken for a failed 90 FPS stream/display negotiation.
        addStatsRow(activity.getString(R.string.xr_stats_stream_cap),
                activity.getString(R.string.xr_stats_fps_value, prefConfig.fps),
                STATS_VALUE_COLOR);
        try {
            float refreshRate = activity.getWindowManager().getDefaultDisplay()
                    .getMode().getRefreshRate();
            if (refreshRate > 0.0f) {
                addStatsRow(activity.getString(R.string.xr_stats_display_refresh),
                        activity.getString(R.string.xr_stats_hz_value, refreshRate),
                        STATS_VALUE_COLOR);
            }
        } catch (RuntimeException ignored) {
            // The display can disappear while the activity is being torn down. The stream
            // counters remain useful, so simply omit this optional row in that race.
        }

        for (String part : text.split("[\\n\\t]+")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int idx = entry.indexOf(':');
            if (idx >= 0) {
                addStatsRow(entry.substring(0, idx).trim(), entry.substring(idx + 1).trim(),
                        STATS_VALUE_COLOR);
            } else {
                addStatsRow(entry, "", STATS_VALUE_COLOR);
            }
        }
        // Requested ceiling (a max, not a target) — compare against the live "Bandwidth" row above.
        addStatsRow("Max bitrate", (prefConfig.bitrate / 1000) + " Mbps", STATS_VALUE_COLOR);

        // Hottest CPU/GPU zone, colored by thermal pressure (Client SBS can run the headset hot).
        ensureThermalScanned();
        int cpuTemp = maxThermalC(cpuThermalFiles);
        int gpuTemp = maxThermalC(gpuThermalFiles);
        if (cpuTemp != TEMP_UNKNOWN) {
            addStatsRow("CPU temp", cpuTemp + "°C", tempColor(cpuTemp));
        }
        if (gpuTemp != TEMP_UNKNOWN) {
            addStatsRow("GPU temp", gpuTemp + "°C", tempColor(gpuTemp));
        }

        addStatsRow("HDR", hdrActive ? "On" : "Off",
                hdrActive ? STATS_ON_COLOR : STATS_LABEL_COLOR);
    }

    /** Only used while a caller is warming up the new typed decoder snapshot. */
    private void appendLegacyStatsFallback(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (String part : text.split("[\\n\\t]+")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.indexOf(':');
            if (separator >= 0) {
                addStatsRow(entry.substring(0, separator).trim(),
                        entry.substring(separator + 1).trim(), STATS_VALUE_COLOR);
            }
        }
    }

    private float displayRefreshRate() {
        try {
            return activity.getWindowManager().getDefaultDisplay().getMode().getRefreshRate();
        } catch (RuntimeException ignored) {
            // The display can disappear during Activity teardown.
            return 0.0f;
        }
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

    private int utilizationColor(double percent) {
        if (percent >= 95.0) {
            return STATS_ERROR_COLOR;
        }
        if (percent >= 80.0) {
            return STATS_WARN_COLOR;
        }
        return STATS_ON_COLOR;
    }

    private int lossColor(float percent) {
        if (percent >= 1.0f) {
            return STATS_ERROR_COLOR;
        }
        if (percent >= 0.1f) {
            return STATS_WARN_COLOR;
        }
        return STATS_ON_COLOR;
    }

    private int backendColor(String backend) {
        if (backend != null && backend.startsWith("LITERT_GPU_GL")) {
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
        if (backend != null && backend.startsWith("LITERT_GPU_GL")) {
            return "LiteRT GPU | OpenCL FP16 | packed GL";
        }
        return backend == null || backend.isEmpty() ? "Unavailable" : backend;
    }

    private void addRateRow(String label, float fps) {
        addStatsRow(label, String.format(Locale.US, "%.1f fps", fps), STATS_VALUE_COLOR);
    }

    private void addLatencyRow(String label, float averageMs, float maximumMs,
                               float completedFps) {
        addStatsRow(label, completedFps > 0.0f
                        ? String.format(Locale.US, "%.2f / %.2f ms", averageMs, maximumMs)
                        : "No samples",
                completedFps > 0.0f ? STATS_VALUE_COLOR : STATS_UNAVAILABLE_COLOR);
    }

    private void addCountRow(String label, long count, float windowSeconds) {
        float rate = windowSeconds > 0.0f ? count / windowSeconds : 0.0f;
        addStatsRow(label, String.format(Locale.US, "%d | %.1f/s", count, rate),
                count > 0L ? STATS_WARN_COLOR : STATS_VALUE_COLOR);
    }

    private static final String STATS_ROW_METRIC = "stats-metric";
    private static final String STATS_ROW_SECTION = "stats-section";

    private void beginStatsRows() {
        reuseStatsRows = true;
        primaryStatsRowCursor = 0;
        secondaryStatsRowCursor = 0;
        activeStatsTable = statsTable;
    }

    private void finishStatsRows() {
        trimStatsTable(statsTable, primaryStatsRowCursor);
        trimStatsTable(statsTableSecondary, secondaryStatsRowCursor);
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
        TableLayout table = activeStatsTable != null ? activeStatsTable : statsTable;
        String expectedTag = section ? STATS_ROW_SECTION : STATS_ROW_METRIC;
        if (!reuseStatsRows) {
            TableRow row = createStatsRow(section);
            table.addView(row);
            return row;
        }

        int index;
        if (table == statsTableSecondary) {
            index = secondaryStatsRowCursor++;
        } else {
            index = primaryStatsRowCursor++;
        }
        View existing = index < table.getChildCount() ? table.getChildAt(index) : null;
        if (existing instanceof TableRow && expectedTag.equals(existing.getTag())) {
            return (TableRow) existing;
        }

        TableRow replacement = createStatsRow(section);
        if (existing != null) {
            table.removeViewAt(index);
        }
        table.addView(replacement, index);
        return replacement;
    }

    private TableRow createStatsRow(boolean section) {
        TableRow row = new TableRow(activity);
        row.setTag(section ? STATS_ROW_SECTION : STATS_ROW_METRIC);
        if (section) {
            TextView heading = new TextView(activity);
            heading.setTextColor(TILE_ACTIVE_COLOR);
            heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP - 1f);
            heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
            heading.setPadding(0, dp(7), 0, dp(2));
            TableRow.LayoutParams params = new TableRow.LayoutParams();
            params.span = 2;
            heading.setLayoutParams(params);
            row.addView(heading);
            return row;
        }

        TextView label = new TextView(activity);
        label.setTextColor(STATS_LABEL_COLOR);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP);
        label.setPadding(0, dp(1), dp(16), dp(1));

        TextView value = new TextView(activity);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP);
        value.setPadding(0, dp(1), 0, dp(1));

        row.addView(label);
        row.addView(value);
        return row;
    }

    private void addStatsSection(String label) {
        TableRow row = obtainStatsRow(true);
        TextView heading = (TextView) row.getChildAt(0);
        heading.setText(label);
    }

    /** Lazily scan /sys/class/thermal once, bucketing each zone's temp file by type (CPU vs GPU). */
    private void ensureThermalScanned() {
        if (thermalScanned) {
            return;
        }
        thermalScanned = true;
        cpuThermalFiles = new java.util.ArrayList<>();
        gpuThermalFiles = new java.util.ArrayList<>();
        java.io.File[] zones = new java.io.File("/sys/class/thermal")
                .listFiles((dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null) {
            return;
        }
        for (java.io.File zone : zones) {
            String type = readFirstLine(new java.io.File(zone, "type"));
            if (type == null) {
                continue;
            }
            type = type.toLowerCase();
            if (type.startsWith("cpu")) {
                cpuThermalFiles.add(new java.io.File(zone, "temp"));
            } else if (type.startsWith("gpu")) {
                gpuThermalFiles.add(new java.io.File(zone, "temp"));
            }
        }
    }

    /** Hottest zone temperature in whole °C among the files, or TEMP_UNKNOWN if none readable. */
    private int maxThermalC(java.util.List<java.io.File> files) {
        int max = TEMP_UNKNOWN;
        if (files != null) {
            for (java.io.File f : files) {
                String s = readFirstLine(f);
                if (s == null) {
                    continue;
                }
                try {
                    int c = Integer.parseInt(s.trim()) / 1000;
                    if (c > max) {
                        max = c;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max;
    }

    private static String readFirstLine(java.io.File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /** Green/amber/red by temperature, to flag thermal pressure at a glance. */
    private int tempColor(int c) {
        if (c >= 85) {
            return 0xFFE05A5A;  // hot
        }
        if (c >= 70) {
            return 0xFFE0B020;  // warm
        }
        return STATS_ON_COLOR;  // cool
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

    /** Local pose of the stats panel: centered below the unchanged control bar. */
    private Pose statsPose(float videoHeightMeters) {
        float barBottomY = -(videoHeightMeters / 2.0f) - BAR_GAP_METERS
                - (BAR_HEIGHT_METERS / 2.0f);
        float y = barBottomY - STATS_GAP_METERS - (STATS_HEIGHT_METERS / 2.0f);
        return new Pose(new Vector3(0.0f, y, BAR_Z_METERS), Quaternion.Identity);
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
        if (statsPanel != null) {
            statsPanel.setPose(statsPose(videoHeightMeters));
        }
        if (depthStatusPanel != null) {
            depthStatusPanel.setPose(depthStatusPose(videoHeightMeters));
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

        // Honor the native send result before committing the UI. Otherwise a failed reliable
        // control send leaves the client stereo interpretation out of sync with the host layout.
        int previousWireMode = wireModeFor(previousMode);
        int nextWireMode = wireModeFor(nextMode);
        if (prefConfig.isHostDoubledWidthMode() && nextWireMode != previousWireMode
                && MoonBridge.sendSetSbsMode(nextWireMode) <= 0) {
            lastModeSwitchMs = 0;
            modeSwitchInProgress = false;
            reportModeSwitchFailure("host request could not be queued");
            return;
        }

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
                streamContainer.switchToClientSbs(isClientSbs, success -> finishModeSwitch(item,
                        previousMode, nextMode, previousWireMode, nextWireMode, wasClientSbs,
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
        if (surfaceSwitchSucceeded && !isClientSbs && prefConfig.isHostDoubledWidthMode()) {
            surfaceSwitchSucceeded = streamContainer != null && streamContainer
                    .resizeHostSbsSurface(isHostDepthPresenterMode(nextMode));
        }

        if (!surfaceSwitchSucceeded || surfaceEntity == null) {
            modeSwitchInProgress = false;
            if (streamContainer != null) {
                streamContainer.setClientSbsActive(wasClientSbs);
            }
            if (prefConfig.isHostDoubledWidthMode() && nextWireMode != previousWireMode
                    && MoonBridge.sendSetSbsMode(previousWireMode) <= 0) {
                LimeLog.severe("XR mode rollback could not restore the host SBS mode");
            }
            lastModeSwitchMs = 0;
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
        modeSwitchInProgress = false;
        persistPresentationState();
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
            // A stereo headset has no mono viewpoint; use the two eye viewpoints and take their
            // midpoint as the head position (orientation from the left eye).
            Pose lp = poseOf(RenderViewpoint.left(session));
            Pose rp = poseOf(RenderViewpoint.right(session));
            Pose head = null;
            if (lp != null && rp != null) {
                Vector3 lt = lp.getTranslation();
                Vector3 rt = rp.getTranslation();
                head = new Pose(new Vector3((lt.getX() + rt.getX()) / 2f,
                        (lt.getY() + rt.getY()) / 2f, (lt.getZ() + rt.getZ()) / 2f), lp.getRotation());
            } else if (lp != null) {
                head = lp;
            }
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

    /** A non-graceful startup failure before frame 1 invalidates the saved mode. This is not a
     *  session-expiry timer: it prevents a genuinely incompatible presentation route from being
     *  retried forever while preserving the user's panel size. */
    public void onStreamStartupFailed() {
        if (streamPresentationReady) {
            return;
        }
        deferredPresenterMode = PresenterMode.NORMAL;
        viewStateStore.resetPresentationToNormal(panelHeightMeters);
        LimeLog.warning("XR: reset saved presentation mode after startup failed before frame 1");
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

    private void applyContentColorMetadata() {
        if (surfaceEntity == null) {
            return;
        }
        boolean hdr = (activity instanceof com.limelight.Game)
                && ((com.limelight.Game) activity).isStreamHdrActive();
        // Tell the AI-input shader to tonemap PQ->SDR for MiDaS when the stream is HDR.
        if (activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).getStreamContainer().setHdrInput(hdr);
        }
        if (hdr && currentPresenterMode == PresenterMode.CLIENT_SBS_AI) {
            int maxContentLightLevel =
                    SurfaceEntity.ContentColorMetadata.MAX_CONTENT_LIGHT_LEVEL_UNKNOWN;
            if (activity instanceof com.limelight.Game) {
                com.limelight.Game game = (com.limelight.Game) activity;
                int reportedMaxCll = game.getStreamHdrMaxContentLightLevel();
                if (reportedMaxCll > 0) {
                    maxContentLightLevel = reportedMaxCll;
                }
            }
            surfaceEntity.setContentColorMetadata(new SurfaceEntity.ContentColorMetadata(
                    SurfaceEntity.ContentColorMetadata.ColorSpace.BT2020,
                    SurfaceEntity.ContentColorMetadata.ColorTransfer.ST2084,
                    // OES sampling has already converted the decoded YUV frame to normalized RGB.
                    // Advertising the decoder's input range here would make SceneCore apply a
                    // second limited-range interpretation to Artemis' GL-produced surface.
                    SurfaceEntity.ContentColorMetadata.ColorRange.FULL,
                    maxContentLightLevel));
            isColorMetadataExplicit = true;
            LimeLog.info("XR: HDR ContentColorMetadata BT2020/ST2084/FULL"
                    + "/MaxCLL=" + (maxContentLightLevel > 0
                            ? Integer.toString(maxContentLightLevel) : "unknown")
                    + " (mode " + currentPresenterMode + ")");
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
            view.setForeground(activity.getDrawable(fg.resourceId));
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
     * aspect-preserved eye views side by side. Client rendering is capped independently from the
     * decoded source size so a 4K stream does not create an 8K-wide mobile swapchain. Every other
     * mode presents a single input-sized frame, so the surface is restored to {@code W×H}.
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

    private int clientSbsEyeWidth() {
        return Math.min(prefConfig.width, MAX_CLIENT_SBS_EYE_WIDTH);
    }

    /** XR surface/render width for Client SBS: two capped eye views side by side. */
    public int getClientSbsSurfaceWidth() {
        return (clientSbsEyeWidth() * 2) & ~1;
    }

    /** Aspect-preserved XR surface/render height corresponding to the capped per-eye width. */
    public int getClientSbsSurfaceHeight() {
        return Math.max(2, Math.round(prefConfig.height
                * (clientSbsEyeWidth() / (float) prefConfig.width)) & ~1);
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
        if (streamPresentationReady) {
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
        videoSurface = null;
        statsTable = null;
        statsItem = null;
        barItems.clear();
        session = null;
    }
}
