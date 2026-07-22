package com.limelight.utils;

/** Pure contract checks for the packed Client SBS EGL output surface. */
final class ClientSbsOutputSurfaceValidation {
    private ClientSbsOutputSurfaceValidation() {
    }

    /** Returns {@code null} when the requested packed surface is safe and exactly realized. */
    static String validate(int requestedWidth, int requestedHeight,
                           int actualWidth, int actualHeight,
                           int perEyeWidth, int perEyeHeight,
                           int maxViewportWidth, int maxViewportHeight,
                           int maxTextureSize) {
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            return "Client SBS output override is missing or invalid";
        }
        if (perEyeWidth <= 0 || perEyeHeight <= 0) {
            return "Client SBS per-eye dimensions are invalid";
        }
        long expectedPackedWidth = (long) perEyeWidth * 2L;
        if (expectedPackedWidth > Integer.MAX_VALUE
                || requestedWidth != (int) expectedPackedWidth
                || requestedHeight != perEyeHeight) {
            return "Client SBS output override is not the required 2W x H layout";
        }
        if (actualWidth <= 0 || actualHeight <= 0) {
            return "EGL reported an invalid output surface size";
        }
        if (maxViewportWidth <= 0 || maxViewportHeight <= 0) {
            return "GL_MAX_VIEWPORT_DIMS is invalid";
        }
        // The compatibility path uses two independent W x H viewports positioned side by side.
        // The packed EGL surface may therefore be wider than GL_MAX_VIEWPORT_DIMS[0] while each
        // eye remains legal. The optional packed single-draw path performs its own 2W preflight.
        if (perEyeWidth > maxViewportWidth || perEyeHeight > maxViewportHeight) {
            return "Client SBS per-eye viewport exceeds GL_MAX_VIEWPORT_DIMS";
        }
        if (maxTextureSize <= 0) {
            return "GL_MAX_TEXTURE_SIZE is invalid";
        }
        if (perEyeWidth > maxTextureSize || perEyeHeight > maxTextureSize) {
            return "Client SBS per-eye color target exceeds GL_MAX_TEXTURE_SIZE";
        }
        if (actualWidth != requestedWidth || actualHeight != requestedHeight) {
            return "EGL output surface size does not match the requested Client SBS override";
        }
        return null;
    }
}
