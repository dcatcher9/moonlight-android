package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.limelight.BuildConfig;
import com.limelight.DebugInfoActivity;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.utils.PerformanceDataTracker;
import com.limelight.utils.UiHelper;


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

        TextView attribution = findViewById(R.id.settingsAttribution);
        if (attribution != null) {
            attribution.setText(getString(R.string.xr_settings_attribution,
                    getString(R.string.app_label), BuildConfig.VERSION_NAME,
                    getString(R.string.xr_app_author)));
        }
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

            // Bitrate is purely user-set: resolution/FPS changes deliberately leave it
            // untouched. getDefaultBitrate() remains only the fresh-install default.
            configureDiagnostics();
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


            Preference debugInfo = findPreference("pref_debug_info");
            if (debugInfo != null) {
                debugInfo.setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(requireContext(), DebugInfoActivity.class));
                    return true;
                });
            }
        }

    }
}
