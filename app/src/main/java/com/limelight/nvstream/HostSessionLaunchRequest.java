package com.limelight.nvstream;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.io.Serializable;

/** The user's launch decision, bound to the host session they actually observed. */
public final class HostSessionLaunchRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Kind { START, RESUME, REPLACE }
    public enum Action { LAUNCH, RESUME, REPLACE, SESSION_CHANGED, SESSION_ENDED }

    public final Kind kind;
    public final int expectedAppId;
    public final String expectedAppUuid;
    public final boolean tokenSupported;
    public final String expectedToken;

    private HostSessionLaunchRequest(Kind kind, int appId, String appUuid,
                                     boolean tokenSupported, String token) {
        this.kind = kind;
        this.expectedAppId = appId;
        this.expectedAppUuid = clean(appUuid);
        this.tokenSupported = tokenSupported;
        this.expectedToken = clean(token);
    }

    public static HostSessionLaunchRequest start() {
        return new HostSessionLaunchRequest(Kind.START, 0, null, false, null);
    }

    public static HostSessionLaunchRequest resume(int appId, String appUuid,
                                                 boolean tokenSupported, String token) {
        return new HostSessionLaunchRequest(Kind.RESUME, appId, appUuid, tokenSupported, token);
    }

    public static HostSessionLaunchRequest resume(ComputerDetails host) {
        return resume(host.runningGameId, host.runningGameUUID,
                host.hostSessionIdSupported, host.hostSessionId);
    }

    public static HostSessionLaunchRequest replace(ComputerDetails host) {
        return new HostSessionLaunchRequest(Kind.REPLACE, host.runningGameId,
                host.runningGameUUID, host.hostSessionIdSupported, host.hostSessionId);
    }

    public Action plan(ComputerDetails current, NvApp requestedApp) {
        boolean running = current.runningGameId != 0 || clean(current.runningGameUUID) != null
                || (current.hostSessionIdSupported && validToken(clean(current.hostSessionId)));
        if (kind == Kind.START) {
            // This fork's token contract also makes /launch atomic: active streams/handshakes
            // are rejected; an idle retained session may be replaced without a client /cancel.
            // Upstream hosts retain the conservative app-state check.
            // InputOnly adds input to an existing host app and never cancels it.
            return current.hostSessionIdSupported || !running
                    || NvApp.REMOTE_INPUT_UUID.equals(requestedApp.getAppUUID())
                    ? Action.LAUNCH : Action.SESSION_CHANGED;
        }
        if (!running) {
            // Replace explicitly permits ending the old session; its prior natural exit is safe.
            // Resume must never turn into a fresh launch using the expired session's settings.
            return kind == Kind.REPLACE ? Action.LAUNCH : Action.SESSION_ENDED;
        }
        if (tokenSupported != current.hostSessionIdSupported
                || !sameApplication(expectedAppId, expectedAppUuid,
                        current.runningGameId, current.runningGameUUID)
                || (tokenSupported && (!validToken(expectedToken)
                        || !expectedToken.equals(current.hostSessionId)))) {
            return Action.SESSION_CHANGED;
        }
        if (kind == Kind.REPLACE) {
            return Action.REPLACE;
        }
        return sameApplication(current.runningGameId, current.runningGameUUID,
                requestedApp.getAppId(), requestedApp.getAppUUID())
                ? Action.RESUME : Action.SESSION_CHANGED;
    }

    public static boolean sameApplication(int firstId, String firstUuid,
                                          int secondId, String secondUuid) {
        firstUuid = clean(firstUuid);
        secondUuid = clean(secondUuid);
        if (firstUuid != null && secondUuid != null) {
            return firstUuid.equalsIgnoreCase(secondUuid);
        }
        return firstId > 0 && secondId > 0 && firstId == secondId;
    }

    private static boolean validToken(String token) {
        return token != null && !"0".equals(token);
    }

    private static String clean(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
