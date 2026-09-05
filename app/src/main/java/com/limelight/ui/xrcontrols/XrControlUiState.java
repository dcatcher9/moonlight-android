package com.limelight.ui.xrcontrols;

/**
 * Pure state machine for the XR stream controls. Contextual surfaces are mutually exclusive, while
 * the Stats overlay has independent visibility so opening a contextual pane cannot accidentally
 * clear the user's Stats choice.
 */
public final class XrControlUiState {
    public enum ModeTileAction {
        SELECT_MODE,
        OPTIONS_TOGGLED
    }

    public enum Surface {
        NONE,
        MODE_OPTIONS,
        SESSION_SETTINGS
    }

    private Surface visibleSurface = Surface.NONE;
    private String modeOptionsId;
    private boolean statsVisible;

    public Surface getVisibleSurface() {
        return visibleSurface;
    }

    public String getModeOptionsId() {
        return modeOptionsId;
    }

    public boolean isStatsVisible() {
        return statsVisible;
    }

    /**
     * Applies the single-target mode-tile interaction. An inactive tile selects its mode without
     * opening options; the active tile toggles that mode's reusable options surface.
     */
    public ModeTileAction onModeTileTapped(String modeId, String activeModeId) {
        if (modeId == null || modeId.isEmpty()) {
            throw new IllegalArgumentException("modeId must not be empty");
        }
        if (activeModeId == null || activeModeId.isEmpty()) {
            throw new IllegalArgumentException("activeModeId must not be empty");
        }

        if (modeId.equals(activeModeId)) {
            toggleModeOptions(modeId);
            return ModeTileAction.OPTIONS_TOGGLED;
        }

        // A pane always belongs to the active mode. Other secondary surfaces can stay open while
        // presentation changes, matching the existing Stats/Settings behavior.
        if (visibleSurface == Surface.MODE_OPTIONS) {
            close();
        }
        return ModeTileAction.SELECT_MODE;
    }

    /** Toggle a contextual surface, closing whichever contextual surface was previously visible. */
    public void toggle(Surface surface) {
        if (surface == Surface.NONE || surface == Surface.MODE_OPTIONS) {
            throw new IllegalArgumentException("Use close() or toggleModeOptions() for " + surface);
        }
        if (visibleSurface == surface) {
            close();
        } else {
            visibleSurface = surface;
            modeOptionsId = null;
        }
    }

    /** Toggle the reusable mode-options row for the supplied stable presentation-mode ID. */
    public void toggleModeOptions(String modeId) {
        if (modeId == null || modeId.isEmpty()) {
            throw new IllegalArgumentException("modeId must not be empty");
        }
        if (visibleSurface == Surface.MODE_OPTIONS && modeId.equals(modeOptionsId)) {
            close();
        } else {
            visibleSurface = Surface.MODE_OPTIONS;
            modeOptionsId = modeId;
        }
    }

    /** Show Stats without changing the currently open contextual surface. */
    public void showStats() {
        statsVisible = true;
    }

    /** Toggle Stats without changing the currently open contextual surface. */
    public void toggleStats() {
        statsVisible = !statsVisible;
    }

    /** Hide Stats without changing the currently open contextual surface. */
    public void hideStats() {
        statsVisible = false;
    }

    public void close() {
        visibleSurface = Surface.NONE;
        modeOptionsId = null;
    }

}
