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
                XrStatsFormat.formatDecoderLatencyMode(true, true));
        assertEquals("Regular component | LL options requested",
                XrStatsFormat.formatDecoderLatencyMode(false, true));
        assertEquals("Regular component | no LL options requested",
                XrStatsFormat.formatDecoderLatencyMode(false, false));
    }

    @Test
    public void gpuStageFormattingDoesNotPresentMissingSamplesAsZeroWork() {
        assertEquals("Waiting for completed timer sample",
                XrStatsFormat.formatGpuStage(Float.NaN, 0L, "warp"));
        assertEquals("1.25 ms | warp",
                XrStatsFormat.formatGpuStage(1.25f, 4L, "warp"));
    }

    @Test
    public void gpuLogDistinguishesMissingZeroAndShortCompletedQueries() {
        assertEquals("n/a", XrStatsFormat.formatGpuStageForLog(0.0f, 0L));
        assertEquals("n/a", XrStatsFormat.formatGpuStageForLog(1.25f, 0L));
        assertEquals("n/a", XrStatsFormat.formatGpuStageForLog(Float.NaN, 4L));
        assertEquals("n/a", XrStatsFormat.formatGpuStageForLog(Float.POSITIVE_INFINITY, 4L));
        assertEquals("n/a", XrStatsFormat.formatGpuStageForLog(-0.1f, 4L));
        assertEquals("0.000000", XrStatsFormat.formatGpuStageForLog(0.0f, 4L));
        assertEquals("0.000125", XrStatsFormat.formatGpuStageForLog(0.000125f, 4L));
        assertEquals("1.250000", XrStatsFormat.formatGpuStageForLog(1.25f, 4L));
    }

    @Test
    public void statsTitleNamesIndependentStreamAndClientSbsWindows() {
        assertEquals("Stats | Client AI 3D | stream 1.0 s | SBS 1.8 s",
                XrStatsFormat.formatStatsTitle(
                        PresentationMode.CLIENT_SBS_AI, 1.0f, 1.8f));
    }

    @Test
    public void statsTitleDoesNotClaimAnUnavailableSamplingWindow() {
        assertEquals("Stats | 2D | stream 1.0 s",
                XrStatsFormat.formatStatsTitle(
                        PresentationMode.NORMAL, 1.0f, Float.NaN));
        assertEquals("Stats | Host AI 3D",
                XrStatsFormat.formatStatsTitle(
                        PresentationMode.HOST_SBS_AI, 0.0f, 0.0f));
        assertEquals("Stats | Game 3D", XrStatsFormat.formatStatsTitle(
                PresentationMode.GAME_3D, 0.0f, 0.0f));
        assertEquals("Stats | Movie 3D", XrStatsFormat.formatStatsTitle(
                PresentationMode.MOVIE_3D, 0.0f, 0.0f));
    }

    @Test
    public void inwardYawKeepsInnerEdgeAnchoredBesideVideo() {
        float videoWidth = 3.56f;
        float panelWidth = 1.40f;
        float gap = 0.10f;
        XrPanelPlacement.StatsPanelPlacement placement =
                XrPanelPlacement.calculateStatsPanelPlacement(
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
        XrPanelPlacement.StatsPanelPlacement right =
                XrPanelPlacement.calculateStatsPanelPlacement(
                        videoWidth, panelWidth, gap, 0.0f, 2.0f);
        XrPanelPlacement.StatsPanelPlacement left =
                XrPanelPlacement.calculateLeftPanelPlacement(
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
        XrPanelPlacement.StatsPanelPlacement placement =
                XrPanelPlacement.calculateLeftPanelPlacement(
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
        XrPanelPlacement.StatsPanelPlacement narrow =
                XrPanelPlacement.calculateStatsPanelPlacement(
                        0.50f, 1.40f, 0.10f, 0.0f, 3.0f);
        XrPanelPlacement.StatsPanelPlacement wide =
                XrPanelPlacement.calculateStatsPanelPlacement(
                        1.50f, 1.40f, 0.10f, 0.0f, 3.0f);

        assertTrue(Math.abs(wide.yawDegrees) > Math.abs(narrow.yawDegrees));
        assertTrue(wide.innerEdgeX > narrow.innerEdgeX);
    }

    @Test
    public void closeViewerLimitsOuterEdgeForHeadClearance() {
        float panelWidth = 1.40f;
        float viewerZ = 1.0f;
        XrPanelPlacement.StatsPanelPlacement placement =
                XrPanelPlacement.calculateStatsPanelPlacement(
                        1.0f, panelWidth, 0.10f, 0.0f, viewerZ);
        double yaw = Math.toRadians(placement.yawDegrees);
        float outerZ = placement.innerEdgeZ
                - panelWidth * (float) Math.sin(yaw);

        assertTrue(outerZ <= viewerZ - 0.45f + EPSILON);
        assertTrue(placement.yawDegrees <= 0.0f);
    }

    @Test
    public void invalidViewerPoseUsesFiniteFallback() {
        XrPanelPlacement.StatsPanelPlacement placement =
                XrPanelPlacement.calculateStatsPanelPlacement(
                        3.56f, 1.40f, 0.10f, Float.NaN, Float.NaN);

        assertTrue(Float.isFinite(placement.centerX));
        assertTrue(Float.isFinite(placement.centerZ));
        assertTrue(Float.isFinite(placement.yawDegrees));
    }

    @Test
    public void rawHalfAspectAndFullAspectGetDifferentRightEdgeAnchors() {
        XrPanelPlacement.StatsPanelPlacement raw =
                XrPanelPlacement.calculateStatsPanelPlacement(
                        1.78f, 1.40f, 0.10f, 0.0f, 2.0f);
        XrPanelPlacement.StatsPanelPlacement full =
                XrPanelPlacement.calculateStatsPanelPlacement(
                        3.56f, 1.40f, 0.10f, 0.0f, 2.0f);

        assertEquals((3.56f - 1.78f) / 2.0f,
                full.innerEdgeX - raw.innerEdgeX, EPSILON);
    }

    @Test
    public void modeOptionsHeightExpandsAndShrinksWithinBounds() {
        assertEquals(500,
                XrPanelPlacement.calculateModeOptionsRasterHeightPixels(
                        500, 300, 0.52f, 0.90f));
        assertEquals(520,
                XrPanelPlacement.calculateModeOptionsRasterHeightPixels(
                        600, 300, 0.52f, 0.90f));
        assertEquals(300,
                XrPanelPlacement.calculateModeOptionsRasterHeightPixels(
                        200, 300, 0.52f, 0.90f));
        assertEquals(0.8667f,
                XrPanelPlacement.calculateModeOptionsHeightMeters(
                        0.52f, 300, 500,
                        0.52f, 0.90f),
                0.0001f);
        assertEquals(0.52f,
                XrPanelPlacement.calculateModeOptionsHeightMeters(
                        0.90f, 400, 200,
                        0.52f, 0.90f),
                0.0001f);
        assertEquals(0.90f,
                XrPanelPlacement.calculateModeOptionsHeightMeters(
                        0.52f, 200, 600,
                        0.52f, 0.90f),
                0.0001f);
    }

    @Test
    public void statsPanelGrowsWithItsRowsAndNeverShrinksBelowTheAuthoredSize() {
        // Reference pair is the authored panel: 1.05 m rendered into a 1440 px raster.
        float authored = 1.05f;
        int raster = 1440;
        int maxRaster = 2538;
        float min = 1.05f;
        float max = 1.85f;

        // A mode whose rows fit exactly keeps the authored height.
        assertEquals(raster,
                XrPanelPlacement.calculateStatsRasterHeightPixels(
                        raster, raster, maxRaster));
        assertEquals(authored,
                XrPanelPlacement.calculateModeOptionsHeightMeters(
                        authored, raster, raster, min, max),
                0.0001f);
        // Client SBS adds a depth section: both the Android raster and physical panel grow.
        assertEquals(2000,
                XrPanelPlacement.calculateStatsRasterHeightPixels(
                        2000, raster, maxRaster));
        assertEquals(1.4583f,
                XrPanelPlacement.calculateModeOptionsHeightMeters(
                        authored, raster, 2000, min, max),
                0.0001f);
        // Sparse content must not leave a sliver — the floor is the authored size.
        assertEquals(raster,
                XrPanelPlacement.calculateStatsRasterHeightPixels(
                        400, raster, maxRaster));
        assertEquals(min,
                XrPanelPlacement.calculateModeOptionsHeightMeters(
                        authored, raster, 400, min, max),
                0.0001f);
        // Past the deterministic pixel cap the panel stops growing and the ScrollView takes over.
        int cappedRaster = XrPanelPlacement.calculateStatsRasterHeightPixels(
                4000, raster, maxRaster);
        assertEquals(maxRaster, cappedRaster);
        assertEquals(max,
                XrPanelPlacement.calculateModeOptionsHeightMeters(
                        authored, raster, cappedRaster, min, max),
                0.0001f);
    }

    @Test
    public void modeOptionsPanelTiltsTowardFaceAndAnchorsBelowLevelControls() {
        float controlY = -1.24f;
        float controlHeight = 0.21f;
        float contextualHeight = 0.44f;
        float gap = 0.02f;
        float anchorZ = 0.02f;
        XrPanelPlacement.ModeOptionsPanelPlacement placement =
                XrPanelPlacement.calculateModeOptionsPanelPlacement(
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
        XrPanelPlacement.ModeOptionsPanelPlacement placement =
                XrPanelPlacement.calculateModeOptionsPanelPlacement(
                        -1.0f, 0.21f, 0.16f, 0.02f,
                        0.02f, Float.NaN, Float.NaN);

        assertTrue(Float.isFinite(placement.centerY));
        assertTrue(Float.isFinite(placement.centerZ));
        assertTrue(Float.isFinite(placement.pitchDegrees));
        assertTrue(placement.pitchDegrees < 0.0f);
    }

    @Test
    public void dockAutoCollapseAllowsOnlyFullyIdleState() {
        assertTrue(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, false, false, false, false));
    }

    @Test
    public void dockAutoCollapseIsBlockedByEveryUnsafeState() {
        assertFalse(XrDockController.shouldAutoCollapseDock(
                false, true, XrControlUiState.Surface.NONE,
                false, false, false, false, false, false, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, false, XrControlUiState.Surface.NONE,
                false, false, false, false, false, false, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.MODE_OPTIONS,
                false, false, false, false, false, false, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.SESSION_SETTINGS,
                false, false, false, false, false, false, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                true, false, false, false, false, false, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, true, false, false, false, false, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, true, false, false, false, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, true, false, false, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, false, true, false, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, false, false, true, false));
        assertFalse(XrDockController.shouldAutoCollapseDock(
                true, true, XrControlUiState.Surface.NONE,
                false, false, false, false, false, false, true));
    }

    @Test
    public void collapsedDockRevealsOnFirstPressOrClickWhileFocusAndHoverStayOnPill() {
        assertTrue(XrDockController.shouldRevealCollapsedDock(
                XrDockController.DockRevealInteraction.EXPLICIT_CLICK));
        assertTrue(XrDockController.shouldRevealCollapsedDock(
                XrDockController.DockRevealInteraction.PRESS_DOWN));
        assertFalse(XrDockController.shouldRevealCollapsedDock(
                XrDockController.DockRevealInteraction.HOVER));
        assertFalse(XrDockController.shouldRevealCollapsedDock(
                XrDockController.DockRevealInteraction.FOCUS));
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
    public void controlToggleIgnoresDuplicateClickFromOnePhysicalTap() {
        assertTrue(XrStreamPresenter.shouldAcceptControlToggle(1000L, 0L));
        assertFalse(XrStreamPresenter.shouldAcceptControlToggle(1200L, 1000L));
        assertTrue(XrStreamPresenter.shouldAcceptControlToggle(1400L, 1000L));
    }

    @Test
    public void sharedSessionSettingsUseTwoSemanticColumns() {
        assertEquals(0, XrSessionSettingsPane.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.HDR));
        assertEquals(0, XrSessionSettingsPane.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.VIDEO_RANGE));
        assertEquals(0, XrSessionSettingsPane.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.CODEC));
        assertEquals(1, XrSessionSettingsPane.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.FRAME_PACING));
        assertEquals(1, XrSessionSettingsPane.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.AUDIO_LAYOUT));
        assertEquals(1, XrSessionSettingsPane.sharedSettingColumn(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.PLAY_AUDIO_ON_PC));
    }
}
