package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.ui.HomeSessionLaunchPolicy;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends AppCompatActivity implements AdapterFragmentCallbacks {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private String lastRunningAppUuid;
    private String lastHostSessionId;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    private PreferenceConfiguration prefConfig;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int EXPORT_LAUNCHER_FILE_ID = 7;
    private final static int HIDE_APP_ID = 8;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }
                    lastRunningAppId = computer.runningGameId;
                    lastRunningAppUuid = computer.runningGameUUID;
                    lastHostSessionId = computer.hostSessionId;
                    runOnUiThread(() -> {
                        TextView label = findViewById(R.id.appListText);
                        if (label != null) {
                            label.setText(computer.name);
                        }
                        updateComputerStatus(computer);
                        updateCurrentSessionBanner();
                    });

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);
                    appGridAdapter.setActionListener(new AppGridAdapter.ActionListener() {
                        @Override
                        public void onPrimaryAction(AppObject app, View anchor) {
                            handleAppSelection(app);
                        }

                        @Override
                        public void onQuitSession(AppObject app) {
                            endCurrentSession(app);
                        }

                        @Override
                        public void onMoreActions(AppObject app, View anchor, View card) {
                            showAppActions(app, anchor, card);
                        }
                    });

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();
                    updateUiWithServerinfo(computer);

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            SearchView searchView = findViewById(R.id.appSearch);
                            appGridAdapter.setSearchQuery(searchView.getQuery().toString());

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, this.prefConfig);

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                // Keep launch and session decisions tied to the latest authoritative host state.
                computer = details;
                runOnUiThread(() -> updateComputerStatus(details));

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // Keep the library visible so the user can see the machine state and go back
                    // without being ejected from the Home Space shell.
                    runOnUiThread(() -> {
                        if (blockingLoadSpinner != null) {
                            blockingLoadSpinner.dismiss();
                            blockingLoadSpinner = null;
                        }
                    });
                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    if (details.runningGameId != lastRunningAppId
                            || !Objects.equals(details.runningGameUUID, lastRunningAppUuid)
                            || !Objects.equals(details.hostSessionId, lastHostSessionId)) {
                        updateRunningSession(details);
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                updateRunningSession(details);
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        // Setup the back button — returns to the machine (Pc) list. AppView was started from
        // PcView, so finishing pops straight back to it. (In immersive XR there's no system
        // back affordance on the panel, so this in-app button is the way back.)
        findViewById(R.id.backButton)
            .setOnClickListener(v -> finish());

        findViewById(R.id.settingsButton)
                .setOnClickListener(v -> startActivity(new Intent(this, StreamSettings.class)));
        findViewById(R.id.resumeSessionButton).setOnClickListener(v -> resumeCurrentSession());
        findViewById(R.id.endSessionButton).setOnClickListener(v -> endCurrentSession());
        SearchView searchView = findViewById(R.id.appSearch);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (appGridAdapter != null) {
                    appGridAdapter.setSearchQuery(query);
                }
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (appGridAdapter != null) {
                    appGridAdapter.setSearchQuery(newText);
                }
                return true;
            }
        });

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);
        updateCurrentSessionBanner();

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        prefConfig = PreferenceConfiguration.readPreferences(this);
        inForeground = true;
        startComputerUpdates();

    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            } else {
                // Clear the content if the user cancelled or if there was an error before this point
                ShortcutHelper.artFileContentToExport = null;
                // Show "File export cancelled." toast only if the user explicitly cancelled.
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

        menu.setHeaderTitle(selectedApp.app.getAppName());
        populateAppActions(menu, selectedApp, info.targetView, true);
    }

    private void populateAppActions(Menu menu, AppObject selectedApp, View targetView,
                                    boolean includeSessionActions) {
        boolean hostOnline = computer != null
                && computer.state == ComputerDetails.State.ONLINE
                && computer.activeAddress != null;
        boolean selectedIsCurrent = isCurrentSessionApp(selectedApp);
        if (includeSessionActions) {
            if (hostOnline && !hasCurrentSession()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1,
                        getResources().getString(R.string.applist_menu_start));
            }
            else if (hostOnline) {
                if (selectedIsCurrent) {
                    menu.add(Menu.NONE, START_OR_RESUME_ID, 1,
                            getResources().getString(R.string.applist_menu_resume));
                    menu.add(Menu.NONE, QUIT_ID, 2,
                            getResources().getString(R.string.applist_menu_quit));
                }
                else {
                    menu.add(Menu.NONE, START_WITH_QUIT, 1,
                            getResources().getString(R.string.applist_menu_quit_and_start));
                }
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (!selectedIsCurrent || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = targetView.findViewById(R.id.grid_image);
            if (appImageView != null) {
                // We have a grid ImageView, so we must be in grid-mode
                BitmapDrawable drawable = (BitmapDrawable)appImageView.getDrawable();
                if (drawable != null && drawable.getBitmap() != null) {
                    // We have a bitmap loaded too
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                }
            }
        }

        menu.add(Menu.NONE, EXPORT_LAUNCHER_FILE_ID, 6, getResources().getString(R.string.applist_menu_export_launcher));
    }

    private void showAppActions(AppObject app, View anchor, View appCard) {
        PopupMenu popup = new PopupMenu(this, anchor);
        // Tapping the active card resumes it and its corner close button ends the session. Keep More
        // limited to secondary actions while preserving the legacy context menu above.
        populateAppActions(popup.getMenu(), app, appCard, false);
        popup.setOnMenuItemClickListener(item -> handleAppAction(item, app, appCard));
        popup.show();
    }

    private void handleAppSelection(AppObject selectedApp) {
        if (managerBinder == null || computer == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        if (computer.state != ComputerDetails.State.ONLINE
                || computer.activeAddress == null) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }

        if (HomeSessionLaunchPolicy.actionFor(lastRunningAppId,
                computer != null ? computer.runningGameUUID : null,
                selectedApp.app.getAppId(), selectedApp.app.getAppUUID()) ==
                HomeSessionLaunchPolicy.Action.START_OR_RESUME) {
            startApp(selectedApp.app);
            return;
        }

        // A host exposes one current session. Never make a second launch look independent:
        // explicitly let the user resume it, replace it, or cancel.
        AppObject runningApp = findCurrentSessionApp();
        String runningName = runningApp != null ? runningApp.app.getAppName() :
                getString(R.string.xr_home_status_running);
        NvApp currentApp = runningApp != null ? runningApp.app
                : new NvApp("app", computer.runningGameUUID, computer.runningGameId, false);
        HostSessionSnapshot expectedSession = captureCurrentHostSession(currentApp);
        new AlertDialog.Builder(this)
                .setTitle(R.string.xr_session_replace_title)
                .setMessage(getString(R.string.xr_session_replace_message, runningName))
                .setPositiveButton(R.string.xr_session_end_and_start,
                        (dialog, which) -> endCurrentSessionThenStart(
                                selectedApp.app, expectedSession))
                .setNeutralButton(R.string.xr_session_resume_current,
                        (dialog, which) -> resumeCurrentSession())
                .setNegativeButton(R.string.applist_menu_cancel, null)
                .show();
    }

    private void startApp(NvApp app) {
        startApp(app, false);
    }

    private void startApp(NvApp app, boolean requireHostIdle) {
        if (managerBinder == null || computer == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        if (computer.state != ComputerDetails.State.ONLINE
                || computer.activeAddress == null) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }

        ServerHelper.doStart(this, app, computer, managerBinder, false,
                requireHostIdle);
    }

    private boolean isCurrentSessionApp(AppObject app) {
        return app != null && computer != null &&
                HomeSessionLaunchPolicy.isCurrentSessionApp(computer.runningGameId,
                        computer.runningGameUUID, app.app.getAppId(), app.app.getAppUUID());
    }

    private AppObject findCurrentSessionApp() {
        if (appGridAdapter == null || computer == null) {
            return null;
        }
        for (int i = 0; i < appGridAdapter.getAllAppCount(); i++) {
            AppObject app = appGridAdapter.getAllApp(i);
            if (isCurrentSessionApp(app)) {
                return app;
            }
        }
        return null;
    }

    private boolean hasCurrentSession() {
        return lastRunningAppId != 0
                || (lastRunningAppUuid != null && !lastRunningAppUuid.isEmpty())
                || lastHostSessionId != null;
    }

    private boolean validateCardSessionAction(AppObject expectedApp) {
        if (managerBinder == null || computer == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return false;
        }
        if (computer.state != ComputerDetails.State.ONLINE || computer.activeAddress == null) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!isCurrentSessionApp(expectedApp)) {
            Toast.makeText(this, R.string.xr_session_changed, Toast.LENGTH_SHORT).show();
            managerBinder.pollComputerNow(uuidString);
            return false;
        }
        return true;
    }

    private static final class HostSessionSnapshot {
        final ComputerDetails computer;
        final NvApp app;
        final String hostSessionId;
        final SessionSettingsStore.PcIdentity pcIdentity;
        final String localSessionId;

        HostSessionSnapshot(ComputerDetails computer, NvApp app, String hostSessionId,
                            SessionSettingsStore.PcIdentity pcIdentity,
                            String localSessionId) {
            this.computer = computer;
            this.app = app;
            this.hostSessionId = hostSessionId;
            this.pcIdentity = pcIdentity;
            this.localSessionId = localSessionId;
        }
    }

    private HostSessionSnapshot captureCurrentHostSession(NvApp expectedApp) {
        if (computer == null || expectedApp == null
                || !SessionSettingsStore.ResumeMetadata.isValidHostSessionId(
                        computer.hostSessionId)) {
            return null;
        }
        NvApp app = new NvApp(expectedApp.getAppName(), expectedApp.getAppUUID(),
                expectedApp.getAppId(), expectedApp.isHdrSupported());
        ComputerDetails capturedComputer = new ComputerDetails(computer);
        String fallbackHost = capturedComputer.activeAddress != null
                ? capturedComputer.activeAddress.address : null;
        try {
            SessionSettingsStore.PcIdentity pcIdentity =
                    new SessionSettingsStore.PcIdentity(capturedComputer.uuid, fallbackHost);
            SessionSettingsStore.SessionRecord record =
                    new SessionSettingsStore(this).getCurrentSession(pcIdentity);
            String localSessionId = null;
            if (record != null && record.getResumeMetadata() != null
                    && capturedComputer.hostSessionId.equals(
                            record.getResumeMetadata().getHostSessionId())) {
                localSessionId = record.getLocalSessionId();
            }
            return new HostSessionSnapshot(capturedComputer, app,
                    capturedComputer.hostSessionId, pcIdentity, localSessionId);
        }
        catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean stillOwnsHostSession(HostSessionSnapshot expected) {
        return expected != null && inForeground && computer != null
                && Objects.equals(expected.hostSessionId, computer.hostSessionId)
                && HomeSessionLaunchPolicy.isCurrentSessionApp(computer.runningGameId,
                        computer.runningGameUUID, expected.app.getAppId(),
                        expected.app.getAppUUID());
    }

    private boolean quitCompletionStillApplies(HostSessionSnapshot expected) {
        if (expected == null || !inForeground || computer == null) {
            return false;
        }
        if (stillOwnsHostSession(expected)) {
            return true;
        }
        return computer.runningGameId == 0
                && (computer.runningGameUUID == null || computer.runningGameUUID.isEmpty())
                && computer.hostSessionId == null;
    }

    private void resumeCurrentSession() {
        resumeCurrentSession(null);
    }

    private void resumeCurrentSession(AppObject expectedApp) {
        if (expectedApp != null && !validateCardSessionAction(expectedApp)) {
            return;
        }
        int runningAppId = computer != null ? computer.runningGameId : lastRunningAppId;
        if (!hasCurrentSession()) {
            return;
        }

        AppObject runningApp = expectedApp != null ? expectedApp : findCurrentSessionApp();
        NvApp app = runningApp != null ? runningApp.app :
                new NvApp("app", computer != null ? computer.runningGameUUID : null,
                        runningAppId, false);
        startApp(app);
    }

    private void endCurrentSession() {
        endCurrentSession(null);
    }

    private void endCurrentSession(AppObject expectedApp) {
        if (expectedApp != null && !validateCardSessionAction(expectedApp)) {
            return;
        }
        if (managerBinder == null || computer == null || !hasCurrentSession()) {
            return;
        }

        AppObject runningApp = expectedApp != null ? expectedApp : findCurrentSessionApp();
        NvApp app = runningApp != null ? runningApp.app :
                new NvApp("app", computer.runningGameUUID, computer.runningGameId, false);
        HostSessionSnapshot expectedSession = captureCurrentHostSession(app);
        if (expectedSession == null) {
            Toast.makeText(this, R.string.xr_session_changed, Toast.LENGTH_SHORT).show();
            return;
        }
        UiHelper.displayQuitConfirmationDialog(this,
                () -> {
                    if (!stillOwnsHostSession(expectedSession)) {
                        Toast.makeText(this, R.string.xr_session_changed,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ServerHelper.doQuit(this, expectedSession.computer, expectedSession.app,
                            expectedSession.hostSessionId, managerBinder, () -> {
                        clearPersistedSession(expectedSession);
                        if (managerBinder != null) {
                            managerBinder.pollComputerNow(uuidString);
                        }
                    });
                },
                null);
    }

    private void endCurrentSessionThenStart(NvApp nextApp) {
        if (!hasCurrentSession() || managerBinder == null || computer == null) {
            startApp(nextApp);
            return;
        }

        AppObject runningApp = findCurrentSessionApp();
        NvApp currentApp = runningApp != null ? runningApp.app :
                new NvApp("app", computer.runningGameUUID, lastRunningAppId, false);
        endCurrentSessionThenStart(nextApp, captureCurrentHostSession(currentApp));
    }

    private void endCurrentSessionThenStart(NvApp nextApp,
                                            HostSessionSnapshot expectedSession) {
        if (!stillOwnsHostSession(expectedSession)) {
            Toast.makeText(this, R.string.xr_session_changed, Toast.LENGTH_SHORT).show();
            return;
        }
        ServerHelper.doQuit(this, expectedSession.computer, expectedSession.app,
                expectedSession.hostSessionId, managerBinder, () -> {
            clearPersistedSession(expectedSession);
            runOnUiThread(() -> {
                if (quitCompletionStillApplies(expectedSession)) {
                    if (stillOwnsHostSession(expectedSession)) {
                        updateRunningSessionCleared(expectedSession);
                    }
                    startApp(nextApp, true);
                }
            });
        });
    }

    private void clearPersistedSession(HostSessionSnapshot expected) {
        if (expected == null || expected.localSessionId == null) {
            return;
        }
        new SessionSettingsStore(this).clearCurrentSession(expected.pcIdentity,
                expected.localSessionId, expected.hostSessionId);
    }

    private void updateComputerStatus(ComputerDetails details) {
        boolean online = details.state == ComputerDetails.State.ONLINE
                && details.activeAddress != null;
        if (appGridAdapter != null) {
            appGridAdapter.setHostOnline(online);
        }
        findViewById(R.id.resumeSessionButton).setEnabled(online);
        findViewById(R.id.endSessionButton).setEnabled(online);
        TextView status = findViewById(R.id.computerStatusText);
        if (status == null) {
            return;
        }

        switch (details.state) {
            case ONLINE:
                status.setText(R.string.pcview_menu_header_online);
                status.setTextColor(0xFF81C995);
                break;
            case OFFLINE:
                status.setText(R.string.pcview_menu_header_offline);
                status.setTextColor(0xFFFFB4AB);
                break;
            default:
                status.setText(R.string.xr_home_refreshing);
                status.setTextColor(0xFFBDC1C6);
                break;
        }
    }

    private void updateCurrentSessionBanner() {
        runOnUiThread(() -> {
            View panel = findViewById(R.id.currentSessionPanel);
            TextView appName = findViewById(R.id.currentSessionAppText);
            if (panel == null || appName == null) {
                return;
            }

            if (!hasCurrentSession()) {
                panel.setVisibility(View.GONE);
                return;
            }

            AppObject runningApp = findCurrentSessionApp();
            appName.setText(runningApp != null ? runningApp.app.getAppName() :
                    getString(R.string.xr_home_status_running));
            panel.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        if (info == null) {
            return super.onContextItemSelected(item);
        }
        AppObject app = (AppObject) appGridAdapter.getItem(info.position);
        return handleAppAction(item, app, info.targetView);
    }

    private boolean handleAppAction(MenuItem item, AppObject app, View targetView) {
        int itemId = item.getItemId();
        switch (itemId) {
            case START_WITH_QUIT: {
                if (!hostIsOnline()) {
                    Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
                    return true;
                }
                AppObject runningApp = findCurrentSessionApp();
                NvApp currentApp = runningApp != null ? runningApp.app
                        : new NvApp("app", computer.runningGameUUID,
                                computer.runningGameId, false);
                HostSessionSnapshot expectedSession = captureCurrentHostSession(currentApp);
                UiHelper.displayQuitConfirmationDialog(this,
                        () -> endCurrentSessionThenStart(app.app, expectedSession), null);
                return true;
            }

            case START_OR_RESUME_ID: {
                startApp(app.app);
                return true;
            }

            case QUIT_ID: {
                if (!hostIsOnline()) {
                    Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
                    return true;
                }
                HostSessionSnapshot expectedSession = captureCurrentHostSession(app.app);
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        if (!stillOwnsHostSession(expectedSession)) {
                            Toast.makeText(AppView.this, R.string.xr_session_changed,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ServerHelper.doQuit(AppView.this, expectedSession.computer,
                                expectedSession.app, expectedSession.hostSessionId,
                                managerBinder, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Re-read authoritative /serverinfo state immediately.
                                        // The UI must not assume /cancel succeeded or mirror the
                                        // host's resume-grace timer locally.
                                        if (managerBinder != null) {
                                            managerBinder.pollComputerNow(uuidString);
                                        }
                                    }
                                });
                    }
                }, null);
                return true;
            }

            case VIEW_DETAILS_ID: {
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;
            }

            case HIDE_APP_ID: {
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                } else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;
            }

            case CREATE_SHORTCUT_ID: {
                ImageView appImageView = targetView.findViewById(R.id.grid_image);
                Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;
            }

            case EXPORT_LAUNCHER_FILE_ID: {
                if (app.app.getAppUUID() == null || (app.app.getAppUUID() != null && app.app.getAppUUID().isEmpty())) {
                    UiHelper.displayConfirmationDialog(
                            AppView.this,
                            getResources().getString(R.string.title_export_sunshine_launcher_file),
                            getResources().getString(R.string.message_export_sunshine_launcher_file),
                            getResources().getString(R.string.proceed),
                            getResources().getString(R.string.cancel),
                            () -> shortcutHelper.exportLauncherFile(computer, app.app),
                            null
                    );
                } else {
                    shortcutHelper.exportLauncherFile(computer, app.app);
                }
                return true;
            }

            default: {
                return false;
            }
        }
    }

    private boolean hostIsOnline() {
        return computer != null && computer.state == ComputerDetails.State.ONLINE
                && computer.activeAddress != null && managerBinder != null;
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // Look through our current app list to tag the one host-owned session. Prefer the
                // stable app UUID when Apollo refreshes an app with a different numeric ID.
                for (int i = 0; i < appGridAdapter.getAllAppCount(); i++) {
                    AppObject existingApp = appGridAdapter.getAllApp(i);
                    boolean isCurrent = HomeSessionLaunchPolicy.isCurrentSessionApp(
                            details.runningGameId, details.runningGameUUID,
                            existingApp.app.getAppId(), existingApp.app.getAppUUID());
                    if (existingApp.isRunning != isCurrent) {
                        existingApp.isRunning = isCurrent;
                        updated = true;
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void updateRunningSession(ComputerDetails details) {
        String previousHostSessionId = lastHostSessionId;
        boolean changed = details.runningGameId != lastRunningAppId
                || !Objects.equals(details.runningGameUUID, lastRunningAppUuid)
                || !Objects.equals(details.hostSessionId, lastHostSessionId);
        lastRunningAppId = details.runningGameId;
        lastRunningAppUuid = details.runningGameUUID;
        lastHostSessionId = details.hostSessionId;
        if (details.runningGameId == 0
                && (details.runningGameUUID == null || details.runningGameUUID.isEmpty())
                && details.hostSessionId == null) {
            clearPersistedSessionAfterAuthoritativeEnd(previousHostSessionId);
        }
        if (changed) {
            // Context-menu contents are snapshots. Close a stale Resume/Quit menu when the
            // authoritative host state changes; the next open will rebuild Start-ready actions.
            runOnUiThread(this::closeContextMenu);
        }
        updateCurrentSessionBanner();
    }

    private void updateRunningSessionCleared(HostSessionSnapshot expected) {
        if (!stillOwnsHostSession(expected)) {
            return;
        }
        computer.runningGameId = 0;
        computer.runningGameUUID = null;
        computer.hostSessionId = null;
        updateRunningSession(computer);
    }

    private void clearPersistedSessionAfterAuthoritativeEnd(String endedHostSessionId) {
        if (!inForeground || computer == null || endedHostSessionId == null) {
            return;
        }
        String fallbackHost = computer.activeAddress != null
                ? computer.activeAddress.address : null;
        try {
            SessionSettingsStore store = new SessionSettingsStore(this);
            SessionSettingsStore.PcIdentity pc =
                    new SessionSettingsStore.PcIdentity(computer.uuid, fallbackHost);
            SessionSettingsStore.SessionRecord record = store.getCurrentSession(pc);
            if (record != null && record.getResumeMetadata() != null
                    && endedHostSessionId.equals(
                            record.getResumeMetadata().getHostSessionId())) {
                store.clearCurrentSession(pc, record.getLocalSessionId(), endedHostSessionId);
            }
        }
        catch (IllegalArgumentException ignored) {
            // A transient discovery record without an identity cannot own persisted settings.
        }
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    AppObject existingApp = appGridAdapter.findAppById(app.getAppId());
                    if (existingApp != null) {
                        // Found the app; update its properties
                        if (!existingApp.app.getAppName().equals(app.getAppName())) {
                            existingApp.app.setAppName(app.getAppName());
                            updated = true;
                        }
                        if (!Objects.equals(existingApp.app.getAppUUID(), app.getAppUUID())) {
                            existingApp.app.setAppUUID(app.getAppUUID());
                            updated = true;
                        }
                        if (existingApp.app.isHdrSupported() != app.isHdrSupported()) {
                            existingApp.app.setHdrSupported(app.isHdrSupported());
                            updated = true;
                        }
                        foundExistingApp = true;
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getAllAppCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = appGridAdapter.getAllApp(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, getString(R.string.app_removed_from_pc));
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.refreshVisibleApps();
                }
                updateCurrentSessionBanner();
            }
        });
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.app_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(appGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                handleAppSelection((AppObject) appGridAdapter.getItem(pos));
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
        listView.requestFocus();
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
