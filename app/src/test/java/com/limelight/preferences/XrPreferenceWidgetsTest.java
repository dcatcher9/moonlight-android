package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.AppCompatButton;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.ui.xrcontrols.XrResolutionSelector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrPreferenceWidgetsTest {
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        Context application = ApplicationProvider.getApplicationContext();
        context = new ContextThemeWrapper(application, R.style.AppTheme);
        preferences = PreferenceManager.getDefaultSharedPreferences(application);
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void choiceGroupUsesAppCompatSegmentsAndFallsBackVertically() {
        XrChoiceGroup group = new XrChoiceGroup(context);
        group.setChoices(new CharSequence[] {"One", "Two", "Three"},
                new CharSequence[] {"1", "2", "3"}, "1", null, value -> true);

        int buttonWidth = dp(110);
        for (int i = 0; i < group.getChildCount(); i++) {
            assertTrue(group.getChildAt(i) instanceof AppCompatButton);
            AppCompatButton button = group.getButtonAt(i);
            assertTrue(button.getMinimumWidth() >= dp(76));
            assertTrue(button.getMinimumHeight() >= dp(XrSegmentButton.MIN_HEIGHT_DP));
            assertEquals(23f * context.getResources().getDisplayMetrics().scaledDensity,
                    button.getTextSize(), 0.5f);
            group.getChildAt(i).getLayoutParams().width = buttonWidth;
        }

        int groupWidth = dp(240);
        group.measure(View.MeasureSpec.makeMeasureSpec(groupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        group.layout(0, 0, group.getMeasuredWidth(), group.getMeasuredHeight());

        assertTrue(group.isStackedForTests());
        assertTrue(group.getButtonAt(1).getTop() > group.getButtonAt(0).getTop());
        assertTrue(group.getButtonAt(2).getTop() > group.getButtonAt(1).getTop());
        assertEquals(group.getButtonAt(0).getLeft(), group.getButtonAt(1).getLeft());
        assertEquals(group.getButtonAt(0).getRight(), group.getButtonAt(2).getRight());
    }

    @Test
    public void choiceGroupKeepsSelectionWhenListenerRejectsChange() {
        XrChoiceGroup group = new XrChoiceGroup(context);
        group.setChoices(new CharSequence[] {"One", "Two"},
                new CharSequence[] {"1", "2"}, "1", null, value -> false);

        assertTrue(group.getButtonAt(0).isActivated());
        group.getButtonAt(1).performClick();

        assertEquals("1", group.getSelectedValue());
        assertTrue(group.getButtonAt(0).isActivated());
        assertFalse(group.getButtonAt(1).isActivated());
    }

    @Test
    public void programmaticSelectionPreservesButtonsAndFocusTargets() {
        XrChoiceGroup group = new XrChoiceGroup(context);
        group.setChoices(new CharSequence[] {"One", "Two"},
                new CharSequence[] {"1", "2"}, "1", null, value -> true);
        AppCompatButton first = group.getButtonAt(0);
        AppCompatButton second = group.getButtonAt(1);

        assertTrue(group.setSelectedValue("2"));

        assertSame(first, group.getButtonAt(0));
        assertSame(second, group.getButtonAt(1));
        assertFalse(first.isActivated());
        assertTrue(second.isActivated());
    }

    @Test
    public void inlineListShowsUnknownValueUntilKnownChoiceIsExplicitlySelected() {
        InlineListPreference preference = new InlineListPreference(context);
        preference.setEntries(new CharSequence[] {"720p", "1080p"});
        preference.setEntryValues(new CharSequence[] {"1280x720", "1920x1080"});
        preference.setValue("3440x1440");

        XrChoiceGroup group = bindChoices(preference);
        assertEquals(3, group.getChildCount());
        assertEquals("3440x1440", group.getButtonAt(0).getTag());
        assertTrue(group.getButtonAt(0).isActivated());
        assertEquals("3440x1440", preference.getValue());

        group.getButtonAt(2).performClick();
        assertEquals("1920x1080", preference.getValue());
        assertTrue(group.getButtonAt(2).isActivated());

        XrChoiceGroup rebound = bindChoices(preference);
        assertEquals(2, rebound.getChildCount());
        assertEquals("1920x1080", rebound.getSelectedValue());
    }

    @Test
    public void inlineSeekBarFormatsValueAndPersistsHardwareKeySteps() {
        AttributeSet attrs = Robolectric.buildAttributeSet()
                .addAttribute(android.R.attr.defaultValue, "500")
                .addAttribute(android.R.attr.max, "300000")
                .addAttribute(android.R.attr.text, "Mbps")
                .addAttribute(R.attr.xrMin, "500")
                .addAttribute(R.attr.xrStep, "500")
                .addAttribute(R.attr.xrKeyStep, "1000")
                .addAttribute(R.attr.xrDivisor, "1000")
                .build();
        SeekBarPreference preference = new SeekBarPreference(context, attrs);
        preference.setProgress(10500);

        View row = LayoutInflater.from(context).inflate(
                R.layout.preference_xr_inline_seekbar, null, false);
        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row));
        TextView value = row.findViewById(R.id.xr_seekbar_value);
        SeekBar seekBar = row.findViewById(R.id.xr_seekbar);
        assertEquals("10.5 Mbps", value.getText().toString());

        seekBar.requestFocus();
        assertTrue(seekBar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));

        assertEquals(11500, preference.getProgress());
        assertEquals("11.5 Mbps", value.getText().toString());
    }

    @Test
    public void settingsXmlUsesInlineChoicesSwitchesAndSynchronizesBitrateReset() {
        StreamSettings activity = Robolectric.buildActivity(StreamSettings.class)
                .create().start().resume().visible().get();
        activity.getSupportFragmentManager().executePendingTransactions();
        StreamSettings.SettingsFragment fragment = (StreamSettings.SettingsFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.settings_container);

        InlineListPreference resolution = fragment.findPreference(
                PreferenceConfiguration.RESOLUTION_PREF_STRING);
        SeekBarPreference bitrate = fragment.findPreference(
                PreferenceConfiguration.BITRATE_PREF_STRING);
        assertTrue(fragment.findPreference(PreferenceConfiguration.ENABLE_HDR_PREF_STRING)
                instanceof SwitchPreferenceCompat);
        assertTrue(fragment.findPreference(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING)
                instanceof InlineListPreference);
        assertNull(fragment.findPreference("category_xr_3d_settings"));

        ViewGroup navigation = (ViewGroup) activity.findViewById(
                R.id.settingsStreaming).getParent();
        assertEquals(4, navigation.getChildCount());
        assertSame(activity.findViewById(R.id.settingsStreaming), navigation.getChildAt(0));
        assertSame(activity.findViewById(R.id.settingsAudioInput), navigation.getChildAt(1));
        assertSame(activity.findViewById(R.id.settingsGeneral), navigation.getChildAt(2));
        assertSame(activity.findViewById(R.id.settingsDiagnostics), navigation.getChildAt(3));

        XrResolutionSelector choices = bindResolutions(resolution);
        assertEquals(View.GONE, ((View) choices.getParent()).findViewById(
                R.id.xr_choice_group).getVisibility());
        AppCompatButton fullHd = choices.findCardByResolutionId("1920x1080");
        fullHd.performClick();

        int expected = PreferenceConfiguration.getDefaultBitrate("1920x1080", "60");
        assertEquals(expected, bitrate.getProgress());
        assertEquals(expected, preferences.getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING, -1));

        activity.findViewById(R.id.settingsAudioInput).performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        fragment = (StreamSettings.SettingsFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.settings_container);
        assertTrue(fragment.findPreference(PreferenceConfiguration.AUDIO_CONFIG_PREF_STRING)
                instanceof InlineListPreference);

        activity.findViewById(R.id.settingsGeneral).performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        fragment = (StreamSettings.SettingsFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.settings_container);
        assertTrue(fragment.findPreference("list_languages") instanceof InlineListPreference);

        activity.findViewById(R.id.settingsDiagnostics).performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        fragment = (StreamSettings.SettingsFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.settings_container);
        assertTrue(fragment.findPreference(
                PreferenceConfiguration.ENABLE_PERF_LOGGING_PREF_STRING)
                instanceof SwitchPreferenceCompat);
    }

    private XrChoiceGroup bindChoices(InlineListPreference preference) {
        View row = LayoutInflater.from(context).inflate(
                R.layout.preference_xr_inline_list, null, false);
        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row));
        return row.findViewById(R.id.xr_choice_group);
    }

    private XrResolutionSelector bindResolutions(InlineListPreference preference) {
        View row = LayoutInflater.from(context).inflate(
                R.layout.preference_xr_inline_list, null, false);
        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row));
        XrResolutionSelector selector = row.findViewById(R.id.xr_resolution_selector);
        assertEquals(View.VISIBLE, selector.getVisibility());
        return selector;
    }

    private static AppCompatButton findChoice(XrChoiceGroup group, String value) {
        for (int i = 0; i < group.getChildCount(); i++) {
            AppCompatButton button = group.getButtonAt(i);
            if (value.equals(button.getTag())) {
                return button;
            }
        }
        throw new AssertionError("Choice not found: " + value);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
