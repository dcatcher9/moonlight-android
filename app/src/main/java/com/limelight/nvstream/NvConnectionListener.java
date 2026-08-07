package com.limelight.nvstream;

public interface NvConnectionListener {
    void stageStarting(String stage);
    void stageComplete(String stage);
    boolean stageFailed(String stage, int portFlags, int errorCode);
    
    void connectionStarted();
    void connectionTerminated(int errorCode);
    void connectionStatusUpdate(int connectionStatus);
    
    void displayMessage(String message);
    void displayTransientMessage(String message);

    void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
    void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);

    void setHdrMode(boolean enabled, byte[] hdrMetadata);

    void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz);

    void setControllerLED(short controllerNumber, byte r, byte g, byte b);

    // Host SBS preparation phase (Apollo extension): 0 = idle/failure,
    // 1 = process-wide depth-engine preparation, 2 = ready, 3 = per-stream GPU pipeline setup.
    void depthStatus(int phase);

    /**
     * Host answer to a live video-mode request (Apollo extension). {@code requestId} is echoed
     * verbatim and is the only correlation key. {@code status} is one of the
     * {@code MoonBridge.VIDEO_MODE_ACK_*} values. The {@code applied*} values report what the host
     * is actually running — a clamped apply is a success, not a failure. Geometry uses the
     * request's wire coordinate system: Host SBS AI reports base dimensions, while Raw Full
     * reports its already-packed desktop. {@code appliedBitrateKbps} is the host's post-budget
     * encoder value.
     */
    void videoModeAck(int requestId, int status, int appliedWidth, int appliedHeight,
                      int appliedFramerateX100, int appliedBitrateKbps);

    /** Exact 88-byte Apollo host-SBS telemetry v1 state body. */
    void hostSbsTelemetryState(byte[] payload);

    /** Called after Apollo has accepted launch/resume and returned the bound session token. */
    void hostSessionEstablished(String hostSessionId, boolean resumed);
}
