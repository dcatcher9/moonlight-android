package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;

import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.Space;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.XrControlUiState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public class XrStreamPresenterEntityLifecycleTest {
    private ActivityController<Activity> controller;
    private XrStreamPresenter presenter;
    private PanelEntity statsPanel;
    private AtomicReference<Pose> appliedStatsPose;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.create();
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        prefs.width = 1920;
        prefs.height = 1080;
        presenter = new XrStreamPresenter(activity, prefs, surface -> { }, visible -> { });
        // init() normally publishes the negotiated aspect after SceneCore creation, which this
        // fixture deliberately bypasses. Match that initialized geometry for position tests.
        ReflectionHelpers.setField(presenter, "fullAspect", (float) prefs.width / prefs.height);
        statsPanel = mock(PanelEntity.class);
        appliedStatsPose = new AtomicReference<>(Pose.Identity);
        when(statsPanel.getPose(Space.PARENT)).thenAnswer(invocation -> appliedStatsPose.get());
        doAnswer(invocation -> {
            appliedStatsPose.set(invocation.getArgument(0));
            return null;
        }).when(statsPanel).setPose(any(Pose.class));
        ReflectionHelpers.setField(presenter, "statsPanel", statsPanel);
        ReflectionHelpers.setField(presenter, "statsVisible", true);
    }

    @After
    public void tearDown() {
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void repeatedStatsPositionRefreshSkipsIdenticalNodeTransaction() {
        repositionStats();
        repositionStats();
        verify(statsPanel, times(1)).setPose(any(Pose.class));

        Pose previousPose = appliedStatsPose.get();
        float previousHeight = ReflectionHelpers.getField(presenter, "panelHeightMeters");
        float changedHeight = previousHeight + 0.5f;
        ReflectionHelpers.setField(presenter, "panelHeightMeters", changedHeight);
        Pose changedPose = ReflectionHelpers.callInstanceMethod(presenter, "statsPose",
                ClassParameter.from(float.class, changedHeight));
        assertNotEquals("Initialized geometry must produce a different desired pose",
                previousPose, changedPose);
        repositionStats();
        verify(statsPanel, times(2)).setPose(any(Pose.class));
        assertEquals(changedPose, appliedStatsPose.get());
    }

    @Test
    public void comparisonUsesCurrentEntityPoseAfterAnotherCallerMovesIt() {
        Pose desired = new Pose(new Vector3(1.0f, 0.0f, 0.1f), Quaternion.Identity);
        XrStreamPresenter.updatePanelPose(statsPanel, desired);
        // Equal values from a fresh calculation must also be skipped.
        XrStreamPresenter.updatePanelPose(statsPanel,
                new Pose(new Vector3(1.0f, 0.0f, 0.1f), Quaternion.Identity));
        verify(statsPanel, times(1)).setPose(any(Pose.class));

        appliedStatsPose.set(Pose.Identity);
        XrStreamPresenter.updatePanelPose(statsPanel, desired);
        verify(statsPanel, times(2)).setPose(any(Pose.class));
        assertEquals(desired, appliedStatsPose.get());
    }

    @Test
    public void settingsPanelRefreshSkipsUnchangedPoseAndStopsWhenHidden() {
        PanelEntity settings = mock(PanelEntity.class);
        AtomicReference<Pose> applied = new AtomicReference<>(Pose.Identity);
        when(settings.getPose(Space.PARENT)).thenAnswer(invocation -> applied.get());
        doAnswer(invocation -> {
            applied.set(invocation.getArgument(0));
            return null;
        }).when(settings).setPose(any(Pose.class));
        ReflectionHelpers.setField(presenter, "auxiliaryPanel", settings);
        XrControlUiState state = ReflectionHelpers.getField(presenter, "controlUiState");
        state.toggle(XrControlUiState.Surface.SESSION_SETTINGS);
        repositionStats();
        repositionStats();
        verify(settings, times(1)).setPose(any(Pose.class));

        state.close();
        applied.set(Pose.Identity);
        repositionStats();
        verify(settings, times(1)).setPose(any(Pose.class));
    }

    @Test
    public void headDistanceChangeStillRepositionsStats() {
        SurfaceEntity video = mock(SurfaceEntity.class);
        when(video.getPose(Space.REAL_WORLD)).thenReturn(
                new Pose(new Vector3(0.0f, 0.0f, -2.0f), Quaternion.Identity));
        ReflectionHelpers.setField(presenter, "surfaceEntity", video);
        repositionStats();
        when(video.getPose(Space.REAL_WORLD)).thenReturn(
                new Pose(new Vector3(0.0f, 0.0f, -4.0f), Quaternion.Identity));
        repositionStats();
        verify(statsPanel, times(2)).setPose(any(Pose.class));
    }

    @Test
    public void disposedPanelIsNotReadOrMoved() {
        when(statsPanel.isDisposed()).thenReturn(true);
        XrStreamPresenter.updatePanelPose(statsPanel, Pose.Identity);
        verify(statsPanel, never()).getPose(any(Space.class));
        verify(statsPanel, never()).setPose(any(Pose.class));
    }

    @Test
    public void destroyReleasesRetainedPanelOnceAndFencesLaterPositionRefresh() {
        presenter.onDestroy();
        presenter.onDestroy();
        repositionStats();
        verify(statsPanel, times(1)).dispose();
        verify(statsPanel, never()).getPose(any(Space.class));
        verify(statsPanel, never()).setPose(any(Pose.class));
    }

    private void repositionStats() {
        ReflectionHelpers.callInstanceMethod(presenter, "repositionStatsPanel");
    }
}
