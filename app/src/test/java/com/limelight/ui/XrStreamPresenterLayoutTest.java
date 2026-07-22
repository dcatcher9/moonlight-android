package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XrStreamPresenterLayoutTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void statsTitleNamesIndependentStreamAndClientSbsWindows() {
        assertEquals("Stats | Client SBS AI | stream 1.0 s | SBS 1.8 s",
                XrStreamPresenter.formatStatsTitle(
                        XrStreamPresenter.PresenterMode.CLIENT_SBS_AI, 1.0f, 1.8f));
    }

    @Test
    public void statsTitleDoesNotClaimAnUnavailableSamplingWindow() {
        assertEquals("Stats | Normal | stream 1.0 s",
                XrStreamPresenter.formatStatsTitle(
                        XrStreamPresenter.PresenterMode.NORMAL, 1.0f, Float.NaN));
        assertEquals("Stats | Host SBS AI",
                XrStreamPresenter.formatStatsTitle(
                        XrStreamPresenter.PresenterMode.HOST_SBS_AI, 0.0f, 0.0f));
    }

    @Test
    public void inwardYawKeepsInnerEdgeAnchoredBesideVideo() {
        float videoWidth = 3.56f;
        float panelWidth = 1.40f;
        float gap = 0.10f;
        XrStreamPresenter.StatsPanelPlacement placement =
                XrStreamPresenter.calculateStatsPanelPlacement(
                        videoWidth, panelWidth, gap, 0.0f, 2.0f);

        float halfWidth = panelWidth / 2.0f;
        double yaw = Math.toRadians(placement.yawDegrees);
        float transformedInnerX = placement.centerX
                - halfWidth * (float) Math.cos(yaw);
        float transformedInnerZ = placement.centerZ
                + halfWidth * (float) Math.sin(yaw);
        float transformedOuterZ = placement.centerZ
                - halfWidth * (float) Math.sin(yaw);

        assertEquals(videoWidth / 2.0f + gap, placement.innerEdgeX, EPSILON);
        assertEquals(placement.innerEdgeX, transformedInnerX, EPSILON);
        assertEquals(placement.innerEdgeZ, transformedInnerZ, EPSILON);
        assertTrue(placement.yawDegrees < 0.0f);
        assertTrue(transformedOuterZ > placement.innerEdgeZ);
    }

    @Test
    public void widerVideoDynamicallyIncreasesInwardYaw() {
        XrStreamPresenter.StatsPanelPlacement narrow =
                XrStreamPresenter.calculateStatsPanelPlacement(
                        0.50f, 1.40f, 0.10f, 0.0f, 3.0f);
        XrStreamPresenter.StatsPanelPlacement wide =
                XrStreamPresenter.calculateStatsPanelPlacement(
                        1.50f, 1.40f, 0.10f, 0.0f, 3.0f);

        assertTrue(Math.abs(wide.yawDegrees) > Math.abs(narrow.yawDegrees));
        assertTrue(wide.innerEdgeX > narrow.innerEdgeX);
    }

    @Test
    public void closeViewerLimitsOuterEdgeForHeadClearance() {
        float panelWidth = 1.40f;
        float viewerZ = 1.0f;
        XrStreamPresenter.StatsPanelPlacement placement =
                XrStreamPresenter.calculateStatsPanelPlacement(
                        1.0f, panelWidth, 0.10f, 0.0f, viewerZ);
        double yaw = Math.toRadians(placement.yawDegrees);
        float outerZ = placement.innerEdgeZ
                - panelWidth * (float) Math.sin(yaw);

        assertTrue(outerZ <= viewerZ - 0.45f + EPSILON);
        assertTrue(placement.yawDegrees <= 0.0f);
    }

    @Test
    public void invalidViewerPoseUsesFiniteFallback() {
        XrStreamPresenter.StatsPanelPlacement placement =
                XrStreamPresenter.calculateStatsPanelPlacement(
                        3.56f, 1.40f, 0.10f, Float.NaN, Float.NaN);

        assertTrue(Float.isFinite(placement.centerX));
        assertTrue(Float.isFinite(placement.centerZ));
        assertTrue(Float.isFinite(placement.yawDegrees));
    }

    @Test
    public void rawHalfAspectAndFullAspectGetDifferentRightEdgeAnchors() {
        XrStreamPresenter.StatsPanelPlacement raw =
                XrStreamPresenter.calculateStatsPanelPlacement(
                        1.78f, 1.40f, 0.10f, 0.0f, 2.0f);
        XrStreamPresenter.StatsPanelPlacement full =
                XrStreamPresenter.calculateStatsPanelPlacement(
                        3.56f, 1.40f, 0.10f, 0.0f, 2.0f);

        assertEquals((3.56f - 1.78f) / 2.0f,
                full.innerEdgeX - raw.innerEdgeX, EPSILON);
    }
}
