package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeSessionLaunchPolicyTest {
    @Test
    public void startsWhenHostHasNoCurrentSession() {
        assertEquals(HomeSessionLaunchPolicy.Action.START_OR_RESUME,
                HomeSessionLaunchPolicy.actionFor(0, 42));
    }

    @Test
    public void resumesWhenSelectedAppOwnsCurrentSession() {
        assertEquals(HomeSessionLaunchPolicy.Action.START_OR_RESUME,
                HomeSessionLaunchPolicy.actionFor(42, 42));
    }

    @Test
    public void guardsReplacementWhenAnotherAppIsRunning() {
        assertEquals(HomeSessionLaunchPolicy.Action.CONFIRM_REPLACE,
                HomeSessionLaunchPolicy.actionFor(7, 42));
    }

    @Test
    public void resumesWhenStableAppUuidMatches() {
        assertEquals(HomeSessionLaunchPolicy.Action.START_OR_RESUME,
                HomeSessionLaunchPolicy.actionFor(7, "host-app", 42, "HOST-APP"));
    }

    @Test
    public void conflictingUuidOverridesMatchingNumericAppId() {
        assertFalse(HomeSessionLaunchPolicy.isCurrentSessionApp(
                42, "running-uuid", 42, "different-uuid"));
    }

    @Test
    public void currentSessionCardMatchesStableUuidAcrossIdChange() {
        assertTrue(HomeSessionLaunchPolicy.isCurrentSessionApp(
                7, "host-app", 42, "HOST-APP"));
    }

    @Test
    public void uuidOnlyHostSessionMatchesSelectedApp() {
        assertTrue(HomeSessionLaunchPolicy.isCurrentSessionApp(
                0, "host-app", 42, "HOST-APP"));
    }

    @Test
    public void unrelatedAppIsNotCurrentSessionCard() {
        assertFalse(HomeSessionLaunchPolicy.isCurrentSessionApp(
                7, "host-app", 42, "other-app"));
    }
}
