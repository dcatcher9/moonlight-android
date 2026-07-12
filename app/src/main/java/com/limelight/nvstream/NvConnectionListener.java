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

    // Host SBS depth-engine phase (Apollo extension): 0 = idle, 1 = loading, 2 = ready.
    void depthStatus(int phase);
    void sbsProfileList(String profiles);
}
