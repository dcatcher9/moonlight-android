package com.limelight.ui;

import androidx.xr.arcore.RenderViewpoint;
import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.Scene;
import androidx.xr.scenecore.ScenePose;
import androidx.xr.scenecore.SessionExt;
import androidx.xr.scenecore.Space;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.ui.xrcontrols.XrControlPanelLayout;

/**
 * Local poses of the panels parented to the video quad: the level dock, the contextual panel
 * pitched toward the viewer beneath it, and the Stats and App Settings side panels yawed toward
 * the viewer. The placement arithmetic lives in {@link XrPanelPlacement}; this class supplies the
 * dock geometry and the viewer position in the video entity's local space.
 *
 * <p>Main thread only, like the SceneCore calls it makes. It keeps no tracking state: the session,
 * the video entity and the quad's aspect are read through {@link Host} on every call, and head
 * pose is read only when a caller asks for a pose.</p>
 */
final class XrPanelPoseSolver {
    /** What the solver reads from the presenter on every call. */
    interface Host {
        Session session();

        SurfaceEntity videoEntity();

        /** Quad width for this height in the current presentation mode's aspect. */
        float videoWidthMeters(float videoHeightMeters);

        /** Tiles hosted in the dock row. */
        int dockTileCount();

        float modeOptionsHeightMeters();
    }

    // Dock tile geometry (meters), shared by build and reposition so the dock stays glued beneath
    // the quad as it changes size on a mode switch or a user resize.
    static final float BAR_HEIGHT_METERS = 0.21f;     // also the per-tile size (square tiles)
    static final float BAR_DIVIDER_METERS = 0.05f;    // extra width for the group divider
    static final float BAR_GAP_METERS = 0.24f;        // quad bottom -> bar center
    static final float MODE_OPTIONS_GAP_METERS = 0.02f;
    static final float STATS_GAP_METERS = 0.10f;
    static final float AUXILIARY_WIDTH_METERS = XrStatsPanel.STATS_WIDTH_METERS;
    static final float AUXILIARY_HEIGHT_METERS = 1.16f;

    private final Host host;

    XrPanelPoseSolver(Host host) {
        this.host = host;
    }

    XrControlPanelLayout controlBarLayout(float videoHeightMeters) {
        return XrControlPanelLayout.calculate(
                host.dockTileCount(), 1,
                BAR_HEIGHT_METERS, BAR_DIVIDER_METERS, videoHeightMeters, BAR_GAP_METERS);
    }

    /** Local pose of the unchanged, level mode-button panel. */
    Pose barPose(float videoHeightMeters) {
        XrControlPanelLayout layout = controlBarLayout(videoHeightMeters);
        return new Pose(new Vector3(0.0f, layout.primaryRowCenterY, XrPanelPlacement.BAR_Z_METERS),
                Quaternion.Identity);
    }

    /** Local pose of the independently pitched contextual subpanel. */
    Pose modeOptionsPose(float videoHeightMeters) {
        XrControlPanelLayout layout = controlBarLayout(videoHeightMeters);
        Vector3 viewer = viewerPositionLocal();
        XrPanelPlacement.ModeOptionsPanelPlacement placement =
                XrPanelPlacement.calculateModeOptionsPanelPlacement(
                        layout.primaryRowCenterY, BAR_HEIGHT_METERS,
                        host.modeOptionsHeightMeters(), MODE_OPTIONS_GAP_METERS,
                        XrPanelPlacement.BAR_Z_METERS, viewer.getY(), viewer.getZ());
        Quaternion rotation = Quaternion.fromAxisAngle(
                new Vector3(1.0f, 0.0f, 0.0f), placement.pitchDegrees);
        return new Pose(new Vector3(0.0f, placement.centerY, placement.centerZ), rotation);
    }

    /** Local pose of the right-side stats panel, dynamically yawed toward the viewer. */
    Pose statsPose(float videoHeightMeters) {
        Vector3 viewer = viewerPositionLocal();
        XrPanelPlacement.StatsPanelPlacement placement =
                XrPanelPlacement.calculateStatsPanelPlacement(
                        host.videoWidthMeters(videoHeightMeters),
                        XrStatsPanel.STATS_WIDTH_METERS, STATS_GAP_METERS,
                        viewer.getX(), viewer.getZ());
        Quaternion rotation = Quaternion.fromAxisAngle(
                new Vector3(0.0f, 1.0f, 0.0f), placement.yawDegrees);
        return new Pose(new Vector3(placement.centerX, placement.centerY, placement.centerZ),
                rotation);
    }

    /** App Settings sits to the video's left and tilts inward toward the viewer. */
    Pose sessionSettingsPose(float videoHeightMeters) {
        Vector3 viewer = viewerPositionLocal();
        XrPanelPlacement.StatsPanelPlacement placement =
                XrPanelPlacement.calculateLeftPanelPlacement(
                        host.videoWidthMeters(videoHeightMeters),
                        AUXILIARY_WIDTH_METERS, STATS_GAP_METERS, viewer.getX(), viewer.getZ());
        Quaternion rotation = Quaternion.fromAxisAngle(
                new Vector3(0.0f, 1.0f, 0.0f), placement.yawDegrees);
        return new Pose(new Vector3(placement.centerX, placement.centerY, placement.centerZ),
                rotation);
    }

    /**
     * Current head position in the video entity's local coordinates, or a point straight in front
     * of the quad at its real-world distance while tracking is unavailable.
     */
    Vector3 viewerPositionLocal() {
        Session session = host.session();
        SurfaceEntity video = host.videoEntity();
        if (session != null && video != null) {
            try {
                Pose headPose = currentHeadPose();
                if (headPose != null) {
                    Scene scene = SessionExt.getScene(session);
                    ScenePose headScenePose = scene.getPerceptionSpace()
                            .getScenePoseFromPerceptionPose(headPose);
                    Vector3 local = headScenePose.transformPositionTo(
                            new Vector3(0.0f, 0.0f, 0.0f), video);
                    if (Float.isFinite(local.getX()) && Float.isFinite(local.getZ())
                            && local.getZ() > 0.05f) {
                        return local;
                    }
                }
            } catch (Throwable ignored) {
                // Use the stable activity-space fallback below until tracking is ready again.
            }
        }

        float fallbackDistance = 2.0f;
        if (video != null) {
            try {
                Vector3 t = video.getPose(Space.REAL_WORLD).getTranslation();
                float distance = (float) Math.sqrt(t.getX() * t.getX()
                        + t.getY() * t.getY() + t.getZ() * t.getZ());
                if (Float.isFinite(distance) && distance > 0.2f) {
                    fallbackDistance = distance;
                }
            } catch (Throwable ignored) {
            }
        }
        return new Vector3(0.0f, 0.0f, fallbackDistance);
    }

    /** Midpoint head pose in perception space; stereo headsets generally expose no mono view. */
    Pose currentHeadPose() {
        Session session = host.session();
        Pose left = poseOf(RenderViewpoint.left(session));
        Pose right = poseOf(RenderViewpoint.right(session));
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        Vector3 lt = left.getTranslation();
        Vector3 rt = right.getTranslation();
        return new Pose(new Vector3((lt.getX() + rt.getX()) / 2.0f,
                (lt.getY() + rt.getY()) / 2.0f,
                (lt.getZ() + rt.getZ()) / 2.0f), left.getRotation());
    }

    /** Current pose of a render viewpoint (eye), or null if unavailable. */
    private static Pose poseOf(RenderViewpoint viewpoint) {
        if (viewpoint == null || viewpoint.getState() == null) {
            return null;
        }
        RenderViewpoint.State state = viewpoint.getState().getValue();
        return state != null ? state.getPose() : null;
    }

    /** Avoid identical native node transactions during the slow stats/position refresh. */
    static void updatePanelPose(PanelEntity panel, Pose pose) {
        if (panel == null || panel.isDisposed()) {
            return;
        }
        // SceneCore 1.0.0's PARENT pose is its cached local value; it does not query native
        // tracking. Read it instead of keeping another cache so reanchors or other callers
        // cannot leave this comparison stale. Preserve every actual pose change.
        if (!pose.equals(panel.getPose(Space.PARENT))) {
            panel.setPose(pose);
        }
    }
}
