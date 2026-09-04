package com.limelight.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
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

import com.limelight.BuildConfig;
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
import com.limelight.sbs.ClientSbsMetricHistory;
import com.limelight.sbs.HostSbsTelemetrySnapshot;
import com.limelight.sbs.HostSbsTelemetryTracker;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;
import com.limelight.ui.xrcontrols.XrBitrateControl;
import com.limelight.ui.xrcontrols.XrSparklineView;
import com.limelight.ui.xrcontrols.XrBitrateRecommendation;
import com.limelight.ui.xrcontrols.XrSegmentedLadder;
import com.limelight.ui.xrcontrols.XrControlPanelLayout;
import com.limelight.ui.xrcontrols.XrControlUiState;
import com.limelight.ui.xrcontrols.XrModeChevronView;
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
    // Keep Stats compact and place it beside the video. A single column is easier to scan in-headset
    // and cuts the Android panel raster from the former 9.1 MP two-column surface to 2.8 MP.
    private static final float STATS_WIDTH_METERS = 1.40f;
    private static final float STATS_HEIGHT_METERS = 1.05f;
    /** Never shrink below the authored size, so a sparse mode does not leave a sliver of a panel. */
    private static final float STATS_MIN_HEIGHT_METERS = STATS_HEIGHT_METERS;
    /** Beyond this the panel would run past comfortable gaze range; the ScrollView takes over. */
    private static final float STATS_MAX_HEIGHT_METERS = 1.85f;
    private static final int STATS_RASTER_WIDTH = 1920;
    private static final int STATS_RASTER_HEIGHT = 1440;
    /**
     * Pixel cap paired with {@link #STATS_MAX_HEIGHT_METERS}. Ceil keeps the physical cap from
     * losing a final fractional pixel; the ScrollView is the bounded fallback beyond this height.
     */
    private static final int STATS_MAX_RASTER_HEIGHT = (int) Math.ceil(
            (double) STATS_RASTER_HEIGHT * STATS_MAX_HEIGHT_METERS / STATS_HEIGHT_METERS);
    // SceneCore alpha16 rasterizes at roughly 1728 px/m. Scale the authored raster to the stated
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

        default void onUseSessionModeDefaultsRequested(PresenterMode mode,
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

        /**
         * A live video-mode change (0x3007) has been accepted by the running stream. The listener
         * owns the durable/session-model bookkeeping; the presenter has already updated
         * {@code PreferenceConfiguration} and the XR geometry. The tuple's bitrate remains the
         * requested total wire budget; Apollo's acknowledged post-audio/FEC encoder bitrate is
         * diagnostic state and is not persisted.
         */
        default void onLiveStreamQualityApplied(PresenterMode mode, StreamQualityTuple applied) {
        }

        /** A live video-mode change was rejected; the stream still carries the previous tuple. */
        default void onLiveStreamQualityFailed() {
        }

        /**
         * The host reports the requested mode is valid but only reachable by reconnecting. The
         * listener should commit the staged record and restart the stream immediately rather than
         * leaving the user waiting on a timeout.
         */
        default void onLiveStreamQualityNeedsReconnect() {
        }

        /**
         * A missing or unusable application ACK left the live host tuple unknowable. Unlike an
         * ordinary reconnect refusal, resynchronization is mandatory even if committing staged
         * settings loses a generation race; reconnecting the last durable record is still safer
         * than continuing with divergent client/host state. {@code commitStagedSettings} is true
         * only for an explicit user transaction. Automatic panel-follow recovery must reconnect
         * the last durable record without consuming unrelated staged UI edits.
         */
        default void onLiveStreamQualityResyncRequired(boolean commitStagedSettings) {
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
    /** Original 0.52 m Android raster; retained so repeated fits cannot compound rounding. */
    private int modeOptionsBaseRasterHeightPixels;
    private int modeOptionsRasterHeightPixels;
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
    private BarItem dumpItem;
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
    /** Anything staged differs from the live connection (the Apply button's enabled state). */
    private boolean reconnectPending;
    /** Whether applying that staged state must reconnect rather than change the stream live. */
    private boolean applyRequiresReconnect = true;
    /** Guards a live video-mode change so it cannot interleave with another transaction. */
    private boolean liveQualityChangeInProgress;
    /**
     * A resolution ACK is the causal boundary for the decoder confirmation that may reveal the
     * surface. Any decoder output received before that ACK is provisional and is discarded when
     * the single post-ACK confirmation transition begins.
     */
    private final LiveQualityConfirmationGate liveQualityConfirmations =
            new LiveQualityConfirmationGate();
    private StreamQualityTuple pendingLiveQuality;
    private StreamQualityTuple previousLiveQuality;
    /** Host-reconciled tuple retained until a resolution transaction also receives its fresh IDR. */
    private StreamQualityTuple acknowledgedLiveQuality;
    /** Presentation mode that owned the request; never infer it from mutable current mode at ACK. */
    private PresenterMode pendingLiveQualityMode;
    /**
     * Last encoder bitrate acknowledged by Apollo after its audio/FEC deductions. This is
     * diagnostic state only: {@link PreferenceConfiguration#bitrate} and persisted XR quality
     * retain the user's total wire budget.
     */
    private int effectiveEncoderBitrateKbps;
    /**
     * Frame rate the user actually chose, and the ceiling panel-follow may never exceed. Distinct
     * from {@link PreferenceConfiguration#fps}, which holds whatever rate the stream is running at
     * right now — including one that panel-follow lowered it to.
     */
    private final PanelRefreshRateState panelRefreshRateState;
    /** Origin of the outstanding request; automatic panel-follow changes are never persisted. */
    private LiveQualityRequestOrigin pendingLiveQualityOrigin;
    /** User-authored tuple retained separately when the panel caps the effective wire FPS. */
    private StreamQualityTuple pendingDurableUserQuality;
    /** Opaque u16 correlation token for the outstanding 0x3007 request; -1 when there is none. */
    private int pendingVideoModeRequestId = -1;
    private int videoModeRequestCounter;
    private static final long LIVE_QUALITY_ACK_TIMEOUT_MS = 4000L;
    private final android.os.Handler liveQualityHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable liveQualityAckTimeoutRunnable = this::onLiveQualityAckTimeout;
    private boolean panelRateReconcilePosted;
    private final Runnable panelRateReconcileRunnable = () -> {
        panelRateReconcilePosted = false;
        reconcilePanelRefreshRate();
    };
    private boolean sessionControlsEnabled = true;
    private final EnumMap<SessionSettingsModel.Key, XrChoiceGroup> sessionChoiceGroups =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, XrBitrateControl> sessionBitrateControls =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, TextView> sessionSourceViews =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, TextView> sessionPendingViews =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private Button sessionDefaultsButton;
    private Button sessionApplyButton;
    private PresenterMode renderedModeOptionsMode;
    private XrResolutionSelector modeResolutionSelector;
    private XrSegmentedLadder modeFpsLadder;
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
    private TextView glanceLoadView;
    private TextView glanceStatusView;
    private static final long GLANCE_LOAD_INTERVAL_MS = 1000L;
    private final android.os.Handler glanceLoadHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean hostActivityStarted;
    private final Runnable glanceLoadRunnable = new Runnable() {
        @Override
        public void run() {
            if (!hostActivityStarted || glanceLoadView == null) {
                return;
            }
            updateGlanceLoad(devicePerformanceSampler.sample());
            if (hostActivityStarted && glanceLoadView != null) {
                glanceLoadHandler.postDelayed(this, GLANCE_LOAD_INTERVAL_MS);
            }
        }
    };

    private final android.os.Handler dockVisibilityHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable collapseDockRunnable = this::collapseDockIfIdle;
    private boolean dockCollapsed;
    private View dockHoverTarget;
    private View dockFocusTarget;

    /** Compact performance-stats panel wrapped inward from the screen's right edge. */
    private PanelEntity statsPanel;
    /** Content root of the stats panel, measured to size the panel to its rows. */
    private LinearLayout statsContentRoot;
    private float statsHeightMeters = STATS_HEIGHT_METERS;
    private int statsRasterHeightPixels = STATS_RASTER_HEIGHT;
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
    private static final int HOST_SBS_TELEMETRY_BACKGROUND_INTERVAL_MS = 500;
    private static final int HOST_SBS_TELEMETRY_FOCUSED_INTERVAL_MS = 100;
    static final long HOST_SBS_TELEMETRY_RETRY_DELAY_MS = 500L;
    static final int HOST_SBS_TELEMETRY_MAX_RETRIES = 3;
    private final HostSbsTelemetryTracker hostSbsTelemetryTracker =
            new HostSbsTelemetryTracker();
    private final android.os.Handler hostSbsTelemetryRetryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int hostSbsTelemetryRequestCounter;
    private boolean hostSbsTelemetryRequested;
    private boolean hostSbsTelemetryFocused;
    private int hostSbsTelemetryRetryAttempts;
    private boolean hostSbsTelemetryRetryPending;
    /**
     * Set before native connection teardown starts. Once set, this presenter must never call any
     * control-stream API because moonlight-common destroys its transport mutexes during
     * {@code NvConnection.stop()}.
     */
    private boolean controlTransportClosing;
    private boolean presenterDestroyed;
    /** Apollo-3D control messages are opt-in; regular Sunshine/Apollo must never receive them. */
    private boolean hostControlExtensionsSupported = true;
    private final Runnable hostSbsTelemetryRetryRunnable =
            this::retryHostSbsTelemetrySubscription;

    private void retryHostSbsTelemetrySubscription() {
        hostSbsTelemetryRetryPending = false;
        if (controlTransportClosing || !hostControlExtensionsSupported
                || !hostSbsTelemetryRequested
                || !streamPresentationReady
                || currentPresenterMode != PresenterMode.HOST_SBS_AI) {
            return;
        }
        hostSbsTelemetryRetryAttempts++;
        sendHostSbsTelemetrySubscriptionAttempt();
    }

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

    static final int STATS_TEXT_DIMEN = R.dimen.xr_text_display;
    static final int SESSION_SUMMARY_TEXT_DIMEN = R.dimen.xr_text_title;
    static final int SESSION_GROUP_TEXT_DIMEN = R.dimen.xr_text_title;
    static final int SESSION_ROW_TITLE_TEXT_DIMEN = R.dimen.xr_text_display;
    static final int SESSION_META_TEXT_DIMEN = R.dimen.xr_text_title;
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

    /**
     * True only for Raw Full, whose {@code 2W x H} frame is a transport no other mode uses.
     *
     * <p>Raw Half packs at {@code W x H} ({@code rawSbsPackedDimensions} multiplies the per-eye
     * width by 1 for Half, 2 for Full), which is byte-for-byte the same stream Normal negotiates,
     * sent with {@code sbs_mode 0} — {@link #wireModeFor} already maps Raw to
     * {@code SBS_MODE_OFF}. The host cannot distinguish the two.</p>
     */
    static boolean usesRawPackedTransport(
            PresenterMode mode,
            PreferenceConfiguration.RawSbsPerEyeResolution perEyeResolution) {
        return mode == PresenterMode.HOST_SBS_RAW
                && perEyeResolution == PreferenceConfiguration.RawSbsPerEyeResolution.FULL;
    }

    /**
     * Maps the logical quality tuple used by the UI/session store onto the 0x3007 wire geometry.
     * Raw Full is the only mode whose requested desktop is already packed before Apollo sees it.
     */
    static int[] liveVideoModeWireDimensions(
            PresenterMode mode, int logicalWidth, int logicalHeight,
            PreferenceConfiguration.RawSbsPerEyeResolution rawPerEyeResolution) {
        if (mode == PresenterMode.HOST_SBS_RAW) {
            if (rawPerEyeResolution == null) {
                return null;
            }
            try {
                int[] packed = PreferenceConfiguration.rawSbsPackedDimensions(
                        logicalWidth, logicalHeight, rawPerEyeResolution);
                return isUsableLiveVideoModeWireDimensions(packed[0], packed[1])
                        ? packed : null;
            } catch (IllegalArgumentException invalidGeometry) {
                return null;
            }
        }
        return isUsableLiveVideoModeWireDimensions(logicalWidth, logicalHeight)
                ? new int[] {logicalWidth, logicalHeight} : null;
    }

    /**
     * Converts the authoritative 0x3008 wire geometry back into the logical quality tuple.
     * Every ACK status carries the mode that actually remains on the wire, so malformed geometry
     * must fail closed even for a refusal rather than being mistaken for a logical per-eye size.
     */
    static int[] liveVideoModeLogicalDimensions(
            PresenterMode mode, int wireWidth, int wireHeight,
            PreferenceConfiguration.RawSbsPerEyeResolution rawPerEyeResolution) {
        if (!isUsableLiveVideoModeWireDimensions(wireWidth, wireHeight)) {
            return null;
        }
        if (mode == PresenterMode.HOST_SBS_RAW) {
            if (rawPerEyeResolution == null) {
                return null;
            }
            if (rawPerEyeResolution == PreferenceConfiguration.RawSbsPerEyeResolution.FULL) {
                // wireWidth is even by validation, so the SBS split is exact.
                int logicalWidth = wireWidth / 2;
                return logicalWidth >= 2
                        ? new int[] {logicalWidth, wireHeight} : null;
            }
        }
        return new int[] {wireWidth, wireHeight};
    }

    private static boolean isUsableLiveVideoModeWireDimensions(int width, int height) {
        int cap = PreferenceConfiguration.MAX_HOST_SBS_PACKED_WIDTH_HEVC_AV1;
        return width >= 2 && height >= 2
                && width <= cap && height <= cap
                && (width & 1) == 0 && (height & 1) == 0;
    }

    /**
     * True when bitrate must be costed against a {@code 2W x H} encoded frame.
     *
     * <p>Host SBS AI is always double-width. Raw is double-width only at Full per-eye resolution;
     * Raw Half packs two half-width eyes into the ordinary {@code W x H} transport.</p>
     */
    static boolean usesPackedBitrateCost(
            PresenterMode mode,
            PreferenceConfiguration.RawSbsPerEyeResolution perEyeResolution) {
        // Unknown Raw packing must not silently under-recommend. Full is the shipped default and
        // the conservative 2W x H cost; other modes are unaffected by this fallback.
        PreferenceConfiguration.RawSbsPerEyeResolution costResolution =
                perEyeResolution != null
                        ? perEyeResolution
                        : PreferenceConfiguration.RawSbsPerEyeResolution.FULL;
        return mode == PresenterMode.HOST_SBS_AI
                || usesRawPackedTransport(mode, costResolution);
    }

    /** Uses the staged Raw choice so its bitrate hint changes before the user applies the edit. */
    static boolean usesPackedBitrateCost(
            PresenterMode mode,
            RawSbsModeSettingsModel rawModel,
            PreferenceConfiguration.RawSbsPerEyeResolution appliedFallback) {
        PreferenceConfiguration.RawSbsPerEyeResolution perEyeResolution =
                rawModel != null ? rawModel.pendingResolution : appliedFallback;
        return usesPackedBitrateCost(mode, perEyeResolution);
    }

    /** Conservative default for callers that do not know the session's Raw packing. */
    static boolean requiresReconnectBeforeModeSwitch(
            PresenterMode previousMode, PresenterMode nextMode) {
        return requiresReconnectBeforeModeSwitch(previousMode, nextMode,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL);
    }

    /**
     * Whether a mode switch crosses a negotiated transport boundary and must therefore reconnect.
     *
     * <p>Only Raw Full renegotiates. Entering or leaving Raw <em>Half</em> changes nothing on the
     * wire, so it is a pure client-side presentation change: flip the SceneCore stereo mode and
     * re-apply the quad aspect. The packing itself cannot change without a reconnect, so one
     * value applies to both sides of the comparison.</p>
     */
    static boolean requiresReconnectBeforeModeSwitch(
            PresenterMode previousMode, PresenterMode nextMode,
            PreferenceConfiguration.RawSbsPerEyeResolution perEyeResolution) {
        return usesRawPackedTransport(previousMode, perEyeResolution)
                != usesRawPackedTransport(nextMode, perEyeResolution);
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

    static boolean resetsHostDepthStatusAtTransitionStart(
            PresenterMode previousMode, PresenterMode nextMode) {
        return previousMode != nextMode && nextMode == PresenterMode.HOST_SBS_AI;
    }

    static boolean resetsHostDepthStatusAtTransitionCommit(
            PresenterMode previousMode, PresenterMode nextMode) {
        return previousMode != nextMode && previousMode == PresenterMode.HOST_SBS_AI;
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

    /**
     * Completion gate for a live video-mode request.
     *
     * <p>A fast bitrate/frame-rate request needs only its correlated APPLIED ACK. A resolution
     * request additionally needs MediaCodec to release a fresh IDR at the acknowledged geometry.
     * A decoder event that arrives before the APPLIED ACK is retained only for diagnostics. The
     * ACK invalidates that provisional evidence and arms exactly one post-ACK decoder transition,
     * which supplies the only decoder output allowed to settle the request. Client SBS resolution
     * changes additionally wait for exact validation of the replacement packed EGL output.</p>
     */
    static final class LiveQualityConfirmationGate {
        private boolean decoderConfirmationRequired;
        private boolean presentationConfirmationRequired;
        private boolean appliedAckReceived;
        private boolean presentationReady;
        private boolean postAckDecoderConfirmationStarted;
        private boolean decoderOutputReceived;
        private boolean matchingDecoderOutputReceived;
        private int decoderOutputWidth;
        private int decoderOutputHeight;

        void begin(boolean decoderConfirmationRequired) {
            begin(decoderConfirmationRequired, false);
        }

        void begin(boolean decoderConfirmationRequired,
                   boolean presentationConfirmationRequired) {
            this.decoderConfirmationRequired = decoderConfirmationRequired;
            this.presentationConfirmationRequired = presentationConfirmationRequired;
            appliedAckReceived = false;
            presentationReady = !presentationConfirmationRequired;
            postAckDecoderConfirmationStarted = false;
            decoderOutputReceived = false;
            matchingDecoderOutputReceived = false;
            decoderOutputWidth = 0;
            decoderOutputHeight = 0;
        }

        boolean onAppliedAck() {
            appliedAckReceived = true;
            return canSettle();
        }

        /** Re-arms readiness when a host clamp supersedes an in-flight packed-output resize. */
        void expectPresentationConfirmation() {
            presentationConfirmationRequired = true;
            presentationReady = false;
        }

        boolean onPresentationReady() {
            presentationReady = true;
            return canSettle();
        }

        boolean onDecoderOutput(int actualWidth, int actualHeight,
                                int expectedWidth, int expectedHeight) {
            decoderOutputReceived = true;
            decoderOutputWidth = actualWidth;
            decoderOutputHeight = actualHeight;
            matchingDecoderOutputReceived = actualWidth > 0 && actualHeight > 0
                    && actualWidth == expectedWidth && actualHeight == expectedHeight;
            return canSettle();
        }

        /**
         * Invalidates every pre-ACK decoder event and arms the request's one allowed post-ACK
         * confirmation. Returns false for a fast request, before APPLIED, or after a rearm has
         * already been consumed.
         */
        boolean beginPostAckDecoderConfirmation() {
            if (!decoderConfirmationRequired || !appliedAckReceived
                    || postAckDecoderConfirmationStarted) {
                return false;
            }
            postAckDecoderConfirmationStarted = true;
            decoderOutputReceived = false;
            matchingDecoderOutputReceived = false;
            decoderOutputWidth = 0;
            decoderOutputHeight = 0;
            return true;
        }

        boolean canSettle() {
            return appliedAckReceived
                    && (!presentationConfirmationRequired || presentationReady)
                    && (!decoderConfirmationRequired
                    || (postAckDecoderConfirmationStarted
                    && matchingDecoderOutputReceived));
        }

        boolean hasAppliedAck() {
            return appliedAckReceived;
        }

        boolean isPresentationReady() {
            return presentationReady;
        }

        boolean hasDecoderOutput() {
            return decoderOutputReceived;
        }

        boolean hasMatchingDecoderOutput() {
            return matchingDecoderOutputReceived;
        }

        boolean hasPostAckDecoderConfirmationStarted() {
            return postAckDecoderConfirmationStarted;
        }

        boolean isWaitingForPresentationAfterMatchingPostAckOutput() {
            return appliedAckReceived
                    && postAckDecoderConfirmationStarted
                    && matchingDecoderOutputReceived
                    && presentationConfirmationRequired
                    && !presentationReady;
        }

        void clear() {
            decoderConfirmationRequired = false;
            presentationConfirmationRequired = false;
            appliedAckReceived = false;
            presentationReady = false;
            postAckDecoderConfirmationStarted = false;
            decoderOutputReceived = false;
            matchingDecoderOutputReceived = false;
            decoderOutputWidth = 0;
            decoderOutputHeight = 0;
        }
    }

    static boolean decoderMismatchRequiresMandatoryResync(
            boolean resolutionChangeInProgress,
            LiveQualityConfirmationGate confirmations) {
        return resolutionChangeInProgress
                && confirmations != null
                && !confirmations.canSettle()
                && confirmations.hasAppliedAck()
                && confirmations.hasPostAckDecoderConfirmationStarted()
                && confirmations.hasDecoderOutput()
                && !confirmations.hasMatchingDecoderOutput();
    }

    enum LiveQualityRequestOrigin {
        USER,
        PANEL_FOLLOW,
    }

    static boolean shouldPersistLiveQualityRequest(LiveQualityRequestOrigin origin) {
        return origin == LiveQualityRequestOrigin.USER;
    }

    static boolean shouldCommitStagedSettingsForResync(LiveQualityRequestOrigin origin) {
        return origin == LiveQualityRequestOrigin.USER;
    }

    static boolean shouldCommitStagedSettingsForMalformedAckResync(
            VideoModeAckOutcome outcome, LiveQualityRequestOrigin origin) {
        // These two statuses prove that the requested tuple was not installed. Reconnecting is
        // still required when their authoritative rollback geometry is malformed, but persisting
        // that rejected tuple would turn recovery into an unintended retry.
        return outcome != VideoModeAckOutcome.REJECTED_NO_RETRY
                && outcome != VideoModeAckOutcome.FAILED_RETRYABLE
                && shouldCommitStagedSettingsForResync(origin);
    }

    /**
     * Small deterministic state machine for panel-rate following.
     *
     * <p>The observed display rate, durable user ceiling, and effective stream rate are three
     * different facts. Keeping them here prevents a Surface/display callback from being consumed
     * while another live-quality transaction is busy. Repeated callbacks coalesce to the newest
     * observation, one transient retry is allowed, and an invalid/clamped target is blocked until
     * either the panel or the user's ceiling actually changes.</p>
     */
    static final class PanelRefreshRateState {
        private int observedPanelHz = -1;
        private int userCeilingHz;
        private int inFlightTargetHz = -1;
        private int blockedTargetHz = -1;
        private int retriesRemaining = 1;
        private boolean reconcilePending;

        PanelRefreshRateState(float initialUserCeilingFps) {
            userCeilingHz = Math.max(1, Math.round(initialUserCeilingFps));
        }

        void observe(float panelRefreshHz) {
            int panelHz = Math.round(panelRefreshHz);
            if (panelHz <= 0) {
                return;
            }
            if (panelHz != observedPanelHz) {
                observedPanelHz = panelHz;
                blockedTargetHz = -1;
                retriesRemaining = 1;
                reconcilePending = true;
            }
        }

        int nextTarget(int effectiveStreamHz, boolean transactionBlocked) {
            int desired = desiredTargetHz();
            if (desired <= 0 || desired == effectiveStreamHz) {
                if (!transactionBlocked) {
                    reconcilePending = false;
                }
                return -1;
            }
            if (transactionBlocked) {
                reconcilePending = true;
                return -1;
            }
            if (desired == blockedTargetHz || inFlightTargetHz > 0) {
                return -1;
            }
            inFlightTargetHz = desired;
            reconcilePending = false;
            return desired;
        }

        int capUserTarget(int requestedCeilingHz) {
            if (observedPanelHz <= 0) {
                return requestedCeilingHz;
            }
            return snapToOfferedFrameRate(Math.min(requestedCeilingHz, observedPanelHz));
        }

        void automaticRequestSucceeded(int appliedHz) {
            int requested = inFlightTargetHz;
            inFlightTargetHz = -1;
            retriesRemaining = 1;
            if (requested > 0 && appliedHz != requested) {
                // The host accepted but clamped this target. Do not spin trying the same value.
                blockedTargetHz = requested;
                reconcilePending = false;
            }
        }

        void automaticRequestFailed(boolean retryable) {
            int failed = inFlightTargetHz;
            inFlightTargetHz = -1;
            if (retryable && retriesRemaining > 0) {
                retriesRemaining--;
                reconcilePending = true;
            } else {
                blockedTargetHz = failed;
                reconcilePending = false;
            }
        }

        void userRequestSucceeded(float durableCeilingFps) {
            int ceiling = Math.max(1, Math.round(durableCeilingFps));
            if (ceiling != userCeilingHz) {
                userCeilingHz = ceiling;
                blockedTargetHz = -1;
                retriesRemaining = 1;
            }
            reconcilePending = true;
        }

        void otherTransactionSettled() {
            reconcilePending = true;
        }

        /**
         * Releases local ownership when an ambiguous missing ACK forces a reconnect. Do not
         * consume the bounded transient retry or block the panel target: the authoritative stream
         * may still need to follow the observed panel after reconnecting at the durable ceiling.
         */
        void requestAbandonedForReconnect() {
            inFlightTargetHz = -1;
            retriesRemaining = 1;
            reconcilePending = true;
        }

        int desiredTargetHz() {
            if (observedPanelHz <= 0 || userCeilingHz <= 0) {
                return -1;
            }
            return snapToOfferedFrameRate(Math.min(userCeilingHz, observedPanelHz));
        }

        int getObservedPanelHz() {
            return observedPanelHz;
        }

        int getUserCeilingHz() {
            return userCeilingHz;
        }

        int getInFlightTargetHz() {
            return inFlightTargetHz;
        }

        boolean isReconcilePending() {
            return reconcilePending;
        }
    }

    /**
     * Surface/display pacing follows the durable user ceiling, never a temporary panel-follow
     * rate. In Client SBS the decoder consumes an offscreen renderer input, so the SceneCore
     * presentation Surface is the only output vote that can reliably let the panel return upward.
     */
    static int durableSurfaceFrameRateVoteHz(PanelRefreshRateState state) {
        return state != null ? Math.max(1, state.getUserCeilingHz()) : 0;
    }

    static boolean liveQualityTransactionBusy(
            int pendingRequestId, boolean resolutionChangeInProgress) {
        return pendingRequestId > 0 || resolutionChangeInProgress;
    }

    private boolean liveQualityTransactionBusy() {
        return liveQualityTransactionBusy(
                pendingVideoModeRequestId, liveQualityChangeInProgress);
    }

    enum LiveQualityAckTimeoutDisposition {
        RECONNECT_FAST_USER,
        RECONNECT_FAST_PANEL_FOLLOW,
        RECONNECT_RESOLUTION,
    }

    static LiveQualityAckTimeoutDisposition liveQualityAckTimeoutDisposition(
            boolean resolutionChangeInProgress, LiveQualityRequestOrigin origin) {
        if (resolutionChangeInProgress) {
            return LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION;
        }
        return origin == LiveQualityRequestOrigin.PANEL_FOLLOW
                ? LiveQualityAckTimeoutDisposition.RECONNECT_FAST_PANEL_FOLLOW
                : LiveQualityAckTimeoutDisposition.RECONNECT_FAST_USER;
    }

    static boolean shouldRevealSurfaceAfterAckTimeout(
            LiveQualityAckTimeoutDisposition disposition, boolean matchingDecoderOutput) {
        return disposition != LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION
                || matchingDecoderOutput;
    }

    static boolean shouldRevealSurfaceDuringMandatoryResync(
            LiveQualityAckTimeoutDisposition disposition,
            boolean matchingDecoderOutput,
            boolean presentationGeometryAdopted) {
        return presentationGeometryAdopted
                && shouldRevealSurfaceAfterAckTimeout(disposition, matchingDecoderOutput);
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
        this.panelRefreshRateState = new PanelRefreshRateState(prefConfig.fps);
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

    /**
     * Enables Apollo-3D-only SBS, telemetry, debug-dump, and live video-mode controls. A regular
     * Sunshine or Apollo host still supports Normal, Raw SBS capture, and on-device Client SBS;
     * stream-quality changes use the standard reconnect path instead. If an authoritative
     * connection-time downgrade finds Host SBS AI already prepared from stale discovery state,
     * {@link com.limelight.Game} persists Normal and replaces the connection before frame 1.
     */
    public void setHostControlExtensionsSupported(boolean supported) {
        if (hostControlExtensionsSupported == supported) {
            return;
        }
        hostControlExtensionsSupported = supported;
        if (!supported) {
            liveQualityHandler.removeCallbacks(panelRateReconcileRunnable);
            panelRateReconcilePosted = false;
            clearHostSbsTelemetrySubscriptionState();
            if (deferredPresenterMode == PresenterMode.HOST_SBS_AI) {
                deferredPresenterMode = PresenterMode.NORMAL;
            }
        }
        for (BarItem item : barItems) {
            if (item.selectsMode != null) {
                item.setEnabled(streamPresentationReady && sessionControlsEnabled
                        && isPresentationModeSupported(
                                item.selectsMode, hostControlExtensionsSupported));
            }
        }
        updateHostDebugDumpAvailability();
        reconcileHostSbsTelemetrySubscription();
    }

    static boolean isPresentationModeSupported(PresenterMode mode,
                                               boolean hostControlExtensionsSupported) {
        return mode != PresenterMode.HOST_SBS_AI || hostControlExtensionsSupported;
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
                                  boolean applyPending) {
        setSettingsModels(sessionModel, qualityModels, clientModel, rawModel, applyPending, true);
    }

    /**
     * @param applyPending          anything staged differs from the live connection
     * @param applyRequiresReconnect applying it must tear down and re-establish the stream
     */
    public void setSettingsModels(SessionSettingsModel sessionModel,
                                  Map<PresenterMode, ModeStreamQualityModel> qualityModels,
                                  ClientSbsModeSettingsModel clientModel,
                                  RawSbsModeSettingsModel rawModel,
                                  boolean applyPending,
                                  boolean applyRequiresReconnect) {
        boolean reconnectPending = applyPending;
        this.applyRequiresReconnect = applyRequiresReconnect;
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
        if (modeFpsLadder != null) {
            modeFpsLadder.setEnabled(enabled);
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
                item.setEnabled(enabled && streamPresentationReady
                        && isPresentationModeSupported(
                                item.selectsMode, hostControlExtensionsSupported));
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
        String name;
        if (PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2.equals(id)) {
            name = "MiDaS 2.1 Small";
        }
        else if (PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DEPTHART_S448_FP16.equals(id)) {
            name = "DepthART S448 FP16 (Experimental)";
        }
        else if (PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16.equals(id)) {
            name = "ZipDepth Base FP16 (Experimental · short 384)";
        }
        else {
            name = "Depth Anything V2 Small";
        }
        return new ClientSbsModeSettingsModel(id, name, id, name,
                SessionSettingsModel.Source.GLOBAL,
                ClientSbsModeSettingsModel.selectBucket(
                        id, prefConfig.width, prefConfig.height),
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
        adoptVideoSurface(surfaceEntity.getSurface());

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
        BarItem hostSbsAi = new BarItem(
                activity.getString(R.string.xr_bar_host_sbs_ai),
                R.drawable.ic_xr_mode_host_sbs, PresenterMode.HOST_SBS_AI);
        BarItem hostSbsRaw = new BarItem(
                activity.getString(R.string.xr_bar_host_sbs_raw),
                R.drawable.ic_xr_mode_host_sbs_raw, PresenterMode.HOST_SBS_RAW);
        BarItem clientSbsAi = new BarItem(
                activity.getString(R.string.xr_bar_client_sbs_ai),
                R.drawable.ic_xr_mode_client_sbs, PresenterMode.CLIENT_SBS_AI);
        BarItem settings = new BarItem(
                activity.getString(R.string.xr_home_settings),
                R.drawable.ic_settings, /* selectsMode= */ null);
        BarItem cinemaView = new BarItem(
                activity.getString(R.string.xr_bar_cinema_view),
                R.drawable.ic_xr_cinema_view, /* selectsMode= */ null);
        BarItem stats = new BarItem(
                activity.getString(R.string.xr_bar_stats),
                R.drawable.ic_xr_diagnostics, /* selectsMode= */ null);
        BarItem library = new BarItem(
                activity.getString(R.string.xr_bar_library),
                R.drawable.ic_xr_library, /* selectsMode= */ null);
        BarItem dump = null;
        if (BuildConfig.DEBUG) {
            dump = new BarItem(
                    activity.getString(R.string.xr_bar_dump),
                    R.drawable.ic_xr_dump, /* selectsMode= */ null);
        }
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
        if (dump != null) {
            dump.onTap = this::requestHostDebugDump;
        }
        endSession.onTap = this::requestEndSession;
        expansion.onTap = this::toggleSecondaryActions;
        settingsItem = settings;
        cinemaItem = cinemaView;
        statsItem = stats;
        dumpItem = dump;
        expansionItem = expansion;

        barItems.clear();
        secondaryBarItems.clear();
        barItems.add(normal);
        barItems.add(hostSbsAi);
        barItems.add(hostSbsRaw);
        barItems.add(clientSbsAi);
        barItems.add(settings);
        barItems.add(cinemaView);
        barItems.add(library);
        barItems.add(stats);
        if (dump != null) {
            barItems.add(dump);
        }
        barItems.add(endSession);
        barItems.add(expansion);
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
            int m = dimen(R.dimen.xr_space_xs);
            lp.setMargins(m, m, m, m);
            bar.addView(tile, lp);
            item.root = tile;
            if (item.secondary) {
                tile.setVisibility(View.GONE);
            }
            if (isMode) {
                item.setEnabled(streamPresentationReady && sessionControlsEnabled
                        && isPresentationModeSupported(
                                item.selectsMode, hostControlExtensionsSupported));
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
        setTextSize(revealButton, R.dimen.xr_text_emphasis);
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
        dockRevealPill.setPadding(dimen(R.dimen.xr_space_lg),
                dimen(R.dimen.xr_space_sm), dimen(R.dimen.xr_space_lg),
                dimen(R.dimen.xr_space_sm));
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
                paletteColor(R.color.xr_surface_sunken), paletteColor(R.color.xr_border_panel), 1));
        modeOptionsHeightMeters = MODE_OPTIONS_MIN_HEIGHT_METERS;
        modeOptionsBaseRasterHeightPixels = 0;
        modeOptionsRasterHeightPixels = 0;
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
        root.setBackgroundColor(paletteColor(R.color.xr_surface_sunken));
        int p = statsDimen(R.dimen.xr_space_lg);
        root.setPadding(p, p, p, p);

        statsTitle = new TextView(activity);
        statsTitle.setText(R.string.xr_stats_title);
        statsTitle.setTextColor(paletteColor(R.color.xr_accent));
        setScaledTextSize(statsTitle, R.dimen.xr_text_display, STATS_CONTENT_SCALE);
        statsTitle.setTypeface(statsTitle.getTypeface(), android.graphics.Typeface.BOLD);
        statsTitle.setPadding(0, 0, 0, statsDimen(R.dimen.xr_space_sm));
        root.addView(statsTitle);

        statsTable = createStatsTable();
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(statsTable, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        statsContentRoot = root;
        statsHeightMeters = STATS_HEIGHT_METERS;
        statsRasterHeightPixels = STATS_RASTER_HEIGHT;
        statsPanel = PanelEntity.create(
                session, root, new IntSize2d(STATS_RASTER_WIDTH, STATS_RASTER_HEIGHT),
                "xr-stats", statsPose(videoHeightMeters), surfaceEntity);
        statsPanel.setScale(STATS_ENTITY_SCALE);
        statsPanel.setSize(new FloatSize2d(
                statsEntityLocalMeters(STATS_WIDTH_METERS, STATS_ENTITY_SCALE),
                statsEntityLocalMeters(STATS_HEIGHT_METERS, STATS_ENTITY_SCALE)));
        statsPanel.setEnabled(statsVisible);
    }

    /** Create the left-side entity used by shared Session Settings. */
    private void createAuxiliaryPanel(float videoHeightMeters) {
        auxiliaryContentHost = new FrameLayout(activity);
        auxiliaryContentHost.setBackgroundColor(paletteColor(R.color.xr_surface_sunken));
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
        root.setPadding(dimen(R.dimen.xr_space_md), dimen(R.dimen.xr_space_xs),
                dimen(R.dimen.xr_space_md), dimen(R.dimen.xr_space_xs));
        root.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_scrim_strong),
                paletteColor(R.color.xr_border), 1));
        root.setClickable(false);
        root.setFocusable(false);
        glanceRoot = root;

        glanceIdentityView = glanceText(paletteColor(R.color.xr_text_primary));
        glanceModeView = glanceText(paletteColor(R.color.xr_accent_bright));
        glanceStreamView = glanceText(paletteColor(R.color.xr_accent_bright));
        glanceLoadView = glanceText(paletteColor(R.color.xr_accent_bright));
        glanceStatusView = glanceText(paletteColor(R.color.xr_status_warn));
        glanceStatusView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        root.addView(glanceIdentityView, glanceLayoutParams(1.35f));
        root.addView(glanceModeView, glanceLayoutParams(0.95f));
        root.addView(glanceStreamView, glanceLayoutParams(1.5f));
        root.addView(glanceLoadView, glanceLayoutParams(1.0f));
        root.addView(glanceStatusView, glanceLayoutParams(0.72f));

        glancePanel = PanelEntity.create(session, root,
                new FloatSize2d(GLANCE_WIDTH_METERS, GLANCE_HEIGHT_METERS),
                "xr-stream-glance", glancePose(videoHeightMeters), surfaceEntity);
        glancePanel.setEnabled(true);
        reconcileGlanceLoadTicker();
    }

    /**
     * Match passive device-load sampling to the host Activity's visible lifecycle. START/STOP is
     * intentional: a translucent dialog may pause the Activity while its glance panel is visible.
     */
    public void onHostActivityStarted() {
        if (hostActivityStarted) {
            return;
        }
        hostActivityStarted = true;
        devicePerformanceSampler.resetCpuBaseline();
        reconcileGlanceLoadTicker();
    }

    /** Stop all passive load sampling while the streaming Activity is backgrounded. */
    public void onHostActivityStopped() {
        hostActivityStarted = false;
        glanceLoadHandler.removeCallbacks(glanceLoadRunnable);
        devicePerformanceSampler.resetCpuBaseline();
    }

    private void reconcileGlanceLoadTicker() {
        glanceLoadHandler.removeCallbacks(glanceLoadRunnable);
        if (hostActivityStarted && glanceLoadView != null) {
            glanceLoadHandler.post(glanceLoadRunnable);
        }
    }

    private TextView glanceText(int color) {
        TextView view = controlText("", R.dimen.xr_text_emphasis, color);
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
        updateHostDebugDumpAvailability();
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

        // PreferenceConfiguration is the effective on-wire tuple. The settings model intentionally
        // retains the user's durable FPS ceiling while panel-follow is temporarily below it.
        StreamQualityTuple live = new StreamQualityTuple(
                prefConfig.width + "x" + prefConfig.height,
                formatFrameRate(prefConfig.fps), prefConfig.bitrate);
        glanceStreamView.setText(formatGlanceStream(activity,
                live.resolution, glanceFrameRateText(live.frameRate), live.bitrateKbps,
                prefConfig.enableHdr ? activity.getString(R.string.xr_glance_hdr)
                        : activity.getString(R.string.xr_glance_sdr)));

        boolean liveStatus = streamPresentationReady && sessionControlsEnabled
                && !modeSwitchInProgress && !liveQualityTransactionBusy()
                && pendingDecoderTransitionMode == null
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
        glanceStatusView.setTextColor(liveStatus ? paletteColor(R.color.xr_status_ok) : paletteColor(R.color.xr_status_warn));
        updateDockRevealPill(statusText, liveStatus);
    }

    private int paletteColor(int colorRes) {
        return ContextCompat.getColor(activity, colorRes);
    }

    /**
     * Client load on the glance strip. Driven by its own ticker rather than {@code setStats}:
     * decoder telemetry is switched off entirely while the stats pane is closed, so a readout that
     * rode on it would sit blank exactly when it is the only place to see the numbers. The cost is
     * one getElapsedCpuTime() and two sysfs reads a second -- no GL call, so no GPU sync.
     */
    private void updateGlanceLoad(DevicePerformanceSampler.Snapshot device) {
        if (glanceLoadView == null) {
            return;
        }
        String unavailable = activity.getString(R.string.xr_glance_load_unavailable);
        String cpu = device != null && device.appCpuAvailable
                ? String.format(Locale.US, "%.1f", device.appCpuCoreEquivalent) : unavailable;
        // Device-wide GPU busy, not this process's share: the KGSL counter cannot attribute.
        String gpu = device != null && device.deviceGpuUtilizationAvailable
                ? String.format(Locale.US, "%.0f%%", device.deviceGpuUtilizationPercent)
                : unavailable;
        glanceLoadView.setText(activity.getString(R.string.xr_glance_load, cpu, gpu));
    }

    private void updateDockRevealPill(int statusText, boolean liveStatus) {
        if (dockRevealPill == null) {
            return;
        }
        dockRevealPill.setText(activity.getString(R.string.xr_dock_reveal_status,
                modeLabel(currentPresenterMode), activity.getString(statusText)));
        dockRevealPill.setTextColor(liveStatus ? paletteColor(R.color.xr_status_ok) : paletteColor(R.color.xr_status_warn));
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
                reconnectPending, modeSwitchInProgress || liveQualityTransactionBusy(),
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
        if (!isPresentationModeSupported(
                item.selectsMode, hostControlExtensionsSupported)) {
            LimeLog.info("XR: ignoring Apollo-3D host mode on a standard host");
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
            if (liveQualityTransactionBusy()) {
                return;
            }
            if (requiresReconnectBeforeModeSwitch(
                    currentPresenterMode, item.selectsMode,
                    prefConfig.rawSbsPerEyeResolution)) {
                // Raw Full negotiates a 2W x H base frame that no other mode uses, so the
                // replacement connection has to renegotiate it. Raw Half is W x H — the same
                // stream as Normal — and switches live through selectMode() below.
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

    /**
     * Apollo can produce a 3D diagnostic dump only while its own depth pipeline owns the stream.
     * Raw SBS is already-packed application content, while Normal and Client SBS have no host
     * depth result to capture. Transitions are excluded so one tap cannot be attributed to two
     * different stream geometries or pipeline generations.
     */
    static boolean isHostDebugDumpAvailable(
            PresenterMode mode, boolean streamReady, boolean controlsEnabled,
            boolean transitionInProgress, boolean depthReady) {
        return mode == PresenterMode.HOST_SBS_AI
                && streamReady
                && controlsEnabled
                && !transitionInProgress
                && depthReady;
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
        if (statsChanged) {
            reconcileHostSbsTelemetrySubscription();
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
        int padding = dimen(R.dimen.xr_space_lg);
        root.setPadding(padding, dimen(R.dimen.xr_space_md),
                padding, dimen(R.dimen.xr_space_md));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dimen(R.dimen.xr_space_lg), dimen(R.dimen.xr_space_md),
                dimen(R.dimen.xr_space_lg), dimen(R.dimen.xr_space_md));
        header.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_panel), 1));
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
                R.dimen.xr_text_emphasis, paletteColor(R.color.xr_accent));
        qualityHeading.setAllCaps(true);
        qualityHeading.setLetterSpacing(0.08f);
        qualityHeading.setTypeface(qualityHeading.getTypeface(),
                android.graphics.Typeface.BOLD);
        qualityHeading.setPadding(dimen(R.dimen.xr_space_xs),
                dimen(R.dimen.xr_space_md), 0, dimen(R.dimen.xr_space_sm));
        root.addView(qualityHeading);

        addModeQualityControls(root, mode);
        if (mode == PresenterMode.HOST_SBS_RAW) {
            addRawSbsModeOptions(root);
        }
        if (mode == PresenterMode.CLIENT_SBS_AI) {
            LinearLayout clientRow = new LinearLayout(activity);
            clientRow.setOrientation(LinearLayout.HORIZONTAL);
            clientRow.setGravity(Gravity.CENTER_VERTICAL);
            int rowPadding = dimen(R.dimen.xr_space_md);
            clientRow.setPadding(rowPadding, rowPadding, rowPadding, rowPadding);
            clientRow.setBackground(controlSurfaceBackground(
                    paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_panel), 1));
            addClientSbsModeOptions(clientRow);
            LinearLayout.LayoutParams clientParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clientParams.topMargin = dimen(R.dimen.xr_space_md);
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
        int cardPadding = dimen(R.dimen.xr_space_md);
        resolutionColumn.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        resolutionColumn.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_panel), 1));
        TextView resolutionTitle = controlText(
                activity.getString(R.string.title_resolution_list),
                R.dimen.xr_text_title, paletteColor(R.color.xr_text_primary));
        resolutionTitle.setTypeface(resolutionTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        applyTitleIcon(resolutionTitle, R.drawable.ic_xr_resolution);
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
        resolutionParams.rightMargin = dimen(R.dimen.xr_space_sm);
        qualityRow.addView(resolutionColumn, resolutionParams);

        LinearLayout tuningColumn = new LinearLayout(activity);
        tuningColumn.setOrientation(LinearLayout.VERTICAL);

        SessionSettingsModel.Value fps = model.get(SessionSettingsModel.Key.FRAME_RATE);
        LinearLayout fpsCard = new LinearLayout(activity);
        fpsCard.setOrientation(LinearLayout.VERTICAL);
        fpsCard.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        fpsCard.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_panel), 1));
        LinearLayout fpsHeading = new LinearLayout(activity);
        fpsHeading.setOrientation(LinearLayout.HORIZONTAL);
        fpsHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView fpsTitle = controlText(activity.getString(R.string.title_fps_ceiling),
                R.dimen.xr_text_title, paletteColor(R.color.xr_text_primary));
        applyTitleIcon(fpsTitle, R.drawable.ic_xr_frame_rate);
        fpsHeading.addView(fpsTitle);
        fpsCard.addView(fpsHeading);
        modeFpsLadder = new XrSegmentedLadder(activity);
        configureFpsLadder(mode, fps, model);
        modeFpsLadder.setEnabled(sessionControlsEnabled);
        fpsCard.addView(modeFpsLadder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tuningColumn.addView(fpsCard);

        SessionSettingsModel.Value bitrate = model.get(SessionSettingsModel.Key.BITRATE);
        LinearLayout bitrateCard = new LinearLayout(activity);
        bitrateCard.setOrientation(LinearLayout.VERTICAL);
        bitrateCard.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        bitrateCard.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_panel), 1));
        TextView bitrateTitle = controlText(
                activity.getString(R.string.title_bitrate_ceiling),
                R.dimen.xr_text_title, paletteColor(R.color.xr_text_primary));
        bitrateTitle.setTypeface(bitrateTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        applyTitleIcon(bitrateTitle, R.drawable.ic_xr_bitrate);
        bitrateCard.addView(bitrateTitle);
        modeBitrateControl = new XrBitrateControl(activity);
        String bitrateId = qualityChoiceId(bitrate,
                String.valueOf(model.pendingQuality.bitrateKbps));
        int recommendedKbps = recommendedBitrateKbps(mode, model);
        modeBitrateControl.setChoices(choicesOrCurrent(bitrate, bitrateId), bitrateId,
                recommendedKbps, bitrateHintFor(recommendedKbps), choiceId ->
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
        bitrateParams.topMargin = dimen(R.dimen.xr_space_sm);
        tuningColumn.addView(bitrateCard, bitrateParams);
        LinearLayout.LayoutParams tuningParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f);
        tuningParams.leftMargin = dimen(R.dimen.xr_space_sm);
        qualityRow.addView(tuningColumn, tuningParams);

        root.addView(qualityRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        modeQualityCueView = controlText(modeQualityCue(model), R.dimen.xr_text_title,
                model.requiresReconnect() ? paletteColor(R.color.xr_status_warn) : paletteColor(R.color.xr_text_secondary));
        modeQualityCueView.setPadding(dimen(R.dimen.xr_space_md),
                dimen(R.dimen.xr_space_sm), dimen(R.dimen.xr_space_md),
                dimen(R.dimen.xr_space_sm));
        modeQualityCueView.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface), paletteColor(R.color.xr_border_panel), 1));
        LinearLayout.LayoutParams cueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cueParams.topMargin = dimen(R.dimen.xr_space_sm);
        root.addView(modeQualityCueView, cueParams);
    }

    private void addModeOptionsFooter(LinearLayout root, PresenterMode mode) {
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setPadding(0, dimen(R.dimen.xr_space_md), 0, 0);

        modeDefaultsButton = compactButton(
                activity.getString(R.string.xr_session_use_session));
        modeDefaultsButton.setEnabled(sessionControlsEnabled);
        modeDefaultsButton.setOnClickListener(v -> controlActionListener
                .onUseSessionModeDefaultsRequested(mode, modeStreamQualityModels.get(mode)));
        footer.addView(modeDefaultsButton);

        modeApplyButton = compactButton(applyButtonLabel());
        modeApplyButton.setBackgroundResource(R.drawable.xr_home_primary_action_background);
        modeApplyButton.setEnabled(sessionControlsEnabled && reconnectPending);
        modeApplyButton.setOnClickListener(v -> controlActionListener
                .onApplyAndReconnectRequested(sessionSettingsModel));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        applyParams.leftMargin = dimen(R.dimen.xr_space_md);
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
        if (model.appliesLive()) {
            return activity.getString(R.string.xr_mode_quality_apply_live, source, live);
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
        heading.setPadding(0, 0, dimen(R.dimen.xr_space_lg), 0);

        TextView title = controlText(modeLabel(mode), R.dimen.xr_text_display,
                paletteColor(R.color.xr_text_primary));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        heading.addView(title);

        TextView active = controlText(activity.getString(mode == currentPresenterMode
                        ? R.string.xr_mode_active : R.string.xr_mode_options_title),
                R.dimen.xr_text_title, mode == currentPresenterMode
                        ? paletteColor(R.color.xr_status_ok)
                        : paletteColor(R.color.xr_text_secondary));
        heading.addView(active);
        row.addView(heading, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f));
    }

    private void addModeStatus(LinearLayout row, String label, String value) {
        LinearLayout status = labeledValue(label, value,
                value.toLowerCase(Locale.US).contains("unavailable")
                        ? paletteColor(R.color.xr_danger)
                        : paletteColor(R.color.xr_text_primary));
        row.addView(status, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2.0f));
    }

    private void addClientSbsModeOptions(LinearLayout row) {
        ClientSbsModeSettingsModel model = clientSbsModeSettingsModel;
        LinearLayout modelColumn = new LinearLayout(activity);
        modelColumn.setOrientation(LinearLayout.VERTICAL);
        modelColumn.setGravity(Gravity.CENTER_VERTICAL);
        modelColumn.setPadding(0, 0, dimen(R.dimen.xr_space_md), 0);
        String source = model.source == SessionSettingsModel.Source.GLOBAL
                ? activity.getString(R.string.xr_setting_source_global)
                : activity.getString(R.string.xr_setting_source_session);
        clientModelSourceView = controlText(
                activity.getString(R.string.xr_client_model) + " \u00b7 " + source,
                R.dimen.xr_text_title, paletteColor(R.color.xr_text_secondary));
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
        choiceParams.topMargin = dimen(R.dimen.xr_space_xs);
        modelColumn.addView(clientModelChoiceGroup, choiceParams);

        clientModelPendingView = controlText("", SESSION_META_TEXT_DIMEN,
                paletteColor(R.color.xr_text_secondary));
        clientModelPendingView.setPadding(0, dimen(R.dimen.xr_space_xs), 0, 0);
        modelColumn.addView(clientModelPendingView);
        updateClientModelPendingView(model);
        // The long four-model group stacks on the headset. Let this weighted column contribute
        // that natural height instead of constraining it to the two short status columns.
        row.addView(modelColumn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2.2f));

        LinearLayout aspect = labeledValue(
                activity.getString(R.string.xr_client_aspect_bucket),
                model.bucket, paletteColor(R.color.xr_text_primary));
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
        int cardPadding = dimen(R.dimen.xr_space_md);
        card.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        card.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_panel), 1));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = controlText(
                activity.getString(R.string.xr_raw_per_eye_resolution),
                R.dimen.xr_text_title, paletteColor(R.color.xr_text_primary));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        rawSbsPerEyeResolutionSourceView = controlText(
                rawSbsSourceText(model), SESSION_META_TEXT_DIMEN,
                paletteColor(R.color.xr_text_secondary));
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
        choiceParams.topMargin = dimen(R.dimen.xr_space_sm);
        card.addView(rawSbsPerEyeResolutionChoiceGroup, choiceParams);

        rawSbsGeometryView = controlText(
                rawSbsGeometryText(model), R.dimen.xr_text_emphasis,
                paletteColor(R.color.xr_text_secondary));
        rawSbsGeometryView.setPadding(0, dimen(R.dimen.xr_space_sm), 0, 0);
        card.addView(rawSbsGeometryView);

        rawSbsPerEyeResolutionPendingView =
                controlText("", SESSION_META_TEXT_DIMEN,
                        paletteColor(R.color.xr_status_warn));
        rawSbsPerEyeResolutionPendingView.setPadding(
                0, dimen(R.dimen.xr_space_xs), 0, 0);
        card.addView(rawSbsPerEyeResolutionPendingView);
        updateRawSbsPendingView(model);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dimen(R.dimen.xr_space_md);
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
        modeFpsLadder = null;
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
        if (model == null || modeResolutionSelector == null || modeFpsLadder == null
                || modeBitrateControl == null) {
            renderModeOptions();
            return;
        }

        modeResolutionSelector.setSelectedResolutionId(model.pendingQuality.resolution);
        modeResolutionSelector.setEnabled(sessionControlsEnabled);
        SessionSettingsModel.Value fps = model.get(SessionSettingsModel.Key.FRAME_RATE);
        String fpsId = qualityChoiceId(fps, model.pendingQuality.frameRate);
        if (!modeFpsLadder.setSelectedChoiceId(fpsId)) {
            configureFpsLadder(mode, fps, model);
        }
        modeFpsLadder.setEnabled(sessionControlsEnabled);
        SessionSettingsModel.Value bitrate = model.get(SessionSettingsModel.Key.BITRATE);
        String bitrateId = qualityChoiceId(bitrate,
                String.valueOf(model.pendingQuality.bitrateKbps));
        // Keep bitrate independent from resolution/fps. Rebuild the choice model so the slider
        // remains in sync with the latest pending value and any out-of-preset entry.
        int recommendedKbps = recommendedBitrateKbps(mode, model);
        modeBitrateControl.setChoices(choicesOrCurrent(bitrate, bitrateId), bitrateId,
                recommendedKbps, bitrateHintFor(recommendedKbps), choiceId ->
                        controlActionListener.onModeQualitySettingSelected(mode,
                                SessionSettingsModel.Key.BITRATE, choiceId,
                                modeStreamQualityModels.get(mode)));
        modeBitrateControl.setEnabled(sessionControlsEnabled);
        modeQualityCueView.setText(modeQualityCue(model));
        modeQualityCueView.setTextColor(model.requiresReconnect()
                ? paletteColor(R.color.xr_status_warn) : paletteColor(R.color.xr_text_secondary));
        modeDefaultsButton.setEnabled(sessionControlsEnabled);
        modeApplyButton.setText(applyButtonLabel());
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
                ? paletteColor(R.color.xr_danger)
                : paletteColor(R.color.xr_text_primary);
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
                R.dimen.xr_text_display, paletteColor(R.color.xr_accent));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(header);

        String pcName = activity.getIntent().getStringExtra(Game.EXTRA_PC_NAME);
        String appName = activity.getIntent().getStringExtra(Game.EXTRA_APP_NAME);
        if ((pcName != null && !pcName.isEmpty()) || (appName != null && !appName.isEmpty())) {
            String identity = pcName != null && !pcName.isEmpty() ? pcName
                    : activity.getString(R.string.xr_session_current_pc);
            if (appName != null && !appName.isEmpty()) {
                identity += " \u00b7 " + appName;
            }
            root.addView(controlText(identity, R.dimen.xr_text_title,
                    paletteColor(R.color.xr_text_primary)));
        }

        TextView summary = controlText(
                activity.getString(R.string.xr_session_settings_summary),
                SESSION_SUMMARY_TEXT_DIMEN, paletteColor(R.color.xr_text_secondary));
        summary.setPadding(0, dimen(R.dimen.xr_space_sm), 0,
                dimen(R.dimen.xr_space_md));
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
        videoParams.rightMargin = dimen(R.dimen.xr_space_sm);
        rows.addView(videoColumn, videoParams);
        LinearLayout.LayoutParams deliveryParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        deliveryParams.leftMargin = dimen(R.dimen.xr_space_sm);
        rows.addView(deliveryColumn, deliveryParams);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Both actions sit in one footer, matching the mode pane. "Use global defaults" used to
        // live in the header, where it competed with the title and left the two buttons in
        // opposite corners of the pane; a reset and its apply belong side by side, reset first so
        // the primary action stays where the eye finishes.
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setPadding(0, dimen(R.dimen.xr_space_md), 0, 0);

        sessionDefaultsButton = compactButton(
                activity.getString(R.string.xr_session_use_global));
        sessionDefaultsButton.setEnabled(sessionControlsEnabled);
        sessionDefaultsButton.setOnClickListener(v -> controlActionListener
                .onUseGlobalDefaultsRequested(sessionSettingsModel));
        footer.addView(sessionDefaultsButton);

        sessionApplyButton = compactButton(applyButtonLabel());
        sessionApplyButton.setBackgroundResource(R.drawable.xr_home_primary_action_background);
        sessionApplyButton.setEnabled(sessionControlsEnabled && reconnectPending);
        sessionApplyButton.setOnClickListener(v -> controlActionListener
                .onApplyAndReconnectRequested(sessionSettingsModel));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        applyParams.leftMargin = dimen(R.dimen.xr_space_md);
        footer.addView(sessionApplyButton, applyParams);
        root.addView(footer);
        return root;
    }

    private LinearLayout sessionSettingsColumn(String label) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView heading = controlText(label, SESSION_GROUP_TEXT_DIMEN,
                paletteColor(R.color.xr_accent));
        heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        heading.setPadding(dimen(R.dimen.xr_space_xs), 0, 0,
                dimen(R.dimen.xr_space_sm));
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
        int rowPadding = dimen(R.dimen.xr_space_lg);
        row.setPadding(rowPadding, rowPadding, rowPadding, rowPadding);
        row.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_panel), 1));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = controlText(sessionSettingLabel(key),
                SESSION_ROW_TITLE_TEXT_DIMEN, paletteColor(R.color.xr_text_primary));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        applyTitleIcon(title, sessionSettingIconRes(key));
        heading.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        String source = value.source == SessionSettingsModel.Source.GLOBAL
                ? activity.getString(R.string.xr_setting_source_global)
                : activity.getString(R.string.xr_setting_source_session);
        TextView sourceView = controlText(source, SESSION_META_TEXT_DIMEN,
                paletteColor(R.color.xr_text_secondary));
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
        choiceParams.topMargin = dimen(R.dimen.xr_space_sm);
        row.addView(choices, choiceParams);

        TextView pending = controlText("", SESSION_META_TEXT_DIMEN,
                paletteColor(R.color.xr_text_secondary));
        pending.setPadding(0, dimen(R.dimen.xr_space_xs), 0, 0);
        sessionPendingViews.put(key, pending);
        row.addView(pending);
        updateSessionPendingView(pending, value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dimen(R.dimen.xr_space_md);
        row.setLayoutParams(lp);
        return row;
    }

    private View buildSessionBitrateSettingRow(SessionSettingsModel.Key key,
                                               SessionSettingsModel.Value value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int rowPadding = dimen(R.dimen.xr_space_lg);
        row.setPadding(rowPadding, rowPadding, rowPadding, rowPadding);
        row.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_panel), 1));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = controlText(sessionSettingLabel(key),
                SESSION_ROW_TITLE_TEXT_DIMEN, paletteColor(R.color.xr_text_primary));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        applyTitleIcon(title, sessionSettingIconRes(key));
        heading.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        String source = value.source == SessionSettingsModel.Source.GLOBAL
                ? activity.getString(R.string.xr_setting_source_global)
                : activity.getString(R.string.xr_setting_source_session);
        TextView sourceView = controlText(source, SESSION_META_TEXT_DIMEN,
                paletteColor(R.color.xr_text_secondary));
        sessionSourceViews.put(key, sourceView);
        heading.addView(sourceView);
        row.addView(heading);

        // Unlike the other rows in this pane, bitrate is stored per presentation mode.
        TextView scopeNote = controlText(
                activity.getString(R.string.xr_session_bitrate_scope),
                SESSION_META_TEXT_DIMEN, paletteColor(R.color.xr_text_secondary));
        int compactSpacing = dimen(R.dimen.xr_space_xs);
        scopeNote.setPadding(0, compactSpacing, 0, compactSpacing);
        row.addView(scopeNote);

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

        TextView pending = controlText("", SESSION_META_TEXT_DIMEN,
                paletteColor(R.color.xr_text_secondary));
        pending.setPadding(0, compactSpacing, 0, 0);
        sessionPendingViews.put(key, pending);
        row.addView(pending);
        updateSessionPendingView(pending, value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dimen(R.dimen.xr_space_md);
        row.setLayoutParams(lp);
        return row;
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
        sessionApplyButton.setText(applyButtonLabel());
        sessionApplyButton.setEnabled(sessionControlsEnabled && reconnectPending);
    }

    /** Three-state Apply label: nothing pending / apply live / apply &amp; reconnect. */
    private String applyButtonLabel() {
        if (!reconnectPending) {
            return activity.getString(R.string.xr_session_no_reconnect_changes);
        }
        return applyRequiresReconnect
                ? activity.getString(R.string.xr_session_apply_reconnect)
                : activity.getString(R.string.xr_session_apply_live);
    }

    private LinearLayout panelColumn() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(paletteColor(R.color.xr_surface_sunken));
        int padding = dimen(R.dimen.xr_space_xl);
        root.setPadding(padding, padding, padding, padding);
        return root;
    }

    private LinearLayout labeledValue(String label, String value, int valueColor) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = controlText(label, R.dimen.xr_text_emphasis,
                paletteColor(R.color.xr_text_secondary));
        TextView valueView = controlText(value, R.dimen.xr_text_title, valueColor);
        column.addView(labelView);
        column.addView(valueView);
        return column;
    }

    private TextView controlText(CharSequence text, int textSizeResource, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        setTextSize(view, textSizeResource);
        view.setTextColor(color);
        return view;
    }

    private Button compactButton(CharSequence text) {
        Button button = new Button(activity);
        styleControlButton(button);
        button.setText(text);
        setTextSize(button, R.dimen.xr_text_title);
        button.setAllCaps(false);
        button.setMinHeight(activity.getResources()
                .getDimensionPixelSize(R.dimen.xr_control_primary));
        button.setFocusable(true);
        return button;
    }

    private void styleControlButton(Button button) {
        button.setBackgroundResource(R.drawable.xr_home_action_background);
        button.setBackgroundTintList(null);
        button.setTextColor(paletteColor(R.color.xr_text_primary));
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

    /**
     * Live frame rate, naming the selected ceiling whenever the stream is running below it.
     *
     * <p>The picker sets a maximum, not a fixed rate: when the headset slows its display the stream
     * follows it down. Without this the glance would read "72 FPS" against a picker showing 90 and
     * look like a bug rather than the intended behaviour.</p>
     */
    private String glanceFrameRateText(String liveFrameRate) {
        int ceiling = panelRefreshRateState.getUserCeilingHz();
        int live = Math.round(parseFrameRate(liveFrameRate, prefConfig.fps));
        if (ceiling <= 0 || live <= 0 || live >= ceiling) {
            return liveFrameRate;
        }
        return activity.getString(R.string.xr_glance_frame_rate_capped, liveFrameRate, ceiling);
    }

    static String formatGlanceStream(Activity activity, String resolution, String frameRate,
                                     int bitrateKbps, String dynamicRange) {
        return activity.getString(R.string.xr_glance_stream,
                resolution.replace("x", " \u00d7 "), frameRate, bitrateKbps / 1000,
                dynamicRange);
    }

    /**
     * Bitrate rung suited to this mode's pending resolution, frame rate and codec, or -1 when the
     * codec cannot reach that shape at any offered rung.
     */
    private int recommendedBitrateKbps(PresenterMode mode, ModeStreamQualityModel model) {
        if (model == null || model.pendingQuality == null) {
            return -1;
        }
        int[] size = parseResolutionSize(model.pendingQuality.resolution);
        if (size == null) {
            return -1;
        }
        int fps = Math.round(parseFrameRate(model.pendingQuality.frameRate, prefConfig.fps));
        boolean packed = usesPackedBitrateCost(
                mode, rawSbsModeSettingsModel, prefConfig.rawSbsPerEyeResolution);
        return XrBitrateRecommendation.recommendedKbps(
                packed, size[0], size[1], fps, codecIdFor(prefConfig.videoFormat));
    }

    private static String codecIdFor(PreferenceConfiguration.FormatOption format) {
        if (format == PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return XrBitrateRecommendation.CODEC_AV1;
        }
        if (format == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return XrBitrateRecommendation.CODEC_H264;
        }
        if (format == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
            return XrBitrateRecommendation.CODEC_HEVC;
        }
        return XrBitrateRecommendation.CODEC_AUTO;
    }

    /** Frame rate is a ceiling too: panel-follow may run the stream below the chosen rung. */
    private void configureFpsLadder(PresenterMode mode, SessionSettingsModel.Value fps,
                                    ModeStreamQualityModel model) {
        // A mode carrying a custom rate reports no choices at all; synthesize the current one so
        // the ladder still renders a single segment rather than refusing to build.
        String fpsId = qualityChoiceId(fps, model.pendingQuality.frameRate);
        modeFpsLadder.setChoices(
                choicesOrCurrent(fps, fpsId),
                fpsId,
                null, null, null,
                choiceId -> controlActionListener.onModeQualitySettingSelected(mode,
                        SessionSettingsModel.Key.FRAME_RATE, choiceId,
                        modeStreamQualityModels.get(mode)));
    }

    private CharSequence bitrateHintFor(int recommendedKbps) {
        return recommendedKbps > 0
                ? activity.getString(R.string.xr_bitrate_recommended)
                : activity.getString(R.string.xr_bitrate_codec_too_slow);
    }

    /** Applies a row-title icon at the inline size every icon on this pane shares. */
    private void applyTitleIcon(TextView title, int iconRes) {
        if (iconRes == 0) {
            return;
        }
        android.graphics.drawable.Drawable icon =
                ContextCompat.getDrawable(activity, iconRes);
        if (icon == null) {
            return;
        }
        int size = activity.getResources().getDimensionPixelSize(R.dimen.xr_icon_inline);
        icon.setBounds(0, 0, size, size);
        title.setCompoundDrawablesRelative(icon, null, null, null);
        title.setCompoundDrawablePadding(dimen(R.dimen.xr_space_sm));
    }

    /** Icon shown beside a settings row title, or 0 where the row has no icon of its own. */
    private int sessionSettingIconRes(SessionSettingsModel.Key key) {
        switch (key) {
            case RESOLUTION:
                return R.drawable.ic_xr_resolution;
            case FRAME_RATE:
                return R.drawable.ic_xr_frame_rate;
            case BITRATE:
                return R.drawable.ic_xr_bitrate;
            case CODEC:
                return R.drawable.ic_xr_codec;
            case HDR:
                return R.drawable.ic_xr_hdr;
            case VIDEO_RANGE:
                return R.drawable.ic_xr_video_range;
            case FRAME_PACING:
                return R.drawable.ic_xr_frame_pacing;
            case AUDIO_LAYOUT:
                return R.drawable.ic_xr_audio;
            case PLAY_AUDIO_ON_PC:
                return R.drawable.ic_xr_audio_host;
            default:
                return 0;
        }
    }

    private String sessionSettingLabel(SessionSettingsModel.Key key) {
        switch (key) {
            case RESOLUTION:
                return activity.getString(R.string.title_resolution_list);
            case FRAME_RATE:
                return activity.getString(R.string.title_fps_ceiling);
            case BITRATE:
                return activity.getString(R.string.title_bitrate_ceiling);
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
        // Column 0 holds the metric name and is deliberately NOT shrinkable. When it was, the
        // longest names wrapped onto a second line and the whole pane gained a ragged rhythm for
        // the sake of a few characters. Sizing the column to its widest label costs horizontal
        // space once; wrapping costs vertical space on every affected row.
        table.setColumnShrinkable(0, false);
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
        root.setBackgroundColor(paletteColor(R.color.xr_scrim_strong));
        int p = dimen(R.dimen.xr_space_md);
        root.setPadding(p, p, p, p);

        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(28), dp(28));
        sp.setMargins(0, 0, dimen(R.dimen.xr_space_md), 0);
        root.addView(spinner, sp);

        depthStatusText = new TextView(activity);
        depthStatusText.setTextColor(paletteColor(R.color.xr_text_primary));
        setTextSize(depthStatusText, R.dimen.xr_text_title);
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
        root.setBackgroundColor(paletteColor(R.color.xr_scrim_strong));
        int padding = dimen(R.dimen.xr_space_md);
        root.setPadding(padding, padding, padding, padding);

        transientMessageText = new TextView(activity);
        transientMessageText.setTextColor(paletteColor(R.color.xr_text_primary));
        setTextSize(transientMessageText, R.dimen.xr_text_title);
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

    private void resetHostDepthStatus() {
        depthStatusHandler.removeCallbacks(showDepthStatusRunnable);
        depthStatusPendingPhase = 0;
        depthStatusPhase = 0;
        if (depthStatusPanel != null && !depthStatusPanel.isDisposed()) {
            depthStatusPanel.setEnabled(false);
        }
        updateHostDebugDumpAvailability();
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
        // Preserve an early ready/failure push even if the panel hierarchy has not been created
        // yet. Dump 3D requires an affirmative phase-2 ownership signal, not merely "not busy".
        depthStatusPhase = phase;
        if (depthStatusPanel == null) {
            return;
        }
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

    /** Main-thread delivery of an already-parsed immutable host telemetry body. */
    public void onHostSbsTelemetryState(
            HostSbsTelemetrySnapshot snapshot, long receivedAtMs) {
        if (!hostSbsTelemetryTracker.accept(snapshot, receivedAtMs)) {
            LimeLog.info("XR: dropping stale or unowned host SBS telemetry"
                    + (snapshot != null ? " request=" + snapshot.requestId
                    + " generation=" + snapshot.generation
                    + " sequence=" + snapshot.sequence : ""));
        }
    }

    private int nextHostSbsTelemetryRequestId() {
        hostSbsTelemetryRequestCounter =
                (hostSbsTelemetryRequestCounter % 0xFFFF) + 1;
        return hostSbsTelemetryRequestCounter;
    }

    private boolean controlTransportOpen() {
        return !controlTransportClosing && !presenterDestroyed;
    }

    /** All ordinary control sends pass through these guards. The sole exception is the final
     * telemetry unsubscribe inside {@link #onConnectionStopping()}, while Game still owns a live
     * native connection. Package visibility keeps the transport boundary directly testable. */
    int sendHostSbsModeControl(int mode) {
        return controlTransportOpen() && hostControlExtensionsSupported
                ? MoonBridge.sendSetSbsMode(mode) : 0;
    }

    int sendHostVideoModeControl(int logicalWidth, int logicalHeight, int framerateX100,
                                 int requestId, int bitrateKbps) {
        if (!controlTransportOpen() || !hostControlExtensionsSupported) {
            return 0;
        }
        int[] wireDimensions = liveVideoModeWireDimensions(
                currentPresenterMode, logicalWidth, logicalHeight,
                prefConfig.rawSbsPerEyeResolution);
        if (wireDimensions == null) {
            LimeLog.severe("XR: refusing invalid live video-mode geometry "
                    + logicalWidth + "x" + logicalHeight + " for " + currentPresenterMode);
            return 0;
        }
        return MoonBridge.sendSetVideoMode(
                wireDimensions[0], wireDimensions[1], framerateX100,
                requestId, bitrateKbps);
    }

    int sendHostTelemetryControl(boolean enabled, boolean focused,
                                 int requestId, int intervalMs) {
        return controlTransportOpen() && hostControlExtensionsSupported
                ? MoonBridge.sendHostSbsTelemetrySubscription(
                        enabled, focused, requestId, intervalMs)
                : 0;
    }

    boolean sendHostDebugDumpControl() {
        if (!controlTransportOpen() || !hostControlExtensionsSupported) {
            return false;
        }
        MoonBridge.sendSbsDebugDump();
        return true;
    }

    /**
     * Owns the host subscription strictly while Host SBS AI is the active, proven stream mode.
     * Opening Stats changes the host publication cadence. Each distinct publication advances chart
     * history on delivery; the slower stats refresh only repaints the accumulated history.
     */
    private void reconcileHostSbsTelemetrySubscription() {
        boolean enable = controlTransportOpen()
                && hostControlExtensionsSupported
                && streamPresentationReady
                && currentPresenterMode == PresenterMode.HOST_SBS_AI;
        boolean focused = enable && statsVisible;
        if (enable == hostSbsTelemetryRequested
                && (!enable || focused == hostSbsTelemetryFocused)) {
            return;
        }

        cancelHostSbsTelemetryRetry(true);
        if (!enable) {
            if (hostSbsTelemetryRequested) {
                sendHostTelemetryControl(
                        false, false, nextHostSbsTelemetryRequestId(),
                        HOST_SBS_TELEMETRY_BACKGROUND_INTERVAL_MS);
            }
            hostSbsTelemetryRequested = false;
            hostSbsTelemetryFocused = false;
            hostSbsTelemetryTracker.deactivate();
            return;
        }

        hostSbsTelemetryRequested = true;
        hostSbsTelemetryFocused = focused;
        sendHostSbsTelemetrySubscriptionAttempt();
    }

    private void sendHostSbsTelemetrySubscriptionAttempt() {
        if (!controlTransportOpen()) {
            return;
        }
        int requestId = nextHostSbsTelemetryRequestId();
        hostSbsTelemetryTracker.activateRequest(requestId);
        int intervalMs = hostSbsTelemetryFocused
                ? HOST_SBS_TELEMETRY_FOCUSED_INTERVAL_MS
                : HOST_SBS_TELEMETRY_BACKGROUND_INTERVAL_MS;
        int result = sendHostTelemetryControl(
                true, hostSbsTelemetryFocused, requestId, intervalMs);
        if (result < 0) {
            cancelHostSbsTelemetryRetry(false);
            hostSbsTelemetryTracker.markSubscriptionUnavailable(
                    requestId, SbsDepthTelemetrySnapshot.Availability.UNSUPPORTED);
            LimeLog.info("XR: host SBS telemetry is unsupported by this host");
        } else if (result == 0) {
            hostSbsTelemetryTracker.markSubscriptionUnavailable(
                    requestId, SbsDepthTelemetrySnapshot.Availability.FAILED);
            LimeLog.warning("XR: host SBS telemetry subscription could not be queued");
            scheduleHostSbsTelemetryRetry();
        } else {
            cancelHostSbsTelemetryRetry(true);
            LimeLog.info("XR: host SBS telemetry subscribed at " + intervalMs
                    + " ms (request " + requestId + ")");
        }
    }

    private void scheduleHostSbsTelemetryRetry() {
        if (hostSbsTelemetryRetryPending
                || hostSbsTelemetryRetryAttempts >= HOST_SBS_TELEMETRY_MAX_RETRIES) {
            if (hostSbsTelemetryRetryAttempts >= HOST_SBS_TELEMETRY_MAX_RETRIES) {
                LimeLog.warning("XR: host SBS telemetry subscription retry limit reached");
            }
            return;
        }
        hostSbsTelemetryRetryPending = true;
        hostSbsTelemetryRetryHandler.postDelayed(
                hostSbsTelemetryRetryRunnable, HOST_SBS_TELEMETRY_RETRY_DELAY_MS);
    }

    private void cancelHostSbsTelemetryRetry(boolean resetAttempts) {
        hostSbsTelemetryRetryHandler.removeCallbacks(hostSbsTelemetryRetryRunnable);
        hostSbsTelemetryRetryPending = false;
        if (resetAttempts) {
            hostSbsTelemetryRetryAttempts = 0;
        }
    }

    private boolean clearHostSbsTelemetrySubscriptionState() {
        cancelHostSbsTelemetryRetry(true);
        boolean wasRequested = hostSbsTelemetryRequested;
        hostSbsTelemetryRequested = false;
        hostSbsTelemetryFocused = false;
        hostSbsTelemetryTracker.deactivate();
        return wasRequested;
    }

    /**
     * Ends the host telemetry subscription while moonlight-common's control transport is still
     * alive. {@link Game} calls this synchronously before {@code NvConnection.stop()} starts.
     * Repeated calls are local no-ops and delayed UI/retry work cannot reopen the subscription.
     */
    public void onConnectionStopping() {
        if (controlTransportClosing || presenterDestroyed) {
            return;
        }
        // Fence every queued and user-origin control path before the native stop thread can begin.
        controlTransportClosing = true;
        sessionControlsEnabled = false;
        streamPresentationReady = false;
        liveQualityHandler.removeCallbacks(liveQualityAckTimeoutRunnable);
        liveQualityHandler.removeCallbacks(panelRateReconcileRunnable);
        panelRateReconcilePosted = false;
        liveQualityChangeInProgress = false;
        liveQualityConfirmations.clear();
        pendingVideoModeRequestId = -1;
        pendingLiveQuality = null;
        previousLiveQuality = null;
        acknowledgedLiveQuality = null;
        pendingLiveQualityMode = null;
        pendingLiveQualityOrigin = null;
        pendingDurableUserQuality = null;
        pendingDecoderTransitionMode = null;
        clientSbsHdrTransitionInProgress = false;
        modeSwitchInProgress = false;
        decoderTransitionGenerations.clear();
        updateHostDebugDumpAvailability();

        boolean wasTelemetryRequested = clearHostSbsTelemetrySubscriptionState();
        if (wasTelemetryRequested) {
            // This is deliberately the only send allowed after the fence closes. Game invokes the
            // hook synchronously before it starts NvConnection.stop(), so the mutex is still live.
            MoonBridge.sendHostSbsTelemetrySubscription(
                    false, false, nextHostSbsTelemetryRequestId(),
                    HOST_SBS_TELEMETRY_BACKGROUND_INTERVAL_MS);
        }
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
        final SbsDepthTelemetrySnapshot depthTelemetry;
        if (currentPresenterMode == PresenterMode.CLIENT_SBS_AI && clientSbsStatsActive) {
            // Client SBS remains authoritative from its local GPU readback.
            depthTelemetry = clientSbs.depthTelemetry;
        } else if (currentPresenterMode == PresenterMode.HOST_SBS_AI) {
            // Host histories already include every distinct accepted publication. This slower
            // stats tick only takes a coherent view for table/layout work.
            depthTelemetry = hostSbsTelemetryTracker.sampleAtStatsTick(
                    android.os.SystemClock.uptimeMillis());
        } else {
            depthTelemetry = null;
        }
        final boolean clientSbsLoggingActive = prefConfig.enablePerfLogging
                && clientSbsStatsActive && clientSbs.backend.startsWith("LITERT_");
        if (!panelVisible && !prefConfig.enablePerfLogging) {
            return;
        }

        DevicePerformanceSampler.Snapshot device = (panelVisible || clientSbsLoggingActive)
                ? devicePerformanceSampler.sample() : null;
        if (clientSbsLoggingActive) {
            String depthHealth;
            if (!clientSbs.depthHealthAvailable) {
                depthHealth = clientSbs.depthHealthReadbackFailed
                        ? "readback_failed_retrying" : "unavailable";
            } else {
                String classifiedEdge = clientSbs.adaptivePopClassified
                        ? String.format(Locale.US, "%.4f", clientSbs.depthEdgeFraction)
                        : "unsettled";
                depthHealth = String.format(Locale.US,
                        "valid=%.1f%% range=%.4f edge=%s pop=%.3f change=%.3f age=%d"
                                + " cuts=%d armed=%s ext=%d anchor=%.1fpx subject=%.3f"
                                + " faults=%d/%d profile=%s"
                                + " collapsed=%s",
                        clientSbs.validDepthFraction * 100.0f,
                        clientSbs.effectiveDepthRangeWidth,
                        classifiedEdge,
                        clientSbs.stereoPopStrength,
                        clientSbs.depthChangeFraction,
                        clientSbs.depthSceneAge,
                        clientSbs.depthHardCutCount,
                        clientSbs.depthCutArmed,
                        clientSbs.depthExternalCutRequests,
                        clientSbs.depthZeroAnchorShift,
                        clientSbs.depthSubjectDepth,
                        clientSbs.depthEmptyRawFrames,
                        clientSbs.depthCollapsedRawFrames,
                        clientSbs.stereoProfileInitialized,
                        clientSbs.rawDepthRangeCollapsed);
            }
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
                    hdrActive ? paletteColor(R.color.xr_status_ok) : paletteColor(R.color.xr_text_primary));
            addStatsRow("Codec", stream.getCodecDescription(), paletteColor(R.color.xr_text_primary));
            addStatsRow("Decoder component", stream.getDecoderName(), paletteColor(R.color.xr_text_primary));
            addStatsRow("Decoder latency",
                    formatDecoderLatencyMode(stream.isDedicatedLowLatencyDecoder(),
                            stream.isDecoderLowLatencyRequested()),
                    stream.isDedicatedLowLatencyDecoder()
                                    || stream.isDecoderLowLatencyRequested()
                            ? paletteColor(R.color.xr_status_ok) : paletteColor(R.color.xr_text_primary));
            addStatsRow("Output pacing", stream.getOutputPacingDescription(),
                    paletteColor(R.color.xr_text_primary));
        } else {
            addStatsRow("Video", "Waiting for decoder sample", paletteColor(R.color.xr_text_disabled));
        }

        if (stream != null) {
            addStatsRow("FPS sender / receive",
                    String.format(Locale.US, "%.1f / %.1f",
                            stream.getStreamSequenceFps(), stream.getReceivedFps()),
                    paletteColor(R.color.xr_text_primary));
            String presentedFps = Float.isFinite(stream.getDecoderPresentedFps())
                    ? String.format(Locale.US, "%.1f", stream.getDecoderPresentedFps()) : "n/a";
            addStatsRow("Decoder output / release / surface",
                    String.format(Locale.US, "%.1f / %.1f / %s",
                            stream.getDecoderOutputFps(), stream.getDecoderReleaseFps(),
                            presentedFps),
                    Float.isFinite(stream.getDecoderPresentedFps())
                            ? paletteColor(R.color.xr_text_primary) : paletteColor(R.color.xr_text_disabled));

            String bandwidth = stream.hasBandwidth()
                    ? String.format(Locale.US, "%.1f Mbps", stream.getBandwidthMbps()) : "n/a";
            String rtt = stream.hasEstimatedRtt()
                    ? String.format(Locale.US, "%d ms", stream.getEstimatedRttMs())
                    : "n/a";
            addStatsRow("Network",
                    String.format(Locale.US, "%s | loss %.2f%% | RTT %s",
                            bandwidth, stream.getNetworkLossPercent(), rtt),
                    stream.getNetworkLossPercent() > 1.0f
                            ? paletteColor(R.color.xr_status_warn) : paletteColor(R.color.xr_text_primary));

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
                    stream.hasDecodeLatency() ? paletteColor(R.color.xr_text_primary) : paletteColor(R.color.xr_text_disabled));
        }

        addStatsSection("DEVICE");
        if (device.appCpuAvailable) {
            addStatsRow("App CPU",
                    String.format(Locale.US, "%.2f cores", device.appCpuCoreEquivalent),
                    paletteColor(R.color.xr_text_primary));
        } else {
            addStatsRow("App CPU", "Warming up", paletteColor(R.color.xr_text_disabled));
        }

        String gpuBusy = device.deviceGpuUtilizationAvailable
                ? String.format(Locale.US, "%.1f%%", device.deviceGpuUtilizationPercent) : "n/a";
        String gpuClock = device.gpuFrequencyAvailable
                ? String.format(Locale.US, "%.0f MHz", device.gpuFrequencyHz / 1_000_000.0)
                : "n/a";
        addStatsRow("Device GPU total / clock", gpuBusy + " | " + gpuClock,
                device.deviceGpuUtilizationAvailable
                        ? utilizationColor(device.deviceGpuUtilizationPercent)
                        : paletteColor(R.color.xr_text_disabled));
        if (clientSbsStatsActive) {
            addStatsRow("Thermal", thermalStatusName(clientSbs.thermalStatus),
                    thermalStatusColor(clientSbs.thermalStatus));
        }

        if (currentPresenterMode == PresenterMode.CLIENT_SBS_AI) {
            addStatsSection("CLIENT SBS");
            if (!clientSbsStatsActive) {
                addStatsRow("Depth pipeline", "Initializing", paletteColor(R.color.xr_text_disabled));
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
                        paletteColor(R.color.xr_text_primary));
                addStatsRow("Depth inference call avg / max",
                        String.format(Locale.US, "%.2f / %.2f ms | OpenCL + sync",
                                clientSbs.averageNativeLiteRtRunWallMs,
                                clientSbs.maxNativeLiteRtRunWallMs),
                        paletteColor(R.color.xr_text_primary));
                addStatsRow("Depth age avg / max",
                        String.format(Locale.US, "%.2f / %.2f ms",
                                clientSbs.averageDepthResultAgeMs,
                                clientSbs.maxDepthResultAgeMs),
                        paletteColor(R.color.xr_text_primary));

                addClientGpuStageRows(clientSbs.gpuTimersAvailable,
                        new float[] {
                                clientSbs.averageGpuModelInputMs,
                                clientSbs.averageGpuMatchedColorMs,
                                clientSbs.averageGpuDepthProfileMs,
                                clientSbs.averageGpuSbsComposeMs
                        },
                        new long[] {
                                clientSbs.gpuModelInputSamples,
                                clientSbs.gpuMatchedColorSamples,
                                clientSbs.gpuDepthProfileSamples,
                                clientSbs.gpuSbsComposeSamples
                        });

                long faults = clientSbs.colorSlotBusySkips + clientSbs.flatSbsOutputs;
                if (faults > 0L) {
                    addStatsRow("Faults",
                            String.format(Locale.US, "color busy %d | flat %d",
                                    clientSbs.colorSlotBusySkips,
                                    clientSbs.flatSbsOutputs),
                            paletteColor(R.color.xr_status_warn));
                }

                addDepthTelemetryRows(depthTelemetry, false);
            }
        }

        if (currentPresenterMode == PresenterMode.HOST_SBS_AI) {
            addStatsSection("HOST SBS");
            addStatsRow("Depth telemetry",
                    formatHostSbsTelemetryStatus(depthTelemetry),
                    depthTelemetry != null && depthTelemetry.isAvailable()
                            ? paletteColor(R.color.xr_status_ok)
                            : telemetryUnavailableColor(depthTelemetry));
            if (depthTelemetry != null && depthTelemetry.isAvailable()) {
                addDepthTelemetryRows(depthTelemetry, true);
            }
        }

        finishStatsRows();
    }

    private static final String[] CLIENT_GPU_STAGE_LABELS = {
            "Model input GL GPU",
            "Matched color GL GPU",
            "Depth profile GL GPU",
            "Stereo render GL GPU"
    };
    private static final String[] CLIENT_GPU_STAGE_DETAILS = {
            "resize + pack + color cut",
            "full-size capture",
            "filter + adaptive profile",
            "prefilter + warp + draw"
    };

    /**
     * Render all GPU stages from parallel, fixed-order arrays. Keeping this boundary independently
     * testable prevents a newly instrumented stage from being logged but omitted from the panel.
     */
    private void addClientGpuStageRows(boolean timersAvailable,
                                       float[] averageMs, long[] samples) {
        if (!timersAvailable) {
            addStatsRow("Client GL GPU stages", "Timer queries unavailable",
                    paletteColor(R.color.xr_text_disabled));
            return;
        }
        if (averageMs == null || samples == null
                || averageMs.length != CLIENT_GPU_STAGE_LABELS.length
                || samples.length != CLIENT_GPU_STAGE_LABELS.length) {
            throw new IllegalArgumentException("Client GPU stage telemetry is incomplete");
        }
        for (int i = 0; i < CLIENT_GPU_STAGE_LABELS.length; i++) {
            addStatsRow(CLIENT_GPU_STAGE_LABELS[i],
                    formatGpuStage(averageMs[i], samples[i], CLIENT_GPU_STAGE_DETAILS[i]),
                    gpuStageColor(samples[i]));
        }
    }

    private void addDepthTelemetryRows(
            SbsDepthTelemetrySnapshot telemetry, boolean hostSource) {
        if (telemetry == null || !telemetry.isAvailable()) {
            String status;
            if (telemetry == null
                    || telemetry.availability == SbsDepthTelemetrySnapshot.Availability.WAITING) {
                status = formatDepthHealthUnavailable(false);
            } else if (telemetry.availability
                    == SbsDepthTelemetrySnapshot.Availability.READBACK_FAILED) {
                status = formatDepthHealthUnavailable(true);
            } else {
                status = telemetry.availability.description;
            }
            addStatsRow("Depth health", status, telemetryUnavailableColor(telemetry));
            return;
        }

        String valid = telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_DEPTH_FRACTION)
                ? String.format(Locale.US, "%.1f%%", telemetry.validDepthFraction * 100.0f)
                : "n/a";
        String range = telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_RANGE)
                ? String.format(Locale.US, "%.4f", telemetry.effectiveRangeWidth)
                : "n/a";
        String pop;
        if (!telemetry.isInitialized()) {
            pop = "no profile";
        } else if (telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_EFFECTIVE)
                && Float.isFinite(telemetry.effectivePop)) {
            // effectivePop is absolute for both sources. Host values must not be normalized again.
            pop = String.format(Locale.US, "%.3f", telemetry.effectivePop);
        } else {
            pop = "n/a";
        }
        String subject = telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_SUBJECT)
                ? String.format(Locale.US, "%.3f", telemetry.subjectDepth)
                : "n/a";
        float popFloor = telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_CONFIG)
                && Float.isFinite(telemetry.popFloor) ? telemetry.popFloor : Float.NaN;
        float popCeiling = telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_CONFIG)
                && Float.isFinite(telemetry.popCeiling) ? telemetry.popCeiling : Float.NaN;
        addTrendStatsRow("Pop strength",
                "valid " + valid + " | range " + range + " | pop " + pop
                        + " | subject " + subject + " | collapsed "
                        + (telemetry.isRangeCollapsed() ? "yes" : "no"),
                telemetry.isRangeCollapsed()
                        ? paletteColor(R.color.xr_status_warn)
                        : paletteColor(R.color.xr_status_ok),
                telemetry.popTrend, false, popFloor, popCeiling);

        boolean classified = telemetry.isAdaptivePopClassified();
        addTrendStatsRow("Edge fraction",
                classified
                        ? String.format(Locale.US, "%.4f",
                                telemetry.classifiedEdgeFraction)
                        : "unsettled",
                classified
                        ? paletteColor(R.color.xr_text_primary)
                        : paletteColor(R.color.xr_text_disabled),
                telemetry.edgeTrend, false, Float.NaN, Float.NaN);

        addTrendStatsRow("Changed-depth fraction",
                telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_CHANGE)
                        ? String.format(Locale.US, "%.4f", telemetry.changeFraction)
                        : "n/a",
                telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_CHANGE)
                        ? paletteColor(R.color.xr_text_primary)
                        : paletteColor(R.color.xr_text_disabled),
                telemetry.changeTrend, false, 0.0f, 1.0f);

        String cutStatus = "n/a";
        if (telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_CUTS)
                && telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_SCENE)) {
            int sceneAge = (int)Math.min(Integer.MAX_VALUE, telemetry.sceneAge);
            cutStatus = hostSource
                    ? formatHostSceneCutStatus(
                            telemetry.hardCutCount, sceneAge,
                            telemetry.isGeometryArmed(), telemetry.isAppearanceArmed(),
                            telemetry.externalCutRequests)
                    : formatSceneCutStatus(
                            telemetry.hardCutCount, sceneAge,
                            // Client currently reports one local geometry-armed bit explicitly.
                            telemetry.isGeometryArmed(),
                            telemetry.externalCutRequests);
        }
        addTrendStatsRow("Scene cuts", cutStatus,
                telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_CUTS)
                        ? paletteColor(R.color.xr_text_secondary)
                        : paletteColor(R.color.xr_text_disabled),
                telemetry.cutTrend, true, Float.NaN, Float.NaN);

        if (telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_FAULTS)
                && (telemetry.emptyDepthFrames > 0L
                || telemetry.collapsedDepthFrames > 0L)) {
            addStatsRow("Depth faults",
                    String.format(Locale.US, "empty %d | collapsed %d",
                            telemetry.emptyDepthFrames,
                            telemetry.collapsedDepthFrames),
                    paletteColor(R.color.xr_status_warn));
        }

        addTrendStatsRow("Zero-plane anchor shift",
                telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_ANCHOR)
                        ? String.format(Locale.US, "%.1f px",
                                telemetry.zeroAnchorShiftPx)
                        : "n/a",
                telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_ANCHOR)
                        ? paletteColor(R.color.xr_text_secondary)
                        : paletteColor(R.color.xr_text_disabled),
                telemetry.anchorTrend, false, Float.NaN, Float.NaN);
    }

    static String formatHostSbsTelemetryStatus(SbsDepthTelemetrySnapshot telemetry) {
        if (telemetry == null) {
            return "Inactive";
        }
        if (!telemetry.isAvailable()) {
            return telemetry.availability.description;
        }
        if (telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_CONFIG)) {
            return String.format(Locale.US, "Live | %dx%d | %s zero plane",
                    telemetry.depthWidth, telemetry.depthHeight,
                    zeroPlaneModeName(telemetry.zeroPlaneMode));
        }
        return "Live | configuration unavailable";
    }

    static String zeroPlaneModeName(int mode) {
        switch (mode) {
            case 1:
                return "subject";
            case 2:
                return "median";
            case 3:
                return "background";
            default:
                return "unknown";
        }
    }

    private int telemetryUnavailableColor(SbsDepthTelemetrySnapshot telemetry) {
        if (telemetry != null
                && (telemetry.availability == SbsDepthTelemetrySnapshot.Availability.FAILED
                || telemetry.availability == SbsDepthTelemetrySnapshot.Availability.STALE
                || telemetry.availability
                        == SbsDepthTelemetrySnapshot.Availability.READBACK_FAILED)) {
            return paletteColor(R.color.xr_status_warn);
        }
        return paletteColor(R.color.xr_text_disabled);
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

    static String formatSceneCutStatus(long totalCuts, int sceneAgeFrames,
                                       boolean cutArmed, long externalCutRequests) {
        return String.format(Locale.US,
                "%d total | scene age %d frames | geometry %s | external requests %d",
                totalCuts, sceneAgeFrames, cutArmed ? "armed" : "disarmed",
                externalCutRequests);
    }

    static String formatHostSceneCutStatus(
            long totalCuts, int sceneAgeFrames,
            boolean geometryArmed, boolean appearanceArmed,
            long externalCutRequests) {
        return String.format(Locale.US,
                "%d total | scene age %d frames | geometry %s | appearance %s"
                        + " | external requests %d",
                totalCuts, sceneAgeFrames,
                geometryArmed ? "armed" : "disarmed",
                appearanceArmed ? "armed" : "disarmed",
                externalCutRequests);
    }

    static String formatDepthHealthUnavailable(boolean readbackFailed) {
        return readbackFailed
                ? "Telemetry unavailable | retrying"
                : "Waiting for sample";
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
        return samples > 0L ? paletteColor(R.color.xr_text_primary) : paletteColor(R.color.xr_text_disabled);
    }

    private int utilizationColor(double percent) {
        if (percent >= 95.0) {
            return paletteColor(R.color.xr_danger);
        }
        if (percent >= 80.0) {
            return paletteColor(R.color.xr_status_warn);
        }
        return paletteColor(R.color.xr_status_ok);
    }

    private int backendColor(String backend) {
        if (isLiteRtOpenClGlBackend(backend)) {
            return paletteColor(R.color.xr_status_ok);
        }
        if ("Unavailable".equals(backend) || "Failed".equals(backend)) {
            return paletteColor(R.color.xr_status_warn);
        }
        if ("Inactive".equals(backend) || "Initializing".equals(backend)) {
            return paletteColor(R.color.xr_text_disabled);
        }
        return paletteColor(R.color.xr_text_primary);
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
    /**
     * Trend rows carry a third child. They need their own pool tag: reusing a plain metric row and
     * appending a sparkline each refresh stacked a new plot on the row every window.
     */
    private static final String STATS_ROW_TREND = "stats-trend";

    private void beginStatsRows() {
        reuseStatsRows = true;
        primaryStatsRowCursor = 0;
    }

    private void finishStatsRows() {
        trimStatsTable(statsTable, primaryStatsRowCursor);
        reuseStatsRows = false;
        scheduleStatsPanelFit();
    }

    /**
     * Grows the stats panel to whatever its rows need, up to a cap.
     *
     * <p>The panel was a fixed 1.05 m raster with the table inside a ScrollView, so any mode that
     * reports more rows than fit — Client SBS adds a whole depth-pipeline section — pushed the user
     * into scrolling a floating panel with a gaze cursor, which is awkward and hides the rows that
     * matter. The ScrollView stays as the fallback beyond {@link #STATS_MAX_HEIGHT_METERS}.</p>
     */
    private void scheduleStatsPanelFit() {
        if (statsPanel == null || statsPanel.isDisposed() || statsContentRoot == null) {
            return;
        }
        // Measure after this layout pass; row views added moments ago have no height yet.
        statsContentRoot.post(this::fitStatsPanelToContent);
    }

    private void fitStatsPanelToContent() {
        if (statsPanel == null || statsPanel.isDisposed() || statsContentRoot == null) {
            return;
        }
        statsContentRoot.measure(
                View.MeasureSpec.makeMeasureSpec(STATS_RASTER_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentHeightPx = statsContentRoot.getMeasuredHeight();
        if (contentHeightPx <= 0) {
            return;
        }
        int targetHeightPixels = calculateStatsRasterHeightPixels(
                contentHeightPx, STATS_RASTER_HEIGHT, STATS_MAX_RASTER_HEIGHT);
        // Scale from the ORIGINAL raster/metre pair rather than the live size, so repeated fits
        // cannot drift the panel a little larger every refresh. Deriving metres from the capped
        // pixel height keeps the Android layout and SceneCore surface at the same aspect.
        float targetHeightMeters = calculateModeOptionsHeightMeters(
                STATS_HEIGHT_METERS, STATS_RASTER_HEIGHT, targetHeightPixels,
                STATS_MIN_HEIGHT_METERS, STATS_MAX_HEIGHT_METERS);
        if (targetHeightPixels == statsRasterHeightPixels
                && Math.abs(targetHeightMeters - statsHeightMeters) < 0.0001f) {
            return;
        }
        statsRasterHeightPixels = targetHeightPixels;
        statsHeightMeters = targetHeightMeters;
        // setSize() alone changes only the physical quad. The hosted Android View remains at the
        // old raster size, clipping newly added rows. Resize both, then cap the ScrollView viewport.
        statsPanel.setSizeInPixels(new IntSize2d(
                STATS_RASTER_WIDTH, targetHeightPixels));
        statsPanel.setSize(new FloatSize2d(
                statsEntityLocalMeters(STATS_WIDTH_METERS, STATS_ENTITY_SCALE),
                statsEntityLocalMeters(targetHeightMeters, STATS_ENTITY_SCALE)));
    }

    static int calculateStatsRasterHeightPixels(int contentHeightPixels,
                                                int minHeightPixels,
                                                int maxHeightPixels) {
        int minHeight = Math.max(1, minHeightPixels);
        int maxHeight = Math.max(minHeight, maxHeightPixels);
        int contentHeight = Math.max(0, contentHeightPixels);
        return Math.max(minHeight, Math.min(maxHeight, contentHeight));
    }

    /** SceneCore applies entity scale after local size; invert it to preserve authored metres. */
    static float statsEntityLocalMeters(float worldMeters, float entityScale) {
        if (!Float.isFinite(worldMeters) || worldMeters < 0.0f
                || !Float.isFinite(entityScale) || entityScale <= 0.0f) {
            return 0.0f;
        }
        return worldMeters / entityScale;
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
        return obtainStatsRow(section ? STATS_ROW_SECTION : STATS_ROW_METRIC);
    }

    private TableRow obtainStatsRow(String expectedTag) {
        boolean section = STATS_ROW_SECTION.equals(expectedTag);
        if (!reuseStatsRows) {
            TableRow row = createStatsRow(expectedTag);
            statsTable.addView(row);
            return row;
        }

        int index = primaryStatsRowCursor++;
        View existing = index < statsTable.getChildCount()
                ? statsTable.getChildAt(index) : null;
        if (existing instanceof TableRow && expectedTag.equals(existing.getTag())) {
            return (TableRow) existing;
        }

        TableRow replacement = createStatsRow(expectedTag);
        if (existing != null) {
            statsTable.removeViewAt(index);
        }
        statsTable.addView(replacement, index);
        return replacement;
    }

    private TableRow createStatsRow(String tag) {
        boolean section = STATS_ROW_SECTION.equals(tag);
        TableRow row = new TableRow(activity);
        row.setTag(tag);
        if (section) {
            TextView heading = new TextView(activity);
            heading.setTextColor(paletteColor(R.color.xr_accent));
            setScaledTextSize(heading, STATS_TEXT_DIMEN, STATS_CONTENT_SCALE);
            heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
            heading.setPadding(0, statsDp(10), 0, statsDp(4));
            TableRow.LayoutParams params = new TableRow.LayoutParams();
            params.span = 2;
            heading.setLayoutParams(params);
            row.addView(heading);
            return row;
        }

        TextView label = new TextView(activity);
        label.setTextColor(paletteColor(R.color.xr_text_secondary));
        setScaledTextSize(label, STATS_TEXT_DIMEN, STATS_CONTENT_SCALE);
        label.setLineSpacing(0f, 1.08f);
        label.setPadding(0, statsDp(3), statsDp(18), statsDp(3));

        TextView value = new TextView(activity);
        setScaledTextSize(value, STATS_TEXT_DIMEN, STATS_CONTENT_SCALE);
        value.setLineSpacing(0f, 1.08f);
        value.setPadding(0, statsDp(3), 0, statsDp(3));

        if (STATS_ROW_TREND.equals(tag)) {
            value.setSingleLine(true);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // Keep the value and its plot inside one bounded table cell. The previous third
            // global TableLayout column depended on runtime font measurement to remain inside
            // SceneCore's clipped panel raster. Sharing the remaining width makes the plot's
            // bounds independent of the widest metric label in another row.
            LinearLayout trendContent = new LinearLayout(activity);
            trendContent.setOrientation(LinearLayout.HORIZONTAL);
            trendContent.setGravity(Gravity.CENTER_VERTICAL);
            trendContent.setWeightSum(1.0f);
            trendContent.addView(value, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.72f));

            XrSparklineView spark = new XrSparklineView(activity);
            LinearLayout.LayoutParams sparkParams =
                    new LinearLayout.LayoutParams(0, statsDp(32), 0.28f);
            sparkParams.leftMargin = statsDp(14);
            sparkParams.topMargin = statsDp(4);
            trendContent.addView(spark, sparkParams);

            row.addView(label);
            row.addView(trendContent);
        } else {
            row.addView(label);
            row.addView(value);
        }
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

    private int thermalStatusColor(int status) {
        if (status >= 5) {
            return paletteColor(R.color.xr_danger);
        }
        if (status >= 3) {
            return paletteColor(R.color.xr_status_warn);
        }
        return paletteColor(R.color.xr_status_ok);
    }

    /**
     * Row with a history plot after the value. Kept to metrics whose meaning depends on their
     * recent shape -- a pop of 1.20 is either a risky scene or one re-classified a moment ago, and
     * only the trend separates those.
     */
    private void addTrendStatsRow(String label, String value, int valueColor,
                                   float[] trend, boolean asDeltas,
                                   float rangeMin, float rangeMax) {
        float[] plotted = trend;
        int count = trend == null ? 0 : trend.length;
        if (asDeltas && count > 1) {
            float[] deltas = new float[count - 1];
            count = ClientSbsMetricHistory.toDeltas(trend, count, deltas);
            plotted = deltas;
        }
        if (count < 2) {
            // Nothing to plot yet: fall back to a plain row so the value still shows during the
            // first seconds of a session rather than leaving a gap. The sample count is not
            // appended here -- the sparkline's content description carries it, in the working
            // case as well as this one.
            addStatsRow(label, value, valueColor);
            return;
        }
        String trendSummary = XrSparklineView.describeTrend(plotted, count);
        if (asDeltas) {
            String restart = XrSparklineView.describeCounterRestart(
                    trend, trend != null ? trend.length : 0);
            if (restart != null) {
                trendSummary += ", " + restart;
            }
        }

        TableRow row = obtainStatsRow(STATS_ROW_TREND);
        ((TextView) row.getChildAt(0)).setText(label);
        LinearLayout trendContent = (LinearLayout) row.getChildAt(1);
        TextView valueView = (TextView) trendContent.getChildAt(0);
        valueView.setText(value);
        valueView.setTextColor(valueColor);
        XrSparklineView spark = (XrSparklineView) trendContent.getChildAt(1);
        // A plain View has neither a role nor a text equivalent. Include the actual recent shape,
        // rather than merely proving the sparkline exists, so accessibility users receive the
        // distinction this plot was added to expose.
        spark.setContentDescription(label + " trend, " + count + " samples, "
                + trendSummary);
        spark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        spark.setColors(valueColor, paletteColor(R.color.xr_border_panel));
        spark.setRange(rangeMin, rangeMax);
        spark.setValues(plotted, count,
                asDeltas
                        ? ClientSbsMetricHistory.CAPACITY - 1
                        : ClientSbsMetricHistory.CAPACITY);
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
        int vm = dimen(R.dimen.xr_space_md);
        lp.topMargin = vm;
        lp.bottomMargin = vm;
        lp.leftMargin = dimen(R.dimen.xr_space_xs);
        lp.rightMargin = dimen(R.dimen.xr_space_xs);
        d.setLayoutParams(lp);
        d.setBackgroundColor(paletteColor(R.color.xr_border));
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

    static int calculateModeOptionsRasterHeightPixels(int contentHeightPixels,
                                                      int baseHeightPixels,
                                                      float minHeightMeters,
                                                      float maxHeightMeters) {
        int baseHeight = Math.max(1, baseHeightPixels);
        if (!Float.isFinite(minHeightMeters) || minHeightMeters <= 0.0f
                || !Float.isFinite(maxHeightMeters)) {
            return baseHeight;
        }
        float boundedMaxHeight = Math.max(minHeightMeters, maxHeightMeters);
        double scaledMaximum = Math.ceil(baseHeight * (double) boundedMaxHeight
                / (double) minHeightMeters);
        int maxHeightPixels = scaledMaximum >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE : Math.max(baseHeight, (int) scaledMaximum);
        return calculateStatsRasterHeightPixels(
                contentHeightPixels, baseHeight, maxHeightPixels);
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
        if (modeOptionsBaseRasterHeightPixels <= 0) {
            modeOptionsBaseRasterHeightPixels = hostHeight;
            modeOptionsRasterHeightPixels = hostHeight;
        }
        modeOptionsContentRoot.measure(
                View.MeasureSpec.makeMeasureSpec(hostWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentHeightPx = modeOptionsContentRoot.getMeasuredHeight()
                + dimen(R.dimen.xr_space_xs);
        int targetHeightPixels = calculateModeOptionsRasterHeightPixels(
                contentHeightPx, modeOptionsBaseRasterHeightPixels,
                MODE_OPTIONS_MIN_HEIGHT_METERS, MODE_OPTIONS_MAX_HEIGHT_METERS);
        // Derive metres from the original raster/metre pair. Using the live pair here would make
        // repeated fits compound integer rounding whenever content or modes change.
        float targetHeightMeters = calculateModeOptionsHeightMeters(
                MODE_OPTIONS_MIN_HEIGHT_METERS, modeOptionsBaseRasterHeightPixels,
                targetHeightPixels,
                MODE_OPTIONS_MIN_HEIGHT_METERS, MODE_OPTIONS_MAX_HEIGHT_METERS);
        if (targetHeightPixels == modeOptionsRasterHeightPixels
                && Math.abs(targetHeightMeters - modeOptionsHeightMeters) < 0.001f) {
            return;
        }
        XrControlPanelLayout layout = controlBarLayout(panelHeightMeters);
        modeOptionsRasterHeightPixels = targetHeightPixels;
        modeOptionsHeightMeters = targetHeightMeters;
        // The two dimensions are independent in SceneCore. setSize() alone stretches the
        // physical quad but leaves the hosted View/ScrollView raster at its old height, clipping
        // the additional model choices. Resize the Android raster first, then the physical panel.
        modeOptionsPanel.setSizeInPixels(new IntSize2d(hostWidth, targetHeightPixels));
        modeOptionsPanel.setSize(new FloatSize2d(layout.widthMeters, targetHeightMeters));
        modeOptionsPanel.setPose(modeOptionsPose(panelHeightMeters));
        modeOptionsHost.requestLayout();
        int modelChoiceCount = clientModelChoiceGroup != null
                ? clientModelChoiceGroup.getChildCount() : 0;
        int modelGroupHeight = clientModelChoiceGroup != null
                ? clientModelChoiceGroup.getMeasuredHeight() : 0;
        int lastModelBottom = modelChoiceCount > 0
                ? clientModelChoiceGroup.getChildAt(modelChoiceCount - 1).getBottom() : 0;
        LimeLog.info(String.format(Locale.US,
                "XR mode options fit: raster=%dx%d contentHeight=%d physicalHeight=%.3fm "
                        + "modelChoices=%d modelGroupHeight=%d lastModelBottom=%d",
                hostWidth, targetHeightPixels, contentHeightPx, targetHeightMeters,
                modelChoiceCount, modelGroupHeight, lastModelBottom));
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
        reconcileHostSbsTelemetrySubscription();
        for (BarItem item : barItems) {
            if (item.selectsMode != null) {
                item.setEnabled(sessionControlsEnabled && isPresentationModeSupported(
                        item.selectsMode, hostControlExtensionsSupported));
            }
        }
        LimeLog.info("XR: first video frame rendered; presentation switching enabled");
        updateGlancePanel();
        revealDockTemporarily();
        schedulePanelRateReconcile();

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
        if (!controlTransportOpen() || !streamPresentationReady || item.selectsMode == null
                || !isPresentationModeSupported(
                        item.selectsMode, hostControlExtensionsSupported)
                || surfaceEntity == null
                || surfaceEntity.isDisposed()
                || item.selectsMode == currentPresenterMode || modeSwitchInProgress
                || liveQualityTransactionBusy()) {
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
        // A ready push belongs to one host-depth generation. Clear it before asking Apollo to
        // enter Host SBS AI so an old session cannot briefly authorize Dump 3D while the
        // replacement pipeline is still loading. A new phase-2 push may then arrive at any point
        // during the transition without being erased again at commit.
        if (resetsHostDepthStatusAtTransitionStart(previousMode, nextMode)) {
            resetHostDepthStatus();
        }
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
                schedulePanelRateReconcile();
                reportModeSwitchFailure("decoder could not prepare for the transition");
                return;
            }
        }

        // Honor the native send result before committing the UI. Otherwise a failed reliable
        // control send leaves the client stereo interpretation out of sync with the host layout.
        int previousWireMode = wireModeFor(previousMode);
        int nextWireMode = wireModeFor(nextMode);
        if (prefConfig.isHostDoubledWidthMode() && nextWireMode != previousWireMode
                && sendHostSbsModeControl(nextWireMode) <= 0) {
            // No surface changed, so the existing target is immediately safe for the replacement
            // IDR that completes the decoder flush.
            if (decoderTransitionRequired) {
                game.completeDecoderPresentationModeTransition();
            }
            lastModeSwitchMs = 0;
            modeSwitchInProgress = false;
            updateGlancePanel();
            revealDockTemporarily();
            schedulePanelRateReconcile();
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

    /**
     * Applies a stream-quality tuple to the running stream with no reconnect.
     *
     * <p>Bitrate-only and frame-rate-only deltas take the fast path: one reliable control message,
     * no surface transaction and no IDR gate. A resolution delta reuses the proven mode-switch
     * transaction verbatim — close the frame gate, hide the quad, ask the host, re-pin the
     * SceneCore surface through the dummy-surface handoff, then reveal once the fresh transition
     * IDR reaches the new target.</p>
     *
     * <p>Main-thread only: every SceneCore call below is Activity-bound.</p>
     */
    public void applyLiveStreamQuality(StreamQualityTuple target) {
        if (target == null) {
            return;
        }
        if (!hostControlExtensionsSupported) {
            LimeLog.info("XR: standard host requires reconnect for stream-quality changes");
            controlActionListener.onLiveStreamQualityNeedsReconnect();
            return;
        }
        float requestedCeiling = parseFrameRate(target.frameRate, prefConfig.fps);
        int effectiveFps = panelRefreshRateState.capUserTarget(
                Math.max(1, Math.round(requestedCeiling)));
        StreamQualityTuple effectiveTarget = effectiveFps == Math.round(requestedCeiling)
                ? target
                : new StreamQualityTuple(
                        target.resolution, String.valueOf(effectiveFps), target.bitrateKbps);
        applyLiveStreamQuality(
                effectiveTarget, LiveQualityRequestOrigin.USER, target);
    }

    /**
     * Follows an automatic headset display-mode change.
     *
     * <p>Android XR moves the panel between its 60/72/90 Hz modes on its own for thermal and power
     * reasons. A stream running at a rate the panel no longer refreshes at is delivered on an
     * uneven cadence — 90 into 72 holds every fifth frame an extra refresh — so the stream follows
     * the panel DOWN. It never follows it back up past the rate the user selected: that ceiling is
     * an encode-budget decision about the host, not a display decision about the headset.</p>
     */
    public void onClientRefreshRateChanged(float panelRefreshHz) {
        panelRefreshRateState.observe(panelRefreshHz);
        if (hostControlExtensionsSupported) {
            reconcilePanelRefreshRate();
        }
    }

    /**
     * Largest offered rate not above {@code effectiveHz}.
     *
     * <p>The panel's own modes are 60/72/90, but {@code getRefreshRate()} also reports a system
     * frame-rate override, which is not restricted to those and can read below 60. Requesting an
     * arbitrary rate would ask the host's virtual display for a mode outside the ladder both ends
     * agree on, so the follow target is snapped down onto it instead.</p>
     */
    static int snapToOfferedFrameRate(int effectiveHz) {
        int[] offered = {30, 60, 72, 90, 120};
        int best = 0;
        for (int rate : offered) {
            if (rate <= effectiveHz && rate > best) {
                best = rate;
            }
        }
        // Never chase an override below the slowest offered rate; hold the floor instead.
        return best == 0 ? offered[0] : best;
    }

    private void schedulePanelRateReconcile() {
        schedulePanelRateReconcile(0L);
    }

    private void schedulePanelRateReconcile(long delayMs) {
        if (!controlTransportOpen() || !hostControlExtensionsSupported) {
            panelRateReconcilePosted = false;
            liveQualityHandler.removeCallbacks(panelRateReconcileRunnable);
            return;
        }
        if (panelRateReconcilePosted) {
            liveQualityHandler.removeCallbacks(panelRateReconcileRunnable);
        }
        panelRateReconcilePosted = true;
        if (delayMs > 0L) {
            liveQualityHandler.postDelayed(panelRateReconcileRunnable, delayMs);
        } else {
            liveQualityHandler.post(panelRateReconcileRunnable);
        }
    }

    /**
     * Attempts the latest coalesced panel target. A blocked transaction leaves the observation
     * pending; its completion schedules this method again.
     */
    private void reconcilePanelRefreshRate() {
        if (!controlTransportOpen()) {
            return;
        }
        boolean blocked = !streamPresentationReady || surfaceEntity == null
                || surfaceEntity.isDisposed() || modeSwitchInProgress
                || liveQualityTransactionBusy() || pendingDecoderTransitionMode != null
                || clientSbsHdrTransitionInProgress;
        int targetFps = panelRefreshRateState.nextTarget(
                Math.round(prefConfig.fps), blocked);
        if (targetFps <= 0) {
            return;
        }

        LimeLog.info("XR: headset panel is "
                + panelRefreshRateState.getObservedPanelHz()
                + "Hz; following effective stream rate to " + targetFps
                + " (durable user ceiling "
                + panelRefreshRateState.getUserCeilingHz() + ")");
        StreamQualityTuple target = new StreamQualityTuple(
                prefConfig.width + "x" + prefConfig.height,
                String.valueOf(targetFps), prefConfig.bitrate);
        if (!applyLiveStreamQuality(
                target, LiveQualityRequestOrigin.PANEL_FOLLOW, null)) {
            panelRefreshRateState.automaticRequestFailed(true);
            schedulePanelRateReconcile(500L);
        }
    }

    private boolean applyLiveStreamQuality(StreamQualityTuple target,
                                           LiveQualityRequestOrigin origin,
                                           StreamQualityTuple durableUserTarget) {
        if (!controlTransportOpen() || target == null || !streamPresentationReady
                || surfaceEntity == null
                || surfaceEntity.isDisposed() || modeSwitchInProgress
                || liveQualityTransactionBusy() || pendingDecoderTransitionMode != null
                || clientSbsHdrTransitionInProgress) {
            return false;
        }
        com.limelight.Game game = activity instanceof com.limelight.Game
                ? (com.limelight.Game) activity : null;
        if (game == null) {
            return false;
        }

        float currentFps = prefConfig.fps;
        int[] size = parseResolutionSize(target.resolution);
        float fps = parseFrameRate(target.frameRate, currentFps);
        int fpsX100 = frameRateX100(target.frameRate, currentFps);
        if (size == null || fps <= 0 || fpsX100 <= 0) {
            LimeLog.warning("XR: ignoring unparseable live stream quality " + target);
            return false;
        }
        StreamQualityTuple previous = new StreamQualityTuple(
                prefConfig.width + "x" + prefConfig.height,
                formatFrameRate(currentFps), prefConfig.bitrate);
        boolean resolutionChanged = size[0] != prefConfig.width || size[1] != prefConfig.height;
        if (resolutionChanged && !supportsLiveResolutionChange(
                currentPresenterMode, prefConfig.rawSbsPerEyeResolution)) {
            // Raw Full's 2W x H transport is negotiated at connect and must reconnect. The
            // controller classifies this too — refuse here as well so a mode/controller mismatch
            // cannot corrupt state.
            LimeLog.warning("XR: refusing a live resolution change in " + currentPresenterMode);
            reportLiveQualityStartFailure(
                    origin, currentPresenterMode + " cannot resize live");
            return false;
        }

        int requestId = nextVideoModeRequestId();

        if (!resolutionChanged) {
            // Fast path: nothing client-side is sized by bitrate or frame rate, so apply
            // optimistically and let the ack resynchronize to whatever the host actually ran.
            if (sendHostVideoModeControl(prefConfig.width, prefConfig.height,
                    fpsX100, requestId, target.bitrateKbps) <= 0) {
                reportLiveQualityStartFailure(
                        origin, "host request could not be queued");
                return false;
            }
            pendingVideoModeRequestId = requestId;
            pendingLiveQuality = target;
            pendingLiveQualityOrigin = origin;
            pendingDurableUserQuality = durableUserTarget;
            previousLiveQuality = previous;
            acknowledgedLiveQuality = null;
            pendingLiveQualityMode = currentPresenterMode;
            liveQualityConfirmations.begin(false);
            prefConfig.fps = fps;
            prefConfig.bitrate = target.bitrateKbps;
            updateDecoderStreamGeometry(
                    game, currentPresenterMode,
                    prefConfig.width, prefConfig.height, Math.round(fps));
            armLiveQualityAckTimeout();
            updateGlancePanel();
            revealDockTemporarily();
            LimeLog.info("XR: requested live stream quality " + target
                    + " (request " + requestId + ", awaiting ack)");
            return true;
        }

        liveQualityChangeInProgress = true;
        liveQualityConfirmations.begin(
                true, currentPresenterMode == PresenterMode.CLIENT_SBS_AI);
        previousLiveQuality = previous;
        pendingLiveQuality = target;
        acknowledgedLiveQuality = null;
        pendingLiveQualityMode = currentPresenterMode;
        updateGlancePanel();
        revealDockTemporarily();

        int transitionGeneration = game.beginDecoderPresentationModeTransition();
        if (!decoderTransitionGenerations.beginMode(transitionGeneration)) {
            clearLiveQualityChange();
            reportLiveQualityStartFailure(
                    origin, "decoder could not prepare for the transition");
            return false;
        }

        // Honor the native send result before touching any client geometry, exactly as the SBS
        // mode switch does. A failed reliable send leaves the stream untouched.
        int sendResult = sendHostVideoModeControl(
                size[0], size[1], fpsX100, requestId, target.bitrateKbps);
        if (sendResult <= 0) {
            // No surface changed, so the existing target is immediately safe for the replacement
            // IDR that completes the decoder flush.
            game.completeDecoderPresentationModeTransition();
            clearLiveQualityChange();
            reportLiveQualityStartFailure(
                    origin, "host request could not be queued");
            return false;
        }
        pendingVideoModeRequestId = requestId;
        pendingLiveQualityOrigin = origin;
        pendingDurableUserQuality = durableUserTarget;

        surfaceEntity.setAlpha(0.0f);
        // Size the client for what we asked. If the ack later reports a clamped apply, the
        // geometry is re-pinned to the applied values before the quad is revealed.
        if (!applyLiveStreamGeometry(game, size[0], size[1], fps, target.bitrateKbps)) {
            game.completeDecoderPresentationModeTransition();
            if (postSendGeometryFailureRequiresMandatoryResync(sendResult)) {
                LimeLog.severe("XR: host video-mode request was queued, but the client could "
                        + "not prepare its presentation geometry; forcing resynchronization");
                requireMandatoryLiveQualityResync(
                        shouldCommitStagedSettingsForResync(origin), false);
                // The queued transaction is owned by mandatory reconnect now. Returning true
                // prevents an automatic caller from treating it as a locally retryable send.
                return true;
            }
            clearLiveQualityChange();
            reportLiveQualityStartFailure(origin, "the XR surface could not be resized");
            return false;
        }

        game.completeDecoderPresentationModeTransition();
        armLiveQualityAckTimeout();
        LimeLog.info("XR: awaiting ack/fresh-IDR output for live quality " + target
                + " (request " + requestId + ")");
        return true;
    }

    /** What a 0x3008 ack means for the outstanding request. */
    enum VideoModeAckOutcome {
        /** Not for the outstanding request (or none is outstanding); drop it. */
        IGNORE_STALE,
        /** The host is running the applied values; adopt them, clamped or not. */
        ADOPT_APPLIED,
        /** Valid but only reachable by reconnecting; stop waiting and restart the stream. */
        NEEDS_RECONNECT,
        /** Failed validation; revert the staged UI and do not retry the same request. */
        REJECTED_NO_RETRY,
        /** Transient failure, already rolled back on the host; revert, retry is permitted. */
        FAILED_RETRYABLE,
        /** Unknown/future status: the protocol does not prove which tuple remains on the host. */
        AMBIGUOUS_RESYNC,
    }

    /**
     * Correlates an ack strictly by {@code requestId}. An ack for any other id — including one
     * arriving after the outstanding request has already been settled or timed out — is stale.
     */
    static VideoModeAckOutcome videoModeAckOutcome(int outstandingRequestId, int ackRequestId,
                                                   int status) {
        if (outstandingRequestId <= 0 || ackRequestId != outstandingRequestId) {
            return VideoModeAckOutcome.IGNORE_STALE;
        }
        switch (status) {
            case MoonBridge.VIDEO_MODE_ACK_APPLIED:
                return VideoModeAckOutcome.ADOPT_APPLIED;
            case MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT:
                return VideoModeAckOutcome.NEEDS_RECONNECT;
            case MoonBridge.VIDEO_MODE_ACK_REJECTED_INVALID:
                return VideoModeAckOutcome.REJECTED_NO_RETRY;
            case MoonBridge.VIDEO_MODE_ACK_FAILED:
                return VideoModeAckOutcome.FAILED_RETRYABLE;
            default:
                // Only the defined FAILED status promises that the host rolled back. A future or
                // corrupt status is ambiguous and must not publish the previous tuple as fact.
                return VideoModeAckOutcome.AMBIGUOUS_RESYNC;
        }
    }

    static boolean videoModeAckRequiresMandatoryResync(
            VideoModeAckOutcome outcome, AcknowledgedVideoMode acknowledged) {
        return outcome == VideoModeAckOutcome.AMBIGUOUS_RESYNC
                || (outcome != VideoModeAckOutcome.IGNORE_STALE && acknowledged == null);
    }

    static boolean acknowledgedGeometryAdoptionSucceeded(
            boolean geometryChanged, boolean resizeSucceeded) {
        // The fast FPS/bitrate-only path performs no surface resize and is already adopted.
        return !geometryChanged || resizeSucceeded;
    }

    static boolean postSendGeometryFailureRequiresMandatoryResync(int sendResult) {
        // Once the reliable request is queued, local resize failure cannot prove host rollback.
        return sendResult > 0;
    }

    private int nextVideoModeRequestId() {
        // Opaque u16 correlation token with wraparound; zero is reserved for "no request".
        videoModeRequestCounter = (videoModeRequestCounter % 0xFFFF) + 1;
        return videoModeRequestCounter;
    }

    /**
     * Host answer to a live video-mode request (0x3008).
     *
     * <p>Correlation is strictly by {@code requestId}; an ack for any other id is stale and
     * dropped. The {@code applied*} values are authoritative — the host legitimately clamps an
     * oversized width to the codec ceiling and scales height to preserve aspect, so a clamped
     * apply is adopted rather than treated as a failure.</p>
     *
     * <p>Main-thread only.</p>
     */
    public void onVideoModeAck(int requestId, int status, int appliedWidth, int appliedHeight,
                               int appliedFramerateX100, int appliedBitrateKbps) {
        VideoModeAckOutcome outcome = videoModeAckOutcome(
                pendingVideoModeRequestId, requestId, status);
        if (outcome == VideoModeAckOutcome.IGNORE_STALE) {
            LimeLog.info("XR: dropping stale video-mode ack " + requestId
                    + " (outstanding request " + pendingVideoModeRequestId + ")");
            return;
        }
        com.limelight.Game game = activity instanceof com.limelight.Game
                ? (com.limelight.Game) activity : null;
        if (game == null) {
            clearLiveQualityChange();
            return;
        }
        cancelLiveQualityAckTimeout();
        pendingVideoModeRequestId = -1;

        PresenterMode requestMode = liveQualityRequestMode();
        StreamQualityTuple requestedLogicalQuality = outcome == VideoModeAckOutcome.ADOPT_APPLIED
                ? pendingLiveQuality : previousLiveQuality;
        AcknowledgedVideoMode acknowledged = acknowledgedVideoMode(
                requestedLogicalQuality, requestMode, prefConfig.rawSbsPerEyeResolution,
                appliedWidth, appliedHeight, appliedFramerateX100, appliedBitrateKbps);
        StreamQualityTuple appliedTuple = acknowledged != null
                ? acknowledged.logicalQuality : null;
        if (videoModeAckRequiresMandatoryResync(outcome, acknowledged)) {
            if (outcome == VideoModeAckOutcome.ADOPT_APPLIED) {
                LimeLog.severe("XR: host returned APPLIED without a usable authoritative "
                        + "video mode for request " + requestId);
            } else if (outcome == VideoModeAckOutcome.AMBIGUOUS_RESYNC) {
                LimeLog.severe("XR: host returned unknown video-mode status " + status
                        + " for request " + requestId + "; host state is ambiguous");
            } else {
                LimeLog.severe("XR: host returned video-mode status " + status
                        + " without usable authoritative wire geometry for request "
                        + requestId + "; host state is ambiguous");
            }
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForMalformedAckResync(
                            outcome, pendingLiveQualityOrigin));
            return;
        }
        if (acknowledged != null) {
            effectiveEncoderBitrateKbps = acknowledged.effectiveEncoderBitrateKbps;
        }
        if (outcome != VideoModeAckOutcome.ADOPT_APPLIED) {
            handleVideoModeRefusal(game, status, appliedTuple);
            return;
        }

        // Adopt the applied values as authoritative, re-pinning geometry when the host clamped.
        LimeLog.info("XR: host applied " + appliedTuple.resolution + " @ "
                + appliedTuple.frameRate + " FPS for request " + requestId
                + " (requested wire bitrate " + appliedTuple.bitrateKbps
                + " Kbps, effective encoder bitrate " + effectiveEncoderBitrateKbps
                + " Kbps)");
        acknowledgedLiveQuality = appliedTuple;
        if (!applyAcknowledgedQuality(game, appliedTuple)) {
            LimeLog.severe("XR: host applied " + appliedTuple
                    + " but the client could not adopt its presentation geometry");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                    false);
            return;
        }
        liveQualityConfirmations.onAppliedAck();

        if (liveQualityChangeInProgress) {
            beginPostAckLiveQualityDecoderConfirmation(game);
            return;
        }
        finishConfirmedLiveQualityChange(game);
    }

    /**
     * Establishes a causal decoder boundary after Apollo has acknowledged the applied resolution.
     *
     * <p>The first transition protects the local Surface resize while the host request is in
     * flight, but its IDR may still come from the previous encoder. Superseding it here makes every
     * accepted completion newer than the APPLIED ACK. The decoder watchdog bounds a lost IDR; this
     * method never starts a second post-ACK attempt.</p>
     */
    private void beginPostAckLiveQualityDecoderConfirmation(com.limelight.Game game) {
        if (!liveQualityConfirmations.beginPostAckDecoderConfirmation()) {
            LimeLog.severe("XR: could not arm the post-ack decoder confirmation exactly once; "
                    + "forcing authoritative reconnect");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                    false);
            return;
        }

        int transitionGeneration = game.beginDecoderPresentationModeTransition();
        if (!decoderTransitionGenerations.beginMode(transitionGeneration)) {
            LimeLog.severe("XR: decoder could not prepare the post-ack resolution confirmation; "
                    + "forcing authoritative reconnect");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                    false);
            return;
        }

        game.completeDecoderPresentationModeTransition();
        LimeLog.info("XR: host resolution is authoritative; awaiting one post-ack fresh-IDR "
                + "output before revealing the surface");
    }

    /**
     * Adopts the host's applied geometry and frame rate while retaining the requested total wire
     * bitrate. Apollo's post-audio/FEC encoder bitrate is tracked separately and must never replace
     * {@link PreferenceConfiguration#bitrate}.
     */
    private boolean applyAcknowledgedQuality(com.limelight.Game game, StreamQualityTuple applied) {
        return applyAcknowledgedQuality(
                game, applied, this::onClientSbsLiveResizeComplete);
    }

    private boolean applyAcknowledgedQuality(
            com.limelight.Game game, StreamQualityTuple applied,
            StreamContainer.SurfaceSwitchCallback clientSbsResizeCallback) {
        int[] size = parseResolutionSize(applied.resolution);
        float fps = parseFrameRate(applied.frameRate, prefConfig.fps);
        if (size == null) {
            return false;
        }
        boolean geometryChanged =
                size[0] != prefConfig.width || size[1] != prefConfig.height;
        if (geometryChanged) {
            LimeLog.info("XR: host clamped the request to " + applied
                    + "; adopting it as authoritative");
            return acknowledgedGeometryAdoptionSucceeded(true,
                    applyLiveStreamGeometry(
                            game, size[0], size[1], fps, applied.bitrateKbps,
                            clientSbsResizeCallback));
        }
        prefConfig.fps = fps;
        prefConfig.bitrate = applied.bitrateKbps;
        updateDecoderStreamGeometry(
                game, liveQualityRequestMode(), size[0], size[1], Math.round(fps));
        return acknowledgedGeometryAdoptionSucceeded(false, false);
    }

    private void handleVideoModeRefusal(com.limelight.Game game, int status,
                                        StreamQualityTuple stillInEffect) {
        // On a refusal the applied* values describe the mode that remains in effect, so
        // resynchronize the client to them rather than to what was optimistically staged.
        StreamQualityTuple durableStillInEffect =
                stillInEffect != null ? stillInEffect : previousLiveQuality;
        PresenterMode requestMode = liveQualityRequestMode();
        LiveQualityRequestOrigin requestOrigin = pendingLiveQualityOrigin;
        int[] rollbackSize = durableStillInEffect != null
                ? parseResolutionSize(durableStillInEffect.resolution) : null;
        boolean asynchronousClientRollback = liveQualityChangeInProgress
                && currentPresenterMode == PresenterMode.CLIENT_SBS_AI
                && rollbackSize != null
                && (rollbackSize[0] != prefConfig.width
                || rollbackSize[1] != prefConfig.height);
        if (durableStillInEffect != null) {
            StreamContainer.SurfaceSwitchCallback rollbackCompletion =
                    asynchronousClientRollback
                            ? success -> {
                                if (!liveQualityChangeInProgress) {
                                    return;
                                }
                                if (!success) {
                                    LimeLog.severe("XR: Client SBS could not restore the "
                                            + "authoritative geometry after host refusal");
                                    boolean commitRequestedAfterReconnect =
                                            requestOrigin == LiveQualityRequestOrigin.USER
                                                    && status == MoonBridge
                                                    .VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT;
                                    requireMandatoryLiveQualityResync(
                                            commitRequestedAfterReconnect, false);
                                    return;
                                }
                                finishVideoModeRefusal(
                                        status, durableStillInEffect,
                                        requestMode, requestOrigin);
                            }
                            : this::onClientSbsLiveResizeComplete;
            if (!applyAcknowledgedQuality(
                    game, durableStillInEffect, rollbackCompletion)) {
                LimeLog.severe("XR: host refused the request, but the client could not restore "
                        + "the authoritative previous presentation geometry");
                // Only NEEDS_RECONNECT says the requested USER target is valid for a fresh stream.
                // INVALID/FAILED must reconnect the last committed record instead.
                boolean commitRequestedAfterReconnect =
                        requestOrigin == LiveQualityRequestOrigin.USER &&
                                status == MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT;
                requireMandatoryLiveQualityResync(
                        commitRequestedAfterReconnect, false);
                return;
            }
            if (asynchronousClientRollback) {
                return;
            }
        }
        finishVideoModeRefusal(status, durableStillInEffect, requestMode, requestOrigin);
    }

    private void finishVideoModeRefusal(
            int status, StreamQualityTuple durableStillInEffect,
            PresenterMode requestMode, LiveQualityRequestOrigin requestOrigin) {
        boolean wasResolutionTransaction = liveQualityChangeInProgress;
        if (wasResolutionTransaction && surfaceEntity != null && !surfaceEntity.isDisposed()) {
            surfaceEntity.setAlpha(1.0f);
        }
        decoderTransitionGenerations.clearMode();
        clearLiveQualityChange();

        if (requestOrigin == LiveQualityRequestOrigin.PANEL_FOLLOW) {
            boolean retryable = status == MoonBridge.VIDEO_MODE_ACK_FAILED;
            panelRefreshRateState.automaticRequestFailed(retryable);
            LimeLog.warning("XR: automatic panel-rate follow was refused (status "
                    + status + (retryable ? "); retrying once" : "); holding current rate"));
            if (retryable) {
                schedulePanelRateReconcile(500L);
            }
            return;
        }

        panelRefreshRateState.otherTransactionSettled();
        if (status == MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT) {
            // The whole point of the ack: take the reconnect immediately instead of making the
            // user sit through the watchdog timeout.
            LimeLog.info("XR: host reports the requested mode needs a reconnect");
            controlActionListener.onLiveStreamQualityNeedsReconnect();
            return;
        }

        // An explicit non-reconnect refusal withdraws the staged target. Publish the tuple that
        // remains on the wire so the controller updates both pending and applied state before its
        // atomic commit; otherwise a rejected optimistic fast-path target becomes durable. Keep
        // the pre-request durable ceiling rather than persisting a temporary panel-throttled FPS.
        if (durableStillInEffect != null) {
            StreamQualityTuple durableRollback = new StreamQualityTuple(
                    durableStillInEffect.resolution,
                    String.valueOf(panelRefreshRateState.getUserCeilingHz()),
                    durableStillInEffect.bitrateKbps);
            controlActionListener.onLiveStreamQualityApplied(
                    requestMode, durableRollback);
        }
        LimeLog.severe("XR: host refused the live video mode (status " + status + ")");
        reportLiveQualityFailure(status == MoonBridge.VIDEO_MODE_ACK_REJECTED_INVALID
                ? "the PC rejected the request as invalid"
                : "the PC could not complete the change");
        schedulePanelRateReconcile();
    }

    /**
     * The host's applied wire mode converted to a logical stream-quality tuple. Raw Full ACKs
     * carry the already-packed {@code 2W x H} desktop; every other mode carries its logical/base
     * dimensions. {@code bitrateKbps} is the host's post-budget encoder value rather than the
     * requested wire budget. Fractional frame rates retain the hundredths-of-a-Hz precision
     * carried by the ACK. Null when the host reported nothing usable.
     */
    static StreamQualityTuple appliedTuple(
            PresenterMode mode,
            PreferenceConfiguration.RawSbsPerEyeResolution rawPerEyeResolution,
            int wireWidth, int wireHeight, int framerateX100, int bitrateKbps) {
        int[] logicalDimensions = liveVideoModeLogicalDimensions(
                mode, wireWidth, wireHeight, rawPerEyeResolution);
        if (logicalDimensions == null || framerateX100 <= 0 || bitrateKbps <= 0) {
            return null;
        }
        return new StreamQualityTuple(
                logicalDimensions[0] + "x" + logicalDimensions[1],
                formatFrameRateX100(framerateX100), bitrateKbps);
    }

    /** Identity-mode convenience retained for deterministic tuple-formatting tests. */
    static StreamQualityTuple appliedTuple(int width, int height, int framerateX100,
                                           int bitrateKbps) {
        return appliedTuple(PresenterMode.NORMAL,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                width, height, framerateX100, bitrateKbps);
    }

    /**
     * Reconciles an ACK without confusing its effective encoder bitrate with the total wire
     * budget sent in the request.
     */
    static AcknowledgedVideoMode acknowledgedVideoMode(
            StreamQualityTuple requestedLogicalQuality, int width, int height,
            int framerateX100, int effectiveEncoderBitrateKbps) {
        return acknowledgedVideoMode(requestedLogicalQuality, PresenterMode.NORMAL,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                width, height, framerateX100, effectiveEncoderBitrateKbps);
    }

    static AcknowledgedVideoMode acknowledgedVideoMode(
            StreamQualityTuple requestedLogicalQuality, PresenterMode mode,
            PreferenceConfiguration.RawSbsPerEyeResolution rawPerEyeResolution,
            int wireWidth, int wireHeight, int framerateX100,
            int effectiveEncoderBitrateKbps) {
        StreamQualityTuple effective = appliedTuple(
                mode, rawPerEyeResolution, wireWidth, wireHeight,
                framerateX100, effectiveEncoderBitrateKbps);
        if (requestedLogicalQuality == null || effective == null) {
            return null;
        }
        return new AcknowledgedVideoMode(new StreamQualityTuple(
                effective.resolution, effective.frameRate,
                requestedLogicalQuality.bitrateKbps),
                effectiveEncoderBitrateKbps);
    }

    static final class AcknowledgedVideoMode {
        final StreamQualityTuple logicalQuality;
        final int effectiveEncoderBitrateKbps;

        private AcknowledgedVideoMode(StreamQualityTuple logicalQuality,
                                     int effectiveEncoderBitrateKbps) {
            this.logicalQuality = logicalQuality;
            this.effectiveEncoderBitrateKbps = effectiveEncoderBitrateKbps;
        }
    }

    /**
     * Re-pins every dimension-derived piece of client state to a new stream size: the cached
     * aspect, the SceneCore surface (through {@code StreamContainer}'s dummy-surface handoff so
     * MediaCodec never sees a transient target), the quad, and the dependent panel poses.
     */
    private boolean applyLiveStreamGeometry(com.limelight.Game game, int width, int height,
                                            float fps, int bitrateKbps) {
        return applyLiveStreamGeometry(
                game, width, height, fps, bitrateKbps,
                this::onClientSbsLiveResizeComplete);
    }

    private boolean applyLiveStreamGeometry(
            com.limelight.Game game, int width, int height,
            float fps, int bitrateKbps,
            StreamContainer.SurfaceSwitchCallback clientSbsResizeCallback) {
        StreamContainer streamContainer = game.getStreamContainer();
        if (streamContainer == null || surfaceEntity == null || surfaceEntity.isDisposed()) {
            return false;
        }

        // Client SBS owns its own GL color targets and presents a packed 2W x H swapchain, so it
        // resizes through the renderer rather than the host-surface dummy-park handoff.
        boolean clientSbsResize = currentPresenterMode == PresenterMode.CLIENT_SBS_AI;
        boolean resized;
        if (clientSbsResize) {
            liveQualityConfirmations.expectPresentationConfirmation();
            // StreamContainer first takes the renderer's GL callback lock and invalidates output.
            // Only then is it safe to publish new dimensions through the shared preferences.
            resized = streamContainer.resizeClientSbsSurface(
                    width, height, clientSbsResizeCallback);
        } else {
            prefConfig.width = width;
            prefConfig.height = height;
            resized = streamContainer.resizeHostSbsSurface(
                    prefConfig.isHostDoubledWidthMode()
                            && isHostDepthPresenterMode(currentPresenterMode));
        }
        if (!resized) {
            return false;
        }

        prefConfig.width = width;
        prefConfig.height = height;
        prefConfig.fps = fps;
        prefConfig.bitrate = bitrateKbps;
        // The only cached dimension-derived field in this class.
        fullAspect = (float) width / height;
        updateDecoderStreamGeometry(
                game, currentPresenterMode, width, height, Math.round(fps));

        float aspect = aspectFor(currentPresenterMode);
        SurfaceEntity.Shape shape = surfaceEntity.getShape();
        float quadHeight = (shape instanceof SurfaceEntity.Shape.Quad)
                ? ((SurfaceEntity.Shape.Quad) shape).getExtents().getHeight()
                : DEFAULT_PANEL_HEIGHT_METERS;
        surfaceEntity.setShape(
                new SurfaceEntity.Shape.Quad(new FloatSize2d(quadHeight * aspect, quadHeight)));
        applyResizeBounds(aspect);
        // Also repositions the stats panel.
        repositionControlBar(quadHeight);
        updateGlancePanel();
        return true;
    }

    /** Third completion boundary for Client SBS: exact packed EGL output at the new geometry. */
    private void onClientSbsLiveResizeComplete(boolean success) {
        if (!liveQualityChangeInProgress) {
            return;
        }
        if (!success) {
            LimeLog.severe("XR: Client SBS could not validate its resized packed output; "
                    + "forcing authoritative reconnect");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                    false);
            return;
        }
        liveQualityConfirmations.onPresentationReady();
        com.limelight.Game game = activity instanceof com.limelight.Game
                ? (com.limelight.Game) activity : null;
        if (game != null) {
            finishConfirmedLiveQualityChange(game);
        }
    }

    /**
     * Decoder callback path for either the provisional pre-ACK transition or the authoritative
     * post-ACK transition.
     *
     * <p>The ACK rides reliable ENet while video rides RTP, so provisional output may arrive on
     * either side of it. A resolution transaction settles only when output from the generation
     * explicitly started after the ACK matches the host-acknowledged geometry.</p>
     */
    private void finishPendingLiveQualityChange() {
        com.limelight.Game game = activity instanceof com.limelight.Game
                ? (com.limelight.Game) activity : null;
        if (!liveQualityChangeInProgress || game == null || surfaceEntity == null
                || surfaceEntity.isDisposed()) {
            decoderTransitionGenerations.clearMode();
            clearLiveQualityChange();
            return;
        }

        // A decoder callback may precede the ACK. Retain its exact dimensions for diagnostics, but
        // never let it settle the request: APPLIED clears this evidence and supersedes the decoder
        // transition before asking for the authoritative confirmation.
        int[] expected = expectedLiveQualityDecoderDimensions();
        int[] actual = game.getDecoderOutputDimensions();
        int actualWidth = actual != null ? actual[0] : 0;
        int actualHeight = actual != null ? actual[1] : 0;
        liveQualityConfirmations.onDecoderOutput(
                actualWidth, actualHeight, expected[0], expected[1]);
        if (liveQualityRequestMode() == PresenterMode.CLIENT_SBS_AI
                && liveQualityConfirmations
                .isWaitingForPresentationAfterMatchingPostAckOutput()) {
            StreamContainer streamContainer = game.getStreamContainer();
            if (streamContainer != null) {
                streamContainer.onClientSbsPostAckDecoderOutput(
                        actualWidth, actualHeight);
            }
        }
        finishConfirmedLiveQualityChange(game);
    }

    private int[] expectedLiveQualityDecoderDimensions() {
        return initialSurfacePixelDimensions(liveQualityRequestMode(),
                prefConfig.width, prefConfig.height, hostSbsVideoFormat,
                prefConfig.rawSbsPerEyeResolution);
    }

    private PresenterMode liveQualityRequestMode() {
        return pendingLiveQualityMode != null
                ? pendingLiveQualityMode : currentPresenterMode;
    }

    /**
     * Completes a live-quality transaction only when every confirmation required by its path is
     * present. For resolution requests, only the single post-ACK decoder transition can complete
     * the transaction. A mismatch from that transition is terminal; continuing would expose a
     * surface whose geometry disagrees with the host.
     */
    private void finishConfirmedLiveQualityChange(com.limelight.Game game) {
        if (!liveQualityConfirmations.canSettle()) {
            if (decoderMismatchRequiresMandatoryResync(
                    liveQualityChangeInProgress, liveQualityConfirmations)) {
                int[] expected = expectedLiveQualityDecoderDimensions();
                int[] actual = game.getDecoderOutputDimensions();
                LimeLog.severe("XR: post-ack fresh-IDR output "
                        + (actual == null ? "unknown" : actual[0] + "x" + actual[1])
                        + " does not match the host-applied "
                        + expected[0] + "x" + expected[1]
                        + "; forcing authoritative reconnect");
                requireMandatoryLiveQualityResync(
                        shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                        false);
                return;
            }

            if (liveQualityChangeInProgress
                    && liveQualityConfirmations.hasDecoderOutput()) {
                int[] expected = expectedLiveQualityDecoderDimensions();
                int[] actual = game.getDecoderOutputDimensions();
                if (liveQualityConfirmations.hasMatchingDecoderOutput()) {
                    if (!liveQualityConfirmations.hasAppliedAck()) {
                        LimeLog.info("XR: matching fresh-IDR output arrived before the host ack");
                    } else if (liveQualityConfirmations
                            .isWaitingForPresentationAfterMatchingPostAckOutput()) {
                        LimeLog.info("XR: matching post-ack fresh-IDR output reached Client SBS; "
                                + "waiting for packed EGL presentation");
                    } else {
                        LimeLog.info("XR: matching fresh-IDR output is waiting for the remaining "
                                + "live-quality confirmation");
                    }
                } else {
                    LimeLog.info("XR: decoder output "
                            + (actual == null ? "unknown" : actual[0] + "x" + actual[1])
                            + " does not yet match " + expected[0] + "x" + expected[1]
                            + "; waiting for the host ack");
                }
            }
            return;
        }

        StreamQualityTuple applied = acknowledgedLiveQuality != null
                ? acknowledgedLiveQuality : pendingLiveQuality;
        PresenterMode requestMode = liveQualityRequestMode();
        boolean wasResolutionTransaction = liveQualityChangeInProgress;
        if (wasResolutionTransaction && surfaceEntity != null && !surfaceEntity.isDisposed()) {
            surfaceEntity.setAlpha(1.0f);
        }
        decoderTransitionGenerations.clearMode();
        settleSuccessfulLiveQuality(requestMode, applied);
        updateGlancePanel();
        revealDockTemporarily();
        LimeLog.info("XR: live quality settled at " + applied);
    }

    private void settleSuccessfulLiveQuality(PresenterMode requestMode,
                                             StreamQualityTuple applied) {
        LiveQualityRequestOrigin origin = pendingLiveQualityOrigin;
        StreamQualityTuple durableRequested = pendingDurableUserQuality;
        StreamQualityTuple durableApplied = durableUserQuality(applied, durableRequested);

        if (origin == LiveQualityRequestOrigin.PANEL_FOLLOW) {
            panelRefreshRateState.automaticRequestSucceeded(
                    Math.round(parseFrameRate(
                            applied != null ? applied.frameRate : "0", prefConfig.fps)));
        } else if (durableApplied != null) {
            panelRefreshRateState.userRequestSucceeded(
                    parseFrameRate(durableApplied.frameRate, prefConfig.fps));
        } else {
            panelRefreshRateState.otherTransactionSettled();
        }

        // The decoder target is an offscreen SurfaceTexture while Client SBS is active. Reapply
        // the durable vote to SceneCore's actual presentation swapchain as the authoritative
        // display-rate owner, in addition to keeping the decoder target's hint current.
        applyDurableVideoSurfaceFrameRateCeiling();
        if (activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).updateDecoderSurfaceFrameRateCeiling(
                    panelRefreshRateState.getUserCeilingHz());
        }
        clearLiveQualityChange();
        if (shouldPersistLiveQualityRequest(origin) && durableApplied != null) {
            // Only an explicit user transaction enters the durable settings path. Panel/thermal
            // following changes the current wire rate and glance, never the saved ceiling.
            controlActionListener.onLiveStreamQualityApplied(requestMode, durableApplied);
        }
        schedulePanelRateReconcile();
    }

    /**
     * Reconciles the host-applied tuple with the user's durable FPS ceiling.
     *
     * <p>The selected FPS is a maximum, so an ACK's lower current FPS is always effective-only.
     * Host-clamped geometry and the requested wire bitrate remain authoritative and durable.</p>
     */
    static StreamQualityTuple durableUserQuality(
            StreamQualityTuple applied, StreamQualityTuple durableRequested) {
        if (applied == null) {
            return null;
        }
        if (durableRequested == null) {
            return applied;
        }
        return new StreamQualityTuple(
                applied.resolution, durableRequested.frameRate, applied.bitrateKbps);
    }

    /**
     * Backstop for a host that acknowledges nothing. A queued reliable request or even a matching
     * IDR cannot prove the host's final FPS/bitrate clamps. Every missing application ACK therefore
     * fails closed to a reconnect; no local success or rollback tuple is claimed as authoritative.
     */
    private void onLiveQualityAckTimeout() {
        if (!liveQualityTransactionBusy() && pendingLiveQuality == null) {
            return;
        }
        com.limelight.Game game = activity instanceof com.limelight.Game
                ? (com.limelight.Game) activity : null;
        if (game == null) {
            clearLiveQualityChange();
            return;
        }
        LiveQualityAckTimeoutDisposition timeoutDisposition =
                liveQualityAckTimeoutDisposition(
                        liveQualityChangeInProgress, pendingLiveQualityOrigin);
        if (timeoutDisposition == LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION
                && liveQualityConfirmations.hasMatchingDecoderOutput()) {
            LimeLog.severe("XR: no video-mode ack after matching fresh-IDR output; "
                    + "final clamps remain unknown");
        } else if (timeoutDisposition == LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION
                && liveQualityConfirmations.hasDecoderOutput()) {
            LimeLog.severe("XR: no video-mode ack explained the fresh-IDR geometry; "
                    + "application remains ambiguous");
        } else if (timeoutDisposition == LiveQualityAckTimeoutDisposition.RECONNECT_RESOLUTION) {
            LimeLog.severe("XR: no video-mode ack and no fresh-IDR output");
        } else {
            LimeLog.severe("XR: no video-mode ack for the live bitrate/frame-rate change; "
                    + "host application remains ambiguous");
        }
        requireMandatoryLiveQualityResync();
    }

    /**
     * Releases every local transition owner and reconnects whenever the host's final tuple cannot
     * be proven. This is shared by missing ACKs, unknown statuses, malformed APPLIED tuples, and a
     * client-side failure to adopt authoritative applied geometry.
     */
    private void requireMandatoryLiveQualityResync() {
        LiveQualityRequestOrigin abandonedOrigin = pendingLiveQualityOrigin;
        requireMandatoryLiveQualityResync(
                shouldCommitStagedSettingsForResync(abandonedOrigin));
    }

    private void requireMandatoryLiveQualityResync(boolean commitStagedSettings) {
        requireMandatoryLiveQualityResync(commitStagedSettings, true);
    }

    private void requireMandatoryLiveQualityResync(
            boolean commitStagedSettings, boolean allowConfirmedSurfaceReveal) {
        boolean wasResolutionTransaction = liveQualityChangeInProgress;
        LiveQualityAckTimeoutDisposition disposition =
                liveQualityAckTimeoutDisposition(
                        liveQualityChangeInProgress, pendingLiveQualityOrigin);
        if (shouldRevealSurfaceDuringMandatoryResync(
                disposition,
                liveQualityConfirmations.hasMatchingDecoderOutput(),
                allowConfirmedSurfaceReveal)
                && surfaceEntity != null && !surfaceEntity.isDisposed()) {
            surfaceEntity.setAlpha(1.0f);
        }
        // A reconnect owns recovery from this point. Release a still-active decoder gate now so a
        // delayed Activity restart cannot leave compressed input starved or fire its generic
        // surface-switch timeout on top of the authoritative reconnect.
        if (wasResolutionTransaction && activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).cancelDecoderPresentationModeTransition();
        }
        decoderTransitionGenerations.clearMode();
        panelRefreshRateState.requestAbandonedForReconnect();
        clearLiveQualityChange();
        controlActionListener.onLiveStreamQualityResyncRequired(
                commitStagedSettings);
    }

    private void armLiveQualityAckTimeout() {
        liveQualityHandler.removeCallbacks(liveQualityAckTimeoutRunnable);
        liveQualityHandler.postDelayed(liveQualityAckTimeoutRunnable,
                LIVE_QUALITY_ACK_TIMEOUT_MS);
    }

    private void cancelLiveQualityAckTimeout() {
        liveQualityHandler.removeCallbacks(liveQualityAckTimeoutRunnable);
    }

    private void clearLiveQualityChange() {
        cancelLiveQualityAckTimeout();
        liveQualityChangeInProgress = false;
        liveQualityConfirmations.clear();
        pendingVideoModeRequestId = -1;
        pendingLiveQuality = null;
        previousLiveQuality = null;
        acknowledgedLiveQuality = null;
        pendingLiveQualityMode = null;
        pendingLiveQualityOrigin = null;
        pendingDurableUserQuality = null;
        updateGlancePanel();
        revealDockTemporarily();
    }

    private void reportLiveQualityStartFailure(
            LiveQualityRequestOrigin origin, String reason) {
        if (origin == LiveQualityRequestOrigin.PANEL_FOLLOW) {
            LimeLog.warning("XR automatic panel-rate request could not start: " + reason);
            return;
        }
        reportLiveQualityFailure(reason);
    }

    private void reportLiveQualityFailure(String reason) {
        LimeLog.severe("XR live stream quality change failed: " + reason);
        if (activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).displayMessage(
                    activity.getString(R.string.xr_session_live_change_failed));
        }
        controlActionListener.onLiveStreamQualityFailed();
    }

    /**
     * Frame rate on the wire is hundredths of a Hz, so fractional rates (2997 = 29.97) survive
     * the round trip even though the XR picker currently only offers whole values.
     */
    static int frameRateX100(String frameRate, float fallbackFps) {
        float fps;
        try {
            fps = Float.parseFloat(frameRate.trim());
        } catch (RuntimeException e) {
            fps = fallbackFps;
        }
        return Math.max(0, Math.min(0xFFFF, Math.round(fps * 100f)));
    }

    static float parseFrameRate(String frameRate, float fallback) {
        try {
            return Float.parseFloat(frameRate.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String formatFrameRate(float frameRate) {
        return formatFrameRateX100(Math.round(frameRate * 100f));
    }

    private static String formatFrameRateX100(int frameRateX100) {
        int whole = frameRateX100 / 100;
        int hundredths = frameRateX100 % 100;
        if (hundredths == 0) {
            return String.valueOf(whole);
        }
        if (hundredths % 10 == 0) {
            return whole + "." + (hundredths / 10);
        }
        return whole + "." + (hundredths < 10 ? "0" : "") + hundredths;
    }

    /**
     * Modes whose decoded frame size can change live. Raw <em>Full</em> is the only exclusion:
     * its {@code 2W x H} packed transport is negotiated at connect and 0x3007 cannot renegotiate
     * it. Raw Half streams {@code W x H} exactly like Normal and resizes live. Client SBS resizes
     * through {@code Stereo3DRenderer}, which refuses a change to its immutable pipeline contract.
     */
    static boolean supportsLiveResolutionChange(
            PresenterMode mode,
            PreferenceConfiguration.RawSbsPerEyeResolution perEyeResolution) {
        return !usesRawPackedTransport(mode, perEyeResolution);
    }

    /** Parses a {@code WxH} stream-quality resolution id; null when it is not usable. */
    static int[] parseResolutionSize(String resolution) {
        if (resolution == null) {
            return null;
        }
        try {
            String[] parts = resolution.split("x", 2);
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            return (width > 0 && height > 0) ? new int[] {width, height} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void finishModeSwitch(BarItem item, PresenterMode previousMode, PresenterMode nextMode,
                                  int previousWireMode, int nextWireMode, boolean wasClientSbs,
                                  boolean isClientSbs, StreamContainer streamContainer,
                                  boolean surfaceSwitchSucceeded) {
        if (!controlTransportOpen()) {
            return;
        }
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
            schedulePanelRateReconcile();
            if (activity instanceof com.limelight.Game) {
                ((com.limelight.Game) activity).cancelDecoderPresentationModeTransition();
            }
            if (streamContainer != null) {
                streamContainer.setClientSbsActive(wasClientSbs);
            }
            if (prefConfig.isHostDoubledWidthMode() && nextWireMode != previousWireMode
                    && sendHostSbsModeControl(previousWireMode) <= 0) {
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

        if (resetsHostDepthStatusAtTransitionCommit(previousMode, nextMode)) {
            resetHostDepthStatus();
        }
        currentPresenterMode = nextMode;
        reconcileHostSbsTelemetrySubscription();
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
            schedulePanelRateReconcile();
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

        if (liveQualityChangeInProgress) {
            if (!decoderTransitionGenerations.dispatchModeIfCurrent(
                    transitionGeneration, this::finishPendingLiveQualityChange)) {
                LimeLog.warning("XR: ignoring superseded live-quality completion generation "
                        + transitionGeneration);
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
        schedulePanelRateReconcile();
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
        boolean liveQualityTimeout = liveQualityChangeInProgress;
        boolean current = decoderTransitionGenerations.dispatchAnyIfCurrent(
                transitionGeneration, () -> {
                    if (pendingDecoderTransitionMode != null) {
                        LimeLog.severe("XR: mode " + pendingDecoderTransitionMode
                                + " timed out before fresh-IDR output");
                    } else if (liveQualityChangeInProgress) {
                        // Keep liveStreamQuality untouched: the listener is never told the change
                        // succeeded, so the staged tuple stays pending while Game terminates.
                        LimeLog.severe("XR: live quality " + pendingLiveQuality
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
        if (current && liveQualityTimeout) {
            // A live resolution transaction already has a dedicated hidden reconnect path. Do not
            // convert its bounded fresh-IDR failure into the generic decoder-surface error dialog.
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                    false);
            return false;
        }
        // Keep both the pending mode and switch guard set while Game terminates the stream. This
        // prevents another tile tap and keeps onDestroy() from persisting the failed mode.
        return current;
    }

    private void finishClientSbsHdrTransition(boolean success) {
        if (!controlTransportOpen() || !clientSbsHdrTransitionInProgress) {
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
        schedulePanelRateReconcile();
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
        if (!hostControlExtensionsSupported) {
            return MoonBridge.SBS_MODE_OFF;
        }
        return XrViewStateStore.desiredHostSbsWireMode(
                XrViewStateStore.Mode.valueOf(currentPresenterMode.name()));
    }

    private boolean hostDebugDumpAvailable() {
        boolean transitionInProgress = modeSwitchInProgress
                || pendingDecoderTransitionMode != null
                || liveQualityTransactionBusy();
        return controlTransportOpen() && hostControlExtensionsSupported
                && isHostDebugDumpAvailable(
                currentPresenterMode, streamPresentationReady, sessionControlsEnabled,
                transitionInProgress, depthStatusPhase == 2);
    }

    private void updateHostDebugDumpAvailability() {
        if (dumpItem != null) {
            dumpItem.setEnabled(hostDebugDumpAvailable());
        }
    }

    /** Client "Dump 3D" button: ask the host to dump one SBS debug frame (2D source / raw depth /
     *  processed depth / SBS result) to its configured debug dir, for offline diagnosis of the
     *  host reprojection. */
    private void requestHostDebugDump() {
        // Keep the protocol boundary guarded even if a stale accessibility or controller event
        // reaches this listener after the tile was disabled during a mode transition.
        if (!hostDebugDumpAvailable()) {
            LimeLog.warning("XR: ignoring host SBS debug dump outside stable Host SBS AI");
            updateHostDebugDumpAvailability();
            return;
        }
        LimeLog.info("XR: requesting host SBS debug frame dump");
        sendHostDebugDumpControl();
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
        return controlTransportOpen() && surfaceEntity != null && !surfaceEntity.isDisposed()
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
                    paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_tile), 1));
        }

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClickable(true);
        col.setFocusable(true);
        col.setFocusableInTouchMode(false);
        col.setContentDescription(item.label);
        int pad = dimen(R.dimen.xr_space_xs);
        col.setPadding(pad, pad, pad,
                item.selectsMode != null ? dimen(R.dimen.xr_space_xl) : pad);
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
                    dimen(R.dimen.xr_control_compact), dimen(R.dimen.xr_space_lg),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            chevronParams.bottomMargin = dimen(R.dimen.xr_space_xs);
            root.addView(chevron, chevronParams);
            item.optionsIndicator = chevron;
        }

        return root;
    }

    /** Visible press/hover feedback on top of the dark fill. */
    private void applySelectableForeground(View view) {
        // Not the platform selectableItemBackground: it is unmasked, so over a rounded tile the
        // highlight paints square corners inside the border.
        view.setForeground(ContextCompat.getDrawable(activity, R.drawable.xr_selectable_overlay));
    }

    private void addBarItemContent(LinearLayout col, BarItem item) {
        ImageView icon = new ImageView(activity);
        int iconSize = activity.getResources()
                .getDimensionPixelSize(R.dimen.xr_icon_tile);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        icon.setImageResource(item.iconRes);
        icon.setColorFilter(ContextCompat.getColor(activity, R.color.xr_text_primary));
        item.iconView = icon;

        col.addView(icon);
        if (item.iconOnly) {
            return;
        }

        TextView text = new TextView(activity);
        text.setText(item.label);
        text.setTextColor(paletteColor(R.color.xr_text_primary));
        setTextSize(text, R.dimen.xr_text_title);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dimen(R.dimen.xr_space_xs);
        text.setLayoutParams(tp);
        col.addView(text);

    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private int dimen(int resourceId) {
        return activity.getResources().getDimensionPixelSize(resourceId);
    }

    private int statsDp(float v) {
        return dp(v * STATS_CONTENT_SCALE);
    }

    private int statsDimen(int resourceId) {
        return Math.round(activity.getResources().getDimension(resourceId)
                * STATS_CONTENT_SCALE);
    }

    private void setTextSize(TextView view, int textSizeResource) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                activity.getResources().getDimension(textSizeResource));
    }

    private void setScaledTextSize(TextView view, int textSizeResource, float scale) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                activity.getResources().getDimension(textSizeResource) * scale);
    }

    private android.graphics.drawable.GradientDrawable controlSurfaceBackground(
            int fillColor, int strokeColor, int strokeWidthDp) {
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(fillColor);
        background.setStroke(dp(strokeWidthDp), strokeColor);
        background.setCornerRadius(
                activity.getResources().getDimension(R.dimen.xr_radius_card));
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
                        paletteColor(R.color.xr_accent_deep), paletteColor(R.color.xr_accent), 2));
            } else {
                root.setBackground(controlSurfaceBackground(
                        paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_tile), 1));
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
     * Adopts a newly obtained SceneCore presentation Surface and immediately restores its durable
     * frame-rate vote. SceneCore may replace the Surface when pixel dimensions change.
     */
    private void adoptVideoSurface(Surface surface) {
        videoSurface = surface;
        applyDurableVideoSurfaceFrameRateCeiling();
    }

    /**
     * Reapplies the user-selected ceiling to the actual SceneCore output swapchain.
     *
     * <p>Best effort: the Surface may already be retiring during a mode transition. The next
     * {@link #adoptVideoSurface(Surface)} call reapplies the same durable value to its replacement.
     * The temporary effective stream FPS is intentionally not used here.</p>
     */
    private void applyDurableVideoSurfaceFrameRateCeiling() {
        Surface surface = videoSurface;
        int ceilingHz = durableSurfaceFrameRateVoteHz(panelRefreshRateState);
        if (surface == null || !surface.isValid() || ceilingHz <= 0
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                        (float) ceilingHz,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS);
            } else {
                surface.setFrameRate(
                        (float) ceilingHz,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
            LimeLog.info("XR: SceneCore presentation Surface ceiling is "
                    + ceilingHz + " Hz");
        } catch (Throwable error) {
            // Surface replacement races are recoverable: the next adopted Surface retries.
            LimeLog.warning("XR: unable to apply SceneCore Surface frame-rate ceiling: "
                    + error.getMessage());
        }
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
        adoptVideoSurface(surfaceEntity.getSurface());
    }

    /**
     * Resizes the Client-SBS SceneCore swapchain from one explicit per-eye geometry.
     *
     * <p>The live path uses the same immutable values for this swapchain and the renderer's
     * packed override so a mutable preference update cannot leave one side at the previous
     * resolution.</p>
     *
     * @return true when SceneCore returned a valid (possibly replacement) Surface
     */
    public boolean setClientSbsSurfaceSize(int perEyeWidth, int perEyeHeight) {
        int[] packed = clientSbsPackedDimensions(perEyeWidth, perEyeHeight);
        if (packed == null || surfaceEntity == null || surfaceEntity.isDisposed()) {
            return false;
        }
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(packed[0], packed[1]));
        adoptVideoSurface(surfaceEntity.getSurface());
        return videoSurface != null && videoSurface.isValid();
    }

    /** Final XR swapchain width for Client SBS: two negotiated-size eye views side by side. */
    public int getClientSbsSurfaceWidth() {
        int[] packed = clientSbsPackedDimensions(prefConfig.width, prefConfig.height);
        return packed != null ? packed[0] : 0;
    }

    /** Final XR height for Client SBS, identical to the negotiated stream height. */
    public int getClientSbsSurfaceHeight() {
        return prefConfig.height;
    }

    /** Exact packed output for two full-resolution Client-SBS eyes, or null when invalid. */
    static int[] clientSbsPackedDimensions(int perEyeWidth, int perEyeHeight) {
        long packedWidth = (long) perEyeWidth * 2L;
        if (perEyeWidth <= 0 || perEyeHeight <= 0 || packedWidth > Integer.MAX_VALUE) {
            return null;
        }
        return new int[] {(int) packedWidth, perEyeHeight};
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

    /** Actual encoded/presentation geometry retained by MediaCodec recovery. */
    static int[] decoderStreamDimensions(PresenterMode mode,
                                         int logicalWidth,
                                         int logicalHeight,
                                         int hostAiVideoFormat,
                                         PreferenceConfiguration.RawSbsPerEyeResolution
                                                 rawPerEyeResolution) {
        return initialSurfacePixelDimensions(mode, logicalWidth, logicalHeight,
                hostAiVideoFormat, rawPerEyeResolution);
    }

    private void updateDecoderStreamGeometry(
            com.limelight.Game game, PresenterMode mode,
            int logicalWidth, int logicalHeight, int fps) {
        int[] encodedDimensions = decoderStreamDimensions(
                mode, logicalWidth, logicalHeight, hostSbsVideoFormat,
                prefConfig.rawSbsPerEyeResolution);
        game.updateDecoderStreamGeometry(
                encodedDimensions[0], encodedDimensions[1], fps);
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
        adoptVideoSurface(surfaceEntity.getSurface());
    }

    /**
     * Tear down the entity/session. Mirrors {@code Stereo3DRenderer.onSurfaceDestroyedAsync()} /
     * {@code StreamContainer.onDestroy()} ordering.
     */
    public void onDestroy() {
        if (presenterDestroyed) {
            return;
        }
        presenterDestroyed = true;
        onHostActivityStopped();
        // Native connection cleanup has already completed by StreamContainer teardown time. Only
        // clear local state here; the synchronous pre-stop hook owns the final transport write.
        controlTransportClosing = true;
        clearHostSbsTelemetrySubscriptionState();
        modeOptionsStatusHandler.removeCallbacks(refreshClientOptionsStatus);
        dockVisibilityHandler.removeCallbacks(collapseDockRunnable);
        cancelLiveQualityAckTimeout();
        liveQualityHandler.removeCallbacks(panelRateReconcileRunnable);
        panelRateReconcilePosted = false;
        liveQualityChangeInProgress = false;
        liveQualityConfirmations.clear();
        pendingVideoModeRequestId = -1;
        pendingLiveQuality = null;
        previousLiveQuality = null;
        acknowledgedLiveQuality = null;
        pendingLiveQualityMode = null;
        pendingLiveQualityOrigin = null;
        pendingDurableUserQuality = null;
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
        glanceLoadView = null;
        glanceStatusView = null;
        controlBarRow = null;
        dockRevealPill = null;
        dockHoverTarget = null;
        dockFocusTarget = null;
        videoSurface = null;
        statsTable = null;
        statsContentRoot = null;
        settingsItem = null;
        cinemaItem = null;
        statsItem = null;
        dumpItem = null;
        expansionItem = null;
        secondaryBarItems.clear();
        secondaryActionsExpanded = false;
        cinemaViewExpanded = false;
        cinemaRestorePose = null;
        cinemaRestoreHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;
        lastCinemaTileTapMs = 0L;
        modeOptionsHost = null;
        modeOptionsContentRoot = null;
        modeOptionsBaseRasterHeightPixels = 0;
        modeOptionsRasterHeightPixels = 0;
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
