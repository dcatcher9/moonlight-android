package com.limelight.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import androidx.activity.ComponentActivity;
import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class XrEyeTrackingPermissionTest {
    private ActivityController<ComponentActivity> controller;
    private ComponentActivity activity;
    private XrEyeTrackingPermission permission;

    @Before
    public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().clear().commit();
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .setSystemFeature(XrEyeTrackingPermission.FEATURE, true);
        createHome();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    @Test
    public void setupRequestsEyePermissionOnceAcrossResumesAndFutureAppLaunches() {
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
        permission.requestOnce();
        ShadowActivity.PermissionsRequest request =
                Shadows.shadowOf(activity).getLastRequestedPermission();
        assertArrayEquals(new String[] {XrEyeTrackingPermission.PERMISSION},
                request.requestedPermissions);

        permission.requestOnce();
        controller.pause().stop();
        controller.restart().start().resume();
        permission.requestOnce();
        assertSame(request, Shadows.shadowOf(activity).getLastRequestedPermission());

        // A declined request is still handled; reopening the app must not repeat the prompt.
        controller.pause().stop().destroy();
        createHome();
        permission.requestOnce();
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
    }

    @Test
    public void anExistingGrantNeverPromptsAndARevocationDoesNotTriggerAnotherSetupRequest() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(XrEyeTrackingPermission.PERMISSION);
        permission.requestOnce();
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());

        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(XrEyeTrackingPermission.PERMISSION);
        controller.pause().stop().destroy();
        createHome();
        permission.requestOnce();
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
    }

    @Test
    public void devicesWithoutEyeInputDoNotPromptOrConsumeTheSetupAttempt() {
        Shadows.shadowOf(activity.getPackageManager())
                .setSystemFeature(XrEyeTrackingPermission.FEATURE, false);
        permission.requestOnce();
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());

        Shadows.shadowOf(activity.getPackageManager())
                .setSystemFeature(XrEyeTrackingPermission.FEATURE, true);
        permission.requestOnce();
        assertArrayEquals(new String[] {XrEyeTrackingPermission.PERMISSION},
                Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
    }

    private void createHome() {
        controller = Robolectric.buildActivity(ComponentActivity.class).create();
        activity = controller.get();
        permission = new XrEyeTrackingPermission(activity);
        controller.start().resume();
    }
}
