package com.limelight;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.UiHelper;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@RunWith(RobolectricTestRunner.class)
public class StartupCrashTest {
    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void nativeShadowAllowsHomeInitialization() {
        assertNotNull(Robolectric.buildActivity(PcView.class).create().get());
    }

    @Test
    public void globalPreferencesRemainReadable() {
        Context context = ApplicationProvider.getApplicationContext();
        assertNotNull(PreferenceConfiguration.readPreferences(context));
    }

    @Test
    public void localeInitializationDoesNotCrashHome() {
        PcView activity = Robolectric.buildActivity(PcView.class).create().get();
        UiHelper.setLocale(activity);
        assertNotNull(activity);
    }

    @Test
    public void appLibraryHandlesMissingIdentity() {
        assertNotNull(Robolectric.buildActivity(AppView.class, new Intent()).create().get());
    }
}
