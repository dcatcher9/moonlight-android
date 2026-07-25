package com.limelight.sbs;

/**
 * Compute shaders used by {@link ClientSbsGpuDepthProcessor}.
 *
 * <p>The bindings are deliberately fixed and private to one processor dispatch:</p>
 * <ul>
 *     <li>SSBO 0: packed Float32 model output, or raw scratch during range resolve</li>
 *     <li>SSBO 1: raw range/histogram scratch, then the per-slot GPU scene-cut word</li>
 *     <li>SSBO 2: processed-depth histogram scratch</li>
 *     <li>SSBO 3: persistent temporal/profile state</li>
 *     <li>image 0: current processed depth</li>
 *     <li>image 1: renderer profile texture</li>
 * </ul>
 */
final class ClientSbsGpuDepthShaders {
    private ClientSbsGpuDepthShaders() {
    }

    private static String lines(String... source) {
        return String.join("\n", source) + "\n";
    }

    private static final String HEADER = lines(
            "#version 310 es",
            "precision highp float;",
            "precision highp int;"
    );

    private static final String STATE = lines(
            "layout(std430, binding = 3) buffer ProcessorState {",
            "    vec4 rangeState;",      // frame low/high, EMA low/high
            "    vec4 profileA;",        // stretch low/high/inverse, subject candidate
            "    vec4 profileB;",        // subject, recenter, zero-plane anchor shift, edge fraction
            "    vec4 profileC;",        // change fraction, pop, pop ratio, cut evidence
            "    uvec4 stateFlags;",     // range init, profile init, first frame, hard cut
            "    ivec4 stateCounters;",  // scene age, cut state, valid samples, frame number
            "    uvec4 healthCounters;", // hard cuts, external requests, empty, collapsed
            "};"
    );

    private static final String EXTERNAL_SCENE_CUT = lines(
            // Binding 1 is raw-stat scratch in earlier programs, but is free in both consumers.
            // Reusing it keeps the pipeline within GLES 3.1's minimum four SSBO bindings.
            "layout(std430, binding = 1) readonly buffer ExternalSceneCut {",
            "    uint externalSceneCutWords[];",
            "};",
            "uniform int uExternalSceneCut;",
            "uniform uint uExternalSceneCutWordOffset;",
            "bool externalSceneCutRequested() {",
            "    return uExternalSceneCut != 0",
            "            || externalSceneCutWords[uExternalSceneCutWordOffset] != 0u;",
            "}"
    );

    private static final String RAW_STATS = lines(
            "layout(std430, binding = 1) buffer RawStats {",
            "    uint rawMinimum;",
            "    uint rawMaximum;",
            "    uint rawValidCount;",
            "    uint rawPadding;",
            "    uint rawHistogram[256];",
            "};"
    );

    /**
     * Raw-range resolution no longer reads the model tensor, so it temporarily aliases raw
     * scratch onto binding zero while the GPU scene-cut word occupies binding one. This lets the
     * one-thread resolve phase apply the cut to the effective range itself and avoids a separate
     * dispatch without exceeding GLES 3.1's minimum four SSBO bindings.
     */
    private static final String RAW_STATS_FOR_RESOLVE = lines(
            "layout(std430, binding = 0) buffer RawStats {",
            "    uint rawMinimum;",
            "    uint rawMaximum;",
            "    uint rawValidCount;",
            "    uint rawPadding;",
            "    uint rawHistogram[256];",
            "};"
    );

    private static final String PROFILE_STATS = lines(
            "layout(std430, binding = 2) buffer ProfileStats {",
            "    uint depthHistogram[256];",
            "    uint subjectHistogram[256];",
            "    uint edgeCount;",
            "    uint changeCount;",
            "    uint subjectWeightTotal;",
            "    uint profilePadding;",
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
            "    vec4 validMask = vec4(sampleValid.x ? 1.0 : 0.0,",
            "            sampleValid.y ? 1.0 : 0.0, sampleValid.z ? 1.0 : 0.0,",
            "            sampleValid.w ? 1.0 : 0.0);",
            "    vec4 validWeight = bilinearWeight * validMask;",
            "    float weightTotal = dot(validWeight, vec4(1.0));",
            "    if (weightTotal <= 1.0e-8) {",
            "        finiteValue = 0.0;",
            "        return false;",
            "    }",
            "    finiteValue = dot(sampleValue, validWeight) / weightTotal;",
            "    return true;",
            "}"
    );

    private static final String RAW_FIXED = lines(
            "bool rawFixed(ivec2 destination, out uint fixedValue) {",
            "    float rawValue;",
            "    if (!sourceAlignedRaw(destination, rawValue)) {",
            "        fixedValue = 0u;",
            "        return false;",
            "    }",
            "    fixedValue = uint(clamp(rawValue, 0.0, 65535.0) * 65536.0 + 0.5);",
            "    return true;",
            "}"
    );

    private static String rawInput(boolean removeReflectedPadding) {
        return RAW_INPUT_HEADER
                + (removeReflectedPadding
                ? REFLECTED_PADDING_SOURCE_ALIGNED_RAW : DIRECT_SOURCE_ALIGNED_RAW)
                + RAW_FIXED;
    }

    /** Clears both independent 256-bin scratch blocks in one 256-thread dispatch. */
    static final String RESET_ALL_STATS = HEADER + RAW_STATS + PROFILE_STATS + lines(
            "layout(local_size_x = 256) in;",
            "void main() {",
            "    uint index = gl_LocalInvocationID.x;",
            "    rawHistogram[index] = 0u;",
            "    depthHistogram[index] = 0u;",
            "    subjectHistogram[index] = 0u;",
            "    if (index == 0u) {",
            "        rawMinimum = 0xffffffffu;",
            "        rawMaximum = 0u;",
            "        rawValidCount = 0u;",
            "        rawPadding = 0u;",
            "        edgeCount = 0u;",
            "        changeCount = 0u;",
            "        subjectWeightTotal = 0u;",
            "        profilePadding = 0u;",
            "    }",
            "}"
    );

    static String rawMinMax(boolean removeReflectedPadding) {
        return HEADER + rawInput(removeReflectedPadding) + RAW_STATS + lines(
                "layout(local_size_x = 16, local_size_y = 16) in;",
                "shared uvec3 localRange[256];",
                "void main() {",
                "    uint lane = gl_LocalInvocationIndex;",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    uint value = 0u;",
                "    bool valid = all(lessThan(point, uOutputSize)) && rawFixed(point, value);",
                "    localRange[lane] = valid ? uvec3(value, value, 1u)",
                "            : uvec3(0xffffffffu, 0u, 0u);",
                "    barrier();",
                "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
                "        if (lane < stride) {",
                "            uvec3 right = localRange[lane + stride];",
                "            localRange[lane].x = min(localRange[lane].x, right.x);",
                "            localRange[lane].y = max(localRange[lane].y, right.y);",
                "            localRange[lane].z += right.z;",
                "        }",
                "        barrier();",
                "    }",
                "    if (lane == 0u && localRange[0].z != 0u) {",
                "        atomicMin(rawMinimum, localRange[0].x);",
                "        atomicMax(rawMaximum, localRange[0].y);",
                "        atomicAdd(rawValidCount, localRange[0].z);",
                "    }",
                "}"
        );
    }

    static final String RAW_MIN_MAX = rawMinMax(true);

    static String rawHistogram(boolean removeReflectedPadding) {
        return HEADER + rawInput(removeReflectedPadding) + RAW_STATS + STATE + lines(
                "layout(local_size_x = 16, local_size_y = 16) in;",
                "uniform sampler2D uPreviousDepth;",
                "shared uint localHistogram[256];",
                "shared uint localChangeCount[256];",
                "void main() {",
                "    uint lane = gl_LocalInvocationIndex;",
                "    localHistogram[lane] = 0u;",
                "    localChangeCount[lane] = 0u;",
                "    barrier();",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    uint value = 0u;",
                "    bool valid = all(lessThan(point, uOutputSize)) && rawFixed(point, value);",
                "    if (valid) {",
                "        uint rangeValue = rawMaximum - rawMinimum;",
                "        uint bin = rangeValue == 0u ? 0u : min(uint(",
                "                float(value - rawMinimum) * 256.0 / float(rangeValue)), 255u);",
                // Histogram atomics stay in fast workgroup memory; only occupied bins are merged
                // into the global scratch block once per workgroup below.
                "        atomicAdd(localHistogram[bin], 1u);",
                "        if (stateFlags.y != 0u && stateFlags.x != 0u) {",
                "            float rawValue = float(value) / 65536.0;",
                "            float denominator = max(rangeState.w - rangeState.z, 1.0e-6);",
                "            float current = clamp((rawValue - rangeState.z) / denominator,",
                "                    0.0, 1.0);",
                "            float previous = texelFetch(uPreviousDepth, point, 0).r;",
                "            localChangeCount[lane] = abs(current - previous) >= 0.12",
                "                    ? 1u : 0u;",
                "        }",
                "    }",
                "    barrier();",
                "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
                "        if (lane < stride)",
                "            localChangeCount[lane] += localChangeCount[lane + stride];",
                "        barrier();",
                "    }",
                "    uint binCount = localHistogram[lane];",
                "    if (binCount != 0u) atomicAdd(rawHistogram[lane], binCount);",
                // rawPadding is intentionally the broad depth-change count after reset. Giving
                // it a real meaning avoids a second raw-tensor and previous-depth read later.
                "    if (lane == 0u && localChangeCount[0] != 0u)",
                "        atomicAdd(rawPadding, localChangeCount[0]);",
                "}"
        );
    }

    static final String RAW_HISTOGRAM = rawHistogram(true);

    static final String RESOLVE_RAW_RANGE = HEADER + RAW_STATS_FOR_RESOLVE + STATE
            + EXTERNAL_SCENE_CUT + lines(
            "layout(local_size_x = 1) in;",
            "uniform float uRangeAlpha;",
            // Take the crossing bin's OUTER edge, not its centre. A percentile only excludes its
            // nominal fraction when the distribution is smooth across that bin; when a large atom
            // sits there -- sky, a far wall, a flat background -- a centred bound cuts through the
            // atom and clips a large share of the frame to a single depth. An outer edge can only
            // widen the range, so it clips at most the nominal fraction, for at most one bin of
            // precision. This matters MORE on the client: with ~6x fewer pixels the per-bin
            // quantization is coarser.
            "float percentileValue(float percentile, float minimumValue, float binWidth,",
            "        float edge) {",
            "    float target = percentile * float(rawValidCount);",
            "    uint cumulative = 0u;",
            "    for (uint bin = 0u; bin < 256u; ++bin) {",
            "        cumulative += rawHistogram[bin];",
            "        if (float(cumulative) >= target)",
            "            return minimumValue + (float(bin) + edge) * binWidth;",
            "    }",
            "    return minimumValue + (255.0 + edge) * binWidth;",
            "}",
            "void applyExternalCutRange() {",
            "    if (stateFlags.x != 0u && externalSceneCutRequested()) {",
            "        rangeState.zw = rangeState.xy;",
            "        stateFlags.w = 1u;",
            "    }",
            "}",
            "void main() {",
            "    stateFlags.z = 0u;",
            // A hard-cut bit describes this frame only. It is re-established below from depth
            // evidence or the external cut before temporal filtering begins.
            "    stateFlags.w = 0u;",
            "    profileC.x = 0.0;",
            "    profileC.w = 0.0;",
            "    stateCounters.z = int(rawValidCount);",
            "    if (rawValidCount == 0u) {",
            "        healthCounters.z = min(healthCounters.z + 1u, 0xfffffffeu);",
            // Keep the previous frame's raw range behavior for an empty external-cut frame.
            "        applyExternalCutRange();",
            "        return;",
            "    }",
            "    float minimumValue = float(rawMinimum) / 65536.0;",
            "    float maximumValue = float(rawMaximum) / 65536.0;",
            "    float rawRange = maximumValue - minimumValue;",
            "    float frameLow = minimumValue;",
            "    float frameHigh = maximumValue;",
            "    if (rawRange > 0.0) {",
            "        float binWidth = rawRange / 256.0;",
            "        float lowCandidate = percentileValue(0.02, minimumValue, binWidth, 0.0);",
            "        float highCandidate = percentileValue(0.98, minimumValue, binWidth, 1.0);",
            "        if (highCandidate - lowCandidate > 1.0e-9) {",
            "            frameLow = lowCandidate;",
            "            frameHigh = highCandidate;",
            "        }",
            "    }",
            "    float collapseScale = max(1.0, max(abs(frameLow), abs(frameHigh)));",
            "    if (frameHigh - frameLow <= collapseScale * 1.0e-5)",
            "        healthCounters.w = min(healthCounters.w + 1u, 0xfffffffeu);",
            "    bool firstFrame = stateFlags.x == 0u;",
            "    float previousRange = max(rangeState.w - rangeState.z, 1.0e-6);",
            "    float distributionShift = firstFrame ? 0.0 : max(",
            "            abs(frameLow - rangeState.z), abs(frameHigh - rangeState.w))",
            "            / previousRange;",
            "    float changeFraction = float(rawPadding) / float(rawValidCount);",
            "    float internalCutEvidence = clamp(0.80 * changeFraction",
            "            + 0.20 * min(distributionShift / 0.15, 1.0), 0.0, 1.0);",
            // Decide broad depth cuts before mapping this frame. This is the same-frame phase
            // boundary that prevents a new scene from being normalized with the old scene's EMA.
            "    bool internalCut = !firstFrame && stateFlags.y != 0u",
            "            && stateCounters.y > 0 && (changeFraction >= 0.58",
            "            || (changeFraction >= 0.42 && distributionShift >= 0.10));",
            "    if (firstFrame || internalCut) {",
            "        rangeState.zw = vec2(frameLow, frameHigh);",
            "        stateFlags.x = 1u;",
            "    } else {",
            // Attack fast, release slow. A symmetric EMA lags the live percentiles, and any frame
            // whose smoothed range is NARROWER than this frame's clips the difference away -- lag
            // becomes clipped depth. Expanding immediately makes that impossible; contraction still
            // decays at uRangeAlpha. Expansion is also the stability-safe direction: the range is a
            // multiplicative gain, so growing it LOWERS the gain, and it is fast shrinking that
            // makes the depth scale breathe.
            "        vec2 smoothed = mix(rangeState.zw, vec2(frameLow, frameHigh), uRangeAlpha);",
            "        rangeState.z = min(smoothed.x, frameLow);",
            "        rangeState.w = max(smoothed.y, frameHigh);",
            "    }",
            "    rangeState.xy = vec2(frameLow, frameHigh);",
            "    stateFlags.z = firstFrame ? 1u : 0u;",
            "    stateFlags.w = internalCut ? 1u : 0u;",
            "    profileC.x = changeFraction;",
            "    profileC.w = internalCutEvidence;",
            // This runs in the same single invocation that owns rangeState, so no additional
            // dispatch or cross-workgroup ordering is required.
            "    applyExternalCutRange();",
            "}"
    );

    static String temporalFilter(boolean removeReflectedPadding) {
        return HEADER + rawInput(removeReflectedPadding)
                + STATE + EXTERNAL_SCENE_CUT + lines(
                "layout(local_size_x = 16, local_size_y = 16) in;",
                "uniform sampler2D uPreviousDepth;",
                "uniform float uDepthAlpha;",
                "uniform float uMovingDepthAlpha;",
                "uniform float uSpatialThresholdScale;",
                "layout(r32f, binding = 0) uniform writeonly highp image2D uCurrentDepth;",
                "bool mappedDepth(ivec2 point, out float mappedValue) {",
                "    ivec2 p = clamp(point, ivec2(0), uOutputSize - ivec2(1));",
                "    float rawValue;",
                "    if (!sourceAlignedRaw(p, rawValue) || stateFlags.x == 0u) {",
                "        mappedValue = 0.5;",
                "        return false;",
                "    }",
                "    float denominator = max(rangeState.w - rangeState.z, 1.0e-6);",
                "    mappedValue = clamp((rawValue - rangeState.z) / denominator, 0.0, 1.0);",
                "    return true;",
                "}",
                "void main() {",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    if (any(greaterThanEqual(point, uOutputSize))) return;",
                "    float previous = texelFetch(uPreviousDepth, point, 0).r;",
                "    float current;",
                "    bool currentValid = mappedDepth(point, current);",
                "    if (!currentValid) {",
                "        float retained = stateFlags.y != 0u ? previous : 0.5;",
                "        imageStore(uCurrentDepth, point, vec4(retained, 0.0, 0.0, 1.0));",
                "        return;",
                "    }",
                "    float outputDepth = current;",
                "    bool resetHistory = stateFlags.z != 0u || stateFlags.w != 0u",
                "            || externalSceneCutRequested();",
                "    if (!resetHistory) {",
                "        float change = abs(current - previous);",
                "        float gradient = 0.0;",
                "        float neighbor;",
                "        if (mappedDepth(point + ivec2(-1, 0), neighbor))",
                "            gradient = max(gradient, abs(current - neighbor));",
                "        if (mappedDepth(point + ivec2(1, 0), neighbor))",
                "            gradient = max(gradient, abs(current - neighbor));",
                "        if (mappedDepth(point + ivec2(0, -1), neighbor))",
                "            gradient = max(gradient, abs(current - neighbor));",
                "        if (mappedDepth(point + ivec2(0, 1), neighbor))",
                "            gradient = max(gradient, abs(current - neighbor));",
                "        float referenceGradient = gradient / max(uSpatialThresholdScale, 1.0);",
                "        float alpha = change >= 0.05 && referenceGradient >= 0.02",
                "                ? uMovingDepthAlpha : uDepthAlpha;",
                "        outputDepth = mix(previous, current, alpha);",
                "    }",
                "    imageStore(uCurrentDepth, point, vec4(outputDepth, 0.0, 0.0, 1.0));",
                "}"
        );
    }

    static String accumulateProfile(boolean removeReflectedPadding) {
        return HEADER + PROFILE_STATS + STATE + lines(
                "layout(local_size_x = 16, local_size_y = 16) in;",
            "uniform sampler2D uCurrentDepth;",
            "uniform ivec2 uOutputSize;",
            "uniform float uSpatialThresholdScale;",
                "shared uint localDepthHistogram[256];",
                "shared uint localSubjectHistogram[256];",
                "shared uvec2 localTotals[256];",
                "void main() {",
                "    uint lane = gl_LocalInvocationIndex;",
                "    localDepthHistogram[lane] = 0u;",
                "    localSubjectHistogram[lane] = 0u;",
                "    localTotals[lane] = uvec2(0u);",
                "    barrier();",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    if (all(lessThan(point, uOutputSize))) {",
                "        float value = clamp(texelFetch(uCurrentDepth, point, 0).r, 0.0, 1.0);",
                "        ivec2 right = min(point + ivec2(1, 0), uOutputSize - ivec2(1));",
                "        ivec2 down = min(point + ivec2(0, 1), uOutputSize - ivec2(1));",
                "        float gx = texelFetch(uCurrentDepth, right, 0).r - value;",
                "        float gy = texelFetch(uCurrentDepth, down, 0).r - value;",
                "        float gradient = sqrt(gx * gx + gy * gy);",
                "        float normalizedX = uOutputSize.x > 1",
                "                ? float(point.x) / float(uOutputSize.x - 1) * 2.0 - 1.0 : 0.0;",
                "        float normalizedY = uOutputSize.y > 1",
                "                ? float(point.y) / float(uOutputSize.y - 1) * 2.0 - 1.0 : 0.0;",
                "        float centerWeight = exp(-0.5 * (",
                "                (normalizedX / 0.70) * (normalizedX / 0.70)",
                "                + (normalizedY / 0.55) * (normalizedY / 0.55)));",
                // Express finite differences in Apollo's aspect-matched reference-texel units
                // before classifying edges or suppressing them from the subject histogram.
                "        float referenceGradient = gradient / max(uSpatialThresholdScale, 1.0);",
                "        float sigmoidValue = 1.0 / (1.0 + exp(-10.0 * (referenceGradient - 0.025)));",
                "        uint weight = uint(centerWeight * (1.0 - sigmoidValue) * 1024.0 + 0.5);",
                "        uint bin = min(uint(value * 256.0), 255u);",
                // Contended histogram updates remain local to the workgroup. One invocation per
                // occupied bin performs the substantially cheaper global merge below.
                "        atomicAdd(localDepthHistogram[bin], 1u);",
                "        atomicAdd(localSubjectHistogram[bin], weight);",
                // Weight the edge statistic by gradient MAGNITUDE in fixed point, not a bare
                // threshold count: a violent silhouette must outweigh a marginal one, or the risk
                // statistic cannot tell a soft gradient field from a shattered one. Matches
                // Apollo's min(grad/0.02, 8) * 256, evaluated on the grid-normalized gradient.
                "        uint edgeWeight = referenceGradient >= 0.02",
                "                ? uint(min(referenceGradient * 50.0, 8.0) * 256.0 + 0.5) : 0u;",
                "        localTotals[lane] = uvec2(edgeWeight, weight);",
                "    }",
                "    barrier();",
                "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
                "        if (lane < stride) localTotals[lane] += localTotals[lane + stride];",
                "        barrier();",
                "    }",
                "    uint depthCount = localDepthHistogram[lane];",
                "    uint subjectWeight = localSubjectHistogram[lane];",
                "    if (depthCount != 0u) atomicAdd(depthHistogram[lane], depthCount);",
                "    if (subjectWeight != 0u)",
                "        atomicAdd(subjectHistogram[lane], subjectWeight);",
                "    if (lane == 0u) {",
                "        if (localTotals[0].x != 0u) atomicAdd(edgeCount, localTotals[0].x);",
                "        if (localTotals[0].y != 0u)",
                "            atomicAdd(subjectWeightTotal, localTotals[0].y);",
                "    }",
                "}"
        );
    }

    static final String ACCUMULATE_PROFILE = accumulateProfile(true);

    static final String RESOLVE_PROFILE = HEADER + PROFILE_STATS + STATE
            + EXTERNAL_SCENE_CUT + lines(
            "layout(local_size_x = 1) in;",
            "layout(rgba32f, binding = 1) uniform writeonly highp image2D uProfileTexture;",
            "uniform int uPixelCount;",
            "uniform float uSubjectAlpha;",
            "uniform float uBandAlpha;",
            "uniform float uSpatialThresholdScale;",
            "uniform int uReferenceFrameAdvance;",
            // `edge` selects the crossing bin's lower (0.0), centre (0.5) or upper (1.0) bound.
            // Band BOUNDS use outer edges so a large atom is not cut through; the median is a point
            // estimate, not a bound, so it keeps the centre.
            "float depthPercentile(float percentile, float edge) {",
            "    float target = percentile * float(uPixelCount);",
            "    uint cumulative = 0u;",
            "    for (uint bin = 0u; bin < 256u; ++bin) {",
            "        cumulative += depthHistogram[bin];",
            "        if (float(cumulative) >= target) return (float(bin) + edge) / 256.0;",
            "    }",
            "    return (255.0 + edge) / 256.0;",
            "}",
            // Must shape identically to the warp's shapedDepth(), or the anchor stops describing
            // the plane the warp renders. Same single clamp, same order.
            "float bestv2RawShift(float d) {",
            "    d = clamp(d, 0.0, 1.0);",
            "    return -1.39635933 + d * (2.776208766 + d * (21.04503417 + d *",
            "        (-94.6673759 + d * (376.6610774 + d * (-645.141824 + d *",
            "        (482.8701123 - 133.5645677 * d))))));",
            "}",
            "float shapedDepth(float d, float low, float inverse, float recenter) {",
            "    return clamp((d - low) * inverse + recenter, 0.0, 1.0);",
            "}",
            "float subjectNearPercentile() {",
            "    float target = 0.35 * float(subjectWeightTotal);",
            "    uint cumulative = 0u;",
            "    for (int bin = 255; bin >= 0; --bin) {",
            "        cumulative += subjectHistogram[bin];",
            "        if (float(cumulative) >= target) return (float(bin) + 0.5) / 256.0;",
            "    }",
            "    return 0.5;",
            "}",
            "void publishProfile() {",
            "    imageStore(uProfileTexture, ivec2(0, 0),",
            "            vec4(profileA.x, profileA.y, profileA.z, profileB.x));",
            // profileB.z carries the shot-latched zero-plane anchor SHIFT (source pixels), not a
            // depth. Storing the resolved shift stops later percentile/recenter motion from making
            // convergence breathe. It replaced the legacy convergence EMA, which is identically
            // zero under an explicit plane.
            "    imageStore(uProfileTexture, ivec2(1, 0),",
            "            vec4(profileB.y, profileB.z, profileC.z, float(stateFlags.y)));",
            "    imageStore(uProfileTexture, ivec2(2, 0),",
            "            vec4(rangeState.x, rangeState.y, profileB.w, profileC.x));",
            "    imageStore(uProfileTexture, ivec2(3, 0),",
            "            vec4(profileA.w, profileC.y, float(stateFlags.w), float(stateCounters.x)));",
            "}",
            "void main() {",
            // An empty model tensor must not turn the temporal fallback value (0.5) into a
            // synthetic ready profile. Preserve the last real profile, or remain uninitialized
            // during warm-up, until at least one finite raw sample exists.
            "    if (stateCounters.z <= 0 || subjectWeightTotal == 0u || uPixelCount <= 0) {",
            "        stateFlags.w = 0u;",
            "        publishProfile();",
            "        return;",
            "    }",
            "    float subjectCandidate = subjectNearPercentile();",
            // P2/P98, not P5/P95. A hard band edge maps every out-of-band pixel onto one shaped
            // depth, and the parallax field is a pure function of shaped depth, so they all render
            // at an identical disparity -- a flat plane with no relief. Widening the band removes
            // that; softening its edge was measured host-side and REJECTED, because it keeps the
            // same over-clipping and charges the band interior for it.
            "    float stretchLow = depthPercentile(0.02, 0.0);",
            "    float stretchHigh = depthPercentile(0.98, 1.0);",
            // A one-texel silhouette occupies a larger fraction of a coarser map. Normalize the
            // density back to Apollo's 434px-short-side calibration before adaptive-pop gating.
            "    float edgeFraction = float(edgeCount) / (float(uPixelCount) * 256.0)",
            "            / max(uSpatialThresholdScale, 1.0);",
            // RESOLVE_RAW_RANGE already compared the current raw frame with the previous depth
            // before choosing this frame's effective normalization range.
            "    float changeFraction = profileC.x;",
            "    bool wasInitialized = stateFlags.y != 0u;",
            "    int sceneAge = wasInitialized ? min(stateCounters.x",
            "            + max(uReferenceFrameAdvance, 1), 65535) : 0;",
            "    int cutState = stateCounters.y;",
            "    bool cutReady = cutState > 0;",
            "    float internalCutEvidence = profileC.w;",
            "    bool externalCut = externalSceneCutRequested();",
            // stateFlags.w was decided before temporal filtering, so both external and internal
            // cuts use this frame's range and bypass old-scene history in the current dispatch.
            "    bool internalCut = stateFlags.w != 0u && !externalCut;",
            "    bool hardCut = wasInitialized && (externalCut || internalCut);",
            // Damp the band. lo/inverse form a MULTIPLICATIVE gain, so an unsmoothed band makes the
            // depth mapping breathe between cuts and that wobble is then multiplied by pop
            // strength. Same attack-fast/release-slow rule as the raw range, smoothed in (lo, hi)
            // space rather than on the reciprocal.
            "    if (wasInitialized && !hardCut) {",
            "        float smoothLow = mix(profileA.x, stretchLow, uBandAlpha);",
            "        float smoothHigh = mix(profileA.y, stretchHigh, uBandAlpha);",
            "        stretchLow = min(smoothLow, stretchLow);",
            "        stretchHigh = max(smoothHigh, stretchHigh);",
            "    }",
            "    float stretchInverse = 1.0 / max(stretchHigh - stretchLow, 1.0e-4);",
            "    if (!cutReady && wasInitialized && sceneAge >= 8) {",
            "        cutState = 1;",
            "        cutReady = true;",
            "    }",
            "    if (!wasInitialized || hardCut) sceneAge = 0;",
            "    if (hardCut) {",
            "        cutState = -1;",
            "    } else if (cutState < 0 && (changeFraction < 0.35 || sceneAge >= 2)) {",
            "        cutState = 1;",
            "    }",
            "    float subjectDepth = !wasInitialized || hardCut ? subjectCandidate",
            "            : mix(profileB.x, subjectCandidate, uSubjectAlpha);",
            "    float stretchedSubject = clamp((subjectDepth - stretchLow) * stretchInverse, 0.0, 1.0);",
            "    float recenter = (0.5 - stretchedSubject) * 0.35;",
            // Shot-latched explicit zero plane (Apollo `median`), replacing the retired per-frame
            // legacy anchor. Resolve TWICE per shot: immediately, so a new shot never renders on
            // the previous shot's plane, then once more when the depth field has settled --
            // normalization settling perturbs 50-60% of texels on the first frames, and a bad latch
            // here is unrecoverable until the next cut. Between those it must not move.
            //
            // The settle test is a CROSSING, not equality: the client advances sceneAge by
            // uReferenceFrameAdvance per depth update, so it can step straight over the threshold.
            "    int previousAge = stateCounters.x;",
            "    bool settledNow = wasInitialized && !hardCut",
            "            && previousAge < 8 && sceneAge >= 8;",
            "    float anchorShift = profileB.z;",
            "    if (!wasInitialized || hardCut || settledNow) {",
            "        float medianDepth = depthPercentile(0.50, 0.5);",
            "        anchorShift = bestv2RawShift(",
            "                shapedDepth(medianDepth, stretchLow, stretchInverse, recenter));",
            "    }",
            // Adaptive pop. Endpoints are calibrated against MEASURED weighted edge density:
            // real footage spans roughly 0.038-0.245 with a median near 0.10, so the previous
            // 0.007/0.016 pair saturated on every real scene and pinned the controller to its
            // floor. The band is 1.20-2.00 (ratio 1.67); the previous 1.25-1.30 was a 1.04 ratio,
            // below the noise floor of every metric that could judge it.
            //
            // Classify on a SETTLED field, never on the cut frame: normalization settling changes
            // 50-60% of depth texels on the first updates, so a busy scene reads smoother than it
            // is and would hold full pop for the whole shot. Hold the floor until the settle
            // crossing, latch once, then stay bit-stable until the next cut.
            "    float popStrength = wasInitialized ? profileC.y : 1.20;",
            "    if (!wasInitialized || hardCut) {",
            "        popStrength = 1.20;",
            "    } else if (settledNow) {",
            "        float confidence = 1.0 - smoothstep(0.04, 0.20, edgeFraction);",
            "        popStrength = mix(1.20, 2.00, confidence);",
            "    }",
            "    profileA = vec4(stretchLow, stretchHigh, stretchInverse, subjectCandidate);",
            "    profileB = vec4(subjectDepth, recenter, anchorShift, edgeFraction);",
            "    float hardCutEvidence = externalCut ? 2.0 : internalCutEvidence;",
            "    profileC = vec4(changeFraction, popStrength, popStrength / 1.20, hardCutEvidence);",
            "    stateFlags.y = 1u;",
            "    stateFlags.w = hardCut ? 1u : 0u;",
            "    if (hardCut) healthCounters.x = min(healthCounters.x + 1u, 0xfffffffeu);",
            "    if (externalCut)",
            "        healthCounters.y = min(healthCounters.y + 1u, 0xfffffffeu);",
            "    stateCounters.x = sceneAge;",
            "    stateCounters.y = cutState;",
            "    stateCounters.w = min(stateCounters.w + 1, 2147483647);",
            "    publishProfile();",
            "}"
    );

    static final String RESET_STATE = HEADER + STATE + lines(
            "layout(local_size_x = 1) in;",
            "layout(rgba32f, binding = 1) uniform writeonly highp image2D uProfileTexture;",
            "void main() {",
            "    rangeState = vec4(0.0);",
            "    profileA = vec4(0.0, 1.0, 1.0, 0.5);",
            "    profileB = vec4(0.5, 0.0, 0.0, 0.0);",
            "    profileC = vec4(0.0, 1.20, 1.0, 0.0);",
            "    stateFlags = uvec4(0u);",
            "    stateCounters = ivec4(0);",
            "    healthCounters = uvec4(0u);",
            "    imageStore(uProfileTexture, ivec2(0, 0), vec4(0.0, 1.0, 1.0, 0.5));",
            "    imageStore(uProfileTexture, ivec2(1, 0), vec4(0.0, 0.0, 1.0, 0.0));",
            "    imageStore(uProfileTexture, ivec2(2, 0), vec4(0.0));",
            "    imageStore(uProfileTexture, ivec2(3, 0), vec4(0.5, 1.20, 0.0, 0.0));",
            "}"
    );
}
