package com.limelight.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.Game;
import com.limelight.binding.video.StreamPerformanceSnapshot;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.ClientSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.AuthoredStereoModeState;
import com.limelight.ui.xrcontrols.GameStereoSourceState;
import com.limelight.ui.xrcontrols.AuthoredStereoModeState.MoviePictureFormat;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;
import com.limelight.sbs.HostSbsTelemetrySnapshot;
import com.limelight.sbs.HostSbsTelemetryTracker;
import com.limelight.ui.xrcontrols.XrControlPanelLayout;
import com.limelight.ui.xrcontrols.XrControlUiState;
import com.limelight.ui.xrcontrols.XrModeChevronView;
import com.limelight.utils.ClientSbsPerformanceSnapshot;
import com.limelight.utils.Stereo3DRenderer;
import com.limelight.utils.ServerHelper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Presentation owner for the single XR route. Every presentation mode shows the stream on one
 * SceneCore {@link SurfaceEntity}: 2D, Host AI 3D, Game 3D and Movie 3D decode straight into its
 * {@link Surface}, while Client AI 3D decodes into {@link StreamContainer}'s GL renderer, which
 * draws its packed side-by-side output into that Surface. A new host session starts in 2D; only
 * a host-confirmed resume restores the last successful presentation intent of that app on that
 * PC.
 *
 * <p>The video {@link Surface} is published through {@link OnSurfaceReadyListener}, so
 * {@code StreamContainer} supplies the initial decoder target through
 * {@code setRenderTarget(...)} and every live replacement through its guarded asynchronous
 * output-surface handoff.</p>
 *
 * <p>The quad starts 2 m in front of the viewer at the per-PC height, keeps the selected
 * resolution's aspect, and can be moved (with a minimum distance clamp) and resized. One dock
 * panel beneath it holds the five single-select presentation modes and the Settings, Cinema, Stats
 * and Disconnect actions; one contextual panel below the dock shows a mode's or Cinema's options;
 * App Settings and Stats open beside the video. Mode switches and live stream-quality changes run
 * through the atomic presentation ACK and the decoder transition gate. See
 * docs/android-xr-sbs.md. {@link #onDestroy()} tears the session down and saves the per-PC
 * height.</p>
 */
@SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
public class XrStreamPresenter {

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

    /** The control-bar items (one clickable tile each, all hosted in the dock panel). */
    private final List<BarItem> barItems = new ArrayList<>();
    private final XrControlUiState controlUiState = new XrControlUiState();
    private BarItem settingsItem;
    private BarItem cinemaItem;
    private BarItem statsItem;
    private BarItem dumpItem;

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
    /** Mode generation that owns the armed same-geometry Client SBS packed-swap proof. */
    private int pendingClientPackedSwapProofGeneration;
    /** Same budget as the Client SBS mode-entry packed-swap proof. */
    private static final long CLIENT_PACKED_SWAP_PROOF_TIMEOUT_MS = 2000L;
    private final Runnable clientPackedSwapProofTimeoutRunnable =
            this::onClientPackedSwapProofTimeout;
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
    private final AuthoredStereoModeState authoredStereoModeState = new AuthoredStereoModeState();

    private boolean hostActivityStarted;

    private final XrDockController dockController;
    private final XrModeOptionsPane modeOptionsPane;
    private final XrPanelPoseSolver poseSolver = new XrPanelPoseSolver(
            new XrPanelPoseSolver.Host() {
                @Override
                public Session session() {
                    return session;
                }

                @Override
                public SurfaceEntity videoEntity() {
                    return surfaceEntity;
                }

                @Override
                public float videoWidthMeters(float videoHeightMeters) {
                    return videoHeightMeters * aspectFor(currentPresenterMode);
                }

                @Override
                public int dockTileCount() {
                    return barItems.size();
                }

                @Override
                public float modeOptionsHeightMeters() {
                    return modeOptionsPane.heightMeters();
                }
            });
    private final XrStatsPanel statsUi;
    private final XrGlanceStrip glanceStrip;
    private final XrSessionSettingsPane sessionSettingsPane;
    private volatile boolean statsVisible;
    private boolean controlUiStateApplied;
    private XrControlUiState.Surface appliedContextualSurface = XrControlUiState.Surface.NONE;
    private boolean appliedStatsRequested;
    private final DevicePerformanceSampler devicePerformanceSampler =
            new DevicePerformanceSampler();
    private final HostSbsTelemetryTracker hostSbsTelemetryTracker =
            new HostSbsTelemetryTracker();
    private final XrHostTelemetrySubscription hostTelemetrySubscription =
            new XrHostTelemetrySubscription(hostSbsTelemetryTracker,
                    new XrHostTelemetrySubscription.Host() {
                        @Override
                        public boolean shouldObserveHostSbsTelemetry() {
                            return XrStreamPresenter.this.shouldObserveHostSbsTelemetry();
                        }

                        @Override
                        public int sendHostTelemetryControl(boolean enabled, boolean focused,
                                                            int requestId, int intervalMs) {
                            return XrStreamPresenter.this.sendHostTelemetryControl(
                                    enabled, focused, requestId, intervalMs);
                        }
                    });
    /**
     * Set before native connection teardown starts. Once set, this presenter must never call any
     * control-stream API because moonlight-common destroys its transport mutexes during
     * {@code NvConnection.stop()}.
     */
    private boolean controlTransportClosing;
    private boolean presenterDestroyed;
    /** Apollo-3D control messages are opt-in; regular Sunshine/Apollo must never receive them. */
    private boolean hostControlExtensionsSupported = true;
    /** Loading overlay and host depth phase, driven by the host's 0x3006 push via
     *  {@link #onDepthStatus}. */
    private final XrDepthStatusOverlay depthStatusOverlay;
    private final XrTransientMessagePanel transientMessagePanel;

    /** Comfortable cinema-preset quad height in meters; mode switches keep this height and vary
     *  width. Shared by the initial placement and Cinema View so both land at the same size. Tune
     *  by feel on the headset (at the ~2 m default distance, 2.0 m ≈ a large cinema screen). */
    private static final float DEFAULT_PANEL_HEIGHT_METERS =
            XrViewStateStore.DEFAULT_HEIGHT_METERS;
    private float panelHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;

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
        this.depthStatusOverlay = new XrDepthStatusOverlay(activity, () -> presenterDestroyed);
        this.transientMessagePanel = new XrTransientMessagePanel(activity);
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
                        return poseSolver.statsPose(videoHeightMeters);
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

                    @Override
                    public ModeStreamQualityModel selectedModeQualityModel() {
                        for (ModeStreamQualityModel model : modeStreamQualityModels.values()) {
                            if (model.selected) {
                                return model;
                            }
                        }
                        return null;
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
                        return poseSolver.currentHeadPose();
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
        this.dockController = new XrDockController(activity,
                new XrDockController.Host() {
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
                    public boolean presenterDestroyed() {
                        return presenterDestroyed;
                    }

                    @Override
                    public String currentModeLabel() {
                        return modeLabel(currentPresenterMode);
                    }

                    @Override
                    public boolean canAutoCollapseDock(boolean dockHovered, boolean dockFocused) {
                        return XrDockController.shouldAutoCollapseDock(
                                streamPresentationReady, sessionControlsEnabled,
                                controlUiState.getVisibleSurface(), controlUiState.isStatsVisible(),
                                reconnectPending, modeSwitchInProgress || liveQualityTransactionBusy(),
                                pendingDecoderTransitionMode != null, depthStatusOverlay.isBusy(),
                                dockHovered, dockFocused);
                    }
                });
        this.modeOptionsPane = new XrModeOptionsPane(activity, prefConfig, controlUiState,
                modeStreamQualityModels, new XrModeOptionsPane.Host() {
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
                    public boolean presenterDestroyed() {
                        return presenterDestroyed;
                    }

                    @Override
                    public PresentationMode currentPresenterMode() {
                        return currentPresenterMode;
                    }

                    @Override
                    public ControlActionListener controlActionListener() {
                        return XrStreamPresenter.this.controlActionListener;
                    }

                    @Override
                    public boolean settingsControlsEnabled() {
                        return XrStreamPresenter.this.settingsControlsEnabled();
                    }

                    @Override
                    public boolean sessionControlsEnabled() {
                        return sessionControlsEnabled;
                    }

                    @Override
                    public boolean streamPresentationReady() {
                        return streamPresentationReady;
                    }

                    @Override
                    public boolean modeSwitchInProgress() {
                        return modeSwitchInProgress;
                    }

                    @Override
                    public boolean liveQualityTransactionBusy() {
                        return XrStreamPresenter.this.liveQualityTransactionBusy();
                    }

                    @Override
                    public ClientSbsModeSettingsModel clientSbsModeSettingsModel() {
                        return clientSbsModeSettingsModel;
                    }

                    @Override
                    public String modeLabel(PresentationMode mode) {
                        return XrStreamPresenter.this.modeLabel(mode);
                    }

                    @Override
                    public String hostDepthStatusText() {
                        return XrStreamPresenter.this.hostDepthStatusText();
                    }

                    @Override
                    public String clientSbsRuntimeStatus(ClientSbsModeSettingsModel model) {
                        return XrStreamPresenter.this.clientSbsRuntimeStatus(model);
                    }

                    @Override
                    public int gameSourceStatusText() {
                        return XrStreamPresenter.this.gameSourceStatusText();
                    }

                    @Override
                    public int gameSourceDetailText() {
                        return XrStreamPresenter.this.gameSourceDetailText();
                    }

                    @Override
                    public String moviePictureFormatId() {
                        return XrStreamPresenter.this.moviePictureFormatId();
                    }

                    @Override
                    public boolean onMoviePictureFormatSelected(String choiceId) {
                        return XrStreamPresenter.this.onMoviePictureFormatSelected(choiceId);
                    }

                    @Override
                    public boolean cinemaExpanded() {
                        return cinemaController.isExpanded();
                    }

                    @Override
                    public boolean onCinemaEnvironmentSelected(String value) {
                        return XrStreamPresenter.this.onCinemaEnvironmentSelected(value);
                    }

                    @Override
                    public void onCinemaActionTapped() {
                        XrStreamPresenter.this.onCinemaActionTapped();
                    }

                    @Override
                    public float panelHeightMeters() {
                        return panelHeightMeters;
                    }

                    @Override
                    public Pose modeOptionsPose(float videoHeightMeters) {
                        return poseSolver.modeOptionsPose(videoHeightMeters);
                    }

                    @Override
                    public XrControlPanelLayout controlBarLayout(float videoHeightMeters) {
                        return poseSolver.controlBarLayout(videoHeightMeters);
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
            if (reconnectViewState.cinemaViewExpanded) {
                // The handed-over quad is the Cinema preset. Resume it so Exit Cinema and the
                // durable per-PC height both use the pre-Cinema screen.
                cinemaController.resumeExpanded(reconnectViewState.cinemaRestoreHeightMeters,
                        reconnectViewState.cinemaRestorePose);
            }
            LimeLog.info("XR: applying reconnect view handoff at height "
                    + panelHeightMeters + " m"
                    + (reconnectViewState.realWorldPose != null ? " with pose" : "")
                    + (reconnectViewState.cinemaViewExpanded ? " in Cinema View" : ""));
        }
        // On a host-confirmed resume, restore direct Host AI/Game/Movie intent immediately. Client SBS
        // is deferred until after frame 1 because it requires a live decoder-to-GL handoff. A new
        // host session's startup mode is Normal.
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
            hostTelemetrySubscription.clearState();
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
        hostTelemetrySubscription.reconcile();
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
        modeOptionsPane.updateGameSourceViews();
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
            modeOptionsPane.updateGameSourceViews();
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
            modeOptionsPane.updateGameSourceViews();
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
            modeOptionsPane.updateGameSourceViews();
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

    /** Replace the immutable applied/pending snapshot and refresh an open Settings panel. */
    public void setSessionSettingsModel(SessionSettingsModel model) {
        sessionSettingsModel = java.util.Objects.requireNonNull(model, "model");
        if (controlUiState.getVisibleSurface()
                == XrControlUiState.Surface.SESSION_SETTINGS && auxiliaryContentHost != null) {
            sessionSettingsPane.updateSessionSettingsView();
        }
    }

    /**
     * Atomically replaces all settings snapshots and updates each open control tree only once.
     *
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
            modeOptionsPane.updateModeOptionsView();
        }
        updateGlancePanel();
        dockController.updateDockVisibilityPolicy();
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
        modeOptionsPane.setModeControlsEnabled(enabled);
        sessionSettingsPane.setBitrateControlsEnabled(enabled);
        modeOptionsPane.setModeDefaultsEnabled(enabled);
        modeOptionsPane.updateCinemaOptionsView();
        for (BarItem item : barItems) {
            if (item.selectsMode != null && item.tapTarget != null) {
                item.setEnabled(enabled && streamPresentationReady
                        && XrPresentationPolicy.isPresentationModeSupported(
                                item.selectsMode, hostControlExtensionsSupported));
            }
        }
        updateGlancePanel();
        dockController.revealDockTemporarily();
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
        // capability, which requires Full Space mode. The Game activity starts in Full Space
        // through PROPERTY_XR_ACTIVITY_START_MODE = XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED in
        // AndroidManifest.xml. Log the capability so a Full Space misconfiguration is
        // diagnosable on-device.
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
        dockController.setControlBarRow(bar);

        TextView revealPill = dockController.createRevealPill();
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        panelRoot.addView(revealPill, pillParams);

        // Bake the initial highlights into the views before the panel is created.
        statsVisible = prefConfig.enablePerfOverlay;
        if (statsVisible) {
            controlUiState.showStats();
        }
        updateModeSelection();

        // Width scales with the tile count so each tile stays square (tile size = bar height),
        // plus a little for the divider — adding tiles widens the bar instead of squeezing them.
        XrControlPanelLayout layout = poseSolver.controlBarLayout(videoHeightMeters);
        dockController.createBarPanel(panelRoot, layout, poseSolver.barPose(videoHeightMeters));

        // The contextual row is a single panel, not one entity per control. It can therefore tilt
        // toward the face independently while its ordinary child Views retain native gaze taps.
        modeOptionsPane.createPanel(layout, videoHeightMeters);

        if (statsVisible) {
            statsUi.createStatsPanel(videoHeightMeters);
        }
        createAuxiliaryPanel(videoHeightMeters);
        applyControlUiState(false, "initial");
        depthStatusOverlay.createPanel(session, surfaceEntity);
        transientMessagePanel.createPanel(session, surfaceEntity);
        glanceStrip.createGlancePanel(videoHeightMeters);
        updateGlancePanel();
        dockController.updateDockVisibilityPolicy();
    }

    /** Create the left-side entity used by shared Session Settings. */
    private void createAuxiliaryPanel(float videoHeightMeters) {
        auxiliaryContentHost = new FrameLayout(activity);
        setSolidPanelCanvas(auxiliaryContentHost);
        auxiliaryPanel = PanelEntity.create(
                session, auxiliaryContentHost,
                new FloatSize2d(XrPanelPoseSolver.AUXILIARY_WIDTH_METERS,
                        XrPanelPoseSolver.AUXILIARY_HEIGHT_METERS),
                "xr-session-controls", poseSolver.statsPose(videoHeightMeters), surfaceEntity);
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
        dockController.updateDockGazeTracking();
        cinemaController.applyCinemaBackground();
        devicePerformanceSampler.resetCpuBaseline();
        glanceStrip.reconcileGlanceLoadTicker();
        modeOptionsPane.reconcileClientOptionsStatusTicker();
        dockController.resyncRevealFocusAttention();
    }

    /** Stop all passive load sampling while the streaming Activity is backgrounded. */
    public void onHostActivityStopped() {
        cinemaController.restoreCinemaBackground();
        hostActivityStarted = false;
        dockController.updateDockGazeTracking();
        dockController.resetRevealAttention();
        glanceStrip.stopLoadTicker();
        modeOptionsPane.stopClientOptionsStatusTicker();
        devicePerformanceSampler.resetCpuBaseline();
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
        // retains the user's durable FPS ceiling while panel-follow is temporarily below it. The
        // dynamic range is the negotiated stream state, not the HDR preference.
        StreamQualityTuple live = currentQualityTuple();
        com.limelight.Game game = hostGame();
        boolean hdrActive = game != null && game.isStreamHdrActive();
        glanceStrip.setStream(XrStatsFormat.formatGlanceStream(activity,
                live.resolution, glanceFrameRateText(live.frameRate), live.bitrateKbps,
                hdrActive ? activity.getString(R.string.xr_glance_hdr)
                        : activity.getString(R.string.xr_glance_sdr)));

        boolean liveStatus = streamPresentationReady && sessionControlsEnabled && !settingsTransactionPending
                && !modeSwitchInProgress && !liveQualityTransactionBusy()
                && pendingDecoderTransitionMode == null
                && !depthStatusOverlay.isBusy() && !reconnectPending;
        int statusText;
        if (!streamPresentationReady) {
            statusText = R.string.xr_glance_starting;
        }
        else if (!sessionControlsEnabled) {
            statusText = R.string.xr_glance_reconnecting;
        }
        else if (settingsTransactionPending || modeSwitchInProgress || pendingDecoderTransitionMode != null || depthStatusOverlay.isBusy()) {
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
        dockController.updateDockRevealPill(statusText, liveStatus);
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

    /**
     * The live tuple with the user's durable FPS ceiling in place of a lower panel-follow rate.
     * Geometry and the requested wire bitrate are the ones the stream carries now.
     */
    private StreamQualityTuple durableLiveQualityTuple() {
        StreamQualityTuple live = currentQualityTuple();
        int ceilingHz = panelRefreshRateState.getUserCeilingHz();
        return ceilingHz > Math.round(prefConfig.fps)
                ? new StreamQualityTuple(live.resolution, String.valueOf(ceilingHz),
                        live.bitrateKbps)
                : live;
    }

    private void onModeTileTapped(BarItem item) {
        dockController.revealDockTemporarily();
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
        dockController.revealDockTemporarily();
        controlUiState.toggle(XrControlUiState.Surface.SESSION_SETTINGS);
        applyControlUiState(true, "session settings");
    }

    private void requestDisconnect() {
        dockController.revealDockTemporarily();
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
            modeOptionsPane.updateCinemaOptionsView();
            return;
        }
        dockController.revealDockTemporarily();
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
        modeOptionsPane.updateCinemaOptionsView();
    }

    /** Direct dock action: Stats remains independent of every contextual settings surface. */
    private void onStatsTileTapped() {
        long now = android.os.SystemClock.uptimeMillis();
        if (!shouldAcceptControlToggle(now, lastStatsTileTapMs)) {
            return;
        }
        lastStatsTileTapMs = now;
        dockController.revealDockTemporarily();
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
            hostTelemetrySubscription.reconcile();
        }

        boolean showModeOptions = visible == XrControlUiState.Surface.MODE_OPTIONS
                || visible == XrControlUiState.Surface.CINEMA_OPTIONS;
        modeOptionsPane.applyVisibility(showModeOptions);

        boolean showAuxiliary = visible == XrControlUiState.Surface.SESSION_SETTINGS;
        if (showAuxiliary && auxiliaryContentHost != null) {
            renderAuxiliaryContent();
        }
        else if (!showAuxiliary) {
            sessionSettingsPane.clearSessionSettingsReferences();
        }
        if (auxiliaryPanel != null && !auxiliaryPanel.isDisposed()) {
            if (showAuxiliary) {
                XrPanelPoseSolver.updatePanelPose(auxiliaryPanel,
                        poseSolver.sessionSettingsPose(panelHeightMeters));
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
        dockController.revealDockTemporarily();
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
        dockController.revealDockTemporarily();
        return true;
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
        switch (depthStatusOverlay.phase()) {
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

    /** Shows a message centered just in front of the video. Must be called on the UI thread. */
    public boolean showTransientMessage(CharSequence message, long durationMs) {
        return transientMessagePanel.show(message, durationMs);
    }

    private void resetHostDepthStatus() {
        depthStatusOverlay.reset();
        updateHostDebugDumpAvailability();
    }

    /**
     * Host depth-engine phase push (Apollo 0x3006), on the UI thread: 0 = idle/failure,
     * 1 = engine loading/building, 2 = ready, 3 = device-pipeline initialization. The overlay
     * records the phase and shows or hides its progress indicator; the controls that depend on
     * the phase refresh here once the panel hierarchy exists.
     */
    public void onDepthStatus(int phase) {
        if (!depthStatusOverlay.onPhase(phase)) {
            return;
        }
        if (controlUiState.getVisibleSurface() == XrControlUiState.Surface.MODE_OPTIONS
                && PresentationMode.HOST_SBS_AI.name().equals(controlUiState.getModeOptionsId())) {
            modeOptionsPane.renderModeOptions();
        }
        updateGlancePanel();
        dockController.revealDockTemporarily();
    }

    /** Main-thread delivery of an already-parsed immutable host telemetry body. */
    public void onHostSbsTelemetryState(
            HostSbsTelemetrySnapshot snapshot, long receivedAtMs) {
        hostTelemetrySubscription.onState(snapshot, receivedAtMs);
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
        liveQualityHandler.removeCallbacks(clientPackedSwapProofTimeoutRunnable);
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

        boolean wasTelemetryRequested = hostTelemetrySubscription.clearState();
        if (wasTelemetryRequested) {
            // This is deliberately the only send allowed after the fence closes. Game invokes the
            // hook synchronously before it starts NvConnection.stop(), so the mutex is still live.
            MoonBridge.sendHostSbsTelemetrySubscription(
                    false, false, hostTelemetrySubscription.nextRequestId(),
                    XrHostTelemetrySubscription.FOCUSED_INTERVAL_MS);
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
                         ClientSbsPerformanceSnapshot clientSbs,
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
            modeOptionsPane.renderModeOptions();
        }
        updateModeOptionsIndicators();
        updateGlancePanel();
        dockController.revealDockTemporarily();
    }

    private void repositionStatsPanel() {
        if (statsVisible && statsUi.panel() != null && !statsUi.panel().isDisposed()) {
            XrPanelPoseSolver.updatePanelPose(statsUi.panel(),
                    poseSolver.statsPose(panelHeightMeters));
        }
        XrControlUiState.Surface visible = controlUiState.getVisibleSurface();
        if (auxiliaryPanel != null && !auxiliaryPanel.isDisposed()) {
            if (visible == XrControlUiState.Surface.SESSION_SETTINGS) {
                XrPanelPoseSolver.updatePanelPose(auxiliaryPanel,
                        poseSolver.sessionSettingsPose(panelHeightMeters));
            }
        }
    }

    /** Enable presentation switching after MediaCodec has rendered the stream's first frame. */
    public void onFirstVideoFrameRendered() {
        if (!controlTransportOpen() || streamPresentationReady) {
            return;
        }

        streamPresentationReady = true;
        hostTelemetrySubscription.reconcile();
        // A Cinema View resumed from a settings restart applies its background once the control
        // transport is open; an already applied override makes this a no-op.
        cinemaController.applyCinemaBackground();
        for (BarItem item : barItems) {
            if (item.selectsMode != null) {
                item.setEnabled(settingsControlsEnabled() && XrPresentationPolicy.isPresentationModeSupported(
                        item.selectsMode, hostControlExtensionsSupported));
            }
        }
        LimeLog.info("XR: first video frame rendered; presentation switching enabled");
        updateGlancePanel();
        dockController.revealDockTemporarily();
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
        if (dockController.hasBarPanel()) {
            dockController.setBarPose(poseSolver.barPose(videoHeightMeters));
        }
        modeOptionsPane.reposition(videoHeightMeters);
        repositionStatsPanel();
        depthStatusOverlay.reposition();
        transientMessagePanel.reposition();
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
        updateGlancePanel();
        dockController.revealDockTemporarily();
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
        // Without a target-mode delta the switch carries the live tuple at the user's durable FPS
        // ceiling: a panel-follow rate below that ceiling is effective-only and must not become
        // the target mode's saved FPS.
        StreamQualityTuple requestedTarget = applyTargetQuality && targetQuality != null
                ? targetQuality.pendingQuality
                : durableLiveQualityTuple();
        StreamQualityTuple target =
                panelRefreshRateState.capUserQuality(requestedTarget, prefConfig.fps);
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
        pendingDurableUserQuality = requestedTarget;
        // The mode crossing supplies the one post-ACK decoder/presentation proof even if the
        // quality delta itself is only FPS or bitrate.
        liveQualityChangeInProgress = true;
        liveQualityConfirmations.begin(
                true, nextMode == PresentationMode.CLIENT_SBS_AI);

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

        // A rejected local send leaves the old Game stream and the old host depth status intact.
        // ACK, source-status and depth-status delivery are serialized on this same main thread,
        // so retiring them now still precedes any reply to the queued request.
        if (previousMode == PresentationMode.GAME_3D || nextMode == PresentationMode.GAME_3D) {
            invalidateGameSourceProof();
        }
        if (XrPresentationPolicy.resetsHostDepthStatusAtTransitionStart(previousMode, nextMode)) {
            resetHostDepthStatus();
        }

        armLiveQualityAckTimeout();
        updateGlancePanel();
        dockController.revealDockTemporarily();
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
                dockController.revealDockTemporarily();
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
        return applyLiveStreamQuality(
                panelRefreshRateState.capUserQuality(target, prefConfig.fps),
                LiveQualityRequestOrigin.USER, target);
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
        dockController.revealDockTemporarily();

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
                // The cancelled transition never reports output. A later request must open its
                // own decoder transition rather than wait on this generation.
                decoderTransitionGenerations.clearMode();
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
            modeOptionsPane.updateGameSourceViews();
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
     * Decoder callback for the transaction's one decoder transition. A geometry change closes
     * that transition's gate before the request is sent; every other transaction begins it after
     * the APPLIED ACK. Either way the gate opens only once the acknowledged geometry has been
     * adopted, and the transaction settles only on output that matches the host-acknowledged
     * raster (plus, for Client SBS, its packed-swap proof).
     */
    private void finishPendingLiveQualityChange() {
        com.limelight.Game game = hostGame();
        if (!liveQualityChangeInProgress || game == null || surfaceEntity == null
                || surfaceEntity.isDisposed()) {
            decoderTransitionGenerations.clearMode();
            clearLiveQualityChange();
            return;
        }

        // Only output recorded after beginPostAckDecoderConfirmation() can settle the request;
        // that call discards anything recorded earlier.
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
                    // No resize transaction, and so no resize watchdog, owns this proof.
                    pendingClientPackedSwapProofGeneration = packedProofGeneration;
                    liveQualityHandler.postDelayed(clientPackedSwapProofTimeoutRunnable,
                            CLIENT_PACKED_SWAP_PROOF_TIMEOUT_MS);
                }
            }
        }
        finishConfirmedLiveQualityChange(game);
    }

    /** The armed same-geometry packed proof never swapped: the presentation is unproven. */
    private void onClientPackedSwapProofTimeout() {
        if (!pendingClientPackedSwapProofArmed || !liveQualityChangeInProgress
                || decoderTransitionGenerations.currentModeGeneration()
                != pendingClientPackedSwapProofGeneration) {
            return;
        }
        LimeLog.severe("XR: Client SBS post-ACK packed proof timed out; forcing reconnect");
        requireMandatoryLiveQualityResync(
                shouldCommitStagedSettingsForResync(pendingLiveQualityOrigin), false);
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
        dockController.revealDockTemporarily();
        LimeLog.info("XR: live quality settled at " + applied);
    }

    private void settleSuccessfulLiveQuality(PresentationMode requestMode,
                                             StreamQualityTuple applied) {
        LiveQualityRequestOrigin origin = pendingLiveQualityOrigin;
        StreamQualityTuple durableRequested = pendingDurableUserQuality;
        StreamQualityTuple durableApplied = XrPresentationPolicy.durableUserQuality(applied, durableRequested);

        if (pendingExactEncodedWidth > 0 && pendingExactEncodedHeight > 0) {
            currentExactEncodedWidth = pendingExactEncodedWidth;
            currentExactEncodedHeight = pendingExactEncodedHeight;
        }

        if (origin == LiveQualityRequestOrigin.PANEL_FOLLOW) {
            panelRefreshRateState.automaticRequestSucceeded(
                    Math.round(XrPresentationPolicy.parseFrameRate(
                            applied != null ? applied.frameRate : "0", prefConfig.fps)));
        } else if (origin != LiveQualityRequestOrigin.GAME_SOURCE && durableApplied != null) {
            panelRefreshRateState.userRequestSucceeded(
                    XrPresentationPolicy.parseFrameRate(durableApplied.frameRate, prefConfig.fps));
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
        modeOptionsPane.updateGameSourceViews();
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
            // Only a failed mono-to-stereo widening latches the attempt. A failed same-mode
            // reconfirmation can come from a transient host display rebuild, so the reconnect
            // clears source proof without blocking a fresh widening attempt afterwards.
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
        liveQualityHandler.removeCallbacks(clientPackedSwapProofTimeoutRunnable);
        cancelLiveQualityAckTimeout();
        resetPendingVideoModeRequest();
        abortPendingModeStart();
        updateGlancePanel();
        dockController.revealDockTemporarily();
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
            dockController.revealDockTemporarily();
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
            dockController.revealDockTemporarily();
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
        } else if (previousMode != PresentationMode.GAME_3D
                && nextMode == PresentationMode.GAME_3D) {
            // An explicit entry allows one new automatic widening attempt. It is recorded where
            // the switch commits, so a mode start that fails leaves the Game state untouched.
            gameSourceState.enterGame();
            gameWireMode = MoonBridge.SBS_MODE_GAME_MONO;
            confirmedGamePresentationGeneration = 0;
            activity.getIntent().removeExtra(EXTRA_GAME_WIDEN_FAILURE);
            activity.getIntent().removeExtra(EXTRA_GAME_WIDEN_QUALITY);
        }
        currentPresenterMode = nextMode;
        // Same-wire and deferred Client SBS switches can commit without a live-quality ACK.
        // Release the scoped window preference here too when the committed producer changes.
        applyPresentationFrameRatePreference();
        hostTelemetrySubscription.reconcile();
        surfaceEntity.setStereoMode(stereoModeFor(currentPresenterMode));
        applyContentColorMetadata();
        LimeLog.info("XR: stereo mode -> " + label);

        reshapeQuadForMode(currentPresenterMode, DEFAULT_PANEL_HEIGHT_METERS);
        updateModeSelection();
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

        // Every path that abandons a transition clears its mode generation, so no transaction
        // owns this completion.
        LimeLog.warning("XR: ignoring stale decoder completion generation "
                + transitionGeneration);
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
                    previousMode, pendingMode, modeLabel(pendingMode));
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
        dockController.revealDockTemporarily();
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
        dockController.revealDockTemporarily();
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
     * size from the screen's real-world distance. An applied Cinema View preset travels with its
     * restore target, so the replacement resumes Cinema instead of adopting its height as the
     * durable per-PC height.
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
        XrReconnectViewState handoff = new XrReconnectViewState(panelHeightMeters, realWorldPose);
        boolean cinemaViewExpanded = cinemaController.isExpanded();
        if (cinemaViewExpanded) {
            handoff = handoff.withCinemaRestore(cinemaController.restoreHeightMeters(),
                    cinemaController.restorePose());
        }
        handoff.writeTo(reconnectIntent);
        LimeLog.info("XR: captured reconnect view at height "
                + panelHeightMeters + " m"
                + (realWorldPose != null ? " with real-world pose" : "")
                + (cinemaViewExpanded ? " in Cinema View" : ""));
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
                transitionInProgress, depthStatusOverlay.phase() == 2, gameProviderV1Supported);
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
        dockController.revealDockTemporarily();
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
        updateGlancePanel();
    }

    /** Reconcile SceneCore metadata after EGL context creation or replacement. Main thread only. */
    public void onClientSbsOutputCapabilityChanged() {
        applyContentColorMetadata();
        if (modeOptionsPane.isClientOptionsOpen()) {
            modeOptionsPane.renderModeOptions();
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
            dockController.revealDockTemporarily();
            if (item.onTap != null) {
                item.onTap.run();
            }
        });
        dockController.attachDockActivityListeners(col);
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

    private android.graphics.drawable.GradientDrawable controlSurfaceBackground(
            int fillColor, int strokeColor, int strokeWidthDp) {
        return XrPanelViews.controlSurfaceBackground(activity, fillColor, strokeColor, strokeWidthDp);
    }

    /** Fill every hosted pixel; rounded child cards can keep their existing interaction styling. */
    private void setSolidPanelCanvas(View root) {
        XrPanelViews.setSolidPanelCanvas(activity, root);
    }

    /**
     * One tile on the {@link #buildControlBar control bar}. Carries its icon+label, the tap action,
     * and — for the mode group — which {@link PresentationMode} it selects (null for actions like
     * Disconnect).
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
        depthStatusOverlay.cancelPendingShowAndHide();
        clientPanelRefreshRatePreference.apply(activity.getWindow(), null, false);
        onHostActivityStopped();
        dockController.closeGazeTracker();
        // Native connection cleanup has already completed by StreamContainer teardown time. Only
        // clear local state here; the synchronous pre-stop hook owns the final transport write.
        controlTransportClosing = true;
        hostTelemetrySubscription.clearState();
        modeOptionsPane.stopClientOptionsStatusTicker();
        dockController.cancelCollapseTimer();
        cancelLiveQualityAckTimeout();
        liveQualityHandler.removeCallbacks(clientPackedSwapProofTimeoutRunnable);
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
        dockController.disposeBarPanel();
        modeOptionsPane.disposePanel();
        statsUi.disposePanel();
        disposePanel(auxiliaryPanel);
        auxiliaryPanel = null;
        glanceStrip.disposePanel();
        depthStatusOverlay.disposePanel();
        transientMessagePanel.disposePanel();
        glanceStrip.releaseViews();
        dockController.releaseViews();
        videoSurface = null;
        statsUi.releaseViews();
        settingsItem = null;
        cinemaItem = null;
        modeOptionsPane.releaseCinemaViews();
        statsItem = null;
        dumpItem = null;
        cinemaController.resetViewState();
        lastCinemaTileTapMs = 0L;
        lastCinemaActionTapMs = 0L;
        modeOptionsPane.releaseViews();
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
