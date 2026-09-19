package com.limelight;

import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.jni.MoonBridge;

/** Bounded transport recovery; the host still decides whether the captured session exists. */
final class TransportReconnectPolicy {
    static final String EXTRA_ATTEMPT = "TransportReconnectAttempt";
    static final int MAX_ATTEMPTS = 5;
    static final long STABLE_PLAYBACK_MILLIS = 10_000;

    private TransportReconnectPolicy() { }

    static boolean canRetry(HostSessionLaunchRequest request, int attempts, int errorCode,
                            boolean startupFailure, int portFlags) {
        if (request.kind != HostSessionLaunchRequest.Kind.RESUME
                || attempts < 0 || attempts >= MAX_ATTEMPTS
                || (request.expectedAppId <= 0 && request.expectedAppUuid == null)
                || (request.tokenSupported
                && (request.expectedToken == null || "0".equals(request.expectedToken)))) {
            return false;
        }
        if (startupFailure) {
            // A refused/expired identity, authentication error, decoder failure, or malformed
            // response needs user action. HTTP 503 may be temporary display/host recovery.
            return errorCode == -408 || errorCode == 503
                    || (errorCode == -1 && portFlags != 0);
        }
        // moonlight-common reports an unexpected ENet control disconnect as -1. Missing video
        // traffic can likewise follow a network outage. Do not retry host-initiated graceful
        // termination, protected content, conversion failures, or missing decodable frames.
        return errorCode == -1 || errorCode == MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC;
    }

    static long delayMillis(int attempt) {
        return 1000L << Math.max(0, Math.min(3, attempt - 1));
    }
}
