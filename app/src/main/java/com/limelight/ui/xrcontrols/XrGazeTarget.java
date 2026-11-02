package com.limelight.ui.xrcontrols;

import androidx.xr.runtime.math.Vector3;

/** Tests an eye ray against a centered panel in that panel's local coordinate space. */
public final class XrGazeTarget {
    private XrGazeTarget() {
    }

    /**
     * The caller transforms both ray components into panel space, including the entity's scale.
     * Width and height are the panel's unscaled local dimensions; its plane is at z = 0.
     */
    public static boolean isLookingAt(Vector3 originInPanelSpace, Vector3 directionInPanelSpace,
                                      float widthMeters, float heightMeters) {
        if (!isFinite(originInPanelSpace) || !isFinite(directionInPanelSpace)
                || !Float.isFinite(widthMeters) || widthMeters <= 0.0f
                || !Float.isFinite(heightMeters) || heightMeters <= 0.0f
                || directionInPanelSpace.getZ() == 0.0f) {
            return false;
        }

        // Use double intermediates so finite rays near parallel to the panel cannot overflow
        // into a false hit. The direction need not be normalized after a space transform.
        double distance = -(double) originInPanelSpace.getZ() / directionInPanelSpace.getZ();
        if (distance < 0.0) {
            return false;
        }
        double x = originInPanelSpace.getX() + distance * directionInPanelSpace.getX();
        double y = originInPanelSpace.getY() + distance * directionInPanelSpace.getY();
        return Math.abs(x) <= widthMeters / 2.0
                && Math.abs(y) <= heightMeters / 2.0;
    }

    private static boolean isFinite(Vector3 vector) {
        return vector != null
                && Float.isFinite(vector.getX())
                && Float.isFinite(vector.getY())
                && Float.isFinite(vector.getZ());
    }
}
