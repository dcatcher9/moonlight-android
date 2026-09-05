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
     * Correlated atomic presentation result. Geometry names deliberately distinguish the source
     * desktop from the exact encoded frame that the decoder must prove before opening output.
     */
    void videoModeAckV2(int status, int appliedMode, int flags, int requestId,
                        int stateGeneration, int appliedSourceWidth, int appliedSourceHeight,
                        int exactEncodedWidth, int exactEncodedHeight,
                        int appliedFramerateX100, int effectiveEncoderBitrateKbps);

    /** Exact 88-byte Apollo host-SBS telemetry v1 state body. */
    void hostSbsTelemetryState(byte[] payload);

    /**
     * Called after the host accepts launch/resume. Token-capable Apollo-3D hosts return the bound
     * session token; standard Sunshine/Apollo hosts report {@code hostSessionIdSupported=false}
     * and a null token.
     */
    void hostSessionEstablished(String hostSessionId, boolean resumed,
                                boolean hostSessionIdSupported);
}
