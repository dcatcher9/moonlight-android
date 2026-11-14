package com.limelight.ui;

import android.opengl.GLSurfaceView;

import com.limelight.LimeLog;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * Prefer a 10-bit window for Client SBS so PQ values survive the final EGL surface. Some XR
 * runtimes expose only the baseline 8-bit config, so selection must remain a preference rather
 * than a hard requirement. The renderer verifies the actual default-framebuffer precision
 * before it advertises HDR output to SceneCore.
 */
final class ClientSbsEglConfigChooser implements GLSurfaceView.EGLConfigChooser {
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;

    @Override
    public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
        int[] attributes = {
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL10.EGL_NONE
        };
        int[] count = new int[1];
        if (!egl.eglChooseConfig(display, attributes, null, 0, count)
                || count[0] <= 0) {
            throw new IllegalArgumentException("No window-capable GLES EGL configs");
        }

        EGLConfig[] configs = new EGLConfig[count[0]];
        if (!egl.eglChooseConfig(display, attributes, configs, configs.length, count)) {
            throw new IllegalArgumentException("Unable to enumerate GLES EGL configs");
        }

        EGLConfig hdrConfig = null;
        EGLConfig sdrConfig = null;
        int hdrScore = Integer.MAX_VALUE;
        int sdrScore = Integer.MAX_VALUE;
        for (int i = 0; i < count[0]; i++) {
            EGLConfig config = configs[i];
            int red = getConfigAttribute(egl, display, config, EGL10.EGL_RED_SIZE);
            int green = getConfigAttribute(egl, display, config, EGL10.EGL_GREEN_SIZE);
            int blue = getConfigAttribute(egl, display, config, EGL10.EGL_BLUE_SIZE);
            int alpha = getConfigAttribute(egl, display, config, EGL10.EGL_ALPHA_SIZE);
            int depth = getConfigAttribute(egl, display, config, EGL10.EGL_DEPTH_SIZE);
            int stencil = getConfigAttribute(egl, display, config, EGL10.EGL_STENCIL_SIZE);

            if (red >= 10 && green >= 10 && blue >= 10 && alpha >= 2) {
                int score = colorDistance(red, green, blue, alpha, 10, 2)
                        + depth + stencil;
                if (score < hdrScore) {
                    hdrScore = score;
                    hdrConfig = config;
                }
            }
            if (red >= 8 && green >= 8 && blue >= 8 && alpha >= 8) {
                int score = colorDistance(red, green, blue, alpha, 8, 8)
                        + depth + stencil;
                if (score < sdrScore) {
                    sdrScore = score;
                    sdrConfig = config;
                }
            }
        }

        EGLConfig selected = hdrConfig != null ? hdrConfig : sdrConfig;
        if (selected == null) {
            throw new IllegalArgumentException("No RGBA EGL config for Client SBS");
        }
        LimeLog.info("Client SBS EGL config preference: "
                + (hdrConfig != null ? "RGB10_A2" : "RGBA8 fallback"));
        return selected;
    }

    private static int colorDistance(int red, int green, int blue, int alpha,
                                     int rgbTarget, int alphaTarget) {
        return Math.abs(red - rgbTarget) + Math.abs(green - rgbTarget)
                + Math.abs(blue - rgbTarget) + Math.abs(alpha - alphaTarget);
    }

    private static int getConfigAttribute(EGL10 egl, EGLDisplay display, EGLConfig config,
                                          int attribute) {
        int[] value = new int[1];
        return egl.eglGetConfigAttrib(display, config, attribute, value) ? value[0] : 0;
    }
}
