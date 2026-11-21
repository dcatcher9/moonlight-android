package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.PcView;
import com.limelight.TestLogSuppressor;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.time.Duration;

/** Confirming a pairing deep link must keep the activity alive until the add has completed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class AddComputerManuallyDeepLinkTest {
    private ComputerManagerService.ComputerManagerBinder binder;
    private ActivityController<AddComputerManually> controller;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() throws InterruptedException {
        Application application = ApplicationProvider.getApplicationContext();
        binder = mock(ComputerManagerService.ComputerManagerBinder.class);
        when(binder.addComputerBlocking(any())).thenReturn(true);
        shadowOf(application).setComponentNameAndServiceForBindService(
                new ComponentName(application, ComputerManagerService.class), binder);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void proceedAddsThePcBeforeLeavingAndStartsPairing() throws InterruptedException {
        Intent link = new Intent(Intent.ACTION_VIEW,
                Uri.parse("art://192.0.2.10:47990?pin=1234&passphrase=test-phrase&name=Desk"));
        controller = Robolectric.buildActivity(AddComputerManually.class, link).setup();
        AddComputerManually activity = controller.get();
        shadowOf(Looper.getMainLooper()).idle();

        AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(confirmation);
        assertTrue(confirmation.isShowing());
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        // Finishing here would let onDestroy interrupt the queued add.
        assertFalse(activity.isFinishing());

        ArgumentCaptor<ComputerDetails> added = ArgumentCaptor.forClass(ComputerDetails.class);
        verify(binder, timeout(5000)).addComputerBlocking(added.capture());
        assertEquals("192.0.2.10", added.getValue().manualAddress.address);
        assertEquals(47990, added.getValue().manualAddress.port);

        Intent pairing = awaitNextStartedActivity(activity);
        assertEquals(PcView.class.getName(), pairing.getComponent().getClassName());
        assertEquals("192.0.2.10", pairing.getStringExtra("hostname"));
        assertEquals(47990, pairing.getIntExtra("port", 0));
        assertEquals("1234", pairing.getStringExtra("pin"));
        assertEquals("test-phrase", pairing.getStringExtra("passphrase"));

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650));
        assertTrue(activity.isFinishing());
    }

    @Test
    public void cancelKeepsTheActivityWithoutAddingAnything() throws InterruptedException {
        Intent link = new Intent(Intent.ACTION_VIEW,
                Uri.parse("art://192.0.2.10:47990?pin=1234&passphrase=test-phrase"));
        controller = Robolectric.buildActivity(AddComputerManually.class, link).setup();
        shadowOf(Looper.getMainLooper()).idle();

        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(controller.get().isFinishing());
        verify(binder, org.mockito.Mockito.after(200).never()).addComputerBlocking(any());
    }

    private static Intent awaitNextStartedActivity(AddComputerManually activity)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Intent started = shadowOf(activity).getNextStartedActivity();
            if (started != null) {
                return started;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("The pairing hand-off to PcView was never started");
    }
}
