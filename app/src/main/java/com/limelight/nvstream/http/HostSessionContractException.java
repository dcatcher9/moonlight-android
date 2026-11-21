package com.limelight.nvstream.http;

import java.io.IOException;

/**
 * A launch or resume that violates the host-session contract: a missing or mismatched session
 * token, or no application identity to launch. Repeating the same request cannot succeed, so
 * the connection reports it once with {@link #STAGE_FAILURE_CODE} instead of retrying it.
 */
public class HostSessionContractException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Stage-failure code for a contract violation; it is not a retryable startup failure. */
    public static final int STAGE_FAILURE_CODE = -200;

    public HostSessionContractException(String message) {
        super(message);
    }
}
