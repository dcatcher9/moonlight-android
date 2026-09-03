package com.limelight.utils;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerHelperTest {
    @Test
    public void matchingNonZeroAppIdIsResume() {
        ComputerDetails computer = computerWithRunningApp(42, null);
        NvApp app = new NvApp("Desktop", null, 42, false);

        assertTrue(ServerHelper.isResumeOfSameHostApp(computer, app));
    }

    @Test
    public void matchingUuidIsResumeEvenWhenIdsDiffer() {
        ComputerDetails computer = computerWithRunningApp(7, "APP-UUID");
        NvApp app = new NvApp("Desktop", "app-uuid", 42, false);

        assertTrue(ServerHelper.isResumeOfSameHostApp(computer, app));
    }

    @Test
    public void noRunningAppDoesNotMatchDefaultValues() {
        ComputerDetails computer = computerWithRunningApp(0, null);
        NvApp app = new NvApp("Desktop", null, 0, false);

        assertFalse(ServerHelper.isResumeOfSameHostApp(computer, app));
    }

    @Test
    public void differentRunningAppIsFreshConnection() {
        ComputerDetails computer = computerWithRunningApp(7, "other-uuid");
        NvApp app = new NvApp("Desktop", "app-uuid", 42, false);

        assertFalse(ServerHelper.isResumeOfSameHostApp(computer, app));
    }

    @Test
    public void matchingIdCannotOverrideConflictingUuid() {
        ComputerDetails computer = computerWithRunningApp(42, "host-uuid");
        NvApp app = new NvApp("Desktop", "other-uuid", 42, false);

        assertFalse(ServerHelper.isResumeOfSameHostApp(computer, app));
    }

    @Test
    public void tokenCapableHostCannotResumeWithoutSessionToken() {
        ComputerDetails computer = computerWithRunningApp(42, null);
        computer.hostSessionId = null;
        NvApp app = new NvApp("Desktop", null, 42, false);

        assertFalse(ServerHelper.isResumeOfSameHostApp(computer, app));
    }

    @Test
    public void legacyHostCanResumeMatchingAppWithoutSessionToken() {
        ComputerDetails computer = computerWithRunningApp(42, null);
        computer.hostSessionIdSupported = false;
        computer.hostSessionId = null;
        NvApp app = new NvApp("Desktop", null, 42, false);

        assertTrue(ServerHelper.isResumeOfSameHostApp(computer, app));
    }

    private static ComputerDetails computerWithRunningApp(int appId, String appUuid) {
        ComputerDetails computer = new ComputerDetails();
        computer.runningGameId = appId;
        computer.runningGameUUID = appUuid;
        computer.hostSessionIdSupported = true;
        computer.hostSessionId = "18446744073709551615";
        return computer;
    }
}
