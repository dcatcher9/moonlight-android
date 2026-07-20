package com.limelight.utils;

/** GLES shaders for the fixed Apollo-derived client SBS production profile. */
final class ClientSbsShaders {
    private ClientSbsShaders() {
    }

    static final String FLAT_FRAGMENT = String.join("\n",
            "#extension GL_OES_EGL_image_external : require",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp samplerExternalOES u_Texture;",
            "void main() {",
            "  vec4 color = texture2D(u_Texture, v_TexCoord);",
            "  gl_FragColor = vec4(color.rgb, 1.0);",
            "}");

    /** Aspect-preserving, reflected-edge SDR input for the fixed-square MiDaS tensor. */
    static final String MODEL_INPUT_FRAGMENT = String.join("\n",
            "#extension GL_OES_EGL_image_external : require",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp samplerExternalOES u_Texture;",
            "uniform bool u_isHdr;",
            "uniform float u_sourceAspect;",
            "float mirrorCoordinate(float value) {",
            "  float wrapped = mod(abs(value), 2.0);",
            "  return wrapped <= 1.0 ? wrapped : 2.0 - wrapped;",
            "}",
            "vec3 pqToLinear(vec3 encoded) {",
            "  const float m1 = 0.1593017578125;",
            "  const float m2 = 78.84375;",
            "  const float c1 = 0.8359375;",
            "  const float c2 = 18.8515625;",
            "  const float c3 = 18.6875;",
            "  vec3 power = pow(max(encoded, 0.0), vec3(1.0 / m2));",
            "  return pow(max(power - c1, 0.0) / max(c2 - c3 * power, 0.00001),",
            "      vec3(1.0 / m1));",
            "}",
            "vec3 bt2020ToBt709(vec3 color) {",
            "  return mat3(1.660491, -0.124550, -0.018151,",
            "      -0.587641, 1.132900, -0.100579,",
            "      -0.072850, -0.008349, 1.118730) * color;",
            "}",
            "vec3 linearToSrgb(vec3 color) {",
            "  vec3 low = 12.92 * color;",
            "  vec3 high = 1.055 * pow(max(color, 0.0), vec3(1.0 / 2.4)) - 0.055;",
            "  return mix(low, high, step(vec3(0.0031308), color));",
            "}",
            "void main() {",
            "  float aspect = max(u_sourceAspect, 0.0001);",
            "  vec2 contentSize = vec2(min(1.0, aspect), min(1.0, 1.0 / aspect));",
            "  vec2 padding = 0.5 * (vec2(1.0) - contentSize);",
            "  vec2 sourceUv = (v_TexCoord - padding) / contentSize;",
            "  sourceUv = vec2(mirrorCoordinate(sourceUv.x),",
            "      mirrorCoordinate(sourceUv.y));",
            "  vec4 color = texture2D(u_Texture, sourceUv);",
            "  if (u_isHdr) {",
            // ST2084 is normalized to 10,000 nits. Express it in the 80-nit reference-white
            // units expected by the SDR model, then convert primaries before tonemapping.
            "    vec3 linearColor = max(bt2020ToBt709(",
            "        pqToLinear(color.rgb) * 125.0), vec3(0.0));",
            "    float luminance = dot(linearColor, vec3(0.2126, 0.7152, 0.0722));",
            "    linearColor /= 1.0 + max(luminance, 0.0);",
            "    float peak = max(1.0, max(linearColor.r,",
            "        max(linearColor.g, linearColor.b)));",
            "    color.rgb = linearToSrgb(clamp(linearColor / peak, 0.0, 1.0));",
            "  }",
            "  gl_FragColor = vec4(color.rgb, 1.0);",
            "}");

    /**
     * Packs the manifest-sized RGBA8 model-input render target into LiteRT's tightly packed
     * Float32 NHWC input. LiteRT converts this GL buffer to internal FP16 PHWC4 on the GPU, so
     * preprocessing remains GPU-resident without relying on direct external-tensor mode.
     * The texture row is flipped because GL texture row zero is bottom-first while the model is
     * top-first.
     */
    static final String MODEL_INPUT_PACK_COMPUTE = createModelInputPackCompute(
            ClientSbsModelManifest.MIDAS_V2_FLOAT.getInputWidth(),
            ClientSbsModelManifest.MIDAS_V2_FLOAT.getInputHeight());

    private static String createModelInputPackCompute(int tensorWidth, int tensorHeight) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("Model input dimensions must be positive");
        }
        return String.join("\n",
            "#version 310 es",
            "precision highp float;",
            "precision highp int;",
            "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
            "uniform highp sampler2D s_ModelInputTexture;",
            "layout(std430, binding = 0) buffer InputTensor {",
            "  float tensorValues[];",
            "};",
            "const uint TENSOR_WIDTH = " + tensorWidth + "u;",
            "const uint TENSOR_HEIGHT = " + tensorHeight + "u;",
            "void main() {",
            "  uint tensorX = gl_GlobalInvocationID.x;",
            "  uint tensorY = gl_GlobalInvocationID.y;",
            "  if (tensorX >= TENSOR_WIDTH || tensorY >= TENSOR_HEIGHT) return;",
            "  int sourceY = int(TENSOR_HEIGHT - 1u - tensorY);",
            "  vec3 rgb = clamp(texelFetch(s_ModelInputTexture,",
            "      ivec2(int(tensorX), sourceY), 0).rgb, 0.0, 1.0);",
            "  uint firstValue = (tensorY * TENSOR_WIDTH + tensorX) * 3u;",
            "  tensorValues[firstValue] = rgb.r;",
            "  tensorValues[firstValue + 1u] = rgb.g;",
            "  tensorValues[firstValue + 2u] = rgb.b;",
            "}");
    }

    /**
     * Mobile form of Apollo's occlusion-aware inverse probe. The host uses a resolution-dependent
     * 12..72 probe budget; twelve fixed steps keep the Galaxy render cost bounded while preserving
     * the same Bestv2 field, frontmost-root selection, and far-background hole fallback.
    */
    static final String REPROJECTION_FRAGMENT = String.join("\n",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp sampler2D s_ColorTexture;",
            "uniform highp sampler2D s_DepthTexture;",
            "uniform highp sampler2D s_ProfileTexture;",
            "uniform vec2 u_sourceSize;",
            "uniform float u_eyeSign;",
            "uniform bool u_UseGpuProfile;",
            "uniform bool u_profileReady;",
            "uniform float u_stretchLow;",
            "uniform float u_stretchInverseRange;",
            "uniform float u_subjectDepth;",
            "uniform float u_recenterDelta;",
            "uniform float u_convergence;",
            "uniform float u_popRatio;",
            "const int PROBE_STEPS = 12;",
            "const float CALIBRATION_WIDTH = 854.0;",
            "const float REFERENCE_ASPECT = 5120.0 / 2160.0;",
            "float bestv2RawShift(float d) {",
            "  d = clamp(d, 0.0, 1.0);",
            "  return -1.39635933 + d * (2.776208766 + d * (21.04503417 + d *",
            "      (-94.6673759 + d * (376.6610774 + d * (-645.141824 + d *",
            "      (482.8701123 - 133.5645677 * d))))));",
            "}",
            "void resolveProfile(out bool ready, out vec3 shape, out vec3 stereo) {",
            // These uniforms define the safe flat/not-ready profile. Active depth samples the
            // first two GPU profile texels; the remaining texels are diagnostics/telemetry.
            "  ready = u_profileReady;",
            "  shape = vec3(u_stretchLow, u_stretchInverseRange, u_recenterDelta);",
            "  stereo = vec3(u_subjectDepth, u_convergence, u_popRatio);",
            "  if (u_UseGpuProfile) {",
            "    vec4 stretch = texture2D(s_ProfileTexture, vec2(0.125, 0.5));",
            "    vec4 gpuStereo = texture2D(s_ProfileTexture, vec2(0.375, 0.5));",
            "    ready = gpuStereo.w > 0.5;",
            "    shape = vec3(stretch.x, stretch.z, gpuStereo.x);",
            "    stereo = vec3(stretch.w, gpuStereo.y, gpuStereo.z);",
            "  }",
            "}",
            "float shapedDepth(float d, vec3 shape) {",
            "  return clamp((d - shape.x) * shape.y + shape.z, 0.0, 1.0);",
            "}",
            "float depthParallax(float d, float subjectShift, float parallaxScale,",
            "    float limit, vec3 shape) {",
            "  float shift = bestv2RawShift(shapedDepth(d, shape));",
            // Match Apollo's explicit subject zero-plane mode: subtract the complete shaped-subject
            // shift and do not add the legacy trim/convergence bias. The tracked subject therefore
            // has exactly zero per-eye parallax instead of being forced behind the display plane.
            "  return clamp((shift - subjectShift) * parallaxScale, -limit, limit);",
            "}",
            "float sampleDepth(float x) {",
            "  return texture2D(s_DepthTexture, vec2(clamp(x, 0.0, 1.0),",
            "      v_TexCoord.y)).r;",
            "}",
            "float reprojectX() {",
            "  bool profileReady;",
            "  vec3 profileShape;",
            "  vec3 stereoProfile;",
            "  resolveProfile(profileReady, profileShape, stereoProfile);",
            "  if (!profileReady) return v_TexCoord.x;",
            // These values are uniform for every probe. Computing them once avoids repeating the
            // subject polynomial, aspect math, and scale/search setup thirteen times per pixel.
            "  float sourceWidth = max(u_sourceSize.x, 1.0);",
            "  float parallaxWidth = min(sourceWidth, CALIBRATION_WIDTH);",
            "  float aspect = max(sourceWidth / max(u_sourceSize.y, 1.0), 0.0001);",
            "  float outputScale = clamp(REFERENCE_ASPECT / aspect, 0.5, 3.0);",
            "  float popScale = 1.25 * max(stereoProfile.z, 1.0) * outputScale;",
            "  float subjectShift = bestv2RawShift(shapedDepth(stereoProfile.x, profileShape));",
            "  float parallaxScale = (0.35 / parallaxWidth) * popScale;",
            "  float parallaxLimit = 0.071 * outputScale;",
            // Explicit anchoring has no convergence bias. Retain the resolved convergence only as
            // a conservative search-radius guard; it can widen the inverse probe but cannot move
            // the selected subject away from zero parallax.
            "  float convergenceGuard = max(stereoProfile.y, 0.0) * 4.0 / parallaxWidth;",
            "  float radius = outputScale * 1.30 * (0.004 +",
            "      12.51 * 0.35 / parallaxWidth + convergenceGuard);",
            "  float startX = v_TexCoord.x - radius;",
            "  float stepX = 2.0 * radius / float(PROBE_STEPS);",
            "  float bestX = v_TexCoord.x;",
            "  float bestDepth = -1.0;",
            "  float backgroundX = v_TexCoord.x;",
            "  float backgroundDepth = 2.0;",
            "  float previousX = startX;",
            "  float previousDepth = sampleDepth(previousX);",
            "  float previousG = (previousX - v_TexCoord.x) -",
            "      u_eyeSign * depthParallax(previousDepth, subjectShift,",
            "      parallaxScale, parallaxLimit, profileShape);",
            "  if (previousDepth < backgroundDepth) {",
            "    backgroundDepth = previousDepth; backgroundX = previousX;",
            "  }",
            "  for (int i = 1; i <= PROBE_STEPS; i++) {",
            "    float x = startX + stepX * float(i);",
            "    float d = sampleDepth(x);",
            "    float g = (x - v_TexCoord.x) - u_eyeSign * depthParallax(d,",
            "        subjectShift, parallaxScale, parallaxLimit, profileShape);",
            "    if ((previousG <= 0.0 && g >= 0.0) || (previousG >= 0.0 && g <= 0.0)) {",
            "      float denominator = g - previousG;",
            "      float t = abs(denominator) > 0.000001",
            "          ? clamp(-previousG / denominator, 0.0, 1.0) : 0.0;",
            "      float crossingX = mix(previousX, x, t);",
            "      float crossingDepth = mix(previousDepth, d, t);",
            "      if (crossingDepth > bestDepth) {",
            "        bestDepth = crossingDepth; bestX = crossingX;",
            "      }",
            "    }",
            "    if (d < backgroundDepth) { backgroundDepth = d; backgroundX = x; }",
            "    previousX = x; previousDepth = d; previousG = g;",
            "  }",
            "  return bestDepth >= 0.0 ? bestX : backgroundX;",
            "}",
            "void main() {",
            "  float sourceX = clamp(reprojectX(), 0.0, 1.0);",
            // The matched OES color is captured through the renderer's vertically flipped quad.
            // Flip once when sampling the resulting 2D texture so it retains the direct path's
            // display orientation and stays aligned with the uploaded depth plane.
            "  vec4 finalColor = texture2D(s_ColorTexture,",
            "      vec2(sourceX, 1.0 - v_TexCoord.y));",
            "  gl_FragColor = vec4(finalColor.rgb, 1.0);",
            "}");

    /** Apollo's validated separable [0.375, 0.25, 0.375] depth prefilter. */
    static final String DEPTH_PREFILTER_FRAGMENT = String.join("\n",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp sampler2D s_InputTexture;",
            "uniform vec2 u_texelSize;",
            "uniform vec2 u_blurDirection;",
            "void main() {",
            "  vec2 delta = u_texelSize * u_blurDirection;",
            "  float depth = texture2D(s_InputTexture, v_TexCoord - delta).r * 0.375 +",
            "      texture2D(s_InputTexture, v_TexCoord).r * 0.25 +",
            "      texture2D(s_InputTexture, v_TexCoord + delta).r * 0.375;",
            "  gl_FragColor = vec4(depth, depth, depth, 1.0);",
            "}");
}
