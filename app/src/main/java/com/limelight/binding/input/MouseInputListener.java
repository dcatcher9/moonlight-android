package com.limelight.binding.input;

/** Mouse events from the on-screen keyboard controls. Button IDs preserve their saved mapping. */
public interface MouseInputListener {
    int BUTTON_LEFT = 1;
    int BUTTON_MIDDLE = 2;
    int BUTTON_RIGHT = 3;
    int BUTTON_X1 = 4;
    int BUTTON_X2 = 5;

    void mouseMove(int deltaX, int deltaY);
    void mouseButtonEvent(int buttonId, boolean down);
}
