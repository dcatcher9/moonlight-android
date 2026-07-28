package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Objects;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.ui.HomeSessionLaunchPolicy;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PcView extends AppCompatActivity implements AdapterFragmentCallbacks {
    private View noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private AbsListView pcListView;
    private TextView machineSectionTitle;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private boolean pairingInProgress;
    private ComputerDetails.AddressTuple pendingPairingAddress;
    private String pendingPairingPin, pendingPairingPassphrase;
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

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
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

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }

    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int OPEN_MANAGEMENT_PAGE_ID = 20;
    private final static int PAIR_ID_OTP = 21;
    private String contextMenuComputerUuid;
    private int contextMenuRunningGameId;
    private String contextMenuRunningGameUuid;
    private String contextMenuHostSessionId;
    private boolean contextMenuOpen;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        View settingsButton = findViewById(R.id.settingsButton);
        View addComputerButton = findViewById(R.id.manuallyAddPc);
        View helpButton = findViewById(R.id.helpButton);
        TextView homeTitle = findViewById(R.id.homeTitle);
        machineSectionTitle = findViewById(R.id.machineSectionTitle);
        homeTitle.setText(R.string.xr_home_title);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, StreamSettings.class));
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });
        helpButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                HelpLauncher.launchSetupGuide(PcView.this);
            }
        });
        pcGridAdapter.setActionListener(new PcGridAdapter.ActionListener() {
            @Override
            public void onPrimaryAction(ComputerObject computer, View anchor) {
                performPrimaryComputerAction(computer, anchor);
            }

            @Override
            public void onMoreActions(ComputerObject computer, View anchor) {
                showComputerActions(computer, anchor);
            }
        });

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.setVisibility(View.GONE);
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
        updateMachinePresentation();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }

        Intent intent = getIntent();

        String hostname = intent.getStringExtra("hostname");
        int port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT);
        pendingPairingPin = intent.getStringExtra("pin");
        pendingPairingPassphrase = intent.getStringExtra("passphrase");

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = new ComputerDetails.AddressTuple(hostname, port);
        } else {
            pendingPairingPin = null;
            pendingPairingPassphrase = null;
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
//                        } else
                        }
                            if (pendingPairingAddress != null) {
                            if (
                                details.state == ComputerDetails.State.ONLINE &&
                                details.activeAddress.equals(pendingPairingAddress)
                            ) {
                                PcView.this.runOnUiThread(() -> {
                                    doPair(details, pendingPairingPin, pendingPairingPassphrase);
                                    pendingPairingAddress = null;
                                    pendingPairingPin = null;
                                    pendingPairingPassphrase = null;
                                });
                            }
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        pairingInProgress = false;
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        contextMenuComputerUuid = computer.details.uuid;
        contextMenuRunningGameId = computer.details.runningGameId;
        contextMenuRunningGameUuid = computer.details.runningGameUUID;
        contextMenuHostSessionId = computer.details.hostSessionId;
        contextMenuOpen = true;

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        populateComputerActions(menu, computer);
    }

    private void populateComputerActions(Menu menu, ComputerObject computer) {
        // Keep these actions available from both the legacy context menu and the visible
        // More button on each XR machine card.
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID_OTP, 1, getResources().getString(R.string.pcview_menu_pair_pc_otp));
            menu.add(Menu.NONE, PAIR_ID, 2, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    private void showComputerActions(ComputerObject computer, View anchor) {
        contextMenuComputerUuid = computer.details.uuid;
        contextMenuRunningGameId = computer.details.runningGameId;
        contextMenuRunningGameUuid = computer.details.runningGameUUID;
        contextMenuHostSessionId = computer.details.hostSessionId;
        contextMenuOpen = true;

        PopupMenu popup = new PopupMenu(this, anchor);
        populateComputerActions(popup.getMenu(), computer);
        popup.setOnMenuItemClickListener(this::onContextItemSelected);
        popup.setOnDismissListener(menu -> contextMenuOpen = false);
        popup.show();
    }

    private void performPrimaryComputerAction(ComputerObject computer, View anchor) {
        if (computer.details.state == ComputerDetails.State.UNKNOWN) {
            showComputerActions(computer, anchor);
        }
        else if (computer.details.state == ComputerDetails.State.OFFLINE) {
            doWakeOnLan(computer.details);
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            doPair(computer.details, null, null);
        }
        else {
            doAppList(computer.details, false, false);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        contextMenuOpen = false;
    }

    private void doPair(final ComputerDetails computer, String otp, String passphrase) {
        if (pairingInProgress) {
            return;
        }
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        pairingInProgress = true;
        showPairingStage(getString(R.string.xr_pairing_connecting_title, computer.name),
                getString(R.string.xr_pairing_connecting_detail), true, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        String pinStr = otp;
                        if (pinStr == null) {
                            pinStr = PairingManager.generatePinString();
                        }

                        if (passphrase == null) {
                            showPairingStage(getString(R.string.xr_pairing_pin_title),
                                    getString(R.string.xr_pairing_pin_detail, pinStr),
                                    true, false);
                        } else {
                            showPairingStage(getString(R.string.xr_pairing_pin_title),
                                    getString(R.string.xr_pairing_otp_detail), true, false);
                        }

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                final String stageMessage = message != null || success
                        ? message : getString(R.string.pair_fail);
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed() || !inForeground) {
                            pairingInProgress = false;
                            return;
                        }
                        if (stageMessage != null) {
                            showPairingStage(getString(R.string.xr_pairing_failed_title),
                                    stageMessage, false, true);
                        }

                        if (toastSuccess) {
                            showPairingStage(getString(R.string.xr_pairing_connected_title),
                                    getString(R.string.xr_pairing_connected_detail),
                                    false, false);
                            View stage = findViewById(R.id.homeConnectionStage);
                            stage.postDelayed(() -> {
                                pairingInProgress = false;
                                if (isFinishing() || isDestroyed() || !inForeground) {
                                    return;
                                }
                                stage.setVisibility(View.GONE);
                                doAppList(computer, true, false);
                            }, 650L);
                        }
                        else {
                            pairingInProgress = false;
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void showPairingStage(CharSequence title, CharSequence detail,
                                  boolean busy, boolean error) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            View panel = findViewById(R.id.homeConnectionStage);
            TextView titleView = findViewById(R.id.homeConnectionStageTitle);
            TextView detailView = findViewById(R.id.homeConnectionStageDetail);
            ProgressBar progress = findViewById(R.id.homeConnectionStageProgress);
            View dismiss = findViewById(R.id.homeConnectionStageDismiss);
            if (panel == null || titleView == null || detailView == null) {
                return;
            }
            titleView.setText(title);
            titleView.setTextColor(ContextCompat.getColor(this,
                    error ? R.color.xr_danger : R.color.xr_text_primary));
            detailView.setText(detail);
            progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
            dismiss.setVisibility(error ? View.VISIBLE : View.GONE);
            dismiss.setOnClickListener(v -> panel.setVisibility(View.GONE));
            panel.setVisibility(View.VISIBLE);
        });
    }

    private void doOTPPair(final ComputerDetails computer) {
        if (pairingInProgress) {
            return;
        }
        Context context = PcView.this;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = getResources().getDimensionPixelSize(R.dimen.xr_space_xl);
        int verticalPadding = getResources().getDimensionPixelSize(R.dimen.xr_space_lg);
        layout.setPadding(horizontalPadding, verticalPadding,
                horizontalPadding, verticalPadding);

        final EditText otpInput = new EditText(context);
        otpInput.setHint("PIN");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });

        final EditText passphraseInput = new EditText(context);
        passphraseInput.setHint(getString(R.string.pair_passphrase_hint));
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        layout.addView(otpInput);
        layout.addView(passphraseInput);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(R.string.pcview_menu_pair_pc_otp);
        dialogBuilder.setView(layout);

        dialogBuilder.setPositiveButton(getString(R.string.proceed), null);

        dialogBuilder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = dialogBuilder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = otpInput.getText().toString();
            String passphrase = passphraseInput.getText().toString();
            if (pin.length() != 4) {
                Toast.makeText(context, getString(R.string.pair_pin_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            if (passphrase.length() < 4 ) {
                Toast.makeText(context, getString(R.string.pair_passphrase_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            doPair(computer, pin, passphrase);
            dialog.dismiss(); // Manually dismiss the dialog if the input is valid
        });
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        final ComputerObject computer = findComputerByUuid(contextMenuComputerUuid);
        if (computer == null) {
            return super.onContextItemSelected(item);
        }
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details, null, null);
                return true;

            case PAIR_ID_OTP:
                doOTPPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                            return;
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app",
                        computer.details.runningGameUUID,
                        computer.details.runningGameId, false),
                        computer.details, managerBinder, false);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                HostSessionSnapshot expectedSession = captureHostSession(computer.details);
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        if (!stillOwnsHostSession(expectedSession)) {
                            Toast.makeText(PcView.this, R.string.xr_session_changed,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ServerHelper.doQuit(PcView.this, expectedSession.computer,
                                expectedSession.app, expectedSession.hostSessionId,
                                managerBinder,
                                () -> {
                                    clearPersistedSession(expectedSession);
                                    if (managerBinder != null) {
                                        managerBinder.pollComputerNow(
                                                expectedSession.computer.uuid);
                                    }
                                });
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            case OPEN_MANAGEMENT_PAGE_ID:
                String managementUrl = computer.guessManagementUrl();
                if (managementUrl == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.pcview_error_no_management_url), Toast.LENGTH_LONG).show();
                } else {
                    HelpLauncher.launchUrl(PcView.this, managementUrl);
                }

            default:
                return super.onContextItemSelected(item);
        }
    }

    private void removeComputer(ComputerDetails details) {
        clearAllPersistedSession(details);
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();
                updateMachinePresentation();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
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

    private void clearAllPersistedSession(ComputerDetails details) {
        if (details == null) {
            return;
        }
        String fallbackHost = details.activeAddress != null
                ? details.activeAddress.address : null;
        try {
            new SessionSettingsStore(this).clearCurrentSession(
                    new SessionSettingsStore.PcIdentity(details.uuid, fallbackHost));
        }
        catch (IllegalArgumentException ignored) {
            // A deleted transient discovery record may not yet have a stable identity.
        }
    }

    private HostSessionSnapshot captureHostSession(ComputerDetails details) {
        if (details == null || !SessionSettingsStore.ResumeMetadata.isValidHostSessionId(
                details.hostSessionId)) {
            return null;
        }
        ComputerDetails captured = new ComputerDetails(details);
        NvApp app = new NvApp("app", captured.runningGameUUID,
                captured.runningGameId, false);
        String fallbackHost = captured.activeAddress != null
                ? captured.activeAddress.address : null;
        try {
            SessionSettingsStore.PcIdentity pc =
                    new SessionSettingsStore.PcIdentity(captured.uuid, fallbackHost);
            SessionSettingsStore.SessionRecord record =
                    new SessionSettingsStore(this).getCurrentSession(pc);
            String localSessionId = null;
            if (record != null && record.getResumeMetadata() != null
                    && captured.hostSessionId.equals(
                            record.getResumeMetadata().getHostSessionId())) {
                localSessionId = record.getLocalSessionId();
            }
            return new HostSessionSnapshot(captured, app, captured.hostSessionId,
                    pc, localSessionId);
        }
        catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean stillOwnsHostSession(HostSessionSnapshot expected) {
        if (expected == null || !inForeground) {
            return false;
        }
        ComputerObject current = findComputerByUuid(expected.computer.uuid);
        return current != null
                && Objects.equals(expected.hostSessionId,
                        current.details.hostSessionId)
                && HomeSessionLaunchPolicy.isCurrentSessionApp(
                        current.details.runningGameId,
                        current.details.runningGameUUID,
                        expected.app.getAppId(), expected.app.getAppUUID());
    }

    private void updateComputer(ComputerDetails details) {
        if (contextMenuOpen && details.uuid.equals(contextMenuComputerUuid)
                && (details.runningGameId != contextMenuRunningGameId
                || !Objects.equals(details.runningGameUUID, contextMenuRunningGameUuid)
                || !Objects.equals(details.hostSessionId, contextMenuHostSessionId))) {
            // Context-menu contents are snapshots. Close stale Resume/Quit actions when Apollo's
            // authoritative session state changes; the next open rebuilds the current actions.
            closeContextMenu();
        }
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            String endedHostSessionId = existingEntry.details.hostSessionId;
            // Replace the information in the existing entry
            existingEntry.details = details;
            if (inForeground && details.state == ComputerDetails.State.ONLINE
                    && details.runningGameId == 0
                    && (details.runningGameUUID == null
                    || details.runningGameUUID.isEmpty())
                    && details.hostSessionId == null) {
                clearPersistedSessionAfterAuthoritativeEnd(details,
                        endedHostSessionId);
            }
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
        updateMachinePresentation();
    }

    private void clearPersistedSession(HostSessionSnapshot expected) {
        if (expected == null || expected.localSessionId == null) {
            return;
        }
        new SessionSettingsStore(this).clearCurrentSession(expected.pcIdentity,
                expected.localSessionId, expected.hostSessionId);
    }

    private void clearPersistedSessionAfterAuthoritativeEnd(ComputerDetails details,
                                                             String endedHostSessionId) {
        if (details == null || endedHostSessionId == null) {
            return;
        }
        String fallbackHost = details.activeAddress != null
                ? details.activeAddress.address : null;
        try {
            SessionSettingsStore store = new SessionSettingsStore(this);
            SessionSettingsStore.PcIdentity pc =
                    new SessionSettingsStore.PcIdentity(details.uuid, fallbackHost);
            SessionSettingsStore.SessionRecord record = store.getCurrentSession(pc);
            if (record != null && record.getResumeMetadata() != null
                    && endedHostSessionId.equals(
                            record.getResumeMetadata().getHostSessionId())) {
                store.clearCurrentSession(pc, record.getLocalSessionId(),
                        endedHostSessionId);
            }
        }
        catch (IllegalArgumentException ignored) {
            // Discovery can briefly surface an entry before either stable identity is populated.
        }
    }

    private ComputerObject findComputerByUuid(String uuid) {
        if (uuid == null) {
            return null;
        }
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);
            if (uuid.equals(computer.details.uuid)) {
                return computer;
            }
        }
        return null;
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        pcListView = listView;
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                performPrimaryComputerAction((ComputerObject) pcGridAdapter.getItem(pos), arg1);
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
        updateMachinePresentation();
    }

    private void updateMachinePresentation() {
        boolean singleMachine = pcGridAdapter != null
                && pcGridAdapter.isSingleMachinePresentation();
        if (machineSectionTitle != null) {
            machineSectionTitle.setText(singleMachine
                    ? R.string.xr_home_your_computer : R.string.xr_bar_machines);
        }
        if (!(pcListView instanceof GridView)) {
            return;
        }

        GridView grid = (GridView) pcListView;
        ViewGroup.LayoutParams currentParams = grid.getLayoutParams();
        FrameLayout.LayoutParams params = currentParams instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) currentParams
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = singleMachine
                ? getResources().getDimensionPixelSize(R.dimen.xr_pc_hero_grid_height)
                : ViewGroup.LayoutParams.MATCH_PARENT;
        params.gravity = singleMachine ? Gravity.CENTER : Gravity.TOP;
        grid.setLayoutParams(params);
        grid.setNumColumns(singleMachine ? 1 : GridView.AUTO_FIT);
        grid.setColumnWidth(getResources().getDimensionPixelSize(singleMachine
                ? R.dimen.xr_pc_hero_card_width : R.dimen.xr_pc_grid_card_width));
        grid.setStretchMode(singleMachine ? GridView.NO_STRETCH : GridView.STRETCH_COLUMN_WIDTH);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        grid.requestLayout();
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
        public String guessManagementUrl() {
            if (details.activeAddress == null) return null;
            return "https://" + details.activeAddress.address + ":" + (details.guessExternalPort() + 1);
        }
    }
}
