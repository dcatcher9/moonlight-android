package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.limelight.DebugInfoActivity;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.utils.PerformanceDataTracker;
import com.limelight.utils.UiHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * XR-first editor for global defaults.
 *
 * Active PC sessions are deliberately edited from the in-stream settings panel. This activity
 * only writes the defaults used when a PC starts a new session.
 */
public class StreamSettings extends AppCompatActivity {
    private static final String STATE_SECTION = "selected_section";

    private static final String[] SECTION_KEYS = {
            "category_video_settings",
            "category_xr_audio_input",
            "category_xr_general",
            "category_xr_diagnostics"
    };

    private static final int[] SECTION_BUTTON_IDS = {
            R.id.settingsStreaming,
            R.id.settingsAudioInput,
            R.id.settingsGeneral,
            R.id.settingsDiagnostics
    };

    private PreferenceConfiguration previousPrefs;
    private int selectedSection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);
        previousPrefs = PreferenceConfiguration.readPreferences(this);
        setContentView(R.layout.activity_stream_settings);

        findViewById(R.id.settingsBackFab).setOnClickListener(v -> finish());
        for (int i = 0; i < SECTION_BUTTON_IDS.length; i++) {
            final int section = i;
            findViewById(SECTION_BUTTON_IDS[i]).setOnClickListener(v -> showSection(section));
        }

        selectedSection = savedInstanceState == null ? 0 :
                savedInstanceState.getInt(STATE_SECTION, 0);
        showSection(selectedSection);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_SECTION, selectedSection);
        super.onSaveInstanceState(outState);
    }

    private void showSection(int section) {
        selectedSection = Math.max(0, Math.min(section, SECTION_KEYS.length - 1));

        for (int i = 0; i < SECTION_BUTTON_IDS.length; i++) {
            Button button = findViewById(SECTION_BUTTON_IDS[i]);
            boolean active = i == selectedSection;
            button.setActivated(active);
            button.setSelected(active);
        }

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container,
                        SettingsFragment.newInstance(SECTION_KEYS[selectedSection]))
                .commit();
    }

    @Override
    public void finish() {
        PreferenceConfiguration newPrefs = PreferenceConfiguration.readPreferences(this);
        boolean localeChanged = previousPrefs != null &&
                !newPrefs.language.equals(previousPrefs.language);
        super.finish();

        if (localeChanged && android.os.Build.VERSION.SDK_INT <
                android.os.Build.VERSION_CODES.TIRAMISU) {
            Intent intent = new Intent(this, PcView.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final String ARG_SECTION = "section";

        static SettingsFragment newInstance(String section) {
            SettingsFragment fragment = new SettingsFragment();
            Bundle args = new Bundle();
            args.putString(ARG_SECTION, section);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            String visibleSection = requireArguments().getString(ARG_SECTION,
                    SECTION_KEYS[0]);
            PreferenceScreen screen = getPreferenceScreen();
            for (String sectionKey : SECTION_KEYS) {
                PreferenceCategory category = findPreference(sectionKey);
                if (category != null && !sectionKey.equals(visibleSection)) {
                    screen.removePreference(category);
                }
            }

            configureStreamingDefaults();
            configureDiagnostics();
        }

        private SharedPreferences getPrefs() {
            return PreferenceManager.getDefaultSharedPreferences(requireContext());
        }

        private void configureStreamingDefaults() {
            Preference resolution = findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);
            if (resolution != null) {
                resolution.setOnPreferenceChangeListener((preference, newValue) -> {
                    resetBitrate((String) newValue, null);
                    return true;
                });
            }

            Preference fps = findPreference(PreferenceConfiguration.FPS_PREF_STRING);
            if (fps != null) {
                fps.setOnPreferenceChangeListener((preference, newValue) -> {
                    resetBitrate(null, (String) newValue);
                    return true;
                });
            }
        }

        private void resetBitrate(String resolution, String fps) {
            SharedPreferences prefs = getPrefs();
            if (resolution == null) {
                resolution = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_RESOLUTION);
            }
            if (fps == null) {
                fps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_FPS);
            }
            int defaultBitrate = PreferenceConfiguration.getDefaultBitrate(resolution, fps);
            SeekBarPreference bitrate = findPreference(
                    PreferenceConfiguration.BITRATE_PREF_STRING);
            if (bitrate != null) {
                // Keep the in-row value synchronized with the automatic resolution/FPS reset.
                bitrate.setProgress(defaultBitrate);
            }
            else {
                prefs.edit().putInt(PreferenceConfiguration.BITRATE_PREF_STRING,
                        defaultBitrate).apply();
            }
        }

        private void configureDiagnostics() {
            Preference logging = findPreference("checkbox_enable_perf_logging");
            if (logging != null) {
                logging.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (!((Boolean) newValue)) {
                        new PerformanceDataTracker().clearLogs(preference.getContext());
                    }
                    return true;
                });
            }

            Preference shareLogs = findPreference("share_performance_logs");
            if (shareLogs != null) {
                shareLogs.setOnPreferenceClickListener(preference -> {
                    sharePerformanceLogs(preference.getContext());
                    return true;
                });
            }

            Preference debugInfo = findPreference("pref_debug_info");
            if (debugInfo != null) {
                debugInfo.setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(requireContext(), DebugInfoActivity.class));
                    return true;
                });
            }
        }

        private void sharePerformanceLogs(Context context) {
            String logs = new PerformanceDataTracker().getLog(context);
            if (logs == null || logs.trim().isEmpty()) {
                Toast.makeText(context, R.string.toast_no_logs, Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                File logFile = new File(context.getCacheDir(), "artemis-performance-logs.txt");
                try (FileOutputStream output = new FileOutputStream(logFile)) {
                    output.write(logs.getBytes(StandardCharsets.UTF_8));
                }

                Uri uri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".fileprovider", logFile);
                Intent intent = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_EMAIL,
                                new String[] {context.getString(R.string.email_recipient)})
                        .putExtra(Intent.EXTRA_SUBJECT,
                                context.getString(R.string.email_subject))
                        .putExtra(Intent.EXTRA_TEXT,
                                context.getString(R.string.email_prefix_message))
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent,
                        context.getString(R.string.email_chooser_title)));
            }
            catch (IOException e) {
                Log.w("StreamSettings", "Unable to create performance log attachment", e);
                Toast.makeText(context, R.string.pref_error_occurred, Toast.LENGTH_SHORT).show();
            }
            catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(context, R.string.toast_no_email_clients, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
