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
import androidx.xr.runtime.math.Pose;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.preferences.XrChoiceGroup;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.XrBitrateControl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The App Settings pane left of the video: its SceneCore panel and host view, the two-column view
 * of settings shared by every presentation mode, the per-key control references an open pane is
 * refreshed through, and the footer defaults button. {@link XrStreamPresenter} owns the model
 * snapshot, decides when the pane is shown, refreshed or released, and supplies its pose.
 * Every method runs on the main thread.
 */
final class XrSessionSettingsPane {
    /** What the pane reads from its owner. */
    interface Host {
        SessionSettingsModel sessionSettingsModel();

        XrStreamPresenter.ControlActionListener controlActionListener();

        boolean settingsControlsEnabled();

        /**
         * Live-apply classification of the selected mode's staged quality tuple, which owns the
         * bitrate row; null before the settings snapshot arrives.
         */
        ModeStreamQualityModel selectedModeQualityModel();
    }

    private final Activity activity;
    private final Host host;

    XrSessionSettingsPane(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    static final int SESSION_SUMMARY_TEXT_DIMEN = R.dimen.xr_text_title;
    static final int SESSION_GROUP_TEXT_DIMEN = R.dimen.xr_text_title;
    static final int SESSION_ROW_TITLE_TEXT_DIMEN = R.dimen.xr_text_display;
    static final int SESSION_META_TEXT_DIMEN = R.dimen.xr_text_title;

    private final EnumMap<SessionSettingsModel.Key, XrChoiceGroup> sessionChoiceGroups =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, XrBitrateControl> sessionBitrateControls =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, TextView> sessionSourceViews =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, TextView> sessionPendingViews =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private Button sessionDefaultsButton;
    /** Left-side panel for the settings shared by every presentation mode in this session. */
    private PanelEntity panel;
    private FrameLayout contentHost;

    /** Creates the hidden panel and its host view, parented to the video entity. */
    void createPanel(Session session, SurfaceEntity videoEntity, Pose pose) {
        contentHost = new FrameLayout(activity);
        XrPanelViews.setSolidPanelCanvas(activity, contentHost);
        panel = PanelEntity.create(
                session, contentHost,
                new FloatSize2d(XrPanelPoseSolver.AUXILIARY_WIDTH_METERS,
                        XrPanelPoseSolver.AUXILIARY_HEIGHT_METERS),
                "xr-session-controls", pose, videoEntity);
        XrPanelViews.configureSolidPanel(panel);
        panel.setEnabled(false);
    }

    boolean hasPanel() {
        return panel != null && !panel.isDisposed();
    }

    boolean hasContentHost() {
        return contentHost != null;
    }

    /**
     * Rebuilds and shows the pane at {@code pose}, or hides it and drops its control references.
     *
     * @param pose ignored when hiding or before the panel exists
     */
    void applyVisibility(boolean visible, Pose pose) {
        if (visible && contentHost != null) {
            contentHost.removeAllViews();
            contentHost.addView(buildSessionSettingsView(), new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else if (!visible) {
            clearSessionSettingsReferences();
        }
        if (hasPanel()) {
            if (visible && pose != null) {
                XrPanelPoseSolver.updatePanelPose(panel, pose);
            }
            panel.setEnabled(visible);
        }
    }

    void reposition(Pose pose) {
        XrPanelPoseSolver.updatePanelPose(panel, pose);
    }

    void disposePanel() {
        if (panel != null && !panel.isDisposed()) {
            panel.dispose();
        }
        panel = null;
        contentHost = null;
    }

    View buildSessionSettingsView() {
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
            SessionSettingsModel.Value value = host.sessionSettingsModel().get(key);
            if (value != null) {
                (sharedSettingColumn(key) == 0 ? videoColumn : deliveryColumn)
                        .addView(buildSessionSettingRow(key, localizeSessionSettingValue(key, value)));
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

        // Defaults follow the same automatic setting path as individual choices.
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setPadding(0, dimen(R.dimen.xr_space_md), 0, 0);

        sessionDefaultsButton = compactButton(
                activity.getString(R.string.xr_session_use_global));
        sessionDefaultsButton.setEnabled(host.settingsControlsEnabled());
        sessionDefaultsButton.setOnClickListener(v -> host.controlActionListener()
                .onUseGlobalDefaultsRequested(host.sessionSettingsModel()));
        footer.addView(sessionDefaultsButton);

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

    /** Stable semantic grouping keeps shared controls scannable in two columns. */
    static int sharedSettingColumn(SessionSettingsModel.Key key) {
        switch (key) {
            case HDR:
            case STREAM_GAMMA:
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
        LinearLayout row = card(dimen(R.dimen.xr_space_lg));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = controlText(sessionSettingLabel(key),
                SESSION_ROW_TITLE_TEXT_DIMEN, paletteColor(R.color.xr_text_primary));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        applyTitleIcon(title, sessionSettingIconRes(key));
        heading.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        String source = settingSourceLabel(value.source);
        TextView sourceView = controlText(source, SESSION_META_TEXT_DIMEN,
                paletteColor(R.color.xr_text_secondary));
        sessionSourceViews.put(key, sourceView);
        heading.addView(sourceView);
        row.addView(heading);

        XrChoiceGroup choices = buildChoiceGroup(value.choices, value.selectedChoiceId,
                value.pendingValue, choiceId -> host.controlActionListener().onSharedSettingSelected(
                        key, choiceId, host.sessionSettingsModel()));
        choices.setReselectEnabled(key == SessionSettingsModel.Key.STREAM_GAMMA
                && value.descriptionRes == R.string.xr_stream_gamma_failed);
        choices.setEnabled(host.settingsControlsEnabled() && value.enabled);
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
        updateSessionPendingView(pending, key, value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dimen(R.dimen.xr_space_md);
        row.setLayoutParams(lp);
        return row;
    }

    private SessionSettingsModel.Value localizeSessionSettingValue(SessionSettingsModel.Key key,
                                                                    SessionSettingsModel.Value value) {
        if (key != SessionSettingsModel.Key.STREAM_GAMMA) return value;
        List<SessionSettingsModel.Choice> choices = new ArrayList<>();
        for (SessionSettingsModel.Choice choice : value.choices) {
            choices.add(new SessionSettingsModel.Choice(choice.id, activity.getString(
                    com.limelight.nvstream.StreamGamma.fromPreference(choice.id).labelRes)));
        }
        return new SessionSettingsModel.Value(activity.getString(
                    com.limelight.nvstream.StreamGamma.fromPreference(value.appliedValue).labelRes),
                activity.getString(com.limelight.nvstream.StreamGamma.fromPreference(value.pendingValue).labelRes),
                value.source, value.reconnectRequired, choices, value.selectedChoiceId,
                value.enabled, value.descriptionRes);
    }

    private View buildSessionBitrateSettingRow(SessionSettingsModel.Key key,
                                               SessionSettingsModel.Value value) {
        LinearLayout row = card(dimen(R.dimen.xr_space_lg));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = controlText(sessionSettingLabel(key),
                SESSION_ROW_TITLE_TEXT_DIMEN, paletteColor(R.color.xr_text_primary));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        applyTitleIcon(title, sessionSettingIconRes(key));
        heading.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        String source = settingSourceLabel(value.source);
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
                        host.controlActionListener().onSharedSettingSelected(
                                key, choiceId, host.sessionSettingsModel()));
        bitrateControl.setEnabled(host.settingsControlsEnabled());
        sessionBitrateControls.put(key, bitrateControl);
        row.addView(bitrateControl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView pending = controlText("", SESSION_META_TEXT_DIMEN,
                paletteColor(R.color.xr_text_secondary));
        pending.setPadding(0, compactSpacing, 0, 0);
        sessionPendingViews.put(key, pending);
        row.addView(pending);
        updateSessionPendingView(pending, key, value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dimen(R.dimen.xr_space_md);
        row.setLayoutParams(lp);
        return row;
    }

    void clearSessionSettingsReferences() {
        sessionChoiceGroups.clear();
        sessionSourceViews.clear();
        sessionPendingViews.clear();
        sessionBitrateControls.clear();
        sessionDefaultsButton = null;
    }

    void updateSessionSettingsView() {
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            if (key.isModeStreamQuality() && key != SessionSettingsModel.Key.BITRATE) {
                continue;
            }
            SessionSettingsModel.Value value = host.sessionSettingsModel().get(key);
            if (value == null) {
                continue;
            }
            value = localizeSessionSettingValue(key, value);
            if (key == SessionSettingsModel.Key.BITRATE) {
                XrBitrateControl bitrateControl = sessionBitrateControls.get(key);
                if (bitrateControl != null) {
                    bitrateControl.setChoices(choicesOrCurrent(value, value.selectedChoiceId),
                            value.selectedChoiceId, value.pendingValue, choiceId ->
                                    host.controlActionListener().onSharedSettingSelected(
                                            key, choiceId, host.sessionSettingsModel()));
                    bitrateControl.setEnabled(host.settingsControlsEnabled());
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
                                    host.controlActionListener().onSharedSettingSelected(
                                            key, choiceId, host.sessionSettingsModel()));
                }
                group.setEnabled(host.settingsControlsEnabled() && value.enabled);
                group.setReselectEnabled(key == SessionSettingsModel.Key.STREAM_GAMMA
                        && value.descriptionRes == R.string.xr_stream_gamma_failed);
            }
            TextView sourceView = sessionSourceViews.get(key);
            if (sourceView != null) {
                sourceView.setText(settingSourceLabel(value.source));
            }
            updateSessionPendingView(sessionPendingViews.get(key), key, value);
        }
        if (sessionDefaultsButton != null) {
            sessionDefaultsButton.setEnabled(host.settingsControlsEnabled());
        }
    }

    private void updateSessionPendingView(TextView pending, SessionSettingsModel.Key key,
                                          SessionSettingsModel.Value value) {
        if (pending == null) {
            return;
        }
        if (value.descriptionRes != 0) {
            pending.setVisibility(View.VISIBLE);
            pending.setText(activity.getString(value.descriptionRes, value.appliedValue));
            return;
        }
        pending.setVisibility(value.hasPendingChange() ? View.VISIBLE : View.GONE);
        if (value.hasPendingChange()) {
            pending.setText(activity.getString(pendingChangeRequiresReconnect(key, value)
                            ? R.string.xr_setting_pending_active
                            : R.string.xr_setting_pending_live,
                    value.appliedValue));
        }
    }

    /**
     * Shared settings carry their own classification. Bitrate is the selected mode's quality, so
     * that mode's tuple classification decides whether the staged change applies live.
     */
    private boolean pendingChangeRequiresReconnect(SessionSettingsModel.Key key,
                                                   SessionSettingsModel.Value value) {
        if (key == SessionSettingsModel.Key.BITRATE) {
            ModeStreamQualityModel selected = host.selectedModeQualityModel();
            if (selected != null) {
                return selected.requiresReconnect();
            }
        }
        return value.reconnectRequired;
    }

    private LinearLayout panelColumn() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(paletteColor(R.color.xr_surface_sunken));
        int padding = dimen(R.dimen.xr_space_xl);
        root.setPadding(padding, padding, padding, padding);
        return root;
    }

    /** Icon shown beside a settings row title, or 0 where the row has no icon of its own. */
    private int sessionSettingIconRes(SessionSettingsModel.Key key) {
        switch (key) {
            case BITRATE:
                return R.drawable.ic_xr_bitrate;
            case CODEC:
                return R.drawable.ic_xr_codec;
            case HDR:
            case STREAM_GAMMA:
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
            case BITRATE:
                return activity.getString(R.string.title_bitrate_ceiling);
            case HDR:
                return activity.getString(R.string.title_enable_hdr);
            case STREAM_GAMMA:
                return activity.getString(R.string.title_stream_gamma);
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

    /** The pane's share of the presenter's control-availability pass, in its original order. */
    void setChoiceGroupsAndDefaultsEnabled(boolean enabled) {
        for (Map.Entry<SessionSettingsModel.Key, XrChoiceGroup> entry : sessionChoiceGroups.entrySet()) {
            SessionSettingsModel.Value value = host.sessionSettingsModel().get(entry.getKey());
            entry.getValue().setEnabled(enabled && value != null && value.enabled);
        }
        if (sessionDefaultsButton != null) {
            sessionDefaultsButton.setEnabled(enabled);
        }
    }

    void setBitrateControlsEnabled(boolean enabled) {
        for (XrBitrateControl control : sessionBitrateControls.values()) {
            control.setEnabled(enabled);
        }
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

    private Button compactButton(CharSequence text) {
        return XrPanelViews.compactButton(activity, text);
    }

    private LinearLayout card(int paddingPixels) {
        return XrPanelViews.card(activity, paddingPixels);
    }

    private void applyTitleIcon(TextView title, int iconRes) {
        XrPanelViews.applyTitleIcon(activity, title, iconRes);
    }

    private String settingSourceLabel(SessionSettingsModel.Source source) {
        return XrPanelViews.settingSourceLabel(activity, source);
    }

    private List<SessionSettingsModel.Choice> choicesOrCurrent(
            SessionSettingsModel.Value value, String choiceId) {
        return XrPanelViews.choicesOrCurrent(value, choiceId);
    }

    private XrChoiceGroup buildChoiceGroup(List<SessionSettingsModel.Choice> choices,
                                           String selectedChoiceId,
                                           CharSequence fallbackLabel,
                                           XrChoiceGroup.OnChoiceSelectedListener listener) {
        return XrPanelViews.buildChoiceGroup(activity, choices, selectedChoiceId, fallbackLabel,
                listener);
    }

    private void configureChoiceGroup(XrChoiceGroup group,
                                      List<SessionSettingsModel.Choice> choices,
                                      String selectedChoiceId,
                                      CharSequence fallbackLabel,
                                      XrChoiceGroup.OnChoiceSelectedListener listener) {
        XrPanelViews.configureChoiceGroup(group, choices, selectedChoiceId, fallbackLabel,
                listener);
    }
}
