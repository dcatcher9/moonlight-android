package com.limelight.ui;

/** Independent hover and focus sources of the collapsed pill's View attention. */
final class DockRevealViewAttentionState {
    boolean hovered;
    boolean focused;

    boolean active() {
        return hovered || focused;
    }

    void clear() {
        hovered = false;
        focused = false;
    }
}
