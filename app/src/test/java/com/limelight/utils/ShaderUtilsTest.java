package com.limelight.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShaderUtilsTest {
    @Test
    public void clientSbsOutputIsForcedOpaque() {
        assertTrue(ShaderUtils.FRAGMENT_SHADER_3D.contains(
                "gl_FragColor = vec4(finalColor.rgb, 1.0)"));
        assertFalse(ShaderUtils.FRAGMENT_SHADER_3D.contains(
                "gl_FragColor = finalColor"));
    }

    @Test
    public void zeroParallaxBlurCopiesInputWithoutDivision() {
        String shader = ShaderUtils.OPTIMIZED_SINGLE_PASS_GAUSSIAN_BLUR_SHADER;
        int guardIndex = shader.indexOf("parallaxFactor < 0.0001");
        int divisionIndex = shader.indexOf("2.0 / parallaxFactor");

        assertTrue(guardIndex >= 0);
        assertTrue(divisionIndex > guardIndex);
        assertTrue(shader.contains("gl_FragColor = texture2D(s_InputTexture, v_TexCoord)"));
        assertTrue(shader.contains("weightSum > 0.00001"));
        assertFalse(shader.contains("/ u_parallax"));
    }
}
