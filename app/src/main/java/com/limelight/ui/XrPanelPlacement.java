package com.limelight.ui;

import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Vector3;

/**
 * Pure spatial-layout geometry for the XR control panels: where the mode-options row and the
 * side panels sit relative to the video quad, and how their fitted heights are clamped.
 */
final class XrPanelPlacement {
    private XrPanelPlacement() {
    }

    /** Nearest the user may drag the panel toward the eyes, in meters from the activity-space origin. */
    static final float MIN_PANEL_DISTANCE_METERS = 0.4f;

    /** Taller independent pane fits per-mode quality controls without moving the level dock. */
    static final float MODE_OPTIONS_MIN_HEIGHT_METERS = 0.52f;

    static final float BAR_Z_METERS = 0.02f;          // nudge toward viewer vs. the quad

    static final float MODE_OPTIONS_MIN_TILT_DEGREES = 10.0f;
    static final float MODE_OPTIONS_MAX_TILT_DEGREES = 30.0f;
    static final float STATS_MIN_INWARD_YAW_DEGREES = 8.0f;
    static final float STATS_MAX_INWARD_YAW_DEGREES = 50.0f;
    static final float STATS_HEAD_CLEARANCE_METERS = 0.45f;

    static int calculateStatsRasterHeightPixels(int contentHeightPixels,
                                                int minHeightPixels,
                                                int maxHeightPixels) {
        int minHeight = Math.max(1, minHeightPixels);
        int maxHeight = Math.max(minHeight, maxHeightPixels);
        int contentHeight = Math.max(0, contentHeightPixels);
        return Math.max(minHeight, Math.min(maxHeight, contentHeight));
    }

    /** SceneCore applies entity scale after local size; invert it to preserve authored metres. */
    static float statsEntityLocalMeters(float worldMeters, float entityScale) {
        if (!Float.isFinite(worldMeters) || worldMeters < 0.0f
                || !Float.isFinite(entityScale) || entityScale <= 0.0f) {
            return 0.0f;
        }
        return worldMeters / entityScale;
    }

    static final class ModeOptionsPanelPlacement {
        final float centerY;
        final float centerZ;
        final float pitchDegrees;

        ModeOptionsPanelPlacement(float centerY, float centerZ, float pitchDegrees) {
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.pitchDegrees = pitchDegrees;
        }
    }

    /**
     * Aim only the contextual subpanel toward the viewer. Its top edge remains anchored beneath
     * the level button row, so opening it cannot move or rotate the primary controls.
     */
    static ModeOptionsPanelPlacement calculateModeOptionsPanelPlacement(
            float controlRowCenterY, float controlRowHeight, float optionsRowHeight,
            float rowGap, float anchorZ, float viewerY, float viewerZ) {
        float safeViewerY = Float.isFinite(viewerY) ? viewerY : 0.0f;
        float safeViewerZ = Float.isFinite(viewerZ) && viewerZ > anchorZ + 0.1f
                ? viewerZ : 2.0f;
        float topAnchorY = controlRowCenterY
                - Math.max(0.0f, controlRowHeight) / 2.0f - Math.max(0.0f, rowGap);
        float unrotatedCenterY = topAnchorY - Math.max(0.0f, optionsRowHeight) / 2.0f;
        float verticalDistance = Math.max(0.0f, safeViewerY - unrotatedCenterY);
        float forwardDistance = Math.max(0.1f, safeViewerZ - anchorZ);
        float requestedTilt = (float) Math.toDegrees(Math.atan2(
                verticalDistance, forwardDistance));
        float pitchDegrees = -Math.max(MODE_OPTIONS_MIN_TILT_DEGREES,
                Math.min(MODE_OPTIONS_MAX_TILT_DEGREES, requestedTilt));

        // Anchor the subpanel's local top-center after rotation. Negative X pitch points its normal
        // upward toward the viewer and brings its lower edge slightly closer to the face.
        float offsetY = Math.max(0.0f, optionsRowHeight) / 2.0f;
        double pitchRadians = Math.toRadians(pitchDegrees);
        float rotatedOffsetY = offsetY * (float) Math.cos(pitchRadians);
        float rotatedOffsetZ = offsetY * (float) Math.sin(pitchRadians);
        return new ModeOptionsPanelPlacement(topAnchorY - rotatedOffsetY,
                anchorZ - rotatedOffsetZ, pitchDegrees);
    }

    static float calculateModeOptionsHeightMeters(float currentHeightMeters,
                                                 int currentHeightPixels,
                                                 int contentHeightPixels,
                                                 float minHeightMeters,
                                                 float maxHeightMeters) {
        float safeCurrentHeightMeters = Float.isFinite(currentHeightMeters)
                ? Math.max(0.0f, currentHeightMeters) : MODE_OPTIONS_MIN_HEIGHT_METERS;
        int safeCurrentHeightPx = Math.max(1, currentHeightPixels);
        int safeContentHeightPx = Math.max(0, contentHeightPixels);
        float minHeight = Math.max(0.0f, minHeightMeters);
        float maxHeight = Math.max(minHeight, maxHeightMeters);
        if (safeContentHeightPx <= 0) {
            return minHeight;
        }
        float targetHeight = safeCurrentHeightMeters * (float) safeContentHeightPx
                / (float) safeCurrentHeightPx;
        if (!Float.isFinite(targetHeight)) {
            return minHeight;
        }
        return Math.max(minHeight, Math.min(maxHeight, targetHeight));
    }

    static int calculateModeOptionsRasterHeightPixels(int contentHeightPixels,
                                                      int baseHeightPixels,
                                                      float minHeightMeters,
                                                      float maxHeightMeters) {
        int baseHeight = Math.max(1, baseHeightPixels);
        if (!Float.isFinite(minHeightMeters) || minHeightMeters <= 0.0f
                || !Float.isFinite(maxHeightMeters)) {
            return baseHeight;
        }
        float boundedMaxHeight = Math.max(minHeightMeters, maxHeightMeters);
        double scaledMaximum = Math.ceil(baseHeight * (double) boundedMaxHeight
                / (double) minHeightMeters);
        int maxHeightPixels = scaledMaximum >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE : Math.max(baseHeight, (int) scaledMaximum);
        return calculateStatsRasterHeightPixels(
                contentHeightPixels, baseHeight, maxHeightPixels);
    }

    static final class StatsPanelPlacement {
        final float innerEdgeX;
        final float innerEdgeZ;
        final float centerX;
        final float centerY;
        final float centerZ;
        final float yawDegrees;

        StatsPanelPlacement(float innerEdgeX, float innerEdgeZ, float centerX,
                            float centerY, float centerZ, float yawDegrees) {
            this.innerEdgeX = innerEdgeX;
            this.innerEdgeZ = innerEdgeZ;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.yawDegrees = yawDegrees;
        }
    }

    /**
     * Pure right-side placement geometry. The panel's inner (local -X) edge remains anchored just
     * outside the video while a negative Y yaw wraps its outer edge toward the viewer. The yaw is
     * solved from the viewer position in video-local space and limited to preserve head clearance.
     */
    static StatsPanelPlacement calculateStatsPanelPlacement(float videoWidthMeters,
                                                             float panelWidthMeters,
                                                             float gapMeters,
                                                             float viewerX,
                                                             float viewerZ) {
        float safeVideoWidth = Float.isFinite(videoWidthMeters)
                ? Math.max(0.0f, videoWidthMeters) : 0.0f;
        float safePanelWidth = Float.isFinite(panelWidthMeters)
                ? Math.max(0.0f, panelWidthMeters) : 0.0f;
        float safeGap = Float.isFinite(gapMeters) ? Math.max(0.0f, gapMeters) : 0.0f;
        float safeViewerX = Float.isFinite(viewerX) ? viewerX : 0.0f;
        float safeViewerZ = Float.isFinite(viewerZ)
                && viewerZ > STATS_HEAD_CLEARANCE_METERS + BAR_Z_METERS
                ? viewerZ : 2.0f;

        float innerEdgeX = safeVideoWidth / 2.0f + safeGap;
        float innerEdgeZ = BAR_Z_METERS;
        float halfWidth = safePanelWidth / 2.0f;
        float yawDegrees = 0.0f;

        if (safePanelWidth > 0.0f) {
            float maxForward = Math.max(0.0f,
                    safeViewerZ - STATS_HEAD_CLEARANCE_METERS - innerEdgeZ);
            float clearanceRatio = Math.min(1.0f, maxForward / safePanelWidth);
            float clearanceYaw = (float) Math.toDegrees(Math.asin(clearanceRatio));
            float maxYaw = Math.min(STATS_MAX_INWARD_YAW_DEGREES, clearanceYaw);
            float minYaw = Math.min(STATS_MIN_INWARD_YAW_DEGREES, maxYaw);

            // Center depends on yaw because the inner edge is fixed. A few fixed-point iterations
            // are sufficient and run only on slow UI/pose events, never in the video frame loop.
            for (int i = 0; i < 3; i++) {
                double yawRadians = Math.toRadians(yawDegrees);
                float centerX = innerEdgeX + halfWidth * (float) Math.cos(yawRadians);
                float centerZ = innerEdgeZ - halfWidth * (float) Math.sin(yawRadians);
                float desiredYaw = (float) -Math.toDegrees(Math.atan2(
                        safeViewerX - centerX, safeViewerZ - centerZ));
                float inwardMagnitude = Math.max(minYaw, Math.min(maxYaw, desiredYaw));
                yawDegrees = -inwardMagnitude;
            }
        }

        double yawRadians = Math.toRadians(yawDegrees);
        float centerX = innerEdgeX + halfWidth * (float) Math.cos(yawRadians);
        float centerZ = innerEdgeZ - halfWidth * (float) Math.sin(yawRadians);
        return new StatsPanelPlacement(innerEdgeX, innerEdgeZ, centerX, 0.0f,
                centerZ, yawDegrees);
    }

    /**
     * Pure left-side mirror of {@link #calculateStatsPanelPlacement}. The panel's inner (local +X)
     * edge remains anchored outside the video and positive Y yaw wraps its outer edge inward.
     */
    static StatsPanelPlacement calculateLeftPanelPlacement(float videoWidthMeters,
                                                            float panelWidthMeters,
                                                            float gapMeters,
                                                            float viewerX,
                                                            float viewerZ) {
        StatsPanelPlacement right = calculateStatsPanelPlacement(
                videoWidthMeters, panelWidthMeters, gapMeters, -viewerX, viewerZ);
        return new StatsPanelPlacement(-right.innerEdgeX, right.innerEdgeZ,
                -right.centerX, right.centerY, right.centerZ, -right.yawDegrees);
    }

    /**
     * Clamp a proposed panel pose so it can't be dragged closer than {@link #MIN_PANEL_DISTANCE_METERS}
     * from the activity-space origin (≈ the initial head position), preserving direction and rotation.
     */
    static Pose clampToMinDistance(Pose pose) {
        Vector3 t = pose.getTranslation();
        float len = t.getLength();
        if (len >= MIN_PANEL_DISTANCE_METERS) {
            return pose;
        }
        if (len < 1e-4f) {
            // Degenerate (panel essentially at the origin) — push straight forward.
            return new Pose(new Vector3(0f, 0f, -MIN_PANEL_DISTANCE_METERS), pose.getRotation());
        }
        float s = MIN_PANEL_DISTANCE_METERS / len;
        Vector3 clamped = new Vector3(t.getX() * s, t.getY() * s, t.getZ() * s);
        return new Pose(clamped, pose.getRotation());
    }
}
