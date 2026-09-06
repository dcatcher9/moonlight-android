package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import com.limelight.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Exercises the real Window attributes across scoped Client SBS preference ownership. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ClientPanelRefreshRatePreferenceTest {
    private ActivityController<Activity> controller;
    private Window window;
    private ClientPanelRefreshRatePreference preference;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class);
        controller.get().setTheme(R.style.AppTheme);
        controller.setup();
        window = controller.get().getWindow();
        preference = new ClientPanelRefreshRatePreference();
        setOriginalPreference(41, 90.0f);
    }

    @After
    public void tearDown() {
        controller.destroy();
    }

    @Test
    public void advertisedSeventyTwoHzKeepsTheCurrentPhysicalResolution() {
        Display.Mode current = mode(1, 7104, 3840, 90.0f);
        Display display = display(current,
                mode(9, 1920, 1080, 72.00001f),
                mode(2, 7104, 3840, 72.00001f), current);
        window.getAttributes().screenBrightness = 0.37f;

        preference.apply(window, display, true);

        assertPreference(2, 72.00001f);
        assertEquals(0.37f, window.getAttributes().screenBrightness, 0.0f);
        assertEquals(7104, display.getMode().getPhysicalWidth());
        assertEquals(3840, display.getMode().getPhysicalHeight());
    }

    @Test
    public void duplicateApplicationsAfterPanelSwitchPreserveTheOriginalHints() {
        Display.Mode ninety = mode(1, 7104, 3840, 90.0f);
        Display.Mode seventyTwo = mode(2, 7104, 3840, 72.00001f);
        Display display = display(ninety, ninety, seventyTwo);
        preference.apply(window, display, true);

        when(display.getMode()).thenReturn(seventyTwo);
        preference.apply(window, display, true);
        preference.apply(window, display, true);
        preference.apply(window, display, false);

        assertPreference(41, 90.0f);
    }

    @Test
    public void committedExitRestoresBothHintsEvenIfDisplayAlreadyDisappeared() {
        Display.Mode current = mode(1, 7104, 3840, 90.0f);
        preference.apply(window, display(current,
                current, mode(2, 7104, 3840, 72.00001f)), true);

        preference.apply(window, null, false);

        assertPreference(41, 90.0f);
    }

    @Test
    public void missingSameResolutionModeLeavesBothOriginalHintsUntouched() {
        Display.Mode current = mode(1, 7104, 3840, 90.0f);
        Display display = display(current, current,
                mode(2, 3840, 2160, 72.00001f),
                mode(3, 7104, 3840, 60.0f));

        preference.apply(window, display, true);
        assertPreference(41, 90.0f);
        assertEquals(7104, display.getMode().getPhysicalWidth());
        assertEquals(3840, display.getMode().getPhysicalHeight());
        preference.apply(window, display, true);
        preference.apply(window, display, false);

        assertPreference(41, 90.0f);
    }

    @Test
    public void losingTheMatchingModeRestoresOriginalHints() {
        Display.Mode current = mode(1, 7104, 3840, 90.0f);
        Display display = display(current,
                current, mode(2, 7104, 3840, 72.00001f));
        preference.apply(window, display, true);

        when(display.getSupportedModes()).thenReturn(new Display.Mode[] {current});
        preference.apply(window, display, true);

        assertPreference(41, 90.0f);
        preference.apply(window, display, false);
        assertPreference(41, 90.0f);
    }

    @Test
    public void missingDisplayLeavesBothOriginalHintsUntouched() {
        preference.apply(window, null, true);
        assertPreference(41, 90.0f);

        preference.apply(window, null, false);
        assertPreference(41, 90.0f);
    }

    @Test
    public void newlyAdvertisedModeAcquiresAndRestoresOriginalHints() {
        Display.Mode current = mode(1, 7104, 3840, 90.0f);
        Display display = display(current, current);
        preference.apply(window, display, true);
        assertPreference(41, 90.0f);

        when(display.getSupportedModes()).thenReturn(new Display.Mode[] {
                current, mode(2, 7104, 3840, 72.00001f)});
        preference.apply(window, display, true);
        assertPreference(2, 72.00001f);
        preference.apply(window, display, false);
        assertPreference(41, 90.0f);
    }

    @Test
    @Config(sdk = 33)
    public void olderAndroidDoesNotRequestAnUnadvertisedRefreshRate() {
        Display.Mode current = mode(1, 7104, 3840, 90.0f);
        Display display = display(current, current);

        preference.apply(window, display, true);
        assertPreference(41, 90.0f);
        setOriginalPreference(42, 72.0f);
        preference.apply(window, display, false);
        assertPreference(42, 72.0f);
    }

    @Test
    public void nextClientEntryCapturesTheNewOriginalPreference() {
        Display.Mode current = mode(1, 7104, 3840, 90.0f);
        Display display = display(current,
                current, mode(2, 7104, 3840, 72.00001f));
        preference.apply(window, display, true);
        preference.apply(window, display, false);
        setOriginalPreference(71, 72.0f);

        preference.apply(window, display, true);
        preference.apply(window, display, false);

        assertPreference(71, 72.0f);
    }

    @Test
    public void usesTheAdvertisedFractionalRateButRejectsDistantRates() {
        Display.Mode current = mode(1, 7104, 3840, 90.0f);
        Display display = display(current, current,
                mode(3, 7104, 3840, 71.8f),
                mode(2, 7104, 3840, 72.00001f));

        preference.apply(window, display, true);
        assertPreference(2, 72.00001f);
        preference.apply(window, display, false);
        Display.Mode distant = mode(3, 7104, 3840, 71.8f);
        when(display.getSupportedModes()).thenReturn(new Display.Mode[] {
                current, distant});
        preference.apply(window, display, true);

        assertPreference(41, 90.0f);
    }

    private void setOriginalPreference(int modeId, float refreshRate) {
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.preferredDisplayModeId = modeId;
        attributes.preferredRefreshRate = refreshRate;
        window.setAttributes(attributes);
    }

    private void assertPreference(int modeId, float refreshRate) {
        assertEquals(modeId, window.getAttributes().preferredDisplayModeId);
        assertEquals(refreshRate, window.getAttributes().preferredRefreshRate, 0.0f);
    }

    static Display.Mode mode(int id, int width, int height, float refreshRate) {
        return ReflectionHelpers.callConstructor(Display.Mode.class,
                ClassParameter.from(int.class, id), ClassParameter.from(int.class, width),
                ClassParameter.from(int.class, height), ClassParameter.from(float.class, refreshRate));
    }

    static Display display(Display.Mode current, Display.Mode... supported) {
        Display display = mock(Display.class);
        when(display.getMode()).thenReturn(current);
        when(display.getRefreshRate()).thenReturn(current.getRefreshRate());
        when(display.getSupportedModes()).thenReturn(supported);
        when(display.getDisplayId()).thenReturn(Display.DEFAULT_DISPLAY);
        return display;
    }
}
