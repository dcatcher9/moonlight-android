package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.Space;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.ui.xrcontrols.XrControlPanelLayout;

import org.junit.Test;

/** Dock and side-panel poses from the quad geometry, with tracking unavailable. */
public final class XrPanelPoseSolverTest {
    private static final float EPSILON = 0.0001f;
    private static final float ASPECT = 16f / 9f;

    private SurfaceEntity video;
    private int tiles = 9;

    private XrPanelPoseSolver solver() {
        return new XrPanelPoseSolver(new XrPanelPoseSolver.Host() {
            @Override public Session session() {
                return null;
            }

            @Override public SurfaceEntity videoEntity() {
                return video;
            }

            @Override public float videoWidthMeters(float videoHeightMeters) {
                return videoHeightMeters * ASPECT;
            }

            @Override public int dockTileCount() {
                return tiles;
            }

            @Override public float modeOptionsHeightMeters() {
                return XrPanelPlacement.MODE_OPTIONS_MIN_HEIGHT_METERS;
            }
        });
    }

    @Test
    public void untrackedViewerStandsInFrontOfTheQuadAtItsRealWorldDistance() {
        video = mock(SurfaceEntity.class);
        when(video.getPose(Space.REAL_WORLD)).thenReturn(
                new Pose(new Vector3(0f, 0f, -3f), Quaternion.Identity));

        Vector3 viewer = solver().viewerPositionLocal();
        assertEquals(0f, viewer.getX(), EPSILON);
        assertEquals(3f, viewer.getZ(), EPSILON);

        Pose stats = solver().statsPose(2f);
        XrPanelPlacement.StatsPanelPlacement expected =
                XrPanelPlacement.calculateStatsPanelPlacement(2f * ASPECT,
                        XrStatsPanel.STATS_WIDTH_METERS, XrPanelPoseSolver.STATS_GAP_METERS,
                        0f, 3f);
        assertEquals(expected.centerX, stats.getTranslation().getX(), EPSILON);
        assertEquals(expected.centerZ, stats.getTranslation().getZ(), EPSILON);

        Pose settings = solver().sessionSettingsPose(2f);
        assertEquals(-expected.centerX, settings.getTranslation().getX(), EPSILON);
    }

    @Test
    public void missingVideoEntityUsesTheDefaultTwoMetreViewer() {
        video = null;
        assertEquals(2f, solver().viewerPositionLocal().getZ(), EPSILON);
    }

    @Test
    public void dockSitsLevelBeneathTheQuadAndWidensWithItsTiles() {
        Pose bar = solver().barPose(2f);
        assertEquals(-1f - XrPanelPoseSolver.BAR_GAP_METERS, bar.getTranslation().getY(),
                EPSILON);
        assertEquals(XrPanelPlacement.BAR_Z_METERS, bar.getTranslation().getZ(), EPSILON);
        assertEquals(Quaternion.Identity, bar.getRotation());

        tiles = 10;
        XrControlPanelLayout layout = solver().controlBarLayout(2f);
        assertEquals(10 * XrPanelPoseSolver.BAR_HEIGHT_METERS
                + XrPanelPoseSolver.BAR_DIVIDER_METERS, layout.widthMeters, EPSILON);
    }
}
