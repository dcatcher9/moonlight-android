package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Surface;
import android.view.Window;
import android.view.WindowManager;

import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Tests actual presenter boundary methods; no SceneCore runtime or physical panel is required. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrClientPanelRefreshRateIntegrationTest {
    private ActivityController<Activity> controller;
    private Window window;
    private PreferenceConfiguration preferences;
    private XrStreamPresenter presenter;
    private XrStreamPresenter.PanelRefreshRateState panel;
    private Display display;

    @Before
    public void setUp() throws Exception {
        controller = Robolectric.buildActivity(Activity.class);
        controller.get().setTheme(R.style.AppTheme);
        controller.setup();
        Activity activity = spy(controller.get());
        window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.preferredDisplayModeId = 41;
        attributes.preferredRefreshRate = 90.0f;
        window.setAttributes(attributes);

        Display.Mode current = ClientPanelRefreshRatePreferenceTest.mode(
                1, 7104, 3840, 90.0f);
        display = ClientPanelRefreshRatePreferenceTest.display(current,
                current, ClientPanelRefreshRatePreferenceTest.mode(
                        2, 7104, 3840, 72.00001f));
        DisplayManager displayManager = mock(DisplayManager.class);
        when(displayManager.getDisplays()).thenReturn(new Display[] {display});
        when(displayManager.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(display);
        doReturn(displayManager).when(activity).getSystemService(Context.DISPLAY_SERVICE);

        preferences = PreferenceConfiguration.readPreferences(activity);
        preferences.width = 1920;
        preferences.height = 1080;
        preferences.fps = 30;
        presenter = new XrStreamPresenter(activity, preferences,
                surface -> { }, visible -> { });
        panel = (XrStreamPresenter.PanelRefreshRateState) field("panelRefreshRateState");
    }

    @After
    public void tearDown() {
        if (presenter != null) {
            presenter.onDestroy();
        }
        controller.destroy();
    }

    @Test
    public void windowPreferenceAppliesAndRestoresWithoutAnAdoptedVideoSurface()
            throws Exception {
        assertNull(field("videoSurface"));
        setField("currentPresenterMode", PresentationMode.CLIENT_SBS_AI);

        invoke("applyPresentationFrameRatePreference");
        assertWindowPreference(2, 72.00001f);

        setField("currentPresenterMode", PresentationMode.HOST_SBS_AI);
        invoke("applyPresentationFrameRatePreference");
        assertWindowPreference(41, 90.0f);
    }

    @Test
    public void destroyingClientPresenterRestoresTheOriginalWindowPreference() throws Exception {
        setField("currentPresenterMode", PresentationMode.CLIENT_SBS_AI);
        invoke("applyPresentationFrameRatePreference");
        assertWindowPreference(2, 72.00001f);

        presenter.onDestroy();

        assertWindowPreference(41, 90.0f);
    }

    @Test
    @Config(sdk = 34)
    public void clientEntryUsesAdvertisedSeventyTwoWhenSixtyIsUnavailable() throws Exception {
        Display.Mode current = display.getMode();
        when(display.getSupportedModes()).thenReturn(new Display.Mode[] {
                current, ClientPanelRefreshRatePreferenceTest.mode(
                        3, 7104, 3840, 72.00001f)});
        setField("currentPresenterMode", PresentationMode.CLIENT_SBS_AI);

        invoke("applyPresentationFrameRatePreference");

        assertWindowPreference(3, 72.00001f);
        assertEquals(30.0f, preferences.fps, 0.0f);
        setField("currentPresenterMode", PresentationMode.NORMAL);
        invoke("applyPresentationFrameRatePreference");
        assertWindowPreference(41, 90.0f);
    }

    @Test
    public void successfulHigherDurableCeilingReleasesSeventyTwoDespiteEffectiveSixtyAck()
            throws Exception {
        setField("currentPresenterMode", PresentationMode.CLIENT_SBS_AI);
        invoke("applyPresentationFrameRatePreference");
        assertWindowPreference(2, 72.00001f);

        panel.observe(60.0f);
        preferences.fps = 60; // Geometry/decoder adoption already consumed the effective ACK.
        setField("pendingLiveQualityOrigin", XrStreamPresenter.LiveQualityRequestOrigin.USER);
        setField("pendingDurableUserQuality", new StreamQualityTuple("1920x1080", "90", 200000));
        invoke("settleSuccessfulLiveQuality",
                new Class<?>[] {PresentationMode.class, StreamQualityTuple.class},
                PresentationMode.CLIENT_SBS_AI,
                new StreamQualityTuple("1920x1080", "60", 200000));

        assertEquals(90, panel.getUserCeilingHz());
        assertEquals(60.0f, preferences.fps, 0.0f);
        assertEquals(90, XrStreamPresenter.durableSurfaceFrameRateVoteHz(
                panel, PresentationMode.CLIENT_SBS_AI));
        assertFalse(XrStreamPresenter.shouldPreferClientPanelRate(
                panel, PresentationMode.CLIENT_SBS_AI));
        assertWindowPreference(41, 90.0f);
    }

    @Test
    public void localModeCommitAppliesClientPreferenceAndReleasesItOnNormalExit()
            throws Exception {
        // Deferred Client startup and a same-wire switch can commit without quality settlement.
        setField("surfaceEntity", mock(SurfaceEntity.class));
        invoke("applyPresenterModeInterpretation",
                new Class<?>[] {PresentationMode.class,
                        PresentationMode.class, String.class},
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI, "Client3D");
        assertWindowPreference(2, 72.00001f);

        invoke("applyPresenterModeInterpretation",
                new Class<?>[] {PresentationMode.class,
                        PresentationMode.class, String.class},
                PresentationMode.CLIENT_SBS_AI,
                PresentationMode.NORMAL, "Normal");
        assertWindowPreference(41, 90.0f);
    }

    @Test
    public void replacementSceneCoreSurfaceKeepsSeventyTwoWithoutRaisingStreamRate()
            throws Exception {
        setField("currentPresenterMode", PresentationMode.CLIENT_SBS_AI);
        for (int replacement = 0; replacement < 2; replacement++) {
            Surface surface = mock(Surface.class);
            when(surface.isValid()).thenReturn(true);

            invoke("adoptVideoSurface", new Class<?>[] {Surface.class}, surface);

            verify(surface).setFrameRate(72.0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
            assertWindowPreference(2, 72.00001f);
            assertEquals(30.0f, preferences.fps, 0.0f);
            assertEquals(30, panel.getUserCeilingHz());
        }
    }

    private void assertWindowPreference(int modeId, float refreshRate) {
        assertEquals(modeId, window.getAttributes().preferredDisplayModeId);
        assertEquals(refreshRate, window.getAttributes().preferredRefreshRate, 0.0f);
    }

    private Object field(String name) throws Exception {
        Field field = XrStreamPresenter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(presenter);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = XrStreamPresenter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(presenter, value);
    }

    private void invoke(String name) throws Exception {
        invoke(name, new Class<?>[0]);
    }

    private void invoke(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = XrStreamPresenter.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(presenter, args);
    }
}
