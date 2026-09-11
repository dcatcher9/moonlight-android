package com.limelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.utils.ServerHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GameReconnectLifecycleTest {
    @Test
    public void reconnectAfterReplacingAppConsumesOnlyLaunchGuards() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent launched = new Intent(context, Game.class)
                .putExtra(Game.EXTRA_REQUIRE_HOST_IDLE, true)
                .putExtra(Game.EXTRA_XR_STARTUP_MODE_OVERRIDE, "NORMAL")
                .putExtra(Game.EXTRA_RESUME_EXISTING_SESSION, false)
                .putExtra(Game.EXTRA_PC_UUID, "pc-1")
                .putExtra(Game.EXTRA_APP_UUID, "app-7")
                .putExtra(Game.EXTRA_APP_ID, 7)
                .putExtra(Game.EXTRA_HOST_SESSION_ID, "1234")
                .putExtra(ServerHelper.EXTRA_HOST_SESSION_ID_SUPPORTED, true)
                .putExtra(Game.EXTRA_VDISPLAY, true)
                .putExtra(Game.EXTRA_SERVER_CERT, new byte[] {1, 2, 3});

        Intent reconnect = Game.createXrReconnectIntent(context, launched);

        assertFalse(reconnect.hasExtra(Game.EXTRA_REQUIRE_HOST_IDLE));
        assertFalse(reconnect.hasExtra(Game.EXTRA_XR_STARTUP_MODE_OVERRIDE));
        assertTrue(reconnect.getBooleanExtra(Game.EXTRA_RESUME_EXISTING_SESSION, false));
        assertEquals(Game.class.getName(), reconnect.getComponent().getClassName());
        assertEquals("pc-1", reconnect.getStringExtra(Game.EXTRA_PC_UUID));
        assertEquals("app-7", reconnect.getStringExtra(Game.EXTRA_APP_UUID));
        assertEquals(7, reconnect.getIntExtra(Game.EXTRA_APP_ID, 0));
        assertEquals("1234", reconnect.getStringExtra(Game.EXTRA_HOST_SESSION_ID));
        assertTrue(reconnect.getBooleanExtra(ServerHelper.EXTRA_HOST_SESSION_ID_SUPPORTED, false));
        assertTrue(reconnect.getBooleanExtra(Game.EXTRA_VDISPLAY, false));
        assertArrayEquals(new byte[] {1, 2, 3}, reconnect.getByteArrayExtra(Game.EXTRA_SERVER_CERT));
        // Building a resume must not weaken the guard on the original replacement launch.
        assertTrue(launched.getBooleanExtra(Game.EXTRA_REQUIRE_HOST_IDLE, false));
        assertFalse(launched.getBooleanExtra(Game.EXTRA_RESUME_EXISTING_SESSION, true));
    }

    @Test
    public void repeatedLegacyReconnectKeepsTokenlessResume() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent first = Game.createXrReconnectIntent(context,
                new Intent(context, Game.class).putExtra(Game.EXTRA_APP_ID, 7));
        Intent second = Game.createXrReconnectIntent(context, first);

        assertTrue(second.getBooleanExtra(Game.EXTRA_RESUME_EXISTING_SESSION, false));
        assertFalse(second.getBooleanExtra(Game.EXTRA_REQUIRE_HOST_IDLE, false));
        assertFalse(second.hasExtra(Game.EXTRA_HOST_SESSION_ID));
        assertFalse(second.getBooleanExtra(ServerHelper.EXTRA_HOST_SESSION_ID_SUPPORTED, false));
        assertEquals(7, second.getIntExtra(Game.EXTRA_APP_ID, 0));
    }

    @Test
    public void stopConnectionClosesHostTelemetryBeforeNativeTeardown() throws Exception {
        File file = new File("src/main/java/com/limelight/Game.java");
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int methodStart = source.indexOf("private void stopConnection()");
        int methodEnd = source.indexOf("public void runAfterConnectionStop", methodStart);
        assertTrue("stopConnection source contract is missing", methodStart >= 0 && methodEnd > 0);

        String method = source.substring(methodStart, methodEnd);
        int preStopHook = method.indexOf("presenter.onConnectionStopping()");
        int nativeStop = method.indexOf("conn.stop(heldSessionTransaction -> {");
        assertTrue("Host telemetry must close before NvConnection destroys native transport",
                preStopHook >= 0 && nativeStop > preStopHook);
    }

    @Test
    public void ordinaryStopFinalizesStreamingActivity() {
        assertTrue(Game.shouldFinalizeStreamOnStop(false));
    }

    @Test
    public void applyReconnectStopPreservesRecreatedStreamingActivity() {
        assertFalse(Game.shouldFinalizeStreamOnStop(true));
    }

    @Test
    public void authoritativeStandardHostDowngradeReconnectsHostAiAsNormal() {
        assertTrue(Game.hostCapabilityRequiresNormalReconnect(false,
                SessionSettingsStore.PresenterMode.HOST_SBS_AI));
        assertFalse(Game.hostCapabilityRequiresNormalReconnect(false,
                SessionSettingsStore.PresenterMode.NORMAL));
        assertFalse(Game.hostCapabilityRequiresNormalReconnect(false,
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI));
        assertFalse(Game.hostCapabilityRequiresNormalReconnect(true,
                SessionSettingsStore.PresenterMode.HOST_SBS_AI));
    }

    @Test
    public void rawSbsNegotiatesDoubleWidthFromLogicalPerEyeQuality() {
        assertArrayEquals(new int[] {7680, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        SessionSettingsStore.PresenterMode.HOST_SBS_RAW));
        assertArrayEquals(new int[] {7680, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RawSbsPerEyeResolution.FULL));
    }

    @Test
    public void rawSbsHalfKeepsLogicalTransportWidth() {
        assertArrayEquals(new int[] {3840, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF));
    }

    @Test
    public void nonRawModesKeepTheirLogicalTransportDimensions() {
        assertArrayEquals(new int[] {3840, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        SessionSettingsStore.PresenterMode.NORMAL));
        assertArrayEquals(new int[] {3840, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF));
    }

    @Test
    public void rawRequiresRequestedOrApolloSyntheticVirtualDisplay() {
        assertTrue(Game.rawSbsHasVirtualDisplayBacking(true, "Desktop", null));
        assertTrue(Game.rawSbsHasVirtualDisplayBacking(
                false, "Virtual Display", null));
        assertTrue(Game.rawSbsHasVirtualDisplayBacking(
                false,
                "app",
                "8902cb19-674a-403d-a587-41b092e900ba"));
        assertFalse(Game.rawSbsHasVirtualDisplayBacking(
                false, "Desktop", "not-the-virtual-display"));
        assertFalse(Game.rawSbsHasVirtualDisplayBacking(false, null, null));
    }
}
