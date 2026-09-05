package com.limelight.sbs;

/**
 * Compute shaders used by {@link ClientSbsGpuDepthProcessor}.
 *
 * <p>The bindings are deliberately fixed and private to one processor dispatch:</p>
 * <ul>
 *     <li>SSBO 0: packed Float32 model output, or raw scratch during range resolve</li>
 *     <li>SSBO 1: raw range/histogram scratch, then the per-slot GPU scene-cut word</li>
 *     <li>SSBO 2: reserved</li>
 *     <li>SSBO 3: persistent temporal/profile state</li>
 *     <li>image 0: current normalized depth used only by cut analysis</li>
 *     <li>image 1: renderer profile texture</li>
 *     <li>image 2: current source-aligned raw ZipDepth field</li>
 *     <li>image 3: last reliable normalized cut-reference depth</li>
 * </ul>
 */
final class ClientSbsGpuDepthShaders {
    private ClientSbsGpuDepthShaders() {
    }

    // Retained only by the non-production legacy profile shader used by offline shader tests.
    // The live V2 path publishes a fixed 1.75 pop and never compiles that shader.
    private static final String POP_FLOOR = String.format(java.util.Locale.US, "%.2f",
            ClientSbsGpuDepthProcessor.LEGACY_ADAPTIVE_POP_FLOOR);
    private static final String POP_CEILING = String.format(java.util.Locale.US, "%.2f",
            ClientSbsGpuDepthProcessor.LEGACY_ADAPTIVE_POP_CEILING);
    private static final String UNCLASSIFIED_EDGE = String.format(java.util.Locale.US, "%.1f",
            ClientSbsGpuDepthProcessor.LEGACY_ADAPTIVE_POP_UNCLASSIFIED_EDGE);
    private static final String FIXED_POP = String.format(java.util.Locale.US, "%.2f",
            ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH);

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
            "    vec4 profileB;",        // subject, recenter, anchor shift, settle-latched edge
            "    vec4 profileC;",        // change fraction, pop, pop ratio, cut evidence
            "    uvec4 stateFlags;",     // range init, profile init, first/hold bits, relatch pulse
            "    ivec4 stateCounters;",  // profile age, cut/hysteresis state, valid samples, frame number
            "    uvec4 healthCounters;", // hard cuts, appearance proposals, empty, collapsed
            "    vec2 cutStateAux;",      // geometry-change EMA, initialized
            "    ivec2 cutStateCounters;", // source-frame age, low-structure scene marker
            "    vec4 v2Camera;",         // shot mean, current mean, valid count, camera valid
            // Append-only causal telemetry. Existing offsets through v2Camera remain unchanged.
            "    uvec4 cutReasonCounters;", // proposals, appearance, geometry, structureless
            "    uvec4 cutAppearanceStats;", // blocks, raw-moderate, raw-delta sum, structural
            "    uvec4 cutAppearanceMeta;", // event current/common support, detector/decision bits
            "    vec4 cutDepthDiagnostics;", // event change, range shift, cut score, baseline
            "    uvec4 cutEventMeta;", // latched-event sequence, reserved
            "};"
    );

    private static final String FRAME_STATE_CONSTANTS = lines(
            "const uint FRAME_STATE_FIRST_DEPTH = "
                    + ClientSbsShotCutPolicy.FRAME_STATE_FIRST_DEPTH + "u;",
            "const uint FRAME_STATE_HOLD_RELIABLE_HISTORY = "
                    + ClientSbsShotCutPolicy.FRAME_STATE_HOLD_RELIABLE_HISTORY + "u;",
            "const uint FRAME_STATE_HISTORY_ADVANCES = "
                    + ClientSbsShotCutPolicy.FRAME_STATE_HISTORY_ADVANCES + "u;",
            "const uint FRAME_STATE_CURRENT_DEPTH_VALID = "
                    + ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_DEPTH_VALID + "u;",
            "const uint FRAME_STATE_CURRENT_V2_VALID = "
                    + ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_V2_VALID + "u;",
            "const uint FRAME_STATE_STRUCTURELESS_GAP = "
                    + ClientSbsShotCutPolicy.FRAME_STATE_STRUCTURELESS_GAP + "u;"
    );

    private static final String SHOT_CUT_STATE_CONSTANTS = lines(
            "const int CUT_STATE_SETTLED = "
                    + ClientSbsShotCutPolicy.CUT_STATE_SETTLED + ";",
            "const int CUT_STATE_GEOMETRY_ARMED = "
                    + ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_ARMED + ";",
            "const int CUT_STATE_APPEARANCE_ARMED = "
                    + ClientSbsShotCutPolicy.CUT_STATE_APPEARANCE_ARMED + ";",
            "const int CUT_STATE_GEOMETRY_ONE_LOW = "
                    + ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_ONE_LOW + ";",
            "const int CUT_STATE_APPEARANCE_ONE_QUIET = "
                    + ClientSbsShotCutPolicy.CUT_STATE_APPEARANCE_ONE_QUIET + ";",
            "const int CUT_STATE_GEOMETRY_LATCHED = "
                    + ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_LATCHED + ";",
            "const int CUT_STATE_APPEARANCE_LATCHED = "
                    + ClientSbsShotCutPolicy.CUT_STATE_APPEARANCE_LATCHED + ";",
            "const int CUT_STATE_GEOMETRY_CONFIRMATION_PENDING = "
                    + ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_CONFIRMATION_PENDING + ";",
            "const int CUT_STATE_APPEARANCE_RECOVERY = "
                    + ClientSbsShotCutPolicy.CUT_STATE_APPEARANCE_RECOVERY + ";",
            "const int CUT_STATE_READY = "
                    + ClientSbsShotCutPolicy.CUT_STATE_READY + ";",
            "const int CUT_STATE_LATCHED = "
                    + ClientSbsShotCutPolicy.CUT_STATE_LATCHED + ";"
    );

    private static final String EXTERNAL_SCENE_CUT = lines(
            // Binding 1 is raw-stat scratch in earlier programs, but is free in both consumers.
            // Reusing it keeps the pipeline within GLES 3.1's minimum four SSBO bindings.
            "layout(std430, binding = 1) readonly buffer ExternalSceneCut {",
            "    uint externalSceneCutWords[];",
            "};",
            "uniform int uExternalSceneCut;",
            "uniform int uSceneEvidenceAvailable;",
            "uniform uint uExternalSceneCutWordOffset;",
            "const uint SCENE_CUT_RECORD_EVIDENCE = 0u;",
            "const uint SCENE_CUT_RECORD_BLOCK_COUNT = 1u;",
            "const uint SCENE_CUT_RECORD_RAW_MODERATE_COUNT = 2u;",
            "const uint SCENE_CUT_RECORD_RAW_DELTA_SUM = 3u;",
            "const uint SCENE_CUT_RECORD_STRUCTURAL_CHANGE_COUNT = 4u;",
            "const uint SCENE_CUT_RECORD_CURRENT_SUPPORT_COUNT = 5u;",
            "const uint SCENE_CUT_RECORD_COMMON_SUPPORT_COUNT = 6u;",
            "const uint SCENE_CUT_RECORD_DIAGNOSTIC_FLAGS = 7u;",
            "const uint SCENE_EVIDENCE_APPEARANCE = "
                    + ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE + "u;",
            "const uint SCENE_EVIDENCE_EXPOSURE_LIKE = "
                    + ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE + "u;",
            "const uint SCENE_EVIDENCE_PERSISTENT_LOW_START = "
                    + ClientSbsShotCutPolicy.SCENE_EVIDENCE_PERSISTENT_LOW_START + "u;",
            "const uint SCENE_EVIDENCE_SUPPORTED_RETURN = "
                    + ClientSbsShotCutPolicy.SCENE_EVIDENCE_SUPPORTED_RETURN + "u;",
            "const uint SCENE_EVIDENCE_CLASSIFICATION_MASK =",
            "        SCENE_EVIDENCE_APPEARANCE | SCENE_EVIDENCE_EXPOSURE_LIKE;",
            "const uint SCENE_EVIDENCE_EVENT_MASK =",
            "        SCENE_EVIDENCE_PERSISTENT_LOW_START | SCENE_EVIDENCE_SUPPORTED_RETURN;",
            "uint externalSceneEvidence() {",
            // The explicit CPU request retains its historical cut semantics. Only the automatic
            // GPU classifier can assert the exposure-like geometry veto. Normalize to one typed
            // classification while retaining event-scoped history transitions; appearance wins
            // malformed dual-classification input as a fail-safe.
            "    uint evidence = (uExternalSceneCut != 0",
            "            ? SCENE_EVIDENCE_APPEARANCE : 0u)",
            "            | externalSceneCutWords[uExternalSceneCutWordOffset",
            "                    + SCENE_CUT_RECORD_EVIDENCE];",
            "    uint events = evidence & SCENE_EVIDENCE_EVENT_MASK;",
            "    if ((evidence & SCENE_EVIDENCE_APPEARANCE) != 0u)",
            "        return events | SCENE_EVIDENCE_APPEARANCE;",
            "    if ((evidence & SCENE_EVIDENCE_EXPOSURE_LIKE) != 0u)",
            "        return events | SCENE_EVIDENCE_EXPOSURE_LIKE;",
            "    return events;",
            "}",
            "uvec4 externalSceneAppearanceStats() {",
            "    return uvec4(",
            "            externalSceneCutWords[uExternalSceneCutWordOffset",
            "                    + SCENE_CUT_RECORD_BLOCK_COUNT],",
            "            externalSceneCutWords[uExternalSceneCutWordOffset",
            "                    + SCENE_CUT_RECORD_RAW_MODERATE_COUNT],",
            "            externalSceneCutWords[uExternalSceneCutWordOffset",
            "                    + SCENE_CUT_RECORD_RAW_DELTA_SUM],",
            "            externalSceneCutWords[uExternalSceneCutWordOffset",
            "                    + SCENE_CUT_RECORD_STRUCTURAL_CHANGE_COUNT]);",
            "}",
            "uvec3 externalSceneAppearanceMeta() {",
            "    return uvec3(",
            "            externalSceneCutWords[uExternalSceneCutWordOffset",
            "                    + SCENE_CUT_RECORD_CURRENT_SUPPORT_COUNT],",
            "            externalSceneCutWords[uExternalSceneCutWordOffset",
            "                    + SCENE_CUT_RECORD_COMMON_SUPPORT_COUNT],",
            "            externalSceneCutWords[uExternalSceneCutWordOffset",
            "                    + SCENE_CUT_RECORD_DIAGNOSTIC_FLAGS]);",
            "}",
            "bool externalSceneCutRequested() {",
            "    return (externalSceneEvidence() & SCENE_EVIDENCE_APPEARANCE) != 0u;",
            "}",
            "bool externalExposureLikeTransition() {",
            "    uint evidence = externalSceneEvidence();",
            "    return (evidence & SCENE_EVIDENCE_APPEARANCE) == 0u",
            "            && (evidence & SCENE_EVIDENCE_EXPOSURE_LIKE) != 0u;",
            "}"
    );

    private static final String RAW_STATS = lines(
            "layout(std430, binding = 1) buffer RawStats {",
            "    uint rawMinimum;",
            "    uint rawMaximum;",
            "    uint rawValidCount;",
            "    uint rawPadding;",
            "    uint rawHistogram[256];",
            "    uvec4 rawGroupMoments[];",
            "};"
    );

    /**
     * Scalar resolution no longer reads the model tensor, so it temporarily aliases raw scratch
     * onto binding zero while the final resolver uses binding one for the GPU scene-cut record.
     * This keeps the six-dispatch pipeline within GLES 3.1's minimum four SSBO bindings.
     */
    private static final String RAW_STATS_FOR_RESOLVE = lines(
            "layout(std430, binding = 0) buffer RawStats {",
            "    uint rawMinimum;",
            "    uint rawMaximum;",
            "    uint rawValidCount;",
            "    uint rawPadding;",
            "    uint rawHistogram[256];",
            "    uvec4 rawGroupMoments[];",
            "};"
    );

    /** Retired normalized-profile scratch; never allocated or dispatched by production V2. */
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

    /** Clears the raw percentile histogram and range counters in one 256-thread dispatch. */
    static final String RESET_ALL_STATS = HEADER + RAW_STATS + lines(
            "layout(local_size_x = 256) in;",
            "void main() {",
            "    uint index = gl_LocalInvocationID.x;",
            "    rawHistogram[index] = 0u;",
            "    if (index == 0u) {",
            "        rawMinimum = 0xffffffffu;",
            "        rawMaximum = 0u;",
            "        rawValidCount = 0u;",
            "        rawPadding = 0u;",
            "    }",
            "}"
    );

    static String rawMinMax(boolean removeReflectedPadding) {
        return HEADER + rawInput(removeReflectedPadding) + RAW_STATS + STATE
                + FRAME_STATE_CONSTANTS + lines(
                "layout(local_size_x = 16, local_size_y = 16) in;",
                "uniform sampler2D uPreviousTemporalDepth;",
                "layout(r32f, binding = 3) uniform writeonly highp image2D uReliableDepth;",
                "shared uvec3 localRange[256];",
                "shared float localMean[256];",
                "shared float localM2[256];",
                "void main() {",
                "    uint lane = gl_LocalInvocationIndex;",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                // Commit the preceding result only after its scalar cut decision exists, but before
                // this result can observe reliable depth. Reuse never dispatches this shader.
                "    bool inBounds = all(lessThan(point, uOutputSize));",
                "    if (inBounds) {",
                "        if ((stateFlags.z & FRAME_STATE_HISTORY_ADVANCES) != 0u) {",
                "            float committed = texelFetch(uPreviousTemporalDepth, point, 0).r;",
                "            imageStore(uReliableDepth, point, vec4(committed, 0.0, 0.0, 1.0));",
                "        } else if (stateFlags.x == 0u) {",
                // resetTemporalState invalidates state rather than clearing four full textures.
                // Clear the cut reference here so first-frame evidence is deterministic.
                "            imageStore(uReliableDepth, point, vec4(0.0, 0.0, 0.0, 1.0));",
                "        }",
                "    }",
                "    float rawValue = 0.0;",
                "    bool valid = inBounds",
                "            && sourceAlignedRaw(point, rawValue);",
                "    uint value = valid",
                "            ? uint(clamp(rawValue, 0.0, 65535.0) * 65536.0 + 0.5) : 0u;",
                "    localRange[lane] = valid ? uvec3(value, value, 1u)",
                "            : uvec3(0xffffffffu, 0u, 0u);",
                "    localMean[lane] = valid ? rawValue : 0.0;",
                "    localM2[lane] = 0.0;",
                "    barrier();",
                "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
                "        if (lane < stride) {",
                "            uvec3 right = localRange[lane + stride];",
                "            uint leftCount = localRange[lane].z;",
                "            uint rightCount = right.z;",
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
                "            localRange[lane].x = min(localRange[lane].x, right.x);",
                "            localRange[lane].y = max(localRange[lane].y, right.y);",
                "            localRange[lane].z += right.z;",
                "        }",
                "        barrier();",
                "    }",
                "    if (lane == 0u) {",
                "        uint groupIndex = gl_WorkGroupID.y * gl_NumWorkGroups.x",
                "                + gl_WorkGroupID.x;",
                "        rawGroupMoments[groupIndex] = uvec4(floatBitsToUint(localMean[0]),",
                "                floatBitsToUint(localM2[0]), localRange[0].z, 0u);",
                "        if (localRange[0].z != 0u) {",
                "            atomicMin(rawMinimum, localRange[0].x);",
                "            atomicMax(rawMaximum, localRange[0].y);",
                "            atomicAdd(rawValidCount, localRange[0].z);",
                "        }",
                "    }",
                "}"
        );
    }

    static final String RAW_MIN_MAX = rawMinMax(true);

    static String rawHistogram(boolean removeReflectedPadding) {
        return HEADER + rawInput(removeReflectedPadding) + RAW_STATS + lines(
                "layout(local_size_x = 16, local_size_y = 16) in;",
                "shared uint localHistogram[256];",
                "void main() {",
                "    uint lane = gl_LocalInvocationIndex;",
                "    localHistogram[lane] = 0u;",
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
                "    }",
                // Every lane must finish contributing before the lane owning each bin publishes it.
                "    barrier();",
                "    uint binCount = localHistogram[lane];",
                "    if (binCount != 0u) atomicAdd(rawHistogram[lane], binCount);",
                "}"
        );
    }

    static final String RAW_HISTOGRAM = rawHistogram(true);

    /** Resolves current validity and the normalization range before temporal filtering. */
    static final String RESOLVE_RAW_RANGE = HEADER + RAW_STATS_FOR_RESOLVE + STATE
            + FRAME_STATE_CONSTANTS + lines(
            "layout(local_size_x = 1) in;",
            "uniform float uRangeAlpha;",
            "uniform int uExpectedPixelCount;",
            "uniform int uRawGroupCount;",
            "const float V2_COLLAPSE_ABS_EPSILON = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsV2CoordinateContract.COLLAPSE_ABS_EPSILON) + ";",
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
            "void main() {",
            // These are preliminary current-result bits. The post-temporal resolver replaces them
            // with the authoritative reliable-history decision.
            "    stateFlags.z = 0u;",
            "    stateFlags.w = 0u;",
            "    stateCounters.z = int(rawValidCount);",
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
            "        stateCounters.z = 0;",
            "        healthCounters.z = min(healthCounters.z + 1u, 0xfffffffeu);",
            "        rawGroupMoments[0].w = floatBitsToUint(0.0);",
            "        return;",
            "    }",
            "    v2Camera.y = currentRawMean;",
            "    bool v2FrameValid = currentRawStd > V2_COLLAPSE_ABS_EPSILON;",
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
            // Preserve this diagnostic before changing the range. Cut resolution runs only after
            // temporal filtering and reads it from otherwise-unused per-frame scratch.
            "    float previousRange = max(rangeState.w - rangeState.z, 1.0e-6);",
            "    float distributionShift = firstFrame ? 0.0 : max(",
            "            abs(frameLow - rangeState.z), abs(frameHigh - rangeState.w))",
            "            / previousRange;",
            "    rawGroupMoments[0].w = floatBitsToUint(distributionShift);",
            "    if (firstFrame) {",
            "        rangeState.zw = vec2(frameLow, frameHigh);",
            "        stateFlags.x = 1u;",
            "    } else {",
            "        vec2 smoothed = mix(rangeState.zw, vec2(frameLow, frameHigh), uRangeAlpha);",
            "        rangeState.z = min(smoothed.x, frameLow);",
            "        rangeState.w = max(smoothed.y, frameHigh);",
            "    }",
            "    rangeState.xy = vec2(frameLow, frameHigh);",
            "    stateFlags.z = (firstFrame ? FRAME_STATE_FIRST_DEPTH : 0u)",
            "            | FRAME_STATE_CURRENT_DEPTH_VALID",
            "            | (v2FrameValid ? FRAME_STATE_CURRENT_V2_VALID : 0u);",
            "}"
    );

    /** Resolves cut ownership from current temporal depth, then publishes the V2 profile. */
    static final String RESOLVE_PROFILE = HEADER + RAW_STATS_FOR_RESOLVE + STATE
            + FRAME_STATE_CONSTANTS + SHOT_CUT_STATE_CONSTANTS + EXTERNAL_SCENE_CUT + lines(
            "layout(local_size_x = 1) in;",
            "uniform int uSourceFrameDelta;",
            "uniform int uReferenceFrameAdvance;",
            "layout(rgba32f, binding = 1) uniform writeonly highp image2D uProfileTexture;",
            "const uint CUT_DECISION_CURRENT_APPEARANCE_PROPOSAL = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_CURRENT_APPEARANCE_PROPOSAL + "u;",
            "const uint CUT_DECISION_SELECTED_APPEARANCE = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_SELECTED_APPEARANCE + "u;",
            "const uint CUT_DECISION_APPEARANCE_ARMED = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_APPEARANCE_ARMED + "u;",
            "const uint CUT_DECISION_APPEARANCE_DEPTH_CORROBORATED = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_APPEARANCE_DEPTH_CORROBORATED
                    + "u;",
            "const uint CUT_DECISION_EXPOSURE_LIKE = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_EXPOSURE_LIKE + "u;",
            "const uint CUT_DECISION_GEOMETRY_CANDIDATE = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_CANDIDATE + "u;",
            "const uint CUT_DECISION_GEOMETRY_CONFIRMATION_PENDING = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_CONFIRMATION_PENDING
                    + "u;",
            "const uint CUT_DECISION_HISTORY_ADVANCED = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_HISTORY_ADVANCED + "u;",
            "const uint CUT_DECISION_ACCEPTED_APPEARANCE = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_ACCEPTED_APPEARANCE + "u;",
            "const uint CUT_DECISION_ACCEPTED_GEOMETRY = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_ACCEPTED_GEOMETRY + "u;",
            "const uint CUT_DECISION_ACCEPTED_STRUCTURELESS_ENTRY = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_ACCEPTED_STRUCTURELESS_ENTRY
                    + "u;",
            "const uint CUT_DECISION_CURRENT_DEPTH_VALID = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_CURRENT_DEPTH_VALID + "u;",
            "const uint CUT_DECISION_GEOMETRY_DEPTH_TRIGGER = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_DEPTH_TRIGGER + "u;",
            "const uint CUT_DECISION_GEOMETRY_STRUCTURE_CORROBORATED = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_STRUCTURE_CORROBORATED
                    + "u;",
            "const uint CUT_DECISION_GEOMETRY_CONFIRMATION_REJECTED = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_CONFIRMATION_REJECTED
                    + "u;",
            "const uint CUT_DECISION_PERSISTENT_LOW_START = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_PERSISTENT_LOW_START + "u;",
            "const uint CUT_DECISION_SUPPORTED_RETURN = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_SUPPORTED_RETURN + "u;",
            "const uint CUT_DECISION_DEPTH_ONLY_FALLBACK = "
                    + ClientSbsGpuDepthProcessor.CUT_DECISION_DEPTH_ONLY_FALLBACK + "u;",
            "const uint SCENE_DIAGNOSTIC_COMPARABLE = "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_COMPARABLE + "u;",
            "const uint SCENE_DIAGNOSTIC_PREVIOUS_STRUCTURE_SUPPORTED = "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_PREVIOUS_STRUCTURE_SUPPORTED
                    + "u;",
            "const float STANDALONE_DEPTH_CHANGE_ENTER = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER) + ";",
            "const float STANDALONE_DEPTH_CHANGE_WITH_SHIFT_ENTER = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_WITH_SHIFT_ENTER) + ";",
            "const float STANDALONE_DISTRIBUTION_SHIFT_ENTER = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.STANDALONE_DISTRIBUTION_SHIFT_ENTER) + ";",
            "const float APPEARANCE_DEPTH_CHANGE_ENTER = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.APPEARANCE_DEPTH_CHANGE_ENTER) + ";",
            "const float APPEARANCE_DEPTH_CHANGE_WITH_SHIFT_ENTER = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.APPEARANCE_DEPTH_CHANGE_WITH_SHIFT_ENTER) + ";",
            "const float APPEARANCE_DISTRIBUTION_SHIFT_ENTER = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.APPEARANCE_DISTRIBUTION_SHIFT_ENTER) + ";",
            "const float NOVEL_GEOMETRY_CHANGE_MINIMUM = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.NOVEL_GEOMETRY_CHANGE_MINIMUM) + ";",
            "const float NOVEL_GEOMETRY_CHANGE_DELTA = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.NOVEL_GEOMETRY_CHANGE_DELTA) + ";",
            "const float NOVEL_GEOMETRY_CHANGE_RATIO = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.NOVEL_GEOMETRY_CHANGE_RATIO) + ";",
            "const float GEOMETRY_BASELINE_ALPHA = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.GEOMETRY_BASELINE_ALPHA) + ";",
            "const float STRUCTURAL_GEOMETRY_CUT_FLOOR = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.STRUCTURAL_GEOMETRY_CUT_FLOOR) + ";",
            "const float GEOMETRY_CHANGE_EXIT = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.GEOMETRY_CHANGE_EXIT) + ";",
            "const int CUT_SETTLE_VALID_DEPTH_UPDATES = "
                    + ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES + ";",
            "void latchCutEvent(uvec4 appearanceStats, uvec3 appearanceMeta,",
            "        uint decisionFlags, vec4 depthDiagnostics) {",
            "    cutAppearanceStats = appearanceStats;",
            "    cutAppearanceMeta = uvec4(appearanceMeta, decisionFlags);",
            "    cutDepthDiagnostics = depthDiagnostics;",
            "    cutEventMeta.x = min(cutEventMeta.x + 1u, 0xfffffffeu);",
            "}",
            "void publishProfile() {",
            "    uint required = FRAME_STATE_CURRENT_DEPTH_VALID",
            "            | FRAME_STATE_CURRENT_V2_VALID;",
            "    bool ready = stateFlags.y != 0u && v2Camera.w > 0.5",
            "            && (stateFlags.z & required) == required;",
            "    imageStore(uProfileTexture, ivec2(0, 0),",
            "            vec4(v2Camera.x, v2Camera.y, 0.0, ready ? 1.0 : 0.0));",
            "    imageStore(uProfileTexture, ivec2(1, 0), rangeState);",
            "    imageStore(uProfileTexture, ivec2(2, 0),",
            "            vec4(profileC.x, profileC.w, cutStateAux.x,",
            "                    float(stateCounters.x)));",
            "    imageStore(uProfileTexture, ivec2(3, 0),",
            "            vec4(v2Camera.z, float(stateFlags.w),",
            "                    float(stateFlags.z & FRAME_STATE_CURRENT_DEPTH_VALID),",
            "                    float(stateFlags.z & FRAME_STATE_HISTORY_ADVANCES)));",
            "}",
            "void main() {",
            "    uint preliminaryFrameState = stateFlags.z;",
            "    stateFlags.z = 0u;",
            // A shot-relatch bit describes this accepted depth frame only. Raw color evidence
            // cannot move the zero plane or pop latch until this pass sees moderate depth/geometry
            // corroboration while the shot detector is armed.
            "    stateFlags.w = 0u;",
            "    bool sceneEvidenceAvailable = uSceneEvidenceAvailable != 0;",
            "    uint currentSceneEvidence = externalSceneEvidence();",
            "    uvec4 currentAppearanceStats = externalSceneAppearanceStats();",
            "    uvec3 currentAppearanceMeta = externalSceneAppearanceMeta();",
            "    bool currentAppearanceProposal = (currentSceneEvidence",
            "            & SCENE_EVIDENCE_APPEARANCE) != 0u;",
            "    if (currentAppearanceProposal) {",
            "        healthCounters.y = min(healthCounters.y + 1u, 0xfffffffeu);",
            "        cutReasonCounters.x = min(cutReasonCounters.x + 1u, 0xfffffffeu);",
            "    }",
            // Scene evidence is valid only for this exact color/depth transaction. Apollo clears
            // evidence after every resolve and never applies a proposal from an invalid inference
            // to a later depth field.
            "    uint selectedSceneEvidence = currentSceneEvidence;",
            "    bool externalEvidence =",
            "            (selectedSceneEvidence & SCENE_EVIDENCE_APPEARANCE) != 0u;",
            "    bool exposureLikeTransition = !externalEvidence",
            "            && (selectedSceneEvidence & SCENE_EVIDENCE_EXPOSURE_LIKE) != 0u;",
            "    bool persistentLowStart = (selectedSceneEvidence",
            "            & SCENE_EVIDENCE_PERSISTENT_LOW_START) != 0u;",
            "    bool supportedReturn = (selectedSceneEvidence",
            "            & SCENE_EVIDENCE_SUPPORTED_RETURN) != 0u;",
            "    uint cutDecisionFlags =",
            "            (currentAppearanceProposal",
            "                    ? CUT_DECISION_CURRENT_APPEARANCE_PROPOSAL : 0u)",
            "            | (externalEvidence ? CUT_DECISION_SELECTED_APPEARANCE : 0u)",
            "            | (exposureLikeTransition ? CUT_DECISION_EXPOSURE_LIKE : 0u)",
            "            | (persistentLowStart ? CUT_DECISION_PERSISTENT_LOW_START : 0u)",
            "            | (supportedReturn ? CUT_DECISION_SUPPORTED_RETURN : 0u);",
            "    bool rawFieldComplete = (preliminaryFrameState",
            "            & FRAME_STATE_CURRENT_DEPTH_VALID) != 0u;",
            "    if (!rawFieldComplete) {",
            // Diagnostics may retain the event for the asynchronous HUD, but it has no authority
            // over a later valid field.
            "        if (currentSceneEvidence != 0u) {",
            "            latchCutEvent(currentAppearanceStats, currentAppearanceMeta,",
            "                    cutDecisionFlags, vec4(-1.0, -1.0, 0.0, cutStateAux.x));",
            "        }",
            "        publishProfile();",
            "        return;",
            "    }",
            "    float currentRawMean = v2Camera.y;",
            "    bool v2FrameValid = (preliminaryFrameState",
            "            & FRAME_STATE_CURRENT_V2_VALID) != 0u;",
            "    bool firstFrame = (preliminaryFrameState",
            "            & FRAME_STATE_FIRST_DEPTH) != 0u;",
            // Cut age follows decoded source steps, independently of the wall-time-normalized
            // profile age. Invalid results never reach this transition.
            "    int sourceFrameDelta = clamp(uSourceFrameDelta, 1, 65535);",
            "    int sourceFrameAge = stateFlags.y != 0u",
            "            ? min(max(cutStateCounters.x, 0), 65535 - sourceFrameDelta)",
            "                    + sourceFrameDelta : 0;",
            "    float distributionShift = uintBitsToFloat(rawGroupMoments[0].w);",
            "    float changeFraction = float(rawPadding) / float(rawValidCount);",
            "    float internalCutEvidence = clamp(0.80 * changeFraction",
            "            + 0.20 * min(distributionShift / 0.15, 1.0), 0.0, 1.0);",
            "    int cutState = stateCounters.y;",
            "    bool settled = (cutState & CUT_STATE_SETTLED) != 0;",
            "    bool geometryArmed = settled",
            "            && (cutState & CUT_STATE_GEOMETRY_ARMED) != 0;",
            "    bool appearanceArmed = settled",
            "            && (cutState & CUT_STATE_APPEARANCE_ARMED) != 0;",
            "    bool geometryLatched = settled",
            "            && (cutState & CUT_STATE_GEOMETRY_LATCHED) != 0;",
            "    float appearanceBlockCount = float(currentAppearanceStats.x);",
            "    float structuralChangeFraction = appearanceBlockCount > 0.0",
            "            ? float(currentAppearanceStats.w) / appearanceBlockCount : 0.0;",
            "    bool currentStructureReliable = appearanceBlockCount > 0.0",
            "            && float(currentAppearanceMeta.x) / appearanceBlockCount >= 0.05;",
            "    bool appearanceComparable = (currentAppearanceMeta.z",
            "            & SCENE_DIAGNOSTIC_COMPARABLE) != 0u;",
            "    bool previousStructureReliable = (currentAppearanceMeta.z",
            "            & SCENE_DIAGNOSTIC_PREVIOUS_STRUCTURE_SUPPORTED) != 0u;",
            // The detector's exposure-like classification covers three host states. Only the
            // first supported-to-structureless edge is state 2 and holds reliable history.
            "    bool firstStructurelessHold = exposureLikeTransition",
            "            && appearanceComparable && previousStructureReliable",
            "            && !currentStructureReliable;",
            "    bool photometricRecoveryVeto = exposureLikeTransition",
            "            && !firstStructurelessHold;",
            "    bool appearanceRecovery = (cutState",
            "            & CUT_STATE_APPEARANCE_RECOVERY) != 0;",
            "    bool appearanceRecoveryTail = appearanceRecovery && !externalEvidence;",
            "    bool appearanceVeto = exposureLikeTransition || appearanceRecoveryTail;",
            // Decide broad depth cuts from this frame's freshly filtered temporal field.
            "    bool internalCut = !firstFrame && stateFlags.y != 0u",
            "            && geometryArmed && !appearanceVeto",
            "            && (changeFraction >= STANDALONE_DEPTH_CHANGE_ENTER",
            "            || (changeFraction >= STANDALONE_DEPTH_CHANGE_WITH_SHIFT_ENTER",
            "            && distributionShift >= STANDALONE_DISTRIBUTION_SHIFT_ENTER));",
            // Appearance is a high-recall qualified structural proposal, not authority to relatch
            // shot geometry. The proposal is qualified on the model-input color grid before it
            // reaches this shader.
            // A weaker depth threshold than the standalone detector preserves similar-depth cuts,
            // while brightness-only transitions cannot move the zero plane.
            "    bool colorGeometryCorroborated = changeFraction >= APPEARANCE_DEPTH_CHANGE_ENTER",
            "            || (changeFraction >= APPEARANCE_DEPTH_CHANGE_WITH_SHIFT_ENTER",
            "            && distributionShift >= APPEARANCE_DISTRIBUTION_SHIFT_ENTER);",
            "    cutDecisionFlags |= (v2FrameValid ? CUT_DECISION_CURRENT_DEPTH_VALID : 0u)",
            "            | (appearanceArmed ? CUT_DECISION_APPEARANCE_ARMED : 0u)",
            "            | (colorGeometryCorroborated",
            "                    ? CUT_DECISION_APPEARANCE_DEPTH_CORROBORATED : 0u);",
            "    bool externalCut = !firstFrame && stateFlags.y != 0u",
            "            && appearanceArmed && externalEvidence && colorGeometryCorroborated;",
            // State 3's first supported update gets exactly one absolute geometry decision,
            // independent of ordinary arming and the post-cut refractory. The typed return event
            // is one-update-scoped; the separate reserved marker persists through intervening
            // low-support updates and is cleared after this decision whether or not it cuts.
            "    bool lowStructureScene = cutStateCounters.y != 0 || persistentLowStart;",
            "    bool referenceStructureless = (currentAppearanceMeta.z",
            "            & SCENE_DIAGNOSTIC_COMPARABLE) != 0u",
            "            && (currentAppearanceMeta.z",
            "                    & SCENE_DIAGNOSTIC_PREVIOUS_STRUCTURE_SUPPORTED) == 0u;",
            "    bool lowStructureReturnCut = !firstFrame && stateFlags.y != 0u",
            "            && lowStructureScene && supportedReturn && currentStructureReliable",
            "            && (changeFraction >= STANDALONE_DEPTH_CHANGE_ENTER",
            "            || (changeFraction >= STANDALONE_DEPTH_CHANGE_WITH_SHIFT_ENTER",
            "            && distributionShift >= STANDALONE_DISTRIBUTION_SHIFT_ENTER));",
            // A latched detector must ignore persistent evidence without becoming permanently blind.
            // Compare against a slow per-update EMA and allow only a materially new geometry spike.
            "    bool novelLatchedGeometryCut = !firstFrame && stateFlags.y != 0u",
            "            && geometryLatched && cutStateAux.y > 0.5",
            "            && !appearanceVeto",
            "            && sourceFrameAge >= CUT_SETTLE_VALID_DEPTH_UPDATES",
            "            && changeFraction >= NOVEL_GEOMETRY_CHANGE_MINIMUM",
            "            && (changeFraction >= cutStateAux.x + NOVEL_GEOMETRY_CHANGE_DELTA",
            "            || changeFraction >= cutStateAux.x * NOVEL_GEOMETRY_CHANGE_RATIO);",
            "    bool geometryConfirmationPending = (cutState",
            "            & CUT_STATE_GEOMETRY_CONFIRMATION_PENDING) != 0;",
            "    bool absoluteGeometryCandidate = changeFraction >= STANDALONE_DEPTH_CHANGE_ENTER",
            "            || (changeFraction >= STANDALONE_DEPTH_CHANGE_WITH_SHIFT_ENTER",
            "            && distributionShift >= STANDALONE_DISTRIBUTION_SHIFT_ENTER);",
            // Prefer Apollo's independent ordinal corroboration whenever the scene detector exists.
            // If that optional detector fails, retain a bounded two-observation depth-only route so
            // camera relatching cannot remain pinned forever.
            "    bool ordinalStructureCorroborated = persistentLowStart",
            "            || referenceStructureless",
            "            || structuralChangeFraction >= STRUCTURAL_GEOMETRY_CUT_FLOOR;",
            "    bool geometryStructureCorroborated = !sceneEvidenceAvailable",
            "            || ordinalStructureCorroborated;",
            "    bool confirmationStructureReliable = !sceneEvidenceAvailable",
            "            || currentStructureReliable;",
            "    bool geometryDepthTrigger = internalCut || novelLatchedGeometryCut",
            "            || lowStructureReturnCut",
            "            || (geometryConfirmationPending && absoluteGeometryCandidate);",
            "    bool geometryConfirmationCandidate = !appearanceVeto",
            "            && geometryStructureCorroborated",
            "            && (internalCut || novelLatchedGeometryCut || lowStructureReturnCut",
            "            || (geometryConfirmationPending && confirmationStructureReliable",
            "                    && absoluteGeometryCandidate));",
            "    bool structurelessCandidateAlreadyConfirmed = persistentLowStart",
            "            && geometryConfirmationCandidate;",
            "    bool confirmedGeometryCut = geometryConfirmationPending",
            "            && geometryConfirmationCandidate;",
            // Precedence makes accepted reason counters mutually exclusive even if malformed or
            // future evidence makes more than one internal predicate true.
            "    uint acceptedCutReason = externalCut ? CUT_DECISION_ACCEPTED_APPEARANCE",
            "            : (structurelessCandidateAlreadyConfirmed",
            "                    ? CUT_DECISION_ACCEPTED_STRUCTURELESS_ENTRY",
            "                    : (confirmedGeometryCut ? CUT_DECISION_ACCEPTED_GEOMETRY : 0u));",
            "    bool acceptedCut = acceptedCutReason != 0u;",
            "    bool startGeometryConfirmation = !acceptedCut",
            "            && !geometryConfirmationPending && geometryConfirmationCandidate",
            "            && !persistentLowStart;",
            "    bool geometryConfirmationRejected = geometryConfirmationPending",
            "            && !geometryConfirmationCandidate;",
            "    cutDecisionFlags |= acceptedCutReason",
            "            | (geometryConfirmationCandidate",
            "                    ? CUT_DECISION_GEOMETRY_CANDIDATE : 0u)",
            "            | (geometryConfirmationPending",
            "                    ? CUT_DECISION_GEOMETRY_CONFIRMATION_PENDING : 0u)",
            "            | (geometryDepthTrigger ? CUT_DECISION_GEOMETRY_DEPTH_TRIGGER : 0u)",
            "            | (geometryDepthTrigger && ordinalStructureCorroborated",
            "                    ? CUT_DECISION_GEOMETRY_STRUCTURE_CORROBORATED : 0u)",
            "            | (!sceneEvidenceAvailable",
            "                    && (geometryDepthTrigger || geometryConfirmationPending)",
            "                    ? CUT_DECISION_DEPTH_ONLY_FALLBACK : 0u)",
            "            | (geometryConfirmationRejected",
            "                    ? CUT_DECISION_GEOMETRY_CONFIRMATION_REJECTED : 0u);",
            "    if (acceptedCutReason == CUT_DECISION_ACCEPTED_APPEARANCE)",
            "        cutReasonCounters.y = min(cutReasonCounters.y + 1u, 0xfffffffeu);",
            "    else if (acceptedCutReason == CUT_DECISION_ACCEPTED_GEOMETRY)",
            "        cutReasonCounters.z = min(cutReasonCounters.z + 1u, 0xfffffffeu);",
            "    else if (acceptedCutReason == CUT_DECISION_ACCEPTED_STRUCTURELESS_ENTRY)",
            "        cutReasonCounters.w = min(cutReasonCounters.w + 1u, 0xfffffffeu);",
            // Only host states 2 and 4 hold the reliable tuple. The generic bit identifies either
            // hold; the reason bit is reserved for the state-2 metadata transition.
            "    bool structurelessGapHold = !firstFrame",
            "            && firstStructurelessHold && !acceptedCut;",
            "    bool holdReliableHistory = structurelessGapHold",
            "            || startGeometryConfirmation;",
            "    bool historyAdvances = !holdReliableHistory;",
            "    if (historyAdvances) cutDecisionFlags |= CUT_DECISION_HISTORY_ADVANCED;",
            // Preserve accepted, proposed, vetoed, and rejected event evidence until a later
            // notable decision replaces it. Ordinary frames must not erase an event between the
            // five/30-inference asynchronous health samples.
            "    bool notableCutDecision = selectedSceneEvidence != 0u",
            "            || geometryDepthTrigger || geometryConfirmationPending",
            "            || acceptedCut || startGeometryConfirmation;",
            "    if (notableCutDecision) {",
            "        latchCutEvent(currentAppearanceStats, currentAppearanceMeta,",
            "                cutDecisionFlags, vec4(changeFraction, distributionShift,",
            "                        internalCutEvidence, cutStateAux.x));",
            "    }",
            // Apollo retains an existing camera across a no-cut collapsed field, clears it when a
            // cut lands on a collapsed field, and acquires the next usable field. Track camera
            // validity independently from normalized cut-history initialization so startup and
            // post-cut recovery do not remain pinned to a meaningless constant output.
            "    bool cameraInitialized = v2Camera.w > 0.5;",
            "    if (acceptedCut && !v2FrameValid) {",
            "        v2Camera.x = 0.0;",
            "        v2Camera.w = 0.0;",
            // Camera availability is independent from reliable comparison-history ownership.
            // Apollo allows the first usable field after a collapsed accepted cut to recover the
            // camera even when that field is otherwise held for exposure/geometry confirmation.
            "    } else if (v2FrameValid && (!cameraInitialized || acceptedCut)) {",
            "        v2Camera.x = currentRawMean;",
            "        v2Camera.w = 1.0;",
            "    }",
            // Apollo freezes only the cut baseline for state 2, the recovery tail, and the first
            // confirmation observation. Range and immediate temporal depth remain current.
            "    if (cutStateAux.y <= 0.5 || acceptedCut) {",
            "        cutStateAux.x = changeFraction;",
            "        cutStateAux.y = 1.0;",
            "    } else if (!firstStructurelessHold && !appearanceRecoveryTail",
            "            && !startGeometryConfirmation) {",
            "        cutStateAux.x = mix(cutStateAux.x, changeFraction,",
            "                GEOMETRY_BASELINE_ALPHA);",
            "    }",
            // One current-result bitfield is the authority for profile, reliable detector history,
            // and actual-inference ownership. A pending geometry candidate holds only that reliable
            // tuple; an invalid field returned above with all bits clear.
            "    stateFlags.z = (firstFrame ? FRAME_STATE_FIRST_DEPTH : 0u)",
            "            | (holdReliableHistory ? FRAME_STATE_HOLD_RELIABLE_HISTORY : 0u)",
            "            | (structurelessGapHold ? FRAME_STATE_STRUCTURELESS_GAP : 0u)",
            "            | (historyAdvances ? FRAME_STATE_HISTORY_ADVANCES : 0u)",
            "            | FRAME_STATE_CURRENT_DEPTH_VALID",
            "            | (v2FrameValid ? FRAME_STATE_CURRENT_V2_VALID : 0u);",
            "    stateFlags.w = acceptedCut ? 1u : 0u;",
            "    cutStateCounters.x = acceptedCut ? 0 : sourceFrameAge;",
            "    cutStateCounters.y = supportedReturn ? 0",
            "            : (persistentLowStart ? 1 : cutStateCounters.y);",
            "    cutState = cutState & ~(CUT_STATE_GEOMETRY_CONFIRMATION_PENDING",
            "            | CUT_STATE_APPEARANCE_RECOVERY);",
            "    if (!firstFrame && !acceptedCut && photometricRecoveryVeto)",
            "        cutState = cutState | CUT_STATE_APPEARANCE_RECOVERY;",
            "    if (startGeometryConfirmation)",
            "        cutState = cutState | CUT_STATE_GEOMETRY_CONFIRMATION_PENDING;",
            "    stateCounters.y = cutState;",
            "    profileC.x = changeFraction;",
            // Two is the existing GPU/health sentinel for an observed external request. The
            // one-frame stateFlags.w pulse separately says whether geometry accepted it.
            "    profileC.w = externalEvidence ? 2.0 : internalCutEvidence;",
            // Run the existing profile/cut-arm state machine only after this frame's temporal
            // comparison and reliable-history decision. This keeps the production path at six
            // dispatches while retaining the host dependency order.
            "    bool wasInitialized = stateFlags.y != 0u;",
            "    int profileSceneAge = wasInitialized ? min(stateCounters.x",
            "            + max(uReferenceFrameAdvance, 1), 65535) : 0;",
            "    bool hardCut = wasInitialized && stateFlags.w != 0u;",
            "    if ((cutState & CUT_STATE_SETTLED) == 0 && wasInitialized",
            "            && sourceFrameAge >= CUT_SETTLE_VALID_DEPTH_UPDATES)",
            "        cutState = CUT_STATE_READY",
            "                | (cutState & CUT_STATE_APPEARANCE_RECOVERY);",
            "    if (!wasInitialized || hardCut) profileSceneAge = 0;",
            "    if (hardCut) {",
            "        cutState = CUT_STATE_LATCHED;",
            "    } else if ((cutState & CUT_STATE_SETTLED) != 0) {",
            "        if ((cutState & CUT_STATE_GEOMETRY_LATCHED) != 0) {",
            "            if (profileC.x < GEOMETRY_CHANGE_EXIT) {",
            "                if ((cutState & CUT_STATE_GEOMETRY_ONE_LOW) != 0) {",
            "                    cutState = cutState & ~(CUT_STATE_GEOMETRY_LATCHED",
            "                            | CUT_STATE_GEOMETRY_ONE_LOW);",
            "                    cutState = cutState | CUT_STATE_GEOMETRY_ARMED;",
            "                } else {",
            "                    cutState = cutState | CUT_STATE_GEOMETRY_ONE_LOW;",
            "                }",
            "            } else {",
            "                cutState = cutState & ~CUT_STATE_GEOMETRY_ONE_LOW;",
            "            }",
            "        }",
            "        if ((cutState & CUT_STATE_APPEARANCE_LATCHED) != 0) {",
            "            if (!externalEvidence) {",
            "                if ((cutState & CUT_STATE_APPEARANCE_ONE_QUIET) != 0) {",
            "                    cutState = cutState & ~(CUT_STATE_APPEARANCE_LATCHED",
            "                            | CUT_STATE_APPEARANCE_ONE_QUIET);",
            "                    cutState = cutState | CUT_STATE_APPEARANCE_ARMED;",
            "                } else {",
            "                    cutState = cutState | CUT_STATE_APPEARANCE_ONE_QUIET;",
            "                }",
            "            } else {",
            "                cutState = cutState & ~CUT_STATE_APPEARANCE_ONE_QUIET;",
            "            }",
            "        }",
            "    }",
            "    profileC.yz = vec2(" + FIXED_POP + ", 1.0);",
            "    stateFlags.y = 1u;",
            "    stateFlags.w = hardCut ? 1u : 0u;",
            "    if (hardCut) healthCounters.x = min(healthCounters.x + 1u, 0xfffffffeu);",
            "    stateCounters.x = profileSceneAge;",
            "    stateCounters.y = cutState;",
            "    stateCounters.w = min(stateCounters.w + 1, 2147483647);",
            "    publishProfile();",
            "}"
    );

    static String temporalFilter(boolean removeReflectedPadding) {
        return HEADER + rawInput(removeReflectedPadding)
                + RAW_STATS + STATE + FRAME_STATE_CONSTANTS + lines(
                "layout(local_size_x = 16, local_size_y = 16) in;",
                "uniform sampler2D uPreviousDepth;",
                "uniform sampler2D uReliableDepth;",
                "uniform float uDepthAlpha;",
                "uniform float uMovingDepthAlpha;",
                "uniform float uSpatialThresholdScale;",
                "layout(r32f, binding = 0) uniform writeonly highp image2D uCurrentDepth;",
                "layout(r32f, binding = 2) uniform writeonly highp image2D uCurrentRawDepth;",
                "shared uint localChangeCount[256];",
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
                "    uint lane = gl_LocalInvocationIndex;",
                "    localChangeCount[lane] = 0u;",
                "    barrier();",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    if (all(lessThan(point, uOutputSize))) {",
                "        float previous = texelFetch(uPreviousDepth, point, 0).r;",
                "        float rawCurrent = 0.0;",
                "        bool rawFieldComplete = (stateFlags.z",
                "                & FRAME_STATE_CURRENT_DEPTH_VALID) != 0u;",
                "        bool v2FrameValid = (stateFlags.z",
                "                & FRAME_STATE_CURRENT_V2_VALID) != 0u;",
                "        bool currentValid = rawFieldComplete",
                "                && sourceAlignedRaw(point, rawCurrent) && stateFlags.x != 0u;",
                "        float denominator = max(rangeState.w - rangeState.z, 1.0e-6);",
                "        float current = currentValid ? clamp(",
                "                (rawCurrent - rangeState.z) / denominator, 0.0, 1.0) : 0.5;",
                "        bool firstDepthFrame = (stateFlags.z",
                "                & FRAME_STATE_FIRST_DEPTH) != 0u;",
                "        imageStore(uCurrentRawDepth, point,",
                "                vec4(currentValid && v2FrameValid ? rawCurrent : 0.0,",
                "                        0.0, 0.0, 1.0));",
                "        if (!currentValid) {",
                "            float retained = stateFlags.y != 0u ? previous : 0.5;",
                "            imageStore(uCurrentDepth, point, vec4(retained, 0.0, 0.0, 1.0));",
                "        } else {",
                "            float outputDepth = current;",
                // Immediate temporal depth advances for every valid inference. Reliable ownership
                // is decided only after this exact output has contributed cut evidence.
                "            bool resetHistory = firstDepthFrame;",
                "            if (!resetHistory) {",
                "                float change = abs(current - previous);",
                "                if (!(change >= 0.05)) {",
                "                    outputDepth = mix(previous, current, uDepthAlpha);",
                "                } else {",
                "                    float gradient = 0.0;",
                "                    float neighbor;",
                "                    if (mappedDepth(point + ivec2(-1, 0), neighbor))",
                "                        gradient = max(gradient, abs(current - neighbor));",
                "                    if (mappedDepth(point + ivec2(1, 0), neighbor))",
                "                        gradient = max(gradient, abs(current - neighbor));",
                "                    if (mappedDepth(point + ivec2(0, -1), neighbor))",
                "                        gradient = max(gradient, abs(current - neighbor));",
                "                    if (mappedDepth(point + ivec2(0, 1), neighbor))",
                "                        gradient = max(gradient, abs(current - neighbor));",
                "                    float referenceGradient = gradient",
                "                            / max(uSpatialThresholdScale, 1.0);",
                "                    float alpha = referenceGradient >= 0.02",
                "                            ? uMovingDepthAlpha : uDepthAlpha;",
                "                    outputDepth = mix(previous, current, alpha);",
                "                }",
                "            }",
                "            imageStore(uCurrentDepth, point,",
                "                    vec4(outputDepth, 0.0, 0.0, 1.0));",
                // The reliable texture is intentionally empty on the first valid frame. Its cut
                // baseline begins with zero change evidence and is promoted at the next inference.
                "            if (!firstDepthFrame) {",
                "                float reliable = texelFetch(uReliableDepth, point, 0).r;",
                "                localChangeCount[lane] = abs(outputDepth - reliable) >= "
                        + ClientSbsShotCutPolicy.glsl(
                        ClientSbsShotCutPolicy.RAW_PIXEL_DEPTH_DELTA),
                "                        ? 1u : 0u;",
                "            }",
                "        }",
                "    }",
                // Every lane must reach these barriers, including a partial edge workgroup.
                "    barrier();",
                "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
                "        if (lane < stride)",
                "            localChangeCount[lane] += localChangeCount[lane + stride];",
                "        barrier();",
                "    }",
                "    if (lane == 0u && localChangeCount[0] != 0u)",
                "        atomicAdd(rawPadding, localChangeCount[0]);",
                "}"
        );
    }

    /** Retired normalized Bestv2 profile accumulator; never compiled by production V2. */
    static String legacyAccumulateProfile(boolean removeReflectedPadding) {
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
                "        float spatialScale = max(uSpatialThresholdScale, 1.0);",
                "        float referenceGradient = gradient / spatialScale;",
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
                // Apollo's min(grad/0.02, 8) * 256. Below the cap, referenceGradient cancels the
                // coarser grid's larger boundary-pixel fraction. Once Apollo's weight saturates,
                // scale the cap itself or that fraction would inflate risk by spatialScale.
                "        uint edgeWeight = referenceGradient >= 0.02",
                "                ? uint(min(referenceGradient * 50.0, 8.0 / spatialScale)",
                "                * 256.0 + 0.5) : 0u;",
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

    static final String LEGACY_ACCUMULATE_PROFILE = legacyAccumulateProfile(true);

    /** Retired normalized Bestv2 profile resolver; never compiled by production V2. */
    static final String LEGACY_RESOLVE_PROFILE = HEADER + PROFILE_STATS + STATE
            + FRAME_STATE_CONSTANTS + SHOT_CUT_STATE_CONSTANTS + EXTERNAL_SCENE_CUT + lines(
            "layout(local_size_x = 1) in;",
            "layout(rgba32f, binding = 1) uniform writeonly highp image2D uProfileTexture;",
            "uniform int uPixelCount;",
            "uniform float uSubjectAlpha;",
            "uniform float uBandAlpha;",
            "uniform int uReferenceFrameAdvance;",
            "const float GEOMETRY_CHANGE_EXIT = "
                    + ClientSbsShotCutPolicy.glsl(
                    ClientSbsShotCutPolicy.GEOMETRY_CHANGE_EXIT) + ";",
            "const int CUT_SETTLE_VALID_DEPTH_UPDATES = "
                    + ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES + ";",
            "const int PROFILE_SETTLE_REFERENCE_FRAMES = "
                    + ClientSbsGpuDepthProcessor.PROFILE_SETTLE_REFERENCE_FRAMES + ";",
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
            "            vec4(profileB.y, profileB.z, profileC.z,",
            "            (stateFlags.z & FRAME_STATE_CURRENT_DEPTH_VALID) != 0u",
            "                    ? float(stateFlags.y) : 0.0));",
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
            "    bool historyAdvances = (stateFlags.z",
            "            & FRAME_STATE_HISTORY_ADVANCES) != 0u;",
            "    if (!historyAdvances) {",
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
            // `edgeCount` already carries cap-aware reference-grid weighting: on a coarser grid a
            // one-texel boundary occupies `scale` times the pixel fraction while both its linear
            // weight and its saturation cap are divided by that scale. Dividing this density again
            // would normalize twice and under-report risk relative to Apollo.
            "    float edgeFraction = float(edgeCount) / (float(uPixelCount) * 256.0);",
            // This retired resolver consumes the legacy path's previously accumulated comparison.
            "    float changeFraction = profileC.x;",
            "    bool wasInitialized = stateFlags.y != 0u;",
            // Profile age deliberately remains wall-time/reference-frame normalized. It controls
            // only adaptive-pop and anchor settle crossings, never cut startup or refractory.
            "    int profileSceneAge = wasInitialized ? min(stateCounters.x",
            "            + max(uReferenceFrameAdvance, 1), 65535) : 0;",
            "    int sourceFrameAge = cutStateCounters.x;",
            "    int cutState = stateCounters.y;",
            "    float requestedCutEvidence = profileC.w;",
            // profileC.w is the classification selected for this exact valid transaction.
            "    bool externalEvidence = requestedCutEvidence > 1.5",
            "            || externalSceneCutRequested();",
            // The retired path emitted its accepted one-frame pulse before this resolver. Raw
            // external evidence alone cannot relatch shot geometry here.
            "    bool hardCut = wasInitialized && stateFlags.w != 0u;",
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
            "    if (cutState == 0 && wasInitialized",
            "            && sourceFrameAge >= CUT_SETTLE_VALID_DEPTH_UPDATES) {",
            // Arming occurs after the retired path made this update's decision, so evidence from
            // the settle-crossing update itself cannot satisfy either branch.
            "        cutState = CUT_STATE_READY;",
            "    }",
            "    if (!wasInitialized || hardCut) profileSceneAge = 0;",
            "    if (hardCut) {",
            // Any accepted shot latches both evidence sources. They rearm independently below,
            // preventing persistent appearance from starving a later standalone geometry cut.
            "        cutState = CUT_STATE_LATCHED;",
            "    } else if ((cutState & CUT_STATE_SETTLED) != 0) {",
            "        if ((cutState & CUT_STATE_GEOMETRY_LATCHED) != 0) {",
            "            if (changeFraction < GEOMETRY_CHANGE_EXIT) {",
            "                if ((cutState & CUT_STATE_GEOMETRY_ONE_LOW) != 0) {",
            "                    cutState = cutState & ~(CUT_STATE_GEOMETRY_LATCHED",
            "                            | CUT_STATE_GEOMETRY_ONE_LOW);",
            "                    cutState = cutState | CUT_STATE_GEOMETRY_ARMED;",
            "                } else {",
            "                    cutState = cutState | CUT_STATE_GEOMETRY_ONE_LOW;",
            "                }",
            "            } else {",
            "                cutState = cutState & ~CUT_STATE_GEOMETRY_ONE_LOW;",
            "            }",
            "        }",
            "        if ((cutState & CUT_STATE_APPEARANCE_LATCHED) != 0) {",
            "            if (!externalEvidence) {",
            "                if ((cutState & CUT_STATE_APPEARANCE_ONE_QUIET) != 0) {",
            "                    cutState = cutState & ~(CUT_STATE_APPEARANCE_LATCHED",
            "                            | CUT_STATE_APPEARANCE_ONE_QUIET);",
            "                    cutState = cutState | CUT_STATE_APPEARANCE_ARMED;",
            "                } else {",
            "                    cutState = cutState | CUT_STATE_APPEARANCE_ONE_QUIET;",
            "                }",
            "            } else {",
            "                cutState = cutState & ~CUT_STATE_APPEARANCE_ONE_QUIET;",
            "            }",
            "        }",
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
            // The settle test is a CROSSING, not equality: the client advances profileSceneAge by
            // uReferenceFrameAdvance per depth update, so it can step straight over the threshold.
            "    int previousProfileAge = stateCounters.x;",
            "    bool settledNow = wasInitialized && !hardCut",
            "            && previousProfileAge < PROFILE_SETTLE_REFERENCE_FRAMES",
            "            && profileSceneAge >= PROFILE_SETTLE_REFERENCE_FRAMES;",
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
            "    float popStrength = wasInitialized ? profileC.y : " + POP_FLOOR + ";",
            "    float classifiedEdgeFraction = wasInitialized ? profileB.w",
            "            : " + UNCLASSIFIED_EDGE + ";",
            "    if (!wasInitialized || hardCut) {",
            "        popStrength = " + POP_FLOOR + ";",
            "        classifiedEdgeFraction = " + UNCLASSIFIED_EDGE + ";",
            "    } else if (settledNow) {",
            "        classifiedEdgeFraction = edgeFraction;",
            "        float confidence = 1.0 - smoothstep(0.04, 0.20, classifiedEdgeFraction);",
            "        popStrength = mix(" + POP_FLOOR + ", " + POP_CEILING + ", confidence);",
            "    }",
            "    profileA = vec4(stretchLow, stretchHigh, stretchInverse, subjectCandidate);",
            "    profileB = vec4(subjectDepth, recenter, anchorShift, classifiedEdgeFraction);",
            "    profileC = vec4(changeFraction, popStrength, popStrength / " + POP_FLOOR + ",",
            "            requestedCutEvidence);",
            "    stateFlags.y = 1u;",
            "    stateFlags.w = hardCut ? 1u : 0u;",
            "    if (hardCut) healthCounters.x = min(healthCounters.x + 1u, 0xfffffffeu);",
            "    stateCounters.x = profileSceneAge;",
            "    stateCounters.y = cutState;",
            "    stateCounters.w = min(stateCounters.w + 1, 2147483647);",
            "    publishProfile();",
            "}"
    );

    static String resetState() {
        return HEADER + STATE + lines(
            "layout(local_size_x = 1) in;",
            "layout(rgba32f, binding = 1) uniform writeonly highp image2D uProfileTexture;",
            "void main() {",
            "    rangeState = vec4(0.0);",
            "    profileA = vec4(0.0, 1.0, 1.0, 0.5);",
            "    profileB = vec4(0.5, 0.0, 0.0, " + UNCLASSIFIED_EDGE + ");",
            "    profileC = vec4(0.0, " + FIXED_POP + ", 1.0, 0.0);",
            "    stateFlags = uvec4(0u);",
            "    stateCounters = ivec4(0);",
            "    healthCounters = uvec4(0u);",
            "    cutStateAux = vec2(0.0);",
            "    cutStateCounters = ivec2(0);",
            "    v2Camera = vec4(0.0);",
            "    cutReasonCounters = uvec4(0u);",
            "    cutAppearanceStats = uvec4(0u);",
            "    cutAppearanceMeta = uvec4(0u);",
            "    cutDepthDiagnostics = vec4(0.0);",
            "    cutEventMeta = uvec4(0u);",
            "    imageStore(uProfileTexture, ivec2(0, 0), vec4(0.0));",
            "    imageStore(uProfileTexture, ivec2(1, 0), vec4(0.0));",
            "    imageStore(uProfileTexture, ivec2(2, 0), vec4(0.0));",
            "    imageStore(uProfileTexture, ivec2(3, 0), vec4(0.0));",
            "}"
        );
    }

    static final String RESET_STATE = resetState();
}
