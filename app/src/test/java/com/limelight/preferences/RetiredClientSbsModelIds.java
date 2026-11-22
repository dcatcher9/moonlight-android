package com.limelight.preferences;

/**
 * Client SBS model ids that the app no longer runs. Tests use them as stored preference values to
 * cover the ZipDepth-only migration and the manifest's rejection of every other model family.
 */
public final class RetiredClientSbsModelIds {
    public static final String DA_V2_STATIC = "depth-anything-v2-small-static-performance";
    public static final String MIDAS_V2 = "midas-v2-float";
    public static final String DEPTHART_S448_FP16 = "depthart-s448-fp16";

    private RetiredClientSbsModelIds() {
    }
}
