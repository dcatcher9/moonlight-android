package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

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
public class StartupTest {
    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void applicationStartsWithoutProfilesSubsystem() {
        ArtemisApplication application =
                (ArtemisApplication) ApplicationProvider.getApplicationContext();
        application.onCreate();
        assertNotNull(application);
    }

    @Test
    public void pcHomeStarts() {
        PcView activity = Robolectric.buildActivity(PcView.class).create().get();
        assertNotNull(activity);
        assertFalse(activity.isFinishing());
    }

    @Test
    public void appLibraryStartsForPc() {
        Intent intent = new Intent()
                .putExtra(AppView.NAME_EXTRA, "Test PC")
                .putExtra(AppView.UUID_EXTRA, "test-pc");
        AppView activity = Robolectric.buildActivity(AppView.class, intent).create().get();
        assertNotNull(activity);
        assertFalse(activity.isFinishing());
    }
}
