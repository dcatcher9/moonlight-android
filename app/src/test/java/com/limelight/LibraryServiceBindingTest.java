package com.limelight;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.GlPreferences;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The library screens unbind ComputerManagerService whenever they bound it, including when the
 * service never connected or the bind thread exited before publishing its binder.
 */
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@RunWith(RobolectricTestRunner.class)
public final class LibraryServiceBindingTest {
    static final List<ServiceConnection> bound = new ArrayList<>();
    static final List<ServiceConnection> unbound = new ArrayList<>();

    public static final class UnconnectedAppView extends AppView {
        @Override public boolean bindService(Intent service, ServiceConnection connection,
                                             int flags) {
            bound.add(connection);
            return true;
        }

        @Override public void unbindService(ServiceConnection connection) {
            unbound.add(connection);
        }
    }

    public static final class UnconnectedPcView extends PcView {
        @Override public boolean bindService(Intent service, ServiceConnection connection,
                                             int flags) {
            bound.add(connection);
            return true;
        }

        @Override public void unbindService(ServiceConnection connection) {
            unbound.add(connection);
        }
    }

    @BeforeClass public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test public void appLibraryDestroyedBeforeItsBinderArrivesStillUnbinds() {
        bound.clear();
        unbound.clear();
        Intent intent = new Intent()
                .putExtra(AppView.NAME_EXTRA, "Test PC")
                .putExtra(AppView.UUID_EXTRA, "test-pc");

        Robolectric.buildActivity(UnconnectedAppView.class, intent).create().destroy();

        assertEquals(1, bound.size());
        assertEquals(bound, unbound);
    }

    @Test public void pcHomeDestroyedBeforeItsBinderArrivesStillUnbinds() {
        bound.clear();
        unbound.clear();
        // A cached GL renderer lets onCreate() bind without a GL probe.
        Context context = ApplicationProvider.getApplicationContext();
        GlPreferences glPrefs = GlPreferences.readPreferences(context);
        glPrefs.glRenderer = "Test renderer";
        glPrefs.savedFingerprint = android.os.Build.FINGERPRINT;
        glPrefs.writePreferences();

        Robolectric.buildActivity(UnconnectedPcView.class).create().destroy();

        assertEquals(1, bound.size());
        assertEquals(bound, unbound);
    }
}
