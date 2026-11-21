package com.limelight.nvstream.http;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The details summary decodes Apollo's action bits: list, view, and launch (which implies view). */
public final class ComputerDetailsPermissionTest {
    private static final int LIST = 0x01000000;
    private static final int VIEW = 0x02000000;
    private static final int LAUNCH = 0x04000000;

    @Test public void listOnlyGrantsNeitherViewNorLaunch() {
        String summary = summary(LIST);
        assertTrue(summary, summary.contains(" - List Apps: true"));
        assertTrue(summary, summary.contains(" - View Streams: false"));
        assertTrue(summary, summary.contains(" - Launch Apps: false"));
    }

    @Test public void viewGrantsViewOnly() {
        String summary = summary(VIEW);
        assertTrue(summary, summary.contains(" - View Streams: true"));
        assertTrue(summary, summary.contains(" - Launch Apps: false"));
    }

    @Test public void launchImpliesView() {
        String summary = summary(LAUNCH);
        assertTrue(summary, summary.contains(" - List Apps: false"));
        assertTrue(summary, summary.contains(" - View Streams: true"));
        assertTrue(summary, summary.contains(" - Launch Apps: true"));
    }

    private static String summary(int permission) {
        ComputerDetails details = new ComputerDetails();
        details.permission = permission;
        return details.toString();
    }
}
