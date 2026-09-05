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
