package com.limelight.grid;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@SuppressWarnings("unchecked")
public class AppGridAdapter extends GenericGridAdapter<AppView.AppObject> {
    private static final int ART_WIDTH_PX = 300;
    private static final int XR_ART_WIDTH_DP = 240;

    public interface ActionListener {
        void onPrimaryAction(AppView.AppObject app, View anchor);
        void onQuitSession(AppView.AppObject app);
        void onMoreActions(AppView.AppObject app, View anchor, View card);
    }

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private CachedAppAssetLoader loader;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private ArrayList<AppView.AppObject> allApps = new ArrayList<>();
    private ActionListener actionListener;
    private String searchQuery = "";
    private boolean hostOnline;

    public AppGridAdapter(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        super(context, getLayoutIdForPreferences(prefs));

        this.computer = computer;
        hostOnline = computer.state == ComputerDetails.State.ONLINE
                && computer.activeAddress != null;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;

        updateLayoutWithPreferences(context, prefs);
    }

    public void updateHiddenApps(Set<Integer> newHiddenAppIds, boolean hideImmediately) {
        this.hiddenAppIds.clear();
        this.hiddenAppIds.addAll(newHiddenAppIds);

        if (hideImmediately) {
            // Reconstruct the itemList with the new hidden app set
            rebuildVisibleApps();
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
        }

        notifyDataSetChanged();
    }

    public void setSearchQuery(String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (searchQuery.equals(normalizedQuery)) {
            return;
        }

        searchQuery = normalizedQuery;
        rebuildVisibleApps();
        notifyDataSetChanged();
    }

    public void setHostOnline(boolean online) {
        if (hostOnline != online) {
            hostOnline = online;
            notifyDataSetChanged();
        }
    }

    public void refreshVisibleApps() {
        rebuildVisibleApps();
        notifyDataSetChanged();
    }

    private void rebuildVisibleApps() {
        itemList.clear();
        for (AppView.AppObject app : allApps) {
            app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            if (isVisible(app)) {
                itemList.add(app);
                loader.queueCacheLoad(app.app);
            }
        }
        sortList(itemList);
    }

    private boolean isVisible(AppView.AppObject app) {
        return (showHiddenApps || !app.isHidden) &&
                (searchQuery.isEmpty() ||
                        app.app.getAppName().toLowerCase(Locale.ROOT).contains(searchQuery));
    }
    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.app_grid_item;
    }
    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        double scalingDivisor = ART_WIDTH_PX / (XR_ART_WIDTH_DP * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations();
        }

        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));

        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void setActionListener(ActionListener actionListener) {
        this.actionListener = actionListener;
    }

    /** Binds the session-only corner close action and fully resets recycled app cards. */
    static void bindSessionQuitButton(View parentView, boolean running, boolean hostOnline,
                                      Runnable quitAction) {
        View quit = parentView.findViewById(R.id.grid_quit_button);
        int visibility = running ? View.VISIBLE : View.GONE;
        boolean enabled = running && hostOnline;

        quit.setVisibility(visibility);
        quit.setEnabled(enabled);
        quit.setOnClickListener(enabled && quitAction != null
                ? v -> quitAction.run() : null);
        quit.setClickable(enabled && quitAction != null);
        quit.setFocusable(enabled);
    }

    static void bindCardSessionState(ImageView overlayView, RelativeLayout gridMask,
                                     TextView statusView, boolean hostOnline, boolean running) {
        // A visible status label and direct action row communicate the running state without
        // covering the app artwork with a large, redundant play icon.
        overlayView.setVisibility(View.GONE);
        if (!hostOnline) {
            gridMask.setBackgroundColor(0x77000000);
            statusView.setText(R.string.pcview_menu_header_offline);
            statusView.setVisibility(View.VISIBLE);
        }
        else if (running) {
            gridMask.setBackgroundColor(0x00000000);
            statusView.setText(R.string.xr_home_status_running);
            statusView.setVisibility(View.VISIBLE);
        }
        else {
            gridMask.setBackgroundColor(0x00000000);
            statusView.setVisibility(View.GONE);
        }
    }

    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    private static void sortList(List<AppView.AppObject> list) {
        Collections.sort(list, new Comparator<AppView.AppObject>() {
            @Override
            public int compare(AppView.AppObject lhs, AppView.AppObject rhs) {
                int lIndex = lhs.app.getAppIndex();
                int rIndex = rhs.app.getAppIndex();
                if (lIndex == rIndex) {
                    return lhs.app.getAppName().toLowerCase().compareTo(rhs.app.getAppName().toLowerCase());
                } else {
                    return lIndex - rIndex;
                }
            }
        });
    }

    public void addApp(AppView.AppObject app) {
        // Update hidden state
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());

        // Always add the app to the all apps list
        allApps.add(app);
        sortList(allApps);

        // Add the app to the adapter data if it's not hidden
        if (isVisible(app)) {
            // Queue a request to fetch this bitmap into cache
            loader.queueCacheLoad(app.app);

            // Add the app to our sorted list
            itemList.add(app);
            sortList(itemList);
        }
    }

    public void removeApp(AppView.AppObject app) {
        itemList.remove(app);
        allApps.remove(app);
    }

    public AppView.AppObject findAppById(int appId) {
        for (AppView.AppObject app : allApps) {
            if (app.app.getAppId() == appId) {
                return app;
            }
        }
        return null;
    }

    public int getAllAppCount() {
        return allApps.size();
    }

    public AppView.AppObject getAllApp(int index) {
        return allApps.get(index);
    }

    @Override
    public void clear() {
        super.clear();
        allApps.clear();
    }

    @Override
    public void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, AppView.AppObject obj) {
        TextView statusView = parentView.findViewById(R.id.grid_status);
        TextView appNameView = parentView.findViewById(R.id.grid_app_name);
        View primaryAction = parentView.findViewById(R.id.grid_primary_action);
        View quitSession = parentView.findViewById(R.id.grid_quit_button);
        View moreActions = parentView.findViewById(R.id.grid_more_button);

        // Let the cached asset loader handle it
        loader.populateImageView(obj.app, imgView, txtView);
        appNameView.setText(obj.app.getAppName());

        bindCardSessionState(overlayView, gridMask, statusView, hostOnline, obj.isRunning);

        if (obj.isHidden) {
            parentView.setAlpha(0.40f);
        }
        else if (!hostOnline) {
            parentView.setAlpha(0.62f);
        }
        else {
            parentView.setAlpha(1.0f);
        }

        primaryAction.setContentDescription(!hostOnline
                ? obj.app.getAppName() + ", "
                        + context.getString(R.string.pcview_menu_header_offline)
                : obj.isRunning ?
                obj.app.getAppName() + ", " + context.getString(R.string.xr_home_status_running) :
                obj.app.getAppName());
        primaryAction.setEnabled(hostOnline);
        primaryAction.setClickable(hostOnline);
        primaryAction.setFocusable(hostOnline);
        primaryAction.setOnClickListener(hostOnline ? v -> {
            if (actionListener != null) {
                actionListener.onPrimaryAction(obj, v);
            }
        } : null);
        bindSessionQuitButton(parentView, obj.isRunning, hostOnline,
                () -> {
                    if (actionListener != null) {
                        actionListener.onQuitSession(obj);
                    }
                });
        quitSession.setContentDescription(context.getString(
                R.string.xr_home_quit_for_app, obj.app.getAppName()));
        moreActions.setVisibility(View.VISIBLE);
        moreActions.setEnabled(true);
        moreActions.setClickable(true);
        moreActions.setFocusable(true);
        moreActions.setContentDescription(context.getString(
                R.string.xr_home_more_for_app, obj.app.getAppName()));
        moreActions.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onMoreActions(obj, v, parentView);
            }
        });
    }
}
