package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.xr.scenecore.Scene;
import androidx.xr.scenecore.GltfModel;
import androidx.xr.scenecore.SpatialCapability;
import androidx.xr.scenecore.SpatialEnvironment;

import com.limelight.LimeLog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Separates preference writes from asynchronous runtime state, as the public SDK does. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class XrBlackEnvironmentOverrideTest {
    private Scene scene;
    private SpatialEnvironment environment;
    private XrBlackEnvironmentOverride control;
    private SpatialEnvironment.SpatialEnvironmentPreference preference;
    private float opacityPreference = SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE;
    private float actualOpacity = 1f;
    private boolean actualEnvironmentActive;
    private boolean owned = true;
    private int cancellations;
    private Set<SpatialCapability> capabilities;
    private Consumer<Float> opacityListener;
    private Consumer<Boolean> environmentListener;
    private Consumer<Set<SpatialCapability>> capabilityListener;
    private MockedStatic<LimeLog> log;

    @Before
    public void setUp() {
        log = mockStatic(LimeLog.class);
        scene = mock(Scene.class);
        environment = mock(SpatialEnvironment.class);
        capabilities = new HashSet<>(Arrays.asList(
                SpatialCapability.APP_ENVIRONMENT, SpatialCapability.PASSTHROUGH_CONTROL));
        when(scene.getSpatialCapabilities()).thenAnswer(invocation -> capabilities);
        when(scene.getSpatialEnvironment()).thenReturn(environment);
        when(environment.getPreferredSpatialEnvironment()).thenAnswer(invocation -> preference);
        when(environment.getPreferredPassthroughOpacity()).thenAnswer(invocation -> opacityPreference);
        when(environment.getCurrentPassthroughOpacity()).thenAnswer(invocation -> actualOpacity);
        when(environment.isPreferredSpatialEnvironmentActive()).thenAnswer(invocation -> actualEnvironmentActive);
        doAnswer(invocation -> { preference = invocation.getArgument(0); return null; })
                .when(environment).setPreferredSpatialEnvironment(any());
        doAnswer(invocation -> { opacityPreference = invocation.getArgument(0); return null; })
                .when(environment).setPreferredPassthroughOpacity(org.mockito.ArgumentMatchers.anyFloat());
        doAnswer(invocation -> { opacityListener = invocation.getArgument(0); return null; })
                .when(environment).addPassthroughOpacityChangedListener(any());
        doAnswer(invocation -> { environmentListener = invocation.getArgument(0); return null; })
                .when(environment).addSpatialEnvironmentChangedListener(any());
        doAnswer(invocation -> { capabilityListener = invocation.getArgument(0); return null; })
                .when(scene).addSpatialCapabilitiesChangedListener(any());
        createControl();
    }

    private void createControl() {
        control = new XrBlackEnvironmentOverride(scene, () -> owned, () -> {
            cancellations++;
            control.restore(true);
        });
    }

    @After
    public void tearDown() {
        control.restore(true);
        log.close();
    }

    @Test
    public void requiresBothCapabilitiesWithoutChangingPreferences() {
        capabilities.remove(SpatialCapability.PASSTHROUGH_CONTROL);
        assertFalse(control.hasRequiredCapabilities());
        assertFalse(control.begin());
        verify(environment, never()).setPreferredSpatialEnvironment(any());
        verify(environment, never()).setPreferredPassthroughOpacity(0f);
        verify(environment, never()).addPassthroughOpacityChangedListener(any());
    }

    @Test
    public void preferenceWriteCannotMasqueradeAsAppliedState() {
        assertTrue(control.begin());
        assertEquals(new SpatialEnvironment.SpatialEnvironmentPreference(null, null), preference);
        assertEquals(0f, opacityPreference, 0f);
        environmentListener.accept(true); // Event payload alone is not an acknowledgement.
        opacityListener.accept(0f);
        assertEquals(0, cancellations);
        actualEnvironmentActive = true;
        environmentListener.accept(true);
        assertEquals(0, cancellations);
        // Neither event payloads nor environment-active alone mark the override as applied.
        actualEnvironmentActive = false;
        environmentListener.accept(false);
        assertEquals(0, cancellations);
        actualEnvironmentActive = true;
        actualOpacity = 0f;
        opacityListener.accept(0f);
        assertEquals(0, cancellations);
        environmentListener.accept(true);
        assertEquals(0, cancellations);
        // Only a loss after both actual state fields were observed applied releases ownership.
        actualEnvironmentActive = false;
        environmentListener.accept(false);
        assertEquals(1, cancellations);
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
    }

    @Test
    public void restoresNoPreferenceRatherThanThePreviouslyVisibleOpacity() {
        actualOpacity = 0.65f;
        assertTrue(control.begin());
        control.restore(true);
        control.restore(true);
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        verify(environment, times(1)).setPreferredSpatialEnvironment(null);
        verify(environment, times(1)).setPreferredPassthroughOpacity(
                SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE);
        verifyListenersRemoved();
    }

    @Test
    public void restoresExistingEnvironmentObjectAndExplicitOpacity() {
        SpatialEnvironment.SpatialEnvironmentPreference previous =
                mock(SpatialEnvironment.SpatialEnvironmentPreference.class);
        preference = previous;
        opacityPreference = 0.35f;
        createControl();
        assertTrue(control.begin());
        control.restore(true);
        assertSame(previous, preference);
        assertEquals(0.35f, opacityPreference, 0f);
    }

    @Test
    public void capabilityLossRestoresAndCancelsEvenBeforeApplication() {
        assertTrue(control.begin());
        capabilities.clear();
        capabilityListener.accept(capabilities);
        assertEquals(1, cancellations);
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        capabilityListener.accept(capabilities);
        assertEquals(1, cancellations);
        verifyListenersRemoved();
    }

    @Test
    public void appliedStateLossEndsTheControlledInterval() {
        actualOpacity = 0f;
        actualEnvironmentActive = true;
        assertTrue(control.begin());
        assertEquals(0, cancellations);
        actualOpacity = 0.5f;
        opacityListener.accept(actualOpacity);
        assertEquals(1, cancellations);
        assertNull(preference);
    }

    @Test
    public void lateRuntimeCallbacksAfterRestoreCannotReactivateTheRequest() {
        assertTrue(control.begin());
        control.restore(true);
        actualEnvironmentActive = true;
        actualOpacity = 0f;
        environmentListener.accept(true);
        opacityListener.accept(0f);
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        assertEquals(0, cancellations);
    }

    @Test
    public void replacedSceneRemovesListenersWithoutMutatingItsPreferences() {
        assertTrue(control.begin());
        assertFalse(control.belongsTo(mock(Scene.class)));
        when(scene.getSpatialEnvironment()).thenReturn(mock(SpatialEnvironment.class));
        assertFalse(control.belongsTo(scene));
        control.restore(false);
        verify(environment, never()).setPreferredSpatialEnvironment(null);
        verifyListenersRemoved();
    }

    @Test
    public void cancelledOwnerReleasesBeforeQueuedAppliedStateIsAccepted() {
        assertTrue(control.begin());
        owned = false;
        actualEnvironmentActive = true;
        actualOpacity = 0f;
        opacityListener.accept(0f);
        assertEquals(1, cancellations);
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        verifyListenersRemoved();
    }

    @Test
    public void partialListenerFailureIsIsolatedAndUnregisters() {
        doThrow(new IllegalStateException("SDK registration"))
                .when(environment).addSpatialEnvironmentChangedListener(any());
        assertFalse(control.begin());
        verify(environment).removePassthroughOpacityChangedListener(opacityListener);
        verify(environment, never()).setPreferredSpatialEnvironment(any());
    }

    @Test
    public void partialPreferenceFailureRestoresBothCapturedPreferences() {
        doAnswer(invocation -> {
            opacityPreference = invocation.getArgument(0);
            if (opacityPreference == 0f) throw new IllegalStateException("SDK setter");
            return null;
        }).when(environment).setPreferredPassthroughOpacity(org.mockito.ArgumentMatchers.anyFloat());
        assertFalse(control.begin());
        assertNull(preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        verifyListenersRemoved();
    }

    @Test
    public void restoreFailureDoesNotSkipTheOtherPreferenceOrListenerCleanup() {
        assertTrue(control.begin());
        doThrow(new IllegalStateException("SDK restore"))
                .when(environment).setPreferredSpatialEnvironment(null);
        control.restore(true);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        verifyListenersRemoved();
    }

    @Test
    public void restorePreservesANewerPreferenceBeforeItsStateCallbackArrives() {
        assertTrue(control.begin());
        // beta02 reconstructs preference wrappers in its getter, so ownership uses value equality.
        // A mock preference has null backing fields and equals empty black; use actual geometry.
        SpatialEnvironment.SpatialEnvironmentPreference newer =
                new SpatialEnvironment.SpatialEnvironmentPreference(null, mock(GltfModel.class));
        preference = newer;
        control.restore(true);
        assertSame(newer, preference);
        assertEquals(SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE, opacityPreference, 0f);
        verify(environment, never()).setPreferredSpatialEnvironment(null);
        verifyListenersRemoved();
    }

    private void verifyListenersRemoved() {
        verify(environment).removePassthroughOpacityChangedListener(opacityListener);
        verify(environment).removeSpatialEnvironmentChangedListener(environmentListener);
        verify(scene).removeSpatialCapabilitiesChangedListener(capabilityListener);
    }
}
