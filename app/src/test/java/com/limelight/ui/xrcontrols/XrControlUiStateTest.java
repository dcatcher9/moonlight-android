package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XrControlUiStateTest {
    @Test
    public void contextualSurfacesAreMutuallyExclusive() {
        XrControlUiState state = new XrControlUiState();

        state.toggleModeOptions("CLIENT_SBS_AI");
        assertEquals(XrControlUiState.Surface.MODE_OPTIONS, state.getVisibleSurface());
        assertEquals("CLIENT_SBS_AI", state.getModeOptionsId());

        state.toggle(XrControlUiState.Surface.SESSION_SETTINGS);
        assertEquals(XrControlUiState.Surface.SESSION_SETTINGS, state.getVisibleSurface());
        assertNull(state.getModeOptionsId());

    }

    @Test
    public void tappingTheSameAffordanceClosesIt() {
        XrControlUiState state = new XrControlUiState();

        state.toggleModeOptions("HOST_SBS_RAW");
        state.toggleModeOptions("HOST_SBS_RAW");
        assertEquals(XrControlUiState.Surface.NONE, state.getVisibleSurface());

        state.toggle(XrControlUiState.Surface.SESSION_SETTINGS);
        state.toggle(XrControlUiState.Surface.SESSION_SETTINGS);
        assertEquals(XrControlUiState.Surface.NONE, state.getVisibleSurface());
    }

    @Test
    public void statsVisibilityIsIndependentOfContextualSurfaces() {
        XrControlUiState state = new XrControlUiState();
        state.showStats();

        state.toggleModeOptions("CLIENT_SBS_AI");
        assertEquals(XrControlUiState.Surface.MODE_OPTIONS, state.getVisibleSurface());
        assertTrue(state.isStatsVisible());

        state.toggle(XrControlUiState.Surface.SESSION_SETTINGS);
        assertEquals(XrControlUiState.Surface.SESSION_SETTINGS, state.getVisibleSurface());
        assertTrue(state.isStatsVisible());

        state.close();
        assertEquals(XrControlUiState.Surface.NONE, state.getVisibleSurface());
        assertTrue(state.isStatsVisible());
    }

    @Test
    public void repeatedShowStatsIsIdempotent() {
        XrControlUiState state = new XrControlUiState();

        state.showStats();
        state.showStats();

        assertTrue(state.isStatsVisible());
        assertEquals(XrControlUiState.Surface.NONE, state.getVisibleSurface());
    }

    @Test
    public void statsCanBeToggledAndExplicitlyHidden() {
        XrControlUiState state = new XrControlUiState();

        state.toggleStats();
        assertTrue(state.isStatsVisible());

        state.toggleStats();
        assertFalse(state.isStatsVisible());

        state.showStats();
        state.hideStats();
        assertFalse(state.isStatsVisible());
    }

    @Test
    public void anotherModeReusesTheSameOptionsSurface() {
        XrControlUiState state = new XrControlUiState();

        state.toggleModeOptions("NORMAL");
        state.toggleModeOptions("CLIENT_SBS_AI");

        assertEquals(XrControlUiState.Surface.MODE_OPTIONS, state.getVisibleSurface());
        assertEquals("CLIENT_SBS_AI", state.getModeOptionsId());
    }

    @Test
    public void inactiveModeTapSelectsWithoutOpeningOptions() {
        XrControlUiState state = new XrControlUiState();

        assertEquals(XrControlUiState.ModeTileAction.SELECT_MODE,
                state.onModeTileTapped("HOST_SBS_RAW", "NORMAL"));
        assertEquals(XrControlUiState.Surface.NONE, state.getVisibleSurface());
        assertNull(state.getModeOptionsId());
    }

    @Test
    public void activeModeTapTogglesItsOptions() {
        XrControlUiState state = new XrControlUiState();

        assertEquals(XrControlUiState.ModeTileAction.OPTIONS_TOGGLED,
                state.onModeTileTapped("CLIENT_SBS_AI", "CLIENT_SBS_AI"));
        assertEquals(XrControlUiState.Surface.MODE_OPTIONS, state.getVisibleSurface());
        assertEquals("CLIENT_SBS_AI", state.getModeOptionsId());

        assertEquals(XrControlUiState.ModeTileAction.OPTIONS_TOGGLED,
                state.onModeTileTapped("CLIENT_SBS_AI", "CLIENT_SBS_AI"));
        assertEquals(XrControlUiState.Surface.NONE, state.getVisibleSurface());
        assertNull(state.getModeOptionsId());
    }

    @Test
    public void inactiveModeTapClosesOldModePaneAndPreservesStats() {
        XrControlUiState state = new XrControlUiState();
        state.toggleModeOptions("NORMAL");
        state.showStats();

        assertEquals(XrControlUiState.ModeTileAction.SELECT_MODE,
                state.onModeTileTapped("HOST_SBS_AI", "NORMAL"));
        assertEquals(XrControlUiState.Surface.NONE, state.getVisibleSurface());
        assertTrue(state.isStatsVisible());
    }

    @Test
    public void clientModelAloneEnablesAtomicReconnectApply() {
        SessionSettingsModel shared = SessionSettingsModel.builder()
                .putApplied(SessionSettingsModel.Key.HDR,
                        "Off", SessionSettingsModel.Source.GLOBAL)
                .build();
        ClientSbsModeSettingsModel applied = new ClientSbsModeSettingsModel(
                "dav2", "Depth Anything V2", "dav2", "Depth Anything V2",
                SessionSettingsModel.Source.GLOBAL, "322 x 182", "Ready");
        ClientSbsModeSettingsModel pending = new ClientSbsModeSettingsModel(
                "dav2", "Depth Anything V2", "midas", "MiDaS 2.1",
                SessionSettingsModel.Source.CURRENT_SESSION,
                "352 x 192", "Reconnect required");

        assertFalse(XrControlUiState.hasReconnectPending(shared, applied));
        assertTrue(XrControlUiState.hasReconnectPending(shared, pending));
    }
}
