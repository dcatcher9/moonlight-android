package com.limelight.grid;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.WifiLinkSpeed;

import java.util.Collections;
import java.util.Comparator;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {
    interface LinkSpeedReader {
        WifiLinkSpeed.Snapshot read();
    }

    public interface ActionListener {
        void onPrimaryAction(PcView.ComputerObject computer, View anchor);
        void onMoreActions(PcView.ComputerObject computer, View anchor);
    }

    private ActionListener actionListener;
    private final LinkSpeedReader linkSpeedReader;

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        this(context, prefs, () -> WifiLinkSpeed.read(context));
    }

    PcGridAdapter(Context context, PreferenceConfiguration prefs,
                  LinkSpeedReader linkSpeedReader) {
        super(context, getLayoutIdForPreferences(prefs));
        this.linkSpeedReader = linkSpeedReader;
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.pc_grid_item;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void setActionListener(ActionListener actionListener) {
        this.actionListener = actionListener;
    }

    public boolean isSingleMachinePresentation() {
        return getCount() == 1;
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, new Comparator<PcView.ComputerObject>() {
            @Override
            public int compare(PcView.ComputerObject lhs, PcView.ComputerObject rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        boolean useHeroLayout = isSingleMachinePresentation();
        int expectedRootId = useHeroLayout ? R.id.pc_card_hero : R.id.pc_card_standard;
        if (convertView == null || convertView.getId() != expectedRootId) {
            int layout = useHeroLayout ? R.layout.pc_grid_item_hero : R.layout.pc_grid_item;
            convertView = LayoutInflater.from(context).inflate(layout, parent, false);
        }

        populateView(convertView,
                convertView.findViewById(R.id.grid_image),
                convertView.findViewById(R.id.grid_mask),
                convertView.findViewById(R.id.grid_spinner),
                convertView.findViewById(R.id.grid_text),
                convertView.findViewById(R.id.grid_overlay),
                (PcView.ComputerObject) getItem(position));
        return convertView;
    }

    @Override
    public void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, PcView.ComputerObject obj) {
        TextView statusView = parentView.findViewById(R.id.grid_status);
        TextView hintView = parentView.findViewById(R.id.grid_hint);
        TextView addressView = parentView.findViewById(R.id.grid_address);
        View connectionSpeedView = parentView.findViewById(R.id.grid_connection_speed);
        TextView downloadSpeedView = parentView.findViewById(R.id.grid_download_speed);
        TextView uploadSpeedView = parentView.findViewById(R.id.grid_upload_speed);
        TextView displayFactView = parentView.findViewById(R.id.grid_display_fact);
        TextView sessionFactView = parentView.findViewById(R.id.grid_session_fact);
        Button primaryAction = parentView.findViewById(R.id.grid_primary_action);
        View moreActions = parentView.findViewById(R.id.grid_more_button);

        // Rows are recycled. Start from the no-button state and opt in only for actions that
        // cannot be reached by opening the library (Wake and Pair).
        primaryAction.setOnClickListener(null);
        primaryAction.setVisibility(View.GONE);
        primaryAction.setEnabled(false);
        primaryAction.setClickable(false);

        imgView.setImageResource(R.drawable.ic_computer);
        if (obj.details.state == ComputerDetails.State.ONLINE) {
            imgView.setAlpha(1.0f);
        }
        else {
            imgView.setAlpha(0.4f);
        }

        if (obj.details.state == ComputerDetails.State.UNKNOWN) {
            prgView.setVisibility(View.VISIBLE);
            statusView.setText(R.string.xr_home_refreshing);
            statusView.setTextColor(color(R.color.xr_text_secondary));
            setOptionalText(hintView, R.string.xr_home_refreshing_hint, true);
        }
        else {
            prgView.setVisibility(View.INVISIBLE);
        }

        txtView.setText(obj.details.name);
        if (obj.details.state == ComputerDetails.State.ONLINE) {
            txtView.setAlpha(1.0f);
        }
        else {
            txtView.setAlpha(0.4f);
        }

        if (obj.details.state == ComputerDetails.State.OFFLINE) {
            statusView.setText(R.string.pcview_menu_header_offline);
            statusView.setTextColor(color(R.color.xr_danger));
            setOptionalText(hintView, R.string.xr_home_wake_hint, true);
            primaryAction.setText(R.string.xr_home_wake);
            primaryAction.setVisibility(View.VISIBLE);
            primaryAction.setEnabled(true);
            primaryAction.setClickable(true);
            overlayView.setImageResource(R.drawable.ic_pc_offline);
            overlayView.setAlpha(0.4f);
            overlayView.setVisibility(View.VISIBLE);
        }
        // We must check if the status is exactly online and unpaired
        // to avoid colliding with the loading spinner when status is unknown
        else if (obj.details.state == ComputerDetails.State.ONLINE &&
                obj.details.pairState != PairingManager.PairState.PAIRED) {
            statusView.setText(R.string.scut_not_paired);
            statusView.setTextColor(color(R.color.xr_status_warn));
            setOptionalText(hintView, R.string.xr_home_pair_hint, true);
            primaryAction.setText(R.string.xr_home_pair);
            primaryAction.setVisibility(View.VISIBLE);
            primaryAction.setEnabled(true);
            primaryAction.setClickable(true);
            overlayView.setImageResource(R.drawable.ic_lock);
            overlayView.setAlpha(1.0f);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            if (obj.details.state == ComputerDetails.State.ONLINE) {
                if (obj.details.runningGameId != 0) {
                    statusView.setText(R.string.xr_home_status_running);
                    statusView.setTextColor(color(R.color.xr_accent));
                }
                else {
                    statusView.setText(R.string.pcview_menu_header_online);
                    statusView.setTextColor(color(R.color.xr_status_ok));
                }
                setOptionalText(hintView, R.string.xr_home_open_library_hint, true);
            }
            overlayView.setVisibility(View.GONE);
        }

        bindHeroFacts(obj.details, addressView, connectionSpeedView, downloadSpeedView,
                uploadSpeedView, displayFactView, sessionFactView);

        String cardDescription = txtView.getText() + ", " + statusView.getText();
        if (connectionSpeedView != null
                && connectionSpeedView.getVisibility() == View.VISIBLE) {
            cardDescription += ", " + connectionSpeedView.getContentDescription();
        }
        parentView.setContentDescription(cardDescription);
        moreActions.setContentDescription(context.getString(R.string.xr_home_more)
                + ": " + obj.details.name);
        parentView.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onPrimaryAction(obj, v);
            }
        });
        if (primaryAction.getVisibility() == View.VISIBLE) {
            primaryAction.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onPrimaryAction(obj, v);
                }
            });
        }
        moreActions.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onMoreActions(obj, v);
            }
        });
    }

    private void bindHeroFacts(ComputerDetails details, TextView addressView,
                               View connectionSpeedView, TextView downloadSpeedView,
                               TextView uploadSpeedView, TextView displayFactView,
                               TextView sessionFactView) {
        if (addressView == null || connectionSpeedView == null || downloadSpeedView == null
                || uploadSpeedView == null || displayFactView == null
                || sessionFactView == null) {
            return;
        }

        String address = details.activeAddress != null ? details.activeAddress.address : null;
        if (address == null || address.trim().isEmpty()) {
            addressView.setVisibility(View.GONE);
        }
        else {
            addressView.setText(context.getString(R.string.xr_home_lan_address, address));
            addressView.setVisibility(View.VISIBLE);
        }

        boolean hostOnline = details.state == ComputerDetails.State.ONLINE;
        WifiLinkSpeed.Snapshot linkSpeed = hostOnline
                ? linkSpeedReader.read()
                : new WifiLinkSpeed.Snapshot(WifiLinkSpeed.UNKNOWN_MBPS,
                        WifiLinkSpeed.UNKNOWN_MBPS);
        if (linkSpeed.hasAnySpeed()) {
            String download = formatLinkSpeedOrUnavailable(linkSpeed.downloadMbps);
            String upload = formatLinkSpeedOrUnavailable(linkSpeed.uploadMbps);
            downloadSpeedView.setText(context.getString(
                    R.string.xr_home_download_link_speed, download));
            uploadSpeedView.setText(context.getString(
                    R.string.xr_home_upload_link_speed, upload));
            connectionSpeedView.setContentDescription(context.getString(
                    R.string.xr_home_wifi_link_speed_description, download, upload));
            connectionSpeedView.setVisibility(View.VISIBLE);
        }
        else {
            downloadSpeedView.setText(null);
            uploadSpeedView.setText(null);
            connectionSpeedView.setContentDescription(null);
            connectionSpeedView.setVisibility(View.GONE);
        }

        boolean pairedOnline = details.state == ComputerDetails.State.ONLINE
                && details.pairState == PairingManager.PairState.PAIRED;
        if (!pairedOnline) {
            displayFactView.setVisibility(View.GONE);
            sessionFactView.setVisibility(View.GONE);
            return;
        }

        displayFactView.setText(details.vDisplaySupported && details.vDisplayDriverReady
                ? R.string.xr_home_virtual_display_ready
                : R.string.xr_home_virtual_display_unavailable);
        displayFactView.setTextColor(details.vDisplaySupported && details.vDisplayDriverReady
                ? color(R.color.xr_status_ok) : color(R.color.xr_danger));
        displayFactView.setVisibility(View.VISIBLE);

        boolean sessionActive = details.runningGameId != 0
                || (details.hostSessionId != null && !details.hostSessionId.trim().isEmpty());
        sessionFactView.setText(sessionActive
                ? R.string.xr_home_session_active : R.string.xr_home_session_ready);
        sessionFactView.setTextColor(sessionActive
                ? color(R.color.xr_accent_bright) : color(R.color.xr_text_primary));
        sessionFactView.setVisibility(View.VISIBLE);
    }

    private String formatLinkSpeed(int speedMbps) {
        if (speedMbps >= 1000) {
            if (speedMbps % 1000 == 0) {
                return context.getString(R.string.xr_home_speed_gbps_whole,
                        speedMbps / 1000);
            }
            return context.getString(R.string.xr_home_speed_gbps, speedMbps / 1000.0f);
        }
        return context.getString(R.string.xr_home_speed_mbps, speedMbps);
    }

    private int color(int resourceId) {
        return ContextCompat.getColor(context, resourceId);
    }

    private String formatLinkSpeedOrUnavailable(int speedMbps) {
        return speedMbps == WifiLinkSpeed.UNKNOWN_MBPS
                ? context.getString(R.string.xr_home_speed_unavailable)
                : formatLinkSpeed(speedMbps);
    }

    private static void setOptionalText(TextView view, int textResource, boolean visible) {
        if (view == null) {
            return;
        }
        view.setText(textResource);
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
