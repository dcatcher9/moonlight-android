package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class XrControlPanelLayoutTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void buttonPanelContainsOnlyTheLevelPrimaryRow() {
        XrControlPanelLayout layout = XrControlPanelLayout.calculate(
                8, 1, 0.21f, 0.05f, 2.0f, 0.24f);

        assertEquals(layout.primaryRowCenterY, layout.panelCenterY, EPSILON);
        assertEquals(0.21f, layout.heightMeters, EPSILON);
    }

    @Test
    public void widthKeepsEveryPrimaryTileAtItsPhysicalSize() {
        XrControlPanelLayout layout = XrControlPanelLayout.calculate(
                8, 1, 0.21f, 0.05f, 2.0f, 0.24f);

        assertEquals(1.73f, layout.widthMeters, EPSILON);
    }

    @Test
    public void compactUtilityActionUsesHalfATileWithoutSqueezingItsNeighbors() {
        XrControlPanelLayout layout = XrControlPanelLayout.calculate(
                8.5f, 1, 0.21f, 0.05f, 2.0f, 0.24f);

        assertEquals(1.835f, layout.widthMeters, EPSILON);
        assertEquals(0.21f, layout.heightMeters, EPSILON);
    }
}
