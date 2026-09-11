package com.limelight;

import com.limelight.ui.PresentationMode;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.nvstream.HostSessionLaunchRequest;

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
    public void reconnectPreservesEstablishedExactResumeAndViewMetadata() {
        Context context = ApplicationProvider.getApplicationContext();
        HostSessionLaunchRequest established = HostSessionLaunchRequest.resume(7, "app-7", true, "1234");
        Intent launched = new Intent(context, Game.class)
                .putExtra(Game.EXTRA_LAUNCH_REQUEST, established)
                .putExtra(Game.EXTRA_PC_UUID, "pc-1")
                .putExtra(Game.EXTRA_APP_UUID, "app-7")
                .putExtra(Game.EXTRA_APP_ID, 7)
                .putExtra(Game.EXTRA_VDISPLAY, true)
                .putExtra(Game.EXTRA_SERVER_CERT, new byte[] {1, 2, 3});
        Intent reconnect = Game.createXrReconnectIntent(context, launched);
        HostSessionLaunchRequest request = Game.getHostSessionLaunchRequest(reconnect);
        assertEquals(HostSessionLaunchRequest.Kind.RESUME, request.kind);
        assertEquals("1234", request.expectedToken);
        assertEquals("app-7", request.expectedAppUuid);
        assertTrue(request.tokenSupported);
        assertEquals(Game.class.getName(), reconnect.getComponent().getClassName());
        assertEquals("pc-1", reconnect.getStringExtra(Game.EXTRA_PC_UUID));
        assertEquals(7, reconnect.getIntExtra(Game.EXTRA_APP_ID, 0));
        assertTrue(reconnect.getBooleanExtra(Game.EXTRA_VDISPLAY, false));
        assertArrayEquals(new byte[] {1, 2, 3}, reconnect.getByteArrayExtra(Game.EXTRA_SERVER_CERT));
    }

    @Test
    public void repeatedLegacyReconnectKeepsTokenlessResume() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent first = new Intent(context, Game.class).putExtra(Game.EXTRA_LAUNCH_REQUEST,
                HostSessionLaunchRequest.resume(7, null, false, null));
        Intent second = Game.createXrReconnectIntent(context, Game.createXrReconnectIntent(context, first));
        HostSessionLaunchRequest request = Game.getHostSessionLaunchRequest(second);
        assertEquals(HostSessionLaunchRequest.Kind.RESUME, request.kind);
        assertEquals(7, request.expectedAppId);
        assertFalse(request.tokenSupported);
        assertEquals(null, request.expectedToken);
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
                PresentationMode.HOST_SBS_AI));
        assertFalse(Game.hostCapabilityRequiresNormalReconnect(false,
                PresentationMode.NORMAL));
        assertFalse(Game.hostCapabilityRequiresNormalReconnect(false,
                PresentationMode.CLIENT_SBS_AI));
        assertFalse(Game.hostCapabilityRequiresNormalReconnect(true,
                PresentationMode.HOST_SBS_AI));
    }

    @Test
    public void rawSbsNegotiatesDoubleWidthFromLogicalPerEyeQuality() {
        assertArrayEquals(new int[] {7680, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        PresentationMode.HOST_SBS_RAW));
        assertArrayEquals(new int[] {7680, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        PresentationMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RawSbsPerEyeResolution.FULL));
    }

    @Test
    public void rawSbsHalfKeepsLogicalTransportWidth() {
        assertArrayEquals(new int[] {3840, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        PresentationMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF));
    }

    @Test
    public void nonRawModesKeepTheirLogicalTransportDimensions() {
        assertArrayEquals(new int[] {3840, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        PresentationMode.NORMAL));
        assertArrayEquals(new int[] {3840, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        PresentationMode.HOST_SBS_AI,
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
