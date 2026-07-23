package com.limelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.preferences.session.SessionSettingsStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GameReconnectLifecycleTest {
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
    }

    @Test
    public void nonRawModesKeepTheirLogicalTransportDimensions() {
        assertArrayEquals(new int[] {3840, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        SessionSettingsStore.PresenterMode.NORMAL));
        assertArrayEquals(new int[] {3840, 2160},
                Game.xrTransportDimensions(3840, 2160,
                        SessionSettingsStore.PresenterMode.HOST_SBS_AI));
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
