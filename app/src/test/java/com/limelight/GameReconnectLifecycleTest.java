package com.limelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore;

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
