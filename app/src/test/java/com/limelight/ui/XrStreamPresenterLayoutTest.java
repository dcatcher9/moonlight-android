package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.ui.xrcontrols.XrControlUiState;

import org.junit.Test;

public class XrStreamPresenterLayoutTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void decoderLatencyDescriptionSeparatesComponentFromRequestedMode() {
        assertEquals("Dedicated low-latency component | LL options requested",
                XrStreamPresenter.formatDecoderLatencyMode(true, true));
        assertEquals("Regular component | LL options requested",
                XrStreamPresenter.formatDecoderLatencyMode(false, true));
        assertEquals("Regular component | no LL options requested",
                XrStreamPresenter.formatDecoderLatencyMode(false, false));
    }

    @Test
    public void gpuStageFormattingDoesNotPresentMissingSamplesAsZeroWork() {
        assertEquals("Waiting for completed timer sample",
                XrStreamPresenter.formatGpuStage(Float.NaN, 0L, "warp"));
        assertEquals("1.25 ms | warp",
                XrStreamPresenter.formatGpuStage(1.25f, 4L, "warp"));
    }

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
    public void leftSessionPaneMirrorsRightPlacementAndTiltsInward() {
        float videoWidth = 3.56f;
        float panelWidth = 1.40f;
        float gap = 0.10f;
        XrStreamPresenter.StatsPanelPlacement right =
                XrStreamPresenter.calculateStatsPanelPlacement(
                        videoWidth, panelWidth, gap, 0.0f, 2.0f);
        XrStreamPresenter.StatsPanelPlacement left =
                XrStreamPresenter.calculateLeftPanelPlacement(
                        videoWidth, panelWidth, gap, 0.0f, 2.0f);

        assertEquals(-right.innerEdgeX, left.innerEdgeX, EPSILON);
        assertEquals(-right.centerX, left.centerX, EPSILON);
        assertEquals(right.centerZ, left.centerZ, EPSILON);
        assertEquals(-right.yawDegrees, left.yawDegrees, EPSILON);
        assertTrue(left.yawDegrees > 0.0f);
    }

    @Test
    public void leftSessionPaneKeepsInnerEdgeAnchoredWhileOuterEdgeWrapsForward() {
        float videoWidth = 3.56f;
        float panelWidth = 1.40f;
        float gap = 0.10f;
        XrStreamPresenter.StatsPanelPlacement placement =
                XrStreamPresenter.calculateLeftPanelPlacement(
                        videoWidth, panelWidth, gap, 0.0f, 2.0f);

        float halfWidth = panelWidth / 2.0f;
        double yaw = Math.toRadians(placement.yawDegrees);
        float transformedInnerX = placement.centerX
                + halfWidth * (float) Math.cos(yaw);
        float transformedInnerZ = placement.centerZ
                - halfWidth * (float) Math.sin(yaw);
        float transformedOuterZ = placement.centerZ
                + halfWidth * (float) Math.sin(yaw);

        assertEquals(-videoWidth / 2.0f - gap, placement.innerEdgeX, EPSILON);
        assertEquals(placement.innerEdgeX, transformedInnerX, EPSILON);
        assertEquals(placement.innerEdgeZ, transformedInnerZ, EPSILON);
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

    @Test
    public void modeOptionsPanelTiltsTowardFaceAndAnchorsBelowLevelControls() {
        float controlY = -1.24f;
        float controlHeight = 0.21f;
        float contextualHeight = 0.44f;
        float gap = 0.02f;
        float anchorZ = 0.02f;
        XrStreamPresenter.ModeOptionsPanelPlacement placement =
                XrStreamPresenter.calculateModeOptionsPanelPlacement(
                        controlY, controlHeight, contextualHeight, gap,
                        anchorZ, 0.0f, 2.0f);

        assertTrue(placement.pitchDegrees < 0.0f);
        assertTrue(placement.pitchDegrees >= -30.0f);
        double pitch = Math.toRadians(placement.pitchDegrees);
        float offset = contextualHeight / 2.0f;
        float transformedTopY = placement.centerY
                + offset * (float) Math.cos(pitch);
        float transformedTopZ = placement.centerZ
                + offset * (float) Math.sin(pitch);
        assertEquals(controlY - controlHeight / 2.0f - gap,
                transformedTopY, EPSILON);
        assertEquals(anchorZ, transformedTopZ, EPSILON);
        assertTrue(-(float) Math.sin(pitch) > 0.0f);
    }

    @Test
    public void invalidModeOptionsViewerPoseUsesFiniteTiltedFallback() {
        XrStreamPresenter.ModeOptionsPanelPlacement placement =
                XrStreamPresenter.calculateModeOptionsPanelPlacement(
                        -1.0f, 0.21f, 0.16f, 0.02f,
                        0.02f, Float.NaN, Float.NaN);

        assertTrue(Float.isFinite(placement.centerY));
        assertTrue(Float.isFinite(placement.centerZ));
        assertTrue(Float.isFinite(placement.pitchDegrees));
        assertTrue(placement.pitchDegrees < 0.0f);
    }

    @Test
    public void dockAutoCollapseAllowsOnlyFullyIdleState() {
        assertTrue(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, false, false, false, false));
    }

    @Test
    public void dockAutoCollapseIsBlockedByEveryUnsafeState() {
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                false, true, XrControlUiState.Surface.NONE,
                false, false, false, false, false, false, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, false, XrControlUiState.Surface.NONE,
                false, false, false, false, false, false, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.MODE_OPTIONS,
                false, false, false, false, false, false, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.SESSION_SETTINGS,
                false, false, false, false, false, false, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                true, false, false, false, false, false, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, true, false, false, false, false, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, true, false, false, false, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, true, false, false, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, false, true, false, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, false, false, true, false));
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, false, false, false, true));
    }

    @Test
    public void collapsedDockRevealPillRespondsToFirstExplicitInteraction() {
        assertTrue(XrStreamPresenter.shouldRevealCollapsedDock(
                XrStreamPresenter.DockRevealInteraction.EXPLICIT_CLICK));
        assertTrue(XrStreamPresenter.shouldRevealCollapsedDock(
                XrStreamPresenter.DockRevealInteraction.PRESS_DOWN));
        assertFalse(XrStreamPresenter.shouldRevealCollapsedDock(
                XrStreamPresenter.DockRevealInteraction.HOVER));
        assertTrue(XrStreamPresenter.shouldRevealCollapsedDock(
                XrStreamPresenter.DockRevealInteraction.FOCUS));
    }

    @Test
    public void statsDockTogglesThePersistentStatsChoice() {
        XrControlUiState state = new XrControlUiState();

        XrStreamPresenter.applyStatsDockAction(state);
        assertTrue(state.isStatsVisible());

        XrStreamPresenter.applyStatsDockAction(state);
        assertFalse(state.isStatsVisible());
    }

    @Test
    public void expandedSessionToolsBlockSoftCollapse() {
        assertFalse(XrStreamPresenter.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, false, false, false, false,
                true));
    }

    @Test
    public void inlineSessionToolsExpandBarWithoutSqueezingPrimaryTiles() {
        assertEquals(8.5f, XrStreamPresenter.controlBarTileUnits(false), EPSILON);
        assertEquals(10.5f, XrStreamPresenter.controlBarTileUnits(true), EPSILON);

        com.limelight.ui.xrcontrols.XrControlPanelLayout compact =
                com.limelight.ui.xrcontrols.XrControlPanelLayout.calculate(
                        XrStreamPresenter.controlBarTileUnits(false),
                        1, 0.21f, 0.05f, 2.0f, 0.24f);
        com.limelight.ui.xrcontrols.XrControlPanelLayout expanded =
                com.limelight.ui.xrcontrols.XrControlPanelLayout.calculate(
                        XrStreamPresenter.controlBarTileUnits(true),
                        1, 0.21f, 0.05f, 2.0f, 0.24f);

        assertEquals(1.835f, compact.widthMeters, EPSILON);
        assertEquals(2.255f, expanded.widthMeters, EPSILON);
        assertEquals(compact.heightMeters, expanded.heightMeters, EPSILON);

        float compactCenter = XrStreamPresenter.controlBarCenterX(
                false, compact.widthMeters, expanded.widthMeters);
        float expandedCenter = XrStreamPresenter.controlBarCenterX(
                true, compact.widthMeters, expanded.widthMeters);
        float compactRightEdge = compactCenter + compact.widthMeters / 2.0f;
        float expandedRightEdge = expandedCenter + expanded.widthMeters / 2.0f;
        float compactToggleCenter = compactRightEdge - 0.25f * 0.21f;
        float expandedToggleCenter = expandedRightEdge - 0.25f * 0.21f;

        assertEquals(compactRightEdge, expandedRightEdge, EPSILON);
        assertEquals(compactToggleCenter, expandedToggleCenter, EPSILON);
    }

    @Test
    public void dockExpansionIgnoresDuplicateClickFromOnePhysicalTap() {
        assertTrue(XrStreamPresenter.shouldAcceptControlToggle(1000L, 0L));
        assertFalse(XrStreamPresenter.shouldAcceptControlToggle(1200L, 1000L));
        assertTrue(XrStreamPresenter.shouldAcceptControlToggle(1400L, 1000L));
    }

    @Test
    public void sharedSessionSettingsUseTwoSemanticColumns() {
        assertEquals(0, XrStreamPresenter.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.HDR));
        assertEquals(0, XrStreamPresenter.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.VIDEO_RANGE));
        assertEquals(0, XrStreamPresenter.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.CODEC));
        assertEquals(1, XrStreamPresenter.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.FRAME_PACING));
        assertEquals(1, XrStreamPresenter.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.AUDIO_LAYOUT));
        assertEquals(1, XrStreamPresenter.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.PLAY_AUDIO_ON_PC));
    }
}
