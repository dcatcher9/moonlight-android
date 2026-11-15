package com.limelight.ui;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
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
import com.limelight.ui.xrcontrols.AuthoredStereoModeState;
import com.limelight.ui.xrcontrols.GameStereoSourceState;
import com.limelight.ui.xrcontrols.AuthoredStereoModeState.MoviePictureFormat;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;
import com.limelight.sbs.ClientSbsGpuDepthProcessor;
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
import com.limelight.utils.ServerHelper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Presentation owner for the single XR route ({@code MODE_XR}). Fresh host connections start in
 * Normal; only a host-confirmed resume of the same app restores its last successful per-machine/app
 * presentation preference. The user switches presentation modes from the in-headset control
 * bar.
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
 * <p>The quad is placed ~2 m in front, sized to one eye's aspect, and the user can move/resize it
 * (with a minimum distance clamp). A floating control panel beneath it offers four single-select
 * presentation modes, one reusable mode-options row, session settings, and stream actions.
 * Switching presentations re-pins the surface and, in the host AI depth mode, drives
 * the host's SBS pipeline on/off. See docs/android-xr-sbs.md. Session teardown and durable mode
 * persistence are handled in {@link #onDestroy()}.
 */
@SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
public class XrStreamPresenter {

    // Control-bar tile geometry (meters). Shared by build + reposition so the bar stays glued
    // beneath the quad as it changes size on a mode switch or a user resize.
    private static final float BAR_HEIGHT_METERS = 0.21f;     // also the per-tile size (square tiles)
    private static final float MODE_OPTIONS_MAX_HEIGHT_METERS = 0.90f;
    private static final float BAR_DIVIDER_METERS = 0.05f;    // extra width for the group divider
    private static final float BAR_GAP_METERS = 0.24f;        // quad bottom -> bar center
    private static final float MODE_OPTIONS_GAP_METERS = 0.02f;
    private static final float STATS_GAP_METERS = 0.10f;
    private static final float AUXILIARY_WIDTH_METERS = XrStatsPanel.STATS_WIDTH_METERS;
    private static final float AUXILIARY_HEIGHT_METERS = 1.16f;
    private static final long DOCK_AUTO_HIDE_DELAY_MS = 8000L;
    private static final float DOCK_REVEAL_ATTENTION_SCALE = 2.0f;
    private static final long DOCK_REVEAL_EXPAND_DURATION_MS = 280L;
    private static final long DOCK_REVEAL_SHRINK_DURATION_MS = 380L;
    private static final long DOCK_REVEAL_SHRINK_DELAY_MS = 250L;
    private static final TimeInterpolator DOCK_REVEAL_EXPAND_INTERPOLATOR =
            new OvershootInterpolator(0.8f);
    private static final TimeInterpolator DOCK_REVEAL_SHRINK_INTERPOLATOR =
            new DecelerateInterpolator(1.5f);

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

        default boolean onModeQualitySettingSelected(PresentationMode mode,
                                                     SessionSettingsModel.Key key,
                                                     String choiceId,
                                                     ModeStreamQualityModel current) {
            return false;
        }

        default void onUseSessionModeDefaultsRequested(PresentationMode mode,
                                                        ModeStreamQualityModel current) {
        }

        default void onPresentationModeCommitted(PresentationMode mode) {
        }

        /** Selects a mode and reconnects without attempting any live host control packet. */
        default void onPresentationModeNeedsReconnect(PresentationMode mode) {
        }

        /**
         * A live video-mode change (0x3007) has been accepted by the running stream. The listener
         * owns the durable/session-model bookkeeping; the presenter has already updated
         * {@code PreferenceConfiguration} and the XR geometry. The tuple's bitrate remains the
         * requested total wire budget; Apollo's acknowledged post-audio/FEC encoder bitrate is
         * diagnostic state and is not persisted.
         */
        default void onLiveStreamQualityApplied(PresentationMode mode, StreamQualityTuple applied) {
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

        /** Return true when the listener handled disconnect and navigation to app selection. */
        default boolean onDisconnectRequested() {
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
    /** Settings-restart handoff. Pose is intentionally not a durable per-PC preference. */
    private final XrReconnectViewState reconnectViewState;

    private Session session;
    private SurfaceEntity surfaceEntity;
    /** Written by the UI/SceneCore thread and read by the Client SBS EGL owner. */
    private volatile Surface videoSurface;

    /** The single PanelEntity hosting the whole row of buttons. */
    private PanelEntity barPanel;
    private IntSize2d barFullRasterSize;
    private int barRasterWidthPixels;
    private int barRasterHeightPixels;
    private View controlBarRow;
    private TextView dockRevealPill;
    private XrDockGazeTracker dockGazeTracker;
    private boolean dockRevealViewAttention;
    /** Hover/focus sources written by the pill's listeners and cleared when the pill hides. */
    private final DockRevealViewAttentionState dockRevealViewAttentionState =
            new DockRevealViewAttentionState();
    private boolean dockRevealGazeAttention;
    private ValueAnimator dockRevealAnimator;
    private float dockRevealScale = 1.0f;
    private float dockRevealTargetScale = 1.0f;
    /** The control-bar items (one clickable tile each, all hosted in {@link #barPanel}). */
    private final List<BarItem> barItems = new ArrayList<>();
    /** Reused contextual View hierarchy hosted by one independently tilted panel. */
    private FrameLayout modeOptionsHost;
    private PanelEntity modeOptionsPanel;
    private float modeOptionsHeightMeters = XrPanelPlacement.MODE_OPTIONS_MIN_HEIGHT_METERS;
    /** Original 0.52 m Android raster; retained so repeated fits cannot compound rounding. */
    private int modeOptionsBaseRasterHeightPixels;
    private int modeOptionsRasterHeightPixels;
    private View modeOptionsContentRoot;
    private boolean modeOptionsFitScheduled;
    /** Consecutive fit attempts that found the host without a layout; zeroed by a laid-out fit. */
    private int modeOptionsFitUnlaidRetries;
    static final int MODE_OPTIONS_FIT_MAX_UNLAID_RETRIES = 60;
    private static final long MODE_OPTIONS_FIT_UNLAID_RETRY_DELAY_MS = 16L;
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
            if (!hostActivityStarted || !isClientOptionsOpen()) {
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

    private XrChoiceGroup cinemaEnvironmentChoiceGroup;
    private Button cinemaActionButton;
    private final XrCinemaController cinemaController;

    /** Left-side PanelEntity for settings shared by every presentation mode in this session. */
    private PanelEntity auxiliaryPanel;
    private FrameLayout auxiliaryContentHost;
    private SessionSettingsModel sessionSettingsModel;
    private final EnumMap<PresentationMode, ModeStreamQualityModel> modeStreamQualityModels =
            new EnumMap<>(PresentationMode.class);
    private ClientSbsModeSettingsModel clientSbsModeSettingsModel;
    /** A requested setting still differs from the live connection. */
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
    private PresentationMode pendingLiveQualityMode;
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
    private final ClientPanelRefreshRatePreference clientPanelRefreshRatePreference =
            new ClientPanelRefreshRatePreference();
    /** Origin of the outstanding request; automatic panel-follow changes are never persisted. */
    private LiveQualityRequestOrigin pendingLiveQualityOrigin;
    /** User-authored tuple retained separately when the panel caps the effective wire FPS. */
    private StreamQualityTuple pendingDurableUserQuality;
    /** Any ACK-first mode transaction retained on the old SceneCore picture until proven. */
    private BarItem pendingAckFirstModeItem;
    private PresentationMode pendingAckFirstPreviousMode;
    /** Opaque correlation token for the outstanding 0x3007 request; -1 when there is none. */
    private int pendingVideoModeRequestId = -1;
    private int videoModeRequestCounter;
    private int pendingDesiredWireMode = -1;
    private int pendingRequestedSourceWidth;
    private int pendingRequestedSourceHeight;
    /** Exact decoder geometry supplied by an accepted v2 transaction. */
    private int pendingExactEncodedWidth;
    private int pendingExactEncodedHeight;
    /** Last fully committed v2 decoder geometry for the current host presentation. */
    private int currentExactEncodedWidth;
    private int currentExactEncodedHeight;
    /** Unsigned host presentation generation. Zero means no v2 generation observed yet. */
    private int lastPresentationStateGeneration;
    private boolean atomicPresentationV2Supported;
    private boolean gameProviderV1Supported;
    private final GameStereoSourceState gameSourceState = new GameStereoSourceState();
    /** Game intent survives reconnect; its packing and source proof never do. */
    private int gameWireMode = MoonBridge.SBS_MODE_GAME_MONO;
    private int confirmedGamePresentationGeneration;
    private boolean gameSourceReconcilePosted;
    /** Consecutive same-mode reconfirmation requests that failed to start locally. */
    private int gameSourceReconfirmStartRetries;
    static final int GAME_SOURCE_RECONFIRM_MAX_START_RETRIES = 3;
    static final long GAME_SOURCE_RECONFIRM_RETRY_DELAY_MS = 500L;
    private final Runnable gameSourceReconcileRunnable = () -> {
        gameSourceReconcilePosted = false;
        reconcileGameSource();
    };
    private static final String EXTRA_GAME_WIDEN_FAILURE = "xr_game_widen_failure";
    private static final String EXTRA_GAME_WIDEN_QUALITY = "xr_game_widen_quality";
    private boolean pendingClientPackedSwapProofArmed;
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
    private boolean settingsTransactionPending;
    private PresentationMode settingsRequestedPresentationMode;
    private PresentationMode renderedModeOptionsMode;
    private XrResolutionSelector modeResolutionSelector;
    private XrSegmentedLadder modeFpsLadder;
    private XrBitrateControl modeBitrateControl;
    private TextView modeQualityCueView;
    private Button modeDefaultsButton;
    private TextView clientModelNameView;
    private TextView clientAspectBucketView;
    private TextView clientRuntimeStatusView;
    private final AuthoredStereoModeState authoredStereoModeState = new AuthoredStereoModeState();
    private XrChoiceGroup moviePictureFormatChoiceGroup;
    private TextView gameSourceStatusView;
    private TextView gameSourceDetailView;

    private boolean hostActivityStarted;

    private final android.os.Handler dockVisibilityHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable collapseDockRunnable = this::collapseDockIfIdle;
    private final Runnable shrinkDockRevealRunnable = this::shrinkDockRevealIfUnattended;
    private boolean dockRevealShrinkPending;
    private boolean dockCollapsed;
    private View dockHoverTarget;
    private View dockFocusTarget;

    private final XrStatsPanel statsUi;
    private final XrGlanceStrip glanceStrip;
    private final XrSessionSettingsPane sessionSettingsPane;
    private volatile boolean statsVisible;
    private boolean controlUiStateApplied;
    private XrControlUiState.Surface appliedContextualSurface = XrControlUiState.Surface.NONE;
    private boolean appliedStatsRequested;
    private final DevicePerformanceSampler devicePerformanceSampler =
            new DevicePerformanceSampler();
    private static final int HOST_SBS_TELEMETRY_FOCUSED_INTERVAL_MS = 100;
    static final long HOST_SBS_TELEMETRY_RETRY_DELAY_MS = 500L;
    static final int HOST_SBS_TELEMETRY_MAX_RETRIES = 3;
    private final HostSbsTelemetryTracker hostSbsTelemetryTracker =
            new HostSbsTelemetryTracker();
    private final android.os.Handler hostSbsTelemetryRetryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int hostSbsTelemetryRequestCounter;
    private boolean hostSbsTelemetryRequested;
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
        if (!hostSbsTelemetryRequested || !shouldObserveHostSbsTelemetry()) {
            return;
        }
        hostSbsTelemetryRetryAttempts++;
        sendHostSbsTelemetrySubscriptionAttempt();
    }

    /** Small centered overlay shown in front of the video while the host prepares its engine or
     *  initializes the stream-specific 3D pipeline
     *  (driven by the host's 0x3006 depth-status push via {@link #onDepthStatus}). */
    private PanelEntity depthStatusPanel;
    private View depthStatusRoot;
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

    /** Comfortable cinema-preset quad height in meters; mode switches keep this height and vary
     *  width. Shared by the initial placement and Cinema View so both land at the same size. Tune
     *  by feel on the headset (at the ~2 m default distance, 2.0 m ≈ a large cinema screen). */
    private static final float DEFAULT_PANEL_HEIGHT_METERS =
            XrViewStateStore.DEFAULT_HEIGHT_METERS;
    private float panelHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;

    enum DockRevealInteraction {
        EXPLICIT_CLICK,
        PRESS_DOWN,
        HOVER,
        FOCUS
    }

    /**
     * Closes the sole ACK-first decoder gate before publishing any host-authoritative geometry.
     * Keeping this sequence in one helper makes it impossible for a concurrent codec recovery to
     * replay the replacement format onto the producer that is still presenting the old picture.
     */
    static int beginAckFirstClientDecoderTransitionBeforeGeometry(
            DecoderTransitionGenerationGate generations,
            IntSupplier beginTransition,
            BooleanSupplier stageGeometry) {
        if (generations == null || beginTransition == null || stageGeometry == null) {
            return 0;
        }
        int generation = beginTransition.getAsInt();
        if (!generations.beginMode(generation)) {
            return 0;
        }
        return stageGeometry.getAsBoolean() ? generation : 0;
    }

    /** Reuses the ACK-first gate at surface handoff; ordinary switches start their gate here. */
    static int reuseOrBeginModeDecoderTransition(
            DecoderTransitionGenerationGate generations,
            boolean ackFirstTransitionAlreadyStarted,
            IntSupplier beginTransition) {
        if (generations == null || beginTransition == null) {
            return 0;
        }
        if (ackFirstTransitionAlreadyStarted) {
            return generations.currentModeGeneration();
        }
        int generation = beginTransition.getAsInt();
        return generations.beginMode(generation) ? generation : 0;
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
        GAME_SOURCE,
    }

    static boolean shouldPersistLiveQualityRequest(LiveQualityRequestOrigin origin) {
        return origin == LiveQualityRequestOrigin.USER;
    }

    static boolean shouldCommitStagedSettingsForResync(LiveQualityRequestOrigin origin) {
        return origin == LiveQualityRequestOrigin.USER;
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
    private PresentationMode currentPresenterMode = PresentationMode.NORMAL;
    /** A saved Client SBS presentation to re-apply once the decoder has produced a valid Normal
     *  frame. Restoring before then would split a still-mono startup frame. */
    private PresentationMode deferredPresenterMode = PresentationMode.NORMAL;

    /** Debounce window for mode-tile taps: a switch starts an async surface handoff, so ignore a
     *  second tap that lands within this window (double-tap / impatient re-tap). */
    private static final long MODE_SWITCH_DEBOUNCE_MS = 600L;
    /** XR may deliver a click twice for one physical tap. Keep direct dock toggles deterministic. */
    private static final long CONTROL_TOGGLE_DEBOUNCE_MS = 400L;
    private long lastModeSwitchMs;
    private long lastStatsTileTapMs;
    private long lastCinemaTileTapMs;
    private long lastCinemaActionTapMs;
    private boolean modeSwitchInProgress;
    /** Surface handoff awaiting its fresh direct output or packed Client-SBS swap proof. */
    private PresentationMode pendingDecoderTransitionMode;
    /** Client-SBS transfer flip awaiting a fresh decoder IDR and first new-format EGL swap. */
    private boolean clientSbsHdrTransitionInProgress;
    private final DecoderTransitionGenerationGate decoderTransitionGenerations =
            new DecoderTransitionGenerationGate();
    /** Mode changes resize or hand off the decoder surface, so they remain disabled until the
     *  decoder confirms that the initial stream frame has reached the XR surface. */
    private boolean streamPresentationReady;

    // The selected resolution defines the eye/view aspect.
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
        this.glanceStrip = new XrGlanceStrip(activity, devicePerformanceSampler,
                new XrGlanceStrip.Host() {
                    @Override
                    public Session session() {
                        return session;
                    }

                    @Override
                    public SurfaceEntity videoEntity() {
                        return surfaceEntity;
                    }

                    @Override
                    public boolean hostActivityStarted() {
                        return hostActivityStarted;
                    }
                });
        this.statsUi = new XrStatsPanel(activity, devicePerformanceSampler, hostSbsTelemetryTracker,
                new XrStatsPanel.Host() {
                    @Override
                    public Session session() {
                        return session;
                    }

                    @Override
                    public SurfaceEntity videoEntity() {
                        return surfaceEntity;
                    }

                    @Override
                    public boolean statsVisible() {
                        return statsVisible;
                    }

                    @Override
                    public boolean presenterDestroyed() {
                        return presenterDestroyed;
                    }

                    @Override
                    public PresentationMode currentPresenterMode() {
                        return currentPresenterMode;
                    }

                    @Override
                    public Pose statsPose(float videoHeightMeters) {
                        return XrStreamPresenter.this.statsPose(videoHeightMeters);
                    }

                    @Override
                    public void repositionStatsPanel() {
                        XrStreamPresenter.this.repositionStatsPanel();
                    }
                });
        this.sessionSettingsPane = new XrSessionSettingsPane(activity,
                new XrSessionSettingsPane.Host() {
                    @Override
                    public SessionSettingsModel sessionSettingsModel() {
                        return sessionSettingsModel;
                    }

                    @Override
                    public ControlActionListener controlActionListener() {
                        return XrStreamPresenter.this.controlActionListener;
                    }

                    @Override
                    public boolean settingsControlsEnabled() {
                        return XrStreamPresenter.this.settingsControlsEnabled();
                    }
                });
        this.cinemaController = new XrCinemaController(prefConfig,
                new XrCinemaController.Host() {
                    @Override
                    public Session session() {
                        return session;
                    }

                    @Override
                    public SurfaceEntity videoEntity() {
                        return surfaceEntity;
                    }

                    @Override
                    public boolean hostActivityStarted() {
                        return hostActivityStarted;
                    }

                    @Override
                    public boolean controlTransportOpen() {
                        return XrStreamPresenter.this.controlTransportOpen();
                    }

                    @Override
                    public PresentationMode currentPresenterMode() {
                        return currentPresenterMode;
                    }

                    @Override
                    public float aspectFor(PresentationMode mode) {
                        return XrStreamPresenter.this.aspectFor(mode);
                    }

                    @Override
                    public float panelHeightMeters() {
                        return panelHeightMeters;
                    }

                    @Override
                    public void setPanelHeightMeters(float heightMeters) {
                        panelHeightMeters = heightMeters;
                    }

                    @Override
                    public void applyResizeBounds(float aspect) {
                        XrStreamPresenter.this.applyResizeBounds(aspect);
                    }

                    @Override
                    public Pose currentHeadPose() {
                        return XrStreamPresenter.this.currentHeadPose();
                    }

                    @Override
                    public void repositionControlBar(float videoHeightMeters) {
                        XrStreamPresenter.this.repositionControlBar(videoHeightMeters);
                    }

                    @Override
                    public void saveHeight(float heightMeters) {
                        viewStateStore.saveHeight(heightMeters);
                    }
                });
        this.controlActionListener = controlActionListener != null
                ? controlActionListener : NO_OP_CONTROL_ACTION_LISTENER;
        this.sessionSettingsModel = initialSessionSettingsModel(prefConfig);
        initializeModeQualityModels(prefConfig, sessionSettingsModel);
        this.clientSbsModeSettingsModel = initialClientSbsModeSettingsModel(prefConfig);
        this.viewStateStore = new XrViewStateStore(activity, activity.getIntent());
        restoreViewState(activity instanceof com.limelight.Game
                ? ((com.limelight.Game) activity).getXrStartupPresenterMode()
                : null);
        this.reconnectViewState = XrReconnectViewState.consumeFrom(activity.getIntent());
        if (reconnectViewState != null) {
            panelHeightMeters = reconnectViewState.panelHeightMeters;
            LimeLog.info("XR: applying reconnect view handoff at height "
                    + panelHeightMeters + " m"
                    + (reconnectViewState.realWorldPose != null ? " with pose" : ""));
        }
        // On a host-confirmed resume, restore direct Host AI/Game/Movie intent immediately. Client SBS
        // is deferred until after frame 1 because it requires a live decoder-to-GL handoff. A
        // fresh connection's state store returns Normal regardless of any older saved mode.
    }

    /** Install or replace the settings/session bridge after StreamContainer creates the presenter. */
    public void setControlActionListener(ControlActionListener listener) {
        controlActionListener = listener != null ? listener : NO_OP_CONTROL_ACTION_LISTENER;
    }

    /**
     * Enables Apollo-3D-only Host SBS AI, telemetry, debug-dump, and session controls. A regular
     * Sunshine or Apollo host still supports Normal, Game, Movie, and on-device Client SBS;
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
            atomicPresentationV2Supported = false;
            gameProviderV1Supported = false;
            invalidateGameSourceProof();
            liveQualityHandler.removeCallbacks(panelRateReconcileRunnable);
            panelRateReconcilePosted = false;
            clearHostSbsTelemetrySubscriptionState();
            if (deferredPresenterMode == PresentationMode.HOST_SBS_AI) {
                deferredPresenterMode = PresentationMode.NORMAL;
            }
        }
        for (BarItem item : barItems) {
            if (item.selectsMode != null) {
                item.setEnabled(streamPresentationReady && settingsControlsEnabled()
                        && XrPresentationPolicy.isPresentationModeSupported(
                                item.selectsMode, hostControlExtensionsSupported));
            }
        }
        updateHostDebugDumpAvailability();
        reconcileHostSbsTelemetrySubscription();
    }

    /** Enables v2 only after RTSP advertises the fixed atomic-presentation feature bit. */
    public void setAtomicPresentationV2Supported(boolean supported) {
        boolean enabled = hostControlExtensionsSupported && supported;
        if (atomicPresentationV2Supported == enabled) {
            return;
        }
        atomicPresentationV2Supported = enabled;
        if (!enabled) {
            gameProviderV1Supported = false;
            invalidateGameSourceProof();
            liveQualityHandler.removeCallbacks(panelRateReconcileRunnable);
            panelRateReconcilePosted = false;
        } else {
            schedulePanelRateReconcile();
        }
        updateHostDebugDumpAvailability();
    }

    /** Game transport is opt-in and requires the acknowledged presentation protocol. */
    public void setGameProviderV1Supported(boolean supported) {
        boolean enabled = hostControlExtensionsSupported && atomicPresentationV2Supported && supported;
        if (gameProviderV1Supported == enabled) {
            return;
        }
        gameProviderV1Supported = enabled;
        invalidateGameSourceProof();
        updateGameSourceViews();
        updateHostDebugDumpAvailability();
        scheduleGameSourceReconcile();
    }

    /** Reliable source observation; depth-engine progress is never game-stereo proof. */
    public void onGameSourceStatus(int state, int provider, int presentationGeneration,
                                   int sourceRevision, int sourceWidth, int sourceHeight,
                                   int packedWidth, int packedHeight) {
        if (!controlTransportOpen() || !gameProviderV1Supported
                || currentPresenterMode != PresentationMode.GAME_3D
                || confirmedGamePresentationGeneration == 0) {
            return;
        }
        if (gameSourceState.requiresPresentationReconfirmation(
                Integer.toUnsignedLong(presentationGeneration),
                Integer.toUnsignedLong(sourceRevision), state, provider,
                sourceWidth, sourceHeight, packedWidth, packedHeight)
                && canReconcileGameSource()) {
            LimeLog.info("XR: Game source observed newer presentation generation "
                    + Integer.toUnsignedString(presentationGeneration) + " after "
                    + Integer.toUnsignedString(confirmedGamePresentationGeneration)
                    + "; reconfirming wire mode " + gameWireMode);
            // Invalidate before posting so repeated observations coalesce into one request.
            // The observation is not an ACK and must never authorize packed interpretation.
            invalidateGameSourceProof();
            updateGameSourceViews();
            updateGlancePanel();
            scheduleGameSourceReconcile();
            return;
        }
        if (gameSourceState.acceptStatus(Integer.toUnsignedLong(presentationGeneration),
                Integer.toUnsignedLong(sourceRevision), state, provider,
                sourceWidth, sourceHeight, packedWidth, packedHeight)) {
            LimeLog.info("XR: Game source accepted state " + state + ", provider " + provider
                    + ", presentation " + Integer.toUnsignedString(presentationGeneration)
                    + ", revision " + Integer.toUnsignedString(sourceRevision));
            updateGameSourceViews();
            updateGlancePanel();
            scheduleGameSourceReconcile();
        }
    }

    private void invalidateGameSourceProof() {
        confirmedGamePresentationGeneration = 0;
        gameSourceState.invalidateSourceProof();
    }

    private void scheduleGameSourceReconcile() {
        scheduleGameSourceReconcile(0L);
    }

    private void scheduleGameSourceReconcile(long delayMs) {
        if (!gameSourceReconcilePosted && controlTransportOpen()
                && gameProviderV1Supported && currentPresenterMode == PresentationMode.GAME_3D) {
            gameSourceReconcilePosted = true;
            if (delayMs > 0L) {
                liveQualityHandler.postDelayed(gameSourceReconcileRunnable, delayMs);
            } else {
                liveQualityHandler.post(gameSourceReconcileRunnable);
            }
        }
    }

    private boolean canReconcileGameSource() {
        return controlTransportOpen() && gameProviderV1Supported && streamPresentationReady
                && settingsControlsEnabled() && currentPresenterMode == PresentationMode.GAME_3D
                && !modeSwitchInProgress && !liveQualityTransactionBusy()
                && pendingDecoderTransitionMode == null && !clientSbsHdrTransitionInProgress
                && !gameSourceState.hasWidenFailure();
    }

    private void reconcileGameSource() {
        if (!canReconcileGameSource()) {
            return;
        }
        int desiredMode;
        boolean sameModeReconfirmation = false;
        if (confirmedGamePresentationGeneration == 0) {
            // Launch/resume carries no ACK, and an independent host rebuild may retire the last
            // proof. Reconfirm the current wire mode without changing desktop or packed layout.
            desiredMode = gameWireMode;
            sameModeReconfirmation = true;
        } else if (gameSourceState.shouldAutoWiden(true, gameWireMode)) {
            desiredMode = MoonBridge.SBS_MODE_GAME_SBS;
        } else {
            return;
        }
        StreamQualityTuple quality = currentQualityTuple();
        if (!applyLiveStreamQuality(quality, LiveQualityRequestOrigin.GAME_SOURCE, null,
                desiredMode)) {
            if (isGameSourceWidening(desiredMode)) {
                gameSourceState.recordWidenFailure();
            } else if (sameModeReconfirmation
                    && gameSourceReconfirmStartRetries < GAME_SOURCE_RECONFIRM_MAX_START_RETRIES) {
                // Source heartbeats are ignored until a generation is confirmed, so no other
                // event re-runs a reconfirmation whose request never left the client.
                gameSourceReconfirmStartRetries++;
                scheduleGameSourceReconcile(GAME_SOURCE_RECONFIRM_RETRY_DELAY_MS);
            }
            updateGameSourceViews();
        } else {
            gameSourceReconfirmStartRetries = 0;
        }
    }

    private boolean isGameSourceWidening(int desiredMode) {
        return gameWireMode == MoonBridge.SBS_MODE_GAME_MONO
                && desiredMode == MoonBridge.SBS_MODE_GAME_SBS;
    }

    private int gameSourceStatusText() {
        GameStereoSourceState.Status status = gameSourceState.status(
                gameProviderV1Supported, gameWireMode);
        if (status == GameStereoSourceState.Status.READY
                && gameWireMode == MoonBridge.SBS_MODE_GAME_SBS) {
            return R.string.xr_game_3d_active;
        }
        return status == GameStereoSourceState.Status.WAITING
                || status == GameStereoSourceState.Status.READY
                ? R.string.xr_game_waiting : R.string.xr_game_showing_2d;
    }

    private int gameSourceDetailText() {
        if (!gameProviderV1Supported) {
            return R.string.xr_game_source_unavailable;
        }
        if (gameSourceState.hasWidenFailure()) {
            return R.string.xr_game_widen_failed;
        }
        GameStereoSourceState.Status status = gameSourceState.status(true, gameWireMode);
        if (status == GameStereoSourceState.Status.READY
                && gameWireMode == MoonBridge.SBS_MODE_GAME_SBS) {
            return R.string.xr_game_source_active;
        }
        if (status == GameStereoSourceState.Status.UNSUPPORTED) {
            return R.string.xr_game_source_unsupported;
        }
        return status == GameStereoSourceState.Status.FALLBACK
                ? R.string.xr_game_source_flat : R.string.xr_game_source_waiting;
    }

    private void updateGameSourceViews() {
        if (gameSourceStatusView != null) {
            gameSourceStatusView.setText(gameSourceStatusText());
        }
        if (gameSourceDetailView != null) {
            gameSourceDetailView.setText(gameSourceDetailText());
            scheduleModeOptionsPanelFit();
        }
    }

    /** Replace the immutable applied/pending snapshot and refresh an open Settings panel. */
    public void setSessionSettingsModel(SessionSettingsModel model) {
        sessionSettingsModel = java.util.Objects.requireNonNull(model, "model");
        if (controlUiState.getVisibleSurface()
                == XrControlUiState.Surface.SESSION_SETTINGS && auxiliaryContentHost != null) {
            sessionSettingsPane.updateSessionSettingsView();
        }
    }

    /** Atomically replace all settings snapshots and update each open control tree only once. */
    public void setSettingsModels(SessionSettingsModel sessionModel,
                                  Map<PresentationMode, ModeStreamQualityModel> qualityModels,
                                  ClientSbsModeSettingsModel clientModel,
                                  boolean applyPending) {
        setSettingsModels(sessionModel, qualityModels, clientModel, applyPending, true);
    }

    /**
     * @param applyPending          anything staged differs from the live connection
     * @param applyRequiresReconnect applying it must tear down and re-establish the stream
     */
    public void setSettingsModels(SessionSettingsModel sessionModel,
                                  Map<PresentationMode, ModeStreamQualityModel> qualityModels,
                                  ClientSbsModeSettingsModel clientModel,
                                  boolean applyPending,
                                  boolean applyRequiresReconnect) {
        this.applyRequiresReconnect = applyRequiresReconnect;
        sessionSettingsModel = java.util.Objects.requireNonNull(sessionModel, "sessionModel");
        java.util.Objects.requireNonNull(qualityModels, "qualityModels");
        modeStreamQualityModels.clear();
        for (PresentationMode mode : PresentationMode.values()) {
            modeStreamQualityModels.put(mode, java.util.Objects.requireNonNull(
                    qualityModels.get(mode), "quality model for " + mode));
        }
        clientSbsModeSettingsModel = java.util.Objects.requireNonNull(clientModel, "clientModel");
        this.reconnectPending = applyPending;
        if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.SESSION_SETTINGS
                && auxiliaryContentHost != null) {
            sessionSettingsPane.updateSessionSettingsView();
        }
        else if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.MODE_OPTIONS) {
            updateModeOptionsView();
        }
        updateGlancePanel();
        updateDockVisibilityPolicy();
    }

    /** Prevent late settings or mode-choice taps after stream teardown begins. */
    public void setSessionControlsEnabled(boolean enabled) {
        sessionControlsEnabled = enabled;
        updateSessionControlAvailability();
    }

    /** Live setting transactions keep the picture and Disconnect available. */
    public void setSettingsTransactionPending(boolean pending) {
        if (settingsTransactionPending == pending) return;
        settingsTransactionPending = pending;
        updateSessionControlAvailability();
        if (!pending) {
            schedulePanelRateReconcile();
            scheduleGameSourceReconcile();
        }
    }

    public void setSettingsRequestedPresentationMode(PresentationMode mode) {
        settingsRequestedPresentationMode = mode;
    }

    private boolean settingsControlsEnabled() {
        return sessionControlsEnabled && !settingsTransactionPending;
    }

    private void updateSessionControlAvailability() {
        boolean enabled = settingsControlsEnabled();
        sessionSettingsPane.setChoiceGroupsAndDefaultsEnabled(enabled);
        if (moviePictureFormatChoiceGroup != null) {
            moviePictureFormatChoiceGroup.setEnabled(enabled && streamPresentationReady
                    && !modeSwitchInProgress && !liveQualityTransactionBusy());
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
        sessionSettingsPane.setBitrateControlsEnabled(enabled);
        if (modeDefaultsButton != null) {
            modeDefaultsButton.setEnabled(enabled);
        }
        updateCinemaOptionsView();
        for (BarItem item : barItems) {
            if (item.selectsMode != null && item.tapTarget != null) {
                item.setEnabled(enabled && streamPresentationReady
                        && XrPresentationPolicy.isPresentationModeSupported(
                                item.selectsMode, hostControlExtensionsSupported));
            }
        }
        updateGlancePanel();
        revealDockTemporarily();
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
        for (PresentationMode mode : PresentationMode.values()) {
            ModeStreamQualityModel.Builder builder = ModeStreamQualityModel.builder(
                    tuple, tuple, tuple, mode == PresentationMode.NORMAL);
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
        return new ClientSbsModeSettingsModel(
                ClientSbsModeSettingsModel.selectBucket(prefConfig.width, prefConfig.height),
                "GPU-only · initializes on first use");
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

        // A saved Host AI preference can be correct from frame 1. Switching to a stereo
        // mode changes BOTH the compositor split AND the
        // frame the host sends — Host SBS AI -> a packed 2W' x H' side-by-side frame (capped to
        // the encoder max), Client SBS -> on-device depth packed at the negotiated stream size.
        // selectMode
        // re-pins the surface to the target frame size (see setHostSurfaceSize/setClientSbsSurfaceSize).
        //
        // Every mode's selected W x H defines the eye/view aspect.
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
        requestOpaqueVideoBlending(surfaceEntity);

        // Frame 1 must target the exact startup buffer: codec-capped packed Host AI, or ordinary
        // W x H for every other mode.
        int[] initialPixelDimensions = XrPresentationPolicy.initialSurfacePixelDimensions(
                currentPresenterMode, prefConfig.width, prefConfig.height, hostSbsVideoFormat);
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
                        if (!surfaceEntityUsable()) {
                            return;
                        }
                        surfaceEntity.setPose(XrPanelPlacement.clampToMinDistance(proposedPose));
                    }

                    @Override
                    public void onMoveEnd(Entity entity, Ray currentRay, Pose proposedPose,
                                          float scale, Entity updatedParent) {
                        if (!surfaceEntityUsable()) {
                            return;
                        }
                        surfaceEntity.setPose(XrPanelPlacement.clampToMinDistance(proposedPose));
                        repositionStatsPanel();
                    }
                });
        surfaceEntity.addComponent(movable);

        // The resizable affordance lets the user grab a corner to scale. Keep the aspect ratio
        // fixed so the SBS halves stay aligned, and apply the new size to the quad when resize ends.

        resizable = ResizableComponent.create(session, (ResizeEvent event) -> {
            if (!surfaceEntityUsable()) {
                return;
            }
            if (event.getResizeState() == ResizeEvent.ResizeState.END) {
                FloatSize3d ns = event.getNewSize();
                panelHeightMeters = XrViewStateStore.clampHeight(ns.getHeight());
                surfaceEntity.setShape(
                        new SurfaceEntity.Shape.Quad(new FloatSize2d(
                                panelHeightMeters * aspectFor(currentPresenterMode),
                                panelHeightMeters)));
                // Keep the control bar glued beneath the (now resized) quad.
                repositionControlBar(panelHeightMeters);
                viewStateStore.saveHeight(cinemaController.durableHeightMeters());
            }
        });
        resizable.setFixedAspectRatioEnabled(true);
        applyResizeBounds(aspectFor(currentPresenterMode));
        surfaceEntity.addComponent(resizable);

        // Since the 2D main panel is hidden, the Android XR system orbiter (with its Close button)
        // isn't available, so we float our own control bar below the video — a row of icon+label
        // tiles, mirroring a virtual-desktop control strip. The mode tiles form a single-select
        // group; Settings, Cinema, Stats, and Disconnect stay directly
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

    /** Requests opaque composition for opaque video pixels; runtime application is unobservable. */
    @SuppressLint("RestrictedApi")
    static void requestOpaqueVideoBlending(SurfaceEntity target) {
        // beta02 exposes these accessors at the Java level but restricts them to its library group.
        // Its getter only returns a cached preference: the native runtime can reject the request
        // without throwing, so there is no success result to infer here. Keep the best-effort
        // request for runtimes that support it; entity alpha still owns transition visibility.
        try {
            target.setMediaBlendingMode(SurfaceEntity.MediaBlendingMode.OPAQUE);
        } catch (RuntimeException | LinkageError error) {
            LimeLog.warning("XR: opaque video blending request failed: " + error);
        }
    }

    /**
     * Build the fixed control panel below the video quad. Mode buttons share one level panel; the
     * reusable contextual row uses one separate tilted panel directly beneath it.
     *
     * @param videoHeightMeters the quad's height, used to place the bar just beneath it.
     */
    private void buildControlBar(float videoHeightMeters) {
        BarItem normal = new BarItem(
                activity.getString(R.string.xr_bar_2d),
                R.drawable.ic_xr_mode_normal, PresentationMode.NORMAL);
        BarItem hostSbsAi = new BarItem(activity.getString(R.string.xr_bar_host_ai_3d),
                R.drawable.ic_xr_mode_host_sbs, PresentationMode.HOST_SBS_AI);
        BarItem clientSbsAi = new BarItem(activity.getString(R.string.xr_bar_client_ai_3d),
                R.drawable.ic_xr_mode_client_sbs, PresentationMode.CLIENT_SBS_AI);
        BarItem game = new BarItem(activity.getString(R.string.xr_bar_game_3d),
                R.drawable.ic_xr_mode_game_3d, PresentationMode.GAME_3D);
        BarItem movie = new BarItem(activity.getString(R.string.xr_bar_movie_3d),
                R.drawable.ic_xr_mode_movie_3d, PresentationMode.MOVIE_3D);
        BarItem settings = new BarItem(
                activity.getString(R.string.xr_session_settings_title),
                R.drawable.ic_settings, /* selectsMode= */ null);
        BarItem cinemaView = new BarItem(
                activity.getString(R.string.xr_bar_cinema_view),
                R.drawable.ic_xr_cinema_view, /* selectsMode= */ null);
        cinemaView.hasOptions = true;
        BarItem stats = new BarItem(
                activity.getString(R.string.xr_bar_stats),
                R.drawable.ic_xr_diagnostics, /* selectsMode= */ null);
        BarItem dump = null;
        if (BuildConfig.DEBUG) {
            dump = new BarItem(
                    activity.getString(R.string.xr_bar_dump),
                    R.drawable.ic_xr_dump, /* selectsMode= */ null);
        }
        BarItem disconnect = new BarItem(
                activity.getString(R.string.game_menu_disconnect),
                R.drawable.ic_xr_disconnect, /* selectsMode= */ null);
        normal.onTap = () -> onModeTileTapped(normal);
        hostSbsAi.onTap = () -> onModeTileTapped(hostSbsAi);
        clientSbsAi.onTap = () -> onModeTileTapped(clientSbsAi);
        game.onTap = () -> onModeTileTapped(game);
        movie.onTap = () -> onModeTileTapped(movie);
        settings.onTap = this::toggleSessionSettings;
        cinemaView.onTap = this::onCinemaTileTapped;
        stats.onTap = this::onStatsTileTapped;
        if (dump != null) {
            dump.onTap = this::requestHostDebugDump;
        }
        disconnect.onTap = this::requestDisconnect;
        settingsItem = settings;
        cinemaItem = cinemaView;
        statsItem = stats;
        dumpItem = dump;

        barItems.clear();
        barItems.add(normal);
        barItems.add(hostSbsAi);
        barItems.add(clientSbsAi);
        barItems.add(game);
        barItems.add(movie);
        barItems.add(settings);
        barItems.add(cinemaView);
        barItems.add(stats);
        barItems.add(disconnect);
        if (dump != null) {
            barItems.add(dump);
        }

        // One panel hosting a horizontal row of clickable tiles — like a normal toolbar. This is what
        // makes the platform draw the per-tile gaze highlight: a single panel whose View hierarchy
        // holds multiple clickable views highlights each one (the way several FABs on a 2D screen do),
        // whereas one interactable child PanelEntity per tile did NOT. Each tile handles its own tap.
        FrameLayout panelRoot = new FrameLayout(activity);
        setSolidPanelCanvas(panelRoot);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        boolean prevWasMode = false;
        boolean first = true;
        for (BarItem item : barItems) {
            boolean isMode = item.selectsMode != null;
            // Divider between the five presentation modes and the direct stream actions.
            if (!first && prevWasMode && !isMode) {
                bar.addView(makeDivider());
            }
            View tile = buildBarItemView(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            int m = dimen(R.dimen.xr_space_xs);
            lp.setMargins(m, m, m, m);
            bar.addView(tile, lp);
            item.root = tile;
            if (isMode) {
                item.setEnabled(streamPresentationReady && settingsControlsEnabled()
                        && XrPresentationPolicy.isPresentationModeSupported(
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
        revealButton.setBackgroundResource(R.drawable.xr_dock_reveal_background);
        revealButton.setText("\u25B4");
        setTextSize(revealButton, R.dimen.xr_text_title);
        revealButton.setAllCaps(false);
        // Keep a generous gaze target even when the active mode/status label is short.
        revealButton.setMinWidth(dimen(R.dimen.xr_dock_reveal_min_width));
        revealButton.setMinHeight(dimen(R.dimen.xr_dock_reveal_min_height));
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
        dockRevealPill.setPadding(dimen(R.dimen.xr_space_xl),
                dimen(R.dimen.xr_space_lg), dimen(R.dimen.xr_space_xl),
                dimen(R.dimen.xr_space_lg));
        dockRevealPill.setVisibility(View.GONE);
        // Gaze/focus enlarges only the pill. Keep the first press path for XR input that focuses
        // on the first pinch without delivering a click, plus ordinary keyboard/controller clicks.
        configureDockRevealInteractions(dockRevealPill, this::revealDockTemporarily,
                this::onDockRevealViewAttentionChanged, dockRevealViewAttentionState);
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        panelRoot.addView(dockRevealPill, pillParams);

        // Bake the initial highlights into the views before the panel is created.
        statsVisible = prefConfig.enablePerfOverlay;
        if (statsVisible) {
            controlUiState.showStats();
        }
        updateModeSelection();

        // Width scales with the tile count so each tile stays square (tile size = bar height),
        // plus a little for the divider — adding tiles widens the bar instead of squeezing them.
        XrControlPanelLayout layout = controlBarLayout(videoHeightMeters);
        barPanel = PanelEntity.create(
                session, panelRoot, new FloatSize2d(layout.widthMeters, layout.heightMeters),
                "xr-control-bar", barPose(videoHeightMeters), surfaceEntity);
        XrPanelViews.configureSolidPanel(barPanel);
        barFullRasterSize = barPanel.getSizeInPixels();
        if (barFullRasterSize != null) {
            barRasterWidthPixels = barFullRasterSize.getWidth();
            barRasterHeightPixels = barFullRasterSize.getHeight();
        }
        barPanel.setEnabled(true);
        dockGazeTracker = new XrDockGazeTracker(activity, session, barPanel,
                this::onDockGazeChanged);

        // The contextual row is a single panel, not one entity per control. It can therefore tilt
        // toward the face independently while its ordinary child Views retain native gaze taps.
        modeOptionsHost = new FrameLayout(activity);
        setSolidPanelCanvas(modeOptionsHost);
        modeOptionsHeightMeters = XrPanelPlacement.MODE_OPTIONS_MIN_HEIGHT_METERS;
        modeOptionsBaseRasterHeightPixels = 0;
        modeOptionsRasterHeightPixels = 0;
        modeOptionsPanel = PanelEntity.create(
                session, modeOptionsHost,
                new FloatSize2d(layout.widthMeters, modeOptionsHeightMeters),
                "xr-mode-options", modeOptionsPose(videoHeightMeters), surfaceEntity);
        XrPanelViews.configureSolidPanel(modeOptionsPanel);
        modeOptionsPanel.setEnabled(false);

        if (statsVisible) {
            statsUi.createStatsPanel(videoHeightMeters);
        }
        createAuxiliaryPanel(videoHeightMeters);
        applyControlUiState(false, "initial");
        createDepthStatusPanel(videoHeightMeters);
        createTransientMessagePanel();
        glanceStrip.createGlancePanel(videoHeightMeters);
        updateGlancePanel();
        updateDockVisibilityPolicy();
    }

    /** Create the left-side entity used by shared Session Settings. */
    private void createAuxiliaryPanel(float videoHeightMeters) {
        auxiliaryContentHost = new FrameLayout(activity);
        setSolidPanelCanvas(auxiliaryContentHost);
        auxiliaryPanel = PanelEntity.create(
                session, auxiliaryContentHost,
                new FloatSize2d(AUXILIARY_WIDTH_METERS, AUXILIARY_HEIGHT_METERS),
                "xr-session-controls", statsPose(videoHeightMeters), surfaceEntity);
        XrPanelViews.configureSolidPanel(auxiliaryPanel);
        auxiliaryPanel.setEnabled(false);
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
        updateDockGazeTracking();
        cinemaController.applyCinemaBackground();
        devicePerformanceSampler.resetCpuBaseline();
        glanceStrip.reconcileGlanceLoadTicker();
        reconcileClientOptionsStatusTicker();
        // A hover that began before the stop is not replayed; focus is read from the pill itself.
        dockRevealViewAttentionState.focused = dockCollapsed && dockRevealPill != null
                && dockRevealPill.getVisibility() == View.VISIBLE && dockRevealPill.isFocused();
        dockRevealViewAttention = dockRevealViewAttentionState.active();
        updateDockRevealAttention();
    }

    /** Stop all passive load sampling while the streaming Activity is backgrounded. */
    public void onHostActivityStopped() {
        cinemaController.restoreCinemaBackground();
        hostActivityStarted = false;
        updateDockGazeTracking();
        resetDockRevealAnimation();
        dockRevealViewAttentionState.clear();
        dockRevealViewAttention = false;
        glanceStrip.stopLoadTicker();
        modeOptionsStatusHandler.removeCallbacks(refreshClientOptionsStatus);
        devicePerformanceSampler.resetCpuBaseline();
    }

    private void reconcileClientOptionsStatusTicker() {
        modeOptionsStatusHandler.removeCallbacks(refreshClientOptionsStatus);
        if (hostActivityStarted && !presenterDestroyed && isClientOptionsOpen()) {
            modeOptionsStatusHandler.postDelayed(refreshClientOptionsStatus, 1000L);
        }
    }

    private void updateGlancePanel() {
        updateHostDebugDumpAvailability();
        if (!glanceStrip.isShowing()) {
            return;
        }
        String pcName = activity.getIntent().getStringExtra(Game.EXTRA_PC_NAME);
        String appName = activity.getIntent().getStringExtra(Game.EXTRA_APP_NAME);
        String identity = pcName == null || pcName.isEmpty()
                ? activity.getString(R.string.xr_session_current_pc) : pcName;
        if (appName != null && !appName.isEmpty()) {
            identity += " \u00b7 " + appName;
        }
        glanceStrip.setIdentity(identity);
        glanceStrip.setMode(modeLabel(currentPresenterMode));

        // PreferenceConfiguration is the effective on-wire tuple. The settings model intentionally
        // retains the user's durable FPS ceiling while panel-follow is temporarily below it.
        StreamQualityTuple live = currentQualityTuple();
        glanceStrip.setStream(XrStatsFormat.formatGlanceStream(activity,
                live.resolution, glanceFrameRateText(live.frameRate), live.bitrateKbps,
                prefConfig.enableHdr ? activity.getString(R.string.xr_glance_hdr)
                        : activity.getString(R.string.xr_glance_sdr)));

        boolean liveStatus = streamPresentationReady && sessionControlsEnabled && !settingsTransactionPending
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
        else if (settingsTransactionPending || modeSwitchInProgress || pendingDecoderTransitionMode != null || isDepthBusy()) {
            statusText = R.string.xr_glance_switching;
        }
        else if (reconnectPending) {
            statusText = R.string.xr_glance_pending;
        }
        else if (currentPresenterMode == PresentationMode.GAME_3D) {
            statusText = gameSourceStatusText();
        }
        else if (currentPresenterMode == PresentationMode.MOVIE_3D
                && !authoredStereoModeState.isSideBySide(currentPresenterMode)) {
            statusText = R.string.xr_game_showing_2d;
        }
        else {
            statusText = R.string.xr_glance_live;
        }
        glanceStrip.setStatus(statusText,
                liveStatus ? paletteColor(R.color.xr_status_ok) : paletteColor(R.color.xr_status_warn));
        updateDockRevealPill(statusText, liveStatus);
    }

    private int paletteColor(int colorRes) {
        return XrPanelViews.paletteColor(activity, colorRes);
    }

    /** The hosting stream activity, or null when the presenter runs under another Activity. */
    private com.limelight.Game hostGame() {
        return activity instanceof com.limelight.Game ? (com.limelight.Game) activity : null;
    }

    /** The effective on-wire quality currently held by {@code prefConfig}. */
    private StreamQualityTuple currentQualityTuple() {
        return new StreamQualityTuple(
                prefConfig.width + "x" + prefConfig.height,
                XrStatsFormat.formatFrameRate(prefConfig.fps), prefConfig.bitrate);
    }

    private void updateDockRevealPill(int statusText, boolean liveStatus) {
        if (dockRevealPill == null) {
            return;
        }
        String text = activity.getString(R.string.xr_dock_reveal_status,
                modeLabel(currentPresenterMode), activity.getString(statusText));
        boolean textChanged = !android.text.TextUtils.equals(dockRevealPill.getText(), text);
        if (textChanged) {
            dockRevealPill.setText(text);
        }
        dockRevealPill.setTextColor(liveStatus ? paletteColor(R.color.xr_status_ok) : paletteColor(R.color.xr_status_warn));
        if (textChanged && dockCollapsed) {
            applyDockPanelBounds();
        }
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
                dockHoverTarget != null, dockFocusTarget != null);
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
        return streamReady
                && controlsEnabled
                && visibleSurface == XrControlUiState.Surface.NONE
                && !statsVisible
                && !reconnectPending
                && !modeSwitchInProgress
                && !decoderTransitionPending
                && !depthBusy
                && !dockHovered
                && !dockFocused;
    }

    static boolean shouldRevealCollapsedDock(DockRevealInteraction interaction) {
        return interaction == DockRevealInteraction.EXPLICIT_CLICK
                || interaction == DockRevealInteraction.PRESS_DOWN;
    }

    /** Install all Galaxy XR activation paths so the collapsed pill never needs a second pinch. */
    static void configureDockRevealInteractions(View pill, Runnable revealAction,
                                               Consumer<Boolean> attentionAction) {
        configureDockRevealInteractions(pill, revealAction, attentionAction,
                new DockRevealViewAttentionState());
    }

    static void configureDockRevealInteractions(View pill, Runnable revealAction,
                                               Consumer<Boolean> attentionAction,
                                               DockRevealViewAttentionState attention) {
        java.util.Objects.requireNonNull(pill, "pill");
        java.util.Objects.requireNonNull(revealAction, "revealAction");
        java.util.Objects.requireNonNull(attentionAction, "attentionAction");
        java.util.Objects.requireNonNull(attention, "attention");
        // Hover listeners run before View updates isHovered(). Track both sources independently
        // so losing focus cannot shrink the target while gaze is still on it, or vice versa.
        pill.setOnHoverListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_HOVER_ENTER:
                case android.view.MotionEvent.ACTION_HOVER_MOVE:
                    attention.hovered = true;
                    attentionAction.accept(attention.active());
                    break;
                case android.view.MotionEvent.ACTION_HOVER_EXIT:
                    attention.hovered = false;
                    attentionAction.accept(attention.active());
                    break;
                default:
                    break;
            }
            return false;
        });
        pill.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    && shouldRevealCollapsedDock(DockRevealInteraction.PRESS_DOWN)) {
                revealAction.run();
            }
            return false;
        });
        pill.setOnFocusChangeListener((v, hasFocus) -> {
            attention.focused = hasFocus;
            attentionAction.accept(attention.active());
        });
        pill.setOnClickListener(v -> {
            if (shouldRevealCollapsedDock(DockRevealInteraction.EXPLICIT_CLICK)) {
                revealAction.run();
            }
        });
    }

    private void onDockRevealViewAttentionChanged(boolean active) {
        if (BuildConfig.DEBUG && dockRevealViewAttention != active) {
            LimeLog.info("XR: dock pill View attention=" + active);
        }
        dockRevealViewAttention = active;
        updateDockRevealAttention();
    }

    private void onDockGazeChanged(boolean active) {
        if (BuildConfig.DEBUG && dockRevealGazeAttention != active) {
            LimeLog.info("XR: dock pill gaze attention=" + active);
        }
        dockRevealGazeAttention = active;
        updateDockRevealAttention();
    }

    private void updateDockRevealAttention() {
        if (dockRevealViewAttention || dockRevealGazeAttention) {
            dockVisibilityHandler.removeCallbacks(shrinkDockRevealRunnable);
            dockRevealShrinkPending = false;
            animateDockRevealAttention(true);
        }
        else if (dockCollapsed && hostActivityStarted && !presenterDestroyed
                && dockRevealTargetScale != 1.0f
                && !dockRevealShrinkPending) {
            // Keep the enlarged target through brief gaze slips. Repeated loss observations must
            // not restart this timer and postpone a deliberate look away indefinitely.
            dockRevealShrinkPending = dockVisibilityHandler.postDelayed(shrinkDockRevealRunnable,
                    DOCK_REVEAL_SHRINK_DELAY_MS);
        }
    }

    private void shrinkDockRevealIfUnattended() {
        dockRevealShrinkPending = false;
        if (!dockRevealViewAttention && !dockRevealGazeAttention) {
            animateDockRevealAttention(false);
        }
    }

    private void updateDockGazeTracking() {
        if (dockGazeTracker != null) {
            dockGazeTracker.setActive(dockCollapsed && hostActivityStarted && !presenterDestroyed);
        }
    }

    private void animateDockRevealAttention(boolean active) {
        if (!dockCollapsed || !hostActivityStarted || presenterDestroyed
                || barPanel == null || barPanel.isDisposed()) {
            if (BuildConfig.DEBUG && active) {
                LimeLog.info("XR: dock pill animation ignored: collapsed=" + dockCollapsed
                        + " started=" + hostActivityStarted + " destroyed=" + presenterDestroyed);
            }
            return;
        }
        float targetScale = active ? DOCK_REVEAL_ATTENTION_SCALE : 1.0f;
        if (dockRevealTargetScale == targetScale) {
            return;
        }
        if (dockRevealAnimator != null) {
            dockRevealAnimator.cancel();
        }
        dockRevealTargetScale = targetScale;
        boolean growing = targetScale > dockRevealScale;
        dockRevealAnimator = ValueAnimator.ofFloat(dockRevealScale, targetScale);
        dockRevealAnimator.setDuration(growing
                ? DOCK_REVEAL_EXPAND_DURATION_MS : DOCK_REVEAL_SHRINK_DURATION_MS);
        // A fast lift with a small settle feels responsive to gaze. Shrink monotonically to keep
        // the resting hit target intact, including reentry during the expansion's overshoot.
        dockRevealAnimator.setInterpolator(growing
                ? DOCK_REVEAL_EXPAND_INTERPOLATOR : DOCK_REVEAL_SHRINK_INTERPOLATOR);
        dockRevealAnimator.addUpdateListener(animation ->
                setDockRevealScale((float) animation.getAnimatedValue()));
        if (BuildConfig.DEBUG) {
            LimeLog.info("XR: dock pill animation target=" + targetScale);
            dockRevealAnimator.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!cancelled && barPanel != null && !barPanel.isDisposed()) {
                        Vector3 scale = barPanel.getNonUniformScale(Space.PARENT);
                        LimeLog.info("XR: dock pill animation complete: value=" + dockRevealScale
                                + " entityScale=" + (scale != null ? scale.getX() : "unknown"));
                    }
                }
            });
        }
        dockRevealAnimator.start();
    }

    private void setDockRevealScale(float scale) {
        if (dockRevealScale == scale) {
            return;
        }
        dockRevealScale = scale;
        if (barPanel != null && !barPanel.isDisposed()) {
            // Scale the cropped entity, not just its View: the physical gaze/tap target must grow
            // with the artwork, without clipping it or reallocating its surface on every frame.
            barPanel.setScale(scale);
        }
    }

    private void resetDockRevealAnimation() {
        dockVisibilityHandler.removeCallbacks(shrinkDockRevealRunnable);
        dockRevealShrinkPending = false;
        if (dockRevealAnimator != null) {
            dockRevealAnimator.cancel();
            dockRevealAnimator = null;
        }
        dockRevealTargetScale = 1.0f;
        setDockRevealScale(1.0f);
    }

    private void setDockCollapsed(boolean collapsed) {
        if (!collapsed || !dockCollapsed) {
            resetDockRevealAnimation();
            dockRevealViewAttentionState.clear();
            dockRevealViewAttention = false;
            dockRevealGazeAttention = false;
        }
        dockCollapsed = collapsed;
        if (controlBarRow != null) {
            controlBarRow.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (dockRevealPill != null) {
            dockRevealPill.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }
        glanceStrip.resetAlpha();
        applyDockPanelBounds();
        updateDockGazeTracking();
    }

    /** Keep the opaque collapsed canvas tightly fitted to its existing reveal pill. */
    private void applyDockPanelBounds() {
        if (barPanel == null || barPanel.isDisposed()) {
            return;
        }
        if (barFullRasterSize == null) {
            barFullRasterSize = barPanel.getSizeInPixels();
        }
        if (barFullRasterSize == null || barFullRasterSize.getWidth() <= 0
                || barFullRasterSize.getHeight() <= 0) {
            return;
        }
        int width = barFullRasterSize.getWidth();
        int height = barFullRasterSize.getHeight();
        if (dockCollapsed && dockRevealPill != null) {
            dockRevealPill.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
            width = Math.max(1, Math.min(width, dockRevealPill.getMeasuredWidth()));
            height = Math.max(1, Math.min(height, dockRevealPill.getMeasuredHeight()));
        }
        // SceneCore derives meters from this raster at its fixed pixel density. Calling setSize
        // too would overwrite these bounds after converting the physical dimensions back to pixels.
        // Keeping the entity scale unchanged preserves the pill's apparent size while cropping.
        if (width != barRasterWidthPixels || height != barRasterHeightPixels) {
            barPanel.setSizeInPixels(new IntSize2d(width, height));
            barRasterWidthPixels = width;
            barRasterHeightPixels = height;
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
        if (!settingsControlsEnabled() || !streamPresentationReady || modeSwitchInProgress || item.selectsMode == null) {
            return;
        }

        // A tap on the active mode cancels a debounced reconnect to a different mode.
        if (settingsRequestedPresentationMode != null && item.selectsMode == currentPresenterMode) {
            settingsRequestedPresentationMode = null;
            controlActionListener.onPresentationModeCommitted(currentPresenterMode);
        }
        if (!XrPresentationPolicy.isPresentationModeSupported(
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
            selectMode(item);
        }
    }

    private void toggleSessionSettings() {
        revealDockTemporarily();
        controlUiState.toggle(XrControlUiState.Surface.SESSION_SETTINGS);
        applyControlUiState(true, "session settings");
    }

    private void requestDisconnect() {
        revealDockTemporarily();
        // Navigation must remain available even if session settings could not be initialized.
        if (!controlActionListener.onDisconnectRequested()
                && activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).disconnectFromXrControls();
        }
    }

    /**
     * Host AI needs a ready depth pipeline; Game diagnostics also cover waiting/missing depth.
     * Movie is already-packed application content, while Normal and Client AI have no host
     * depth result to capture. Transitions are excluded so one tap cannot be attributed to two
     * different stream geometries or pipeline generations.
     */
    static boolean isHostDebugDumpAvailable(
            PresentationMode mode, boolean streamReady, boolean controlsEnabled,
            boolean transitionInProgress, boolean hostDepthReady, boolean gameProviderSupported) {
        return ((mode == PresentationMode.HOST_SBS_AI && hostDepthReady)
                || (mode == PresentationMode.GAME_3D && gameProviderSupported))
                && streamReady
                && controlsEnabled
                && !transitionInProgress;
    }

    private void onCinemaTileTapped() {
        if (!settingsControlsEnabled() || !controlTransportOpen()) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!shouldAcceptControlToggle(now, lastCinemaTileTapMs)) {
            return;
        }
        lastCinemaTileTapMs = now;
        if (!cinemaController.isExpanded()) {
            onCinemaActionTapped();
            return;
        }
        controlUiState.toggle(XrControlUiState.Surface.CINEMA_OPTIONS);
        applyControlUiState(true, "cinema options");
    }

    private void onCinemaActionTapped() {
        if (!settingsControlsEnabled() || !controlTransportOpen()) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!shouldAcceptControlToggle(now, lastCinemaActionTapMs)) {
            return;
        }
        lastCinemaActionTapMs = now;
        if (surfaceEntity == null || surfaceEntity.isDisposed()) {
            cinemaController.restoreCinemaBackground();
            cinemaController.resetViewState();
            if (cinemaItem != null) {
                cinemaItem.setSelected(false);
            }
            updateCinemaOptionsView();
            return;
        }
        revealDockTemporarily();
        if (cinemaController.isExpanded()) {
            cinemaController.restoreCinemaBackground();
            cinemaController.restoreCinemaView();
            if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.CINEMA_OPTIONS) {
                controlUiState.close();
                applyControlUiState(true, "exit cinema");
            }
        }
        else {
            cinemaController.applyCinemaViewPreset();
            cinemaController.applyCinemaBackground();
        }
        if (cinemaItem != null) {
            cinemaItem.setSelected(cinemaController.isExpanded());
        }
        updateCinemaOptionsView();
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
        if (!showStats) {
            statsUi.cancelStatsPanelFit();
        }
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

        if (showStats && statsUi.panel() == null && session != null) {
            statsUi.createStatsPanel(panelHeightMeters);
            repositionStatsPanel();
        }
        if (statsUi.panel() != null && !statsUi.panel().isDisposed()) {
            statsUi.panel().setEnabled(showStats);
        }
        if (statsChanged && notifyStatsListener && statsVisibilityListener != null) {
            statsVisibilityListener.onStatsVisibilityChanged(showStats);
        }
        if (statsChanged) {
            reconcileHostSbsTelemetrySubscription();
        }

        boolean showModeOptions = visible == XrControlUiState.Surface.MODE_OPTIONS
                || visible == XrControlUiState.Surface.CINEMA_OPTIONS;
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
        if (hostActivityStarted && isClientOptionsOpen()) {
            modeOptionsStatusHandler.postDelayed(refreshClientOptionsStatus, 1000L);
        }

        boolean showAuxiliary = visible == XrControlUiState.Surface.SESSION_SETTINGS;
        if (showAuxiliary && auxiliaryContentHost != null) {
            renderAuxiliaryContent();
        }
        else if (!showAuxiliary) {
            sessionSettingsPane.clearSessionSettingsReferences();
        }
        if (auxiliaryPanel != null && !auxiliaryPanel.isDisposed()) {
            if (showAuxiliary) {
                updatePanelPose(auxiliaryPanel, sessionSettingsPose(panelHeightMeters));
            }
            auxiliaryPanel.setEnabled(showAuxiliary);
        }

        if (settingsItem != null) {
            settingsItem.setSelected(visible == XrControlUiState.Surface.SESSION_SETTINGS);
        }
        if (cinemaItem != null) {
            cinemaItem.setSelected(cinemaController.isExpanded());
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
        if (cinemaItem != null) {
            cinemaItem.setOptionsOpen(controlUiState.getVisibleSurface()
                    == XrControlUiState.Surface.CINEMA_OPTIONS);
        }
    }

    private void renderModeOptions() {
        if (modeOptionsHost == null) {
            return;
        }
        clearModeOptionsReferences();
        if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.CINEMA_OPTIONS) {
            renderCinemaOptions();
            return;
        }
        PresentationMode mode;
        try {
            mode = PresentationMode.valueOf(controlUiState.getModeOptionsId());
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

        LinearLayout header = createContextualOptionsHeader();
        addContextualOptionsHeading(header, modeLabel(mode), mode == currentPresenterMode);
        switch (mode) {
            case NORMAL:
                addModeStatus(header, activity.getString(R.string.xr_mode_normal_source),
                        activity.getString(R.string.xr_mode_normal_detail));
                break;
            case GAME_3D:
                addModeStatus(header, activity.getString(R.string.xr_mode_game_source),
                        activity.getString(R.string.xr_mode_game_detail));
                break;
            case MOVIE_3D:
                addModeStatus(header, activity.getString(R.string.xr_mode_movie_source),
                        activity.getString(R.string.xr_mode_movie_detail));
                break;
            case HOST_SBS_AI:
                addModeStatus(header, activity.getString(R.string.xr_mode_host_ai_description),
                        hostDepthStatusText());
                break;
            case CLIENT_SBS_AI:
                addModeStatus(header, activity.getString(R.string.xr_mode_client_ai_description),
                        clientSbsRuntimeStatus(clientSbsModeSettingsModel));
                break;
            default:
                break;
        }
        root.addView(header);

        if (mode == PresentationMode.MOVIE_3D) {
            addMovieModeOptions(root);
        } else if (mode == PresentationMode.GAME_3D) {
            LinearLayout sourceCard = createModeSourceCard(root,
                    activity.getString(gameSourceStatusText()));
            gameSourceStatusView = (TextView) sourceCard.getChildAt(0);
            gameSourceDetailView = controlText(activity.getString(gameSourceDetailText()),
                    R.dimen.xr_text_emphasis, paletteColor(R.color.xr_text_secondary));
            sourceCard.addView(gameSourceDetailView);
        }

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
        if (mode == PresentationMode.CLIENT_SBS_AI) {
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

        attachContextualOptions(root);
    }

    private void renderCinemaOptions() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dimen(R.dimen.xr_space_lg);
        root.setPadding(padding, dimen(R.dimen.xr_space_md),
                padding, dimen(R.dimen.xr_space_md));
        LinearLayout header = createContextualOptionsHeader();
        addContextualOptionsHeading(header, activity.getString(R.string.xr_bar_cinema_view),
                cinemaController.isExpanded());
        root.addView(header);

        LinearLayout environmentCard = card(padding);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dimen(R.dimen.xr_space_md);
        root.addView(environmentCard, cardParams);

        TextView title = controlText(activity.getString(R.string.xr_cinema_environment),
                R.dimen.xr_text_emphasis, paletteColor(R.color.xr_text_primary));
        title.setPadding(0, 0, 0, dimen(R.dimen.xr_space_sm));
        environmentCard.addView(title);
        cinemaEnvironmentChoiceGroup = new XrChoiceGroup(activity);
        XrChoiceGroup choices = cinemaEnvironmentChoiceGroup;
        cinemaEnvironmentChoiceGroup.setChoices(
                activity.getResources().getTextArray(R.array.xr_cinema_environment_names),
                activity.getResources().getTextArray(R.array.xr_cinema_environment_values),
                prefConfig.cinemaEnvironment.preferenceValue, null,
                value -> choices == cinemaEnvironmentChoiceGroup
                        && onCinemaEnvironmentSelected(value));
        environmentCard.addView(cinemaEnvironmentChoiceGroup, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView hint = controlText(activity.getString(R.string.xr_cinema_environment_hint),
                R.dimen.xr_text_title, paletteColor(R.color.xr_text_secondary));
        hint.setPadding(0, dimen(R.dimen.xr_space_sm), 0, 0);
        environmentCard.addView(hint);

        cinemaActionButton = new Button(activity);
        styleControlButton(cinemaActionButton);
        cinemaActionButton.setText(R.string.xr_cinema_exit);
        cinemaActionButton.setOnClickListener(ignored -> {
            if (cinemaController.isExpanded()) {
                onCinemaActionTapped();
            }
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.gravity = Gravity.END;
        actionParams.topMargin = dimen(R.dimen.xr_space_md);
        root.addView(cinemaActionButton, actionParams);
        updateCinemaOptionsView();
        attachContextualOptions(root);
    }

    private boolean onCinemaEnvironmentSelected(String value) {
        if (!cinemaController.isExpanded() || !sessionControlsEnabled || !controlTransportOpen()
                || controlUiState.getVisibleSurface() != XrControlUiState.Surface.CINEMA_OPTIONS) {
            return false;
        }
        PreferenceConfiguration.CinemaEnvironment selected =
                PreferenceConfiguration.CinemaEnvironment.fromPreferenceValue(value);
        if (selected != prefConfig.cinemaEnvironment) {
            prefConfig.cinemaEnvironment = selected;
            PreferenceConfiguration.setCinemaEnvironment(activity, selected);
            cinemaController.restoreCinemaBackground();
            cinemaController.applyCinemaBackground();
        }
        return true;
    }

    private void updateCinemaOptionsView() {
        if (cinemaEnvironmentChoiceGroup != null) {
            cinemaEnvironmentChoiceGroup.setEnabled(settingsControlsEnabled());
            cinemaEnvironmentChoiceGroup.setSelectedValue(
                    prefConfig.cinemaEnvironment.preferenceValue);
        }
        if (cinemaActionButton != null) {
            cinemaActionButton.setEnabled(cinemaController.isExpanded()
                    && sessionControlsEnabled && surfaceEntity != null
                    && !surfaceEntity.isDisposed());
        }
    }

    /** Modes and Cinema share one scrollable contextual panel beneath the unchanged dock. */
    private void attachContextualOptions(LinearLayout root) {
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

    private void addModeQualityControls(LinearLayout root, PresentationMode mode) {
        ModeStreamQualityModel model = modeStreamQualityModels.get(mode);
        if (model == null) {
            return;
        }

        LinearLayout qualityRow = new LinearLayout(activity);
        qualityRow.setOrientation(LinearLayout.HORIZONTAL);
        qualityRow.setGravity(Gravity.TOP);
        qualityRow.setBaselineAligned(false);

        int cardPadding = dimen(R.dimen.xr_space_md);
        LinearLayout resolutionColumn = card(cardPadding);
        TextView resolutionTitle = controlText(
                activity.getString(R.string.title_resolution_list),
                R.dimen.xr_text_title, paletteColor(R.color.xr_text_primary));
        resolutionTitle.setTypeface(resolutionTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        applyTitleIcon(resolutionTitle, R.drawable.ic_xr_resolution);
        resolutionColumn.addView(resolutionTitle);
        modeResolutionSelector = XrResolutionSelector.forModeSubpane(activity);
        modeResolutionSelector.setSelectedResolutionId(model.pendingQuality.resolution);
        modeResolutionSelector.setEnabled(settingsControlsEnabled());
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
        LinearLayout fpsCard = card(cardPadding);
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
        modeFpsLadder.setEnabled(settingsControlsEnabled());
        fpsCard.addView(modeFpsLadder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tuningColumn.addView(fpsCard);

        SessionSettingsModel.Value bitrate = model.get(SessionSettingsModel.Key.BITRATE);
        LinearLayout bitrateCard = card(cardPadding);
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
        modeBitrateControl.setEnabled(settingsControlsEnabled());
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

    private void addModeOptionsFooter(LinearLayout root, PresentationMode mode) {
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setPadding(0, dimen(R.dimen.xr_space_md), 0, 0);

        modeDefaultsButton = compactButton(
                activity.getString(R.string.xr_session_use_session));
        modeDefaultsButton.setEnabled(settingsControlsEnabled());
        modeDefaultsButton.setOnClickListener(v -> controlActionListener
                .onUseSessionModeDefaultsRequested(mode, modeStreamQualityModels.get(mode)));
        footer.addView(modeDefaultsButton);

        root.addView(footer);
    }

    private List<SessionSettingsModel.Choice> choicesOrCurrent(
            SessionSettingsModel.Value value, String choiceId) {
        return XrPanelViews.choicesOrCurrent(value, choiceId);
    }

    private static String qualityChoiceId(SessionSettingsModel.Value value, String fallback) {
        return value.selectedChoiceId != null ? value.selectedChoiceId : fallback;
    }

    private String modeQualityCue(ModeStreamQualityModel model) {
        String source = modeQualitySource(model);
        String live = XrStatsFormat.formatQualityTuple(model.liveQuality);
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

    private LinearLayout createContextualOptionsHeader() {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dimen(R.dimen.xr_space_lg), dimen(R.dimen.xr_space_md),
                dimen(R.dimen.xr_space_lg), dimen(R.dimen.xr_space_md));
        header.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_panel), 1));
        return header;
    }

    private void addContextualOptionsHeading(LinearLayout row, String label, boolean selected) {
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(0, 0, dimen(R.dimen.xr_space_lg), 0);

        TextView title = controlText(label, R.dimen.xr_text_display,
                paletteColor(R.color.xr_text_primary));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        heading.addView(title);

        TextView active = controlText(activity.getString(selected
                        ? R.string.xr_mode_active : R.string.xr_mode_options_title),
                R.dimen.xr_text_title, selected
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
        LinearLayout modelColumn = labeledValue(
                activity.getString(R.string.xr_client_model),
                activity.getString(R.string.xr_depth_model_zipdepth_base),
                paletteColor(R.color.xr_text_primary));
        clientModelNameView = (TextView) modelColumn.getChildAt(1);
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

    private LinearLayout createModeSourceCard(LinearLayout root, String title) {
        LinearLayout card = card(dimen(R.dimen.xr_space_md));
        TextView heading = controlText(title,
                R.dimen.xr_text_title, paletteColor(R.color.xr_text_primary));
        heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        heading.setPadding(0, 0, 0, dimen(R.dimen.xr_space_sm));
        card.addView(heading);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dimen(R.dimen.xr_space_md);
        root.addView(card, params);
        return card;
    }

    private void addMovieModeOptions(LinearLayout root) {
        LinearLayout card = createModeSourceCard(root, activity.getString(R.string.xr_movie_picture_format));
        moviePictureFormatChoiceGroup = buildChoiceGroup(java.util.Arrays.asList(
                new SessionSettingsModel.Choice("2d", activity.getString(R.string.xr_movie_format_2d)),
                new SessionSettingsModel.Choice("half_sbs", activity.getString(R.string.xr_movie_format_half_sbs)),
                new SessionSettingsModel.Choice("full_sbs", activity.getString(R.string.xr_movie_format_full_sbs))),
                moviePictureFormatId(), "", this::onMoviePictureFormatSelected);
        moviePictureFormatChoiceGroup.setEnabled(settingsControlsEnabled() && streamPresentationReady);
        card.addView(moviePictureFormatChoiceGroup);
    }

    private String moviePictureFormatId() {
        switch (authoredStereoModeState.getMoviePictureFormat()) {
            case HALF_SBS: return "half_sbs";
            case FULL_SBS: return "full_sbs";
            default: return "2d";
        }
    }

    private boolean onMoviePictureFormatSelected(String choiceId) {
        if (!controlTransportOpen() || !settingsControlsEnabled() || !streamPresentationReady
                || currentPresenterMode != PresentationMode.MOVIE_3D
                || modeSwitchInProgress || liveQualityTransactionBusy()
                || surfaceEntity == null || surfaceEntity.isDisposed()) {
            return false;
        }
        MoviePictureFormat format;
        switch (choiceId) {
            case "2d": format = MoviePictureFormat.TWO_D; break;
            case "half_sbs": format = MoviePictureFormat.HALF_SBS; break;
            case "full_sbs": format = MoviePictureFormat.FULL_SBS; break;
            default: return false;
        }
        authoredStereoModeState.setMoviePictureFormat(format);
        // This is an explicit interpretation of the existing frame, never a request to resize
        // the Windows desktop, decoder buffer or transport. Half-SBS restores squeezed eyes.
        surfaceEntity.setStereoMode(stereoModeFor(currentPresenterMode));
        reshapeQuadForMode(currentPresenterMode, panelHeightMeters);
        updateGlancePanel();
        revealDockTemporarily();
        return true;
    }

    private void clearModeOptionsReferences() {
        moviePictureFormatChoiceGroup = null;
        gameSourceStatusView = null;
        gameSourceDetailView = null;
        cinemaEnvironmentChoiceGroup = null;
        cinemaActionButton = null;
        renderedModeOptionsMode = null;
        modeResolutionSelector = null;
        modeFpsLadder = null;
        modeBitrateControl = null;
        modeQualityCueView = null;
        modeDefaultsButton = null;
        clientModelNameView = null;
        clientAspectBucketView = null;
        clientRuntimeStatusView = null;
        modeOptionsContentRoot = null;
        modeOptionsFitScheduled = false;
        modeOptionsStatusHandler.removeCallbacks(modeOptionsFitRunnable);
    }

    private void updateModeOptionsView() {
        PresentationMode mode = renderedModeOptionsMode;
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
        modeResolutionSelector.setEnabled(settingsControlsEnabled());
        SessionSettingsModel.Value fps = model.get(SessionSettingsModel.Key.FRAME_RATE);
        String fpsId = qualityChoiceId(fps, model.pendingQuality.frameRate);
        if (!modeFpsLadder.setSelectedChoiceId(fpsId)) {
            configureFpsLadder(mode, fps, model);
        }
        modeFpsLadder.setEnabled(settingsControlsEnabled());
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
        modeBitrateControl.setEnabled(settingsControlsEnabled());
        modeQualityCueView.setText(modeQualityCue(model));
        modeQualityCueView.setTextColor(model.requiresReconnect()
                ? paletteColor(R.color.xr_status_warn) : paletteColor(R.color.xr_text_secondary));
        modeDefaultsButton.setEnabled(settingsControlsEnabled());
        if (moviePictureFormatChoiceGroup != null) {
            moviePictureFormatChoiceGroup.setSelectedValue(moviePictureFormatId());
            moviePictureFormatChoiceGroup.setEnabled(settingsControlsEnabled() && streamPresentationReady
                    && !modeSwitchInProgress && !liveQualityTransactionBusy());
        }
        if (mode == PresentationMode.CLIENT_SBS_AI) {
            updateClientSbsOptionsView();
        }
        scheduleModeOptionsPanelFit();
    }

    private void updateClientSbsOptionsView() {
        ClientSbsModeSettingsModel model = clientSbsModeSettingsModel;
        if (clientModelNameView != null) {
            clientModelNameView.setText(R.string.xr_depth_model_zipdepth_base);
        }
        if (clientAspectBucketView != null) {
            clientAspectBucketView.setText(model.bucket);
        }
        updateClientSbsRuntimeStatusView();
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
                && PresentationMode.CLIENT_SBS_AI.name().equals(
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
        if (currentPresenterMode != PresentationMode.CLIENT_SBS_AI
                && (backend == null || "Initializing".equals(backend)
                || "Unavailable".equals(backend))) {
            return model.status;
        }
        return XrStatsFormat.depthBackendName(backend);
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
                return currentPresenterMode == PresentationMode.HOST_SBS_AI
                        ? activity.getString(R.string.xr_mode_host_waiting)
                        : activity.getString(R.string.xr_mode_starts_when_selected);
        }
    }

    private void renderAuxiliaryContent() {
        auxiliaryContentHost.removeAllViews();
        View content = sessionSettingsPane.buildSessionSettingsView();
        auxiliaryContentHost.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private XrChoiceGroup buildChoiceGroup(List<SessionSettingsModel.Choice> choices,
                                           String selectedChoiceId,
                                           CharSequence fallbackLabel,
                                           XrChoiceGroup.OnChoiceSelectedListener listener) {
        return XrPanelViews.buildChoiceGroup(activity, choices, selectedChoiceId, fallbackLabel,
                listener);
    }

    private LinearLayout labeledValue(String label, String value, int valueColor) {
        return XrPanelViews.labeledValue(activity, label, value, valueColor);
    }

    private TextView controlText(CharSequence text, int textSizeResource, int color) {
        return XrPanelViews.controlText(activity, text, textSizeResource, color);
    }

    private Button compactButton(CharSequence text) {
        return XrPanelViews.compactButton(activity, text);
    }

    private void styleControlButton(Button button) {
        XrPanelViews.styleControlButton(activity, button);
    }

    private String modeLabel(PresentationMode mode) {
        switch (mode) {
            case HOST_SBS_AI:
                return activity.getString(R.string.xr_bar_host_ai_3d);
            case CLIENT_SBS_AI:
                return activity.getString(R.string.xr_bar_client_ai_3d);
            case GAME_3D:
                return activity.getString(R.string.xr_bar_game_3d);
            case MOVIE_3D:
                return activity.getString(R.string.xr_bar_movie_3d);
            default:
                return activity.getString(R.string.xr_bar_2d);
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
        int live = Math.round(XrPresentationPolicy.parseFrameRate(liveFrameRate, prefConfig.fps));
        if (ceiling <= 0 || live <= 0 || live >= ceiling) {
            return liveFrameRate;
        }
        return activity.getString(R.string.xr_glance_frame_rate_capped, liveFrameRate, ceiling);
    }

    /**
     * Bitrate rung suited to this mode's pending resolution, frame rate and codec, or -1 when the
     * codec cannot reach that shape at any offered rung.
     */
    private int recommendedBitrateKbps(PresentationMode mode, ModeStreamQualityModel model) {
        if (model == null || model.pendingQuality == null) {
            return -1;
        }
        int[] size = XrPresentationPolicy.parseResolutionSize(model.pendingQuality.resolution);
        if (size == null) {
            return -1;
        }
        int fps = Math.round(XrPresentationPolicy.parseFrameRate(model.pendingQuality.frameRate, prefConfig.fps));
        boolean packed = XrPresentationPolicy.usesPackedBitrateCost(mode);
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
    private void configureFpsLadder(PresentationMode mode, SessionSettingsModel.Value fps,
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
        XrPanelViews.applyTitleIcon(activity, title, iconRes);
    }

    /**
     * Centered overlay that appears while the host prepares depth and disappears once depth is
     * live. It distinguishes process-wide engine preparation from per-stream GPU setup and is
     * driven entirely by the host's {@link #onDepthStatus} phase pushes.
     */
    private void createDepthStatusPanel(float videoHeightMeters) {
        LinearLayout root = createDepthStatusView();
        depthStatusPanel = PanelEntity.create(
                session, root, new FloatSize2d(DEPTH_STATUS_WIDTH_METERS, DEPTH_STATUS_HEIGHT_METERS),
                "xr-depth-status", depthStatusPose(), surfaceEntity);
        XrPanelViews.configureSolidPanel(depthStatusPanel);
        setDepthStatusVisible(false);
    }

    private LinearLayout createDepthStatusView() {
        LinearLayout root = new LinearLayout(activity);
        // Hide before attachment: SceneCore entity visibility does not stop Android animations.
        // INVISIBLE preserves the hosted view's measured bounds for the next loading message.
        root.setVisibility(View.INVISIBLE);
        depthStatusRoot = root;
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        setSolidPanelCanvas(root);
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

        return root;
    }

    private void setDepthStatusVisible(boolean visible) {
        boolean panelAvailable = depthStatusPanel != null && !depthStatusPanel.isDisposed();
        boolean show = visible && panelAvailable && !presenterDestroyed;
        if (depthStatusRoot != null) {
            // ProgressBar receives aggregated visibility through this ancestor and stops its
            // indeterminate drawable instead of repainting a hidden SceneCore panel every vsync.
            depthStatusRoot.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
        if (panelAvailable) {
            depthStatusPanel.setEnabled(show);
        }
    }

    /** Center the transient status on the video and nudge it toward the viewer as an overlay. */
    private Pose depthStatusPose() {
        return new Pose(new Vector3(0.0f, 0.0f, XrPanelPlacement.BAR_Z_METERS), Quaternion.Identity);
    }

    private void createTransientMessagePanel() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        setSolidPanelCanvas(root);
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
        XrPanelViews.configureSolidPanel(transientMessagePanel);
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
            setDepthStatusVisible(true);
        }
    }

    private boolean isDepthBusy() {
        return depthStatusPhase == 1 || depthStatusPhase == 3;
    }

    private void resetHostDepthStatus() {
        depthStatusHandler.removeCallbacks(showDepthStatusRunnable);
        depthStatusPendingPhase = 0;
        depthStatusPhase = 0;
        setDepthStatusVisible(false);
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
        // yet. Host AI dumps require an affirmative phase-2 signal, not merely "not busy".
        depthStatusPhase = phase;
        if (depthStatusPanel == null) {
            return;
        }
        depthStatusHandler.removeCallbacks(showDepthStatusRunnable);
        if (phase == 1 || phase == 3) {
            depthStatusPendingPhase = phase;
            depthStatusHandler.postDelayed(showDepthStatusRunnable, 600);
        } else {
            setDepthStatusVisible(false);
        }
        if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.MODE_OPTIONS
                && PresentationMode.HOST_SBS_AI.name().equals(controlUiState.getModeOptionsId())) {
            renderModeOptions();
        }
        updateGlancePanel();
        revealDockTemporarily();
    }

    /** Main-thread delivery of an already-parsed immutable host telemetry body. */
    public void onHostSbsTelemetryState(
            HostSbsTelemetrySnapshot snapshot, long receivedAtMs) {
        // Closing Stats also closes this subscription's local ownership. An already queued
        // publication must not rebuild hidden history or trigger stale-packet logging.
        if (!hostSbsTelemetryRequested || !shouldObserveHostSbsTelemetry()) {
            return;
        }
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
        // finish() precedes onStop(), so display callbacks can outlive the user's Disconnect.
        return !controlTransportClosing && !presenterDestroyed
                && (activity instanceof com.limelight.Game
                        ? ((com.limelight.Game) activity).isConnectionUiActive()
                        : !activity.isFinishing() && !activity.isDestroyed());
    }

    /** All ordinary control sends pass through these guards. The sole exception is the final
     * telemetry unsubscribe inside {@link #onConnectionStopping()}, while Game still owns a live
     * native connection. Package visibility keeps the transport boundary directly testable. */
    int sendHostVideoModeControl(int logicalWidth, int logicalHeight, int framerateX100,
                                 int requestId, int bitrateKbps) {
        return sendHostVideoModeControl(
                currentPresenterMode, logicalWidth, logicalHeight,
                framerateX100, requestId, bitrateKbps);
    }

    private int sendHostVideoModeControl(
            PresentationMode requestMode,
            int logicalWidth, int logicalHeight, int framerateX100,
            int requestId, int bitrateKbps) {
        return sendHostVideoModeControl(requestMode, logicalWidth, logicalHeight,
                framerateX100, requestId, bitrateKbps, effectiveWireModeFor(requestMode));
    }

    private int sendHostVideoModeControl(
            PresentationMode requestMode,
            int logicalWidth, int logicalHeight, int framerateX100,
            int requestId, int bitrateKbps, int desiredWireMode) {
        if (!controlTransportOpen() || !atomicPresentationV2Supported) {
            return 0;
        }
        if ((desiredWireMode == MoonBridge.SBS_MODE_GAME_MONO
                || desiredWireMode == MoonBridge.SBS_MODE_GAME_SBS)
                && (!gameProviderV1Supported || requestMode != PresentationMode.GAME_3D)) {
            return 0;
        }
        int[] wireDimensions = XrPresentationPolicy.liveVideoModeWireDimensions(logicalWidth, logicalHeight);
        if (wireDimensions == null) {
            LimeLog.severe("XR: refusing invalid live video-mode geometry "
                    + logicalWidth + "x" + logicalHeight + " for " + requestMode);
            return 0;
        }
        pendingDesiredWireMode = desiredWireMode;
        pendingRequestedSourceWidth = wireDimensions[0];
        pendingRequestedSourceHeight = wireDimensions[1];
        return MoonBridge.sendSetVideoModeV2(
                pendingDesiredWireMode, requestId,
                wireDimensions[0], wireDimensions[1],
                framerateX100, bitrateKbps);
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

    private boolean shouldObserveHostSbsTelemetry() {
        return controlTransportOpen()
                && hostControlExtensionsSupported
                && statsVisible
                && streamPresentationReady
                && currentPresenterMode == PresentationMode.HOST_SBS_AI;
    }

    /**
     * Owns depth-health telemetry only while Stats observes the active, proven Host SBS AI mode.
     * Closing Stats cancels the subscription and chart era; operational host status is independent.
     */
    private void reconcileHostSbsTelemetrySubscription() {
        boolean enable = shouldObserveHostSbsTelemetry();
        if (enable == hostSbsTelemetryRequested) {
            return;
        }

        cancelHostSbsTelemetryRetry(true);
        if (!enable) {
            if (hostSbsTelemetryRequested) {
                sendHostTelemetryControl(
                        false, false, nextHostSbsTelemetryRequestId(),
                        HOST_SBS_TELEMETRY_FOCUSED_INTERVAL_MS);
            }
            hostSbsTelemetryRequested = false;
            hostSbsTelemetryTracker.deactivate();
            return;
        }

        hostSbsTelemetryRequested = true;
        sendHostSbsTelemetrySubscriptionAttempt();
    }

    private void sendHostSbsTelemetrySubscriptionAttempt() {
        if (!hostSbsTelemetryRequested || !shouldObserveHostSbsTelemetry()) {
            return;
        }
        int requestId = nextHostSbsTelemetryRequestId();
        hostSbsTelemetryTracker.activateRequest(requestId);
        int intervalMs = HOST_SBS_TELEMETRY_FOCUSED_INTERVAL_MS;
        int result = sendHostTelemetryControl(
                true, true, requestId, intervalMs);
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
        if (!hostSbsTelemetryRequested || !shouldObserveHostSbsTelemetry()) {
            return;
        }
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
        hostSbsTelemetryTracker.deactivate();
        return wasRequested;
    }

    /**
     * Ends the host telemetry subscription while moonlight-common's control transport is still
     * alive. {@link Game} calls this synchronously before {@code NvConnection.stop()} starts.
     * Repeated calls are local no-ops and delayed UI/retry work cannot reopen the subscription.
     */
    public void onConnectionStopping() {
        cinemaController.restoreCinemaBackground();
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
        liveQualityHandler.removeCallbacks(gameSourceReconcileRunnable);
        gameSourceReconcilePosted = false;
        invalidateGameSourceProof();
        gameSourceState.leaveGame();
        resetVideoModeStateForTeardown();
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
                    HOST_SBS_TELEMETRY_FOCUSED_INTERVAL_MS);
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
     * Rebuilds the XR table from typed snapshots captured on the same decoder tick.
     *
     * @param hdrActive whether the negotiated stream is actually HDR, rather than merely requested
     */
    public void setStats(StreamPerformanceSnapshot stream,
                         Stereo3DRenderer.ClientSbsPerformanceSnapshot clientSbs,
                         boolean hdrActive) {
        statsUi.setStats(stream, clientSbs, hdrActive);
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
        updateModeOptionsIndicators();
        updateGlancePanel();
        revealDockTemporarily();
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
            if (!modeOptionsFitScheduled
                    && modeOptionsFitUnlaidRetries < MODE_OPTIONS_FIT_MAX_UNLAID_RETRIES) {
                modeOptionsFitUnlaidRetries++;
                modeOptionsFitScheduled = true;
                modeOptionsStatusHandler.postDelayed(modeOptionsFitRunnable,
                        MODE_OPTIONS_FIT_UNLAID_RETRY_DELAY_MS);
            }
            return;
        }
        modeOptionsFitUnlaidRetries = 0;
        if (modeOptionsBaseRasterHeightPixels <= 0) {
            modeOptionsBaseRasterHeightPixels = hostHeight;
            modeOptionsRasterHeightPixels = hostHeight;
        }
        modeOptionsContentRoot.measure(
                View.MeasureSpec.makeMeasureSpec(hostWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentHeightPx = modeOptionsContentRoot.getMeasuredHeight()
                + dimen(R.dimen.xr_space_xs);
        int targetHeightPixels = XrPanelPlacement.calculateModeOptionsRasterHeightPixels(
                contentHeightPx, modeOptionsBaseRasterHeightPixels,
                XrPanelPlacement.MODE_OPTIONS_MIN_HEIGHT_METERS, MODE_OPTIONS_MAX_HEIGHT_METERS);
        // Derive metres from the original raster/metre pair. Using the live pair here would make
        // repeated fits compound integer rounding whenever content or modes change.
        float targetHeightMeters = XrPanelPlacement.calculateModeOptionsHeightMeters(
                XrPanelPlacement.MODE_OPTIONS_MIN_HEIGHT_METERS, modeOptionsBaseRasterHeightPixels,
                targetHeightPixels,
                XrPanelPlacement.MODE_OPTIONS_MIN_HEIGHT_METERS, MODE_OPTIONS_MAX_HEIGHT_METERS);
        if (targetHeightPixels == modeOptionsRasterHeightPixels
                && Math.abs(targetHeightMeters - modeOptionsHeightMeters) < 0.001f) {
            return;
        }
        XrControlPanelLayout layout = controlBarLayout(panelHeightMeters);
        modeOptionsRasterHeightPixels = targetHeightPixels;
        modeOptionsHeightMeters = targetHeightMeters;
        // Preserve the existing authored fit sequence. SceneCore beta02's final setSize() also
        // relayouts the hosted raster at its default pixel density; integer rounding can differ
        // slightly from the explicit targetHeightPixels above.
        modeOptionsPanel.setSizeInPixels(new IntSize2d(hostWidth, targetHeightPixels));
        modeOptionsPanel.setSize(new FloatSize2d(layout.widthMeters, targetHeightMeters));
        modeOptionsPanel.setPose(modeOptionsPose(panelHeightMeters));
        modeOptionsHost.requestLayout();
        LimeLog.info(String.format(Locale.US,
                "XR mode options fit: raster=%dx%d contentHeight=%d physicalHeight=%.3fm",
                hostWidth, targetHeightPixels, contentHeightPx, targetHeightMeters));
    }

    /** Local pose of the unchanged, level mode-button panel. */
    private Pose barPose(float videoHeightMeters) {
        XrControlPanelLayout layout = controlBarLayout(videoHeightMeters);
        return new Pose(new Vector3(0.0f, layout.primaryRowCenterY, XrPanelPlacement.BAR_Z_METERS),
                Quaternion.Identity);
    }

    /** Local pose of the independently pitched contextual subpanel. */
    private Pose modeOptionsPose(float videoHeightMeters) {
        XrControlPanelLayout layout = controlBarLayout(videoHeightMeters);
        Vector3 viewer = statsViewerPositionLocal();
        XrPanelPlacement.ModeOptionsPanelPlacement placement = XrPanelPlacement.calculateModeOptionsPanelPlacement(
                layout.primaryRowCenterY, BAR_HEIGHT_METERS, modeOptionsHeightMeters,
                MODE_OPTIONS_GAP_METERS, XrPanelPlacement.BAR_Z_METERS, viewer.getY(), viewer.getZ());
        Quaternion rotation = Quaternion.fromAxisAngle(
                new Vector3(1.0f, 0.0f, 0.0f), placement.pitchDegrees);
        return new Pose(new Vector3(0.0f, placement.centerY, placement.centerZ), rotation);
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
        XrPanelPlacement.StatsPanelPlacement placement = XrPanelPlacement.calculateStatsPanelPlacement(
                videoHeightMeters * aspectFor(currentPresenterMode),
                XrStatsPanel.STATS_WIDTH_METERS, STATS_GAP_METERS, viewer.getX(), viewer.getZ());
        Quaternion rotation = Quaternion.fromAxisAngle(
                new Vector3(0.0f, 1.0f, 0.0f), placement.yawDegrees);
        return new Pose(new Vector3(placement.centerX, placement.centerY, placement.centerZ),
                rotation);
    }

    /** App Settings sits to the video's left and tilts inward toward the viewer. */
    private Pose sessionSettingsPose(float videoHeightMeters) {
        Vector3 viewer = statsViewerPositionLocal();
        XrPanelPlacement.StatsPanelPlacement placement = XrPanelPlacement.calculateLeftPanelPlacement(
                videoHeightMeters * aspectFor(currentPresenterMode),
                AUXILIARY_WIDTH_METERS, STATS_GAP_METERS, viewer.getX(), viewer.getZ());
        Quaternion rotation = Quaternion.fromAxisAngle(
                new Vector3(0.0f, 1.0f, 0.0f), placement.yawDegrees);
        return new Pose(new Vector3(placement.centerX, placement.centerY, placement.centerZ),
                rotation);
    }

    private void repositionStatsPanel() {
        if (statsVisible && statsUi.panel() != null && !statsUi.panel().isDisposed()) {
            updatePanelPose(statsUi.panel(), statsPose(panelHeightMeters));
        }
        XrControlUiState.Surface visible = controlUiState.getVisibleSurface();
        if (auxiliaryPanel != null && !auxiliaryPanel.isDisposed()) {
            if (visible == XrControlUiState.Surface.SESSION_SETTINGS) {
                updatePanelPose(auxiliaryPanel, sessionSettingsPose(panelHeightMeters));
            }
        }
    }

    /** Avoid identical native node transactions during the slow stats/position refresh. */
    static void updatePanelPose(PanelEntity panel, Pose pose) {
        if (panel == null || panel.isDisposed()) {
            return;
        }
        // SceneCore 1.0.0's PARENT pose is its cached local value; it does not query native
        // tracking. Read it instead of keeping another cache so reanchors or other callers
        // cannot leave this comparison stale. Preserve every actual pose change.
        if (!pose.equals(panel.getPose(Space.PARENT))) {
            panel.setPose(pose);
        }
    }

    private XrControlPanelLayout controlBarLayout(float videoHeightMeters) {
        return XrControlPanelLayout.calculate(
                barItems.size(), 1,
                BAR_HEIGHT_METERS, BAR_DIVIDER_METERS, videoHeightMeters, BAR_GAP_METERS);
    }

    /** Enable presentation switching after MediaCodec has rendered the stream's first frame. */
    public void onFirstVideoFrameRendered() {
        if (!controlTransportOpen() || streamPresentationReady) {
            return;
        }

        streamPresentationReady = true;
        reconcileHostSbsTelemetrySubscription();
        for (BarItem item : barItems) {
            if (item.selectsMode != null) {
                item.setEnabled(settingsControlsEnabled() && XrPresentationPolicy.isPresentationModeSupported(
                        item.selectsMode, hostControlExtensionsSupported));
            }
        }
        LimeLog.info("XR: first video frame rendered; presentation switching enabled");
        updateGlancePanel();
        revealDockTemporarily();
        schedulePanelRateReconcile();

        // The initial direct mode is now proven to match a decoded frame. Mark it as the most
        // successful presentation. Client SBS still needs its guarded GL surface handoff.
        if (deferredPresenterMode == PresentationMode.NORMAL) {
            persistPresentationState();
            controlActionListener.onPresentationModeCommitted(currentPresenterMode);
        }
        scheduleGameSourceReconcile();

        PresentationMode modeToRestore = deferredPresenterMode;
        deferredPresenterMode = PresentationMode.NORMAL;
        if (modeToRestore != PresentationMode.NORMAL) {
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
        glanceStrip.reposition(videoHeightMeters);
        updateGlancePanel();
    }

    /**
     * Apply a presentation chosen from the bar. Sets the compositor eye split, drives the host SBS
     * pipeline on/off in Host SBS AI, re-pins the surface to the target
     * frame size, and reshapes the quad to the mode's aspect. The quad's height is preserved; only
     * the width changes (when the aspect changes), so the screen keeps its vertical size.
     */
    private void selectMode(BarItem item) {
        if (!settingsControlsEnabled() || !controlTransportOpen() || !streamPresentationReady || item.selectsMode == null
                || !XrPresentationPolicy.isPresentationModeSupported(
                        item.selectsMode, hostControlExtensionsSupported)
                || surfaceEntity == null
                || surfaceEntity.isDisposed()
                || item.selectsMode == currentPresenterMode || modeSwitchInProgress
                || liveQualityTransactionBusy()) {
            return;
        }
        if (XrPresentationPolicy.requiresAtomicPresentationReconnect(
                currentPresenterMode, item.selectsMode,
                atomicPresentationV2Supported)) {
            LimeLog.info("XR: reconnecting before unacknowledged host wire-mode boundary "
                    + currentPresenterMode + " -> " + item.selectsMode);
            controlActionListener.onPresentationModeNeedsReconnect(item.selectsMode);
            return;
        }
        ModeStreamQualityModel targetQuality = modeStreamQualityModels.get(item.selectsMode);
        if (XrPresentationPolicy.shouldReconnectBeforeModeEntry(
                currentPresenterMode, item.selectsMode,
                applyRequiresReconnect, targetQuality)) {
            // Reconnect before any host request or surface handoff. An ACK for an interim tuple
            // would otherwise replace the target mode's saved quality before Game could apply it.
            LimeLog.info("XR: reconnecting before presentation quality boundary "
                    + currentPresenterMode + " -> " + item.selectsMode);
            controlActionListener.onPresentationModeNeedsReconnect(item.selectsMode);
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
        if (item.selectsMode == PresentationMode.GAME_3D) {
            gameSourceState.enterGame();
            gameWireMode = MoonBridge.SBS_MODE_GAME_MONO;
            confirmedGamePresentationGeneration = 0;
            activity.getIntent().removeExtra(EXTRA_GAME_WIDEN_FAILURE);
            activity.getIntent().removeExtra(EXTRA_GAME_WIDEN_QUALITY);
        }
        updateGlancePanel();
        revealDockTemporarily();
        PresentationMode previousMode = currentPresenterMode;
        PresentationMode nextMode = item.selectsMode;

        boolean fuseClientQuality = XrPresentationPolicy.shouldFuseClientModeEntryQuality(
                previousMode, nextMode, atomicPresentationV2Supported,
                applyRequiresReconnect, targetQuality);
        if (effectiveWireModeFor(previousMode) != effectiveWireModeFor(nextMode) || fuseClientQuality) {
            beginAckFirstModeTransition(
                    item, previousMode, targetQuality,
                    targetQuality != null && targetQuality.appliesLiveIfSelected());
            return;
        }

        continueModeSurfaceSwitch(item, previousMode, nextMode);
    }

    /**
     * Requests an inactive Client mode's saved tuple while SceneCore keeps presenting the old
     * producer. No decoder recovery, Surface handoff, EGL resize, or interpretation change starts
     * until the correlated host ACK supplies the authoritative tuple.
     */
    private void beginAckFirstModeTransition(
            BarItem item, PresentationMode previousMode,
            ModeStreamQualityModel targetQuality,
            boolean applyTargetQuality) {
        // Own the mode start before validation or decoder gating can fail. The shared abort
        // path must release selectMode's guard even when no host request has been queued yet.
        pendingAckFirstModeItem = item;
        pendingAckFirstPreviousMode = previousMode;
        PresentationMode nextMode = item.selectsMode;
        StreamQualityTuple durableTarget = applyTargetQuality && targetQuality != null
                ? targetQuality.pendingQuality : null;
        StreamQualityTuple requestedTarget = durableTarget != null
                ? durableTarget
                : currentQualityTuple();
        float requestedCeiling = XrPresentationPolicy.parseFrameRate(requestedTarget.frameRate, prefConfig.fps);
        int effectiveFps = panelRefreshRateState.capUserTarget(
                Math.max(1, Math.round(requestedCeiling)));
        StreamQualityTuple target = effectiveFps == Math.round(requestedCeiling)
                ? requestedTarget
                : new StreamQualityTuple(
                        requestedTarget.resolution, String.valueOf(effectiveFps),
                        requestedTarget.bitrateKbps);
        int[] size = XrPresentationPolicy.parseResolutionSize(target.resolution);
        float fps = XrPresentationPolicy.parseFrameRate(target.frameRate, prefConfig.fps);
        int fpsX100 = XrPresentationPolicy.frameRateX100(target.frameRate, prefConfig.fps);
        if (size == null || fps <= 0 || fpsX100 <= 0) {
            abortPendingModeStart();
            reportLiveQualityStartFailure(
                    LiveQualityRequestOrigin.USER,
                    "the target presentation quality is invalid");
            return;
        }

        com.limelight.Game game = hostGame();
        int transitionGeneration = game != null
                ? game.beginDecoderPresentationModeTransition() : 0;
        if (!decoderTransitionGenerations.beginMode(transitionGeneration)) {
            abortPendingModeStart();
            reportLiveQualityStartFailure(
                    LiveQualityRequestOrigin.USER,
                    "the decoder could not close its presentation gate");
            return;
        }

        int requestId = nextVideoModeRequestId();
        pendingVideoModeRequestId = requestId;
        pendingExactEncodedWidth = 0;
        pendingExactEncodedHeight = 0;
        pendingLiveQuality = target;
        previousLiveQuality = currentQualityTuple();
        acknowledgedLiveQuality = null;
        pendingLiveQualityMode = nextMode;
        pendingLiveQualityOrigin = LiveQualityRequestOrigin.USER;
        pendingDurableUserQuality = durableTarget;
        // The mode crossing supplies the one post-ACK decoder/presentation proof even if the
        // quality delta itself is only FPS or bitrate.
        liveQualityChangeInProgress = true;
        liveQualityConfirmations.begin(
                true, nextMode == PresentationMode.CLIENT_SBS_AI);

        // Reset Host AI telemetry before its transition starts.
        if (XrPresentationPolicy.resetsHostDepthStatusAtTransitionStart(previousMode, nextMode)) {
            resetHostDepthStatus();
        }
        if (sendHostVideoModeControl(
                nextMode,
                size[0], size[1], fpsX100, requestId, target.bitrateKbps) <= 0) {
            game.cancelDecoderPresentationModeTransition();
            clearLiveQualityChange();
            reportLiveQualityStartFailure(
                    LiveQualityRequestOrigin.USER,
                    "host request could not be queued");
            return;
        }

        // A rejected local send leaves the old Game stream intact. ACK/status delivery is
        // serialized on this same main thread, so retire proof now, before either can run.
        if (previousMode == PresentationMode.GAME_3D || nextMode == PresentationMode.GAME_3D) {
            invalidateGameSourceProof();
        }

        armLiveQualityAckTimeout();
        updateGlancePanel();
        revealDockTemporarily();
        LimeLog.info("XR: retaining " + previousMode
                + " while awaiting atomic " + nextMode + " ack for " + target
                + " (request " + requestId + ")");
    }

    private void continueModeSurfaceSwitch(
            BarItem item, PresentationMode previousMode, PresentationMode nextMode) {
        if (!controlTransportOpen() || !modeSwitchInProgress
                || surfaceEntity == null || surfaceEntity.isDisposed()
                || item == null || item.selectsMode != nextMode
                || currentPresenterMode != previousMode) {
            if (isAckFirstModeTransitionPending()) {
                requireMandatoryLiveQualityResync(
                        shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin), false);
            }
            return;
        }
        // A ready push belongs to one host-depth generation. Clear it before asking Apollo to
        // enter Host SBS AI so an old session cannot briefly authorize Dump 3D while the
        // replacement pipeline is still loading. A new phase-2 push may then arrive at any point
        // during the transition without being erased again at commit.
        if (!isAckFirstModeTransitionPending()
                && XrPresentationPolicy.resetsHostDepthStatusAtTransitionStart(previousMode, nextMode)) {
            resetHostDepthStatus();
        }
        boolean wasClientSbs = (previousMode == PresentationMode.CLIENT_SBS_AI);
        boolean isClientSbs = (nextMode == PresentationMode.CLIENT_SBS_AI);

        com.limelight.Game game = hostGame();
        boolean decoderTransitionRequired = XrPresentationPolicy.requiresDecoderTransition(previousMode, nextMode)
                || isAckFirstModeTransitionPending();
        if (decoderTransitionRequired) {
            boolean reuseAckFirstTransition = isAckFirstModeTransitionPending();
            int transitionGeneration = reuseOrBeginModeDecoderTransition(
                    decoderTransitionGenerations, reuseAckFirstTransition,
                    () -> game != null ? game.beginDecoderPresentationModeTransition() : 0);
            if (transitionGeneration <= 0) {
                if (isAckFirstModeTransitionPending()) {
                    LimeLog.severe("XR: decoder could not prepare the ACKed Client SBS entry; "
                            + "forcing authoritative reconnect");
                    requireMandatoryLiveQualityResync(
                            shouldCommitStagedSettingsForResync(
                                    pendingLiveQualityOrigin), false);
                    return;
                }
                lastModeSwitchMs = 0;
                modeSwitchInProgress = false;
                updateGlancePanel();
                revealDockTemporarily();
                schedulePanelRateReconcile();
                reportModeSwitchFailure("decoder could not prepare for the transition");
                return;
            }
        }

        // SceneCore cannot synchronize StereoMode/Shape changes with producer buffers. A Client
        // SBS crossing therefore retains both the old buffer and its old interpretation through
        // decoder parking/rebinding. The target interpretation is committed only at its fresh
        // presentation proof. Direct-producer size changes retain their established eager hide.
        boolean retainOldPictureUntilTargetFrame = XrPresentationPolicy.retainsOldPictureUntilFreshTargetFrame(
                previousMode, nextMode);
        if (!retainOldPictureUntilTargetFrame) {
            surfaceEntity.setAlpha(0.0f);
        }

        StreamContainer streamContainer = activity instanceof com.limelight.Game
                ? ((com.limelight.Game) activity).getStreamContainer() : null;
        if (streamContainer != null && wasClientSbs != isClientSbs) {
            // Entering must activate before the GL surface resumes. Leaving must remain active
            // until MediaCodec has parked and the detach callback succeeds; disabling here queues
            // a black clear while SceneCore still presents the retained picture at alpha 1.
            streamContainer.setClientSbsActive(
                    XrPresentationPolicy.clientSbsActiveDuringSurfaceSwitch(wasClientSbs, isClientSbs));
        }
        if (wasClientSbs != isClientSbs) {
            if (streamContainer == null) {
                finishModeSwitch(item, previousMode, nextMode,
                        wasClientSbs, isClientSbs, null, false);
            } else {
                streamContainer.switchToClientSbs(isClientSbs,
                        prefConfig.isHostDoubledWidthMode()
                                && XrPresentationPolicy.isPackedWireMode(effectiveTargetWireMode(nextMode)),
                        success -> finishModeSwitch(item, previousMode, nextMode,
                                wasClientSbs,
                                isClientSbs, streamContainer, success));
            }
            return;
        }

        finishModeSwitch(item, previousMode, nextMode,
                wasClientSbs, isClientSbs, streamContainer, true);
    }

    /**
     * Applies a stream-quality tuple to the running stream with no reconnect.
     *
     * <p>Bitrate-only and frame-rate-only deltas take the fast path: one reliable control message,
     * no surface transaction and no IDR gate. A resolution delta retains the current presentation
     * until the reliable ACK, then performs one decoder/surface transaction at the authoritative
     * geometry and settles only after its fresh-IDR/presentation proof.</p>
     *
     * <p>Main-thread only: every SceneCore call below is Activity-bound.</p>
     */
    public boolean applyLiveStreamQuality(StreamQualityTuple target) {
        if (!controlTransportOpen() || target == null) {
            return false;
        }
        if (!atomicPresentationV2Supported) {
            LimeLog.info("XR: host without atomic presentation v2 requires reconnect for stream-quality changes");
            controlActionListener.onLiveStreamQualityNeedsReconnect();
            return false;
        }
        float requestedCeiling = XrPresentationPolicy.parseFrameRate(target.frameRate, prefConfig.fps);
        int effectiveFps = panelRefreshRateState.capUserTarget(
                Math.max(1, Math.round(requestedCeiling)));
        StreamQualityTuple effectiveTarget = effectiveFps == Math.round(requestedCeiling)
                ? target
                : new StreamQualityTuple(
                        target.resolution, String.valueOf(effectiveFps), target.bitrateKbps);
        return applyLiveStreamQuality(
                effectiveTarget, LiveQualityRequestOrigin.USER, target);
    }

    public boolean isStreamQualityTransactionBusy() {
        return liveQualityTransactionBusy() || modeSwitchInProgress
                || pendingDecoderTransitionMode != null || clientSbsHdrTransitionInProgress;
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
        if (atomicPresentationV2Supported) {
            reconcilePanelRefreshRate();
        }
    }

    private void schedulePanelRateReconcile() {
        schedulePanelRateReconcile(0L);
    }

    private void schedulePanelRateReconcile(long delayMs) {
        if (!controlTransportOpen() || !atomicPresentationV2Supported) {
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
        if (!controlTransportOpen() || settingsTransactionPending) {
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
        return applyLiveStreamQuality(target, origin, durableUserTarget,
                effectiveWireModeFor(currentPresenterMode));
    }

    private boolean applyLiveStreamQuality(StreamQualityTuple target,
                                           LiveQualityRequestOrigin origin,
                                           StreamQualityTuple durableUserTarget,
                                           int desiredWireMode) {
        if (!controlTransportOpen() || !atomicPresentationV2Supported
                || target == null || !streamPresentationReady
                || surfaceEntity == null
                || surfaceEntity.isDisposed() || modeSwitchInProgress
                || liveQualityTransactionBusy() || pendingDecoderTransitionMode != null
                || clientSbsHdrTransitionInProgress) {
            return false;
        }
        com.limelight.Game game = hostGame();
        if (game == null) {
            return false;
        }

        float currentFps = prefConfig.fps;
        int[] size = XrPresentationPolicy.parseResolutionSize(target.resolution);
        float fps = XrPresentationPolicy.parseFrameRate(target.frameRate, currentFps);
        int fpsX100 = XrPresentationPolicy.frameRateX100(target.frameRate, currentFps);
        if (size == null || fps <= 0 || fpsX100 <= 0) {
            LimeLog.warning("XR: ignoring unparseable live stream quality " + target);
            return false;
        }
        StreamQualityTuple previous = new StreamQualityTuple(
                prefConfig.width + "x" + prefConfig.height,
                XrStatsFormat.formatFrameRate(currentFps), prefConfig.bitrate);
        boolean resolutionChanged = size[0] != prefConfig.width || size[1] != prefConfig.height;

        int requestId = nextVideoModeRequestId();

        liveQualityChangeInProgress = true;
        liveQualityConfirmations.begin(
                true, currentPresenterMode == PresentationMode.CLIENT_SBS_AI);
        previousLiveQuality = previous;
        pendingLiveQuality = target;
        acknowledgedLiveQuality = null;
        pendingLiveQualityMode = currentPresenterMode;
        pendingLiveQualityOrigin = origin;
        pendingDurableUserQuality = durableUserTarget;
        pendingVideoModeRequestId = requestId;
        pendingExactEncodedWidth = 0;
        pendingExactEncodedHeight = 0;
        pendingClientPackedSwapProofArmed = false;
        updateGlancePanel();
        revealDockTemporarily();

        // Close the decoder output gate before a geometry-changing request can reach the host.
        // FPS/bitrate-only v2 requests start their post-ACK proof after the correlated ACK.
        boolean preSendDecoderGate = resolutionChanged
                || desiredWireMode != effectiveWireModeFor(currentPresenterMode)
                || origin == LiveQualityRequestOrigin.GAME_SOURCE;
        if (preSendDecoderGate) {
            int transitionGeneration = game.beginDecoderPresentationModeTransition();
            if (!decoderTransitionGenerations.beginMode(transitionGeneration)) {
                clearLiveQualityChange();
                reportLiveQualityStartFailure(
                        origin, "the decoder could not close its presentation gate");
                return false;
            }
        }

        // The reliable ACK is the authority for the geometry to allocate. Until it arrives, do
        // not resize either producer or publish replacement geometry.
        int sendResult = sendHostVideoModeControl(currentPresenterMode,
                size[0], size[1], fpsX100, requestId, target.bitrateKbps, desiredWireMode);
        if (sendResult <= 0) {
            if (preSendDecoderGate) {
                game.cancelDecoderPresentationModeTransition();
            }
            clearLiveQualityChange();
            reportLiveQualityStartFailure(
                    origin, "host request could not be queued");
            return false;
        }
        // Until the request queues, the confirmed stream and its source proof still apply.
        // Main-thread ACK/status delivery cannot race this post-send invalidation.
        if (currentPresenterMode == PresentationMode.GAME_3D && gameProviderV1Supported) {
            if (origin == LiveQualityRequestOrigin.USER && !target.equals(previous)) {
                gameSourceState.onRelevantQualityChanged();
                activity.getIntent().removeExtra(EXTRA_GAME_WIDEN_FAILURE);
                activity.getIntent().removeExtra(EXTRA_GAME_WIDEN_QUALITY);
            }
            invalidateGameSourceProof();
            updateGameSourceViews();
        }
        armLiveQualityAckTimeout();
        LimeLog.info("XR: retaining current presentation while awaiting authoritative ack for "
                + "live quality " + target + " (request " + requestId + ")");
        return true;
    }

    private int nextVideoModeRequestId() {
        // Opaque u32 correlation token with natural Java-int wrap. Zero and the local -1
        // "no request" sentinel are skipped.
        do {
            videoModeRequestCounter++;
        } while (videoModeRequestCounter == 0 || videoModeRequestCounter == -1);
        return videoModeRequestCounter;
    }

    /** Main-thread delivery of the exact 28-byte atomic presentation v2 ACK. */
    public void onVideoModeAckV2(
            int status, int appliedMode, int flags, int requestId,
            int stateGeneration, int appliedSourceWidth, int appliedSourceHeight,
            int exactEncodedWidth, int exactEncodedHeight,
            int appliedFramerateX100, int effectiveBitrateKbps) {
        if (pendingVideoModeRequestId == -1
                || requestId != pendingVideoModeRequestId) {
            LimeLog.info("XR: dropping stale atomic presentation ack "
                    + Integer.toUnsignedString(requestId)
                    + " (outstanding request "
                    + Integer.toUnsignedString(pendingVideoModeRequestId) + ")");
            return;
        }

        com.limelight.Game game = hostGame();
        if (game == null) {
            clearLiveQualityChange();
            return;
        }
        cancelLiveQualityAckTimeout();
        pendingVideoModeRequestId = -1;

        boolean validBody = XrPresentationPolicy.isKnownVideoModeAckStatus(status)
                && XrPresentationPolicy.isValidAtomicPresentationAckBody(
                        flags, appliedMode, stateGeneration,
                        appliedSourceWidth, appliedSourceHeight,
                        exactEncodedWidth, exactEncodedHeight,
                        appliedFramerateX100, effectiveBitrateKbps);
        if (!validBody) {
            LimeLog.severe("XR: malformed atomic presentation ack; forcing reconnect");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin), false);
            return;
        }

        boolean recoveredGameWidenRefusal = status != MoonBridge.VIDEO_MODE_ACK_APPLIED
                && status != MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT
                && pendingLiveQualityOrigin == LiveQualityRequestOrigin.GAME_SOURCE
                && pendingDesiredWireMode == MoonBridge.SBS_MODE_GAME_SBS
                && gameWireMode == MoonBridge.SBS_MODE_GAME_MONO
                && appliedMode == MoonBridge.SBS_MODE_GAME_MONO
                && appliedSourceWidth == pendingRequestedSourceWidth
                && appliedSourceHeight == pendingRequestedSourceHeight
                && (stateGeneration == lastPresentationStateGeneration
                || XrPresentationPolicy.isStrictlyNewerUnsignedGeneration(lastPresentationStateGeneration, stateGeneration));
        if (recoveredGameWidenRefusal) {
            // The ACK explicitly proves the retained mono layout. Re-open only after its fresh
            // decoder boundary and latch this attempt across provider/encoder rebuilds.
            gameSourceState.recordWidenFailure();
            pendingDesiredWireMode = MoonBridge.SBS_MODE_GAME_MONO;
            LimeLog.warning("XR: Game 3D widening refused; retaining proven mono until a deliberate retry");
        } else if (status != MoonBridge.VIDEO_MODE_ACK_APPLIED) {
            boolean commitRequestedTarget =
                    status == MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT
                    && shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin);
            LimeLog.severe("XR: atomic presentation request was rejected with status "
                    + status + "; reconnecting without a rollback packet");
            requireMandatoryLiveQualityResync(commitRequestedTarget, false);
            return;
        }

        boolean matchesRequest = appliedMode == pendingDesiredWireMode
                && appliedSourceWidth == pendingRequestedSourceWidth
                && appliedSourceHeight == pendingRequestedSourceHeight
                && (recoveredGameWidenRefusal || XrPresentationPolicy.isStrictlyNewerUnsignedGeneration(
                        lastPresentationStateGeneration, stateGeneration));
        if (!matchesRequest) {
            LimeLog.severe("XR: atomic presentation ack does not match the requested mode/source "
                    + "or advance generation; forcing reconnect");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin), false);
            return;
        }

        AcknowledgedVideoMode acknowledged = AcknowledgedVideoMode.acknowledgedVideoMode(
                pendingLiveQuality,
                appliedSourceWidth, appliedSourceHeight,
                appliedFramerateX100, effectiveBitrateKbps);
        if (acknowledged == null) {
            LimeLog.severe("XR: atomic presentation ack has no usable logical quality; "
                    + "forcing reconnect");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin), false);
            return;
        }

        lastPresentationStateGeneration = stateGeneration;
        pendingExactEncodedWidth = exactEncodedWidth;
        pendingExactEncodedHeight = exactEncodedHeight;
        effectiveEncoderBitrateKbps = acknowledged.effectiveEncoderBitrateKbps;
        int[] decoderOutput = game.getDecoderOutputDimensions();
        if (pendingLiveQualityOrigin != LiveQualityRequestOrigin.GAME_SOURCE
                && XrPresentationPolicy.canSettleAtomicQualityWithoutDecoderTransition(
                isAckFirstModeTransitionPending(), previousLiveQuality,
                pendingLiveQuality, exactEncodedWidth, exactEncodedHeight,
                decoderOutput != null ? decoderOutput[0] : 0,
                decoderOutput != null ? decoderOutput[1] : 0)) {
            // The atomic ACK proves mode/source and the decoder is already presenting its exact
            // raster. FPS/bitrate-only changes therefore need no flush, IDR, or surface work.
            liveQualityChangeInProgress = false;
            liveQualityConfirmations.begin(false);
        }
        handleAppliedVideoModeAck(game, requestId, acknowledged.logicalQuality);
    }

    private void handleAppliedVideoModeAck(
            com.limelight.Game game, int requestId,
            StreamQualityTuple appliedTuple) {
        PresentationMode requestMode = liveQualityRequestMode();

        // Adopt the applied values as authoritative, re-pinning geometry when the host clamped.
        LimeLog.info("XR: host applied " + appliedTuple.resolution + " @ "
                + appliedTuple.frameRate + " FPS for request " + requestId
                + " (requested wire bitrate " + appliedTuple.bitrateKbps
                + " Kbps, effective encoder bitrate " + effectiveEncoderBitrateKbps
                + " Kbps)");
        acknowledgedLiveQuality = appliedTuple;
        liveQualityConfirmations.onAppliedAck();

        if (isAckFirstModeTransitionPending()) {
            if (!liveQualityConfirmations.beginPostAckDecoderConfirmation()) {
                LimeLog.severe("XR: host applied the presentation transaction, but the client "
                        + "could not arm its one post-ACK confirmation");
                requireMandatoryLiveQualityResync(
                        shouldCommitStagedSettingsForResync(
                                pendingLiveQualityOrigin), false);
                return;
            }
            int transitionGeneration = decoderTransitionGenerations.currentModeGeneration();
            if (transitionGeneration <= 0
                    || !stageAcknowledgedModeEntryQuality(game, appliedTuple)) {
                LimeLog.severe("XR: host applied the presentation transaction, but the client "
                        + "could not retain and stage its authoritative handoff");
                requireMandatoryLiveQualityResync(
                        shouldCommitStagedSettingsForResync(
                                pendingLiveQualityOrigin), false);
                return;
            }
            continueModeSurfaceSwitch(
                    pendingAckFirstModeItem,
                    pendingAckFirstPreviousMode,
                    requestMode);
            return;
        }

        if (liveQualityChangeInProgress) {
            beginPostAckLiveQualityDecoderConfirmation(game, appliedTuple);
            return;
        }
        if (!applyAcknowledgedQualityWithoutGeometryChange(game, appliedTuple)) {
            LimeLog.severe("XR: host applied " + appliedTuple
                    + " but the client could not adopt its presentation geometry");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                    false);
            return;
        }
        finishConfirmedLiveQualityChange(game);
    }

    /**
     * Establishes a causal decoder boundary after Apollo has acknowledged the applied resolution.
     *
     * <p>No speculative decoder/surface transition exists before the ACK. This method starts the
     * request's sole transition, adopts the authoritative geometry behind its closed frame gate,
     * and only then requests the fresh IDR that may settle it.</p>
     */
    private void beginPostAckLiveQualityDecoderConfirmation(
            com.limelight.Game game, StreamQualityTuple appliedTuple) {
        if (!liveQualityConfirmations.beginPostAckDecoderConfirmation()) {
            LimeLog.severe("XR: could not arm the post-ack decoder confirmation exactly once; "
                    + "forcing authoritative reconnect");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                    false);
            return;
        }

        int transitionGeneration = decoderTransitionGenerations.currentModeGeneration();
        if (transitionGeneration <= 0) {
            transitionGeneration = game.beginDecoderPresentationModeTransition();
        }
        if (decoderTransitionGenerations.currentModeGeneration() <= 0
                && !decoderTransitionGenerations.beginMode(transitionGeneration)) {
            LimeLog.severe("XR: decoder could not prepare the post-ack resolution confirmation; "
                    + "forcing authoritative reconnect");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                    false);
            return;
        }

        // Client SBS can retain its previous packed SceneCore buffer through the resize. Direct
        // producer changes retain their established hidden boundary.
        if (currentPresenterMode != PresentationMode.CLIENT_SBS_AI) {
            surfaceEntity.setAlpha(0.0f);
        }
        final int expectedTransitionGeneration = transitionGeneration;
        boolean adoptionStarted = applyAcknowledgedQuality(
                game, appliedTuple, this::onClientSbsLiveResizeComplete,
                () -> liveQualityChangeInProgress
                        && acknowledgedLiveQuality == appliedTuple
                        && decoderTransitionGenerations.currentModeGeneration()
                        == expectedTransitionGeneration,
                success -> finishPostAckGeometryAdoption(
                        game, appliedTuple, expectedTransitionGeneration, success));
        if (!adoptionStarted) {
            LimeLog.severe("XR: host applied " + appliedTuple
                    + " but the client could not adopt its presentation geometry");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin),
                    false);
            return;
        }
    }

    private void finishPostAckGeometryAdoption(
            com.limelight.Game game, StreamQualityTuple appliedTuple,
            int transitionGeneration, boolean success) {
        if (!liveQualityChangeInProgress || acknowledgedLiveQuality != appliedTuple
                || decoderTransitionGenerations.currentModeGeneration()
                != transitionGeneration) {
            LimeLog.warning("XR: ignoring stale post-ack surface-handoff completion");
            return;
        }
        if (!success) {
            LimeLog.severe("XR: authoritative host geometry could not bind its decoder surface");
            requireMandatoryLiveQualityResync(
                    shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin), false);
            return;
        }

        game.completeDecoderPresentationModeTransition();
        LimeLog.info("XR: host resolution is authoritative; awaiting the sole post-ack "
                + "fresh-IDR output before presentation commit");
    }

    /** Stages an ACKed mode tuple without resizing the still-visible old producer. */
    private boolean stageAcknowledgedModeEntryQuality(
            com.limelight.Game game, StreamQualityTuple applied) {
        int[] size = XrPresentationPolicy.parseResolutionSize(applied.resolution);
        float fps = XrPresentationPolicy.parseFrameRate(applied.frameRate, prefConfig.fps);
        if (size == null || fps <= 0) {
            return false;
        }
        prefConfig.width = size[0];
        prefConfig.height = size[1];
        prefConfig.fps = fps;
        prefConfig.bitrate = applied.bitrateKbps;
        fullAspect = (float) size[0] / size[1];
        updateDecoderStreamGeometry(
                game, liveQualityRequestMode(),
                size[0], size[1], Math.round(fps));
        return true;
    }

    /**
     * Adopts the host's applied geometry and frame rate while retaining the requested total wire
     * bitrate. Apollo's post-audio/FEC encoder bitrate is tracked separately and must never replace
     * {@link PreferenceConfiguration#bitrate}.
     */
    private boolean applyAcknowledgedQualityWithoutGeometryChange(
            com.limelight.Game game, StreamQualityTuple applied) {
        int[] size = XrPresentationPolicy.parseResolutionSize(applied.resolution);
        float fps = XrPresentationPolicy.parseFrameRate(applied.frameRate, prefConfig.fps);
        if (size == null || size[0] != prefConfig.width || size[1] != prefConfig.height) {
            return false;
        }
        prefConfig.fps = fps;
        prefConfig.bitrate = applied.bitrateKbps;
        updateDecoderStreamGeometry(
                game, liveQualityRequestMode(), size[0], size[1], Math.round(fps));
        return XrPresentationPolicy.acknowledgedGeometryAdoptionSucceeded(false, false);
    }

    private boolean applyAcknowledgedQuality(
            com.limelight.Game game, StreamQualityTuple applied,
            StreamContainer.SurfaceSwitchCallback clientSbsResizeCallback,
            BooleanSupplier transactionCurrent,
            StreamContainer.SurfaceSwitchCallback adoptionCompletion) {
        int[] size = XrPresentationPolicy.parseResolutionSize(applied.resolution);
        float fps = XrPresentationPolicy.parseFrameRate(applied.frameRate, prefConfig.fps);
        if (size == null || transactionCurrent == null || adoptionCompletion == null
                || !transactionCurrent.getAsBoolean()) {
            return false;
        }
        int[] previousRaster = currentExactEncodedWidth > 0 && currentExactEncodedHeight > 0
                ? new int[] {currentExactEncodedWidth, currentExactEncodedHeight}
                : XrPresentationPolicy.initialSurfacePixelDimensions(currentPresenterMode,
                        prefConfig.width, prefConfig.height, hostSbsVideoFormat);
        boolean encodedRasterChanged = pendingExactEncodedWidth > 0 && pendingExactEncodedHeight > 0
                && (pendingExactEncodedWidth != previousRaster[0]
                || pendingExactEncodedHeight != previousRaster[1]);
        boolean geometryChanged = size[0] != prefConfig.width || size[1] != prefConfig.height
                || encodedRasterChanged;
        if (geometryChanged) {
            LimeLog.info("XR: adopting authoritative source " + applied.resolution
                    + " and encoded raster " + pendingExactEncodedWidth + "x" + pendingExactEncodedHeight);
            return XrPresentationPolicy.acknowledgedGeometryAdoptionSucceeded(true,
                    applyLiveStreamGeometry(
                            game, size[0], size[1], fps, applied.bitrateKbps,
                            clientSbsResizeCallback, transactionCurrent,
                            adoptionCompletion));
        }
        prefConfig.fps = fps;
        prefConfig.bitrate = applied.bitrateKbps;
        updateDecoderStreamGeometry(
                game, liveQualityRequestMode(), size[0], size[1], Math.round(fps));
        adoptionCompletion.onComplete(true);
        return XrPresentationPolicy.acknowledgedGeometryAdoptionSucceeded(false, false);
    }

    /**
     * Re-pins every dimension-derived piece of client state to a new stream size: the cached
     * aspect, the SceneCore surface (through {@code StreamContainer}'s dummy-surface handoff so
     * MediaCodec never sees a transient target), the quad, and the dependent panel poses.
     */
    private boolean applyLiveStreamGeometry(
            com.limelight.Game game, int width, int height,
            float fps, int bitrateKbps,
            StreamContainer.SurfaceSwitchCallback clientSbsResizeCallback,
            BooleanSupplier transactionCurrent,
            StreamContainer.SurfaceSwitchCallback adoptionCompletion) {
        StreamContainer streamContainer = game.getStreamContainer();
        if (streamContainer == null || surfaceEntity == null || surfaceEntity.isDisposed()
                || transactionCurrent == null || adoptionCompletion == null) {
            return false;
        }

        // Client SBS owns its own GL color targets and presents a packed 2W x H swapchain, so it
        // resizes through the renderer rather than the host-surface dummy-park handoff.
        PresentationMode geometryMode = currentPresenterMode;
        boolean clientSbsResize = geometryMode == PresentationMode.CLIENT_SBS_AI;
        if (clientSbsResize) {
            liveQualityConfirmations.expectPresentationConfirmation();
            // StreamContainer blocks new draws immediately; the EGL owner acknowledges detach.
            // Renderer-owned source dimensions remain unchanged until that detach completes.
            if (!streamContainer.resizeClientSbsSurface(
                    width, height, clientSbsResizeCallback)) {
                return false;
            }
            if (!transactionCurrent.getAsBoolean()
                    || !applyLiveStreamGeometryState(
                    game, geometryMode, width, height, fps, bitrateKbps)) {
                return false;
            }
            adoptionCompletion.onComplete(true);
            return true;
        }

        return streamContainer.resizeHostSbsSurface(
                prefConfig.isHostDoubledWidthMode()
                        && XrPresentationPolicy.isPackedWireMode(effectiveTargetWireMode(geometryMode)),
                width, height, success -> {
                    if (!transactionCurrent.getAsBoolean()
                            || currentPresenterMode != geometryMode) {
                        LimeLog.warning("XR: ignoring superseded Host SBS surface resize");
                        return;
                    }
                    adoptionCompletion.onComplete(success
                            && applyLiveStreamGeometryState(
                            game, geometryMode, width, height, fps, bitrateKbps));
                });
    }

    private boolean applyLiveStreamGeometryState(
            com.limelight.Game game, PresentationMode geometryMode,
            int width, int height, float fps, int bitrateKbps) {
        if (surfaceEntity == null || surfaceEntity.isDisposed()
                || currentPresenterMode != geometryMode) {
            return false;
        }
        prefConfig.width = width;
        prefConfig.height = height;
        prefConfig.fps = fps;
        prefConfig.bitrate = bitrateKbps;
        // The only cached dimension-derived field in this class.
        fullAspect = (float) width / height;
        updateDecoderStreamGeometry(
                game, geometryMode, width, height, Math.round(fps));

        // Also repositions the stats panel.
        reshapeQuadForMode(geometryMode, DEFAULT_PANEL_HEIGHT_METERS);
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
        com.limelight.Game game = hostGame();
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
        com.limelight.Game game = hostGame();
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
        if (liveQualityRequestMode() == PresentationMode.CLIENT_SBS_AI
                && liveQualityConfirmations
                .isWaitingForPresentationAfterMatchingPostAckOutput()) {
            StreamContainer streamContainer = game.getStreamContainer();
            if (streamContainer != null) {
                int[] before = previousLiveQuality != null
                        ? XrPresentationPolicy.parseResolutionSize(previousLiveQuality.resolution) : null;
                int[] after = acknowledgedLiveQuality != null
                        ? XrPresentationPolicy.parseResolutionSize(acknowledgedLiveQuality.resolution) : null;
                boolean geometryChanged = before == null || after == null
                        || before[0] != after[0] || before[1] != after[1];
                if (geometryChanged) {
                    streamContainer.onClientSbsPostAckDecoderOutput(
                            actualWidth, actualHeight);
                } else if (!pendingClientPackedSwapProofArmed) {
                    pendingClientPackedSwapProofArmed = true;
                    int packedProofGeneration =
                            decoderTransitionGenerations.currentModeGeneration();
                    boolean armed = streamContainer.completeClientSbsModeSwitchAfterSwap(() -> {
                        if (!liveQualityChangeInProgress
                                || decoderTransitionGenerations.currentModeGeneration()
                                != packedProofGeneration) {
                            return;
                        }
                        liveQualityConfirmations.onPresentationReady();
                        finishConfirmedLiveQualityChange(game);
                    });
                    if (!armed) {
                        pendingClientPackedSwapProofArmed = false;
                        LimeLog.severe("XR: could not arm Client SBS post-ACK packed proof; "
                                + "forcing reconnect");
                        requireMandatoryLiveQualityResync(
                                shouldCommitStagedSettingsForResync(
                                        pendingLiveQualityOrigin), false);
                        return;
                    }
                }
            }
        }
        finishConfirmedLiveQualityChange(game);
    }

    private int[] expectedLiveQualityDecoderDimensions() {
        if (pendingExactEncodedWidth > 0 && pendingExactEncodedHeight > 0) {
            return new int[] {pendingExactEncodedWidth, pendingExactEncodedHeight};
        }
        return XrPresentationPolicy.initialSurfacePixelDimensions(liveQualityRequestMode(),
                prefConfig.width, prefConfig.height, hostSbsVideoFormat);
    }

    private PresentationMode liveQualityRequestMode() {
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
        if (!controlTransportOpen()) {
            return;
        }
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
        PresentationMode requestMode = liveQualityRequestMode();
        boolean wasResolutionTransaction = liveQualityChangeInProgress;
        commitGameTransport(requestMode);
        if (wasResolutionTransaction && surfaceEntity != null && !surfaceEntity.isDisposed()) {
            surfaceEntity.setAlpha(1.0f);
        }
        decoderTransitionGenerations.clearMode();
        settleSuccessfulLiveQuality(requestMode, applied);
        updateGlancePanel();
        revealDockTemporarily();
        LimeLog.info("XR: live quality settled at " + applied);
    }

    private void settleSuccessfulLiveQuality(PresentationMode requestMode,
                                             StreamQualityTuple applied) {
        LiveQualityRequestOrigin origin = pendingLiveQualityOrigin;
        StreamQualityTuple durableRequested = pendingDurableUserQuality;
        StreamQualityTuple durableApplied = XrPresentationPolicy.durableUserQuality(applied, durableRequested);
        commitGameTransport(requestMode);

        if (pendingExactEncodedWidth > 0 && pendingExactEncodedHeight > 0) {
            currentExactEncodedWidth = pendingExactEncodedWidth;
            currentExactEncodedHeight = pendingExactEncodedHeight;
        }

        if (origin == LiveQualityRequestOrigin.PANEL_FOLLOW) {
            panelRefreshRateState.automaticRequestSucceeded(
                    Math.round(XrPresentationPolicy.parseFrameRate(
                            applied != null ? applied.frameRate : "0", prefConfig.fps)));
        } else if (origin == LiveQualityRequestOrigin.GAME_SOURCE) {
            panelRefreshRateState.otherTransactionSettled();
        } else if (durableApplied != null) {
            panelRefreshRateState.userRequestSucceeded(
                    XrPresentationPolicy.parseFrameRate(durableApplied.frameRate, prefConfig.fps));
        } else {
            panelRefreshRateState.otherTransactionSettled();
        }

        // The decoder target is an offscreen SurfaceTexture while Client SBS is active. Reapply
        // the durable vote to SceneCore's actual presentation swapchain as the authoritative
        // display-rate owner, in addition to keeping the decoder target's hint current.
        applyPresentationFrameRatePreference();
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
        scheduleGameSourceReconcile();
    }

    /** Called only once the existing ACK/fresh-frame gate can commit this presentation. */
    private void commitGameTransport(PresentationMode requestMode) {
        if (requestMode != PresentationMode.GAME_3D || !gameProviderV1Supported
                || (pendingDesiredWireMode != MoonBridge.SBS_MODE_GAME_MONO
                && pendingDesiredWireMode != MoonBridge.SBS_MODE_GAME_SBS)) {
            return;
        }
        gameWireMode = pendingDesiredWireMode;
        boolean changedGeneration = confirmedGamePresentationGeneration != lastPresentationStateGeneration;
        confirmedGamePresentationGeneration = lastPresentationStateGeneration;
        gameSourceState.confirmPresentation(Integer.toUnsignedLong(lastPresentationStateGeneration),
                prefConfig.width, prefConfig.height);
        if (changedGeneration) {
            LimeLog.info("XR: Game presentation confirmed generation "
                    + Integer.toUnsignedString(confirmedGamePresentationGeneration)
                    + ", wire mode " + gameWireMode + ", source "
                    + prefConfig.width + "x" + prefConfig.height);
        }
        if (surfaceEntity != null && !surfaceEntity.isDisposed()) {
            surfaceEntity.setStereoMode(stereoModeFor(PresentationMode.GAME_3D));
        }
        updateGameSourceViews();
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
        com.limelight.Game game = hostGame();
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
        if (!controlTransportOpen()) {
            return;
        }
        if (pendingLiveQualityOrigin == LiveQualityRequestOrigin.GAME_SOURCE
                && isGameSourceWidening(pendingDesiredWireMode)) {
            // A failed same-mode reconfirmation can be caused by a transient host display
            // rebuild. Reconnect still clears all source proof, but must not prevent a fresh
            // mono-to-stereo attempt after the replacement connection becomes ready.
            gameSourceState.recordWidenFailure();
        }
        if (commitStagedSettings && isAckFirstModeTransitionPending()) {
            stagePendingAckFirstModeForReconnect();
        }
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
        // A reconnect owns recovery from this point. Keep any decoder gate that preceded the
        // request closed until stream teardown: after a send, opening it would allow output whose
        // mode or geometry the client failed to prove. prepareForStop() releases it safely.
        decoderTransitionGenerations.clearMode();
        pendingDecoderTransitionMode = null;
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
        if (pendingClientPackedSwapProofArmed) {
            cancelClientSbsModeSwap();
        }
        cancelLiveQualityAckTimeout();
        resetPendingVideoModeRequest();
        abortPendingModeStart();
        updateGlancePanel();
        revealDockTemporarily();
    }

    /**
     * Forgets the in-flight live video-mode request and nothing else. The ACK-first mode item,
     * the acknowledged encoded raster and the presentation generation describe the stream that
     * is still running, so they are left to the caller.
     */
    private void resetPendingVideoModeRequest() {
        liveQualityChangeInProgress = false;
        liveQualityConfirmations.clear();
        pendingVideoModeRequestId = -1;
        pendingLiveQuality = null;
        previousLiveQuality = null;
        acknowledgedLiveQuality = null;
        pendingLiveQualityMode = null;
        pendingLiveQualityOrigin = null;
        pendingDurableUserQuality = null;
        pendingDesiredWireMode = -1;
        pendingRequestedSourceWidth = 0;
        pendingRequestedSourceHeight = 0;
        pendingExactEncodedWidth = 0;
        pendingExactEncodedHeight = 0;
        pendingClientPackedSwapProofArmed = false;
    }

    /**
     * Connection-stop and destroy reset: the request fields plus the ACK-first mode item, the
     * acknowledged encoded raster and the presentation generation, none of which outlive the
     * connection. It does not cancel an armed packed-swap completion.
     */
    private void resetVideoModeStateForTeardown() {
        liveQualityChangeInProgress = false;
        liveQualityConfirmations.clear();
        pendingVideoModeRequestId = -1;
        pendingLiveQuality = null;
        previousLiveQuality = null;
        acknowledgedLiveQuality = null;
        pendingLiveQualityMode = null;
        pendingLiveQualityOrigin = null;
        pendingDurableUserQuality = null;
        pendingAckFirstModeItem = null;
        pendingAckFirstPreviousMode = null;
        pendingDesiredWireMode = -1;
        pendingRequestedSourceWidth = 0;
        pendingRequestedSourceHeight = 0;
        pendingExactEncodedWidth = 0;
        pendingExactEncodedHeight = 0;
        currentExactEncodedWidth = 0;
        currentExactEncodedHeight = 0;
        lastPresentationStateGeneration = 0;
        pendingClientPackedSwapProofArmed = false;
    }

    private boolean isAckFirstModeTransitionPending() {
        return pendingAckFirstModeItem != null
                && pendingAckFirstPreviousMode != null;
    }

    private void stagePendingAckFirstModeForReconnect() {
        BarItem pendingItem = pendingAckFirstModeItem;
        if (pendingItem != null) {
            controlActionListener.onPresentationModeCommitted(
                    pendingItem.selectsMode);
        }
    }

    /** Releases a mode start that never reached a durable presentation commit. */
    private void abortPendingModeStart() {
        if (!isAckFirstModeTransitionPending()) {
            return;
        }
        pendingAckFirstModeItem = null;
        pendingAckFirstPreviousMode = null;
        modeSwitchInProgress = false;
        lastModeSwitchMs = 0L;
        decoderTransitionGenerations.clearMode();
        pendingDecoderTransitionMode = null;
    }

    private void reportLiveQualityStartFailure(
            LiveQualityRequestOrigin origin, String reason) {
        if (origin == LiveQualityRequestOrigin.GAME_SOURCE) {
            // The caller still owns the requested wire mode after failed-start cleanup and
            // records a widening failure only for an actual mono-to-stereo attempt.
            LimeLog.warning("XR automatic Game source request could not start: " + reason);
            return;
        }
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

    private void finishModeSwitch(BarItem item, PresentationMode previousMode, PresentationMode nextMode,
                                  boolean wasClientSbs,
                                  boolean isClientSbs, StreamContainer streamContainer,
                                  boolean surfaceSwitchSucceeded) {
        if (!controlTransportOpen()) {
            return;
        }
        if (surfaceSwitchSucceeded && !isClientSbs && !wasClientSbs
                && prefConfig.isHostDoubledWidthMode()
                && (XrPresentationPolicy.requiresHostSurfaceResize(previousMode, nextMode)
                || isAckFirstModeTransitionPending())) {
            if (streamContainer != null && streamContainer.resizeHostSbsSurface(
                    XrPresentationPolicy.isPackedWireMode(effectiveTargetWireMode(nextMode)),
                    prefConfig.width, prefConfig.height,
                    success -> finishModeSwitchAfterSurfaceHandoff(
                            item, previousMode, nextMode, wasClientSbs, isClientSbs,
                            streamContainer, success))) {
                return;
            }
            surfaceSwitchSucceeded = false;
        }

        finishModeSwitchAfterSurfaceHandoff(
                item, previousMode, nextMode, wasClientSbs, isClientSbs,
                streamContainer, surfaceSwitchSucceeded);
    }

    private void finishModeSwitchAfterSurfaceHandoff(
            BarItem item, PresentationMode previousMode, PresentationMode nextMode,
            boolean wasClientSbs, boolean isClientSbs, StreamContainer streamContainer,
            boolean surfaceSwitchSucceeded) {
        if (!controlTransportOpen() || !modeSwitchInProgress || item == null
                || item.selectsMode != nextMode || currentPresenterMode != previousMode) {
            return;
        }

        boolean surfaceUsable = surfaceEntity != null && !surfaceEntity.isDisposed();
        if (!surfaceSwitchSucceeded || !surfaceUsable) {
            if (streamContainer != null && wasClientSbs != isClientSbs) {
                // A timed-out entry may still hold renderer locks inside a driver call. Close
                // presentation without those locks; connection teardown owns eventual cleanup.
                streamContainer.abandonClientSbsPresentation();
            }
            if (isAckFirstModeTransitionPending()) {
                pendingDecoderTransitionMode = null;
                LimeLog.severe("XR: ACKed Client SBS entry could not complete its sole surface "
                        + "handoff; forcing authoritative reconnect");
                requireMandatoryLiveQualityResync(
                        shouldCommitStagedSettingsForResync(
                                pendingLiveQualityOrigin), false);
                return;
            }
            modeSwitchInProgress = false;
            decoderTransitionGenerations.clearMode();
            updateGlancePanel();
            revealDockTemporarily();
            schedulePanelRateReconcile();
            // Keep a decoder gate that preceded this request closed until prepareForStop(). A
            // failed surface handoff has no proven target on which output may safely resume.
            lastModeSwitchMs = 0;
            if (surfaceUsable) {
                surfaceEntity.setAlpha(1.0f);
            }
            if (surfaceUsable && activity instanceof com.limelight.Game) {
                ((com.limelight.Game) activity).handleDecoderSurfaceSwitchFailure();
            }
            return;
        }

        if (streamContainer != null && wasClientSbs != isClientSbs) {
            streamContainer.setClientSbsActive(isClientSbs);
        }
        boolean decoderTransitionRequired = XrPresentationPolicy.requiresDecoderTransition(previousMode, nextMode)
                || isAckFirstModeTransitionPending();
        boolean retainOldPictureUntilTargetFrame =
                XrPresentationPolicy.retainsOldPictureUntilFreshTargetFrame(previousMode, nextMode);
        if (!retainOldPictureUntilTargetFrame) {
            applyPresenterModeInterpretation(previousMode, nextMode, item.label);
        }
        if (decoderTransitionRequired) {
            // Client-SBS crossings retain the previous interpretation as well as its last buffer.
            // Entry commits only after a packed EGL swap; exit commits when MediaCodec accepts the
            // fresh direct target frame. A lost proof terminates instead of persisting a partial
            // transition. Direct-producer resize transitions keep their established hidden gate.
            pendingDecoderTransitionMode = nextMode;
            if (retainOldPictureUntilTargetFrame && isClientSbs
                    && !armClientSbsModeSwap(
                            decoderTransitionGenerations.currentModeGeneration(), nextMode)) {
                return;
            }
            ((com.limelight.Game) activity).completeDecoderPresentationModeTransition();
            LimeLog.info("XR: awaiting fresh-IDR output before completing mode " + nextMode);
        } else {
            if (retainOldPictureUntilTargetFrame) {
                applyPresenterModeInterpretation(previousMode, nextMode, item.label);
            }
            surfaceEntity.setAlpha(1.0f);
            modeSwitchInProgress = false;
            persistPresentationState();
            controlActionListener.onPresentationModeCommitted(currentPresenterMode);
            updateGlancePanel();
            revealDockTemporarily();
            schedulePanelRateReconcile();
            scheduleGameSourceReconcile();
        }
    }

    /** Commits SceneCore's interpretation only at the visibility boundary chosen by the caller. */
    private void applyPresenterModeInterpretation(PresentationMode previousMode,
                                                  PresentationMode nextMode,
                                                  String label) {
        if (XrPresentationPolicy.resetsHostDepthStatusAtTransitionCommit(previousMode, nextMode)) {
            resetHostDepthStatus();
        }
        if (previousMode != nextMode && nextMode == PresentationMode.MOVIE_3D) {
            authoredStereoModeState.reset();
        }
        if (previousMode == PresentationMode.GAME_3D && nextMode != PresentationMode.GAME_3D) {
            gameSourceState.leaveGame();
            confirmedGamePresentationGeneration = 0;
            gameWireMode = MoonBridge.SBS_MODE_GAME_MONO;
        }
        currentPresenterMode = nextMode;
        // Same-wire and deferred Client SBS switches can commit without a live-quality ACK.
        // Release the scoped window preference here too when the committed producer changes.
        applyPresentationFrameRatePreference();
        reconcileHostSbsTelemetrySubscription();
        surfaceEntity.setStereoMode(stereoModeFor(currentPresenterMode));
        applyContentColorMetadata();
        LimeLog.info("XR: stereo mode -> " + label);

        reshapeQuadForMode(currentPresenterMode, DEFAULT_PANEL_HEIGHT_METERS);
        updateModeSelection();
    }

    private String labelForPresenterMode(PresentationMode mode) {
        for (BarItem item : barItems) {
            if (item.selectsMode == mode) {
                return item.label;
            }
        }
        return mode.name();
    }

    /** Decoder callback: the fresh transition IDR is now being released to the target Surface. */
    public void onDecoderPresentationModeTransitionOpened(int transitionGeneration) {
        if (!controlTransportOpen()) {
            return;
        }
        PresentationMode pendingMode = pendingDecoderTransitionMode;
        if (pendingMode != null) {
            if (!decoderTransitionGenerations.dispatchModeIfCurrent(
                    transitionGeneration,
                    () -> {
                        if (isAckFirstModeTransitionPending()) {
                            int[] expected = expectedLiveQualityDecoderDimensions();
                            int[] actual = ((com.limelight.Game) activity)
                                    .getDecoderOutputDimensions();
                            int actualWidth = actual != null ? actual[0] : 0;
                            int actualHeight = actual != null ? actual[1] : 0;
                            liveQualityConfirmations.onDecoderOutput(
                                    actualWidth, actualHeight,
                                    expected[0], expected[1]);
                            if (decoderMismatchRequiresMandatoryResync(
                                    true, liveQualityConfirmations)) {
                                LimeLog.severe("XR: ACK-first mode transaction received "
                                        + actualWidth + "x" + actualHeight
                                        + " instead of authoritative "
                                        + expected[0] + "x" + expected[1]
                                        + "; forcing reconnect");
                                pendingDecoderTransitionMode = null;
                                requireMandatoryLiveQualityResync(
                                        shouldCommitStagedSettingsForResync(
                                                pendingLiveQualityOrigin), false);
                                return;
                            }
                        }
                        if (XrPresentationPolicy.requiresClientPackedSwapProof(currentPresenterMode, pendingMode)) {
                            LimeLog.info("XR: fresh Client SBS decoder input reached; awaiting "
                                    + "first packed swap");
                            armClientSbsModeSwapTimeout(transitionGeneration);
                            return;
                        }
                        finishPendingModeTransition(pendingMode);
                    })) {
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

    private boolean armClientSbsModeSwap(int transitionGeneration, PresentationMode pendingMode) {
        com.limelight.Game game = hostGame();
        StreamContainer streamContainer = game != null ? game.getStreamContainer() : null;
        boolean armed = streamContainer != null
                && streamContainer.completeClientSbsModeSwitchAfterSwap(() -> {
                    if (!decoderTransitionGenerations.dispatchModeIfCurrent(
                            transitionGeneration,
                            () -> {
                                if (isAckFirstModeTransitionPending()) {
                                    liveQualityConfirmations.onPresentationReady();
                                    if (!liveQualityConfirmations.canSettle()) {
                                        LimeLog.info("XR: Client SBS packed swap is waiting for "
                                                + "the ACKed decoder boundary");
                                        return;
                                    }
                                }
                                finishPendingModeTransition(pendingMode);
                            })) {
                        LimeLog.warning("XR: ignoring superseded Client SBS packed-swap proof "
                                + transitionGeneration);
                    }
                });
        if (!armed) {
            failPendingClientSbsModeSwap(transitionGeneration,
                    "could not arm the packed-swap proof");
            return false;
        }

        LimeLog.info("XR: retaining previous presentation until first Client SBS packed swap");
        return true;
    }

    /** The packed-swap budget begins at fresh decoder output, not while waiting for its IDR. */
    private void armClientSbsModeSwapTimeout(int transitionGeneration) {
        liveQualityHandler.postDelayed(
                () -> failPendingClientSbsModeSwap(
                        transitionGeneration, "timed out before the first packed swap"),
                2000L);
    }

    private void failPendingClientSbsModeSwap(int transitionGeneration, String reason) {
        decoderTransitionGenerations.dispatchModeIfCurrent(transitionGeneration, () -> {
            cancelClientSbsModeSwap();
            decoderTransitionGenerations.clearMode();
            LimeLog.severe("XR: Client SBS mode " + reason);
            if (isAckFirstModeTransitionPending()) {
                pendingDecoderTransitionMode = null;
                requireMandatoryLiveQualityResync(
                        shouldCommitStagedSettingsForResync(
                                pendingLiveQualityOrigin), false);
                return;
            }
            if (activity instanceof com.limelight.Game) {
                ((com.limelight.Game) activity).handleDecoderSurfaceSwitchFailure();
            }
        });
    }

    private void cancelClientSbsModeSwap() {
        com.limelight.Game game = hostGame();
        StreamContainer streamContainer = game != null ? game.getStreamContainer() : null;
        if (streamContainer != null) {
            streamContainer.cancelClientSbsModeSwitchCompletion();
        }
    }

    private void finishPendingModeTransition(PresentationMode pendingMode) {
        // Both decoder output and a later packed EGL swap can reach this completion boundary.
        // Disconnect closes presentation commits before onStop invalidates their generations.
        if (!controlTransportOpen() || surfaceEntity == null || surfaceEntity.isDisposed()
                || pendingDecoderTransitionMode != pendingMode) {
            LimeLog.warning("XR: ignoring stale decoder transition completion for " + pendingMode);
            return;
        }
        PresentationMode previousMode = currentPresenterMode;
        boolean completedPackedClientEntry =
                XrPresentationPolicy.requiresClientPackedSwapProof(previousMode, pendingMode);
        if (previousMode != pendingMode) {
            applyPresenterModeInterpretation(
                    previousMode, pendingMode, labelForPresenterMode(pendingMode));
        }
        pendingDecoderTransitionMode = null;
        decoderTransitionGenerations.clearMode();
        commitGameTransport(pendingMode);
        surfaceEntity.setAlpha(1.0f);
        if (isAckFirstModeTransitionPending()) {
            StreamQualityTuple applied = acknowledgedLiveQuality;
            // Clear only the fused-mode record first. settleSuccessfulLiveQuality() clears the
            // live-quality record and publishes the ACKed target while modeSwitchInProgress still
            // blocks any reentrant request from Game.
            pendingAckFirstModeItem = null;
            pendingAckFirstPreviousMode = null;
            settleSuccessfulLiveQuality(pendingMode, applied);
        }
        modeSwitchInProgress = false;
        persistPresentationState();
        controlActionListener.onPresentationModeCommitted(currentPresenterMode);
        updateGlancePanel();
        revealDockTemporarily();
        schedulePanelRateReconcile();
        scheduleGameSourceReconcile();
        LimeLog.info(completedPackedClientEntry
                ? "XR: first fresh packed swap completed mode " + pendingMode
                : "XR: fresh-IDR output completed mode " + pendingMode);
    }

    private void openClientSbsHdrTransition() {
        com.limelight.Game game = hostGame();
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
        if (!controlTransportOpen()) {
            return false;
        }
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
                || currentPresenterMode != PresentationMode.CLIENT_SBS_AI) {
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

    private int effectiveWireModeFor(PresentationMode mode) {
        if (mode == PresentationMode.GAME_3D && gameProviderV1Supported) {
            return currentPresenterMode == PresentationMode.GAME_3D
                    ? gameWireMode : MoonBridge.SBS_MODE_GAME_MONO;
        }
        return XrPresentationPolicy.wireModeFor(mode);
    }

    private int effectiveTargetWireMode(PresentationMode mode) {
        return pendingLiveQualityMode == mode && pendingDesiredWireMode >= 0
                ? pendingDesiredWireMode : effectiveWireModeFor(mode);
    }

    private void reportModeSwitchFailure(String reason) {
        LimeLog.severe("XR mode switch failed: " + reason);
        if (activity instanceof com.limelight.Game) {
            ((com.limelight.Game) activity).displayMessage(
                    "Unable to switch 3D presentation mode. The previous mode is still active.");
        }
    }

    private boolean surfaceEntityUsable() {
        return !presenterDestroyed && surfaceEntity != null && !surfaceEntity.isDisposed();
    }

    private void restoreViewState(PresentationMode authoritativeStartupMode) {
        panelHeightMeters = viewStateStore.restoreHeight();
        PresentationMode savedMode = authoritativeStartupMode != null
                ? authoritativeStartupMode : PresentationMode.NORMAL;
        authoredStereoModeState.reset();
        gameWireMode = MoonBridge.SBS_MODE_GAME_MONO;
        if (savedMode == PresentationMode.GAME_3D) {
            gameSourceState.enterGame();
            if (activity.getIntent().getBooleanExtra(EXTRA_GAME_WIDEN_FAILURE, false)
                    && gameRetryQualityKey().equals(
                            activity.getIntent().getStringExtra(EXTRA_GAME_WIDEN_QUALITY))) {
                gameSourceState.recordWidenFailure();
            }
        }
        if (savedMode == PresentationMode.HOST_SBS_AI
                || savedMode == PresentationMode.GAME_3D || savedMode == PresentationMode.MOVIE_3D) {
            // Restore intent with a known initial interpretation: Host AI's negotiated SBS, or
            // ordinary mono for Game/Movie. Authored packing never survives a new connection.
            currentPresenterMode = savedMode;
        } else if (savedMode == PresentationMode.CLIENT_SBS_AI) {
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
        viewStateStore.saveHeight(cinemaController.durableHeightMeters());
    }

    /**
     * Snapshots the live rendered geometry before a settings restart tears down SceneCore. The replacement
     * Activity consumes this transient Intent state, preserving both physical size and apparent
     * size from the screen's real-world distance.
     */
    public void captureReconnectViewState(Intent reconnectIntent) {
        reconnectIntent.putExtra(EXTRA_GAME_WIDEN_FAILURE,
                currentPresenterMode == PresentationMode.GAME_3D
                        && gameSourceState.hasWidenFailure());
        reconnectIntent.putExtra(EXTRA_GAME_WIDEN_QUALITY, gameRetryQualityKey());
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
        viewStateStore.saveHeight(cinemaController.durableHeightMeters());
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
        // NvConnection gates this launch intent against the freshly negotiated Game capability.
        return currentPresenterMode == PresentationMode.GAME_3D
                ? MoonBridge.SBS_MODE_GAME_MONO : XrPresentationPolicy.wireModeFor(currentPresenterMode);
    }

    private String gameRetryQualityKey() {
        // Automatic panel-rate following must not turn reconnect into a new quality choice.
        return prefConfig.width + "x" + prefConfig.height + ":" + panelRefreshRateState.getUserCeilingHz()
                + ":" + prefConfig.bitrate + ":" + prefConfig.enableHdr
                + ":" + prefConfig.videoFormat;
    }

    private boolean hostDebugDumpAvailable() {
        boolean transitionInProgress = modeSwitchInProgress
                || pendingDecoderTransitionMode != null
                || liveQualityTransactionBusy();
        return controlTransportOpen() && hostControlExtensionsSupported
                && isHostDebugDumpAvailable(
                currentPresenterMode, streamPresentationReady, sessionControlsEnabled,
                transitionInProgress, depthStatusPhase == 2, gameProviderV1Supported);
    }

    private void updateHostDebugDumpAvailability() {
        if (dumpItem != null) {
            dumpItem.setEnabled(hostDebugDumpAvailable());
        }
    }

    /** Ask the host to capture its current Host AI or Game 3D diagnostic bundle. */
    private void requestHostDebugDump() {
        // Keep the protocol boundary guarded even if a stale accessibility or controller event
        // reaches this listener after the tile was disabled during a mode transition.
        if (!hostDebugDumpAvailable()) {
            LimeLog.warning("XR: ignoring 3D debug dump outside a supported, stable presentation");
            updateHostDebugDumpAvailability();
            return;
        }
        LimeLog.info("XR: requesting 3D debug frame dump for " + currentPresenterMode);
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

    /** Quad aspect (width/height); Game and Movie apply their authored-stereo interpretation. */
    private float aspectFor(PresentationMode mode) {
        if (mode == PresentationMode.GAME_3D || mode == PresentationMode.MOVIE_3D) {
            return authoredStereoModeState.presentationAspect(mode, fullAspect);
        }
        return fullAspect;
    }

    private SurfaceEntity.StereoMode stereoModeFor(PresentationMode mode) {
        if (mode == PresentationMode.GAME_3D) {
            return gameWireMode == MoonBridge.SBS_MODE_GAME_SBS
                    ? SurfaceEntity.StereoMode.SIDE_BY_SIDE : SurfaceEntity.StereoMode.MONO;
        }
        if (mode == PresentationMode.MOVIE_3D) {
            return authoredStereoModeState.isSideBySide(mode)
                    ? SurfaceEntity.StereoMode.SIDE_BY_SIDE : SurfaceEntity.StereoMode.MONO;
        }
        return (mode == PresentationMode.NORMAL) ? SurfaceEntity.StereoMode.MONO : SurfaceEntity.StereoMode.SIDE_BY_SIDE;
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
                && XrPresentationPolicy.canSynchronizeClientSbsHdrTransition(
                currentPresenterMode,
                streamPresentationReady,
                modeSwitchInProgress,
                liveQualityTransactionBusy() || pendingDecoderTransitionMode != null,
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
        com.limelight.Game game = hostGame();
        boolean hdr = game != null && game.isStreamHdrActive();
        StreamContainer streamContainer = game != null ? game.getStreamContainer() : null;
        // Tell the AI-input shader to tonemap PQ->SDR for ZipDepth when the stream is HDR.
        if (streamContainer != null) {
            streamContainer.setHdrInput(hdr);
        }
        if (currentPresenterMode == PresentationMode.CLIENT_SBS_AI) {
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
        } else if (currentPresenterMode != PresentationMode.CLIENT_SBS_AI) {
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

    /**
     * Re-fits the video quad to {@code mode}'s aspect at its current height (or the fallback when
     * the entity is not a quad), then refreshes the resize bounds and the panels hung off it.
     */
    private void reshapeQuadForMode(PresentationMode mode, float fallbackHeightMeters) {
        float aspect = aspectFor(mode);
        SurfaceEntity.Shape shape = surfaceEntity.getShape();
        float height = shape instanceof SurfaceEntity.Shape.Quad
                ? ((SurfaceEntity.Shape.Quad) shape).getExtents().getHeight()
                : fallbackHeightMeters;
        surfaceEntity.setShape(new SurfaceEntity.Shape.Quad(new FloatSize2d(height * aspect, height)));
        applyResizeBounds(aspect);
        repositionControlBar(height);
    }

    /** Build one control-bar tile. */
    private View buildBarItemView(BarItem item) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackground(controlSurfaceBackground(
                paletteColor(R.color.xr_surface_raised), paletteColor(R.color.xr_border_tile), 1));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClickable(true);
        col.setFocusable(true);
        col.setFocusableInTouchMode(false);
        col.setContentDescription(item.label);
        int pad = dimen(R.dimen.xr_space_xs);
        col.setPadding(pad, pad, pad,
                item.hasOptions ? dimen(R.dimen.xr_space_xl) : pad);
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

        if (item.hasOptions) {
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
        col.addView(icon);

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
        return XrPanelViews.dp(activity, v);
    }

    private int dimen(int resourceId) {
        return XrPanelViews.dimen(activity, resourceId);
    }

    private void setTextSize(TextView view, int textSizeResource) {
        XrPanelViews.setTextSize(activity, view, textSizeResource);
    }

    private void setScaledTextSize(TextView view, int textSizeResource, float scale) {
        XrPanelViews.setScaledTextSize(activity, view, textSizeResource, scale);
    }

    private android.graphics.drawable.GradientDrawable controlSurfaceBackground(
            int fillColor, int strokeColor, int strokeWidthDp) {
        return XrPanelViews.controlSurfaceBackground(activity, fillColor, strokeColor, strokeWidthDp);
    }

    private LinearLayout card(int paddingPixels) {
        return XrPanelViews.card(activity, paddingPixels);
    }

    /** Fill every hosted pixel; rounded child cards can keep their existing interaction styling. */
    private void setSolidPanelCanvas(View root) {
        XrPanelViews.setSolidPanelCanvas(activity, root);
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
        final PresentationMode selectsMode;
        boolean hasOptions;
        Runnable onTap;
        View root;
        View tapTarget;
        XrModeChevronView optionsIndicator;

        BarItem(String label, int iconRes, PresentationMode selectsMode) {
            this.label = label;
            this.iconRes = iconRes;
            this.selectsMode = selectsMode;
            this.hasOptions = selectsMode != null;
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

    public Surface getVideoSurface() {
        return videoSurface;
    }

    /**
     * Adopts a newly obtained SceneCore presentation Surface and immediately restores its durable
     * frame-rate vote. SceneCore may replace the Surface when pixel dimensions change.
     */
    private void adoptVideoSurface(Surface surface) {
        videoSurface = surface;
        applyPresentationFrameRatePreference();
    }

    /**
     * Reapplies mode-aware panel preferences to the window and actual SceneCore output swapchain.
     *
     * <p>Best effort: the Surface may already be retiring during a mode transition. The next
     * {@link #adoptVideoSurface(Surface)} call reapplies the same durable value to its replacement.
     * The temporary effective stream FPS is intentionally not used here.</p>
     */
    private void applyPresentationFrameRatePreference() {
        // Prefer the advertised 72 Hz panel mode for Client SBS's low stream ceilings.
        // Use the durable ceiling so a new 90 FPS request releases the preference even if
        // its first ACK remains capped to the current panel rate.
        clientPanelRefreshRatePreference.apply(activity.getWindow(),
                ServerHelper.getActiveDisplay(activity, prefConfig),
                PanelRefreshRateState.shouldPreferClientPanelRate(panelRefreshRateState, currentPresenterMode));
        Surface surface = videoSurface;
        int ceilingHz = PanelRefreshRateState.durableSurfaceFrameRateVoteHz(panelRefreshRateState, currentPresenterMode);
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
            LimeLog.info("XR: SceneCore presentation Surface requests "
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
    public boolean setClientSbsSurfaceSize(boolean fullStereo) {
        if (surfaceEntity == null || surfaceEntity.isDisposed()) {
            return false;
        }
        int width = fullStereo ? getClientSbsSurfaceWidth() : prefConfig.width;
        int height = fullStereo ? getClientSbsSurfaceHeight() : prefConfig.height;
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(width, height));
        adoptVideoSurface(surfaceEntity.getSurface());
        return videoSurface != null && videoSurface.isValid();
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
        int[] packed = XrPresentationPolicy.clientSbsPackedDimensions(perEyeWidth, perEyeHeight);
        if (packed == null || surfaceEntity == null || surfaceEntity.isDisposed()) {
            return false;
        }
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(packed[0], packed[1]));
        adoptVideoSurface(surfaceEntity.getSurface());
        return videoSurface != null && videoSurface.isValid();
    }

    /** Final XR swapchain width for Client SBS: two negotiated-size eye views side by side. */
    public int getClientSbsSurfaceWidth() {
        int[] packed = XrPresentationPolicy.clientSbsPackedDimensions(prefConfig.width, prefConfig.height);
        return packed != null ? packed[0] : 0;
    }

    /** Final XR height for Client SBS, identical to the negotiated stream height. */
    public int getClientSbsSurfaceHeight() {
        return prefConfig.height;
    }

    /** Kept for {@code StreamContainer}; the arithmetic lives in {@link XrPresentationPolicy}. */
    static int[] clientSbsPackedDimensions(int perEyeWidth, int perEyeHeight) {
        return XrPresentationPolicy.clientSbsPackedDimensions(perEyeWidth, perEyeHeight);
    }

    private int hostSbsVideoFormat = MoonBridge.VIDEO_FORMAT_H265;

    /** Updates the host-SBS allocation from the codec actually selected by RTSP/MediaCodec. */
    public void setHostSbsVideoFormat(int videoFormat) {
        boolean resizeRequired = XrPresentationPolicy.hostSbsFormatChangeRequiresResize(
                currentPresenterMode, prefConfig.width, prefConfig.height,
                hostSbsVideoFormat, videoFormat);
        hostSbsVideoFormat = videoFormat;
        if (resizeRequired) {
            setHostSurfaceSize(true);
        }
    }

    private void updateDecoderStreamGeometry(
            com.limelight.Game game, PresentationMode mode,
            int logicalWidth, int logicalHeight, int fps) {
        int[] encodedDimensions;
        if (pendingExactEncodedWidth > 0 && pendingExactEncodedHeight > 0) {
            encodedDimensions = new int[] {
                    pendingExactEncodedWidth, pendingExactEncodedHeight};
        } else {
            encodedDimensions = XrPresentationPolicy.decoderStreamDimensions(
                    mode, logicalWidth, logicalHeight, hostSbsVideoFormat);
        }
        game.updateDecoderStreamGeometry(
                encodedDimensions[0], encodedDimensions[1], fps);
    }

    /** Re-pin the XR surface for a host presentation: the packed SBS frame ({@code 2W' x H'}) when a
     *  host depth mode's SBS is active, or the plain 2D frame ({@code W x H}) for NORMAL. Re-fetches
     *  the surface in case the resize re-creates it. Main-thread only (SceneCore is Activity-bound). */
    public boolean setHostSurfaceSize(boolean sbs) {
        return setHostSurfaceSize(sbs, prefConfig.width, prefConfig.height);
    }

    /** Explicit logical geometry avoids publishing new preferences before an async handoff lands. */
    boolean setHostSurfaceSize(boolean sbs, int logicalWidth, int logicalHeight) {
        if (surfaceEntity == null || surfaceEntity.isDisposed()) {
            return false;
        }
        int w;
        int h;
        boolean pendingExactGeometry = pendingDesiredWireMode >= 0
                && XrPresentationPolicy.isPackedWireMode(pendingDesiredWireMode) == sbs
                && pendingExactEncodedWidth > 0 && pendingExactEncodedHeight > 0;
        boolean committedExactGeometry = !pendingExactGeometry
                && XrPresentationPolicy.isPackedWireMode(effectiveWireModeFor(currentPresenterMode)) == sbs
                && currentExactEncodedWidth > 0 && currentExactEncodedHeight > 0;
        if (pendingExactGeometry) {
            w = pendingExactEncodedWidth;
            h = pendingExactEncodedHeight;
        } else if (committedExactGeometry) {
            w = currentExactEncodedWidth;
            h = currentExactEncodedHeight;
        } else if (sbs) {
            int[] packed = PreferenceConfiguration.hostSbsPackedDimensions(
                    logicalWidth, logicalHeight, hostSbsVideoFormat);
            w = packed[0];
            h = packed[1];
        } else {
            w = logicalWidth;
            h = logicalHeight;
        }
        surfaceEntity.setSurfacePixelDimensions(new IntSize2d(w, h));
        adoptVideoSurface(surfaceEntity.getSurface());
        return videoSurface != null && videoSurface.isValid();
    }

    /**
     * Tear down the entity/session. Mirrors {@code Stereo3DRenderer.onSurfaceDestroyedAsync()} /
     * {@code StreamContainer.onDestroy()} ordering.
     */
    public void onDestroy() {
        if (presenterDestroyed) {
            return;
        }
        cinemaController.restoreCinemaBackground();
        presenterDestroyed = true;
        statsUi.cancelStatsPanelFit();
        depthStatusHandler.removeCallbacks(showDepthStatusRunnable);
        setDepthStatusVisible(false);
        clientPanelRefreshRatePreference.apply(activity.getWindow(), null, false);
        onHostActivityStopped();
        if (dockGazeTracker != null) {
            dockGazeTracker.close();
            dockGazeTracker = null;
        }
        // Native connection cleanup has already completed by StreamContainer teardown time. Only
        // clear local state here; the synchronous pre-stop hook owns the final transport write.
        controlTransportClosing = true;
        clearHostSbsTelemetrySubscriptionState();
        modeOptionsStatusHandler.removeCallbacks(refreshClientOptionsStatus);
        dockVisibilityHandler.removeCallbacks(collapseDockRunnable);
        cancelLiveQualityAckTimeout();
        liveQualityHandler.removeCallbacks(panelRateReconcileRunnable);
        panelRateReconcilePosted = false;
        liveQualityHandler.removeCallbacks(gameSourceReconcileRunnable);
        gameSourceReconcilePosted = false;
        invalidateGameSourceProof();
        gameSourceState.leaveGame();
        resetVideoModeStateForTeardown();
        viewStateStore.saveHeight(cinemaController.durableHeightMeters());
        if (surfaceEntity != null) {
            if (!surfaceEntity.isDisposed()) {
                surfaceEntity.dispose();
            }
            surfaceEntity = null;
        }
        disposePanel(barPanel);
        barPanel = null;
        disposePanel(modeOptionsPanel);
        modeOptionsPanel = null;
        statsUi.disposePanel();
        disposePanel(auxiliaryPanel);
        auxiliaryPanel = null;
        glanceStrip.disposePanel();
        disposePanel(depthStatusPanel);
        depthStatusPanel = null;
        depthStatusRoot = null;
        depthStatusText = null;
        transientMessageHandler.removeCallbacks(hideTransientMessageRunnable);
        disposePanel(transientMessagePanel);
        transientMessagePanel = null;
        transientMessageText = null;
        glanceStrip.releaseViews();
        controlBarRow = null;
        dockRevealPill = null;
        dockHoverTarget = null;
        dockFocusTarget = null;
        videoSurface = null;
        statsUi.releaseViews();
        settingsItem = null;
        cinemaItem = null;
        cinemaEnvironmentChoiceGroup = null;
        cinemaActionButton = null;
        statsItem = null;
        dumpItem = null;
        cinemaController.resetViewState();
        lastCinemaTileTapMs = 0L;
        lastCinemaActionTapMs = 0L;
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

    private static void disposePanel(PanelEntity panel) {
        if (panel != null && !panel.isDisposed()) {
            panel.dispose();
        }
    }
}
