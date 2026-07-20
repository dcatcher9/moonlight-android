package com.limelight.sbs;

/**
 * Compute shaders used by {@link ClientSbsGpuDepthProcessor}.
 *
 * <p>The bindings are deliberately fixed and private to one processor dispatch:</p>
 * <ul>
 *     <li>SSBO 0: packed Float32 model output</li>
 *     <li>SSBO 1: raw range and histogram scratch</li>
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
            "    vec4 profileB;",        // subject, recenter, convergence, edge fraction
            "    vec4 profileC;",        // change fraction, pop, pop ratio, unused
            "    uvec4 stateFlags;",     // range init, profile init, first frame, hard cut
            "    ivec4 stateCounters;",  // scene age, cut state, valid samples, frame number
            "};"
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

    private static final String RAW_INPUT = lines(
            "layout(std430, binding = 0) readonly buffer RawDepth {",
            "    uint rawWords[];",
            "};",
            "uniform uint uRawByteOffset;",
            "uniform uint uRawPixelStrideBytes;",
            "uniform ivec2 uTensorSize;",
            "uniform ivec2 uOutputSize;",
            "uniform vec2 uContentScale;",
            "float tensorRaw(ivec2 point) {",
            "    ivec2 p = clamp(point, ivec2(0), uTensorSize - ivec2(1));",
            "    uint index = uint(p.y * uTensorSize.x + p.x);",
            "    uint absoluteByte = uRawByteOffset + index * uRawPixelStrideBytes;",
            "    float value = uintBitsToFloat(rawWords[absoluteByte >> 2u]);",
            "    return isnan(value) || isinf(value) ? 0.0 : max(value, 0.0);",
            "}",
            "float sourceAlignedRaw(ivec2 destination) {",
            "    vec2 outputUv = (vec2(destination) + vec2(0.5)) / vec2(uOutputSize);",
            "    vec2 padding = vec2(0.5) * (vec2(1.0) - uContentScale);",
            "    vec2 source = (padding + outputUv * uContentScale) * vec2(uTensorSize)",
            "            - vec2(0.5);",
            "    source = clamp(source, vec2(0.0), vec2(uTensorSize - ivec2(1)));",
            "    ivec2 low = ivec2(floor(source));",
            "    ivec2 high = min(low + ivec2(1), uTensorSize - ivec2(1));",
            "    vec2 weight = source - vec2(low);",
            "    weight = mix(weight, vec2(0.0), lessThanEqual(weight, vec2(1.0e-5)));",
            "    float top = mix(tensorRaw(low), tensorRaw(ivec2(high.x, low.y)), weight.x);",
            "    float bottom = mix(tensorRaw(ivec2(low.x, high.y)), tensorRaw(high), weight.x);",
            "    return mix(top, bottom, weight.y);",
            "}",
            "uint rawFixed(ivec2 destination) {",
            "    return uint(clamp(sourceAlignedRaw(destination), 0.0, 65535.0)",
            "            * 65536.0 + 0.5);",
            "}"
    );

    static final String RESET_RAW_STATS = HEADER + RAW_STATS + lines(
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

    static final String RAW_MIN_MAX = HEADER + RAW_INPUT + RAW_STATS + lines(
            "layout(local_size_x = 16, local_size_y = 16) in;",
            "void main() {",
            "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
            "    if (any(greaterThanEqual(point, uOutputSize))) return;",
            "    uint value = rawFixed(point);",
            "    atomicMin(rawMinimum, value);",
            "    atomicMax(rawMaximum, value);",
            "    atomicAdd(rawValidCount, 1u);",
            "}"
    );

    static final String RAW_HISTOGRAM = HEADER + RAW_INPUT + RAW_STATS + lines(
            "layout(local_size_x = 16, local_size_y = 16) in;",
            "void main() {",
            "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
            "    if (any(greaterThanEqual(point, uOutputSize))) return;",
            "    uint value = rawFixed(point);",
            "    uint rangeValue = rawMaximum - rawMinimum;",
            "    uint bin = rangeValue == 0u ? 0u : min(uint(",
            "            float(value - rawMinimum) * 256.0 / float(rangeValue)), 255u);",
            "    atomicAdd(rawHistogram[bin], 1u);",
            "}"
    );

    static final String RESOLVE_RAW_RANGE = HEADER + RAW_STATS + STATE + lines(
            "layout(local_size_x = 1) in;",
            "float percentileValue(float percentile, float minimumValue, float binWidth) {",
            "    float target = percentile * float(rawValidCount);",
            "    uint cumulative = 0u;",
            "    for (uint bin = 0u; bin < 256u; ++bin) {",
            "        cumulative += rawHistogram[bin];",
            "        if (float(cumulative) >= target) return minimumValue + (float(bin) + 0.5) * binWidth;",
            "    }",
            "    return minimumValue + 255.5 * binWidth;",
            "}",
            "void main() {",
            "    stateFlags.z = 0u;",
            "    stateFlags.w = 0u;",
            "    stateCounters.z = int(rawValidCount);",
            "    if (rawValidCount == 0u) return;",
            "    float minimumValue = float(rawMinimum) / 65536.0;",
            "    float maximumValue = float(rawMaximum) / 65536.0;",
            "    float rawRange = maximumValue - minimumValue;",
            "    float frameLow = minimumValue;",
            "    float frameHigh = maximumValue;",
            "    if (rawRange > 0.0) {",
            "        float binWidth = rawRange / 256.0;",
            "        float lowCandidate = percentileValue(0.02, minimumValue, binWidth);",
            "        float highCandidate = percentileValue(0.98, minimumValue, binWidth);",
            "        if (highCandidate - lowCandidate > 1.0e-9) {",
            "            frameLow = lowCandidate;",
            "            frameHigh = highCandidate;",
            "        }",
            "    }",
            "    bool firstFrame = stateFlags.x == 0u;",
            "    if (firstFrame) {",
            "        rangeState.zw = vec2(frameLow, frameHigh);",
            "        stateFlags.x = 1u;",
            "    } else {",
            "        rangeState.zw = mix(rangeState.zw, vec2(frameLow, frameHigh), 0.18);",
            "    }",
            "    rangeState.xy = vec2(frameLow, frameHigh);",
            "    stateFlags.z = firstFrame ? 1u : 0u;",
            "}"
    );

    static String temporalFilter(boolean r16f) {
        String shaderHeader = r16f
                ? lines("#version 310 es",
                "#extension GL_EXT_shader_image_load_formatted : require",
                "precision highp float;",
                "precision highp int;")
                : HEADER;
        // R16F is not one of the core GLES image format qualifiers. The formatted-image
        // extension permits a load/store-only image to omit its format and infer R16F from the
        // image-unit binding. R32F keeps its core qualifier for the guaranteed fallback path.
        String imageLayout = r16f ? "layout(binding = 0)"
                : "layout(r32f, binding = 0)";
        return shaderHeader + RAW_INPUT + STATE + lines(
                "layout(local_size_x = 16, local_size_y = 16) in;",
                "uniform sampler2D uPreviousDepth;",
                imageLayout + " uniform writeonly highp image2D uCurrentDepth;",
                "float mappedDepth(ivec2 point) {",
                "    ivec2 p = clamp(point, ivec2(0), uOutputSize - ivec2(1));",
                "    float denominator = max(rangeState.w - rangeState.z, 1.0e-6);",
                "    return clamp((max(sourceAlignedRaw(p), 0.0) - rangeState.z) / denominator, 0.0, 1.0);",
                "}",
                "void main() {",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    if (any(greaterThanEqual(point, uOutputSize))) return;",
                "    float current = mappedDepth(point);",
                "    float previous = texelFetch(uPreviousDepth, point, 0).r;",
                "    float outputDepth = current;",
                "    if (stateFlags.z == 0u) {",
                "        float change = abs(current - previous);",
                "        float gradient = 0.0;",
                "        gradient = max(gradient, abs(current - mappedDepth(point + ivec2(-1, 0))));",
                "        gradient = max(gradient, abs(current - mappedDepth(point + ivec2(1, 0))));",
                "        gradient = max(gradient, abs(current - mappedDepth(point + ivec2(0, -1))));",
                "        gradient = max(gradient, abs(current - mappedDepth(point + ivec2(0, 1))));",
                "        float filtered = mix(previous, current, 0.50);",
                "        outputDepth = change >= 0.05 && gradient >= 0.02",
                "                ? mix(filtered, current, 0.25) : filtered;",
                "    }",
                "    imageStore(uCurrentDepth, point, vec4(outputDepth, 0.0, 0.0, 1.0));",
                "}"
        );
    }

    static final String RESET_PROFILE_STATS = HEADER + PROFILE_STATS + lines(
            "layout(local_size_x = 256) in;",
            "void main() {",
            "    uint index = gl_LocalInvocationID.x;",
            "    depthHistogram[index] = 0u;",
            "    subjectHistogram[index] = 0u;",
            "    if (index == 0u) {",
            "        edgeCount = 0u;",
            "        changeCount = 0u;",
            "        subjectWeightTotal = 0u;",
            "        profilePadding = 0u;",
            "    }",
            "}"
    );

    static final String ACCUMULATE_PROFILE = HEADER + PROFILE_STATS + STATE + lines(
            "layout(local_size_x = 16, local_size_y = 16) in;",
            "uniform sampler2D uCurrentDepth;",
            "uniform sampler2D uPreviousDepth;",
            "uniform ivec2 uOutputSize;",
            "void main() {",
            "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
            "    if (any(greaterThanEqual(point, uOutputSize))) return;",
            "    float value = clamp(texelFetch(uCurrentDepth, point, 0).r, 0.0, 1.0);",
            "    ivec2 right = min(point + ivec2(1, 0), uOutputSize - ivec2(1));",
            "    ivec2 down = min(point + ivec2(0, 1), uOutputSize - ivec2(1));",
            "    float gx = texelFetch(uCurrentDepth, right, 0).r - value;",
            "    float gy = texelFetch(uCurrentDepth, down, 0).r - value;",
            "    float gradient = sqrt(gx * gx + gy * gy);",
            "    if (gradient >= 0.02) atomicAdd(edgeCount, 1u);",
            "    float previous = stateFlags.z != 0u ? 0.0",
            "            : texelFetch(uPreviousDepth, point, 0).r;",
            "    if (abs(value - previous) >= 0.05) atomicAdd(changeCount, 1u);",
            "    float normalizedX = uOutputSize.x > 1",
            "            ? float(point.x) / float(uOutputSize.x - 1) * 2.0 - 1.0 : 0.0;",
            "    float normalizedY = uOutputSize.y > 1",
            "            ? float(point.y) / float(uOutputSize.y - 1) * 2.0 - 1.0 : 0.0;",
            "    float centerWeight = exp(-0.5 * (",
            "            (normalizedX / 0.70) * (normalizedX / 0.70)",
            "            + (normalizedY / 0.55) * (normalizedY / 0.55)));",
            "    float sigmoidValue = 1.0 / (1.0 + exp(-10.0 * (gradient - 0.025)));",
            "    uint weight = uint(centerWeight * (1.0 - sigmoidValue) * 1024.0 + 0.5);",
            "    uint bin = min(uint(value * 256.0), 255u);",
            "    atomicAdd(depthHistogram[bin], 1u);",
            "    atomicAdd(subjectHistogram[bin], weight);",
            "    atomicAdd(subjectWeightTotal, weight);",
            "}"
    );

    static final String RESOLVE_PROFILE = HEADER + PROFILE_STATS + STATE + lines(
            "layout(local_size_x = 1) in;",
            "layout(rgba32f, binding = 1) uniform writeonly highp image2D uProfileTexture;",
            "uniform int uExternalSceneCut;",
            "uniform int uPixelCount;",
            "float depthPercentile(float percentile) {",
            "    float target = percentile * float(uPixelCount);",
            "    uint cumulative = 0u;",
            "    for (uint bin = 0u; bin < 256u; ++bin) {",
            "        cumulative += depthHistogram[bin];",
            "        if (float(cumulative) >= target) return (float(bin) + 0.5) / 256.0;",
            "    }",
            "    return 255.5 / 256.0;",
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
            "    imageStore(uProfileTexture, ivec2(1, 0),",
            "            vec4(profileB.y, profileB.z, profileC.z, float(stateFlags.y)));",
            "    imageStore(uProfileTexture, ivec2(2, 0),",
            "            vec4(rangeState.x, rangeState.y, profileB.w, profileC.x));",
            "    imageStore(uProfileTexture, ivec2(3, 0),",
            "            vec4(profileA.w, profileC.y, float(stateFlags.w), float(stateCounters.x)));",
            "}",
            "void main() {",
            "    if (subjectWeightTotal == 0u || uPixelCount <= 0) {",
            "        stateFlags.w = 0u;",
            "        publishProfile();",
            "        return;",
            "    }",
            "    float subjectCandidate = subjectNearPercentile();",
            "    float stretchLow = depthPercentile(0.05);",
            "    float stretchHigh = depthPercentile(0.95);",
            "    float stretchInverse = 1.0 / max(stretchHigh - stretchLow, 1.0e-4);",
            "    float edgeFraction = float(edgeCount) / float(uPixelCount);",
            "    float changeFraction = float(changeCount) / float(uPixelCount);",
            "    bool wasInitialized = stateFlags.y != 0u;",
            "    int sceneAge = wasInitialized ? min(stateCounters.x + 1, 65535) : 0;",
            "    int cutState = stateCounters.y;",
            "    bool cutReady = cutState > 0;",
            "    bool hardCut = wasInitialized && (uExternalSceneCut != 0",
            "            || (cutReady && changeFraction >= 0.65));",
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
            "            : mix(profileB.x, subjectCandidate, 0.20);",
            "    float stretchedSubject = clamp((subjectDepth - stretchLow) * stretchInverse, 0.0, 1.0);",
            "    float recenter = (0.5 - stretchedSubject) * 0.35;",
            "    float convergenceTarget = (1.0 - subjectDepth) * 0.006;",
            "    float convergence = !wasInitialized || hardCut ? convergenceTarget",
            "            : mix(profileB.z, convergenceTarget, 0.10);",
            "    float popStrength = wasInitialized ? profileC.y : 1.25;",
            "    if (!wasInitialized || hardCut) {",
            "        float confidence = 1.0 - smoothstep(0.007, 0.016, edgeFraction);",
            "        popStrength = mix(1.25, 1.30, confidence);",
            "    }",
            "    profileA = vec4(stretchLow, stretchHigh, stretchInverse, subjectCandidate);",
            "    profileB = vec4(subjectDepth, recenter, convergence, edgeFraction);",
            "    profileC = vec4(changeFraction, popStrength, popStrength / 1.25, 0.0);",
            "    stateFlags.y = 1u;",
            "    stateFlags.w = hardCut ? 1u : 0u;",
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
            "    profileC = vec4(0.0, 1.25, 1.0, 0.0);",
            "    stateFlags = uvec4(0u);",
            "    stateCounters = ivec4(0);",
            "    imageStore(uProfileTexture, ivec2(0, 0), vec4(0.0, 1.0, 1.0, 0.5));",
            "    imageStore(uProfileTexture, ivec2(1, 0), vec4(0.0, 0.0, 1.0, 0.0));",
            "    imageStore(uProfileTexture, ivec2(2, 0), vec4(0.0));",
            "    imageStore(uProfileTexture, ivec2(3, 0), vec4(0.5, 1.25, 0.0, 0.0));",
            "}"
    );
}
