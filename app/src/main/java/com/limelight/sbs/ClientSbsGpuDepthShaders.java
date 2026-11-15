package com.limelight.sbs;

/**
 * Compute shaders used by {@link ClientSbsGpuDepthProcessor}.
 *
 * <p>The bindings are fixed and private to one processor dispatch:</p>
 * <ul>
 *     <li>SSBO 0: packed Float32 model output</li>
 *     <li>SSBO 1: per-inference raw count, geometry range/histogram and moment scratch</li>
 *     <li>SSBO 3: persistent validity, health and adaptive-camera state</li>
 *     <li>image 0: current source-aligned raw ZipDepth field</li>
 *     <li>image 1: renderer profile texel</li>
 * </ul>
 */
final class ClientSbsGpuDepthShaders {
    private ClientSbsGpuDepthShaders() {
    }

    /** Edge of the square per-pixel workgroup; the processor's dispatch math shares it. */
    static final int WORKGROUP_EDGE = 16;
    /** Invocations in one per-pixel workgroup, which sizes its shared reduction arrays. */
    static final int WORKGROUP_LANES = WORKGROUP_EDGE * WORKGROUP_EDGE;
    /** Bins in the raw geometry histogram, cleared by one invocation per bin. */
    static final int HISTOGRAM_BINS = 256;
    /** Scalar words preceding the histogram in the raw scratch block. */
    static final int RAW_STATS_SCALAR_WORDS = 5;

    private static final String PIXEL_WORKGROUP_LAYOUT = "layout(local_size_x = "
            + WORKGROUP_EDGE + ", local_size_y = " + WORKGROUP_EDGE + ") in;";
    /** First stride of the pairwise in-workgroup reduction. */
    private static final String REDUCTION_LOOP = "    for (uint stride = "
            + (WORKGROUP_LANES / 2) + "u; stride != 0u; stride >>= 1u) {";

    private static String lines(String... source) {
        return String.join("\n", source) + "\n";
    }

    /** Formats a finite float as a GLSL literal. */
    static String glsl(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("GLSL literal must be finite");
        }
        return Float.toString(value);
    }

    private static final String HEADER = lines(
            "#version 310 es",
            "precision highp float;",
            "precision highp int;"
    );

    /*
     * Keep this std430 declaration in step with the named byte offsets beside
     * ClientSbsGpuDepthProcessor.STATE_BYTES. Three uints occupy bytes 0..11; each following
     * four-component vector is 16-byte aligned, so the block is exactly 64 bytes.
     */
    static final String STATE = lines(
            "layout(std430, binding = 3) buffer ProcessorState {",
            "    uint frameState;",           // FRAME_STATE_* bits of the newest real inference
            "    uint incompleteFieldCount;", // fields with a missing, non-finite or negative texel
            "    uint collapsedFieldCount;",  // complete fields rejected by the collapse/quantile test
            "    vec4 v2Camera;",             // active zero, current mean, valid count, initialized
            "    vec4 adaptiveCamera;",       // active inverse amplitude, target inverse, raw P05/P95
            "    uvec4 cameraClock;",         // source microseconds low/high, clock armed, current valid
            "};"
    );

    /** The same block for a pass that only reads validity, so it owes no storage barrier. */
    static final String STATE_READ_ONLY = STATE.replace(
            "binding = 3) buffer ProcessorState", "binding = 3) readonly buffer ProcessorState");

    private static final String FRAME_STATE_CONSTANTS = lines(
            "const uint FRAME_STATE_CURRENT_DEPTH_VALID = "
                    + ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_DEPTH_VALID + "u;",
            "const uint FRAME_STATE_CURRENT_V2_VALID = "
                    + ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_V2_VALID + "u;"
    );

    /*
     * Five scalar words and the histogram end at byte 1044; std430 aligns the uvec4 array to 16,
     * so rawGroupMoments begins at ClientSbsGpuDepthProcessor.RAW_STATS_HEADER_BYTES (1056).
     */
    static final String RAW_STATS = lines(
            "layout(std430, binding = 1) buffer RawStats {",
            "    uint rawValidCount;",
            "    uint geometryMinimum;",
            "    uint geometryMaximum;",
            "    uint geometryValidCount;",
            "    uint geometryQuantilesValid;",
            "    uint geometryHistogram[" + HISTOGRAM_BINS + "];",
            "    uvec4 rawGroupMoments[];",
            "};"
    );

    private static final String RAW_INPUT_HEADER = lines(
            "layout(std430, binding = 0) readonly buffer RawDepth {",
            "    uint rawWords[];",
            "};",
            "uniform uint uRawByteOffset;",
            "uniform uint uRawPixelStrideBytes;",
            "uniform ivec2 uTensorSize;",
            "uniform ivec2 uOutputSize;",
            "bool tensorRaw(ivec2 point, out float finiteValue) {",
            "    ivec2 p = clamp(point, ivec2(0), uTensorSize - ivec2(1));",
            "    uint index = uint(p.y * uTensorSize.x + p.x);",
            "    uint absoluteByte = uRawByteOffset + index * uRawPixelStrideBytes;",
            "    float value = uintBitsToFloat(rawWords[absoluteByte >> 2u]);",
            "    if (isnan(value) || isinf(value) || value < 0.0) {",
            "        finiteValue = 0.0;",
            "        return false;",
            "    }",
            "    finiteValue = value;",
            "    return true;",
            "}"
    );

    private static final String DIRECT_SOURCE_ALIGNED_RAW = lines(
            "bool sourceAlignedRaw(ivec2 destination, out float finiteValue) {",
            "    return tensorRaw(destination, finiteValue);",
            "}"
    );

    private static final String REFLECTED_PADDING_SOURCE_ALIGNED_RAW = lines(
            "uniform vec2 uContentScale;",
            "bool sourceAlignedRaw(ivec2 destination, out float finiteValue) {",
            "    vec2 outputUv = (vec2(destination) + vec2(0.5)) / vec2(uOutputSize);",
            "    vec2 padding = vec2(0.5) * (vec2(1.0) - uContentScale);",
            "    vec2 source = (padding + outputUv * uContentScale) * vec2(uTensorSize)",
            "            - vec2(0.5);",
            "    source = clamp(source, vec2(0.0), vec2(uTensorSize - ivec2(1)));",
            "    ivec2 low = ivec2(floor(source));",
            "    ivec2 high = min(low + ivec2(1), uTensorSize - ivec2(1));",
            "    vec2 weight = source - vec2(low);",
            "    weight = mix(weight, vec2(0.0), lessThanEqual(weight, vec2(1.0e-5)));",
            "    vec4 sampleValue;",
            "    bvec4 sampleValid;",
            "    sampleValid.x = tensorRaw(low, sampleValue.x);",
            "    sampleValid.y = tensorRaw(ivec2(high.x, low.y), sampleValue.y);",
            "    sampleValid.z = tensorRaw(ivec2(low.x, high.y), sampleValue.z);",
            "    sampleValid.w = tensorRaw(high, sampleValue.w);",
            "    vec4 bilinearWeight = vec4((1.0 - weight.x) * (1.0 - weight.y),",
            "            weight.x * (1.0 - weight.y), (1.0 - weight.x) * weight.y,",
            "            weight.x * weight.y);",
            // Never renormalize around a bad model sample. That would let a partially invalid
            // field masquerade as complete after reflected-padding removal.
            "    if (!all(sampleValid)) {",
            "        finiteValue = 0.0;",
            "        return false;",
            "    }",
            "    finiteValue = dot(sampleValue, bilinearWeight);",
            "    return true;",
            "}"
    );

    private static String rawInput(boolean removeReflectedPadding) {
        return RAW_INPUT_HEADER
                + (removeReflectedPadding
                ? REFLECTED_PADDING_SOURCE_ALIGNED_RAW : DIRECT_SOURCE_ALIGNED_RAW);
    }

    /** Clears the geometry histogram and the per-inference counters in one 256-thread dispatch. */
    static final String RESET_RAW_STATS = HEADER + RAW_STATS + lines(
            "layout(local_size_x = " + HISTOGRAM_BINS + ") in;",
            "void main() {",
            "    uint index = gl_LocalInvocationID.x;",
            "    geometryHistogram[index] = 0u;",
            "    if (index == 0u) {",
            "        rawValidCount = 0u;",
            "        geometryMinimum = 0xffffffffu;",
            "        geometryMaximum = 0u;",
            "        geometryValidCount = 0u;",
            "        geometryQuantilesValid = 0u;",
            "    }",
            "}"
    );

    /** Counts admitted texels and reduces raw extrema plus per-workgroup mean/M2 moments. */
    static String rawMinMax(boolean removeReflectedPadding) {
        return HEADER + rawInput(removeReflectedPadding) + RAW_STATS + lines(
                PIXEL_WORKGROUP_LAYOUT,
                "shared uint localCount[" + WORKGROUP_LANES + "];",
                "shared float localMean[" + WORKGROUP_LANES + "];",
                "shared float localM2[" + WORKGROUP_LANES + "];",
                "shared uvec2 localGeometryRange[" + WORKGROUP_LANES + "];",
                "void main() {",
                "    uint lane = gl_LocalInvocationIndex;",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    float rawValue = 0.0;",
                "    bool valid = all(lessThan(point, uOutputSize))",
                "            && sourceAlignedRaw(point, rawValue);",
                "    localCount[lane] = valid ? 1u : 0u;",
                "    localMean[lane] = valid ? rawValue : 0.0;",
                "    localM2[lane] = 0.0;",
                // ZipDepth admits only finite nonnegative values, so float bits preserve order.
                "    localGeometryRange[lane] = valid",
                "            ? uvec2(floatBitsToUint(rawValue == 0.0 ? 0.0 : rawValue))",
                "            : uvec2(0xffffffffu, 0u);",
                "    barrier();",
                REDUCTION_LOOP,
                "        if (lane < stride) {",
                "            uint leftCount = localCount[lane];",
                "            uint rightCount = localCount[lane + stride];",
                "            if (rightCount != 0u) {",
                "                if (leftCount == 0u) {",
                "                    localMean[lane] = localMean[lane + stride];",
                "                    localM2[lane] = localM2[lane + stride];",
                "                } else {",
                "                    uint mergedCount = leftCount + rightCount;",
                "                    float delta = localMean[lane + stride] - localMean[lane];",
                "                    localMean[lane] += delta * float(rightCount)",
                "                            / float(mergedCount);",
                "                    localM2[lane] += localM2[lane + stride] + delta * delta",
                "                            * float(leftCount) * float(rightCount)",
                "                            / float(mergedCount);",
                "                }",
                "            }",
                "            localCount[lane] = leftCount + rightCount;",
                "            localGeometryRange[lane].x = min(localGeometryRange[lane].x,",
                "                    localGeometryRange[lane + stride].x);",
                "            localGeometryRange[lane].y = max(localGeometryRange[lane].y,",
                "                    localGeometryRange[lane + stride].y);",
                "        }",
                "        barrier();",
                "    }",
                "    if (lane == 0u) {",
                "        uint groupIndex = gl_WorkGroupID.y * gl_NumWorkGroups.x",
                "                + gl_WorkGroupID.x;",
                "        rawGroupMoments[groupIndex] = uvec4(floatBitsToUint(localMean[0]),",
                "                floatBitsToUint(localM2[0]), localCount[0], 0u);",
                "        if (localCount[0] != 0u) {",
                "            atomicAdd(rawValidCount, localCount[0]);",
                "            atomicMin(geometryMinimum, localGeometryRange[0].x);",
                "            atomicMax(geometryMaximum, localGeometryRange[0].y);",
                "        }",
                "    }",
                "}"
        );
    }

    /** Bins every admitted texel over the exact raw extrema for the P05/P95 estimate. */
    static String rawHistogram(boolean removeReflectedPadding) {
        return HEADER + rawInput(removeReflectedPadding) + RAW_STATS + lines(
                PIXEL_WORKGROUP_LAYOUT,
                "shared uint localGeometryHistogram[" + HISTOGRAM_BINS + "];",
                "void main() {",
                "    uint lane = gl_LocalInvocationIndex;",
                "    localGeometryHistogram[lane] = 0u;",
                "    barrier();",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    float rawValue = 0.0;",
                "    bool valid = all(lessThan(point, uOutputSize))",
                "            && sourceAlignedRaw(point, rawValue);",
                "    if (valid) {",
                "        float minimumValue = uintBitsToFloat(geometryMinimum);",
                "        float maximumValue = uintBitsToFloat(geometryMaximum);",
                "        float rangeValueFloat = maximumValue - minimumValue;",
                "        uint geometryBin = rangeValueFloat <= 0.0 ? 0u : min(uint(",
                "                clamp((rawValue - minimumValue) / rangeValueFloat, 0.0, 1.0)",
                "                        * 256.0), 255u);",
                // Histogram atomics stay in fast workgroup memory; only occupied bins are merged
                // into the global scratch block once per workgroup below.
                "        atomicAdd(localGeometryHistogram[geometryBin], 1u);",
                "    }",
                // Every lane must finish contributing before the lane owning each bin publishes it.
                "    barrier();",
                "    uint geometryBinCount = localGeometryHistogram[lane];",
                "    if (geometryBinCount != 0u) {",
                "        atomicAdd(geometryHistogram[lane], geometryBinCount);",
                "        atomicAdd(geometryValidCount, geometryBinCount);",
                "    }",
                "}"
        );
    }

    /** Resolves completeness, mean, collapse and the P05/P95 geometry bounds of one raw field. */
    static final String RESOLVE_RAW_FIELD = HEADER + RAW_STATS + STATE
            + FRAME_STATE_CONSTANTS + lines(
            "layout(local_size_x = 1) in;",
            "uniform int uExpectedPixelCount;",
            "uniform int uRawGroupCount;",
            "const float V2_COLLAPSE_ABS_EPSILON = "
                    + glsl(ClientSbsV2CoordinateContract.COLLAPSE_ABS_EPSILON) + ";",
            "float geometryPercentile(float percentile, float minimumValue, float binWidth,",
            "        float edge) {",
            "    float target = percentile * float(geometryValidCount);",
            "    uint cumulative = 0u;",
            "    for (uint bin = 0u; bin < 256u; ++bin) {",
            "        cumulative += geometryHistogram[bin];",
            "        if (float(cumulative) >= target)",
            "            return minimumValue + (float(bin) + edge) * binWidth;",
            "    }",
            "    return 0.0;",
            "}",
            "void main() {",
            // These are the raw field's own validity bits. PUBLISH_PROFILE replaces them with the
            // final bits after the adaptive camera has observed this field.
            "    frameState = 0u;",
            "    adaptiveCamera.zw = vec2(0.0);",
            "    geometryQuantilesValid = 0u;",
            "    float currentRawMean = 0.0;",
            "    float currentRawM2 = 0.0;",
            "    uint momentCount = 0u;",
            "    for (int group = 0; group < uRawGroupCount; ++group) {",
            "        uvec4 right = rawGroupMoments[group];",
            "        uint rightCount = right.z;",
            "        float rightMean = uintBitsToFloat(right.x);",
            "        float rightM2 = uintBitsToFloat(right.y);",
            "        if (rightCount == 0u) continue;",
            "        if (momentCount == 0u) {",
            "            currentRawMean = rightMean;",
            "            currentRawM2 = rightM2;",
            "            momentCount = rightCount;",
            "        } else {",
            "            uint mergedCount = momentCount + rightCount;",
            "            float delta = rightMean - currentRawMean;",
            "            currentRawMean += delta * float(rightCount) / float(mergedCount);",
            "            currentRawM2 += rightM2 + delta * delta",
            "                    * float(momentCount) * float(rightCount) / float(mergedCount);",
            "            momentCount = mergedCount;",
            "        }",
            "    }",
            "    float currentRawStd = momentCount != 0u",
            "            ? sqrt(max(currentRawM2 / float(momentCount), 0.0)) : 0.0;",
            "    v2Camera.z = float(rawValidCount);",
            "    bool rawFieldComplete = uExpectedPixelCount > 0",
            "            && rawValidCount == uint(uExpectedPixelCount)",
            "            && momentCount == rawValidCount",
            "            && !isnan(currentRawMean) && !isinf(currentRawMean)",
            "            && !isnan(currentRawStd) && !isinf(currentRawStd);",
            "    if (!rawFieldComplete) {",
            "        incompleteFieldCount = min(incompleteFieldCount + 1u, 0xfffffffeu);",
            "        return;",
            "    }",
            "    v2Camera.y = currentRawMean;",
            "    bool v2FrameValid = currentRawStd > V2_COLLAPSE_ABS_EPSILON;",
            "    float geometryLow = uintBitsToFloat(geometryMinimum);",
            "    float geometryHigh = uintBitsToFloat(geometryMaximum);",
            "    float geometryRange = geometryHigh - geometryLow;",
            "    uint geometryPopulation = 0u;",
            "    for (uint bin = 0u; bin < 256u; ++bin)",
            "        geometryPopulation += geometryHistogram[bin];",
            "    bool quantilesValid = geometryValidCount == rawValidCount",
            "            && geometryPopulation == geometryValidCount",
            "            && !isnan(geometryRange) && !isinf(geometryRange)",
            "            && geometryRange > 0.0;",
            "    if (quantilesValid) {",
            "        float binWidth = geometryRange / 256.0;",
            "        float p05 = geometryPercentile(0.05, geometryLow, binWidth, 0.0);",
            "        float p95 = geometryPercentile(0.95, geometryLow, binWidth, 1.0);",
            "        quantilesValid = !isnan(p05) && !isinf(p05)",
            "                && !isnan(p95) && !isinf(p95)",
            "                && p05 <= p95;",
            "        if (quantilesValid) {",
            // Crossing-bin edges are mathematically inside the raw range. Bound the final
            // floating-point reconstruction so last-bin rounding cannot reject a valid field.
            "            p05 = clamp(p05, geometryLow, geometryHigh);",
            "            p95 = clamp(p95, geometryLow, geometryHigh);",
            "            adaptiveCamera.zw = vec2(p05, p95);",
            "            geometryQuantilesValid = 1u;",
            "        }",
            "    }",
            "    v2FrameValid = v2FrameValid && quantilesValid;",
            "    if (!v2FrameValid)",
            "        collapsedFieldCount = min(collapsedFieldCount + 1u, 0xfffffffeu);",
            "    frameState = FRAME_STATE_CURRENT_DEPTH_VALID",
            "            | (v2FrameValid ? FRAME_STATE_CURRENT_V2_VALID : 0u);",
            "}"
    );

    /** Writes the source-aligned raw R32F field; an unusable field is written as zero. */
    static String writeRawDepth(boolean removeReflectedPadding) {
        return HEADER + rawInput(removeReflectedPadding) + STATE_READ_ONLY
                + FRAME_STATE_CONSTANTS + lines(
                PIXEL_WORKGROUP_LAYOUT,
                "layout(r32f, binding = 0) uniform writeonly highp image2D uCurrentRawDepth;",
                "void main() {",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    if (!all(lessThan(point, uOutputSize))) return;",
                "    uint required = FRAME_STATE_CURRENT_DEPTH_VALID",
                "            | FRAME_STATE_CURRENT_V2_VALID;",
                "    float rawCurrent = 0.0;",
                "    bool currentValid = (frameState & required) == required",
                "            && sourceAlignedRaw(point, rawCurrent);",
                "    imageStore(uCurrentRawDepth, point,",
                "            vec4(currentValid ? rawCurrent : 0.0, 0.0, 0.0, 1.0));",
                "}"
        );
    }

    /** Advances the source-timed adaptive camera, then publishes the V2 profile texel. */
    static final String PUBLISH_PROFILE = HEADER + RAW_STATS + STATE
            + FRAME_STATE_CONSTANTS + lines(
            "layout(local_size_x = 1) in;",
            "uniform uvec2 uSourceTimestampUs;",
            "uniform float uRawCoordinateScale;",
            "layout(rgba32f, binding = 1) uniform writeonly highp image2D uProfileTexture;",
            "void observeAdaptiveCamera(bool rawUsable) {",
            "    cameraClock.w = 0u;",
            "    adaptiveCamera.y = 0.0;",
            "    uvec2 now = uSourceTimestampUs;",
            "    uvec2 previous = cameraClock.xy;",
            "    bool after = now.y > previous.y",
            "            || (now.y == previous.y && now.x > previous.x);",
            "    if (all(equal(now, uvec2(0u)))",
            "            || (any(notEqual(previous, uvec2(0u))) && !after)) {",
            "        cameraClock.z = 0u;",
            "        return;",
            "    }",
            "    uvec2 elapsed = uvec2(now.x - previous.x,",
            "            now.y - previous.y - (now.x < previous.x ? 1u : 0u));",
            "    bool initialized = v2Camera.w > 0.5;",
            "    bool canMove = initialized && cameraClock.z == 1u && elapsed.y == 0u",
            "            && elapsed.x <= "
                    + ClientSbsV2CoordinateContract.ADAPTIVE_MAX_TICK_GAP_US + "u;",
            "    cameraClock.xy = now;",
            "    float halfSpan = 0.5 * (adaptiveCamera.w - adaptiveCamera.z);",
            "    float amplitude = max(halfSpan, uRawCoordinateScale);",
            "    float targetInverse = 1.0 / amplitude;",
            "    bool usable = rawUsable && geometryQuantilesValid == 1u",
            "            && !isnan(uRawCoordinateScale) && !isinf(uRawCoordinateScale)",
            "            && uRawCoordinateScale > 0.0 && !isnan(halfSpan) && !isinf(halfSpan)",
            "            && halfSpan >= 0.0 && !isnan(targetInverse) && !isinf(targetInverse)",
            "            && targetInverse > 0.0;",
            "    if (!usable) {",
            "        cameraClock.z = 0u;",
            "        return;",
            "    }",
            "    adaptiveCamera.y = targetInverse;",
            "    if (!initialized) {",
            "        v2Camera.x = v2Camera.y;",
            "        adaptiveCamera.x = targetInverse;",
            "        v2Camera.w = 1.0;",
            "    } else if (canMove) {",
            "        float seconds = float(elapsed.x) * 0.000001;",
            "        float alpha = 1.0 - exp(-seconds / "
                    + glsl(ClientSbsV2CoordinateContract.ADAPTIVE_TIME_CONSTANT_SECONDS) + ");",
            "        float logDelta = alpha * (log(targetInverse) - log(adaptiveCamera.x));",
            "        float logBound = " + glsl(
                    ClientSbsV2CoordinateContract.ADAPTIVE_LOG_SCALE_RATE) + " * seconds;",
            "        float nextInverse = adaptiveCamera.x",
            "                * exp(clamp(logDelta, -logBound, logBound));",
            "        float zeroBound = seconds / nextInverse;",
            "        float zeroDelta = alpha * v2Camera.y - alpha * v2Camera.x;",
            "        float nextZero = v2Camera.x + clamp(zeroDelta, -zeroBound, zeroBound);",
            "        if (isnan(nextInverse) || isinf(nextInverse) || nextInverse <= 0.0",
            "                || isnan(zeroBound) || isinf(zeroBound)",
            "                || isnan(nextZero) || isinf(nextZero)) {",
            "            cameraClock.z = 0u;",
            "            adaptiveCamera.y = 0.0;",
            "            return;",
            "        }",
            "        adaptiveCamera.x = nextInverse;",
            "        v2Camera.x = nextZero;",
            "    }",
            "    cameraClock.zw = uvec2(1u);",
            "}",
            "void main() {",
            "    uint rawFieldState = frameState;",
            "    frameState = 0u;",
            "    bool rawFieldComplete = (rawFieldState",
            "            & FRAME_STATE_CURRENT_DEPTH_VALID) != 0u;",
            // Camera observations belong to real inference source time. Invalid depth disarms the
            // clock without clearing an established camera.
            "    bool rawGeometryUsable = rawFieldComplete && (rawFieldState",
            "            & FRAME_STATE_CURRENT_V2_VALID) != 0u;",
            "    observeAdaptiveCamera(rawGeometryUsable);",
            // One current-result bitfield is the authority for the profile and for the
            // near-reuse owner: a V2-valid field also needs a camera valid for this observation.
            "    if (rawFieldComplete) {",
            "        frameState = FRAME_STATE_CURRENT_DEPTH_VALID",
            "                | (rawGeometryUsable && cameraClock.w == 1u",
            "                        ? FRAME_STATE_CURRENT_V2_VALID : 0u);",
            "    }",
            "    uint required = FRAME_STATE_CURRENT_DEPTH_VALID",
            "            | FRAME_STATE_CURRENT_V2_VALID;",
            "    bool ready = v2Camera.w > 0.5 && cameraClock.w == 1u",
            "            && (frameState & required) == required;",
            "    imageStore(uProfileTexture, ivec2(0, 0),",
            "            vec4(v2Camera.x, v2Camera.y, adaptiveCamera.x, ready ? 1.0 : 0.0));",
            "}"
    );

    static final String RESET_STATE = HEADER + STATE + lines(
            "layout(local_size_x = 1) in;",
            "layout(rgba32f, binding = 1) uniform writeonly highp image2D uProfileTexture;",
            "void main() {",
            "    frameState = 0u;",
            "    incompleteFieldCount = 0u;",
            "    collapsedFieldCount = 0u;",
            "    v2Camera = vec4(0.0);",
            "    adaptiveCamera = vec4(0.0);",
            "    cameraClock = uvec4(0u);",
            "    imageStore(uProfileTexture, ivec2(0, 0), vec4(0.0));",
            "}"
    );
}
