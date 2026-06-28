package com.limelight.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

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
 * the surface always carries the same packed frame) plus Machines and Disconnect actions.
 * See docs/android-xr-sbs.md. Still open: session lifecycle on pause/resume (see {@link #onDestroy}).
 */
public class XrStreamPresenter {

    /** Nearest the user may drag the panel toward the eyes, in meters from the activity-space origin. */
    private static final float MIN_PANEL_DISTANCE_METERS = 0.75f;

    // Control-bar tile geometry (meters). Shared by build + reposition so the bar stays glued
    // beneath the quad as it changes size on a mode switch or a user resize.
    private static final float BAR_HEIGHT_METERS = 0.21f;     // also the per-tile size (square tiles)
    private static final float BAR_DIVIDER_METERS = 0.05f;    // extra width for the group divider
    private static final float BAR_GAP_METERS = 0.24f;        // quad bottom -> bar center
    private static final float BAR_Z_METERS = 0.02f;          // nudge toward viewer vs. the quad
    private static final int TILE_IDLE_COLOR = 0xCC1E2630;    // resting tile fill
    private static final int TILE_ACTIVE_COLOR = 0xFF2C72E0;  // active (selected) mode tile fill
    private static final float STATS_WIDTH_METERS = 0.95f;    // performance-stats panel beside quad
    private static final float STATS_HEIGHT_METERS = 1.1f;
    private static final float STATS_GAP_METERS = 0.10f;      // gap between quad edge and stats panel
    // 2D→3D adjustment sub-panel (Client SBS only), docked under the control bar.
    private static final float ADJUST_WIDTH_METERS = 1.7f;
    private static final float ADJUST_HEIGHT_METERS = 0.9f;
    private static final float ADJUST_GAP_METERS = 0.06f;     // gap between bar bottom and sub-panel
    private static final String CLIENT_SBS_HALF_WIDTH_KEY = "xr_client_sbs_half_width";
    // Effect-parameter ids (map to the shared prefConfig fields the renderer reads every frame).
    private static final int PARAM_DEPTH = 0;
    private static final int PARAM_CONVERGENCE = 1;
    private static final int PARAM_BALANCE = 2;

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final OnSurfaceReadyListener listener;

    private Session session;
    private SurfaceEntity surfaceEntity;
    private Surface videoSurface;

    /** The single PanelEntity hosting the whole row of buttons. */
    private PanelEntity barPanel;
    /** The control-bar items (one clickable tile each, all hosted in {@link #barPanel}). */
    private final List<BarItem> barItems = new ArrayList<>();

    /** Sub-panel under the quad with live 2D→3D effect sliders; shown only in Client SBS. */
    private PanelEntity adjustPanel;

    /** Floating performance-stats panel beside the quad and its table; toggled by the Stats tile. */
    private PanelEntity statsPanel;
    private TableLayout statsTable;
    private BarItem statsItem;
    private boolean statsVisible;

    // CPU/GPU thermal-zone temp files under /sys/class/thermal (readable by the app on this device),
    // discovered once and bucketed by zone type ("cpu*"/"gpu*"). Temps are reported in milli-°C.
    private java.util.List<java.io.File> cpuThermalFiles;
    private java.util.List<java.io.File> gpuThermalFiles;
    private boolean thermalScanned;
    private static final int TEMP_UNKNOWN = Integer.MIN_VALUE;

    private static final int STATS_LABEL_COLOR = 0xFF9FB3C8;  // muted blue-grey for row labels
    private static final int STATS_VALUE_COLOR = 0xFFFFFFFF;  // white for values
    private static final int STATS_ON_COLOR = 0xFF5CD65C;     // green for "on"/HDR active
    private static final float STATS_TEXT_SP = 30f;
    /** Comfortable default quad height in meters; mode switches keep this height and vary width. */
    private static final float DEFAULT_PANEL_HEIGHT_METERS = 1.2f;

    public enum PresenterMode {
        NORMAL,
        HOST_SBS,
        CLIENT_SBS
    }

    /** Which mode the SurfaceEntity is currently presenting (defaults to NORMAL). */
    private PresenterMode currentPresenterMode = PresenterMode.NORMAL;

    /** Half-width Client SBS: render each eye at W/2 into a W×H surface instead of full W into 2W×H.
     *  ~Halves GPU load and heat at slightly softer per-eye sharpness. Persisted; default off. */
    private boolean clientSbsHalfWidth;

    /** Debounce window for mode-tile taps: a switch starts an async surface handoff, so ignore a
     *  second tap that lands within this window (double-tap / impatient re-tap). */
    private static final long MODE_SWITCH_DEBOUNCE_MS = 600L;
    private long lastModeSwitchMs;

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
        this.clientSbsHalfWidth = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(activity).getBoolean(CLIENT_SBS_HALF_WIDTH_KEY, false);
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

        // Enable device (head) tracking so the "Reset" tile can recenter the panel in front of the
        // user's current head pose (via RenderViewpoint). The default session has it DISABLED, which
        // is why RenderViewpoint.mono(session) was null. Head pose needs no runtime permission.
        try {
            Config cfg = session.getConfig();
            SessionConfigureResult cr = session.configure(cfg.copy(
                    cfg.getPlaneTracking(), cfg.getHandTracking(), DeviceTrackingMode.LAST_KNOWN));
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
        BarItem clientSbs = new BarItem(
                activity.getString(R.string.xr_bar_client_sbs),
                R.drawable.ic_xr_mode_client_sbs, PresenterMode.CLIENT_SBS);
        BarItem hostSbs = new BarItem(
                activity.getString(R.string.xr_bar_host_sbs),
                R.drawable.ic_xr_mode_host_sbs, PresenterMode.HOST_SBS);
        BarItem stats = new BarItem(
                activity.getString(R.string.xr_bar_stats),
                R.drawable.ic_xr_stats, /* selectsMode= */ null);
        BarItem reset = new BarItem(
                activity.getString(R.string.xr_bar_reset),
                R.drawable.ic_xr_reset, /* selectsMode= */ null);
        BarItem machines = new BarItem(
                activity.getString(R.string.xr_bar_machines),
                R.drawable.ic_computer, /* selectsMode= */ null);
        BarItem disconnect = new BarItem(
                activity.getString(R.string.xr_bar_disconnect),
                R.drawable.ic_xr_disconnect, /* selectsMode= */ null);

        normal.onTap = () -> selectMode(normal);
        clientSbs.onTap = () -> selectMode(clientSbs);
        hostSbs.onTap = () -> selectMode(hostSbs);
        stats.onTap = this::toggleStats;
        reset.onTap = this::resetView;
        machines.onTap = this::returnToMachineSelection;
        disconnect.onTap = activity::finish;
        statsItem = stats;

        barItems.clear();
        barItems.add(normal);
        barItems.add(clientSbs);
        barItems.add(hostSbs);
        barItems.add(stats);
        barItems.add(reset);
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
            prevWasMode = isMode;
            first = false;
        }

        // Bake the initial highlights into the views before the panel is created.
        statsVisible = prefConfig.enablePerfOverlay;
        updateModeSelection();
        statsItem.setSelected(statsVisible);

        // Width scales with the tile count so each tile stays square (tile size = bar height),
        // plus a little for the divider — adding tiles widens the bar instead of squeezing them.
        float barWidth = barItems.size() * BAR_HEIGHT_METERS + BAR_DIVIDER_METERS;
        barPanel = PanelEntity.create(
                session, bar, new FloatSize2d(barWidth, BAR_HEIGHT_METERS),
                "xr-control-bar", barPose(videoHeightMeters), surfaceEntity);
        barPanel.setEnabled(true);

        createStatsPanel(videoHeightMeters);
        createAdjustPanel(videoHeightMeters);
    }

    /**
     * Floating performance-stats panel above the quad, fed by {@link #setStatsText} (which
     * {@code Game.onPerfUpdate} forwards to). Hidden until the Stats tile toggles it. The 2D perf
     * overlay can't be used in XR because the activity's main panel is hidden.
     */
    private void createStatsPanel(float videoHeightMeters) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xCC101418);
        int p = dp(14);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(activity);
        title.setText("Performance");
        title.setTextColor(TILE_ACTIVE_COLOR);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP + 2f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        statsTable = new TableLayout(activity);
        statsTable.setColumnShrinkable(1, true);
        root.addView(statsTable);

        statsPanel = PanelEntity.create(
                session, root, new FloatSize2d(STATS_WIDTH_METERS, STATS_HEIGHT_METERS),
                "xr-stats", statsPose(videoHeightMeters), surfaceEntity);
        statsPanel.setEnabled(statsVisible);
    }

    /** Toggle the performance-stats panel; also flips the pref so the decoder emits perf text. */
    public void toggleStats() {
        statsVisible = !statsVisible;
        prefConfig.enablePerfOverlay = statsVisible;
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
    public void setStatsText(String text, boolean hdrActive) {
        if (statsTable == null) {
            return;
        }
        statsTable.removeAllViews();
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

    /**
     * Sub-panel docked under the control bar with live 2D→3D effect controls (Depth / Convergence /
     * Balance), each a −/value/+ stepper. Steps mutate the shared {@code prefConfig} fields that
     * {@code Stereo3DRenderer} reads every frame, so the effect updates in real time; the new value
     * is also persisted. Shown only in Client SBS (hidden in Normal/Host SBS, which do no synthesis).
     */
    private void createAdjustPanel(float videoHeightMeters) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xCC101418);
        int p = dp(14);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(activity);
        title.setText("3D adjust");
        title.setTextColor(TILE_ACTIVE_COLOR);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP + 2f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        addParamRow(root, "Strength",
                "Overall 3D intensity. Higher = stronger pop-out, but more eye strain.", PARAM_DEPTH);
        addParamRow(root, "Convergence",
                "Screen plane for the whole scene. Lower = pops in front, higher = sits behind.",
                PARAM_CONVERGENCE);
        addHalfWidthToggle(root);

        adjustPanel = PanelEntity.create(
                session, root, new FloatSize2d(ADJUST_WIDTH_METERS, ADJUST_HEIGHT_METERS),
                "xr-3d-adjust", adjustPose(videoHeightMeters), surfaceEntity);
        adjustPanel.setEnabled(currentPresenterMode == PresenterMode.CLIENT_SBS);
    }

    /**
     * One effect block: a header (bold label + live value%), a one-line description, and a
     * full-width slider. Each block is generously padded so the sliders are easy to target by gaze.
     * Dragging updates the shared {@code prefConfig} on every change (renderer reads it next frame),
     * so the effect changes live; the value is persisted on release. The value label is fixed-width
     * so changing it mid-drag doesn't trigger a row re-layout (which made the drag feel laggy).
     */
    private void addParamRow(LinearLayout parent, String label, String desc, int paramId) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(22), 0, dp(22));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView lbl = new TextView(activity);
        lbl.setText(label);
        lbl.setTextColor(STATS_VALUE_COLOR);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP);
        lbl.setTypeface(lbl.getTypeface(), android.graphics.Typeface.BOLD);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(activity);
        value.setText(pct(getParam(paramId)));
        value.setTextColor(TILE_ACTIVE_COLOR);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP);
        value.setGravity(Gravity.END);
        value.setWidth(dp(96));

        header.addView(lbl);
        header.addView(value);

        TextView description = new TextView(activity);
        description.setText(desc);
        description.setTextColor(STATS_LABEL_COLOR);
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP - 8f);
        description.setPadding(0, dp(2), 0, dp(4));

        SeekBar slider = new SeekBar(activity);
        slider.setMax(100);
        slider.setProgress(Math.round(getParam(paramId) * 100f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(8), 0, dp(8));
        slider.setLayoutParams(slp);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float v = progress / 100f;
                setParam(paramId, v);          // live: shared prefConfig -> renderer next frame
                value.setText(pct(v));
                // Client SBS now renders on demand, so nudge a redraw to show the change even when
                // no new video frame is arriving (e.g. paused stream).
                if (activity instanceof com.limelight.Game) {
                    ((com.limelight.Game) activity).getStreamContainer().requestStereoRender();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                persistParam(paramKey(paramId), sb.getProgress() / 100f);
            }
        });

        block.addView(header);
        block.addView(description);
        block.addView(slider);
        parent.addView(block);
    }

    /** Toggle row for half-width Client SBS (performance/thermal). A Switch in the same panel gets
     *  the gaze highlight + native tap like the bar tiles. */
    private void addHalfWidthToggle(LinearLayout parent) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(18), 0, dp(8));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView lbl = new TextView(activity);
        lbl.setText("Half-width (performance)");
        lbl.setTextColor(STATS_VALUE_COLOR);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP);
        lbl.setTypeface(lbl.getTypeface(), android.graphics.Typeface.BOLD);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(activity);
        sw.setChecked(clientSbsHalfWidth);
        sw.setOnCheckedChangeListener((b, checked) -> setClientSbsHalfWidth(checked));

        header.addView(lbl);
        header.addView(sw);

        TextView description = new TextView(activity);
        description.setText("Renders each eye at half width — much cooler and smoother, slightly softer.");
        description.setTextColor(STATS_LABEL_COLOR);
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP - 8f);
        description.setPadding(0, dp(2), 0, dp(2));

        block.addView(header);
        block.addView(description);
        parent.addView(block);
    }

    /** Apply the half-width toggle: persist it, and — if currently in Client SBS — re-cycle the GL
     *  surface at the new width and reshape the quad to the matching aspect. */
    private void setClientSbsHalfWidth(boolean half) {
        if (clientSbsHalfWidth == half) {
            return;
        }
        clientSbsHalfWidth = half;
        persistBool(CLIENT_SBS_HALF_WIDTH_KEY, half);
        // Only the surface resolution changes (W <-> 2W); the quad keeps fullAspect / same size.
        if (currentPresenterMode == PresenterMode.CLIENT_SBS
                && activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).getStreamContainer().recycleClientSbs();
        }
    }

    private void persistBool(String key, boolean value) {
        try {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                    .edit().putBoolean(key, value).apply();
        } catch (Throwable t) {
            LimeLog.warning("XR: persist failed: " + t);
        }
    }

    private float getParam(int paramId) {
        switch (paramId) {
            case PARAM_DEPTH: return prefConfig.parallax_depth;
            case PARAM_CONVERGENCE: return prefConfig.convergence_ratio;
            default: return prefConfig.balance_shift;
        }
    }

    private void setParam(int paramId, float v) {
        switch (paramId) {
            case PARAM_DEPTH: prefConfig.parallax_depth = v; break;
            case PARAM_CONVERGENCE: prefConfig.convergence_ratio = v; break;
            default: prefConfig.balance_shift = v; break;
        }
    }

    private static String paramKey(int paramId) {
        switch (paramId) {
            case PARAM_DEPTH: return "parallax_depth";
            case PARAM_CONVERGENCE: return "convergence_ratio";
            default: return "balance_shift";
        }
    }

    private static String pct(float v) {
        return Math.round(v * 100f) + "%";
    }

    /** Persist a 0..1 effect param back to the int (0–100) pref the settings UI / loader use. */
    private void persistParam(String key, float value01) {
        try {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                    .edit().putInt(key, Math.round(value01 * 100f)).apply();
        } catch (Throwable t) {
            LimeLog.warning("XR 3D-adjust: persist failed: " + t);
        }
    }

    /** Show the adjust sub-panel only in Client SBS (the only mode that synthesizes 3D on-device). */
    private void updateAdjustPanelVisibility() {
        if (adjustPanel != null) {
            adjustPanel.setEnabled(currentPresenterMode == PresenterMode.CLIENT_SBS);
        }
    }

    private void addStatsRow(String label, String value, int valueColor) {
        TableRow row = new TableRow(activity);

        TextView l = new TextView(activity);
        l.setText(label);
        l.setTextColor(STATS_LABEL_COLOR);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP);
        l.setPadding(0, dp(1), dp(16), dp(1));

        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextColor(valueColor);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP);
        v.setPadding(0, dp(1), 0, dp(1));

        row.addView(l);
        row.addView(v);
        statsTable.addView(row);
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

    /** Local pose of the stats panel: just off the quad's right edge, top-aligned, so it doesn't
     *  cover the video. */
    private Pose statsPose(float videoHeightMeters) {
        float quadWidth = videoHeightMeters * aspectFor(currentPresenterMode);
        float x = (quadWidth / 2.0f) + STATS_GAP_METERS + (STATS_WIDTH_METERS / 2.0f);
        float y = (videoHeightMeters / 2.0f) - (STATS_HEIGHT_METERS / 2.0f);
        return new Pose(new Vector3(x, y, BAR_Z_METERS), Quaternion.Identity);
    }

    /** Local pose of the 3D-adjust sub-panel: centered just beneath the control bar. */
    private Pose adjustPose(float videoHeightMeters) {
        float barBottomY = -(videoHeightMeters / 2.0f) - BAR_GAP_METERS - (BAR_HEIGHT_METERS / 2.0f);
        float y = barBottomY - ADJUST_GAP_METERS - (ADJUST_HEIGHT_METERS / 2.0f);
        return new Pose(new Vector3(0.0f, y, BAR_Z_METERS), Quaternion.Identity);
    }

    /** Move the bar, stats and adjust panels when the quad height changes (mode switch). */
    private void repositionControlBar(float videoHeightMeters) {
        if (barPanel != null) {
            barPanel.setPose(barPose(videoHeightMeters));
        }
        if (statsPanel != null) {
            statsPanel.setPose(statsPose(videoHeightMeters));
        }
        if (adjustPanel != null) {
            adjustPanel.setPose(adjustPose(videoHeightMeters));
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
                || item.selectsMode == currentPresenterMode) {
            return;
        }
        // A switch kicks off an async surface handoff (GL pause/resume + resize); ignore a second
        // mode tap landing right after one so overlapping handoffs can't interleave and glitch.
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastModeSwitchMs < MODE_SWITCH_DEBOUNCE_MS) {
            return;
        }
        lastModeSwitchMs = now;
        currentPresenterMode = item.selectsMode;
        com.limelight.utils.Stereo3DRenderer.clientSbs = (currentPresenterMode == PresenterMode.CLIENT_SBS);
        
        if (activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).getStreamContainer().switchToClientSbs(currentPresenterMode == PresenterMode.CLIENT_SBS);
        }

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
        updateAdjustPanelVisibility();
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
     * Reset the video panel after the user has moved/resized it: back to the default distance,
     * orientation, scale, and size for the current mode. The bar and stats panel follow.
     */
    private void resetView() {
        if (surfaceEntity == null) {
            return;
        }
        surfaceEntity.setScale(1.0f);
        float aspect = aspectFor(currentPresenterMode);
        float height = DEFAULT_PANEL_HEIGHT_METERS;
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
                Pose inFront = head.compose(
                        new Pose(new Vector3(0.0f, 0.0f, -2.0f), Quaternion.Identity));
                surfaceEntity.setPose(inFront, Space.REAL_WORLD);
                placed = inFront;
            }
        } catch (Throwable t) {
            LimeLog.warning("XR reset: current head pose unavailable (" + t + ")");
        }
        if (placed == null) {
            surfaceEntity.setPose(new Pose(new Vector3(0.0f, 0.0f, -2.0f), Quaternion.Identity));
        }
        repositionControlBar(height);
    }

    /** Current pose of a render viewpoint (eye), or null if unavailable. */
    private static Pose poseOf(RenderViewpoint vp) {
        if (vp == null || vp.getState() == null) {
            return null;
        }
        RenderViewpoint.State s = vp.getState().getValue();
        return s != null ? s.getPose() : null;
    }

    /** Quad aspect (width/height). Host SBS shows each eye the host's native half-width slot
     *  (perEyeAspect). Client SBS renders the FULL frame per eye in both full- and half-width modes
     *  (half-width just squeezes it into W/2 and the compositor stretches it back across the quad),
     *  so the quad stays fullAspect and the same physical size — only the resolution changes. */
    private float aspectFor(PresenterMode mode) {
        return (mode == PresenterMode.HOST_SBS) ? perEyeAspect : fullAspect;
    }

    private SurfaceEntity.StereoMode stereoModeFor(PresenterMode mode) {
        return (mode == PresenterMode.NORMAL) ? SurfaceEntity.StereoMode.MONO : SurfaceEntity.StereoMode.SIDE_BY_SIDE;
    }

    /**
     * Color metadata for the XR surface. The stream's HDR-ness is the same in every presentation
     * mode, so when the negotiated stream is 10-bit HDR we tag the surface as HDR10 (BT2020
     * primaries + ST2084/PQ transfer, range per the full-range pref) in ALL modes:
     *  - Client SBS NEEDS it: its GL output buffers don't carry the decoder's HDR dataspace, so
     *    without the tag the compositor treats 10-bit PQ as SDR and washes it out.
     *  - Normal/Host SBS render the decoder's HDR buffers directly; tagging matches their dataspace.
     * Crucially we must NOT reset to the unset default when leaving Client SBS on an HDR stream:
     * once explicit metadata has been set, an unset default makes the compositor treat the direct
     * HDR paths as SDR and wash them out too. Only a genuinely SDR stream uses the unset default.
     */
    private void applyContentColorMetadata() {
        if (surfaceEntity == null) {
            return;
        }
        boolean hdr = (activity instanceof com.limelight.Game)
                && ((com.limelight.Game) activity).isStreamHdrActive();
        // Tell the AI-input shader to tonemap PQ->SDR for MiDaS when the stream is HDR.
        com.limelight.utils.Stereo3DRenderer.hdrInput = hdr;
        if (hdr) {
            surfaceEntity.setContentColorMetadata(new SurfaceEntity.ContentColorMetadata(
                    SurfaceEntity.ContentColorMetadata.ColorSpace.BT2020,
                    SurfaceEntity.ContentColorMetadata.ColorTransfer.ST2084,
                    prefConfig.fullRange
                            ? SurfaceEntity.ContentColorMetadata.ColorRange.FULL
                            : SurfaceEntity.ContentColorMetadata.ColorRange.LIMITED,
                    SurfaceEntity.ContentColorMetadata.MAX_CONTENT_LIGHT_LEVEL_UNKNOWN));
            LimeLog.info("XR: HDR ContentColorMetadata BT2020/ST2084/"
                    + (prefConfig.fullRange ? "FULL" : "LIMITED") + " (mode " + currentPresenterMode + ")");
        } else {
            surfaceEntity.setContentColorMetadata(
                    SurfaceEntity.ContentColorMetadata.Companion
                            .getDEFAULT_UNSET_CONTENT_COLOR_METADATA());
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

    /** Build one tile: a clickable vertical layout with the icon truly centered above the label.
     *  Clickable+focusable (with a selectable foreground) so the platform draws the gaze highlight
     *  — which works now that all tiles live in one panel. The OnClickListener handles the tap. */
    private View buildBarItemView(BarItem item) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClickable(true);
        col.setFocusable(true);
        col.setBackgroundColor(TILE_IDLE_COLOR);
        int pad = dp(3);
        col.setPadding(pad, pad, pad, pad);
        col.setOnClickListener(v -> {
            if (item.onTap != null) {
                item.onTap.run();
            }
        });
        // Visible press/hover feedback on top of the dark fill.
        TypedValue fg = new TypedValue();
        if (activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, fg, true) && fg.resourceId != 0) {
            col.setForeground(activity.getDrawable(fg.resourceId));
        }

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
        return col;
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

        BarItem(String label, int iconRes, PresenterMode selectsMode) {
            this.label = label;
            this.iconRes = iconRes;
            this.selectsMode = selectsMode;
        }

        /** Active mode tile gets an accent fill; everything else stays dark. */
        void setSelected(boolean selected) {
            if (root != null) {
                root.setBackgroundColor(selected ? TILE_ACTIVE_COLOR : TILE_IDLE_COLOR);
            }
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
     * <i>full-resolution</i> eye views side by side, so the surface must be twice as wide
     * ({@code 2W×H}) to preserve each eye's full input resolution. Every other mode presents a
     * single input-sized ({@code W×H}) frame (flat for NORMAL, host-packed for HOST_SBS), so the
     * surface is restored to {@code W×H}. Re-fetches the entity's surface in case the resize
     * re-creates it. Main-thread only (SceneCore is Activity-bound).
     */
    public void setClientSbsSurfaceSize(boolean fullStereo) {
        if (surfaceEntity == null) {
            return;
        }
        int width = fullStereo ? getClientSbsSurfaceWidth() : prefConfig.width;
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(width, prefConfig.height));
        videoSurface = surfaceEntity.getSurface();
    }

    /** XR surface / render width for Client SBS: 2W for full per-eye resolution (default), or W in
     *  half-width mode (≈half the GPU/heat). Used by {@link StreamContainer} for the renderer's
     *  output viewport and by {@link #setClientSbsSurfaceSize}. */
    public int getClientSbsSurfaceWidth() {
        return clientSbsHalfWidth ? prefConfig.width : prefConfig.width * 2;
    }

    /**
     * Tear down the entity/session. Mirrors {@code Stereo3DRenderer.onSurfaceDestroyed()} /
     * {@code StreamContainer.onDestroy()} ordering.
     */
    public void onDestroy() {
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
        if (adjustPanel != null) {
            if (!adjustPanel.isDisposed()) {
                adjustPanel.dispose();
            }
            adjustPanel = null;
        }

        videoSurface = null;
        statsTable = null;
        statsItem = null;
        barItems.clear();
        session = null;
    }
}
