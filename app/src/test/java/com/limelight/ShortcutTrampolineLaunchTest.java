package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Intent;

import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class ShortcutTrampolineLaunchTest {
    @Test public void differentAppOnAHostWithoutAppUuidsNeedsConfirmation() {
        // Standard Sunshine reports no app UUIDs; a shortcut by app ID has none either.
        ComputerDetails host = host(5, null);

        assertTrue(ShortcutTrampoline.requiresReplaceConfirmation(host,
                new NvApp("Other", null, 7, false)));
    }

    @Test public void idleHostOrTheSameAppStartsWithoutConfirmation() {
        assertFalse(ShortcutTrampoline.requiresReplaceConfirmation(host(0, null),
                new NvApp("Game", null, 7, false)));
        assertFalse(ShortcutTrampoline.requiresReplaceConfirmation(host(7, null),
                new NvApp("Game", null, 7, false)));
        assertFalse(ShortcutTrampoline.requiresReplaceConfirmation(host(3, "APP-UUID"),
                new NvApp("Game", "app-uuid", 0, false)));
    }

    @Test public void confirmedQuitAndStartCarriesReplaceAuthorityForTheObservedSession() {
        ComputerDetails host = host(5, "running-uuid");
        host.hostSessionIdSupported = true;
        host.hostSessionId = "token-5";
        host.activeAddress = new ComputerDetails.AddressTuple("192.0.2.1", 47989);
        ComputerManagerService.ComputerManagerBinder binder =
                mock(ComputerManagerService.ComputerManagerBinder.class);
        when(binder.getUniqueId()).thenReturn("client-id");
        Activity parent = Robolectric.buildActivity(Activity.class).create().get();

        Intent intent = ShortcutTrampoline.createConfirmedReplaceIntent(parent,
                new NvApp("Next", "next-uuid", 7, false), host, binder, false);

        HostSessionLaunchRequest request = Game.getHostSessionLaunchRequest(intent);
        assertEquals(HostSessionLaunchRequest.Kind.REPLACE, request.kind);
        assertEquals(5, request.expectedAppId);
        assertEquals("running-uuid", request.expectedAppUuid);
        assertEquals("token-5", request.expectedToken);
        assertEquals(7, intent.getIntExtra(Game.EXTRA_APP_ID, 0));
    }

    private static ComputerDetails host(int runningId, String runningUuid) {
        ComputerDetails details = new ComputerDetails();
        details.runningGameId = runningId;
        details.runningGameUUID = runningUuid;
        return details;
    }
}
