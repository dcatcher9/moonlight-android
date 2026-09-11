package com.limelight.ui;

import com.limelight.nvstream.HostSessionLaunchRequest;

/**
 * Keeps the Home Space launch behavior aligned with the host's one-session contract.
 */
public final class HomeSessionLaunchPolicy {
    public enum Action {
        START_OR_RESUME,
        CONFIRM_REPLACE
    }

    private HomeSessionLaunchPolicy() {
    }

    public static Action actionFor(int runningAppId, int selectedAppId) {
        return actionFor(runningAppId, null, selectedAppId, null);
    }

    public static Action actionFor(int runningAppId, String runningAppUuid,
                                   int selectedAppId, String selectedAppUuid) {
        boolean hasRunningSession = runningAppId != 0
                || (runningAppUuid != null && !runningAppUuid.isEmpty());
        return !hasRunningSession || isCurrentSessionApp(runningAppId, runningAppUuid,
                selectedAppId, selectedAppUuid) ?
                Action.START_OR_RESUME : Action.CONFIRM_REPLACE;
    }

    /**
     * Returns whether an app card represents the host's one authoritative current session.
     * Apollo app IDs may change across app-list refreshes, so a stable non-empty UUID match is
     * accepted too. Without UUIDs, both numeric IDs must be positive and equal.
     */
    public static boolean isCurrentSessionApp(int runningAppId, String runningAppUuid,
                                              int selectedAppId, String selectedAppUuid) {
        return HostSessionLaunchRequest.sameApplication(runningAppId, runningAppUuid,
                selectedAppId, selectedAppUuid);
    }
}
