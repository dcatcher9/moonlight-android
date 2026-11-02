package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Looper;

import androidx.activity.ComponentActivity;
import androidx.xr.arcore.ArDevice;
import androidx.xr.arcore.Eye;
import androidx.xr.arcore.PerceptionState;
import androidx.xr.arcore.PerceptionStateExt;
import androidx.xr.arcore.TrackingState;
import androidx.xr.runtime.Config;
import androidx.xr.runtime.CoreState;
import androidx.xr.runtime.DeviceTrackingMode;
import androidx.xr.runtime.EyeTrackingMode;
import androidx.xr.runtime.Session;
import androidx.xr.runtime.SessionConfigureSuccess;
import androidx.xr.runtime.XrDevice;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.PerceptionSpace;
import androidx.xr.scenecore.Scene;
import androidx.xr.scenecore.ScenePose;
import androidx.xr.scenecore.SessionExt;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import kotlinx.coroutines.flow.StateFlow;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class XrDockGazeTrackerTest {
    private ActivityController<ComponentActivity> controller;
    private ComponentActivity activity;
    private Session session;
    private PanelEntity panel;
    private XrDockGazeTracker tracker;
    private MockedStatic<XrDevice> devices;
    private final List<Boolean> gazeChanges = new ArrayList<>();

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(ComponentActivity.class).create();
        activity = controller.get();
        Shadows.shadowOf(activity.getPackageManager()).setSystemFeature(
                XrEyeTrackingPermission.FEATURE, true);
        XrDevice device = mock(XrDevice.class);
        when(device.isEyeTrackingModeSupported(EyeTrackingMode.FINE_TRACKING)).thenReturn(true);
        devices = mockStatic(XrDevice.class);
        devices.when(() -> XrDevice.getCurrentDevice(activity)).thenReturn(device);
        session = mock(Session.class);
        AtomicReference<Config> currentConfig = new AtomicReference<>(
                new Config.Builder().setDeviceTracking(DeviceTrackingMode.LAST_KNOWN).build());
        when(session.getConfig()).thenAnswer(ignored -> currentConfig.get());
        when(session.configure(any())).thenAnswer(invocation -> {
            currentConfig.set(invocation.getArgument(0));
            return new SessionConfigureSuccess();
        });
        panel = mock(PanelEntity.class);
        tracker = new XrDockGazeTracker(activity, session, panel, gazeChanges::add);
        controller.start().resume();
    }

    @After
    public void tearDown() {
        tracker.close();
        devices.close();
        controller.pause().stop().destroy();
    }

    @Test
    public void missingGrantNeverPromptsOrEnablesAcrossCollapseResume() {
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
        tracker.setActive(true);
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
        verify(session, never()).configure(any());

        tracker.setActive(false);
        tracker.setActive(true);
        controller.pause().stop();
        controller.restart().start().resume();
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
        verify(session, never()).configure(any());
    }

    @Test
    public void repeatedVisibilityRefreshesDoNotReconfigureAndOtherTrackingStaysIntact() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                XrEyeTrackingPermission.PERMISSION);
        tracker.setActive(true);
        tracker.setActive(true);
        verify(session, times(1)).configure(any());

        tracker.setActive(false);
        tracker.setActive(false);
        tracker.close();
        tracker.close();
        tracker.setActive(true);
        ArgumentCaptor<Config> configs = ArgumentCaptor.forClass(Config.class);
        verify(session, times(2)).configure(configs.capture());
        List<Config> transitions = configs.getAllValues();
        assertEquals(EyeTrackingMode.FINE_TRACKING, transitions.get(0).getEyeTracking());
        assertEquals(EyeTrackingMode.DISABLED, transitions.get(1).getEyeTracking());
        for (Config config : transitions) {
            assertEquals(DeviceTrackingMode.LAST_KNOWN, config.getDeviceTracking());
        }
    }

    @Test
    public void optionalTrackingConfigurationFailureDoesNotRetryWithEveryCollapse() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                XrEyeTrackingPermission.PERMISSION);
        doThrow(new IllegalStateException("Tracking unavailable")).when(session).configure(any());
        tracker.setActive(true);
        tracker.setActive(false);
        tracker.setActive(true);
        tracker.setActive(false);
        tracker.close();
        verify(session, times(1)).configure(any());
    }

    @Test
    public void activationBeforeStartedWaitsForLifecycleAndStopsSamplingInBackground() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                XrEyeTrackingPermission.PERMISSION);
        controller.pause().stop();
        tracker.setActive(true);
        verify(session, never()).configure(any());

        controller.restart().start().resume();
        verify(session, times(1)).configure(any());
        controller.pause().stop();
        verify(session, times(2)).configure(any());

        // The observer retains desired activation, even if the caller could not receive STARTED
        // yet, and repeated START/RESUME notifications still perform one configure per transition.
        controller.restart().start().resume();
        ArgumentCaptor<Config> configs = ArgumentCaptor.forClass(Config.class);
        verify(session, times(3)).configure(configs.capture());
        assertEquals(EyeTrackingMode.FINE_TRACKING, configs.getAllValues().get(0).getEyeTracking());
        assertEquals(EyeTrackingMode.DISABLED, configs.getAllValues().get(1).getEyeTracking());
        assertEquals(EyeTrackingMode.FINE_TRACKING, configs.getAllValues().get(2).getEyeTracking());
    }

    @Test
    public void briefTrackingLossKeepsExpansionButAValidLookAwayShrinksImmediately() {
        updateGaze(true, true);
        assertEquals(java.util.Arrays.asList(true), gazeChanges);
        ShadowSystemClock.advanceBy(Duration.ofMillis(100));
        updateGaze(false, false);
        assertEquals(java.util.Arrays.asList(true), gazeChanges);

        ShadowSystemClock.advanceBy(Duration.ofMillis(51));
        updateGaze(false, false);
        assertEquals(java.util.Arrays.asList(true, false), gazeChanges);

        updateGaze(true, true);
        updateGaze(true, false);
        assertEquals(java.util.Arrays.asList(true, false, true, false), gazeChanges);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void identityEyeGazesAlongNegativeZAndRotatedLookAwayShrinksBeforeClose() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                XrEyeTrackingPermission.PERMISSION);
        CoreState frame = mock(CoreState.class);
        StateFlow<CoreState> frames = mock(StateFlow.class);
        when(frames.getValue()).thenReturn(frame);
        when(session.getState()).thenReturn(frames);
        PerceptionState snapshot = mock(PerceptionState.class);
        ArDevice.State device = mock(ArDevice.State.class);
        when(snapshot.getArDeviceState()).thenReturn(device);
        when(device.getDevicePose()).thenReturn(Pose.Identity);
        when(device.getTrackingState()).thenReturn(TrackingState.TRACKING);
        Eye.State eye = mock(Eye.State.class);
        when(snapshot.getLeftEyeState()).thenReturn(eye);
        when(eye.isOpen()).thenReturn(true);
        when(eye.getTrackingState()).thenReturn(TrackingState.TRACKING);
        AtomicReference<Pose> eyePose = new AtomicReference<>(Pose.Identity);
        when(eye.getPose()).thenAnswer(ignored -> eyePose.get());
        Scene scene = mock(Scene.class);
        PerceptionSpace perceptionSpace = mock(PerceptionSpace.class);
        when(scene.getPerceptionSpace()).thenReturn(perceptionSpace);
        ScenePose head = mock(ScenePose.class);
        when(perceptionSpace.getScenePoseFromPerceptionPose(Pose.Identity)).thenReturn(head);
        when(panel.getSize()).thenReturn(new FloatSize2d(0.4f, 0.2f));
        when(head.transformPositionTo(Pose.Identity.getTranslation(), panel))
                .thenReturn(new Vector3(0f, 0f, 1f));
        // The target is one metre in front of the head, with parallel local axes. Preserve the
        // actual direction supplied by the eye Pose: replacing it with a canned -Z vector would
        // mask a regression that uses the eye's inward-facing +Z/backward axis as its gaze ray.
        when(head.transformDirectionTo(any(Vector3.class), same(panel)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        try (MockedStatic<PerceptionStateExt> snapshots = mockStatic(PerceptionStateExt.class);
             MockedStatic<SessionExt> scenes = mockStatic(SessionExt.class)) {
            snapshots.when(() -> PerceptionStateExt.getPerceptionState(frame)).thenReturn(snapshot);
            scenes.when(() -> SessionExt.getScene(session)).thenReturn(scene);
            tracker.setActive(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(java.util.Arrays.asList(true), gazeChanges);

            Pose lookingAway = new Pose(Pose.Identity.getTranslation(),
                    Quaternion.fromAxisAngle(Vector3.Up, 45f));
            eyePose.set(lookingAway);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(33));
            assertEquals(java.util.Arrays.asList(true, false), gazeChanges);
            verify(head, times(2)).transformPositionTo(Pose.Identity.getTranslation(), panel);
            verify(head).transformDirectionTo(Pose.Identity.getForward(), panel);
            verify(head).transformDirectionTo(lookingAway.getForward(), panel);

            tracker.close();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            snapshots.verify(() -> PerceptionStateExt.getPerceptionState(frame), times(2));
            assertEquals(java.util.Arrays.asList(true, false), gazeChanges);
        }
    }

    private void updateGaze(boolean validGaze, boolean hit) {
        ReflectionHelpers.callInstanceMethod(tracker, "updateGaze",
                ClassParameter.from(boolean.class, validGaze),
                ClassParameter.from(boolean.class, hit));
    }
}
