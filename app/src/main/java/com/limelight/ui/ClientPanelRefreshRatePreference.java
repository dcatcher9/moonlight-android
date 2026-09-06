package com.limelight.ui;

import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import com.limelight.LimeLog;

/** A scoped window preference; Android XR still owns the physical panel rate. */
final class ClientPanelRefreshRatePreference {
    static final int CLIENT_PANEL_HZ = 72;

    private boolean active;
    private int previousModeId;
    private float previousRefreshRate;

    void apply(Window window, Display display, boolean preferClientPanelRate) {
        Display.Mode mode = preferClientPanelRate && display != null
                ? findClientPanelMode(display) : null;
        WindowManager.LayoutParams attributes = window.getAttributes();
        // Use the mode advertised to this app, including its precise rate (72.00001 on Galaxy XR).
        // Only an advertised same-resolution mode qualifies for this scoped preference.
        if (mode != null) {
            int modeId = mode.getModeId();
            float refreshRate = mode.getRefreshRate();
            boolean acquiring = !active;
            if (acquiring) {
                previousModeId = attributes.preferredDisplayModeId;
                previousRefreshRate = attributes.preferredRefreshRate;
                active = true;
            }
            if (attributes.preferredDisplayModeId != modeId
                    || attributes.preferredRefreshRate != refreshRate) {
                attributes.preferredDisplayModeId = modeId;
                attributes.preferredRefreshRate = refreshRate;
                window.setAttributes(attributes);
                logRequest(mode, display);
            } else if (acquiring) {
                logRequest(mode, display);
            }
        } else if (active) {
            attributes.preferredDisplayModeId = previousModeId;
            attributes.preferredRefreshRate = previousRefreshRate;
            window.setAttributes(attributes);
            active = false;
            LimeLog.info("XR: released Client SBS " + CLIENT_PANEL_HZ + " Hz panel preference");
        }
    }

    private static void logRequest(Display.Mode mode, Display display) {
        LimeLog.info("XR: Client SBS requests a " + mode.getRefreshRate()
                + " Hz panel using advertised mode " + mode.getModeId()
                + "; observed refresh=" + display.getRefreshRate()
                + " Hz (runtime decides the effective rate)");
    }

    private static Display.Mode findClientPanelMode(Display display) {
        Display.Mode current = display.getMode();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()
                    && Math.abs(mode.getRefreshRate() - CLIENT_PANEL_HZ) < 0.1f) {
                return mode;
            }
        }
        return null;
    }
}
