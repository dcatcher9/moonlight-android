package com.limelight.ui.xrcontrols;

/** Pure layout math for the fixed, level XR mode-button panel. */
public final class XrControlPanelLayout {
    public final float widthMeters;
    public final float heightMeters;
    public final float panelCenterY;
    public final float primaryRowCenterY;

    private XrControlPanelLayout(float widthMeters, float heightMeters,
                                 float panelCenterY, float primaryRowCenterY) {
        this.widthMeters = widthMeters;
        this.heightMeters = heightMeters;
        this.panelCenterY = panelCenterY;
        this.primaryRowCenterY = primaryRowCenterY;
    }

    public static XrControlPanelLayout calculate(float tileUnits, int dividerCount,
                                                  float tileSizeMeters,
                                                  float dividerWidthMeters,
                                                  float videoHeightMeters,
                                                  float videoToPrimaryRowCenterGapMeters) {
        if (tileUnits <= 0.0f || dividerCount < 0 || tileSizeMeters <= 0.0f
                || dividerWidthMeters < 0.0f || videoHeightMeters <= 0.0f
                || videoToPrimaryRowCenterGapMeters <= 0.0f) {
            throw new IllegalArgumentException("XR control-panel dimensions must be positive");
        }
        float width = tileUnits * tileSizeMeters + dividerCount * dividerWidthMeters;
        float height = tileSizeMeters;
        float primaryRowCenter = -(videoHeightMeters / 2.0f)
                - videoToPrimaryRowCenterGapMeters;
        return new XrControlPanelLayout(width, height, primaryRowCenter, primaryRowCenter);
    }
}
