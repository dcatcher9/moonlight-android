package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class XrControlPanelLayoutTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void buttonPanelContainsOnlyTheLevelPrimaryRow() {
        XrControlPanelLayout layout = XrControlPanelLayout.calculate(
                9, 1, 0.21f, 0.05f, 2.0f, 0.24f);

        assertEquals(layout.primaryRowCenterY, layout.panelCenterY, EPSILON);
        assertEquals(0.21f, layout.heightMeters, EPSILON);
    }

    @Test
    public void fiveModeDockAndDirectActionsKeepTheirPhysicalTileSize() {
        XrControlPanelLayout layout = XrControlPanelLayout.calculate(
                9, 1, 0.21f, 0.05f, 2.0f, 0.24f);

        assertEquals(1.94f, layout.widthMeters, EPSILON);
    }

    @Test
    public void debugDumpKeepsEveryTileAtItsPhysicalSize() {
        XrControlPanelLayout layout = XrControlPanelLayout.calculate(
                10, 1, 0.21f, 0.05f, 2.0f, 0.24f);

        assertEquals(2.15f, layout.widthMeters, EPSILON);
        assertEquals(0.21f, layout.heightMeters, EPSILON);
    }
}
