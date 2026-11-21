package com.limelight.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.XrChoiceGroup;
import com.limelight.ui.xrcontrols.ClientSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.XrBitrateControl;
import com.limelight.ui.xrcontrols.XrBitrateRecommendation;
import com.limelight.ui.xrcontrols.XrControlPanelLayout;
import com.limelight.ui.xrcontrols.XrControlUiState;
import com.limelight.ui.xrcontrols.XrResolutionSelector;
import com.limelight.ui.xrcontrols.XrSegmentedLadder;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * The reusable contextual panel beneath the dock: its SceneCore panel and host view, the
 * per-mode quality controls and source cards, the Cinema options it also renders, the
 * incremental refresh, the bounded content-height fit and the Client status ticker.
 * {@link XrStreamPresenter} owns the mode state machine and every decision the controls lead
 * to; this class reads that state live through {@link Host} each time it renders or refreshes.
 */
final class XrModeOptionsPane {
    /** Live presenter state and the actions the pane's controls trigger. */
    interface Host {
        Session session();

        SurfaceEntity videoEntity();

        boolean hostActivityStarted();

        boolean presenterDestroyed();

        PresentationMode currentPresenterMode();

        XrStreamPresenter.ControlActionListener controlActionListener();

        boolean settingsControlsEnabled();

        boolean sessionControlsEnabled();

        boolean streamPresentationReady();

        boolean modeSwitchInProgress();

        boolean liveQualityTransactionBusy();

        ClientSbsModeSettingsModel clientSbsModeSettingsModel();

        String modeLabel(PresentationMode mode);

        String hostDepthStatusText();

        String clientSbsRuntimeStatus(ClientSbsModeSettingsModel model);

        int gameSourceStatusText();

        int gameSourceDetailText();

        String moviePictureFormatId();

        boolean onMoviePictureFormatSelected(String choiceId);

        boolean cinemaExpanded();

        boolean onCinemaEnvironmentSelected(String value);

        void onCinemaActionTapped();

        float panelHeightMeters();

        Pose modeOptionsPose(float videoHeightMeters);

        XrControlPanelLayout controlBarLayout(float videoHeightMeters);
    }

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final XrControlUiState controlUiState;
    private final EnumMap<PresentationMode, ModeStreamQualityModel> modeStreamQualityModels;
    private final Host host;

    XrModeOptionsPane(Activity activity, PreferenceConfiguration prefConfig,
                      XrControlUiState controlUiState,
                      EnumMap<PresentationMode, ModeStreamQualityModel> modeStreamQualityModels,
                      Host host) {
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.controlUiState = controlUiState;
        this.modeStreamQualityModels = modeStreamQualityModels;
        this.host = host;
    }

    private static final float MODE_OPTIONS_MAX_HEIGHT_METERS = 0.90f;

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
    private final android.os.Handler modeOptionsStatusHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshClientOptionsStatus = new Runnable() {
        @Override
        public void run() {
            if (!host.hostActivityStarted() || !isClientOptionsOpen()) {
                return;
            }
            updateClientSbsRuntimeStatusView();
            modeOptionsStatusHandler.postDelayed(this, 1000L);
        }
    };
    private XrChoiceGroup cinemaEnvironmentChoiceGroup;
    private Button cinemaActionButton;
    private PresentationMode renderedModeOptionsMode;
    private XrResolutionSelector modeResolutionSelector;
    private XrSegmentedLadder modeFpsLadder;
    private XrBitrateControl modeBitrateControl;
    private TextView modeQualityCueView;
    private Button modeDefaultsButton;
    private TextView clientModelNameView;
    private TextView clientAspectBucketView;
    private TextView clientRuntimeStatusView;
    /** Client header status; refreshed with the GPU status card. */
    private TextView clientHeaderStatusView;
    private XrChoiceGroup moviePictureFormatChoiceGroup;
    private TextView gameSourceStatusView;
    private TextView gameSourceDetailView;

    float heightMeters() {
        return modeOptionsHeightMeters;
    }

    /** Creates the disabled contextual panel at its minimum height. */
    void createPanel(XrControlPanelLayout layout, float videoHeightMeters) {
        modeOptionsHost = new FrameLayout(activity);
        setSolidPanelCanvas(modeOptionsHost);
        modeOptionsHeightMeters = XrPanelPlacement.MODE_OPTIONS_MIN_HEIGHT_METERS;
        modeOptionsBaseRasterHeightPixels = 0;
        modeOptionsRasterHeightPixels = 0;
        modeOptionsPanel = PanelEntity.create(
                host.session(), modeOptionsHost,
                new FloatSize2d(layout.widthMeters, modeOptionsHeightMeters),
                "xr-mode-options", host.modeOptionsPose(videoHeightMeters), host.videoEntity());
        XrPanelViews.configureSolidPanel(modeOptionsPanel);
        modeOptionsPanel.setEnabled(false);
    }

    /** Renders, places and enables the panel for the contextual surface now visible. */
    void applyVisibility(boolean showModeOptions) {
        modeOptionsStatusHandler.removeCallbacks(refreshClientOptionsStatus);
        if (modeOptionsHost != null) {
            if (showModeOptions) {
                renderModeOptions();
            }
        }
        if (modeOptionsPanel != null && !modeOptionsPanel.isDisposed()) {
            if (showModeOptions) {
                modeOptionsPanel.setPose(host.modeOptionsPose(host.panelHeightMeters()));
            }
            modeOptionsPanel.setEnabled(showModeOptions);
        }
        if (host.hostActivityStarted() && isClientOptionsOpen()) {
            modeOptionsStatusHandler.postDelayed(refreshClientOptionsStatus, 1000L);
        }
    }

    /** The pane's share of the presenter's control-availability pass, in its original order. */
    void setModeControlsEnabled(boolean enabled) {
        if (moviePictureFormatChoiceGroup != null) {
            moviePictureFormatChoiceGroup.setEnabled(moviePictureFormatEnabled(enabled));
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
    }

    void setModeDefaultsEnabled(boolean enabled) {
        if (modeDefaultsButton != null) {
            modeDefaultsButton.setEnabled(enabled);
        }
    }

    void stopClientOptionsStatusTicker() {
        modeOptionsStatusHandler.removeCallbacks(refreshClientOptionsStatus);
    }

    void reposition(float videoHeightMeters) {
        if (modeOptionsPanel != null && !modeOptionsPanel.isDisposed()) {
            modeOptionsPanel.setPose(host.modeOptionsPose(videoHeightMeters));
        }
    }

    void renderModeOptions() {
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
        addContextualOptionsHeading(header, host.modeLabel(mode), mode == host.currentPresenterMode());
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
                        host.hostDepthStatusText());
                break;
            case CLIENT_SBS_AI:
                clientHeaderStatusView = addModeStatus(header,
                        activity.getString(R.string.xr_mode_client_ai_description),
                        host.clientSbsRuntimeStatus(host.clientSbsModeSettingsModel()));
                break;
            default:
                break;
        }
        root.addView(header);

        if (mode == PresentationMode.MOVIE_3D) {
            addMovieModeOptions(root);
        } else if (mode == PresentationMode.GAME_3D) {
            LinearLayout sourceCard = createModeSourceCard(root,
                    activity.getString(host.gameSourceStatusText()));
            gameSourceStatusView = (TextView) sourceCard.getChildAt(0);
            gameSourceDetailView = controlText(activity.getString(host.gameSourceDetailText()),
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
                host.cinemaExpanded());
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
                        && host.onCinemaEnvironmentSelected(value));
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
            if (host.cinemaExpanded()) {
                host.onCinemaActionTapped();
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

    void updateCinemaOptionsView() {
        if (cinemaEnvironmentChoiceGroup != null) {
            cinemaEnvironmentChoiceGroup.setEnabled(host.settingsControlsEnabled());
            cinemaEnvironmentChoiceGroup.setSelectedValue(
                    prefConfig.cinemaEnvironment.preferenceValue);
        }
        if (cinemaActionButton != null) {
            cinemaActionButton.setEnabled(host.cinemaExpanded()
                    && host.sessionControlsEnabled() && host.videoEntity() != null
                    && !host.videoEntity().isDisposed());
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
        modeResolutionSelector.setEnabled(host.settingsControlsEnabled());
        modeResolutionSelector.setOnResolutionSelectedListener(choiceId ->
                host.controlActionListener().onModeQualitySettingSelected(mode,
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
        modeFpsLadder.setEnabled(host.settingsControlsEnabled());
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
                        host.controlActionListener().onModeQualitySettingSelected(mode,
                                SessionSettingsModel.Key.BITRATE, choiceId,
                                modeStreamQualityModels.get(mode)));
        modeBitrateControl.setEnabled(host.settingsControlsEnabled());
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
        modeDefaultsButton.setEnabled(host.settingsControlsEnabled());
        modeDefaultsButton.setOnClickListener(v -> host.controlActionListener()
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

    /** Adds a header status column and returns its value view. */
    private TextView addModeStatus(LinearLayout row, String label, String value) {
        LinearLayout status = labeledValue(label, value, statusColor(value));
        row.addView(status, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2.0f));
        return (TextView) status.getChildAt(1);
    }

    private void addClientSbsModeOptions(LinearLayout row) {
        ClientSbsModeSettingsModel model = host.clientSbsModeSettingsModel();
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
        String runtimeStatus = host.clientSbsRuntimeStatus(model);
        LinearLayout runtime = labeledValue(
                activity.getString(R.string.xr_client_gpu_status), runtimeStatus,
                statusColor(runtimeStatus));
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
                host.moviePictureFormatId(), "", host::onMoviePictureFormatSelected);
        moviePictureFormatChoiceGroup.setEnabled(
                moviePictureFormatEnabled(host.settingsControlsEnabled()));
        card.addView(moviePictureFormatChoiceGroup);
    }

    /** Movie interpretation changes need a ready stream and no transaction in flight. */
    private boolean moviePictureFormatEnabled(boolean controlsEnabled) {
        return controlsEnabled && host.streamPresentationReady()
                && !host.modeSwitchInProgress() && !host.liveQualityTransactionBusy();
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
        clientHeaderStatusView = null;
        modeOptionsContentRoot = null;
        modeOptionsFitScheduled = false;
        modeOptionsStatusHandler.removeCallbacks(modeOptionsFitRunnable);
    }

    void updateModeOptionsView() {
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
        modeResolutionSelector.setEnabled(host.settingsControlsEnabled());
        SessionSettingsModel.Value fps = model.get(SessionSettingsModel.Key.FRAME_RATE);
        String fpsId = qualityChoiceId(fps, model.pendingQuality.frameRate);
        if (!modeFpsLadder.setSelectedChoiceId(fpsId)) {
            configureFpsLadder(mode, fps, model);
        }
        modeFpsLadder.setEnabled(host.settingsControlsEnabled());
        SessionSettingsModel.Value bitrate = model.get(SessionSettingsModel.Key.BITRATE);
        String bitrateId = qualityChoiceId(bitrate,
                String.valueOf(model.pendingQuality.bitrateKbps));
        // Keep bitrate independent from resolution/fps. Rebuild the choice model so the slider
        // remains in sync with the latest pending value and any out-of-preset entry.
        int recommendedKbps = recommendedBitrateKbps(mode, model);
        modeBitrateControl.setChoices(choicesOrCurrent(bitrate, bitrateId), bitrateId,
                recommendedKbps, bitrateHintFor(recommendedKbps), choiceId ->
                        host.controlActionListener().onModeQualitySettingSelected(mode,
                                SessionSettingsModel.Key.BITRATE, choiceId,
                                modeStreamQualityModels.get(mode)));
        modeBitrateControl.setEnabled(host.settingsControlsEnabled());
        modeQualityCueView.setText(modeQualityCue(model));
        modeQualityCueView.setTextColor(model.requiresReconnect()
                ? paletteColor(R.color.xr_status_warn) : paletteColor(R.color.xr_text_secondary));
        modeDefaultsButton.setEnabled(host.settingsControlsEnabled());
        if (moviePictureFormatChoiceGroup != null) {
            moviePictureFormatChoiceGroup.setSelectedValue(host.moviePictureFormatId());
            moviePictureFormatChoiceGroup.setEnabled(
                    moviePictureFormatEnabled(host.settingsControlsEnabled()));
        }
        if (mode == PresentationMode.CLIENT_SBS_AI) {
            updateClientSbsOptionsView();
        }
        scheduleModeOptionsPanelFit();
    }

    private void updateClientSbsOptionsView() {
        ClientSbsModeSettingsModel model = host.clientSbsModeSettingsModel();
        if (clientModelNameView != null) {
            clientModelNameView.setText(R.string.xr_depth_model_zipdepth_base);
        }
        if (clientAspectBucketView != null) {
            clientAspectBucketView.setText(model.bucket);
        }
        updateClientSbsRuntimeStatusView();
    }

    /** Refreshes both the header status and the GPU status card from the live backend. */
    private void updateClientSbsRuntimeStatusView() {
        if (clientRuntimeStatusView == null && clientHeaderStatusView == null) {
            return;
        }
        String status = host.clientSbsRuntimeStatus(host.clientSbsModeSettingsModel());
        int color = statusColor(status);
        for (TextView view : new TextView[] {clientHeaderStatusView, clientRuntimeStatusView}) {
            if (view != null) {
                view.setText(status);
                view.setTextColor(color);
            }
        }
    }

    /** Status text that names an unavailable backend or source uses the danger colour. */
    private int statusColor(String status) {
        return status.toLowerCase(Locale.US).contains("unavailable")
                ? paletteColor(R.color.xr_danger)
                : paletteColor(R.color.xr_text_primary);
    }

    boolean isClientOptionsOpen() {
        return controlUiState.getVisibleSurface() == XrControlUiState.Surface.MODE_OPTIONS
                && PresentationMode.CLIENT_SBS_AI.name().equals(
                controlUiState.getModeOptionsId());
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
                choiceId -> host.controlActionListener().onModeQualitySettingSelected(mode,
                        SessionSettingsModel.Key.FRAME_RATE, choiceId,
                        modeStreamQualityModels.get(mode)));
    }

    private CharSequence bitrateHintFor(int recommendedKbps) {
        return recommendedKbps > 0
                ? activity.getString(R.string.xr_bitrate_recommended)
                : activity.getString(R.string.xr_bitrate_codec_too_slow);
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
        XrControlPanelLayout layout = host.controlBarLayout(host.panelHeightMeters());
        modeOptionsRasterHeightPixels = targetHeightPixels;
        modeOptionsHeightMeters = targetHeightMeters;
        // Preserve the existing authored fit sequence. SceneCore beta02's final setSize() also
        // relayouts the hosted raster at its default pixel density; integer rounding can differ
        // slightly from the explicit targetHeightPixels above.
        modeOptionsPanel.setSizeInPixels(new IntSize2d(hostWidth, targetHeightPixels));
        modeOptionsPanel.setSize(new FloatSize2d(layout.widthMeters, targetHeightMeters));
        modeOptionsPanel.setPose(host.modeOptionsPose(host.panelHeightMeters()));
        modeOptionsHost.requestLayout();
        LimeLog.info(String.format(Locale.US,
                "XR mode options fit: raster=%dx%d contentHeight=%d physicalHeight=%.3fm",
                hostWidth, targetHeightPixels, contentHeightPx, targetHeightMeters));
    }

    void updateGameSourceViews() {
        if (gameSourceStatusView != null) {
            gameSourceStatusView.setText(host.gameSourceStatusText());
        }
        if (gameSourceDetailView != null) {
            gameSourceDetailView.setText(host.gameSourceDetailText());
            scheduleModeOptionsPanelFit();
        }
    }

    void reconcileClientOptionsStatusTicker() {
        modeOptionsStatusHandler.removeCallbacks(refreshClientOptionsStatus);
        if (host.hostActivityStarted() && !host.presenterDestroyed() && isClientOptionsOpen()) {
            modeOptionsStatusHandler.postDelayed(refreshClientOptionsStatus, 1000L);
        }
    }

    void disposePanel() {
        if (modeOptionsPanel != null && !modeOptionsPanel.isDisposed()) {
            modeOptionsPanel.dispose();
        }
        modeOptionsPanel = null;
    }

    void releaseCinemaViews() {
        cinemaEnvironmentChoiceGroup = null;
        cinemaActionButton = null;
    }

    void releaseViews() {
        modeOptionsHost = null;
        modeOptionsContentRoot = null;
        modeOptionsBaseRasterHeightPixels = 0;
        modeOptionsRasterHeightPixels = 0;
        modeOptionsStatusHandler.removeCallbacks(modeOptionsFitRunnable);
        modeOptionsFitScheduled = false;
    }

    private int paletteColor(int colorRes) {
        return XrPanelViews.paletteColor(activity, colorRes);
    }

    private int dimen(int resourceId) {
        return XrPanelViews.dimen(activity, resourceId);
    }

    private TextView controlText(CharSequence text, int textSizeResource, int color) {
        return XrPanelViews.controlText(activity, text, textSizeResource, color);
    }

    private LinearLayout labeledValue(String label, String value, int valueColor) {
        return XrPanelViews.labeledValue(activity, label, value, valueColor);
    }

    private Button compactButton(CharSequence text) {
        return XrPanelViews.compactButton(activity, text);
    }

    private void styleControlButton(Button button) {
        XrPanelViews.styleControlButton(activity, button);
    }

    private void applyTitleIcon(TextView title, int iconRes) {
        XrPanelViews.applyTitleIcon(activity, title, iconRes);
    }

    private android.graphics.drawable.GradientDrawable controlSurfaceBackground(
            int fillColor, int strokeColor, int strokeWidthDp) {
        return XrPanelViews.controlSurfaceBackground(activity, fillColor, strokeColor, strokeWidthDp);
    }

    private LinearLayout card(int paddingPixels) {
        return XrPanelViews.card(activity, paddingPixels);
    }

    private void setSolidPanelCanvas(View root) {
        XrPanelViews.setSolidPanelCanvas(activity, root);
    }

    private XrChoiceGroup buildChoiceGroup(List<SessionSettingsModel.Choice> choices,
                                           String selectedChoiceId,
                                           CharSequence fallbackLabel,
                                           XrChoiceGroup.OnChoiceSelectedListener listener) {
        return XrPanelViews.buildChoiceGroup(activity, choices, selectedChoiceId, fallbackLabel,
                listener);
    }
}
