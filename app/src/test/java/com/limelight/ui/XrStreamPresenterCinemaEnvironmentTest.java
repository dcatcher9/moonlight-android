package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.os.Looper;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.Scene;
import androidx.xr.scenecore.SessionExt;
import androidx.xr.scenecore.Space;
import androidx.xr.scenecore.SpatialCapability;
import androidx.xr.scenecore.SpatialEnvironment;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.XrChoiceGroup;
import com.limelight.ui.xrcontrols.XrControlUiState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Exercises the existing Cinema action and lifecycle through the real environment controller. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = com.limelight.shadows.ShadowMoonBridge.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class XrStreamPresenterCinemaEnvironmentTest {
    private ActivityController<Activity> activityController;
    private XrStreamPresenter presenter;
    private MockedStatic<SessionExt> sessionExt;
    private SurfaceEntity surface;
    private Scene scene;
    private SpatialEnvironment environment;
    private SpatialEnvironment.SpatialEnvironmentPreference preference;
    private float opacityPreference = SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE;
    private boolean actualEnvironmentActive;
    private float actualOpacity = 1f;
    private Set<SpatialCapability> capabilities;
    private Consumer<Boolean> environmentListener;
    private Consumer<Float> opacityListener;
    private Consumer<Set<SpatialCapability>> capabilityListener;
    private final Pose originalPose = new Pose(new Vector3(0.4f, 1.3f, -3.1f), Quaternion.Identity);

    @Before
    public void setUp() {
        activityController = Robolectric.buildActivity(Activity.class);
        activityController.get().setTheme(R.style.AppTheme);
        activityController.setup();
        presenter = new XrStreamPresenter(activityController.get(),
                PreferenceConfiguration.readPreferences(activityController.get()),
                ignored -> { }, ignored -> { });
        surface = mock(SurfaceEntity.class);
        when(surface.isEnabled(false)).thenReturn(true);
        when(surface.getPose(Space.REAL_WORLD)).thenReturn(originalPose);
        ReflectionHelpers.setField(presenter, "surfaceEntity", surface);
        ReflectionHelpers.setField(presenter, "panelHeightMeters", 1.1f);
        ReflectionHelpers.setField(presenter, "fullAspect", 16f / 9f);
        ReflectionHelpers.setField(presenter, "hostActivityStarted", true);
        ReflectionHelpers.setField(presenter, "streamPresentationReady", true);
        ReflectionHelpers.setField(presenter, "sessionControlsEnabled", true);

        Session session = mock(Session.class);
        scene = mock(Scene.class);
        environment = mock(SpatialEnvironment.class);
        sessionExt = mockStatic(SessionExt.class);
        sessionExt.when(() -> SessionExt.getScene(session)).thenReturn(scene);
        ReflectionHelpers.setField(presenter, "session", session);
        when(scene.getSpatialEnvironment()).thenReturn(environment);
        capabilities = new HashSet<>(Arrays.asList(
                SpatialCapability.APP_ENVIRONMENT, SpatialCapability.PASSTHROUGH_CONTROL));
        when(scene.getSpatialCapabilities()).thenAnswer(ignored -> capabilities);
        when(environment.getPreferredSpatialEnvironment()).thenAnswer(ignored -> preference);
        when(environment.getPreferredPassthroughOpacity()).thenAnswer(ignored -> opacityPreference);
        when(environment.isPreferredSpatialEnvironmentActive()).thenAnswer(ignored -> actualEnvironmentActive);
        when(environment.getCurrentPassthroughOpacity()).thenAnswer(ignored -> actualOpacity);
        doAnswer(invocation -> { preference = invocation.getArgument(0); return null; })
                .when(environment).setPreferredSpatialEnvironment(any());
        doAnswer(invocation -> { opacityPreference = invocation.getArgument(0); return null; })
                .when(environment).setPreferredPassthroughOpacity(anyFloat());
        doAnswer(invocation -> { environmentListener = invocation.getArgument(0); return null; })
                .when(environment).addSpatialEnvironmentChangedListener(any());
        doAnswer(invocation -> { opacityListener = invocation.getArgument(0); return null; })
                .when(environment).addPassthroughOpacityChangedListener(any());
        doAnswer(invocation -> { capabilityListener = invocation.getArgument(0); return null; })
                .when(scene).addSpatialCapabilitiesChangedListener(any());
    }

    @After
    public void tearDown() {
        presenter.onDestroy();
        activityController.destroy();
        sessionExt.close();
    }

    @Test
    public void cinemaRetainsGeometryToggleAndRestoresExactPriorEnvironment() {
        SpatialEnvironment.SpatialEnvironmentPreference previous =
                mock(SpatialEnvironment.SpatialEnvironmentPreference.class);
        preference = previous;
        opacityPreference = 0.35f;

        tapCinema();
        assertTrue(cinemaSelected());
        assertBlackRequested();
        assertEquals(2f, height(), 0f);
        acknowledgeApplied();
        tapCinema();

        assertFalse(cinemaSelected());
        assertEquals(1.1f, height(), 0f);
        assertSame(previous, preference);
        assertEquals(0.35f, opacityPreference, 0f);
        verify(surface).setPose(originalPose, Space.REAL_WORLD);
        ArgumentCaptor<SurfaceEntity.Shape> shapes = ArgumentCaptor.forClass(SurfaceEntity.Shape.class);
        verify(surface, times(2)).setShape(shapes.capture());
        assertEquals(2f, ((SurfaceEntity.Shape.Quad) shapes.getAllValues().get(0)).getExtents().getHeight(), 0f);
        assertEquals(1.1f, ((SurfaceEntity.Shape.Quad) shapes.getAllValues().get(1)).getExtents().getHeight(), 0f);
        assertSame(PresentationMode.NORMAL,
                ReflectionHelpers.getField(presenter, "currentPresenterMode"));
        verify(surface, never()).setEnabled(false);
    }

    @Test
    public void cinemaTileEntersThenTogglesSharedOptionsAndExitClosesThePane() {
        FrameLayout host = new FrameLayout(activityController.get());
        ReflectionHelpers.setField(presenter, "modeOptionsHost", host);
        XrControlUiState controls = ReflectionHelpers.getField(presenter, "controlUiState");
        tapCinemaTile();
        assertTrue(cinemaSelected());
        assertEquals(XrControlUiState.Surface.NONE, controls.getVisibleSurface());
        assertEquals(0, host.getChildCount());
        tapCinemaTile();
        assertEquals(XrControlUiState.Surface.CINEMA_OPTIONS, controls.getVisibleSurface());
        assertTrue(host.getChildAt(0) instanceof ScrollView);
        XrChoiceGroup choices = ReflectionHelpers.getField(presenter, "cinemaEnvironmentChoiceGroup");
        Button action = ReflectionHelpers.getField(presenter, "cinemaActionButton");
        assertEquals(3, choices.getChildCount());
        assertEquals("black", choices.getSelectedValue());
        assertEquals("Exit Cinema", action.getText().toString());

        choices.getButtonAt(2).performClick();
        assertEquals("passthrough", choices.getSelectedValue());
        assertSame(PreferenceConfiguration.CinemaEnvironment.PASSTHROUGH,
                PreferenceConfiguration.readPreferences(activityController.get()).cinemaEnvironment);
        assertEquals(1f, opacityPreference, 0f);
        tapCinemaTile();
        assertEquals(XrControlUiState.Surface.NONE, controls.getVisibleSurface());
        assertTrue(cinemaSelected());
        tapCinemaTile();
        action = ReflectionHelpers.getField(presenter, "cinemaActionButton");
        action.performClick();
        assertFalse(cinemaSelected());
        assertEquals(XrControlUiState.Surface.NONE, controls.getVisibleSurface());
        assertEquals(1.1f, height(), 0f);
        // The saved selection is used on the next one-tap entry.
        tapCinemaTile();
        assertTrue(cinemaSelected());
        assertEquals(1f, opacityPreference, 0f);
    }

    @Test
    public void choosingEnvironmentDuringCinemaKeepsScreenPoseAndRestoresOriginalOnExit() {
        SpatialEnvironment.SpatialEnvironmentPreference original =
                mock(SpatialEnvironment.SpatialEnvironmentPreference.class);
        preference = original;
        opacityPreference = 0.35f;
        tapCinema();
        acknowledgeApplied();
        ReflectionHelpers.setField(presenter, "modeOptionsHost", new FrameLayout(activityController.get()));
        tapCinemaTile();
        XrChoiceGroup choices = ReflectionHelpers.getField(presenter, "cinemaEnvironmentChoiceGroup");
        clearInvocations(surface);

        choices.getButtonAt(1).performClick();
        assertTrue(cinemaSelected());
        assertNull(preference);
        assertEquals(0f, opacityPreference, 0f);
        verify(surface, never()).setShape(any());
        verify(surface, never()).setPose(any(), any());
        tapCinema();
        assertSame(original, preference);
        assertEquals(0.35f, opacityPreference, 0f);
    }

    @Test
    public void hiddenOrReplacedCinemaChoicesCannotApplyLateClicks() {
        ReflectionHelpers.setField(presenter, "modeOptionsHost", new FrameLayout(activityController.get()));
        tapCinemaTile();
        tapCinemaTile();
        XrChoiceGroup oldChoices = ReflectionHelpers.getField(presenter, "cinemaEnvironmentChoiceGroup");
        tapCinemaTile();
        oldChoices.getButtonAt(2).performClick();
        assertSame(PreferenceConfiguration.CinemaEnvironment.BLACK,
                PreferenceConfiguration.readPreferences(activityController.get()).cinemaEnvironment);

        tapCinemaTile();
        oldChoices.getButtonAt(1).performClick();
        assertSame(PreferenceConfiguration.CinemaEnvironment.BLACK,
                PreferenceConfiguration.readPreferences(activityController.get()).cinemaEnvironment);
        XrChoiceGroup currentChoices = ReflectionHelpers.getField(presenter, "cinemaEnvironmentChoiceGroup");
        currentChoices.getButtonAt(2).performClick();
        assertSame(PreferenceConfiguration.CinemaEnvironment.PASSTHROUGH,
                PreferenceConfiguration.readPreferences(activityController.get()).cinemaEnvironment);
    }

    @Test
    public void disabledCinemaControlsCannotSaveOrApplyALateChoice() {
        ReflectionHelpers.setField(presenter, "modeOptionsHost", new FrameLayout(activityController.get()));
        tapCinemaTile();
        tapCinemaTile();
        XrChoiceGroup choices = ReflectionHelpers.getField(presenter, "cinemaEnvironmentChoiceGroup");
        Button action = ReflectionHelpers.getField(presenter, "cinemaActionButton");
        presenter.setSessionControlsEnabled(false);
        assertFalse(choices.isEnabled());
        assertFalse(action.isEnabled());
        choices.getButtonAt(2).performClick();
        action.performClick();
        tapCinemaTile();
        XrControlUiState controls = ReflectionHelpers.getField(presenter, "controlUiState");
        assertEquals(XrControlUiState.Surface.CINEMA_OPTIONS, controls.getVisibleSurface());
        assertEquals("black", choices.getSelectedValue());
        assertSame(PreferenceConfiguration.CinemaEnvironment.BLACK,
                PreferenceConfiguration.readPreferences(activityController.get()).cinemaEnvironment);
        assertTrue(cinemaSelected());
    }

    @Test
    public void exitBeforeRuntimeAcknowledgementCannotBeReactivatedByLateCallbacks() {
        tapCinema();
        Consumer<Boolean> staleEnvironmentListener = environmentListener;
        Consumer<Float> staleOpacityListener = opacityListener;
        tapCinema();
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        clearInvocations(environment);

        actualEnvironmentActive = true;
        actualOpacity = 0f;
        staleEnvironmentListener.accept(true);
        staleOpacityListener.accept(0f);

        assertFalse(cinemaSelected());
        assertNull(backgroundOwner());
        verify(environment, never()).setPreferredSpatialEnvironment(any());
        verify(environment, never()).setPreferredPassthroughOpacity(anyFloat());
    }

    @Test
    public void foregroundReentryRecapturesCurrentPreferenceWithoutMovingTheScreen() {
        tapCinema();
        Consumer<Boolean> staleListener = environmentListener;
        presenter.onHostActivityStopped();
        assertTrue(cinemaSelected());
        assertNull(backgroundOwner());
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        SpatialEnvironment.SpatialEnvironmentPreference foregroundPreference =
                mock(SpatialEnvironment.SpatialEnvironmentPreference.class);
        preference = foregroundPreference;
        opacityPreference = 0.6f;
        clearInvocations(surface);

        presenter.onHostActivityStarted();
        assertBlackRequested();
        Object newOwner = backgroundOwner();
        staleListener.accept(true);
        assertSame(newOwner, backgroundOwner());
        verify(surface, never()).setShape(any());
        verify(surface, never()).setPose(any(), any());

        tapCinema();
        assertSame(foregroundPreference, preference);
        assertEquals(0.6f, opacityPreference, 0f);
        assertEquals(1.1f, height(), 0f);
    }

    @Test
    public void capabilityLossReleasesOnlyBackgroundAndDoesNotRetryFromStaleEvents() {
        tapCinema();
        acknowledgeApplied();
        Consumer<Set<SpatialCapability>> staleListener = capabilityListener;
        capabilities.clear();
        staleListener.accept(capabilities);
        assertTrue(cinemaSelected());
        assertEquals(2f, height(), 0f);
        assertNull(backgroundOwner());
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        clearInvocations(environment);

        capabilities.add(SpatialCapability.APP_ENVIRONMENT);
        capabilities.add(SpatialCapability.PASSTHROUGH_CONTROL);
        staleListener.accept(capabilities);
        assertNull(backgroundOwner());
        verify(environment, never()).setPreferredSpatialEnvironment(any());
        tapCinema();
        assertFalse(cinemaSelected());
        assertEquals(1.1f, height(), 0f);
    }

    @Test
    public void unsupportedEnvironmentStillAllowsOriginalCinemaSizeAndPoseToggle() {
        capabilities.remove(SpatialCapability.PASSTHROUGH_CONTROL);
        tapCinema();
        assertTrue(cinemaSelected());
        assertEquals(2f, height(), 0f);
        assertNull(backgroundOwner());
        tapCinema();
        assertFalse(cinemaSelected());
        assertEquals(1.1f, height(), 0f);
        verify(surface).setPose(originalPose, Space.REAL_WORLD);
        verify(environment, never()).setPreferredSpatialEnvironment(any());
        verify(environment, never()).setPreferredPassthroughOpacity(anyFloat());
    }

    @Test
    public void connectionStopAndDestroyRestoreOnceBeforeSceneDisposal() {
        tapCinema();
        Consumer<Boolean> staleListener = environmentListener;
        presenter.onConnectionStopping();
        assertNull(backgroundOwner());
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        verify(surface, never()).dispose();
        presenter.onDestroy();
        staleListener.accept(true);
        verify(environment, times(1)).setPreferredSpatialEnvironment(null);
        verify(environment, times(1)).setPreferredPassthroughOpacity(
                SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE);
        verify(surface).dispose();
    }

    private void tapCinema() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS);
        ReflectionHelpers.callInstanceMethod(presenter, "onCinemaActionTapped");
    }

    private void tapCinemaTile() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS);
        ReflectionHelpers.callInstanceMethod(presenter, "onCinemaTileTapped");
    }

    private void acknowledgeApplied() {
        actualEnvironmentActive = true;
        actualOpacity = 0f;
        environmentListener.accept(true);
    }

    private void assertBlackRequested() {
        assertNotNull(backgroundOwner());
        assertEquals(new SpatialEnvironment.SpatialEnvironmentPreference(null, null), preference);
        assertEquals(0f, opacityPreference, 0f);
    }

    private Object backgroundOwner() {
        return ReflectionHelpers.getField(presenter, "cinemaBackgroundOverride");
    }

    private boolean cinemaSelected() {
        return ReflectionHelpers.getField(presenter, "cinemaViewExpanded");
    }

    private float height() {
        return ReflectionHelpers.getField(presenter, "panelHeightMeters");
    }
}
