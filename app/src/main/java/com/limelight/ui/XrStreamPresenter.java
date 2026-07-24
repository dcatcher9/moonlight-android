package com.limelight.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
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
import com.limelight.R;
import com.limelight.Game;
import com.limelight.binding.video.StreamPerformanceSnapshot;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.XrChoiceGroup;
import com.limelight.ui.xrcontrols.ClientSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.RawSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;
import com.limelight.ui.xrcontrols.XrBitrateControl;
import com.limelight.ui.xrcontrols.XrControlPanelLayout;
import com.limelight.ui.xrcontrols.XrControlUiState;
import com.limelight.ui.xrcontrols.XrModeChevronView;
import com.limelight.ui.xrcontrols.XrParameterGlyphView;
import com.limelight.ui.xrcontrols.XrResolutionSelector;
import com.limelight.utils.Stereo3DRenderer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * {@code StreamContainer} can supply the initial target through
 * {@code decoderRenderer.setRenderTarget(...)} and guarded live replacements through
 * {@code decoderRenderer.setOutputSurface(...)}.
 *
 * <p>This class is the <i>only</i> one that imports the Jetpack XR SDK, and it is constructed
 * exclusively behind {@code XrUtils.isXrDevice(...)}.
 *
 * <p>The quad is placed ~2 m in front, sized to one eye's aspect, and the user can move/resize it
 * (with a minimum distance clamp). A floating control panel beneath it offers four single-select
 * presentation modes, one reusable mode-options row, session settings, and stream actions.
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
    /** Taller independent pane fits per-mode quality controls without moving the level dock. */
    private static final float MODE_OPTIONS_MIN_HEIGHT_METERS = 0.52f;
    private static final float MODE_OPTIONS_MAX_HEIGHT_METERS = 0.90f;
    private static final float BAR_DIVIDER_METERS = 0.05f;    // extra width for the group divider
    private static final float BAR_GAP_METERS = 0.24f;        // quad bottom -> bar center
    private static final float BAR_Z_METERS = 0.02f;          // nudge toward viewer vs. the quad
    private static final float MODE_OPTIONS_GAP_METERS = 0.02f;
    private static final float MODE_OPTIONS_MIN_TILT_DEGREES = 10.0f;
    private static final float MODE_OPTIONS_MAX_TILT_DEGREES = 30.0f;
    private static final int TILE_IDLE_COLOR = 0xFF202831;    // resting tonal surface
    private static final int TILE_IDLE_BORDER_COLOR = 0xFF455466;
    private static final int TILE_ACTIVE_COLOR = 0xFF2D5F91;  // active tonal accent
    private static final int TILE_ACTIVE_BORDER_COLOR = 0xFF9AC7FF;
    private static final int PANEL_BACKGROUND_COLOR = 0xFF0D131A;
    private static final int PANEL_SECTION_COLOR = 0xFF18222D;
    private static final int PANEL_SUBTLE_COLOR = 0xFF121B24;
    private static final int PANEL_SECTION_BORDER_COLOR = 0xFF34485D;
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
    private static final float AUXILIARY_WIDTH_METERS = STATS_WIDTH_METERS;
    private static final float AUXILIARY_HEIGHT_METERS = 1.16f;
    private static final float GLANCE_WIDTH_METERS = 1.48f;
    private static final float GLANCE_HEIGHT_METERS = 0.11f;
    private static final float GLANCE_GAP_METERS = 0.07f;
    private static final float CINEMA_PRESET_DISTANCE_METERS = 2.0f;
    private static final long DOCK_AUTO_HIDE_DELAY_MS = 8000L;

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }

    public interface StatsVisibilityListener {
        void onStatsVisibilityChanged(boolean visible);
    }

    /**
     * Bridge to the current-session settings repository and reconnect coordinator. Keeping it at
     * the presentation boundary avoids teaching SceneCore UI code about preference storage.
     */
    public interface ControlActionListener {
        default boolean onSharedSettingSelected(SessionSettingsModel.Key key,
                                                String choiceId,
                                                SessionSettingsModel current) {
            return false;
        }

        default void onUseGlobalDefaultsRequested(SessionSettingsModel current) {
        }

        default void onApplyAndReconnectRequested(SessionSettingsModel pending) {
        }

        default boolean onModeQualitySettingSelected(PresenterMode mode,
                                                     SessionSettingsModel.Key key,
                                                     String choiceId,
                                                     ModeStreamQualityModel current) {
            return false;
        }

        default void onUseModeGlobalDefaultsRequested(PresenterMode mode,
                                                      ModeStreamQualityModel current) {
        }

        default boolean onClientSbsModelSelected(String modelId,
                                                 ClientSbsModeSettingsModel current) {
            return false;
        }

        default boolean onRawSbsPerEyeResolutionSelected(
                String resolutionId, RawSbsModeSettingsModel current) {
            return false;
        }

        default void onPresentationModeCommitted(PresenterMode mode) {
        }

        default void onLibraryRequested() {
        }

        /** Return true when the listener owns the end-session flow. */
        default boolean onEndSessionRequested() {
            return false;
        }
    }

    private static final ControlActionListener NO_OP_CONTROL_ACTION_LISTENER =
            new ControlActionListener() {
            };

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final OnSurfaceReadyListener listener;
    private final StatsVisibilityListener statsVisibilityListener;
    private ControlActionListener controlActionListener;
    private final XrViewStateStore viewStateStore;
    /** Apply-only handoff. Pose is intentionally not a durable per-PC preference. */
    private final XrReconnectViewState reconnectViewState;

    private Session session;
    private SurfaceEntity surfaceEntity;
    /** Written by the UI/SceneCore thread and read by GLSurfaceView's EGL thread. */
    private volatile Surface videoSurface;

    /** The single PanelEntity hosting the whole row of buttons. */
    private PanelEntity barPanel;
    private View controlBarRow;
    private TextView dockRevealPill;
    /** The control-bar items (one clickable tile each, all hosted in {@link #barPanel}). */
    private final List<BarItem> barItems = new ArrayList<>();
    /** Secondary session actions revealed inline by the compact + / - dock affordance. */
    private final List<BarItem> secondaryBarItems = new ArrayList<>();
    /** Reused contextual View hierarchy hosted by one independently tilted panel. */
    private FrameLayout modeOptionsHost;
    private PanelEntity modeOptionsPanel;
    private float modeOptionsHeightMeters = MODE_OPTIONS_MIN_HEIGHT_METERS;
    private View modeOptionsContentRoot;
    private boolean modeOptionsFitScheduled;
    private final Runnable modeOptionsFitRunnable = new Runnable() {
        @Override
        public void run() {
            modeOptionsFitScheduled = false;
            fitModeOptionsPanelToContent();
        }
    };
    private final XrControlUiState controlUiState = new XrControlUiState();
    private final android.os.Handler modeOptionsStatusHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshClientOptionsStatus = new Runnable() {
        @Override
        public void run() {
            if (!isClientOptionsOpen()) {
                return;
            }
            updateClientSbsRuntimeStatusView();
            modeOptionsStatusHandler.postDelayed(this, 1000L);
        }
    };
    private BarItem settingsItem;
    private BarItem cinemaItem;
    private BarItem statsItem;
    private BarItem expansionItem;
    private boolean secondaryActionsExpanded;

    private boolean cinemaViewExpanded;
    private float cinemaRestoreHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;
    private Pose cinemaRestorePose;

    /** Left-side PanelEntity for settings shared by every presentation mode in this session. */
    private PanelEntity auxiliaryPanel;
    private FrameLayout auxiliaryContentHost;
    private SessionSettingsModel sessionSettingsModel;
    private final EnumMap<PresenterMode, ModeStreamQualityModel> modeStreamQualityModels =
            new EnumMap<>(PresenterMode.class);
    private ClientSbsModeSettingsModel clientSbsModeSettingsModel;
    private RawSbsModeSettingsModel rawSbsModeSettingsModel;
    private boolean reconnectPending;
    private boolean sessionControlsEnabled = true;
    private final EnumMap<SessionSettingsModel.Key, XrChoiceGroup> sessionChoiceGroups =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, XrBitrateControl> sessionBitrateControls =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, TextView> sessionSourceViews =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, TextView> sessionPendingViews =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, XrParameterGlyphView> sessionGlyphViews =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private Button sessionDefaultsButton;
    private Button sessionApplyButton;
    private PresenterMode renderedModeOptionsMode;
    private XrResolutionSelector modeResolutionSelector;
    private XrChoiceGroup modeFpsChoiceGroup;
    private XrParameterGlyphView modeFpsGlyph;
    private XrBitrateControl modeBitrateControl;
    private TextView modeQualityCueView;
    private Button modeDefaultsButton;
    private Button modeApplyButton;
    private XrChoiceGroup clientModelChoiceGroup;
    private TextView clientModelSourceView;
    private TextView clientModelPendingView;
    private TextView clientAspectBucketView;
    private TextView clientRuntimeStatusView;
    private XrChoiceGroup rawSbsPerEyeResolutionChoiceGroup;
    private TextView rawSbsPerEyeResolutionSourceView;
    private TextView rawSbsPerEyeResolutionPendingView;
    private TextView rawSbsGeometryView;

    /** Passive glance strip above the video; it never intercepts input. */
    private PanelEntity glancePanel;
    private View glanceRoot;
    private TextView glanceIdentityView;
    private TextView glanceModeView;
    private TextView glanceStreamView;
    private TextView glanceStatusView;

    private final android.os.Handler dockVisibilityHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable collapseDockRunnable = this::collapseDockIfIdle;
    private boolean dockCollapsed;
    private View dockHoverTarget;
    private View dockFocusTarget;

    /** Compact performance-stats panel wrapped inward from the screen's right edge. */
    private PanelEntity statsPanel;
    private TextView statsTitle;
    private TableLayout statsTable;
    private boolean reuseStatsRows;
    private int primaryStatsRowCursor;
    private volatile boolean statsVisible;
    private boolean controlUiStateApplied;
    private XrControlUiState.Surface appliedContextualSurface = XrControlUiState.Surface.NONE;
    private boolean appliedStatsRequested;
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
    static final float STATS_TEXT_SP = 30f;
    static final float SESSION_SUMMARY_TEXT_SP = 25f;
    static final float SESSION_GROUP_TEXT_SP = 26f;
    static final float SESSION_ROW_TITLE_TEXT_SP = 29f;
    static final float SESSION_META_TEXT_SP = 22f;
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

    enum DockRevealInteraction {
        EXPLICIT_CLICK,
        PRESS_DOWN,
        HOVER,
        FOCUS
    }

    /** Host SBS AI makes the host emit a packed 2W' x H' side-by-side frame. */
    private static boolean isHostDepthPresenterMode(PresenterMode mode) {
        return mode == PresenterMode.HOST_SBS_AI;
    }

    /** True when a direct-decoder mode switch crosses the Host SBS AI packed-size boundary. */
    static boolean requiresHostSurfaceResize(PresenterMode previousMode, PresenterMode nextMode) {
        return isHostDepthPresenterMode(previousMode) != isHostDepthPresenterMode(nextMode);
    }

    /** Raw uses a different negotiated base width, so it cannot share the live-switch path. */
    static boolean requiresReconnectBeforeModeSwitch(
            PresenterMode previousMode, PresenterMode nextMode) {
        return (previousMode == PresenterMode.HOST_SBS_RAW)
                != (nextMode == PresenterMode.HOST_SBS_RAW);
    }

    /** True only when the decoder target or encoded dimensions change across the transition. */
    static boolean requiresDecoderTransition(PresenterMode previousMode, PresenterMode nextMode) {
        boolean crossesClientRenderer = (previousMode == PresenterMode.CLIENT_SBS_AI)
                != (nextMode == PresenterMode.CLIENT_SBS_AI);
        return crossesClientRenderer || requiresHostSurfaceResize(previousMode, nextMode);
    }

    static boolean canSynchronizeClientSbsHdrTransition(
            PresenterMode mode,
            boolean streamReady,
            boolean modeSwitchInProgress,
            boolean hdrTransitionInProgress) {
        return mode == PresenterMode.CLIENT_SBS_AI
                && streamReady
                && (!modeSwitchInProgress || hdrTransitionInProgress);
    }

    /**
     * Binds decoder callbacks to the exact UI transaction that requested them. A newer transition
     * supersedes the older generation before its posted main-thread callback can run.
     */
    static final class DecoderTransitionGenerationGate {
        private int modeGeneration;
        private int hdrGeneration;

        boolean beginMode(int generation) {
            if (generation <= 0) {
                return false;
            }
            modeGeneration = generation;
            hdrGeneration = 0;
            return true;
        }

        boolean beginHdr(int generation) {
            if (generation <= 0) {
                return false;
            }
            hdrGeneration = generation;
            modeGeneration = 0;
            return true;
        }

        boolean dispatchModeIfCurrent(int generation, Runnable action) {
            if (generation <= 0 || generation != modeGeneration || action == null) {
                return false;
            }
            action.run();
            return true;
        }

        boolean dispatchHdrIfCurrent(int generation, Runnable action) {
            if (generation <= 0 || generation != hdrGeneration || action == null) {
                return false;
            }
            action.run();
            return true;
        }

        boolean dispatchAnyIfCurrent(int generation, Runnable action) {
            if (generation <= 0
                    || (generation != modeGeneration && generation != hdrGeneration)
                    || action == null) {
                return false;
            }
            action.run();
            return true;
        }

        void clearMode() {
            modeGeneration = 0;
        }

        void clearHdr() {
            hdrGeneration = 0;
        }

        void clear() {
            modeGeneration = 0;
            hdrGeneration = 0;
        }
    }

    /** Which mode the SurfaceEntity is currently presenting (defaults to NORMAL). */
    private PresenterMode currentPresenterMode = PresenterMode.NORMAL;
    /** A saved Client SBS presentation to re-apply once the decoder has produced a valid Normal
     *  frame. Restoring before then would split a still-mono startup frame. */
    private PresenterMode deferredPresenterMode = PresenterMode.NORMAL;

    /** Debounce window for mode-tile taps: a switch starts an async surface handoff, so ignore a
     *  second tap that lands within this window (double-tap / impatient re-tap). */
    private static final long MODE_SWITCH_DEBOUNCE_MS = 600L;
    /** XR may deliver a click twice for one physical tap. Keep direct dock toggles deterministic. */
    private static final long CONTROL_TOGGLE_DEBOUNCE_MS = 400L;
    private long lastModeSwitchMs;
    private long lastStatsTileTapMs;
    private long lastCinemaTileTapMs;
    private long lastDockExpansionTapMs;
    private boolean modeSwitchInProgress;
    /** Successful surface handoff awaiting the fresh-IDR output before it may be persisted/shown. */
    private PresenterMode pendingDecoderTransitionMode;
    /** Client-SBS transfer flip awaiting a fresh decoder IDR and first new-format EGL swap. */
    private boolean clientSbsHdrTransitionInProgress;
    private final DecoderTransitionGenerationGate decoderTransitionGenerations =
            new DecoderTransitionGenerationGate();
    /** Mode changes resize or hand off the decoder surface, so they remain disabled until the
     *  decoder confirms that the initial stream frame has reached the XR surface. */
    private boolean streamPresentationReady;

    // The selected resolution defines the Full eye/view aspect. Raw Half uses half that physical
    // width so SceneCore does not stretch each W/2 x H encoded eye back to W/H.
    private float fullAspect;
    /** Kept so the resize affordance's bounds can be re-derived for the active mode's aspect. */
    private ResizableComponent resizable;

    public XrStreamPresenter(Activity activity, PreferenceConfiguration prefConfig,
                             OnSurfaceReadyListener listener,
                             StatsVisibilityListener statsVisibilityListener) {
        this(activity, prefConfig, listener, statsVisibilityListener,
                NO_OP_CONTROL_ACTION_LISTENER);
    }

    public XrStreamPresenter(Activity activity, PreferenceConfiguration prefConfig,
                             OnSurfaceReadyListener listener,
                             StatsVisibilityListener statsVisibilityListener,
                             ControlActionListener controlActionListener) {
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.listener = listener;
        this.statsVisibilityListener = statsVisibilityListener;
        this.controlActionListener = controlActionListener != null
                ? controlActionListener : NO_OP_CONTROL_ACTION_LISTENER;
        this.sessionSettingsModel = initialSessionSettingsModel(prefConfig);
        initializeModeQualityModels(prefConfig, sessionSettingsModel);
        this.clientSbsModeSettingsModel = initialClientSbsModeSettingsModel(prefConfig);
        this.rawSbsModeSettingsModel = initialRawSbsModeSettingsModel(prefConfig);
        this.viewStateStore = new XrViewStateStore(activity, activity.getIntent());
        restoreViewState();
        this.reconnectViewState = XrReconnectViewState.consumeFrom(activity.getIntent());
        if (reconnectViewState != null) {
            panelHeightMeters = reconnectViewState.panelHeightMeters;
            LimeLog.info("XR: applying reconnect view handoff at height "
                    + panelHeightMeters + " m"
                    + (reconnectViewState.realWorldPose != null ? " with pose" : ""));
        }
        // On a host-confirmed resume, restore direct Host/Raw presentation immediately. Client SBS
        // is deferred until after frame 1 because it requires a live decoder-to-GL handoff. A
        // fresh connection's state store returns Normal regardless of any older saved mode.
    }

    /** Install or replace the settings/session bridge after StreamContainer creates the presenter. */
    public void setControlActionListener(ControlActionListener listener) {
        controlActionListener = listener != null ? listener : NO_OP_CONTROL_ACTION_LISTENER;
    }

    /** Replace the immutable applied/pending snapshot and refresh an open Settings panel. */
    public void setSessionSettingsModel(SessionSettingsModel model) {
        sessionSettingsModel = java.util.Objects.requireNonNull(model, "model");
        if (controlUiState.getVisibleSurface()
                == XrControlUiState.Surface.SESSION_SETTINGS && auxiliaryContentHost != null) {
            updateSessionSettingsView();
        }
    }

    /** Replace Client SBS model/status data and refresh its open reusable subpane. */
    public void setClientSbsModeSettingsModel(ClientSbsModeSettingsModel model) {
        clientSbsModeSettingsModel = java.util.Objects.requireNonNull(model, "model");
        if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.MODE_OPTIONS
                && PresenterMode.CLIENT_SBS_AI.name().equals(controlUiState.getModeOptionsId())) {
            updateModeOptionsView();
        } else if (controlUiState.getVisibleSurface()
                == XrControlUiState.Surface.SESSION_SETTINGS && auxiliaryContentHost != null) {
            updateSessionApplyButton();
        }
    }

    /** Atomically replace all settings snapshots and update each open control tree only once. */
    public void setSettingsModels(SessionSettingsModel sessionModel,
                                  Map<PresenterMode, ModeStreamQualityModel> qualityModels,
                                  ClientSbsModeSettingsModel clientModel,
                                  RawSbsModeSettingsModel rawModel,
                                  boolean reconnectPending) {
        sessionSettingsModel = java.util.Objects.requireNonNull(sessionModel, "sessionModel");
        java.util.Objects.requireNonNull(qualityModels, "qualityModels");
        modeStreamQualityModels.clear();
        for (PresenterMode mode : PresenterMode.values()) {
            modeStreamQualityModels.put(mode, java.util.Objects.requireNonNull(
                    qualityModels.get(mode), "quality model for " + mode));
        }
        clientSbsModeSettingsModel = java.util.Objects.requireNonNull(clientModel, "clientModel");
        rawSbsModeSettingsModel = java.util.Objects.requireNonNull(rawModel, "rawModel");
        this.reconnectPending = reconnectPending;
        if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.SESSION_SETTINGS
                && auxiliaryContentHost != null) {
            updateSessionSettingsView();
        }
        else if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.MODE_OPTIONS) {
            updateModeOptionsView();
        }
        updateGlancePanel();
        updateDockVisibilityPolicy();
    }

    /** Prevent late settings or mode-choice taps after Apply has begun stream teardown. */
    public void setSessionControlsEnabled(boolean enabled) {
        sessionControlsEnabled = enabled;
        for (XrChoiceGroup group : sessionChoiceGroups.values()) {
            group.setEnabled(enabled);
        }
        if (sessionDefaultsButton != null) {
            sessionDefaultsButton.setEnabled(enabled);
        }
        if (clientModelChoiceGroup != null) {
            clientModelChoiceGroup.setEnabled(enabled);
        }
        if (rawSbsPerEyeResolutionChoiceGroup != null) {
            rawSbsPerEyeResolutionChoiceGroup.setEnabled(enabled);
        }
        if (modeResolutionSelector != null) {
            modeResolutionSelector.setEnabled(enabled);
        }
        if (modeFpsChoiceGroup != null) {
            modeFpsChoiceGroup.setEnabled(enabled);
        }
        if (modeBitrateControl != null) {
            modeBitrateControl.setEnabled(enabled);
        }
        for (XrBitrateControl control : sessionBitrateControls.values()) {
            control.setEnabled(enabled);
        }
        if (modeDefaultsButton != null) {
            modeDefaultsButton.setEnabled(enabled);
        }
        if (modeApplyButton != null) {
            modeApplyButton.setEnabled(enabled && reconnectPending);
        }
        for (BarItem item : barItems) {
            if (item.selectsMode != null && item.tapTarget != null) {
                item.tapTarget.setEnabled(enabled);
            }
        }
        updateSessionApplyButton();
        updateGlancePanel();
        revealDockTemporarily();
    }

    public SessionSettingsModel getSessionSettingsModel() {
        return sessionSettingsModel;
    }

    public ClientSbsModeSettingsModel getClientSbsModeSettingsModel() {
        return clientSbsModeSettingsModel;
    }

    public RawSbsModeSettingsModel getRawSbsModeSettingsModel() {
        return rawSbsModeSettingsModel;
    }

    private static SessionSettingsModel initialSessionSettingsModel(
            PreferenceConfiguration prefConfig) {
        String fps = Math.rint(prefConfig.fps) == prefConfig.fps
                ? String.format(Locale.US, "%.0f FPS", prefConfig.fps)
                : String.format(Locale.US, "%.2f FPS", prefConfig.fps);
        String bitrate = prefConfig.bitrate % 1000 == 0
                ? String.format(Locale.US, "%d Mbps", prefConfig.bitrate / 1000)
                : String.format(Locale.US, "%.1f Mbps", prefConfig.bitrate / 1000.0f);
        String codec;
        PreferenceConfiguration.FormatOption videoFormat = prefConfig.videoFormat != null
                ? prefConfig.videoFormat : PreferenceConfiguration.FormatOption.AUTO;
        switch (videoFormat) {
            case FORCE_AV1:
                codec = "AV1";
                break;
            case FORCE_HEVC:
                codec = "HEVC";
                break;
            case FORCE_H264:
                codec = "H.264";
                break;
            default:
                codec = "Automatic";
                break;
        }
        String pacing;
        switch (prefConfig.framePacing) {
            case PreferenceConfiguration.FRAME_PACING_BALANCED:
                pacing = "Balanced";
                break;
            case PreferenceConfiguration.FRAME_PACING_CAP_FPS:
                pacing = "Cap to FPS";
                break;
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
                pacing = "Maximum smoothness";
                break;
            default:
                pacing = "Lowest latency";
                break;
        }
        int channels = prefConfig.audioConfiguration != null
                ? prefConfig.audioConfiguration.channelCount : 2;
        String audio = channels == 8 ? "7.1 surround"
                : channels == 6 ? "5.1 surround" : "Stereo";
        SessionSettingsModel.Source global = SessionSettingsModel.Source.GLOBAL;
        return SessionSettingsModel.builder()
                .putApplied(SessionSettingsModel.Key.RESOLUTION,
                        prefConfig.width + " x " + prefConfig.height, global)
                .putApplied(SessionSettingsModel.Key.FRAME_RATE, fps, global)
                .putApplied(SessionSettingsModel.Key.BITRATE, bitrate, global)
                .putApplied(SessionSettingsModel.Key.HDR, prefConfig.enableHdr ? "On" : "Off", global)
                .putApplied(SessionSettingsModel.Key.VIDEO_RANGE,
                        prefConfig.fullRange ? "Full" : "Limited", global)
                .putApplied(SessionSettingsModel.Key.CODEC, codec, global)
                .putApplied(SessionSettingsModel.Key.FRAME_PACING, pacing, global)
                .putApplied(SessionSettingsModel.Key.AUDIO_LAYOUT, audio, global)
                .putApplied(SessionSettingsModel.Key.PLAY_AUDIO_ON_PC,
                        prefConfig.playHostAudio ? "On" : "Off", global)
                .build();
    }

    private void initializeModeQualityModels(PreferenceConfiguration prefConfig,
                                             SessionSettingsModel initial) {
        String resolution = prefConfig.width + "x" + prefConfig.height;
        String frameRate = Math.rint(prefConfig.fps) == prefConfig.fps
                ? String.format(Locale.US, "%.0f", prefConfig.fps)
                : String.format(Locale.US, "%.2f", prefConfig.fps);
        StreamQualityTuple tuple = new StreamQualityTuple(
                resolution, frameRate, prefConfig.bitrate);
        for (PresenterMode mode : PresenterMode.values()) {
            ModeStreamQualityModel.Builder builder = ModeStreamQualityModel.builder(
                    tuple, tuple, tuple, mode == PresenterMode.NORMAL);
            builder.put(SessionSettingsModel.Key.RESOLUTION,
                    initial.get(SessionSettingsModel.Key.RESOLUTION));
            builder.put(SessionSettingsModel.Key.FRAME_RATE,
                    initial.get(SessionSettingsModel.Key.FRAME_RATE));
            builder.put(SessionSettingsModel.Key.BITRATE,
                    initial.get(SessionSettingsModel.Key.BITRATE));
            modeStreamQualityModels.put(mode, builder.build());
        }
    }

    private static ClientSbsModeSettingsModel initialClientSbsModeSettingsModel(
            PreferenceConfiguration prefConfig) {
        String id = prefConfig.clientSbsDepthModelId != null
                ? prefConfig.clientSbsDepthModelId
                : PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2;
        boolean midas = PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2.equals(id);
        String name = midas ? "MiDaS 2.1 Small" : "Depth Anything V2 Small";
        return new ClientSbsModeSettingsModel(id, name, id, name,
                SessionSettingsModel.Source.GLOBAL,
                ClientSbsModeSettingsModel.selectBucket(
                        midas, prefConfig.width, prefConfig.height),
                "GPU-only · initializes on first use");
    }

    private static RawSbsModeSettingsModel initialRawSbsModeSettingsModel(
            PreferenceConfiguration prefConfig) {
        PreferenceConfiguration.RawSbsPerEyeResolution resolution =
                prefConfig.rawSbsPerEyeResolution != null
                        ? prefConfig.rawSbsPerEyeResolution
                        : PreferenceConfiguration.RawSbsPerEyeResolution.FULL;
        return new RawSbsModeSettingsModel(
                resolution, resolution, SessionSettingsModel.Source.GLOBAL);
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
        // Every mode's selected W x H defines the Full eye/view aspect. Raw SBS negotiates either
        // an exact 2W x H Full buffer or a W x H Half buffer; Half uses a W/(2H) physical quad so
        // SceneCore preserves the encoded eye aspect instead of stretching it.
        fullAspect = (float) prefConfig.width / prefConfig.height;
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

        // Frame 1 must target the exact startup buffer: codec-capped packed Host AI, the selected
        // Full/Half Raw packing, or ordinary W x H for Normal/Client.
        int[] initialPixelDimensions = initialSurfacePixelDimensions(
                currentPresenterMode, prefConfig.width, prefConfig.height, hostSbsVideoFormat,
                prefConfig.rawSbsPerEyeResolution);
        surfaceEntity.setSurfacePixelDimensions(
                new IntSize2d(initialPixelDimensions[0], initialPixelDimensions[1]));
        // Parent to the activity space (the rendered scene root) and make visibility explicit.
        // Without the explicit parent the entity isn't attached to the rendered scene graph, so the
        // quad never appears even though its surface is being fed/consumed.
        surfaceEntity.setParent(scene.getActivitySpace());
        if (reconnectViewState != null) {
            try {
                float parentWorldScaleY = scene.getActivitySpace()
                        .getNonUniformScale(Space.REAL_WORLD).getY();
                panelHeightMeters = XrReconnectViewState.localHeight(
                        reconnectViewState.panelHeightMeters, parentWorldScaleY);
                surfaceEntity.setShape(new SurfaceEntity.Shape.Quad(new FloatSize2d(
                        panelHeightMeters * aspectFor(currentPresenterMode),
                        panelHeightMeters)));
            } catch (Throwable error) {
                LimeLog.warning("XR: reconnect world-scale restore failed: " + error);
            }
        }
        if (reconnectViewState != null && reconnectViewState.realWorldPose != null) {
            try {
                surfaceEntity.setPose(reconnectViewState.realWorldPose, Space.REAL_WORLD);
            } catch (Throwable error) {
                LimeLog.warning("XR: reconnect pose restore failed: " + error);
            }
        }
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
        // tiles, mirroring a virtual-desktop control strip. The mode tiles form a single-select
        // group; Settings, Cinema, Library, Stats, and the compact utility action stay directly
        // reachable without hiding primary navigation in a submenu. The panel is parented to
        // the quad so it follows when the user moves it.
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
     * Build the fixed control panel below the video quad. Mode buttons share one level panel; the
     * reusable contextual row uses one separate tilted panel directly beneath it.
     *
     * @param videoHeightMeters the quad's height, used to place the bar just beneath it.
     */
    private void buildControlBar(float videoHeightMeters) {
        secondaryActionsExpanded = false;
        lastDockExpansionTapMs = 0L;
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
        BarItem settings = new BarItem(
                activity.getString(R.string.xr_home_settings),
                R.drawable.ic_settings, /* selectsMode= */ null);
        BarItem cinemaView = new BarItem(
                activity.getString(R.string.xr_bar_cinema_view),
                R.drawable.ic_xr_cinema_view, /* selectsMode= */ null);
        BarItem stats = new BarItem(
                activity.getString(R.string.xr_bar_stats),
                R.drawable.ic_xr_stats, /* selectsMode= */ null);
        BarItem library = new BarItem(
                activity.getString(R.string.xr_bar_library),
                R.drawable.ic_xr_library, /* selectsMode= */ null);
        BarItem dump = new BarItem(
                activity.getString(R.string.xr_bar_dump),
                R.drawable.ic_xr_dump, /* selectsMode= */ null);
        dump.secondary = true;
        BarItem endSession = new BarItem(
                activity.getString(R.string.xr_home_end_session),
                R.drawable.ic_xr_disconnect, /* selectsMode= */ null);
        endSession.secondary = true;
        endSession.destructive = true;
        BarItem expansion = new BarItem(
                activity.getString(R.string.xr_dock_expand_session_tools),
                R.drawable.ic_add_base, /* selectsMode= */ null,
                0.5f, /* iconOnly= */ true);
        normal.onTap = () -> onModeTileTapped(normal);
        clientSbsAi.onTap = () -> onModeTileTapped(clientSbsAi);
        hostSbsRaw.onTap = () -> onModeTileTapped(hostSbsRaw);
        hostSbsAi.onTap = () -> onModeTileTapped(hostSbsAi);
        settings.onTap = this::toggleSessionSettings;
        cinemaView.onTap = this::onCinemaTileTapped;
        library.onTap = this::openLibrary;
        stats.onTap = this::onStatsTileTapped;
        dump.onTap = XrStreamPresenter::requestHostDebugDump;
        endSession.onTap = this::requestEndSession;
        expansion.onTap = this::toggleSecondaryActions;
        settingsItem = settings;
        cinemaItem = cinemaView;
        statsItem = stats;
        expansionItem = expansion;

        barItems.clear();
        secondaryBarItems.clear();
        barItems.add(normal);
        barItems.add(hostSbsRaw);
        barItems.add(hostSbsAi);
        barItems.add(clientSbsAi);
        barItems.add(settings);
        barItems.add(cinemaView);
        barItems.add(library);
        barItems.add(stats);
        barItems.add(dump);
        barItems.add(endSession);
        barItems.add(expansion);
        secondaryBarItems.add(dump);
        secondaryBarItems.add(endSession);

        // One panel hosting a horizontal row of clickable tiles — like a normal toolbar. This is what
        // makes the platform draw the per-tile gaze highlight: a single panel whose View hierarchy
        // holds multiple clickable views highlights each one (the way several FABs on a 2D screen do),
        // whereas one interactable child PanelEntity per tile did NOT. Each tile handles its own tap.
        FrameLayout panelRoot = new FrameLayout(activity);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        boolean prevWasMode = false;
        boolean first = true;
        for (BarItem item : barItems) {
            boolean isMode = item.selectsMode != null;
            // Divider between the four presentation modes and the direct stream actions.
            if (!first && prevWasMode && !isMode) {
                bar.addView(makeDivider());
            }
            View tile = buildBarItemView(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, item.widthUnits);
            int m = dp(2);
            lp.setMargins(m, m, m, m);
            bar.addView(tile, lp);
            item.root = tile;
            if (item.secondary) {
                tile.setVisibility(View.GONE);
            }
            if (isMode) {
                item.setEnabled(streamPresentationReady);
            }
            prevWasMode = isMode;
            first = false;
        }

        panelRoot.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        controlBarRow = bar;

        Button revealButton = new Button(activity);
        styleControlButton(revealButton);
        revealButton.setText("\u25B4");
        revealButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        revealButton.setAllCaps(false);
        revealButton.setMinWidth(0);
        revealButton.setMinHeight(0);
        dockRevealPill = revealButton;
        dockRevealPill.setGravity(Gravity.CENTER);
        dockRevealPill.setClickable(true);
        dockRevealPill.setFocusable(true);
        dockRevealPill.setFocusableInTouchMode(true);
        dockRevealPill.setContentDescription(
                activity.getString(R.string.xr_dock_show_controls));
        // SceneCore-hosted Views do not have a normal window token. Samsung's tooltip popup logs
        // an error when it tries to resolve this anchor, while contentDescription remains valid
        // for accessibility and gaze narration.
        dockRevealPill.setPadding(dp(18), dp(8), dp(18), dp(8));
        dockRevealPill.setVisibility(View.GONE);
        // Some XR input paths focus a hosted TextView/Button on the first pinch and do not deliver
        // its click until the second. Reveal on the first press event as well; the click handler
        // remains for keyboard/controller activation and both paths are safely idempotent.
        configureDockRevealInteractions(dockRevealPill, this::revealDockTemporarily);
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        panelRoot.addView(dockRevealPill, pillParams);

        // Bake the initial highlights into the views before the panel is created.
        statsVisible = prefConfig.enablePerfOverlay;
        if (statsVisible) {
            devicePerformanceSampler.resetCpuBaseline();
            controlUiState.showStats();
        }
        updateModeSelection();

        // Width scales with the tile count so each tile stays square (tile size = bar height),
        // plus a little for the divider — adding tiles widens the bar instead of squeezing them.
        XrControlPanelLayout layout = controlBarLayout(videoHeightMeters);
        barPanel = PanelEntity.create(
                session, panelRoot, new FloatSize2d(layout.widthMeters, layout.heightMeters),
                "xr-control-bar", barPose(videoHeightMeters), surfaceEntity);
        barPanel.setEnabled(true);

        // The contextual row is a single panel, not one entity per control. It can therefore tilt
        // toward the face independently while its ordinary child Views retain native gaze taps.
        modeOptionsHost = new FrameLayout(activity);
        modeOptionsHost.setBackground(controlSurfaceBackground(
                PANEL_BACKGROUND_COLOR, PANEL_SECTION_BORDER_COLOR, 1));
        modeOptionsHeightMeters = MODE_OPTIONS_MIN_HEIGHT_METERS;
        modeOptionsPanel = PanelEntity.create(
                session, modeOptionsHost,
                new FloatSize2d(layout.widthMeters, modeOptionsHeightMeters),
                "xr-mode-options", modeOptionsPose(videoHeightMeters), surfaceEntity);
        modeOptionsPanel.setEnabled(false);

        if (statsVisible) {
            createStatsPanel(videoHeightMeters);
        }
        createAuxiliaryPanel(videoHeightMeters);
        applyControlUiState(false, "initial");
        createDepthStatusPanel(videoHeightMeters);
        createTransientMessagePanel();
        createGlancePanel(videoHeightMeters);
        updateGlancePanel();
        updateDockVisibilityPolicy();
    }

    /** Compact single-column performance panel beside the video. */
    private void createStatsPanel(float videoHeightMeters) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        // Fully opaque content avoids blending a second large translucent surface over video.
        root.setBackgroundColor(PANEL_BACKGROUND_COLOR);
        int p = statsDp(18);
        root.setPadding(p, p, p, p);

        statsTitle = new TextView(activity);
        statsTitle.setText(R.string.xr_stats_title);
        statsTitle.setTextColor(TILE_ACTIVE_BORDER_COLOR);
        statsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                (STATS_TEXT_SP + 4f) * STATS_CONTENT_SCALE);
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

    /** Create the left-side entity used by shared Session Settings. */
    private void createAuxiliaryPanel(float videoHeightMeters) {
        auxiliaryContentHost = new FrameLayout(activity);
        auxiliaryContentHost.setBackgroundColor(PANEL_BACKGROUND_COLOR);
        auxiliaryPanel = PanelEntity.create(
                session, auxiliaryContentHost,
                new FloatSize2d(AUXILIARY_WIDTH_METERS, AUXILIARY_HEIGHT_METERS),
                "xr-session-controls", statsPose(videoHeightMeters), surfaceEntity);
        auxiliaryPanel.setEnabled(false);
    }

    /** Create the slim, passive stream-at-a-glance strip above the video. */
    private void createGlancePanel(float videoHeightMeters) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(14), dp(5), dp(14), dp(5));
        root.setBackground(controlSurfaceBackground(0xE61A1E24, 0xFF45484F, 1));
        root.setClickable(false);
        root.setFocusable(false);
        glanceRoot = root;

        glanceIdentityView = glanceText(Color.WHITE);
        glanceModeView = glanceText(0xFFD6E5F5);
        glanceStreamView = glanceText(0xFFD6E5F5);
        glanceStatusView = glanceText(STATS_WARN_COLOR);
        glanceStatusView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        root.addView(glanceIdentityView, glanceLayoutParams(1.45f));
        root.addView(glanceModeView, glanceLayoutParams(1.0f));
        root.addView(glanceStreamView, glanceLayoutParams(1.55f));
        root.addView(glanceStatusView, glanceLayoutParams(0.72f));

        glancePanel = PanelEntity.create(session, root,
                new FloatSize2d(GLANCE_WIDTH_METERS, GLANCE_HEIGHT_METERS),
                "xr-stream-glance", glancePose(videoHeightMeters), surfaceEntity);
        glancePanel.setEnabled(true);
    }

    private TextView glanceText(int color) {
        TextView view = controlText("", 21f, color);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private LinearLayout.LayoutParams glanceLayoutParams(float weight) {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, weight);
    }

    private Pose glancePose(float videoHeightMeters) {
        float centerY = Math.max(0.0f, videoHeightMeters) / 2.0f
                + GLANCE_GAP_METERS + GLANCE_HEIGHT_METERS / 2.0f;
        return new Pose(new Vector3(0.0f, centerY, BAR_Z_METERS), Quaternion.Identity);
    }

    private void updateGlancePanel() {
        if (glancePanel == null || glancePanel.isDisposed() || glanceIdentityView == null) {
            return;
        }
        String pcName = activity.getIntent().getStringExtra(Game.EXTRA_PC_NAME);
        String appName = activity.getIntent().getStringExtra(Game.EXTRA_APP_NAME);
        String identity = pcName == null || pcName.isEmpty()
                ? activity.getString(R.string.xr_session_current_pc) : pcName;
        if (appName != null && !appName.isEmpty()) {
            identity += " \u00b7 " + appName;
        }
        glanceIdentityView.setText(identity);
        glanceModeView.setText(modeLabel(currentPresenterMode));

        ModeStreamQualityModel model = modeStreamQualityModels.get(currentPresenterMode);
        StreamQualityTuple live = model != null ? model.liveQuality
                : new StreamQualityTuple(prefConfig.width + "x" + prefConfig.height,
                        String.format(Locale.US, "%.0f", prefConfig.fps), prefConfig.bitrate);
        glanceStreamView.setText(activity.getString(R.string.xr_glance_stream,
                live.resolution.replace("x", " \u00d7 "), live.frameRate,
                prefConfig.enableHdr ? activity.getString(R.string.xr_glance_hdr)
                        : activity.getString(R.string.xr_glance_sdr)));

        boolean liveStatus = streamPresentationReady && sessionControlsEnabled
                && !modeSwitchInProgress && pendingDecoderTransitionMode == null
                && !isDepthBusy() && !reconnectPending;
        int statusText;
        if (!streamPresentationReady) {
            statusText = R.string.xr_glance_starting;
        }
        else if (!sessionControlsEnabled) {
            statusText = R.string.xr_glance_reconnecting;
        }
        else if (modeSwitchInProgress || pendingDecoderTransitionMode != null || isDepthBusy()) {
            statusText = R.string.xr_glance_switching;
        }
        else if (reconnectPending) {
            statusText = R.string.xr_glance_pending;
        }
        else {
            statusText = R.string.xr_glance_live;
        }
        glanceStatusView.setText(statusText);
        glanceStatusView.setTextColor(liveStatus ? STATS_ON_COLOR : STATS_WARN_COLOR);
        updateDockRevealPill(statusText, liveStatus);
    }

    private void updateDockRevealPill(int statusText, boolean liveStatus) {
        if (dockRevealPill == null) {
            return;
        }
        dockRevealPill.setText(activity.getString(R.string.xr_dock_reveal_status,
                modeLabel(currentPresenterMode), activity.getString(statusText)));
        dockRevealPill.setTextColor(liveStatus ? STATS_ON_COLOR : STATS_WARN_COLOR);
    }

    /** Reset the soft-collapse timer and immediately restore the full dock. */
    private void revealDockTemporarily() {
        dockVisibilityHandler.removeCallbacks(collapseDockRunnable);
        setDockCollapsed(false);
        updateDockVisibilityPolicy();
    }

    /** Re-evaluate whether the dock is idle enough to begin its eight-second timer. */
    private void updateDockVisibilityPolicy() {
        dockVisibilityHandler.removeCallbacks(collapseDockRunnable);
        if (!canAutoCollapseDock()) {
            setDockCollapsed(false);
            return;
        }
        if (!dockCollapsed) {
            dockVisibilityHandler.postDelayed(collapseDockRunnable,
                    DOCK_AUTO_HIDE_DELAY_MS);
        }
    }

    private void collapseDockIfIdle() {
        if (!canAutoCollapseDock()) {
            updateDockVisibilityPolicy();
            return;
        }
        setDockCollapsed(true);
    }

    private boolean canAutoCollapseDock() {
        return shouldAutoCollapseDock(streamPresentationReady, sessionControlsEnabled,
                controlUiState.getVisibleSurface(), controlUiState.isStatsVisible(),
                reconnectPending, modeSwitchInProgress,
                pendingDecoderTransitionMode != null, isDepthBusy(),
                dockHoverTarget != null, dockFocusTarget != null,
                secondaryActionsExpanded);
    }

    static boolean shouldAutoCollapseDock(boolean streamReady,
                                          boolean controlsEnabled,
                                          XrControlUiState.Surface visibleSurface,
                                          boolean statsVisible,
                                          boolean reconnectPending,
                                          boolean modeSwitchInProgress,
                                          boolean decoderTransitionPending,
                                          boolean depthBusy,
                                          boolean dockHovered,
                                          boolean dockFocused) {
        return shouldAutoCollapseDock(streamReady, controlsEnabled, visibleSurface,
                statsVisible, reconnectPending, modeSwitchInProgress,
                decoderTransitionPending, depthBusy, dockHovered, dockFocused, false);
    }

    static boolean shouldAutoCollapseDock(boolean streamReady,
                                          boolean controlsEnabled,
                                          XrControlUiState.Surface visibleSurface,
                                          boolean statsVisible,
                                          boolean reconnectPending,
                                          boolean modeSwitchInProgress,
                                          boolean decoderTransitionPending,
                                          boolean depthBusy,
                                          boolean dockHovered,
                                          boolean dockFocused,
                                          boolean secondaryActionsExpanded) {
        return streamReady
                && controlsEnabled
                && visibleSurface == XrControlUiState.Surface.NONE
                && !statsVisible
                && !reconnectPending
                && !modeSwitchInProgress
                && !decoderTransitionPending
                && !depthBusy
                && !dockHovered
                && !dockFocused
                && !secondaryActionsExpanded;
    }

    static boolean shouldRevealCollapsedDock(DockRevealInteraction interaction) {
        return interaction == DockRevealInteraction.EXPLICIT_CLICK
                || interaction == DockRevealInteraction.PRESS_DOWN
                || interaction == DockRevealInteraction.FOCUS;
    }

    /** Install all Galaxy XR activation paths so the collapsed pill never needs a second pinch. */
    static void configureDockRevealInteractions(View pill, Runnable revealAction) {
        java.util.Objects.requireNonNull(pill, "pill");
        java.util.Objects.requireNonNull(revealAction, "revealAction");
        pill.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    && shouldRevealCollapsedDock(DockRevealInteraction.PRESS_DOWN)) {
                revealAction.run();
            }
            return false;
        });
        pill.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && shouldRevealCollapsedDock(DockRevealInteraction.FOCUS)) {
                // On Galaxy XR, the first pinch can arrive as focus acquisition without a click.
                revealAction.run();
            }
        });
        pill.setOnClickListener(v -> {
            if (shouldRevealCollapsedDock(DockRevealInteraction.EXPLICIT_CLICK)) {
                revealAction.run();
            }
        });
    }

    private void setDockCollapsed(boolean collapsed) {
        dockCollapsed = collapsed;
        if (controlBarRow != null) {
            controlBarRow.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (dockRevealPill != null) {
            dockRevealPill.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }
        if (glanceRoot != null) {
            glanceRoot.setAlpha(collapsed ? 0.42f : 1.0f);
        }
    }

    /** Track native gaze hover and focus without consuming the ordinary Android View event. */
    private void attachDockActivityListeners(View view) {
        view.setOnHoverListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_HOVER_ENTER:
                case android.view.MotionEvent.ACTION_HOVER_MOVE:
                    dockHoverTarget = v;
                    revealDockTemporarily();
                    break;
                case android.view.MotionEvent.ACTION_HOVER_EXIT:
                    if (dockHoverTarget == v) {
                        dockHoverTarget = null;
                        updateDockVisibilityPolicy();
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dockFocusTarget = v;
                revealDockTemporarily();
            }
            else if (dockFocusTarget == v) {
                dockFocusTarget = null;
                updateDockVisibilityPolicy();
            }
        });
    }

    private void onModeTileTapped(BarItem item) {
        revealDockTemporarily();
        if (!streamPresentationReady || modeSwitchInProgress || item.selectsMode == null) {
            return;
        }

        XrControlUiState.Surface previousSurface = controlUiState.getVisibleSurface();
        XrControlUiState.ModeTileAction action = controlUiState.onModeTileTapped(
                item.selectsMode.name(), currentPresenterMode.name());
        if (action == XrControlUiState.ModeTileAction.OPTIONS_TOGGLED
                || previousSurface == XrControlUiState.Surface.MODE_OPTIONS) {
            applyControlUiState(true, "mode tile");
        }
        if (action == XrControlUiState.ModeTileAction.SELECT_MODE) {
            if (requiresReconnectBeforeModeSwitch(
                    currentPresenterMode, item.selectsMode)) {
                // Raw's negotiated base frame is already packed stereo, at either Full or Half
                // width. Never feed it into Client AI or ask Host AI to pack it again. Commit the
                // target first and let the replacement connection use the correct dimensions.
                LimeLog.info("XR: reconnecting before Raw SBS transport boundary "
                        + currentPresenterMode + " -> " + item.selectsMode);
                controlActionListener.onPresentationModeCommitted(item.selectsMode);
                return;
            }
            selectMode(item);
        }
    }

    private void toggleSessionSettings() {
        revealDockTemporarily();
        controlUiState.toggle(XrControlUiState.Surface.SESSION_SETTINGS);
        applyControlUiState(true, "session settings");
    }

    private void openLibrary() {
        revealDockTemporarily();
        controlActionListener.onLibraryRequested();
    }

    private void toggleSecondaryActions() {
        long now = android.os.SystemClock.uptimeMillis();
        if (!shouldAcceptControlToggle(now, lastDockExpansionTapMs)) {
            return;
        }
        lastDockExpansionTapMs = now;
        revealDockTemporarily();
        setSecondaryActionsExpanded(!secondaryActionsExpanded);
    }

    private void setSecondaryActionsExpanded(boolean expanded) {
        secondaryActionsExpanded = expanded;
        int visibility = secondaryActionVisibility(expanded);
        for (BarItem item : secondaryBarItems) {
            if (item.root != null) {
                item.root.setVisibility(visibility);
            }
            if (!expanded && item.tapTarget != null) {
                if (dockHoverTarget == item.tapTarget) {
                    dockHoverTarget = null;
                }
                if (dockFocusTarget == item.tapTarget) {
                    item.tapTarget.clearFocus();
                    dockFocusTarget = null;
                }
            }
        }
        if (expansionItem != null) {
            expansionItem.setIconAndDescription(
                    expansionIconResource(expanded),
                    activity.getString(expanded
                            ? R.string.xr_dock_collapse_session_tools
                            : R.string.xr_dock_expand_session_tools));
            expansionItem.setSelected(expanded);
        }
        if (controlBarRow != null) {
            controlBarRow.requestLayout();
            controlBarRow.invalidate();
        }
        if (barPanel != null && !barPanel.isDisposed()) {
            XrControlPanelLayout layout = controlBarLayout(panelHeightMeters);
            barPanel.setSize(new FloatSize2d(layout.widthMeters, layout.heightMeters));
            // Keep the right edge (and therefore the compact +/- gaze target) stationary while
            // the two secondary actions materialize immediately to its left.
            barPanel.setPose(barPose(panelHeightMeters));
        }
        LimeLog.info("XR: session tools " + (expanded ? "expanded" : "collapsed"));
        updateDockVisibilityPolicy();
    }

    static int secondaryActionVisibility(boolean expanded) {
        return expanded ? View.VISIBLE : View.GONE;
    }

    static int expansionIconResource(boolean expanded) {
        return expanded ? R.drawable.ic_remove_base : R.drawable.ic_add_base;
    }

    static float controlBarTileUnits(boolean expanded) {
        return 8.5f + (expanded ? 2.0f : 0.0f);
    }

    static float controlBarCenterX(boolean expanded, float compactWidthMeters,
                                   float expandedWidthMeters) {
        return expanded ? -(expandedWidthMeters - compactWidthMeters) / 2.0f : 0.0f;
    }

    private void requestEndSession() {
        if (!controlActionListener.onEndSessionRequested()
                && activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).endSessionFromXrControls();
        }
    }

    private void onCinemaTileTapped() {
        long now = android.os.SystemClock.uptimeMillis();
        if (!shouldAcceptControlToggle(now, lastCinemaTileTapMs)) {
            return;
        }
        lastCinemaTileTapMs = now;
        if (surfaceEntity == null || surfaceEntity.isDisposed()) {
            cinemaViewExpanded = false;
            cinemaRestorePose = null;
            cinemaRestoreHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;
            if (cinemaItem != null) {
                cinemaItem.setSelected(false);
            }
            return;
        }
        revealDockTemporarily();
        if (cinemaViewExpanded) {
            restoreCinemaView();
        }
        else {
            applyCinemaViewPreset();
        }
        if (cinemaItem != null) {
            cinemaItem.setSelected(cinemaViewExpanded);
        }
    }

    /** Direct dock action: Stats remains independent of every contextual settings surface. */
    private void onStatsTileTapped() {
        long now = android.os.SystemClock.uptimeMillis();
        if (!shouldAcceptControlToggle(now, lastStatsTileTapMs)) {
            return;
        }
        lastStatsTileTapMs = now;
        revealDockTemporarily();
        applyStatsDockAction(controlUiState);
        applyControlUiState(true, "stats dock");
    }

    static void applyStatsDockAction(XrControlUiState state) {
        state.toggleStats();
    }

    static boolean shouldAcceptControlToggle(long nowMs, long previousTapMs) {
        return previousTapMs == 0L
                || nowMs - previousTapMs >= CONTROL_TOGGLE_DEBOUNCE_MS;
    }

    /** Apply independent Stats visibility and the mutually-exclusive contextual surface. */
    private void applyControlUiState(boolean notifyStatsListener, String reason) {
        XrControlUiState.Surface visible = controlUiState.getVisibleSurface();
        boolean statsRequested = controlUiState.isStatsVisible();
        boolean showStats = statsRequested;
        boolean statsChanged = showStats != statsVisible;
        statsVisible = showStats;
        if (prefConfig.enablePerfOverlay != statsRequested) {
            prefConfig.enablePerfOverlay = statsRequested;
            PreferenceConfiguration.setPerformanceOverlayEnabled(activity, statsRequested);
        }

        if (!controlUiStateApplied || visible != appliedContextualSurface
                || statsRequested != appliedStatsRequested) {
            LimeLog.info("XR UI: context " + appliedContextualSurface + " -> " + visible
                    + ", stats=" + statsRequested + ", visible=" + showStats
                    + ", reason=" + reason);
            appliedContextualSurface = visible;
            appliedStatsRequested = statsRequested;
            controlUiStateApplied = true;
        }

        if (showStats && statsPanel == null && session != null) {
            devicePerformanceSampler.resetCpuBaseline();
            createStatsPanel(panelHeightMeters);
            repositionStatsPanel();
        }
        if (statsPanel != null && !statsPanel.isDisposed()) {
            statsPanel.setEnabled(showStats);
        }
        if (statsChanged && showStats) {
            devicePerformanceSampler.resetCpuBaseline();
        }
        if (statsChanged && notifyStatsListener && statsVisibilityListener != null) {
            statsVisibilityListener.onStatsVisibilityChanged(showStats);
        }

        boolean showModeOptions = visible == XrControlUiState.Surface.MODE_OPTIONS;
        modeOptionsStatusHandler.removeCallbacks(refreshClientOptionsStatus);
        if (modeOptionsHost != null) {
            if (showModeOptions) {
                renderModeOptions();
            }
        }
        if (modeOptionsPanel != null && !modeOptionsPanel.isDisposed()) {
            if (showModeOptions) {
                modeOptionsPanel.setPose(modeOptionsPose(panelHeightMeters));
            }
            modeOptionsPanel.setEnabled(showModeOptions);
        }
        if (isClientOptionsOpen()) {
            modeOptionsStatusHandler.postDelayed(refreshClientOptionsStatus, 1000L);
        }

        boolean showAuxiliary = visible == XrControlUiState.Surface.SESSION_SETTINGS;
        if (showAuxiliary && auxiliaryContentHost != null) {
            renderAuxiliaryContent();
        }
        else if (!showAuxiliary) {
            clearSessionSettingsReferences();
        }
        if (auxiliaryPanel != null && !auxiliaryPanel.isDisposed()) {
            if (showAuxiliary) {
                auxiliaryPanel.setPose(sessionSettingsPose(panelHeightMeters));
            }
            auxiliaryPanel.setEnabled(showAuxiliary);
        }

        if (settingsItem != null) {
            settingsItem.setSelected(visible == XrControlUiState.Surface.SESSION_SETTINGS);
        }
        if (cinemaItem != null) {
            cinemaItem.setSelected(cinemaViewExpanded);
        }
        if (statsItem != null) {
            statsItem.setSelected(showStats);
        }
        updateModeOptionsIndicators();
        revealDockTemporarily();
    }

    private void updateModeOptionsIndicators() {
        String openModeId = controlUiState.getVisibleSurface()
                == XrControlUiState.Surface.MODE_OPTIONS
                ? controlUiState.getModeOptionsId() : null;
        for (BarItem item : barItems) {
            if (item.selectsMode != null) {
                item.setOptionsOpen(item.selectsMode.name().equals(openModeId));
            }
        }
    }

    private void renderModeOptions() {
        if (modeOptionsHost == null) {
            return;
        }
        clearModeOptionsReferences();
        PresenterMode mode;
        try {
            mode = PresenterMode.valueOf(controlUiState.getModeOptionsId());
        } catch (RuntimeException e) {
            controlUiState.close();
            if (modeOptionsPanel != null && !modeOptionsPanel.isDisposed()) {
                modeOptionsPanel.setEnabled(false);
            }
            return;
        }

        renderedModeOptionsMode = mode;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        root.setPadding(padding, dp(14), padding, dp(14));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));
        header.setBackground(controlSurfaceBackground(
                PANEL_SECTION_COLOR, PANEL_SECTION_BORDER_COLOR, 1));
        addModeOptionsHeading(header, mode);
        switch (mode) {
            case NORMAL:
                addModeStatus(header, activity.getString(R.string.xr_mode_normal_source),
                        activity.getString(R.string.xr_mode_normal_detail));
                break;
            case HOST_SBS_RAW:
                addModeStatus(header, activity.getString(R.string.xr_mode_host_raw_source),
                        activity.getString(R.string.xr_mode_host_raw_detail));
                break;
            case HOST_SBS_AI:
                addModeStatus(header, activity.getString(R.string.xr_mode_host_ai_source),
                        hostDepthStatusText());
                break;
            case CLIENT_SBS_AI:
                addModeStatus(header, activity.getString(R.string.xr_client_gpu_status),
                        clientSbsRuntimeStatus(clientSbsModeSettingsModel));
                break;
        }
        root.addView(header);

        TextView qualityHeading = controlText(
                activity.getString(R.string.xr_mode_quality_heading),
                20f, TILE_ACTIVE_BORDER_COLOR);
        qualityHeading.setAllCaps(true);
        qualityHeading.setLetterSpacing(0.08f);
        qualityHeading.setTypeface(qualityHeading.getTypeface(),
                android.graphics.Typeface.BOLD);
        qualityHeading.setPadding(dp(4), dp(12), 0, dp(7));
        root.addView(qualityHeading);

        addModeQualityControls(root, mode);
        if (mode == PresenterMode.HOST_SBS_RAW) {
            addRawSbsModeOptions(root);
        }
        if (mode == PresenterMode.CLIENT_SBS_AI) {
            LinearLayout clientRow = new LinearLayout(activity);
            clientRow.setOrientation(LinearLayout.HORIZONTAL);
            clientRow.setGravity(Gravity.CENTER_VERTICAL);
            clientRow.setPadding(dp(14), dp(12), dp(14), dp(12));
            clientRow.setBackground(controlSurfaceBackground(
                    PANEL_SECTION_COLOR, PANEL_SECTION_BORDER_COLOR, 1));
            addClientSbsModeOptions(clientRow);
            LinearLayout.LayoutParams clientParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clientParams.topMargin = dp(10);
            root.addView(clientRow, clientParams);
        }
        addModeOptionsFooter(root, mode);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        modeOptionsContentRoot = root;
        modeOptionsHost.removeAllViews();
        modeOptionsHost.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scheduleModeOptionsPanelFit();
    }

    private void addModeQualityControls(LinearLayout root, PresenterMode mode) {
        ModeStreamQualityModel model = modeStreamQualityModels.get(mode);
        if (model == null) {
            return;
        }

        LinearLayout qualityRow = new LinearLayout(activity);
        qualityRow.setOrientation(LinearLayout.HORIZONTAL);
        qualityRow.setGravity(Gravity.TOP);
        qualityRow.setBaselineAligned(false);

        LinearLayout resolutionColumn = new LinearLayout(activity);
        resolutionColumn.setOrientation(LinearLayout.VERTICAL);
        resolutionColumn.setPadding(dp(14), dp(12), dp(14), dp(12));
        resolutionColumn.setBackground(controlSurfaceBackground(
                PANEL_SECTION_COLOR, PANEL_SECTION_BORDER_COLOR, 1));
        TextView resolutionTitle = controlText(
                activity.getString(R.string.title_resolution_list), 24f, Color.WHITE);
        resolutionTitle.setTypeface(resolutionTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        resolutionColumn.addView(resolutionTitle);
        modeResolutionSelector = new XrResolutionSelector(activity);
        modeResolutionSelector.setSelectedResolutionId(model.pendingQuality.resolution);
        modeResolutionSelector.setEnabled(sessionControlsEnabled);
        modeResolutionSelector.setOnResolutionSelectedListener(choiceId ->
                controlActionListener.onModeQualitySettingSelected(mode,
                        SessionSettingsModel.Key.RESOLUTION, choiceId,
                        modeStreamQualityModels.get(mode)));
        resolutionColumn.addView(modeResolutionSelector, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams resolutionParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.55f);
        resolutionParams.rightMargin = dp(6);
        qualityRow.addView(resolutionColumn, resolutionParams);

        LinearLayout tuningColumn = new LinearLayout(activity);
        tuningColumn.setOrientation(LinearLayout.VERTICAL);

        SessionSettingsModel.Value fps = model.get(SessionSettingsModel.Key.FRAME_RATE);
        LinearLayout fpsCard = new LinearLayout(activity);
        fpsCard.setOrientation(LinearLayout.VERTICAL);
        fpsCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        fpsCard.setBackground(controlSurfaceBackground(
                PANEL_SECTION_COLOR, PANEL_SECTION_BORDER_COLOR, 1));
        LinearLayout fpsHeading = new LinearLayout(activity);
        fpsHeading.setOrientation(LinearLayout.HORIZONTAL);
        fpsHeading.setGravity(Gravity.CENTER_VERTICAL);
        modeFpsGlyph = parameterGlyph(XrParameterGlyphView.Kind.FPS_MOTION_BARS,
                model.pendingQuality.frameRate);
        fpsHeading.addView(modeFpsGlyph, glyphLayoutParams());
        fpsHeading.addView(controlText(activity.getString(R.string.title_fps_list),
                24f, Color.WHITE));
        fpsCard.addView(fpsHeading);
        modeFpsChoiceGroup = buildChoiceGroup(fps.choices,
                qualityChoiceId(fps, model.pendingQuality.frameRate), fps.pendingValue,
                choiceId -> controlActionListener.onModeQualitySettingSelected(mode,
                        SessionSettingsModel.Key.FRAME_RATE, choiceId,
                        modeStreamQualityModels.get(mode)));
        modeFpsChoiceGroup.setEnabled(sessionControlsEnabled);
        fpsCard.addView(modeFpsChoiceGroup, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tuningColumn.addView(fpsCard);

        SessionSettingsModel.Value bitrate = model.get(SessionSettingsModel.Key.BITRATE);
        LinearLayout bitrateCard = new LinearLayout(activity);
        bitrateCard.setOrientation(LinearLayout.VERTICAL);
        bitrateCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        bitrateCard.setBackground(controlSurfaceBackground(
                PANEL_SECTION_COLOR, PANEL_SECTION_BORDER_COLOR, 1));
        TextView bitrateTitle = controlText(
                activity.getString(R.string.title_seekbar_bitrate), 24f, Color.WHITE);
        bitrateTitle.setTypeface(bitrateTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        bitrateCard.addView(bitrateTitle);
        modeBitrateControl = new XrBitrateControl(activity);
        String bitrateId = qualityChoiceId(bitrate,
                String.valueOf(model.pendingQuality.bitrateKbps));
        modeBitrateControl.setChoices(choicesOrCurrent(bitrate, bitrateId), bitrateId,
                bitrate.pendingValue, choiceId ->
                        controlActionListener.onModeQualitySettingSelected(mode,
                                SessionSettingsModel.Key.BITRATE, choiceId,
                                modeStreamQualityModels.get(mode)));
        modeBitrateControl.setEnabled(sessionControlsEnabled);
        bitrateCard.addView(modeBitrateControl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bitrateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bitrateParams.topMargin = dp(8);
        tuningColumn.addView(bitrateCard, bitrateParams);
        LinearLayout.LayoutParams tuningParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f);
        tuningParams.leftMargin = dp(6);
        qualityRow.addView(tuningColumn, tuningParams);

        root.addView(qualityRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        modeQualityCueView = controlText(modeQualityCue(model), 22f,
                model.requiresReconnect() ? STATS_WARN_COLOR : STATS_LABEL_COLOR);
        modeQualityCueView.setPadding(dp(14), dp(9), dp(14), dp(9));
        modeQualityCueView.setBackground(controlSurfaceBackground(
                PANEL_SUBTLE_COLOR, PANEL_SECTION_BORDER_COLOR, 1));
        LinearLayout.LayoutParams cueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cueParams.topMargin = dp(9);
        root.addView(modeQualityCueView, cueParams);
    }

    private void addModeOptionsFooter(LinearLayout root, PresenterMode mode) {
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setPadding(0, dp(10), 0, 0);

        modeDefaultsButton = compactButton(
                activity.getString(R.string.xr_session_use_session));
        modeDefaultsButton.setEnabled(sessionControlsEnabled);
        modeDefaultsButton.setOnClickListener(v -> controlActionListener
                .onUseModeGlobalDefaultsRequested(mode, modeStreamQualityModels.get(mode)));
        footer.addView(modeDefaultsButton);

        modeApplyButton = compactButton(activity.getString(R.string.xr_session_apply_reconnect));
        modeApplyButton.setBackgroundResource(R.drawable.xr_home_primary_action_background);
        modeApplyButton.setEnabled(sessionControlsEnabled && reconnectPending);
        modeApplyButton.setOnClickListener(v -> controlActionListener
                .onApplyAndReconnectRequested(sessionSettingsModel));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        applyParams.leftMargin = dp(10);
        footer.addView(modeApplyButton, applyParams);
        root.addView(footer);
    }

    private List<SessionSettingsModel.Choice> choicesOrCurrent(
            SessionSettingsModel.Value value, String choiceId) {
        if (!value.choices.isEmpty()) {
            return value.choices;
        }
        List<SessionSettingsModel.Choice> fallback = new ArrayList<>();
        String safeChoiceId = value.selectedChoiceId != null
                ? value.selectedChoiceId
                : (choiceId != null ? choiceId : "0");
        String safeLabel = value.pendingValue != null
                ? value.pendingValue
                : safeChoiceId;
        fallback.add(new SessionSettingsModel.Choice(safeChoiceId, safeLabel));
        return fallback;
    }

    private static String qualityChoiceId(SessionSettingsModel.Value value, String fallback) {
        return value.selectedChoiceId != null ? value.selectedChoiceId : fallback;
    }

    private String modeQualityCue(ModeStreamQualityModel model) {
        String source = modeQualitySource(model);
        String live = formatQualityTuple(model.liveQuality);
        if (model.requiresReconnect()) {
            return activity.getString(R.string.xr_mode_quality_reconnect, source, live);
        }
        if (model.hasPendingChanges()) {
            return activity.getString(R.string.xr_mode_quality_pending, source, live);
        }
        return activity.getString(R.string.xr_mode_quality_live, source, live);
    }

    private String modeQualitySource(ModeStreamQualityModel model) {
        for (SessionSettingsModel.Value value : model.getValues().values()) {
            if (value.source == SessionSettingsModel.Source.CURRENT_SESSION) {
                return activity.getString(R.string.xr_setting_source_session);
            }
        }
        return activity.getString(R.string.xr_setting_source_global);
    }

    private static String formatQualityTuple(StreamQualityTuple tuple) {
        String bitrate = tuple.bitrateKbps % 1000 == 0
                ? (tuple.bitrateKbps / 1000) + " Mbps"
                : String.format(Locale.US, "%.1f Mbps", tuple.bitrateKbps / 1000.0f);
        return tuple.resolution.replace("x", " \u00d7 ") + " @ " + tuple.frameRate
                + " FPS · " + bitrate;
    }

    private void addModeOptionsHeading(LinearLayout row, PresenterMode mode) {
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(0, 0, dp(18), 0);

        TextView title = controlText(modeLabel(mode), 30f, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        heading.addView(title);

        TextView active = controlText(activity.getString(mode == currentPresenterMode
                        ? R.string.xr_mode_active : R.string.xr_mode_options_title),
                22f, mode == currentPresenterMode ? STATS_ON_COLOR : STATS_LABEL_COLOR);
        heading.addView(active);
        row.addView(heading, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f));
    }

    private void addModeStatus(LinearLayout row, String label, String value) {
        LinearLayout status = labeledValue(label, value,
                value.toLowerCase(Locale.US).contains("unavailable")
                        ? STATS_ERROR_COLOR : Color.WHITE);
        row.addView(status, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2.0f));
    }

    private void addClientSbsModeOptions(LinearLayout row) {
        ClientSbsModeSettingsModel model = clientSbsModeSettingsModel;
        LinearLayout modelColumn = new LinearLayout(activity);
        modelColumn.setOrientation(LinearLayout.VERTICAL);
        modelColumn.setGravity(Gravity.CENTER_VERTICAL);
        modelColumn.setPadding(0, 0, dp(12), 0);
        String source = model.source == SessionSettingsModel.Source.GLOBAL
                ? activity.getString(R.string.xr_setting_source_global)
                : activity.getString(R.string.xr_setting_source_session);
        clientModelSourceView = controlText(
                activity.getString(R.string.xr_client_model) + " \u00b7 " + source,
                22f, STATS_LABEL_COLOR);
        modelColumn.addView(clientModelSourceView);

        clientModelChoiceGroup = buildChoiceGroup(model.choices, model.selectedChoiceId,
                model.pendingModelName, choiceId -> {
                    return controlActionListener.onClientSbsModelSelected(
                            choiceId, clientSbsModeSettingsModel);
                });
        clientModelChoiceGroup.setEnabled(sessionControlsEnabled);
        LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        choiceParams.topMargin = dp(4);
        modelColumn.addView(clientModelChoiceGroup, choiceParams);

        clientModelPendingView = controlText("", SESSION_META_TEXT_SP, STATS_LABEL_COLOR);
        clientModelPendingView.setPadding(0, dp(3), 0, 0);
        modelColumn.addView(clientModelPendingView);
        updateClientModelPendingView(model);
        row.addView(modelColumn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 2.2f));

        LinearLayout aspect = labeledValue(
                activity.getString(R.string.xr_client_aspect_bucket),
                model.bucket, Color.WHITE);
        clientAspectBucketView = (TextView) aspect.getChildAt(1);
        row.addView(aspect,
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f));
        String runtimeStatus = clientSbsRuntimeStatus(model);
        LinearLayout runtime = labeledValue(
                activity.getString(R.string.xr_client_gpu_status), runtimeStatus,
                clientRuntimeStatusColor(runtimeStatus));
        clientRuntimeStatusView = (TextView) runtime.getChildAt(1);
        row.addView(runtime,
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));
    }

    private void addRawSbsModeOptions(LinearLayout root) {
        RawSbsModeSettingsModel model = rawSbsModeSettingsModel;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(controlSurfaceBackground(
                PANEL_SECTION_COLOR, PANEL_SECTION_BORDER_COLOR, 1));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = controlText(
                activity.getString(R.string.xr_raw_per_eye_resolution),
                24f, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        rawSbsPerEyeResolutionSourceView = controlText(
                rawSbsSourceText(model), SESSION_META_TEXT_SP, STATS_LABEL_COLOR);
        heading.addView(rawSbsPerEyeResolutionSourceView);
        card.addView(heading);

        rawSbsPerEyeResolutionChoiceGroup = buildChoiceGroup(
                model.choices, model.selectedChoiceId, model.pendingResolutionName,
                choiceId -> controlActionListener.onRawSbsPerEyeResolutionSelected(
                        choiceId, rawSbsModeSettingsModel));
        rawSbsPerEyeResolutionChoiceGroup.setEnabled(sessionControlsEnabled);
        LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        choiceParams.topMargin = dp(7);
        card.addView(rawSbsPerEyeResolutionChoiceGroup, choiceParams);

        rawSbsGeometryView = controlText(
                rawSbsGeometryText(model), 20f, STATS_LABEL_COLOR);
        rawSbsGeometryView.setPadding(0, dp(7), 0, 0);
        card.addView(rawSbsGeometryView);

        rawSbsPerEyeResolutionPendingView =
                controlText("", SESSION_META_TEXT_SP, STATS_WARN_COLOR);
        rawSbsPerEyeResolutionPendingView.setPadding(0, dp(4), 0, 0);
        card.addView(rawSbsPerEyeResolutionPendingView);
        updateRawSbsPendingView(model);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        root.addView(card, params);
    }

    private String rawSbsSourceText(RawSbsModeSettingsModel model) {
        return model.source == SessionSettingsModel.Source.GLOBAL
                ? activity.getString(R.string.xr_setting_source_global)
                : activity.getString(R.string.xr_setting_source_session);
    }

    private String rawSbsGeometryText(RawSbsModeSettingsModel model) {
        ModeStreamQualityModel quality =
                modeStreamQualityModels.get(PresenterMode.HOST_SBS_RAW);
        int logicalWidth = prefConfig.width;
        int logicalHeight = prefConfig.height;
        if (quality != null) {
            try {
                String[] dimensions = quality.pendingQuality.resolution.split("x", 2);
                logicalWidth = Integer.parseInt(dimensions[0]);
                logicalHeight = Integer.parseInt(dimensions[1]);
            }
            catch (RuntimeException ignored) {
                // The controller will repair malformed custom dimensions before reconnect.
            }
        }
        int[] packed;
        try {
            packed = PreferenceConfiguration.rawSbsPackedDimensions(
                    logicalWidth, logicalHeight, model.pendingResolution);
        }
        catch (IllegalArgumentException unsupported) {
            return activity.getString(R.string.xr_raw_geometry_unsupported);
        }
        return activity.getString(R.string.xr_raw_geometry,
                packed[0] / 2, packed[1], packed[0], packed[1]);
    }

    private void updateRawSbsPendingView(RawSbsModeSettingsModel model) {
        if (rawSbsPerEyeResolutionPendingView == null) {
            return;
        }
        rawSbsPerEyeResolutionPendingView.setVisibility(
                model.hasPendingChange() ? View.VISIBLE : View.GONE);
        if (model.hasPendingChange()) {
            rawSbsPerEyeResolutionPendingView.setText(activity.getString(
                    R.string.xr_setting_pending_active, model.appliedResolutionName));
        }
    }

    private void clearModeOptionsReferences() {
        renderedModeOptionsMode = null;
        modeResolutionSelector = null;
        modeFpsChoiceGroup = null;
        modeFpsGlyph = null;
        modeBitrateControl = null;
        modeQualityCueView = null;
        modeDefaultsButton = null;
        modeApplyButton = null;
        clientModelChoiceGroup = null;
        clientModelSourceView = null;
        clientModelPendingView = null;
        clientAspectBucketView = null;
        clientRuntimeStatusView = null;
        rawSbsPerEyeResolutionChoiceGroup = null;
        rawSbsPerEyeResolutionSourceView = null;
        rawSbsPerEyeResolutionPendingView = null;
        rawSbsGeometryView = null;
        modeOptionsContentRoot = null;
        modeOptionsFitScheduled = false;
        modeOptionsStatusHandler.removeCallbacks(modeOptionsFitRunnable);
    }

    private void updateModeOptionsView() {
        PresenterMode mode = renderedModeOptionsMode;
        if (mode == null || !mode.name().equals(controlUiState.getModeOptionsId())) {
            renderModeOptions();
            return;
        }
        ModeStreamQualityModel model = modeStreamQualityModels.get(mode);
        if (model == null || modeResolutionSelector == null || modeFpsChoiceGroup == null
                || modeBitrateControl == null) {
            renderModeOptions();
            return;
        }

        modeResolutionSelector.setSelectedResolutionId(model.pendingQuality.resolution);
        modeResolutionSelector.setEnabled(sessionControlsEnabled);
        SessionSettingsModel.Value fps = model.get(SessionSettingsModel.Key.FRAME_RATE);
        String fpsId = qualityChoiceId(fps, model.pendingQuality.frameRate);
        if (!modeFpsChoiceGroup.setSelectedValue(fpsId)) {
            configureChoiceGroup(modeFpsChoiceGroup, fps.choices, fpsId, fps.pendingValue,
                    choiceId -> controlActionListener.onModeQualitySettingSelected(mode,
                            SessionSettingsModel.Key.FRAME_RATE, choiceId,
                            modeStreamQualityModels.get(mode)));
        }
        modeFpsChoiceGroup.setEnabled(sessionControlsEnabled);
        if (modeFpsGlyph != null) {
            modeFpsGlyph.setParameter(XrParameterGlyphView.Kind.FPS_MOTION_BARS,
                    model.pendingQuality.frameRate);
        }
        SessionSettingsModel.Value bitrate = model.get(SessionSettingsModel.Key.BITRATE);
        String bitrateId = qualityChoiceId(bitrate,
                String.valueOf(model.pendingQuality.bitrateKbps));
        // Keep bitrate independent from resolution/fps. Rebuild the choice model so the slider
        // remains in sync with the latest pending value and any out-of-preset entry.
        modeBitrateControl.setChoices(choicesOrCurrent(bitrate, bitrateId), bitrateId,
                bitrate.pendingValue, choiceId ->
                        controlActionListener.onModeQualitySettingSelected(mode,
                                SessionSettingsModel.Key.BITRATE, choiceId,
                                modeStreamQualityModels.get(mode)));
        modeBitrateControl.setEnabled(sessionControlsEnabled);
        modeQualityCueView.setText(modeQualityCue(model));
        modeQualityCueView.setTextColor(model.requiresReconnect()
                ? STATS_WARN_COLOR : STATS_LABEL_COLOR);
        modeDefaultsButton.setEnabled(sessionControlsEnabled);
        modeApplyButton.setEnabled(sessionControlsEnabled && reconnectPending);
        if (mode == PresenterMode.CLIENT_SBS_AI) {
            updateClientSbsOptionsView();
        }
        else if (mode == PresenterMode.HOST_SBS_RAW) {
            updateRawSbsOptionsView();
        }
        scheduleModeOptionsPanelFit();
    }

    private void updateRawSbsOptionsView() {
        if (rawSbsPerEyeResolutionChoiceGroup == null) {
            renderModeOptions();
            return;
        }
        RawSbsModeSettingsModel model = rawSbsModeSettingsModel;
        if (!rawSbsPerEyeResolutionChoiceGroup.setSelectedValue(
                model.selectedChoiceId)) {
            configureChoiceGroup(rawSbsPerEyeResolutionChoiceGroup,
                    model.choices, model.selectedChoiceId, model.pendingResolutionName,
                    choiceId -> controlActionListener.onRawSbsPerEyeResolutionSelected(
                            choiceId, rawSbsModeSettingsModel));
        }
        rawSbsPerEyeResolutionChoiceGroup.setEnabled(sessionControlsEnabled);
        rawSbsPerEyeResolutionSourceView.setText(rawSbsSourceText(model));
        rawSbsGeometryView.setText(rawSbsGeometryText(model));
        updateRawSbsPendingView(model);
    }

    private void updateClientSbsOptionsView() {
        if (clientModelChoiceGroup == null) {
            return;
        }
        ClientSbsModeSettingsModel model = clientSbsModeSettingsModel;
        if (!clientModelChoiceGroup.setSelectedValue(model.selectedChoiceId)) {
            configureChoiceGroup(clientModelChoiceGroup, model.choices,
                    model.selectedChoiceId, model.pendingModelName, choiceId ->
                            controlActionListener.onClientSbsModelSelected(
                                    choiceId, clientSbsModeSettingsModel));
        }
        clientModelChoiceGroup.setEnabled(sessionControlsEnabled);
        String source = model.source == SessionSettingsModel.Source.GLOBAL
                ? activity.getString(R.string.xr_setting_source_global)
                : activity.getString(R.string.xr_setting_source_session);
        clientModelSourceView.setText(
                activity.getString(R.string.xr_client_model) + " \u00b7 " + source);
        updateClientModelPendingView(model);
        clientAspectBucketView.setText(model.bucket);
        updateClientSbsRuntimeStatusView();
    }

    private void updateClientModelPendingView(ClientSbsModeSettingsModel model) {
        if (clientModelPendingView == null) {
            return;
        }
        clientModelPendingView.setVisibility(
                model.hasPendingModelChange() ? View.VISIBLE : View.GONE);
        if (model.hasPendingModelChange()) {
            clientModelPendingView.setText(activity.getString(
                    R.string.xr_setting_pending_active, model.appliedModelName));
        }
    }

    private void updateClientSbsRuntimeStatusView() {
        if (clientRuntimeStatusView == null) {
            return;
        }
        String status = clientSbsRuntimeStatus(clientSbsModeSettingsModel);
        clientRuntimeStatusView.setText(status);
        clientRuntimeStatusView.setTextColor(clientRuntimeStatusColor(status));
    }

    private int clientRuntimeStatusColor(String status) {
        return status.toLowerCase(Locale.US).contains("unavailable")
                ? STATS_ERROR_COLOR : Color.WHITE;
    }

    private boolean isClientOptionsOpen() {
        return controlUiState.getVisibleSurface() == XrControlUiState.Surface.MODE_OPTIONS
                && PresenterMode.CLIENT_SBS_AI.name().equals(
                controlUiState.getModeOptionsId());
    }

    private String clientSbsRuntimeStatus(ClientSbsModeSettingsModel model) {
        if (!(activity instanceof com.limelight.Game)) {
            return model.status;
        }
        StreamContainer container = ((com.limelight.Game) activity).getStreamContainer();
        if (container == null) {
            return model.status;
        }
        String backend = container.getClientSbsBackendStatus();
        if (currentPresenterMode != PresenterMode.CLIENT_SBS_AI
                && (backend == null || "Initializing".equals(backend)
                || "Unavailable".equals(backend))) {
            return model.status;
        }
        return depthBackendName(backend);
    }

    private String hostDepthStatusText() {
        switch (depthStatusPhase) {
            case 1:
                return activity.getString(R.string.xr_mode_host_loading);
            case 2:
                return activity.getString(R.string.xr_mode_host_ready);
            case 3:
                return activity.getString(R.string.xr_mode_host_initializing);
            default:
                return currentPresenterMode == PresenterMode.HOST_SBS_AI
                        ? activity.getString(R.string.xr_mode_host_waiting)
                        : activity.getString(R.string.xr_mode_starts_when_selected);
        }
    }

    private void renderAuxiliaryContent() {
        auxiliaryContentHost.removeAllViews();
        View content = buildSessionSettingsView();
        auxiliaryContentHost.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private View buildSessionSettingsView() {
        clearSessionSettingsReferences();
        LinearLayout root = panelColumn();

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = controlText(activity.getString(R.string.xr_session_settings_title),
                32f, TILE_ACTIVE_BORDER_COLOR);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        sessionDefaultsButton = compactButton(
                activity.getString(R.string.xr_session_use_global));
        sessionDefaultsButton.setEnabled(sessionControlsEnabled);
        sessionDefaultsButton.setOnClickListener(v -> controlActionListener
                .onUseGlobalDefaultsRequested(sessionSettingsModel));
        header.addView(sessionDefaultsButton);
        root.addView(header);

        String pcName = activity.getIntent().getStringExtra(Game.EXTRA_PC_NAME);
        String appName = activity.getIntent().getStringExtra(Game.EXTRA_APP_NAME);
        if ((pcName != null && !pcName.isEmpty()) || (appName != null && !appName.isEmpty())) {
            String identity = pcName != null && !pcName.isEmpty() ? pcName
                    : activity.getString(R.string.xr_session_current_pc);
            if (appName != null && !appName.isEmpty()) {
                identity += " \u00b7 " + appName;
            }
            root.addView(controlText(identity, 24f, Color.WHITE));
        }

        TextView summary = controlText(
                activity.getString(R.string.xr_session_settings_summary),
                SESSION_SUMMARY_TEXT_SP, STATS_LABEL_COLOR);
        summary.setPadding(0, dp(6), 0, dp(10));
        root.addView(summary);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.HORIZONTAL);
        rows.setBaselineAligned(false);
        LinearLayout videoColumn = sessionSettingsColumn(
                activity.getString(R.string.xr_session_video_group));
        LinearLayout deliveryColumn = sessionSettingsColumn(
                activity.getString(R.string.xr_session_delivery_group));
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            if (key.isModeStreamQuality() && key != SessionSettingsModel.Key.BITRATE) {
                continue;
            }
            SessionSettingsModel.Value value = sessionSettingsModel.get(key);
            if (value != null) {
                (sharedSettingColumn(key) == 0 ? videoColumn : deliveryColumn)
                        .addView(buildSessionSettingRow(key, value));
            }
        }
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        videoParams.rightMargin = dp(6);
        rows.addView(videoColumn, videoParams);
        LinearLayout.LayoutParams deliveryParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        deliveryParams.leftMargin = dp(6);
        rows.addView(deliveryColumn, deliveryParams);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        sessionApplyButton = compactButton(reconnectPending
                ? activity.getString(R.string.xr_session_apply_reconnect)
                : activity.getString(R.string.xr_session_no_reconnect_changes));
        sessionApplyButton.setBackgroundResource(R.drawable.xr_home_primary_action_background);
        sessionApplyButton.setEnabled(sessionControlsEnabled && reconnectPending);
        sessionApplyButton.setOnClickListener(v -> controlActionListener
                .onApplyAndReconnectRequested(sessionSettingsModel));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        applyParams.gravity = Gravity.END;
        applyParams.topMargin = dp(10);
        root.addView(sessionApplyButton, applyParams);
        return root;
    }

    private LinearLayout sessionSettingsColumn(String label) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView heading = controlText(label, SESSION_GROUP_TEXT_SP,
                TILE_ACTIVE_BORDER_COLOR);
        heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        heading.setPadding(dp(2), 0, 0, dp(6));
        column.addView(heading);
        return column;
    }

    /** Stable semantic grouping keeps the six shared controls scannable in two short columns. */
    static int sharedSettingColumn(SessionSettingsModel.Key key) {
        switch (key) {
            case HDR:
            case VIDEO_RANGE:
            case CODEC:
                return 0;
            case FRAME_PACING:
            case AUDIO_LAYOUT:
            case PLAY_AUDIO_ON_PC:
            case BITRATE:
                return 1;
            default:
                throw new IllegalArgumentException("Mode quality does not belong in this pane: "
                        + key);
        }
    }

    private View buildSessionSettingRow(SessionSettingsModel.Key key,
                                        SessionSettingsModel.Value value) {
        if (key == SessionSettingsModel.Key.BITRATE) {
            return buildSessionBitrateSettingRow(key, value);
        }
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(16), dp(18), dp(16));
        row.setBackground(controlSurfaceBackground(
                PANEL_SECTION_COLOR, PANEL_SECTION_BORDER_COLOR, 1));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        XrParameterGlyphView glyph = sessionSettingGlyph(key, value);
        if (glyph != null) {
            sessionGlyphViews.put(key, glyph);
            heading.addView(glyph, glyphLayoutParams());
        }
        TextView title = controlText(sessionSettingLabel(key),
                SESSION_ROW_TITLE_TEXT_SP, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        String source = value.source == SessionSettingsModel.Source.GLOBAL
                ? activity.getString(R.string.xr_setting_source_global)
                : activity.getString(R.string.xr_setting_source_session);
        TextView sourceView = controlText(source, SESSION_META_TEXT_SP, STATS_LABEL_COLOR);
        sessionSourceViews.put(key, sourceView);
        heading.addView(sourceView);
        row.addView(heading);

        XrChoiceGroup choices = buildChoiceGroup(value.choices, value.selectedChoiceId,
                value.pendingValue, choiceId -> controlActionListener.onSharedSettingSelected(
                        key, choiceId, sessionSettingsModel));
        choices.setEnabled(sessionControlsEnabled);
        sessionChoiceGroups.put(key, choices);
        LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        choiceParams.topMargin = dp(7);
        row.addView(choices, choiceParams);

        TextView pending = controlText("", SESSION_META_TEXT_SP, STATS_LABEL_COLOR);
        pending.setPadding(0, dp(4), 0, 0);
        sessionPendingViews.put(key, pending);
        row.addView(pending);
        updateSessionPendingView(pending, value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private View buildSessionBitrateSettingRow(SessionSettingsModel.Key key,
                                               SessionSettingsModel.Value value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(16), dp(18), dp(16));
        row.setBackground(controlSurfaceBackground(
                PANEL_SECTION_COLOR, PANEL_SECTION_BORDER_COLOR, 1));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = controlText(sessionSettingLabel(key),
                SESSION_ROW_TITLE_TEXT_SP, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        String source = value.source == SessionSettingsModel.Source.GLOBAL
                ? activity.getString(R.string.xr_setting_source_global)
                : activity.getString(R.string.xr_setting_source_session);
        TextView sourceView = controlText(source, SESSION_META_TEXT_SP, STATS_LABEL_COLOR);
        sessionSourceViews.put(key, sourceView);
        heading.addView(sourceView);
        row.addView(heading);

        XrBitrateControl bitrateControl = new XrBitrateControl(activity);
        bitrateControl.setChoices(choicesOrCurrent(value, value.selectedChoiceId),
                value.selectedChoiceId, value.pendingValue, choiceId ->
                        controlActionListener.onSharedSettingSelected(
                                key, choiceId, sessionSettingsModel));
        bitrateControl.setEnabled(sessionControlsEnabled);
        sessionBitrateControls.put(key, bitrateControl);
        row.addView(bitrateControl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView pending = controlText("", SESSION_META_TEXT_SP, STATS_LABEL_COLOR);
        pending.setPadding(0, dp(4), 0, 0);
        sessionPendingViews.put(key, pending);
        row.addView(pending);
        updateSessionPendingView(pending, value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private XrParameterGlyphView sessionSettingGlyph(
            SessionSettingsModel.Key key, SessionSettingsModel.Value value) {
        XrParameterGlyphView.Kind kind;
        switch (key) {
            case HDR:
                kind = XrParameterGlyphView.Kind.HDR_SUN;
                break;
            case VIDEO_RANGE:
                kind = XrParameterGlyphView.Kind.VIDEO_RANGE;
                break;
            case FRAME_PACING:
                kind = XrParameterGlyphView.Kind.FRAME_PACING;
                break;
            case AUDIO_LAYOUT:
                kind = XrParameterGlyphView.Kind.AUDIO_LAYOUT;
                break;
            case PLAY_AUDIO_ON_PC:
                kind = XrParameterGlyphView.Kind.PRODUCER;
                break;
            case CODEC:
            default:
                return null;
        }
        XrParameterGlyphView glyph = new XrParameterGlyphView(activity);
        updateSessionSettingGlyph(glyph, key, value);
        return glyph;
    }

    private void updateSessionSettingGlyph(XrParameterGlyphView glyph,
                                           SessionSettingsModel.Key key,
                                           SessionSettingsModel.Value value) {
        String stableValue = value.selectedChoiceId != null
                ? value.selectedChoiceId : value.pendingValue;
        XrParameterGlyphView.Kind kind;
        switch (key) {
            case HDR:
                kind = XrParameterGlyphView.Kind.HDR_SUN;
                break;
            case VIDEO_RANGE:
                kind = XrParameterGlyphView.Kind.VIDEO_RANGE;
                break;
            case FRAME_PACING:
                kind = XrParameterGlyphView.Kind.FRAME_PACING;
                break;
            case AUDIO_LAYOUT:
                kind = XrParameterGlyphView.Kind.AUDIO_LAYOUT;
                break;
            case PLAY_AUDIO_ON_PC:
                kind = XrParameterGlyphView.Kind.PRODUCER;
                // Host-audio true means the PC is also a producer; false keeps audio in-headset.
                stableValue = "true".equals(value.selectedChoiceId) ? "pc" : "headset";
                break;
            default:
                return;
        }
        glyph.setParameter(kind, stableValue);
    }

    private XrParameterGlyphView parameterGlyph(XrParameterGlyphView.Kind kind,
                                                 String stableValue) {
        XrParameterGlyphView glyph = new XrParameterGlyphView(activity);
        glyph.setParameter(kind, stableValue);
        return glyph;
    }

    private LinearLayout.LayoutParams glyphLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.rightMargin = dp(10);
        return params;
    }

    private XrChoiceGroup buildChoiceGroup(List<SessionSettingsModel.Choice> choices,
                                           String selectedChoiceId,
                                           CharSequence fallbackLabel,
                                           XrChoiceGroup.OnChoiceSelectedListener listener) {
        XrChoiceGroup group = new XrChoiceGroup(activity);
        configureChoiceGroup(group, choices, selectedChoiceId, fallbackLabel, listener);
        return group;
    }

    private void configureChoiceGroup(XrChoiceGroup group,
                                      List<SessionSettingsModel.Choice> choices,
                                      String selectedChoiceId,
                                      CharSequence fallbackLabel,
                                      XrChoiceGroup.OnChoiceSelectedListener listener) {
        CharSequence[] labels = new CharSequence[choices.size()];
        CharSequence[] ids = new CharSequence[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            SessionSettingsModel.Choice choice = choices.get(i);
            labels[i] = choice.label;
            ids[i] = choice.id;
        }
        String effectiveSelection = selectedChoiceId;
        CharSequence customEntry = null;
        if (choices.isEmpty()) {
            effectiveSelection = "__current__";
            customEntry = fallbackLabel;
        }
        group.setChoices(labels, ids, effectiveSelection, customEntry, listener);
    }

    private void clearSessionSettingsReferences() {
        sessionChoiceGroups.clear();
        sessionSourceViews.clear();
        sessionPendingViews.clear();
        sessionGlyphViews.clear();
        sessionBitrateControls.clear();
        sessionDefaultsButton = null;
        sessionApplyButton = null;
    }

    private void updateSessionSettingsView() {
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            if (key.isModeStreamQuality() && key != SessionSettingsModel.Key.BITRATE) {
                continue;
            }
            SessionSettingsModel.Value value = sessionSettingsModel.get(key);
            if (value == null) {
                continue;
            }
            if (key == SessionSettingsModel.Key.BITRATE) {
                XrBitrateControl bitrateControl = sessionBitrateControls.get(key);
                if (bitrateControl != null) {
                    bitrateControl.setChoices(choicesOrCurrent(value, value.selectedChoiceId),
                            value.selectedChoiceId, value.pendingValue, choiceId ->
                                    controlActionListener.onSharedSettingSelected(
                                            key, choiceId, sessionSettingsModel));
                    bitrateControl.setEnabled(sessionControlsEnabled);
                }
            }
            else {
                XrChoiceGroup group = sessionChoiceGroups.get(key);
                if (group == null) {
                    continue;
                }
                if (!group.setSelectedValue(value.selectedChoiceId)) {
                    configureChoiceGroup(group, value.choices, value.selectedChoiceId,
                            value.pendingValue, choiceId ->
                                    controlActionListener.onSharedSettingSelected(
                                            key, choiceId, sessionSettingsModel));
                }
                group.setEnabled(sessionControlsEnabled);
            }
            TextView sourceView = sessionSourceViews.get(key);
            if (sourceView != null) {
                sourceView.setText(value.source == SessionSettingsModel.Source.GLOBAL
                        ? activity.getString(R.string.xr_setting_source_global)
                        : activity.getString(R.string.xr_setting_source_session));
            }
            updateSessionPendingView(sessionPendingViews.get(key), value);
            XrParameterGlyphView glyph = sessionGlyphViews.get(key);
            if (glyph != null) {
                updateSessionSettingGlyph(glyph, key, value);
            }
        }
        if (sessionDefaultsButton != null) {
            sessionDefaultsButton.setEnabled(sessionControlsEnabled);
        }
        updateSessionApplyButton();
    }

    private void updateSessionPendingView(TextView pending,
                                          SessionSettingsModel.Value value) {
        if (pending == null) {
            return;
        }
        pending.setVisibility(value.hasPendingChange() ? View.VISIBLE : View.GONE);
        if (value.hasPendingChange()) {
            pending.setText(activity.getString(
                    R.string.xr_setting_pending_active, value.appliedValue));
        }
    }

    private void updateSessionApplyButton() {
        if (sessionApplyButton == null) {
            return;
        }
        sessionApplyButton.setText(reconnectPending
                ? activity.getString(R.string.xr_session_apply_reconnect)
                : activity.getString(R.string.xr_session_no_reconnect_changes));
        sessionApplyButton.setEnabled(sessionControlsEnabled && reconnectPending);
    }

    private LinearLayout panelColumn() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PANEL_BACKGROUND_COLOR);
        int padding = dp(22);
        root.setPadding(padding, padding, padding, padding);
        return root;
    }

    private LinearLayout labeledValue(String label, String value, int valueColor) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = controlText(label, 21f, STATS_LABEL_COLOR);
        TextView valueView = controlText(value, 26f, valueColor);
        column.addView(labelView);
        column.addView(valueView);
        return column;
    }

    private TextView controlText(CharSequence text, float sp, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private Button compactButton(CharSequence text) {
        Button button = new Button(activity);
        styleControlButton(button);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f);
        button.setAllCaps(false);
        button.setMinHeight(dp(72));
        button.setFocusable(true);
        return button;
    }

    private void styleControlButton(Button button) {
        button.setBackgroundResource(R.drawable.xr_home_action_background);
        button.setBackgroundTintList(null);
        button.setTextColor(Color.WHITE);
        button.setFocusable(true);
    }

    private String modeLabel(PresenterMode mode) {
        switch (mode) {
            case HOST_SBS_RAW:
                return activity.getString(R.string.xr_bar_host_sbs_raw);
            case HOST_SBS_AI:
                return activity.getString(R.string.xr_bar_host_sbs_ai);
            case CLIENT_SBS_AI:
                return activity.getString(R.string.xr_bar_client_sbs_ai);
            default:
                return activity.getString(R.string.xr_bar_normal);
        }
    }

    private String sessionSettingLabel(SessionSettingsModel.Key key) {
        switch (key) {
            case RESOLUTION:
                return activity.getString(R.string.title_resolution_list);
            case FRAME_RATE:
                return activity.getString(R.string.title_fps_list);
            case BITRATE:
                return activity.getString(R.string.title_seekbar_bitrate);
            case HDR:
                return activity.getString(R.string.title_enable_hdr);
            case VIDEO_RANGE:
                return activity.getString(R.string.title_full_range);
            case CODEC:
                return activity.getString(R.string.title_video_format);
            case FRAME_PACING:
                return activity.getString(R.string.title_frame_pacing);
            case AUDIO_LAYOUT:
                return activity.getString(R.string.title_audio_config_list);
            case PLAY_AUDIO_ON_PC:
                return activity.getString(R.string.title_checkbox_host_audio);
            default:
                return key.name();
        }
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
        if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.MODE_OPTIONS
                && PresenterMode.HOST_SBS_AI.name().equals(controlUiState.getModeOptionsId())) {
            renderModeOptions();
        }
        updateGlancePanel();
        revealDockTemporarily();
    }

    public boolean isStatsVisible() {
        return statsVisible;
    }

    /** Toggle the performance-stats panel; also flips the pref so the decoder emits perf text. */
    public void toggleStats() {
        controlUiState.toggleStats();
        applyControlUiState(true, "stats toggle");
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
                            + " | stream codec=%s decoder=%s dedicated_ll=%s"
                            + " ll_requested=%s submit=%s pacing=%s sequence=%.1f received=%.1f"
                            + " output=%.1f release=%.1f presented=%.1f"
                            + " decode_ms=%.2f/%.2f queue_ms_avg_p95_max=%.2f/%.2f/%.2f"
                            + " queue_depth_max=%d"
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
                    stream != null ? stream.getCodecDescription() : "n/a",
                    stream != null ? stream.getDecoderName() : "n/a",
                    stream != null && stream.isDedicatedLowLatencyDecoder(),
                    stream != null && stream.isDecoderLowLatencyRequested(),
                    stream != null && stream.isDirectDecoderSubmission()
                            ? "direct" : "buffered",
                    stream != null ? stream.getOutputPacingDescription() : "n/a",
                    stream != null ? stream.getStreamSequenceFps() : 0.0f,
                    stream != null ? stream.getReceivedFps() : 0.0f,
                    stream != null ? stream.getDecoderOutputFps() : 0.0f,
                    stream != null ? stream.getDecoderReleaseFps() : 0.0f,
                    stream != null ? stream.getDecoderPresentedFps() : Float.NaN,
                    stream != null ? stream.getDecodeAverageMs() : 0.0f,
                    stream != null ? stream.getDecodeMaxMs() : 0.0f,
                    stream != null ? stream.getDecoderQueueAverageMs() : Float.NaN,
                    stream != null ? stream.getDecoderQueueP95Ms() : Float.NaN,
                    stream != null ? stream.getDecoderQueueMaxMs() : Float.NaN,
                    stream != null ? stream.getDecoderQueueMaxDepth() : 0,
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
                    "DecoderPerf %.2fs | mode=%s codec=%s decoder=%s"
                            + " dedicated_ll=%s ll_requested=%s submit=%s pacing=%s"
                            + " | fps sequence=%.1f received=%.1f output=%.1f"
                            + " release=%.1f presented=%.1f | decode_ms=%.2f/%.2f"
                            + " queue_ms_avg_p95_max=%.2f/%.2f/%.2f queue_depth_max=%d",
                    stream.getElapsedMs() / 1000.0f,
                    presenterModeName(currentPresenterMode),
                    stream.getCodecDescription(),
                    stream.getDecoderName(),
                    stream.isDedicatedLowLatencyDecoder(),
                    stream.isDecoderLowLatencyRequested(),
                    stream.isDirectDecoderSubmission() ? "direct" : "buffered",
                    stream.getOutputPacingDescription(),
                    stream.getStreamSequenceFps(),
                    stream.getReceivedFps(),
                    stream.getDecoderOutputFps(),
                    stream.getDecoderReleaseFps(),
                    stream.getDecoderPresentedFps(),
                    stream.getDecodeAverageMs(),
                    stream.getDecodeMaxMs(),
                    stream.getDecoderQueueAverageMs(),
                    stream.getDecoderQueueP95Ms(),
                    stream.getDecoderQueueMaxMs(),
                    stream.getDecoderQueueMaxDepth()));
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
                    String.format(Locale.US, "%dx%d | %s | %s range",
                            stream.getSourceWidth(), stream.getSourceHeight(),
                            hdrActive ? "HDR" : "SDR", stream.getVideoRange()),
                    hdrActive ? STATS_ON_COLOR : STATS_VALUE_COLOR);
            addStatsRow("Codec", stream.getCodecDescription(), STATS_VALUE_COLOR);
            addStatsRow("Decoder component", stream.getDecoderName(), STATS_VALUE_COLOR);
            addStatsRow("Decoder latency",
                    formatDecoderLatencyMode(stream.isDedicatedLowLatencyDecoder(),
                            stream.isDecoderLowLatencyRequested()),
                    stream.isDedicatedLowLatencyDecoder()
                                    || stream.isDecoderLowLatencyRequested()
                            ? STATS_ON_COLOR : STATS_VALUE_COLOR);
            addStatsRow("Output pacing", stream.getOutputPacingDescription(),
                    STATS_VALUE_COLOR);
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
        addStatsRow("Device GPU total / clock", gpuBusy + " | " + gpuClock,
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
                addStatsRow("Depth inference call avg / max",
                        String.format(Locale.US, "%.2f / %.2f ms | OpenCL + sync",
                                clientSbs.averageNativeLiteRtRunWallMs,
                                clientSbs.maxNativeLiteRtRunWallMs),
                        STATS_VALUE_COLOR);
                addStatsRow("Depth age avg / max",
                        String.format(Locale.US, "%.2f / %.2f ms",
                                clientSbs.averageDepthResultAgeMs,
                                clientSbs.maxDepthResultAgeMs),
                        STATS_VALUE_COLOR);

                if (clientSbs.gpuTimersAvailable) {
                    addStatsRow("Model input GL GPU",
                            formatGpuStage(clientSbs.averageGpuModelInputMs,
                                    clientSbs.gpuModelInputSamples,
                                    "resize + pack + color cut"),
                            gpuStageColor(clientSbs.gpuModelInputSamples));
                    addStatsRow("Matched color GL GPU",
                            formatGpuStage(clientSbs.averageGpuMatchedColorMs,
                                    clientSbs.gpuMatchedColorSamples, "full-size capture"),
                            gpuStageColor(clientSbs.gpuMatchedColorSamples));
                    addStatsRow("Depth/profile GL GPU",
                            formatGpuStage(clientSbs.averageGpuDepthProfileMs,
                                    clientSbs.gpuDepthProfileSamples, "normalize + profile"),
                            gpuStageColor(clientSbs.gpuDepthProfileSamples));
                    addStatsRow("Stereo render GL GPU",
                            formatGpuStage(clientSbs.averageGpuSbsComposeMs,
                                    clientSbs.gpuSbsComposeSamples,
                                    "prefilter + warp + draw"),
                            gpuStageColor(clientSbs.gpuSbsComposeSamples));
                    addStatsRow("GPU timing note", "Stages can overlap; do not add as busy %",
                            STATS_UNAVAILABLE_COLOR);
                } else {
                    addStatsRow("Client GL GPU stages", "Timer queries unavailable",
                            STATS_UNAVAILABLE_COLOR);
                }
                addStatsRow("XR composition", "SceneCore does not expose compositor timing",
                        STATS_UNAVAILABLE_COLOR);

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

    static String formatDecoderLatencyMode(boolean dedicatedComponent,
                                           boolean lowLatencyRequested) {
        if (dedicatedComponent && lowLatencyRequested) {
            return "Dedicated low-latency component | LL options requested";
        }
        if (dedicatedComponent) {
            return "Dedicated low-latency component | no LL option reported";
        }
        if (lowLatencyRequested) {
            return "Regular component | LL options requested";
        }
        return "Regular component | no LL options requested";
    }

    static String formatGpuStage(float averageMs, long samples, String detail) {
        if (samples <= 0L || !Float.isFinite(averageMs)) {
            return "Waiting for completed timer sample";
        }
        return String.format(Locale.US, "%.2f ms | %s", averageMs, detail);
    }

    private int gpuStageColor(long samples) {
        return samples > 0L ? STATS_VALUE_COLOR : STATS_UNAVAILABLE_COLOR;
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
            heading.setTextColor(TILE_ACTIVE_BORDER_COLOR);
            heading.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    (STATS_TEXT_SP + 1f) * STATS_CONTENT_SCALE);
            heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
            heading.setPadding(0, statsDp(10), 0, statsDp(4));
            TableRow.LayoutParams params = new TableRow.LayoutParams();
            params.span = 2;
            heading.setLayoutParams(params);
            row.addView(heading);
            return row;
        }

        TextView label = new TextView(activity);
        label.setTextColor(STATS_LABEL_COLOR);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP * STATS_CONTENT_SCALE);
        label.setLineSpacing(0f, 1.08f);
        label.setPadding(0, statsDp(3), statsDp(18), statsDp(3));

        TextView value = new TextView(activity);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATS_TEXT_SP * STATS_CONTENT_SCALE);
        value.setLineSpacing(0f, 1.08f);
        value.setPadding(0, statsDp(3), 0, statsDp(3));

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
        if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.MODE_OPTIONS) {
            renderModeOptions();
        }
        updateGlancePanel();
        revealDockTemporarily();
    }

    static final class ModeOptionsPanelPlacement {
        final float centerY;
        final float centerZ;
        final float pitchDegrees;

        ModeOptionsPanelPlacement(float centerY, float centerZ, float pitchDegrees) {
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.pitchDegrees = pitchDegrees;
        }
    }

    /**
     * Aim only the contextual subpanel toward the viewer. Its top edge remains anchored beneath
     * the level button row, so opening it cannot move or rotate the primary controls.
     */
    static ModeOptionsPanelPlacement calculateModeOptionsPanelPlacement(
            float controlRowCenterY, float controlRowHeight, float optionsRowHeight,
            float rowGap, float anchorZ, float viewerY, float viewerZ) {
        float safeViewerY = Float.isFinite(viewerY) ? viewerY : 0.0f;
        float safeViewerZ = Float.isFinite(viewerZ) && viewerZ > anchorZ + 0.1f
                ? viewerZ : 2.0f;
        float topAnchorY = controlRowCenterY
                - Math.max(0.0f, controlRowHeight) / 2.0f - Math.max(0.0f, rowGap);
        float unrotatedCenterY = topAnchorY - Math.max(0.0f, optionsRowHeight) / 2.0f;
        float verticalDistance = Math.max(0.0f, safeViewerY - unrotatedCenterY);
        float forwardDistance = Math.max(0.1f, safeViewerZ - anchorZ);
        float requestedTilt = (float) Math.toDegrees(Math.atan2(
                verticalDistance, forwardDistance));
        float pitchDegrees = -Math.max(MODE_OPTIONS_MIN_TILT_DEGREES,
                Math.min(MODE_OPTIONS_MAX_TILT_DEGREES, requestedTilt));

        // Anchor the subpanel's local top-center after rotation. Negative X pitch points its normal
        // upward toward the viewer and brings its lower edge slightly closer to the face.
        float offsetY = Math.max(0.0f, optionsRowHeight) / 2.0f;
        double pitchRadians = Math.toRadians(pitchDegrees);
        float rotatedOffsetY = offsetY * (float) Math.cos(pitchRadians);
        float rotatedOffsetZ = offsetY * (float) Math.sin(pitchRadians);
        return new ModeOptionsPanelPlacement(topAnchorY - rotatedOffsetY,
                anchorZ - rotatedOffsetZ, pitchDegrees);
    }

    static float calculateModeOptionsHeightMeters(float currentHeightMeters,
                                                 int currentHeightPixels,
                                                 int contentHeightPixels,
                                                 float minHeightMeters,
                                                 float maxHeightMeters) {
        float safeCurrentHeightMeters = Float.isFinite(currentHeightMeters)
                ? Math.max(0.0f, currentHeightMeters) : MODE_OPTIONS_MIN_HEIGHT_METERS;
        int safeCurrentHeightPx = Math.max(1, currentHeightPixels);
        int safeContentHeightPx = Math.max(0, contentHeightPixels);
        float minHeight = Math.max(0.0f, minHeightMeters);
        float maxHeight = Math.max(minHeight, maxHeightMeters);
        if (safeContentHeightPx <= 0) {
            return minHeight;
        }
        float targetHeight = safeCurrentHeightMeters * (float) safeContentHeightPx
                / (float) safeCurrentHeightPx;
        if (!Float.isFinite(targetHeight)) {
            return minHeight;
        }
        return Math.max(minHeight, Math.min(maxHeight, targetHeight));
    }

    private void scheduleModeOptionsPanelFit() {
        if (modeOptionsPanel == null || modeOptionsPanel.isDisposed()
                || modeOptionsHost == null || modeOptionsContentRoot == null) {
            return;
        }
        if (modeOptionsFitScheduled) {
            return;
        }
        modeOptionsFitScheduled = true;
        modeOptionsStatusHandler.removeCallbacks(modeOptionsFitRunnable);
        modeOptionsStatusHandler.post(modeOptionsFitRunnable);
    }

    private void fitModeOptionsPanelToContent() {
        if (modeOptionsPanel == null || modeOptionsPanel.isDisposed()
                || modeOptionsHost == null || modeOptionsContentRoot == null) {
            return;
        }
        int hostWidth = modeOptionsHost.getWidth();
        int hostHeight = modeOptionsHost.getHeight();
        if (hostWidth <= 0 || hostHeight <= 0) {
            scheduleModeOptionsPanelFit();
            return;
        }
        modeOptionsContentRoot.measure(
                View.MeasureSpec.makeMeasureSpec(hostWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentHeightPx = modeOptionsContentRoot.getMeasuredHeight() + dp(4);
        float targetHeightMeters = calculateModeOptionsHeightMeters(
                modeOptionsHeightMeters, hostHeight, contentHeightPx,
                MODE_OPTIONS_MIN_HEIGHT_METERS, MODE_OPTIONS_MAX_HEIGHT_METERS);
        if (Math.abs(targetHeightMeters - modeOptionsHeightMeters) < 0.001f) {
            return;
        }
        XrControlPanelLayout layout = controlBarLayout(panelHeightMeters);
        modeOptionsPanel.setSize(new FloatSize2d(layout.widthMeters, targetHeightMeters));
        modeOptionsHeightMeters = targetHeightMeters;
        modeOptionsPanel.setPose(modeOptionsPose(panelHeightMeters));
        modeOptionsHost.requestLayout();
    }

    /** Local pose of the unchanged, level mode-button panel. */
    private Pose barPose(float videoHeightMeters) {
        XrControlPanelLayout layout = controlBarLayout(videoHeightMeters);
        XrControlPanelLayout compactLayout = XrControlPanelLayout.calculate(
                controlBarTileUnits(false), 1, BAR_HEIGHT_METERS, BAR_DIVIDER_METERS,
                videoHeightMeters, BAR_GAP_METERS);
        float centerX = controlBarCenterX(secondaryActionsExpanded,
                compactLayout.widthMeters, layout.widthMeters);
        return new Pose(new Vector3(centerX, layout.primaryRowCenterY, BAR_Z_METERS),
                Quaternion.Identity);
    }

    /** Local pose of the independently pitched contextual subpanel. */
    private Pose modeOptionsPose(float videoHeightMeters) {
        XrControlPanelLayout layout = controlBarLayout(videoHeightMeters);
        Vector3 viewer = statsViewerPositionLocal();
        ModeOptionsPanelPlacement placement = calculateModeOptionsPanelPlacement(
                layout.primaryRowCenterY, BAR_HEIGHT_METERS, modeOptionsHeightMeters,
                MODE_OPTIONS_GAP_METERS, BAR_Z_METERS, viewer.getY(), viewer.getZ());
        Quaternion rotation = Quaternion.fromAxisAngle(
                new Vector3(1.0f, 0.0f, 0.0f), placement.pitchDegrees);
        return new Pose(new Vector3(0.0f, placement.centerY, placement.centerZ), rotation);
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

    /**
     * Pure left-side mirror of {@link #calculateStatsPanelPlacement}. The panel's inner (local +X)
     * edge remains anchored outside the video and positive Y yaw wraps its outer edge inward.
     */
    static StatsPanelPlacement calculateLeftPanelPlacement(float videoWidthMeters,
                                                            float panelWidthMeters,
                                                            float gapMeters,
                                                            float viewerX,
                                                            float viewerZ) {
        StatsPanelPlacement right = calculateStatsPanelPlacement(
                videoWidthMeters, panelWidthMeters, gapMeters, -viewerX, viewerZ);
        return new StatsPanelPlacement(-right.innerEdgeX, right.innerEdgeZ,
                -right.centerX, right.centerY, right.centerZ, -right.yawDegrees);
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

    /** Session Settings sits to the video's left and tilts inward toward the viewer. */
    private Pose sessionSettingsPose(float videoHeightMeters) {
        Vector3 viewer = statsViewerPositionLocal();
        StatsPanelPlacement placement = calculateLeftPanelPlacement(
                videoHeightMeters * aspectFor(currentPresenterMode),
                AUXILIARY_WIDTH_METERS, STATS_GAP_METERS, viewer.getX(), viewer.getZ());
        Quaternion rotation = Quaternion.fromAxisAngle(
                new Vector3(0.0f, 1.0f, 0.0f), placement.yawDegrees);
        return new Pose(new Vector3(placement.centerX, placement.centerY, placement.centerZ),
                rotation);
    }

    private void repositionStatsPanel() {
        if (statsVisible && statsPanel != null && !statsPanel.isDisposed()) {
            statsPanel.setPose(statsPose(panelHeightMeters));
        }
        XrControlUiState.Surface visible = controlUiState.getVisibleSurface();
        if (auxiliaryPanel != null && !auxiliaryPanel.isDisposed()) {
            if (visible == XrControlUiState.Surface.SESSION_SETTINGS) {
                auxiliaryPanel.setPose(sessionSettingsPose(panelHeightMeters));
            }
        }
    }

    private XrControlPanelLayout controlBarLayout(float videoHeightMeters) {
        return XrControlPanelLayout.calculate(
                controlBarTileUnits(secondaryActionsExpanded), 1,
                BAR_HEIGHT_METERS, BAR_DIVIDER_METERS, videoHeightMeters, BAR_GAP_METERS);
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
        updateGlancePanel();
        revealDockTemporarily();

        // The initial Host/Raw mode is now proven to match a decoded frame. Mark it as the most
        // successful presentation. Client SBS still needs its guarded GL surface handoff.
        if (deferredPresenterMode == PresenterMode.NORMAL) {
            persistPresentationState();
            controlActionListener.onPresentationModeCommitted(currentPresenterMode);
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
        if (modeOptionsPanel != null && !modeOptionsPanel.isDisposed()) {
            modeOptionsPanel.setPose(modeOptionsPose(videoHeightMeters));
        }
        repositionStatsPanel();
        if (depthStatusPanel != null) {
            depthStatusPanel.setPose(depthStatusPose());
        }
        if (transientMessagePanel != null) {
            transientMessagePanel.setPose(depthStatusPose());
        }
        if (glancePanel != null && !glancePanel.isDisposed()) {
            glancePanel.setPose(glancePose(videoHeightMeters));
        }
        updateGlancePanel();
    }

    /**
     * Apply a presentation chosen from the bar. Sets the compositor eye split, drives the host SBS
     * pipeline on/off in Host SBS AI, re-pins the surface to the target
     * frame size, and reshapes the quad to the mode's aspect. The quad's height is preserved; only
     * the width changes (when the aspect changes), so the screen keeps its vertical size.
     */
    private void selectMode(BarItem item) {
        if (!streamPresentationReady || item.selectsMode == null || surfaceEntity == null
                || surfaceEntity.isDisposed()
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
        updateGlancePanel();
        revealDockTemporarily();
        PresenterMode previousMode = currentPresenterMode;
        PresenterMode nextMode = item.selectsMode;
        boolean wasClientSbs = (previousMode == PresenterMode.CLIENT_SBS_AI);
        boolean isClientSbs = (nextMode == PresenterMode.CLIENT_SBS_AI);

        com.limelight.Game game = activity instanceof com.limelight.Game
                ? (com.limelight.Game) activity : null;
        boolean decoderTransitionRequired = requiresDecoderTransition(previousMode, nextMode);
        if (decoderTransitionRequired) {
            int transitionGeneration = game != null
                    ? game.beginDecoderPresentationModeTransition() : 0;
            if (!decoderTransitionGenerations.beginMode(transitionGeneration)) {
                lastModeSwitchMs = 0;
                modeSwitchInProgress = false;
                updateGlancePanel();
                revealDockTemporarily();
                reportModeSwitchFailure("decoder could not prepare for the transition");
                return;
            }
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
            updateGlancePanel();
            revealDockTemporarily();
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

        boolean surfaceUsable = surfaceEntity != null && !surfaceEntity.isDisposed();
        if (!surfaceSwitchSucceeded || !surfaceUsable) {
            modeSwitchInProgress = false;
            decoderTransitionGenerations.clearMode();
            updateGlancePanel();
            revealDockTemporarily();
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
            if (surfaceUsable) {
                surfaceEntity.setAlpha(1.0f);
            }
            if (surfaceUsable && activity instanceof com.limelight.Game) {
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
            controlActionListener.onPresentationModeCommitted(currentPresenterMode);
            updateGlancePanel();
            revealDockTemporarily();
        }
    }

    /** Decoder callback: the fresh transition IDR is now being released to the target Surface. */
    public void onDecoderPresentationModeTransitionOpened(int transitionGeneration) {
        PresenterMode pendingMode = pendingDecoderTransitionMode;
        if (pendingMode != null) {
            if (!decoderTransitionGenerations.dispatchModeIfCurrent(
                    transitionGeneration, () -> finishPendingModeTransition(pendingMode))) {
                LimeLog.warning("XR: ignoring superseded decoder completion generation "
                        + transitionGeneration + " for mode " + pendingMode);
            }
            return;
        }

        if (clientSbsHdrTransitionInProgress) {
            if (!decoderTransitionGenerations.dispatchHdrIfCurrent(
                    transitionGeneration, this::openClientSbsHdrTransition)) {
                LimeLog.warning("XR: ignoring superseded Client SBS HDR completion generation "
                        + transitionGeneration);
            }
            return;
        }

        // A failed host control send can still complete the decoder flush on the unchanged
        // Surface. Consume only the matching transaction and do not let it affect a later switch.
        if (decoderTransitionGenerations.dispatchModeIfCurrent(
                transitionGeneration, decoderTransitionGenerations::clearMode)) {
            LimeLog.info("XR: completed decoder recovery for an aborted mode switch");
        } else {
            LimeLog.warning("XR: ignoring stale decoder completion generation "
                    + transitionGeneration);
        }
    }

    private void finishPendingModeTransition(PresenterMode pendingMode) {
        if (surfaceEntity == null || surfaceEntity.isDisposed()
                || currentPresenterMode != pendingMode) {
            LimeLog.warning("XR: ignoring stale decoder transition completion for " + pendingMode);
            return;
        }
        pendingDecoderTransitionMode = null;
        decoderTransitionGenerations.clearMode();
        surfaceEntity.setAlpha(1.0f);
        modeSwitchInProgress = false;
        persistPresentationState();
        controlActionListener.onPresentationModeCommitted(currentPresenterMode);
        updateGlancePanel();
        revealDockTemporarily();
        LimeLog.info("XR: fresh-IDR output completed mode " + pendingMode);
    }

    private void openClientSbsHdrTransition() {
        com.limelight.Game game = activity instanceof com.limelight.Game
                ? (com.limelight.Game) activity : null;
        StreamContainer streamContainer = game != null
                ? game.getStreamContainer() : null;
        if (streamContainer == null) {
            finishClientSbsHdrTransition(false);
            return;
        }

        // The quad has remained hidden since the transition began. Install the target SceneCore
        // interpretation before GL submits its first new-transfer buffer; setHdrInput() stays
        // pinned until the renderer's generation commit below.
        applyContentColorMetadata();
        streamContainer.completeClientSbsHdrTransition(this::finishClientSbsHdrTransition);
        LimeLog.info("XR: fresh-IDR output reached Client SBS HDR boundary; "
                + "awaiting first new-format EGL swap");
    }

    /** Decoder callback: preserve the last successful saved mode while the stream terminates. */
    public boolean onDecoderPresentationModeTransitionTimedOut(int transitionGeneration) {
        boolean current = decoderTransitionGenerations.dispatchAnyIfCurrent(
                transitionGeneration, () -> {
                    if (pendingDecoderTransitionMode != null) {
                        LimeLog.severe("XR: mode " + pendingDecoderTransitionMode
                                + " timed out before fresh-IDR output");
                    } else if (clientSbsHdrTransitionInProgress) {
                        LimeLog.severe("XR: Client SBS HDR transition timed out before "
                                + "fresh-IDR output");
                    } else {
                        LimeLog.severe("XR: decoder recovery for an aborted mode switch timed out");
                    }
                });
        if (!current) {
            LimeLog.warning("XR: ignoring stale decoder timeout generation "
                    + transitionGeneration);
        }
        // Keep both the pending mode and switch guard set while Game terminates the stream. This
        // prevents another tile tap and keeps onDestroy() from persisting the failed mode.
        return current;
    }

    private void finishClientSbsHdrTransition(boolean success) {
        if (!clientSbsHdrTransitionInProgress) {
            return;
        }
        decoderTransitionGenerations.clearHdr();
        if (!success || surfaceEntity == null || surfaceEntity.isDisposed()
                || currentPresenterMode != PresenterMode.CLIENT_SBS_AI) {
            LimeLog.severe("XR: Client SBS HDR transition failed before first output swap");
            if (activity instanceof com.limelight.Game) {
                ((com.limelight.Game) activity).handleDecoderSurfaceSwitchFailure();
            }
            return;
        }

        clientSbsHdrTransitionInProgress = false;
        modeSwitchInProgress = false;
        surfaceEntity.setAlpha(1.0f);
        updateGlancePanel();
        revealDockTemporarily();
        LimeLog.info("XR: first frame-boundary-safe Client SBS HDR output is visible");
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
     * Cinema preset helper that toggles between the large preset and the last transform/height.
     * The enlarged preset keeps the panel at the tuned cinematic distance and orientation.
     */
    private void applyCinemaViewPreset() {
        if (surfaceEntity == null || surfaceEntity.isDisposed()) {
            return;
        }
        cinemaRestoreHeightMeters = panelHeightMeters;
        try {
            cinemaRestorePose = surfaceEntity.getPose(Space.REAL_WORLD);
        } catch (Throwable error) {
            LimeLog.warning("XR cinema view: current pose capture failed (" + error + ")");
            cinemaRestorePose = null;
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
                        new Pose(
                                new Vector3(0.0f, 0.0f, -CINEMA_PRESET_DISTANCE_METERS),
                                Quaternion.Identity));
                surfaceEntity.setPose(inFront, Space.REAL_WORLD);
                placed = inFront;
            }
        } catch (Throwable t) {
            LimeLog.warning("XR cinema view: current head pose unavailable (" + t + ")");
        }
        if (placed == null) {
            surfaceEntity.setPose(new Pose(
                    new Vector3(0.0f, 0.0f, -CINEMA_PRESET_DISTANCE_METERS),
                    Quaternion.Identity));
        }
        repositionControlBar(height);
        viewStateStore.saveHeight(panelHeightMeters);
        cinemaViewExpanded = true;
    }

    private void restoreCinemaView() {
        if (surfaceEntity == null || surfaceEntity.isDisposed()) {
            return;
        }
        surfaceEntity.setScale(1.0f);
        float restoredHeight = cinemaRestoreHeightMeters > 0.0f
                ? cinemaRestoreHeightMeters
                : DEFAULT_PANEL_HEIGHT_METERS;
        float aspect = aspectFor(currentPresenterMode);
        panelHeightMeters = restoredHeight;
        surfaceEntity.setShape(new SurfaceEntity.Shape.Quad(new FloatSize2d(
                panelHeightMeters * aspect, panelHeightMeters)));
        applyResizeBounds(aspect);
        if (cinemaRestorePose != null) {
            surfaceEntity.setPose(cinemaRestorePose, Space.REAL_WORLD);
        } else {
            surfaceEntity.setPose(new Pose(
                    new Vector3(0.0f, 0.0f, -CINEMA_PRESET_DISTANCE_METERS),
                    Quaternion.Identity), Space.REAL_WORLD);
        }
        repositionControlBar(panelHeightMeters);
        viewStateStore.saveHeight(panelHeightMeters);
        cinemaRestorePose = null;
        cinemaRestoreHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;
        cinemaViewExpanded = false;
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
        // The Game callback persists the mode through an app/session-guarded editor. This store
        // owns only per-PC geometry, so a finishing Game cannot overwrite a replacement app's
        // presentation mode.
        viewStateStore.saveHeight(panelHeightMeters);
    }

    /**
     * Snapshots the live rendered geometry before Apply tears down SceneCore. The replacement
     * Activity consumes this transient Intent state, preserving both physical size and apparent
     * size from the screen's real-world distance.
     */
    public void captureReconnectViewState(Intent reconnectIntent) {
        float shapeHeight = panelHeightMeters;
        float realWorldScaleY = 1.0f;
        Pose realWorldPose = null;
        if (surfaceEntity != null && !surfaceEntity.isDisposed()) {
            try {
                SurfaceEntity.Shape shape = surfaceEntity.getShape();
                if (shape instanceof SurfaceEntity.Shape.Quad) {
                    shapeHeight = ((SurfaceEntity.Shape.Quad) shape)
                            .getExtents().getHeight();
                } else {
                    shapeHeight = surfaceEntity.getDimensions().getHeight();
                }
                realWorldScaleY = surfaceEntity.getNonUniformScale(Space.REAL_WORLD).getY();
            } catch (Throwable error) {
                LimeLog.warning("XR: reconnect size snapshot failed: " + error);
            }
            try {
                realWorldPose = surfaceEntity.getPose(Space.REAL_WORLD);
            } catch (Throwable error) {
                LimeLog.warning("XR: reconnect pose snapshot failed: " + error);
            }
        }

        panelHeightMeters = XrReconnectViewState.effectiveHeight(
                shapeHeight, realWorldScaleY, panelHeightMeters);
        viewStateStore.saveHeight(panelHeightMeters);
        new XrReconnectViewState(panelHeightMeters, realWorldPose)
                .writeTo(reconnectIntent);
        LimeLog.info("XR: captured reconnect view at height "
                + panelHeightMeters + " m"
                + (realWorldPose != null ? " with real-world pose" : ""));
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

    /** Quad aspect (width/height), including Raw Half's narrower encoded eye geometry. */
    private float aspectFor(PresenterMode mode) {
        return presentationAspect(mode, fullAspect, prefConfig.rawSbsPerEyeResolution);
    }

    static float presentationAspect(PresenterMode mode, float logicalAspect) {
        return presentationAspect(mode, logicalAspect,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL);
    }

    static float presentationAspect(
            PresenterMode mode, float logicalAspect,
            PreferenceConfiguration.RawSbsPerEyeResolution rawPerEyeResolution) {
        if (mode == PresenterMode.HOST_SBS_RAW
                && rawPerEyeResolution
                == PreferenceConfiguration.RawSbsPerEyeResolution.HALF) {
            return logicalAspect / 2.0f;
        }
        return logicalAspect;
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

    public boolean canBeginClientSbsHdrTransition() {
        return surfaceEntity != null && !surfaceEntity.isDisposed()
                && canSynchronizeClientSbsHdrTransition(
                currentPresenterMode,
                streamPresentationReady,
                modeSwitchInProgress,
                clientSbsHdrTransitionInProgress);
    }

    /**
     * Hide the retained old-transfer buffer and block Client-SBS capture before decoder recovery.
     * A repeated host flip supersedes the pending target while keeping the quad hidden.
     */
    public boolean beginClientSbsHdrTransition(boolean enabled, int decoderTransitionGeneration) {
        if (!canBeginClientSbsHdrTransition()
                || decoderTransitionGeneration <= 0
                || !(activity instanceof com.limelight.Game)) {
            return false;
        }
        StreamContainer streamContainer =
                ((com.limelight.Game) activity).getStreamContainer();
        if (streamContainer == null
                || !streamContainer.beginClientSbsHdrTransition(enabled)) {
            return false;
        }

        decoderTransitionGenerations.beginHdr(decoderTransitionGeneration);
        clientSbsHdrTransitionInProgress = true;
        modeSwitchInProgress = true;
        surfaceEntity.setAlpha(0.0f);
        updateGlancePanel();
        revealDockTemporarily();
        LimeLog.info("XR: hiding Client SBS output until the "
                + (enabled ? "HDR" : "SDR") + " frame boundary");
        return true;
    }

    /** Reapply color metadata immediately after a host SDR/HDR transition. Main thread only. */
    public void onHdrModeChanged() {
        if (clientSbsHdrTransitionInProgress) {
            return;
        }
        applyContentColorMetadata();
    }

    /** Reconcile SceneCore metadata after EGL context creation or replacement. Main thread only. */
    public void onClientSbsOutputCapabilityChanged() {
        applyContentColorMetadata();
        if (isClientOptionsOpen()) {
            renderModeOptions();
        }
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
        if (item.destructive) {
            root.setBackgroundResource(R.drawable.xr_home_destructive_action_background);
        }
        else {
            root.setBackground(controlSurfaceBackground(
                    TILE_IDLE_COLOR, TILE_IDLE_BORDER_COLOR, 1));
        }

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClickable(true);
        col.setFocusable(true);
        col.setFocusableInTouchMode(false);
        col.setContentDescription(item.label);
        int pad = dp(3);
        col.setPadding(pad, pad, pad, item.selectsMode != null ? dp(24) : pad);
        col.setOnClickListener(v -> {
            revealDockTemporarily();
            if (item.onTap != null) {
                item.onTap.run();
            }
        });
        attachDockActivityListeners(col);
        item.tapTarget = col;
        applySelectableForeground(col);
        addBarItemContent(col, item);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(col, contentParams);

        if (item.selectsMode != null) {
            XrModeChevronView chevron = new XrModeChevronView(activity);
            FrameLayout.LayoutParams chevronParams = new FrameLayout.LayoutParams(
                    dp(40), dp(18),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            chevronParams.bottomMargin = dp(5);
            root.addView(chevron, chevronParams);
            item.optionsIndicator = chevron;
        }

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
        int iconSize = dp(item.iconOnly ? 42 : 48);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        icon.setImageResource(item.iconRes);
        icon.setColorFilter(Color.WHITE);
        item.iconView = icon;

        col.addView(icon);
        if (item.iconOnly) {
            return;
        }

        TextView text = new TextView(activity);
        text.setText(item.label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        text.setLayoutParams(tp);
        col.addView(text);

    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private int statsDp(float v) {
        return dp(v * STATS_CONTENT_SCALE);
    }

    private android.graphics.drawable.GradientDrawable controlSurfaceBackground(
            int fillColor, int strokeColor, int strokeWidthDp) {
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(fillColor);
        background.setStroke(dp(strokeWidthDp), strokeColor);
        background.setCornerRadius(dp(14));
        return background;
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
        /** Physical width relative to a normal square dock tile. */
        final float widthUnits;
        /** Compact utility tiles retain an accessible label without drawing text in the dock. */
        final boolean iconOnly;
        /** Hidden until the compact expansion tile is activated. */
        boolean secondary;
        /** Uses the destructive semantic surface while retaining the ordinary dock interaction. */
        boolean destructive;
        Runnable onTap;
        View root;
        View tapTarget;
        ImageView iconView;
        XrModeChevronView optionsIndicator;

        BarItem(String label, int iconRes, PresenterMode selectsMode) {
            this(label, iconRes, selectsMode, 1.0f, false);
        }

        BarItem(String label, int iconRes, PresenterMode selectsMode,
                float widthUnits, boolean iconOnly) {
            this.label = label;
            this.iconRes = iconRes;
            this.selectsMode = selectsMode;
            this.widthUnits = widthUnits;
            this.iconOnly = iconOnly;
        }

        void setEnabled(boolean enabled) {
            if (root != null) {
                root.setAlpha(enabled ? 1.0f : 0.4f);
            }
            if (tapTarget != null) {
                tapTarget.setEnabled(enabled);
            }
        }

        void setOptionsOpen(boolean open) {
            if (optionsIndicator == null) {
                return;
            }
            optionsIndicator.setExpanded(open);
            optionsIndicator.setSelected(open);
            optionsIndicator.setActivated(open);
        }

        void setIconAndDescription(int iconResource, CharSequence description) {
            if (iconView != null) {
                iconView.setImageResource(iconResource);
                iconView.invalidate();
            }
            if (tapTarget != null) {
                tapTarget.setContentDescription(description);
            }
        }


        /** Active mode tile gets a bright accent fill + white border so the current mode is
         *  unmistakable at a glance; everything else stays a flat dark fill. */
        void setSelected(boolean selected) {
            if (root == null) {
                return;
            }
            if (selected) {
                root.setBackground(controlSurfaceBackground(
                        TILE_ACTIVE_COLOR, TILE_ACTIVE_BORDER_COLOR, 2));
            } else {
                root.setBackground(controlSurfaceBackground(
                        TILE_IDLE_COLOR, TILE_IDLE_BORDER_COLOR, 1));
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
        if (surfaceEntity == null || surfaceEntity.isDisposed()) {
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

    private int hostSbsVideoFormat = MoonBridge.VIDEO_FORMAT_H265;

    static boolean hostSbsFormatChangeRequiresResize(
            PresenterMode mode, int width, int height, int oldFormat, int newFormat) {
        if (mode != PresenterMode.HOST_SBS_AI || oldFormat == newFormat) {
            return false;
        }
        int[] oldDimensions = PreferenceConfiguration.hostSbsPackedDimensions(
                width, height, oldFormat);
        int[] newDimensions = PreferenceConfiguration.hostSbsPackedDimensions(
                width, height, newFormat);
        return oldDimensions[0] != newDimensions[0]
                || oldDimensions[1] != newDimensions[1];
    }

    /** Updates the host-SBS allocation from the codec actually selected by RTSP/MediaCodec. */
    public void setHostSbsVideoFormat(int videoFormat) {
        boolean resizeRequired = hostSbsFormatChangeRequiresResize(
                currentPresenterMode, prefConfig.width, prefConfig.height,
                hostSbsVideoFormat, videoFormat);
        hostSbsVideoFormat = videoFormat;
        if (resizeRequired) {
            setHostSurfaceSize(true);
        }
    }

    /** Packed Host SBS frame dimensions (2W' x H'). When the per-eye width is capped, the height is
     *  scaled by the same factor so the per-eye aspect is preserved. Even dimensions. */
    private int hostSbsPackedWidth() {
        return PreferenceConfiguration.hostSbsPackedDimensions(
                prefConfig.width, prefConfig.height, hostSbsVideoFormat)[0];
    }
    private int hostSbsPackedHeight() {
        return PreferenceConfiguration.hostSbsPackedDimensions(
                prefConfig.width, prefConfig.height, hostSbsVideoFormat)[1];
    }

    static int[] initialSurfacePixelDimensions(PresenterMode mode,
                                               int logicalWidth,
                                               int logicalHeight,
                                               int hostAiVideoFormat,
                                               PreferenceConfiguration.RawSbsPerEyeResolution
                                                       rawPerEyeResolution) {
        if (mode == PresenterMode.HOST_SBS_RAW) {
            return PreferenceConfiguration.rawSbsPackedDimensions(
                    logicalWidth, logicalHeight, rawPerEyeResolution);
        }
        if (isHostDepthPresenterMode(mode)) {
            return PreferenceConfiguration.hostSbsPackedDimensions(
                    logicalWidth, logicalHeight, hostAiVideoFormat);
        }
        return new int[] {logicalWidth, logicalHeight};
    }

    static int[] initialSurfacePixelDimensions(PresenterMode mode,
                                               int logicalWidth,
                                               int logicalHeight,
                                               int hostAiVideoFormat) {
        return initialSurfacePixelDimensions(mode, logicalWidth, logicalHeight,
                hostAiVideoFormat,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL);
    }

    /** Re-pin the XR surface for a host presentation: the packed SBS frame ({@code 2W' x H'}) when a
     *  host depth mode's SBS is active, or the plain 2D frame ({@code W x H}) for NORMAL. Re-fetches
     *  the surface in case the resize re-creates it. Main-thread only (SceneCore is Activity-bound). */
    public void setHostSurfaceSize(boolean sbs) {
        if (surfaceEntity == null || surfaceEntity.isDisposed()) {
            return;
        }
        int w;
        int h;
        if (sbs) {
            w = hostSbsPackedWidth();
            h = hostSbsPackedHeight();
        } else if (currentPresenterMode == PresenterMode.HOST_SBS_RAW) {
            int[] raw = PreferenceConfiguration.rawSbsPackedDimensions(
                    prefConfig.width, prefConfig.height,
                    prefConfig.rawSbsPerEyeResolution);
            w = raw[0];
            h = raw[1];
        } else {
            w = prefConfig.width;
            h = prefConfig.height;
        }
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(w, h));
        videoSurface = surfaceEntity.getSurface();
    }

    /**
     * Tear down the entity/session. Mirrors {@code Stereo3DRenderer.onSurfaceDestroyedAsync()} /
     * {@code StreamContainer.onDestroy()} ordering.
     */
    public void onDestroy() {
        modeOptionsStatusHandler.removeCallbacks(refreshClientOptionsStatus);
        dockVisibilityHandler.removeCallbacks(collapseDockRunnable);
        viewStateStore.saveHeight(panelHeightMeters);
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
        if (modeOptionsPanel != null) {
            if (!modeOptionsPanel.isDisposed()) {
                modeOptionsPanel.dispose();
            }
            modeOptionsPanel = null;
        }
        if (statsPanel != null) {
            if (!statsPanel.isDisposed()) {
                statsPanel.dispose();
            }
            statsPanel = null;
        }
        if (auxiliaryPanel != null) {
            if (!auxiliaryPanel.isDisposed()) {
                auxiliaryPanel.dispose();
            }
            auxiliaryPanel = null;
        }
        if (glancePanel != null) {
            if (!glancePanel.isDisposed()) {
                glancePanel.dispose();
            }
            glancePanel = null;
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
        glanceRoot = null;
        glanceIdentityView = null;
        glanceModeView = null;
        glanceStreamView = null;
        glanceStatusView = null;
        controlBarRow = null;
        dockRevealPill = null;
        dockHoverTarget = null;
        dockFocusTarget = null;
        videoSurface = null;
        statsTable = null;
        settingsItem = null;
        cinemaItem = null;
        statsItem = null;
        expansionItem = null;
        secondaryBarItems.clear();
        secondaryActionsExpanded = false;
        cinemaViewExpanded = false;
        cinemaRestorePose = null;
        cinemaRestoreHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;
        lastCinemaTileTapMs = 0L;
        modeOptionsHost = null;
        modeOptionsContentRoot = null;
        modeOptionsStatusHandler.removeCallbacks(modeOptionsFitRunnable);
        modeOptionsFitScheduled = false;
        auxiliaryContentHost = null;
        pendingDecoderTransitionMode = null;
        clientSbsHdrTransitionInProgress = false;
        modeSwitchInProgress = false;
        decoderTransitionGenerations.clear();
        barItems.clear();
        controlUiState.close();
        session = null;
    }
}
