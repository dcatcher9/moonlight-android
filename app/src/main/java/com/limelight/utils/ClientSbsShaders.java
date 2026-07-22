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
            "uniform mat4 u_TextureTransform;",
            "uniform bool u_tonemapHdrToSdr;",
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
            "vec3 tonemapHdr(vec3 encoded) {",
            "  vec3 linearColor = max(bt2020ToBt709(",
            "      pqToLinear(encoded) * 125.0), vec3(0.0));",
            "  float luminance = dot(linearColor, vec3(0.2126, 0.7152, 0.0722));",
            "  linearColor /= 1.0 + max(luminance, 0.0);",
            "  float peak = max(1.0, max(linearColor.r,",
            "      max(linearColor.g, linearColor.b)));",
            "  return linearToSrgb(clamp(linearColor / peak, 0.0, 1.0));",
            "}",
            "void main() {",
            "  vec2 sourceUv = (u_TextureTransform * vec4(v_TexCoord, 0.0, 1.0)).xy;",
            "  vec4 color = texture2D(u_Texture, sourceUv);",
            "  if (u_tonemapHdrToSdr) color.rgb = tonemapHdr(color.rgb);",
            "  gl_FragColor = vec4(color.rgb, 1.0);",
            "}");

    /** Direct full-frame input used by every static aspect bucket. */
    static final String MODEL_INPUT_FRAGMENT = createModelInputFragment(true);

    /**
     * Builds the stream-fixed model-input shader. A rectangular Depth Anything graph gets a
     * literal full-frame UV path; the inactive square padding/reflection code is not compiled into
     * that program. The external decoder texture is GL_LINEAR, so rendering it into the fixed
     * model target performs one direct bilinear resize.
     */
    static String createModelInputFragment(boolean directFullFrame) {
        String aspectUniform = directFullFrame ? "" : "uniform float u_sourceAspect;";
        String mirrorFunction = directFullFrame ? "" : String.join("\n",
                "float mirrorCoordinate(float value) {",
                "  float wrapped = mod(abs(value), 2.0);",
                "  return wrapped <= 1.0 ? wrapped : 2.0 - wrapped;",
                "}");
        String sourceCoordinates = directFullFrame
                ? "  vec2 sourceUv = v_TexCoord;"
                : String.join("\n",
                "  float aspect = max(u_sourceAspect, 0.0001);",
                "  vec2 contentSize = vec2(min(1.0, aspect), min(1.0, 1.0 / aspect));",
                "  vec2 padding = 0.5 * (vec2(1.0) - contentSize);",
                "  vec2 sourceUv = (v_TexCoord - padding) / contentSize;",
                "  sourceUv = vec2(mirrorCoordinate(sourceUv.x),",
                "      mirrorCoordinate(sourceUv.y));");
        return String.join("\n",
            "#extension GL_OES_EGL_image_external : require",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp samplerExternalOES u_Texture;",
            "uniform mat4 u_TextureTransform;",
            "uniform bool u_isHdr;",
            aspectUniform,
            mirrorFunction,
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
            sourceCoordinates,
            "  sourceUv = (u_TextureTransform * vec4(sourceUv, 0.0, 1.0)).xy;",
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
    }

    /**
     * Packs the manifest-sized RGBA8 model-input render target into LiteRT's tightly packed
     * Float32 NHWC input. LiteRT converts this GL buffer to the selected model's internal GPU
     * layout and precision, so preprocessing remains GPU-resident without relying on direct
     * external-tensor mode.
     * The texture row is flipped because GL texture row zero is bottom-first while the model is
     * top-first.
     */
    static final String MODEL_INPUT_PACK_COMPUTE = createModelInputPackCompute(
            ClientSbsModelManifest.MIDAS_V2_STATIC_16_9.getInputWidth(),
            ClientSbsModelManifest.MIDAS_V2_STATIC_16_9.getInputHeight());

    static String createModelInputPackCompute(int tensorWidth, int tensorHeight) {
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

    /** Template for Apollo's occlusion-aware inverse probe compatibility path. */
    private static final String REPROJECTION_TEMPLATE = String.join("\n",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp sampler2D s_ColorTexture;",
            "uniform highp sampler2D s_DepthTexture;",
            "uniform highp sampler2D s_ProfileTexture;",
            "uniform vec2 u_sourceSize;",
            "uniform float u_eyeSign;",
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
            // Production has one GPU-resident profile path. The renderer does not invoke this
            // shader until that profile and its exact matched color frame have been adopted.
            "  vec4 stretch = texture2D(s_ProfileTexture, vec2(0.125, 0.5));",
            "  vec4 gpuStereo = texture2D(s_ProfileTexture, vec2(0.375, 0.5));",
            "  ready = gpuStereo.w > 0.5;",
            "  shape = vec3(stretch.x, stretch.z, gpuStereo.x);",
            "  stereo = vec3(stretch.w, gpuStereo.y, gpuStereo.z);",
            "}",
            "float shapedDepth(float d, vec3 shape) {",
            "  return clamp((d - shape.x) * shape.y + shape.z, 0.0, 1.0);",
            "}",
            "float depthParallax(float d, float anchorShift, float parallaxScale,",
            "    float convergenceOffset, float limit, vec3 shape) {",
            "  float shift = bestv2RawShift(shapedDepth(d, shape));",
            // Match Apollo's active production profile: legacy zero plane, subject_lock=0.5,
            // followed by the Bestv2 trim/convergence offset and final pop/aspect scaling.
            "  return clamp((shift - anchorShift) * parallaxScale + convergenceOffset,",
            "      -limit, limit);",
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
            "  float anchorShift = 0.5 * subjectShift;",
            "  float parallaxScale = (0.35 / parallaxWidth) * popScale;",
            "  float convergenceBias = -0.004 + stereoProfile.y * 4.0 / parallaxWidth;",
            "  float convergenceOffset = convergenceBias * popScale;",
            "  float parallaxLimit = 0.071 * outputScale;",
            // The maximum resolved convergence also keeps the inverse search radius conservative.
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
            "      u_eyeSign * depthParallax(previousDepth, anchorShift,",
            "      parallaxScale, convergenceOffset, parallaxLimit, profileShape);",
            "  if (previousDepth < backgroundDepth) {",
            "    backgroundDepth = previousDepth; backgroundX = previousX;",
            "  }",
            "  for (int i = 1; i <= PROBE_STEPS; i++) {",
            "    float x = startX + stepX * float(i);",
            "    float d = sampleDepth(x);",
            "    float g = (x - v_TexCoord.x) - u_eyeSign * depthParallax(d,",
            "        anchorShift, parallaxScale, convergenceOffset, parallaxLimit,",
            "        profileShape);",
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
            // The SurfaceTexture transform is applied before capture. The renderer retains its
            // established flipped 2D texture convention for depth/profile alignment, so undo that
            // convention exactly once when sampling the display-oriented color target.
            "  vec4 finalColor = texture2D(s_ColorTexture,",
            "      vec2(sourceX, 1.0 - v_TexCoord.y));",
            "  gl_FragColor = vec4(finalColor.rgb, 1.0);",
            "}");

    /**
     * Chooses one immutable probe budget with the same multiplicative-aspect buckets as depth.
     * The 16:9, 21:9, and 32:9 graphs use 32, 24, and 16 steps respectively. Wider frames cover
     * more source distance per depth texel, so lowering the count there retains a bounded mobile
     * work budget while the common 16:9 path receives Apollo's higher-quality solve.
     */
    static int probeStepsForAspect(float sourceAspect) {
        if (!Float.isFinite(sourceAspect) || sourceAspect <= 0.0f) {
            throw new IllegalArgumentException("Source aspect must be finite and positive");
        }
        ClientSbsDepthInputShape bucket = ClientSbsDepthInputShape.select(sourceAspect);
        if (bucket.equals(ClientSbsDepthInputShape.ASPECT_16_9)) {
            return 32;
        }
        return bucket.equals(ClientSbsDepthInputShape.ASPECT_21_9) ? 24 : 16;
    }

    static String createReprojectionFragment(float sourceAspect) {
        return withProbeSteps(REPROJECTION_TEMPLATE, probeStepsForAspect(sourceAspect));
    }

    static String createReprojectionFragment(int probeSteps) {
        return withProbeSteps(REPROJECTION_TEMPLATE, probeSteps);
    }

    /** Default retained for source/tests that do not yet pass the stream aspect explicitly. */
    static final String REPROJECTION_FRAGMENT = createReprojectionFragment(16.0f / 9.0f);

    /**
     * Precomputes the expensive Bestv2 inverse reprojection for both eyes. Red stores the left-eye
     * signed source displacement and green stores the right-eye displacement. The renderer executes
     * this shader once per adopted depth/profile pair into a linearly sampled RG16F target rather
     * than repeating the stream-fixed probe search at presentation resolution.
     */
    private static final String WARP_MAP_TEMPLATE = String.join("\n",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp sampler2D s_DepthTexture;",
            "uniform highp sampler2D s_ProfileTexture;",
            "uniform vec2 u_sourceSize;",
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
            "  vec4 stretch = texture2D(s_ProfileTexture, vec2(0.125, 0.5));",
            "  vec4 gpuStereo = texture2D(s_ProfileTexture, vec2(0.375, 0.5));",
            "  ready = gpuStereo.w > 0.5;",
            "  shape = vec3(stretch.x, stretch.z, gpuStereo.x);",
            "  stereo = vec3(stretch.w, gpuStereo.y, gpuStereo.z);",
            "}",
            "float shapedDepth(float d, vec3 shape) {",
            "  return clamp((d - shape.x) * shape.y + shape.z, 0.0, 1.0);",
            "}",
            "float depthParallax(float d, float anchorShift, float parallaxScale,",
            "    float convergenceOffset, float limit, vec3 shape) {",
            "  float shift = bestv2RawShift(shapedDepth(d, shape));",
            "  return clamp((shift - anchorShift) * parallaxScale + convergenceOffset,",
            "      -limit, limit);",
            "}",
            "float sampleDepth(float x) {",
            "  return texture2D(s_DepthTexture, vec2(clamp(x, 0.0, 1.0),",
            "      v_TexCoord.y)).r;",
            "}",
            "void updateFrontmostCrossing(float previousG, float g, float previousX,",
            "    float x, float previousDepth, float d, inout float bestDepth,",
            "    inout float bestX) {",
            "  if ((previousG <= 0.0 && g >= 0.0) || (previousG >= 0.0 && g <= 0.0)) {",
            "    float denominator = g - previousG;",
            "    float t = abs(denominator) > 0.000001",
            "        ? clamp(-previousG / denominator, 0.0, 1.0) : 0.0;",
            "    float crossingX = mix(previousX, x, t);",
            "    float crossingDepth = mix(previousDepth, d, t);",
            "    if (crossingDepth > bestDepth) {",
            "      bestDepth = crossingDepth; bestX = crossingX;",
            "    }",
            "  }",
            "}",
            "vec2 reprojectBothEyes(vec3 profileShape, float anchorShift,",
            "    float parallaxScale, float convergenceOffset, float parallaxLimit,",
            "    float radius) {",
            "  float startX = v_TexCoord.x - radius;",
            "  float stepX = 2.0 * radius / float(PROBE_STEPS);",
            "  float leftBestX = v_TexCoord.x;",
            "  float rightBestX = v_TexCoord.x;",
            "  float leftBestDepth = -1.0;",
            "  float rightBestDepth = -1.0;",
            "  float backgroundX = v_TexCoord.x;",
            "  float backgroundDepth = 2.0;",
            "  float previousX = startX;",
            "  float previousDepth = sampleDepth(previousX);",
            "  float previousParallax = depthParallax(previousDepth, anchorShift,",
            "      parallaxScale, convergenceOffset, parallaxLimit, profileShape);",
            "  float previousDelta = previousX - v_TexCoord.x;",
            "  vec2 previousG = vec2(previousDelta + previousParallax,",
            "      previousDelta - previousParallax);",
            "  if (previousDepth < backgroundDepth) {",
            "    backgroundDepth = previousDepth; backgroundX = previousX;",
            "  }",
            "  for (int i = 1; i <= PROBE_STEPS; i++) {",
            "    float x = startX + stepX * float(i);",
            "    float d = sampleDepth(x);",
            "    float parallax = depthParallax(d,",
            "        anchorShift, parallaxScale, convergenceOffset, parallaxLimit,",
            "        profileShape);",
            "    float delta = x - v_TexCoord.x;",
            "    vec2 g = vec2(delta + parallax, delta - parallax);",
            "    updateFrontmostCrossing(previousG.x, g.x, previousX, x,",
            "        previousDepth, d, leftBestDepth, leftBestX);",
            "    updateFrontmostCrossing(previousG.y, g.y, previousX, x,",
            "        previousDepth, d, rightBestDepth, rightBestX);",
            "    if (d < backgroundDepth) { backgroundDepth = d; backgroundX = x; }",
            "    previousX = x; previousDepth = d; previousG = g;",
            "  }",
            "  return vec2(leftBestDepth >= 0.0 ? leftBestX : backgroundX,",
            "      rightBestDepth >= 0.0 ? rightBestX : backgroundX);",
            "}",
            "void main() {",
            "  bool profileReady;",
            "  vec3 profileShape;",
            "  vec3 stereoProfile;",
            "  resolveProfile(profileReady, profileShape, stereoProfile);",
            "  if (!profileReady) {",
            "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);",
            "    return;",
            "  }",
            "  float sourceWidth = max(u_sourceSize.x, 1.0);",
            "  float parallaxWidth = min(sourceWidth, CALIBRATION_WIDTH);",
            "  float aspect = max(sourceWidth / max(u_sourceSize.y, 1.0), 0.0001);",
            "  float outputScale = clamp(REFERENCE_ASPECT / aspect, 0.5, 3.0);",
            "  float popScale = 1.25 * max(stereoProfile.z, 1.0) * outputScale;",
            "  float subjectShift = bestv2RawShift(shapedDepth(stereoProfile.x, profileShape));",
            "  float anchorShift = 0.5 * subjectShift;",
            "  float parallaxScale = (0.35 / parallaxWidth) * popScale;",
            "  float convergenceBias = -0.004 + stereoProfile.y * 4.0 / parallaxWidth;",
            "  float convergenceOffset = convergenceBias * popScale;",
            "  float parallaxLimit = 0.071 * outputScale;",
            "  float convergenceGuard = max(stereoProfile.y, 0.0) * 4.0 / parallaxWidth;",
            "  float radius = outputScale * 1.30 * (0.004 +",
            "      12.51 * 0.35 / parallaxWidth + convergenceGuard);",
            // Store small signed displacements instead of absolute source coordinates. RG16F then
            // retains sub-pixel precision across the full eye rather than quantizing near x=1.
            "  vec2 sourceXs = clamp(reprojectBothEyes(profileShape, anchorShift,",
            "      parallaxScale, convergenceOffset, parallaxLimit, radius), 0.0, 1.0);",
            "  gl_FragColor = vec4(sourceXs - v_TexCoord.xx, 0.0, 1.0);",
            "}");

    static String createWarpMapFragment(float sourceAspect) {
        return withProbeSteps(WARP_MAP_TEMPLATE, probeStepsForAspect(sourceAspect));
    }

    static String createWarpMapFragment(int probeSteps) {
        return withProbeSteps(WARP_MAP_TEMPLATE, probeSteps);
    }

    /** Default retained for source/tests that do not yet pass the stream aspect explicitly. */
    static final String WARP_MAP_FRAGMENT = createWarpMapFragment(16.0f / 9.0f);

    private static String withProbeSteps(String template, int probeSteps) {
        if (probeSteps < 4 || probeSteps > 72) {
            throw new IllegalArgumentException("Probe steps must be between 4 and 72");
        }
        return template.replace("const int PROBE_STEPS = 12;",
                "const int PROBE_STEPS = " + probeSteps + ";");
    }

    /**
     * Cheap packed-SBS compose: one full-width draw, one warp lookup, and one color lookup per
     * output pixel. The half-open left/right split is derived from packed X; avoiding fract()
     * prevents the far-right edge from wrapping back to eye X zero.
     */
    static final String WARPED_REPROJECTION_FRAGMENT = String.join("\n",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp sampler2D s_ColorTexture;",
            "uniform highp sampler2D s_WarpMapTexture;",
            "void main() {",
            "  float packedX = clamp(v_TexCoord.x, 0.0, 1.0);",
            "  float rightEye = step(0.5, packedX);",
            "  float eyeX = clamp(packedX * 2.0 - rightEye, 0.0, 1.0);",
            // The warp pass uses the renderer's established vertically flipped texture vertices.
            // Undo that FBO storage flip when reading the map, just as color sampling below does.
            "  vec2 sourceOffsets = texture2D(s_WarpMapTexture,",
            "      vec2(eyeX, 1.0 - v_TexCoord.y)).rg;",
            "  float sourceOffset = mix(sourceOffsets.r, sourceOffsets.g, rightEye);",
            "  float sourceX = eyeX + sourceOffset;",
            "  vec4 finalColor = texture2D(s_ColorTexture,",
            "      vec2(clamp(sourceX, 0.0, 1.0), 1.0 - v_TexCoord.y));",
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
