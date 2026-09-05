package com.limelight.utils;

/** GLES shaders for the fixed Apollo-derived client SBS production profile. */
final class ClientSbsShaders {
    private ClientSbsShaders() {
    }

    // Single-owner adaptive-pop band (ClientSbsGpuDepthProcessor). Declared before every template
    // that interpolates them, since static initializers run in declaration order.
    private static final float ADAPTIVE_POP_CEILING =
            com.limelight.sbs.ClientSbsGpuDepthProcessor.ADAPTIVE_POP_CEILING;
    private static final String POP_FLOOR = String.format(java.util.Locale.US, "%.2f",
            com.limelight.sbs.ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR);
    private static final String POP_CEILING =
            String.format(java.util.Locale.US, "%.2f", ADAPTIVE_POP_CEILING);

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
     * The largest number of source cells one model texel can overlap on either axis. The worst
     * supported XR ladder entry is 2160x5120 portrait: its reflected content occupies 162x384
     * cells in the selected 672x384 graph, so a 13.34-cell footprint overlaps at most 15 cells.
     * GLSL ES 1.00 requires a literal loop bound; 16 retains one cell of headroom without making
     * the shader stream-size-specific (same-graph live resizes can therefore retain the program).
     */
    static final int MODEL_INPUT_MAX_AREA_SOURCE_CELLS = 16;

    /**
     * Builds the stream-fixed model-input shader. A source whose orientation matches its model
     * gets a literal full-frame path. Portrait input uses reflected padding with
     * {@code u_sourceAspect = sourceAspect / modelAspect}; expressing it relative to the model is
     * what preserves the source aspect on a rectangular landscape tensor.
     *
     * <p>Downsampling integrates every source texel cell covered by a model texel with its exact
     * overlap area. Sampling a sparse fixed lattice aliases thin features, particularly at 4K.
     * A source smaller than the occupied model grid instead uses pixel-center bilinear sampling,
     * matching the host's resize contract. HDR conversion is applied to each source cell before
     * spatial integration so HDR and equivalent SDR inputs do not average in different transfer
     * domains.</p>
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
                "  vec2 sourceUv = (v_TexCoord - padding) / contentSize;");
        String effectiveRatio = directFullFrame
                ? "  vec2 effectiveDownsampleRatio = u_downsampleRatio;"
                : "  vec2 effectiveDownsampleRatio = u_downsampleRatio / contentSize;";
        String sourceCellUv = directFullFrame
                ? String.join("\n",
                // GLSL ES 1.00 has no integer clamp overload, so clamp in float coordinates.
                "  vec2 maxCell = max(u_sourceSize - vec2(1.0), vec2(0.0));",
                "  vec2 boundedCell = clamp(vec2(sourceCell), vec2(0.0), maxCell);",
                "  return (boundedCell + vec2(0.5)) / u_sourceSize;")
                : String.join("\n",
                // Reflection is piecewise affine. Resolve every discrete source cell before the
                // decoder transform so a footprint crossing a padding fold remains exact.
                "  vec2 sourceUv = (vec2(sourceCell) + vec2(0.5)) / u_sourceSize;",
                "  return vec2(mirrorCoordinate(sourceUv.x),",
                "      mirrorCoordinate(sourceUv.y));");
        String footprintBounds = directFullFrame
                ? String.join("\n",
                "  vec2 sourceLo = vec2(targetPixel) * u_downsampleRatio;",
                "  vec2 sourceHi = vec2(targetPixel + ivec2(1)) * u_downsampleRatio;")
                : String.join("\n",
                "  vec2 targetSize = u_sourceSize / u_downsampleRatio;",
                "  vec2 sourceLo = ((vec2(targetPixel) / targetSize - padding)",
                "      / contentSize) * u_sourceSize;",
                "  vec2 sourceHi = ((vec2(targetPixel + ivec2(1)) / targetSize - padding)",
                "      / contentSize) * u_sourceSize;");
        return String.join("\n",
            "#extension GL_OES_EGL_image_external : require",
            "precision highp float;",
            "precision highp int;",
            "const int MAX_AREA_SOURCE_CELLS = "
                    + MODEL_INPUT_MAX_AREA_SOURCE_CELLS + ";",
            "uniform vec2 u_downsampleRatio;",
            "uniform vec2 u_sourceSize;",
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
            "vec3 toModelColor(vec3 encoded) {",
            "  if (u_isHdr) {",
            // ST2084 is normalized to 10,000 nits. Express it in the 80-nit reference-white
            // units expected by the SDR model, then convert primaries before tonemapping.
            "    vec3 linearColor = max(bt2020ToBt709(",
            "        pqToLinear(encoded) * 125.0), vec3(0.0));",
            "    float luminance = dot(linearColor, vec3(0.2126, 0.7152, 0.0722));",
            "    linearColor /= 1.0 + max(luminance, 0.0);",
            "    float peak = max(1.0, max(linearColor.r,",
            "        max(linearColor.g, linearColor.b)));",
            "    return linearToSrgb(clamp(linearColor / peak, 0.0, 1.0));",
            "  }",
            "  return encoded;",
            "}",
            "vec2 sourceCellUv(ivec2 sourceCell) {",
            sourceCellUv,
            "}",
            "vec3 loadModelColor(ivec2 sourceCell) {",
            "  vec2 logicalUv = sourceCellUv(sourceCell);",
            "  vec2 transformedUv = (u_TextureTransform",
            "      * vec4(logicalUv, 0.0, 1.0)).xy;",
            "  return toModelColor(texture2D(u_Texture, transformedUv).rgb);",
            "}",
            "vec3 sampleModelColorBilinear(vec2 centerUv) {",
            "  vec2 sourcePosition = centerUv * u_sourceSize - vec2(0.5);",
            "  ivec2 lo = ivec2(floor(sourcePosition));",
            "  vec2 blend = fract(sourcePosition);",
            "  vec3 top = mix(loadModelColor(lo),",
            "      loadModelColor(lo + ivec2(1, 0)), blend.x);",
            "  vec3 bottom = mix(loadModelColor(lo + ivec2(0, 1)),",
            "      loadModelColor(lo + ivec2(1, 1)), blend.x);",
            "  return mix(top, bottom, blend.y);",
            "}",
            "vec3 sampleModelFootprint(vec2 sourceLo, vec2 sourceHi) {",
            "  ivec2 first = ivec2(floor(sourceLo));",
            "  ivec2 end = ivec2(ceil(sourceHi));",
            "  vec3 weightedSum = vec3(0.0);",
            "  for (int sourceOffsetY = 0;",
            "      sourceOffsetY < MAX_AREA_SOURCE_CELLS; sourceOffsetY++) {",
            "    int sourceY = first.y + sourceOffsetY;",
            "    if (sourceY >= end.y) break;",
            "    float yCoverage = max(min(sourceHi.y, float(sourceY + 1))",
            "        - max(sourceLo.y, float(sourceY)), 0.0);",
            "    for (int sourceOffsetX = 0;",
            "        sourceOffsetX < MAX_AREA_SOURCE_CELLS; sourceOffsetX++) {",
            "      int sourceX = first.x + sourceOffsetX;",
            "      if (sourceX >= end.x) break;",
            "      float xCoverage = max(min(sourceHi.x, float(sourceX + 1))",
            "          - max(sourceLo.x, float(sourceX)), 0.0);",
            "      weightedSum += loadModelColor(ivec2(sourceX, sourceY))",
            "          * (xCoverage * yCoverage);",
            "    }",
            "  }",
            "  float footprintArea = max((sourceHi.x - sourceLo.x)",
            "      * (sourceHi.y - sourceLo.y), 0.000001);",
            "  return weightedSum / footprintArea;",
            "}",
            "void main() {",
            sourceCoordinates,
            effectiveRatio,
            "  ivec2 targetPixel = ivec2(floor(gl_FragCoord.xy));",
            footprintBounds,
            "  vec3 color;",
            "  if (effectiveDownsampleRatio.x < 1.0",
            "      || effectiveDownsampleRatio.y < 1.0) {",
            "    color = sampleModelColorBilinear(sourceUv);",
            "  } else {",
            "    color = sampleModelFootprint(sourceLo, sourceHi);",
            "  }",
            "  gl_FragColor = vec4(color, 1.0);",
            "}");
    }

    /**
     * Packs the manifest-sized RGBA8 model-input render target into LiteRT's tightly packed
     * Float32 NHWC input. LiteRT converts this GL buffer to the resolved graph's internal GPU
     * layout and precision, so preprocessing remains GPU-resident without relying on direct
     * external-tensor mode.
     * The texture row is flipped because GL texture row zero is bottom-first while the model is
     * top-first.
     */
    static final String MODEL_INPUT_PACK_COMPUTE = createModelInputPackCompute(
            ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9.getInputWidth(),
            ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9.getInputHeight());

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
            // stereo = (shot-latched zero-plane anchor SHIFT in source pixels, adaptive pop
            // ratio, unused). The anchor is resolved once per shot by RESOLVE_PROFILE through the
            // same shapedDepth()/bestv2RawShift() path used below, so it describes exactly the
            // plane this shader renders.
            "  shape = vec3(stretch.x, stretch.z, gpuStereo.x);",
            "  stereo = vec3(gpuStereo.y, gpuStereo.z, 0.0);",
            "}",
            "float shapedDepth(float d, vec3 shape) {",
            "  return clamp((d - shape.x) * shape.y + shape.z, 0.0, 1.0);",
            "}",
            "float depthParallax(float d, float anchorShift, float parallaxScale, vec3 shape) {",
            "  float shift = bestv2RawShift(shapedDepth(d, shape));",
            // No convergence offset: it is identically zero under an explicit zero plane. No safety
            // clamp either -- reach is 9.979 * (0.35/854) * strength * outputScale against the old
            // 0.071 * outputScale bound, so outputScale cancels and binding needs strength > 17.36
            // while the configured maximum is 2.0.
            "  return (shift - anchorShift) * parallaxScale;",
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
            "  float popScale = " + POP_FLOOR + " * max(stereoProfile.y, 1.0) * outputScale;",
            "  float anchorShift = stereoProfile.x;",
            "  float parallaxScale = (0.35 / parallaxWidth) * popScale;",
            // Still the historical over-wide bound; the frame's exact reach replaces it in the
            // probe-lattice change. It only ever costs probes, never correctness.
            "  float radius = outputScale * " + POP_CEILING + " * (0.004 + 12.51 * 0.35 / parallaxWidth);",
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
            "      parallaxScale, profileShape);",
            "  if (previousDepth < backgroundDepth) {",
            "    backgroundDepth = previousDepth; backgroundX = previousX;",
            "  }",
            "  for (int i = 1; i <= PROBE_STEPS; i++) {",
            "    float x = startX + stepX * float(i);",
            "    float d = sampleDepth(x);",
            "    float g = (x - v_TexCoord.x) - u_eyeSign * depthParallax(d,",
            "        anchorShift, parallaxScale, profileShape);",
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
     * Sizes the probe budget from the DEPTH grid rather than from a fixed per-aspect count.
     *
     * <p>Apollo pins probe SPACING, not step count: {@code BESTV2_TARGET_DEPTH_TEXELS / depthWidth},
     * with the target at 1.22 depth texels. That is where the probe grid matches the resolution of
     * the signal it samples — finer merely oversamples a bilinear map, and coarser breaks the
     * one-breakpoint-per-probe-interval argument the inverse solve relies on. Porting Apollo's step
     * count directly would be wrong, because the client's depth map is 2.4–3.4x coarser on the short
     * side; the correct port is the spacing rule, which then yields FEWER probes here.</p>
     *
     * <p>The fixed 32/24/16 budget oversampled by roughly 3x: at 16:9 the radius is about 0.0243 in
     * normalized source U, so 32 probes sat 0.40 depth texels apart.</p>
     *
     * <p>{@code parallaxWidth} is taken as the 854 calibration width, which holds for every stream
     * at or above that width — i.e. every realistic XR resolution. A narrower source would widen the
     * in-shader radius and leave these probes coarser than the 1.22 target.</p>
     */
    static int probeStepsForAspect(float sourceAspect) {
        return probeStepsForDepthOutput(sourceAspect, 1);
    }

    /**
     * Sizes the probe budget for the source-aligned width of the resolved depth graph's output.
     *
     * <p>The aspect-only overload is a legacy calibration helper retained for offline tests.
     * Production passes the resolved ZipDepth output width and its dedicated bucket boundary.
     * Portrait callers must pass the cropped, source-aligned output width, not the padded landscape
     * tensor width.</p>
     */
    static int probeStepsForDepthOutput(float sourceAspect, int selectedDepthWidth) {
        if (!Float.isFinite(sourceAspect) || sourceAspect <= 0.0f) {
            throw new IllegalArgumentException("Source aspect must be finite and positive");
        }
        if (selectedDepthWidth <= 0) {
            throw new IllegalArgumentException("Depth output width must be positive");
        }
        ClientSbsDepthInputShape bucket = ClientSbsDepthInputShape.select(sourceAspect);
        // Portrait input is aspect-fitted into the landscape graph and the reflected side padding
        // is cropped from its depth output. Size against that narrower, source-aligned output, not
        // the padded tensor width. Historical MiDaS graphs define the conservative legacy floor
        // used by offline tests; production ZipDepth's larger resolved width raises it naturally.
        int legacyDepthWidth = sourceAspect < 1.0f
                ? Math.max(1, Math.round(sourceAspect
                * Math.max(bucket.getHeight(), tallestModelHeightFor(bucket))))
                : Math.max(bucket.getWidth(), widestModelWidthFor(bucket));
        int depthWidth = Math.max(selectedDepthWidth, legacyDepthWidth);
        // Landscape streams size from the bucket's NARROWEST aspect, so streams in the same
        // immutable direct-resize contract produce byte-identical shader source. Portrait
        // contracts already vary with the exact aspect-fit crop, so use their exact source aspect.
        float worstAspect = sourceAspect < 1.0f
                ? sourceAspect : narrowestAspectFor(bucket);
        return probeStepsForDepthOutput(sourceAspect, depthWidth, worstAspect);
    }

    /**
     * Sizes a landscape graph from its own nearest-aspect selection interval rather than the
     * legacy DA-V2 interval. This keeps one shader identity for every aspect routed to that graph.
     */
    static int probeStepsForDepthOutput(float sourceAspect, int selectedDepthWidth,
                                        float minimumLandscapeAspect) {
        if (!Float.isFinite(sourceAspect) || sourceAspect <= 0.0f) {
            throw new IllegalArgumentException("Source aspect must be finite and positive");
        }
        if (selectedDepthWidth <= 0) {
            throw new IllegalArgumentException("Depth output width must be positive");
        }
        if (!Float.isFinite(minimumLandscapeAspect) || minimumLandscapeAspect <= 0.0f) {
            throw new IllegalArgumentException(
                    "Minimum landscape aspect must be finite and positive");
        }
        float worstAspect = sourceAspect < 1.0f ? sourceAspect : minimumLandscapeAspect;
        float outputScale = Math.max(0.5f, Math.min(REFERENCE_ASPECT_RATIO / worstAspect, 3.0f));
        float radius = outputScale * ADAPTIVE_POP_CEILING
                * (0.004f + 12.51f * 0.35f / CALIBRATION_WIDTH_PX);
        float spacing = TARGET_DEPTH_TEXELS / selectedDepthWidth;
        int steps = (int) Math.ceil(2.0f * radius / spacing);
        return Math.max(8, Math.min(steps, 48));
    }

    private static final float REFERENCE_ASPECT_RATIO = 5120.0f / 2160.0f;
    private static final float CALIBRATION_WIDTH_PX = 854.0f;
    private static final float TARGET_DEPTH_TEXELS = 1.22f;

    /**
     * Narrowest source aspect that still selects this bucket. Buckets are chosen by least
     * multiplicative distortion, so the boundary between adjacent buckets is their geometric mean.
     * The widest bucket's lower bound is 4:3, the narrowest realistic landscape stream.
     */
    private static float narrowestAspectFor(ClientSbsDepthInputShape bucket) {
        float wide = aspectOf(ClientSbsDepthInputShape.ASPECT_16_9);
        float mid = aspectOf(ClientSbsDepthInputShape.ASPECT_21_9);
        float ultra = aspectOf(ClientSbsDepthInputShape.ASPECT_32_9);
        if (bucket.equals(ClientSbsDepthInputShape.ASPECT_16_9)) {
            return 4.0f / 3.0f;
        }
        if (bucket.equals(ClientSbsDepthInputShape.ASPECT_21_9)) {
            return (float) Math.sqrt(wide * mid);
        }
        return (float) Math.sqrt(mid * ultra);
    }

    private static float aspectOf(ClientSbsDepthInputShape bucket) {
        return bucket.getWidth() / (float) bucket.getHeight();
    }

    private static int widestModelWidthFor(ClientSbsDepthInputShape bucket) {
        if (bucket.equals(ClientSbsDepthInputShape.ASPECT_16_9)) {
            return ClientSbsModelManifest.MIDAS_V2_STATIC_16_9.getInputWidth();
        }
        if (bucket.equals(ClientSbsDepthInputShape.ASPECT_21_9)) {
            return ClientSbsModelManifest.MIDAS_V2_STATIC_21_9.getInputWidth();
        }
        return ClientSbsModelManifest.MIDAS_V2_STATIC_32_9.getInputWidth();
    }

    private static int tallestModelHeightFor(ClientSbsDepthInputShape bucket) {
        if (bucket.equals(ClientSbsDepthInputShape.ASPECT_16_9)) {
            return ClientSbsModelManifest.MIDAS_V2_STATIC_16_9.getInputHeight();
        }
        if (bucket.equals(ClientSbsDepthInputShape.ASPECT_21_9)) {
            return ClientSbsModelManifest.MIDAS_V2_STATIC_21_9.getInputHeight();
        }
        return ClientSbsModelManifest.MIDAS_V2_STATIC_32_9.getInputHeight();
    }

    static String createReprojectionFragment(float sourceAspect) {
        return withProbeSteps(REPROJECTION_TEMPLATE, probeStepsForAspect(sourceAspect));
    }

    static String createReprojectionFragment(int probeSteps) {
        return withProbeSteps(REPROJECTION_TEMPLATE, probeSteps);
    }

    /** Default ZipDepth 16:9 contract for source/tests without a resolved stream contract. */
    static final String REPROJECTION_FRAGMENT =
            createReprojectionFragment(productionZipDepth16By9ProbeSteps());

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
            // stereo = (shot-latched zero-plane anchor SHIFT in source pixels, adaptive pop
            // ratio, unused). The anchor is resolved once per shot by RESOLVE_PROFILE through the
            // same shapedDepth()/bestv2RawShift() path used below, so it describes exactly the
            // plane this shader renders.
            "  shape = vec3(stretch.x, stretch.z, gpuStereo.x);",
            "  stereo = vec3(gpuStereo.y, gpuStereo.z, 0.0);",
            "}",
            "float shapedDepth(float d, vec3 shape) {",
            "  return clamp((d - shape.x) * shape.y + shape.z, 0.0, 1.0);",
            "}",
            "float depthParallax(float d, float anchorShift, float parallaxScale, vec3 shape) {",
            "  float shift = bestv2RawShift(shapedDepth(d, shape));",
            "  return (shift - anchorShift) * parallaxScale;",
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
            "    float parallaxScale, float radius) {",
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
            "      parallaxScale, profileShape);",
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
            "        anchorShift, parallaxScale, profileShape);",
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
            "  float popScale = " + POP_FLOOR + " * max(stereoProfile.y, 1.0) * outputScale;",
            "  float anchorShift = stereoProfile.x;",
            "  float parallaxScale = (0.35 / parallaxWidth) * popScale;",
            // Still the historical over-wide bound; the frame's exact reach replaces it in the
            // probe-lattice change. It only ever costs probes, never correctness.
            "  float radius = outputScale * " + POP_CEILING + " * (0.004 + 12.51 * 0.35 / parallaxWidth);",
            // Store small signed displacements instead of absolute source coordinates. RG16F then
            // retains sub-pixel precision across the full eye rather than quantizing near x=1.
            "  vec2 sourceXs = clamp(reprojectBothEyes(profileShape, anchorShift,",
            "      parallaxScale, radius), 0.0, 1.0);",
            "  gl_FragColor = vec4(sourceXs - v_TexCoord.xx, 0.0, 1.0);",
            "}");

    static String createWarpMapFragment(float sourceAspect) {
        return withProbeSteps(WARP_MAP_TEMPLATE, probeStepsForAspect(sourceAspect));
    }

    static String createWarpMapFragment(int probeSteps) {
        return withProbeSteps(WARP_MAP_TEMPLATE, probeSteps);
    }

    /** Default ZipDepth 16:9 contract for source/tests without a resolved stream contract. */
    static final String WARP_MAP_FRAGMENT =
            createWarpMapFragment(productionZipDepth16By9ProbeSteps());

    /**
     * Builds the two-eye inverse map from an already conditioned signed-parallax field.
     *
     * <p>The conditioner guarantees a horizontal slope strictly below one, so each eye has one
     * inverse. This is the same visibility contract as current Host SBS: eleven fixed-point
     * iterations replace the legacy frontmost multi-root probe and its background fallback. Like
     * the host shader, this paired-eye solve stops only when both next coordinates equal their
     * current coordinates exactly; a non-settled sample still reaches the same hard cap as the
     * reference solve.</p>
     */
    static final String CONTRACTIVE_WARP_MAP_FRAGMENT = String.join("\n",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp sampler2D s_ParallaxTexture;",
            "const int INVERSE_ITERATIONS = 11;",
            "vec2 reprojectBothEyes() {",
            "  vec2 destination = v_TexCoord.xx;",
            "  vec2 sourceXs = destination;",
            "  for (int iteration = 0; iteration < INVERSE_ITERATIONS; iteration++) {",
            "    float leftParallax = texture2D(s_ParallaxTexture,",
            "        vec2(sourceXs.x, v_TexCoord.y)).r;",
            "    float rightParallax = texture2D(s_ParallaxTexture,",
            "        vec2(sourceXs.y, v_TexCoord.y)).r;",
            "    vec2 nextSourceXs = destination + vec2(-leftParallax, rightParallax);",
            "    bool exactlySettled = all(equal(nextSourceXs, sourceXs));",
            "    sourceXs = nextSourceXs;",
            "    if (exactlySettled) break;",
            "  }",
            "  return sourceXs;",
            "}",
            "void main() {",
            // As with the compatibility map, store signed displacements rather than absolute U
            // so RG16F retains useful sub-pixel precision across the complete eye.
            "  vec2 sourceXs = clamp(reprojectBothEyes(), 0.0, 1.0);",
            "  gl_FragColor = vec4(sourceXs - v_TexCoord.xx, 0.0, 1.0);",
            "}");

    /**
     * Doubles only the horizontal inverse-map lattice. The exact 1x map supplies a bilinear seed,
     * then one fixed-point update against the same R32F parallax field contracts that seed error.
     * The coarse FBO is vertically flipped by the renderer's texture coordinates, so its lookup
     * explicitly undoes that storage flip before the refined FBO applies the same convention.
     */
    static final String CONTRACTIVE_WARP_MAP_REFINEMENT_FRAGMENT = String.join("\n",
            "precision highp float;",
            "varying vec2 v_TexCoord;",
            "uniform highp sampler2D s_CoarseWarpMapTexture;",
            "uniform highp sampler2D s_ParallaxTexture;",
            "void main() {",
            "  vec2 destination = v_TexCoord.xx;",
            "  vec2 coarseOffsets = texture2D(s_CoarseWarpMapTexture,",
            "      vec2(v_TexCoord.x, 1.0 - v_TexCoord.y)).rg;",
            "  vec2 sourceXs = clamp(destination + coarseOffsets, 0.0, 1.0);",
            "  float leftParallax = texture2D(s_ParallaxTexture,",
            "      vec2(sourceXs.x, v_TexCoord.y)).r;",
            "  float rightParallax = texture2D(s_ParallaxTexture,",
            "      vec2(sourceXs.y, v_TexCoord.y)).r;",
            "  sourceXs = clamp(destination + vec2(-leftParallax, rightParallax),",
            "      0.0, 1.0);",
            "  gl_FragColor = vec4(sourceXs - destination, 0.0, 1.0);",
            "}");

    private static int productionZipDepth16By9ProbeSteps() {
        ClientSbsModelManifest manifest =
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9;
        return probeStepsForDepthOutput(
                16.0f / 9.0f,
                manifest.getOutputWidth(),
                ClientSbsModelManifest.minimumLandscapeAspectForDedicatedProbeBucket(manifest));
    }

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

}
