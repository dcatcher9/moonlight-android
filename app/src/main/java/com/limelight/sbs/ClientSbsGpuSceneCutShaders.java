package com.limelight.sbs;

/** Compute shaders used by {@link ClientSbsGpuSceneCutDetector}. */
final class ClientSbsGpuSceneCutShaders {
    private ClientSbsGpuSceneCutShaders() {
    }

    private static String lines(String... source) {
        return String.join("\n", source) + "\n";
    }

    private static final String HEADER = lines(
            "#version 310 es",
            "precision highp float;",
            "precision highp int;"
    );

    /*
     * Keep this std430 declaration byte-for-byte identical in every pass. Current-frame fields
     * are reset before each detection. The previous summary and histogram survive until COMMIT
     * replaces them after the renderer accepts the frame, so no CPU-side history or readback is
     * needed.
     */
    private static final String STATS = lines(
            "layout(std430, binding = 0) buffer SceneCutStats {",
            "    uint currentLumaSum;",
            "    uint currentBlockCount;",
            "    uint previousLumaSum;",
            "    uint previousBlockCount;",
            "    uint rawDeltaSum;",
            "    uint centeredDeltaSum;",
            "    uint rawModerateCount;",
            "    uint centeredModerateCount;",
            "    uint centeredStrongCount;",
            "    uint centeredVeryStrongCount;",
            "    uint histogramL1;",
            "    uint statsPadding;",
            "    uint currentHistogram[16];",
            "    uint previousHistogram[16];",
            "};"
    );

    private static final String OUTPUT = lines(
            "layout(std430, binding = 1) buffer SceneCutOutput {",
            "    uint sceneCutWords[];",
            "};"
    );

    static final String RESET = HEADER + STATS + OUTPUT + lines(
            "layout(local_size_x = 16) in;",
            "uniform uint uOutputWordOffset;",
            "void main() {",
            "    uint index = gl_LocalInvocationID.x;",
            "    currentHistogram[index] = 0u;",
            "    if (index == 0u) {",
            "        sceneCutWords[uOutputWordOffset] = 0u;",
            "        currentLumaSum = 0u;",
            "        currentBlockCount = 0u;",
            "        rawDeltaSum = 0u;",
            "        centeredDeltaSum = 0u;",
            "        rawModerateCount = 0u;",
            "        centeredModerateCount = 0u;",
            "        centeredStrongCount = 0u;",
            "        centeredVeryStrongCount = 0u;",
            "        histogramL1 = 0u;",
            "        statsPadding = 0u;",
            "    }",
            "}"
    );

    /**
     * Builds the stream-fixed pass that both packs LiteRT's Float32 NHWC tensor and reduces the
     * same model-input texels into the persistent scene-cut luma grid. Texture row zero is GL's
     * bottom row, so the tensor destination row is flipped to retain the model's top-first layout.
     */
    static String createPackAndDownsampleLuma(int tensorWidth, int tensorHeight) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("Model input dimensions must be positive");
        }
        return HEADER + STATS + lines(
            "layout(local_size_x = 16, local_size_y = 16) in;",
            "layout(r32ui, binding = 1) uniform writeonly highp uimage2D uCurrentLuma;",
            "uniform highp sampler2D uCurrentColor;",
            "layout(std430, binding = 2) buffer InputTensor {",
            "    float tensorValues[];",
            "};",
            "const uint TENSOR_WIDTH = " + tensorWidth + "u;",
            "const uint TENSOR_HEIGHT = " + tensorHeight + "u;",
            "const uvec2 INPUT_SIZE = uvec2(TENSOR_WIDTH, TENSOR_HEIGHT);",
            "shared uvec2 blockTotals[256];",
            "void main() {",
            "    uint lane = gl_LocalInvocationIndex;",
            "    uvec2 point = gl_GlobalInvocationID.xy;",
            "    blockTotals[lane] = uvec2(0u);",
            "    if (all(lessThan(point, INPUT_SIZE))) {",
            "        vec3 sourceRgb = texelFetch(uCurrentColor, ivec2(point), 0).rgb;",
            // Preserve the former pack shader exactly: clamp only, and flip GL's source row into
            // the model's top-first NHWC destination row.
            "        vec3 tensorRgb = clamp(sourceRgb, vec3(0.0), vec3(1.0));",
            "        uint tensorY = TENSOR_HEIGHT - 1u - point.y;",
            "        uint firstValue = (tensorY * TENSOR_WIDTH + point.x) * 3u;",
            "        tensorValues[firstValue] = tensorRgb.r;",
            "        tensorValues[firstValue + 1u] = tensorRgb.g;",
            "        tensorValues[firstValue + 2u] = tensorRgb.b;",
            // Preserve the detector's independent non-finite sanitization and luma quantization.
            "        vec3 rgb = sourceRgb;",
            "        if (any(isnan(rgb)) || any(isinf(rgb))) rgb = vec3(0.0);",
            "        rgb = clamp(rgb, vec3(0.0), vec3(1.0));",
            "        float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));",
            "        uint quantizedLuma = uint(luma * 255.0 + 0.5);",
            "        blockTotals[lane] = uvec2(quantizedLuma, 1u);",
            "    }",
            "    barrier();",
            // Fixed tree reduction avoids two contended shared-memory atomics per model pixel.
            "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
            "        if (lane < stride) blockTotals[lane] += blockTotals[lane + stride];",
            "        barrier();",
            "    }",
            "    if (lane == 0u && blockTotals[0].y != 0u) {",
            "        uint blockLuma = (blockTotals[0].x + blockTotals[0].y / 2u)",
            "                / blockTotals[0].y;",
            "        ivec2 block = ivec2(gl_WorkGroupID.xy);",
            "        imageStore(uCurrentLuma, block, uvec4(blockLuma, 0u, 0u, 1u));",
            "        atomicAdd(currentLumaSum, blockLuma);",
            "        atomicAdd(currentBlockCount, 1u);",
            "        atomicAdd(currentHistogram[min(blockLuma >> 4u, 15u)], 1u);",
            "    }",
            "}"
        );
    }

    static final String COMPARE = HEADER + STATS + lines(
            "layout(local_size_x = 16, local_size_y = 16) in;",
            "layout(r32ui, binding = 0) uniform readonly highp uimage2D uPreviousLuma;",
            "layout(r32ui, binding = 1) uniform readonly highp uimage2D uCurrentLuma;",
            "uniform ivec2 uBlockGrid;",
            "uniform int uHistoryValid;",
            // Block averaging makes these thresholds insensitive to ordinary moving objects.
            "const uint RAW_MODERATE_DELTA = 28u;",
            "const uint CENTERED_MODERATE_DELTA = 22u;",
            "const uint CENTERED_STRONG_DELTA = 44u;",
            "const uint CENTERED_VERY_STRONG_DELTA = 72u;",
            "shared uvec4 localDeltaTotals[256];",
            "shared uvec2 localCountTotals[256];",
            "uint absoluteDifference(int left, int right) {",
            "    return uint(abs(left - right));",
            "}",
            "void main() {",
            "    uint lane = gl_LocalInvocationIndex;",
            "    localDeltaTotals[lane] = uvec4(0u);",
            "    localCountTotals[lane] = uvec2(0u);",
            "    ivec2 block = ivec2(gl_GlobalInvocationID.xy);",
            "    bool comparable = uHistoryValid != 0 && all(lessThan(block, uBlockGrid))",
            "            && currentBlockCount != 0u && previousBlockCount != 0u;",
            "    if (comparable) {",
            "        uint current = imageLoad(uCurrentLuma, block).r;",
            "        uint previous = imageLoad(uPreviousLuma, block).r;",
            "        uint currentMean = (currentLumaSum + currentBlockCount / 2u)",
            "                / currentBlockCount;",
            "        uint previousMean = (previousLumaSum + previousBlockCount / 2u)",
            "                / previousBlockCount;",
            "        uint rawDelta = absoluteDifference(int(current), int(previous));",
            "        uint centeredDelta = absoluteDifference(int(current) - int(currentMean),",
            "                int(previous) - int(previousMean));",
            "        localDeltaTotals[lane] = uvec4(rawDelta, centeredDelta,",
            "                rawDelta >= RAW_MODERATE_DELTA ? 1u : 0u,",
            "                centeredDelta >= CENTERED_MODERATE_DELTA ? 1u : 0u);",
            "        localCountTotals[lane] = uvec2(",
            "                centeredDelta >= CENTERED_STRONG_DELTA ? 1u : 0u,",
            "                centeredDelta >= CENTERED_VERY_STRONG_DELTA ? 1u : 0u);",
            "    }",
            "    barrier();",
            "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
            "        if (lane < stride) {",
            "            localDeltaTotals[lane] += localDeltaTotals[lane + stride];",
            "            localCountTotals[lane] += localCountTotals[lane + stride];",
            "        }",
            "        barrier();",
            "    }",
            "    if (lane == 0u) {",
            "        uvec4 delta = localDeltaTotals[0];",
            "        uvec2 count = localCountTotals[0];",
            "        if (delta.x != 0u) atomicAdd(rawDeltaSum, delta.x);",
            "        if (delta.y != 0u) atomicAdd(centeredDeltaSum, delta.y);",
            "        if (delta.z != 0u) atomicAdd(rawModerateCount, delta.z);",
            "        if (delta.w != 0u) atomicAdd(centeredModerateCount, delta.w);",
            "        if (count.x != 0u) atomicAdd(centeredStrongCount, count.x);",
            "        if (count.y != 0u) atomicAdd(centeredVeryStrongCount, count.y);",
            "    }",
            "}"
    );

    static final String RESOLVE = HEADER + STATS + OUTPUT + lines(
            "layout(local_size_x = 1) in;",
            "uniform int uHistoryValid;",
            "uniform uint uOutputWordOffset;",
            "bool fractionAtLeast(uint value, uint total, uint percent) {",
            "    return total != 0u && value >= (total * percent + 99u) / 100u;",
            "}",
            "void main() {",
            "    bool comparable = uHistoryValid != 0 && currentBlockCount != 0u",
            "            && currentBlockCount == previousBlockCount;",
            "    uint l1 = 0u;",
            "    if (comparable) {",
            "        for (uint bin = 0u; bin < 16u; ++bin) {",
            "            uint current = currentHistogram[bin];",
            "            uint previous = previousHistogram[bin];",
            "            l1 += current >= previous ? current - previous : previous - current;",
            "        }",
            "    }",
            "    histogramL1 = l1;",
            "    uint blocks = currentBlockCount;",
            "    bool broadRawChange = fractionAtLeast(rawModerateCount, blocks, 55u);",
            "    bool broadStructuralChange = fractionAtLeast(centeredModerateCount, blocks, 48u);",
            "    bool strongStructuralChange = fractionAtLeast(centeredStrongCount, blocks, 20u);",
            "    bool enoughRawEnergy = blocks != 0u && rawDeltaSum >= blocks * 34u;",
            "    bool enoughStructuralEnergy = blocks != 0u && centeredDeltaSum >= blocks * 27u;",
            // L1/(2*N) >= 0.14: the coarse luma distribution changed materially.
            "    bool histogramChanged = blocks != 0u && l1 * 100u >= blocks * 28u;",
            // Preserve recall for cuts between scenes with similar aggregate histograms.
            "    bool overwhelmingStructure = fractionAtLeast(centeredVeryStrongCount, blocks, 14u)",
            "            && centeredDeltaSum >= blocks * 42u;",
            "    uint currentMean = blocks == 0u ? 0u",
            "            : (currentLumaSum + blocks / 2u) / blocks;",
            "    uint previousMean = previousBlockCount == 0u ? 0u",
            "            : (previousLumaSum + previousBlockCount / 2u) / previousBlockCount;",
            "    uint meanJump = currentMean >= previousMean",
            "            ? currentMean - previousMean : previousMean - currentMean;",
            // A near-global black/white transition has little centered structure but is a cut.
            "    bool uniformHardTransition = meanJump >= 72u",
            "            && fractionAtLeast(rawModerateCount, blocks, 90u);",
            "    bool hardCut = comparable && ((broadRawChange && broadStructuralChange",
            "            && strongStructuralChange && enoughRawEnergy && enoughStructuralEnergy",
            "            && (histogramChanged || overwhelmingStructure))",
            "            || uniformHardTransition);",
            "    sceneCutWords[uOutputWordOffset] = hardCut ? 1u : 0u;",
            "}"
    );

    /** Commits a resolved frame only after the renderer transfers it to the inference queue. */
    static final String COMMIT = HEADER + STATS + lines(
            "layout(local_size_x = 16) in;",
            "void main() {",
            "    uint index = gl_LocalInvocationID.x;",
            "    previousHistogram[index] = currentHistogram[index];",
            "    if (index == 0u) {",
            "        previousLumaSum = currentLumaSum;",
            "        previousBlockCount = currentBlockCount;",
            "    }",
            "}"
    );
}
