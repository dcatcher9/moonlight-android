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
            "    uint currentBlockCount;",
            "    uint previousBlockCount;",
            "    uint rawDeltaSum;",
            "    uint rawModerateCount;",
            "    uint structuralChangeCount;",
            "    uint histogramL1;",
            // Keep the histogram arrays 16-byte aligned even though std430 only requires scalar
            // alignment here. This preserves the conservative layout used by the original path.
            "    uint statsPadding0;",
            "    uint statsPadding1;",
            "    uint currentHistogram[16];",
            "    uint previousHistogram[16];",
            "};"
    );

    private static final String OUTPUT = lines(
            "layout(std430, binding = 1) buffer SceneCutOutput {",
            "    uint sceneCutWords[];",
            "};",
            "const uint SCENE_EVIDENCE_APPEARANCE = "
                    + ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE + "u;",
            "const uint SCENE_EVIDENCE_EXPOSURE_LIKE = "
                    + ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE + "u;"
    );

    static final String RESET = HEADER + STATS + OUTPUT + lines(
            "layout(local_size_x = 16) in;",
            "uniform uint uOutputWordOffset;",
            "void main() {",
            "    uint index = gl_LocalInvocationID.x;",
            "    currentHistogram[index] = 0u;",
            "    if (index == 0u) {",
            "        sceneCutWords[uOutputWordOffset] = 0u;",
            "        currentBlockCount = 0u;",
            "        rawDeltaSum = 0u;",
            "        rawModerateCount = 0u;",
            "        structuralChangeCount = 0u;",
            "        histogramL1 = 0u;",
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
            // One max-RGB sample per model-input texel. Lane zero later gathers a fixed 3x3
            // lattice and takes its median. An order statistic commutes with any shared monotone
            // per-channel exposure curve, including clamp-created ties.
            "shared uint blockOrdinalValues[256];",
            "void main() {",
            "    uint lane = gl_LocalInvocationIndex;",
            "    uvec2 point = gl_GlobalInvocationID.xy;",
            "    blockTotals[lane] = uvec2(0u);",
            "    blockOrdinalValues[lane] = 0u;",
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
            "        blockOrdinalValues[lane] = uint(max(rgb.r, max(rgb.g, rgb.b))",
            "                * 255.0 + 0.5);",
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
            "        uvec2 blockOrigin = gl_WorkGroupID.xy * 16u;",
            "        uint validWidth = min(16u, TENSOR_WIDTH - blockOrigin.x);",
            "        uint validHeight = min(16u, TENSOR_HEIGHT - blockOrigin.y);",
            "        uvec3 sampleX = uvec3(0u, (validWidth - 1u) / 2u, validWidth - 1u);",
            "        uvec3 sampleY = uvec3(0u, (validHeight - 1u) / 2u, validHeight - 1u);",
            "        uint ordinalSamples[9];",
            "        for (uint sampleYIndex = 0u; sampleYIndex < 3u; ++sampleYIndex) {",
            "            for (uint sampleXIndex = 0u; sampleXIndex < 3u; ++sampleXIndex) {",
            "                uint sampleIndex = sampleY[sampleYIndex] * 16u",
            "                        + sampleX[sampleXIndex];",
            "                ordinalSamples[sampleYIndex * 3u + sampleXIndex] =",
            "                        blockOrdinalValues[sampleIndex];",
            "            }",
            "        }",
            // Nine-value insertion sort costs only 36 comparisons in the single reducing lane.
            "        for (uint sampleIndex = 1u; sampleIndex < 9u; ++sampleIndex) {",
            "            uint sampleValue = ordinalSamples[sampleIndex];",
            "            int insertionIndex = int(sampleIndex) - 1;",
            "            while (insertionIndex >= 0",
            "                    && ordinalSamples[insertionIndex] > sampleValue) {",
            "                ordinalSamples[insertionIndex + 1] =",
            "                        ordinalSamples[insertionIndex];",
            "                --insertionIndex;",
            "            }",
            "            ordinalSamples[insertionIndex + 1] = sampleValue;",
            "        }",
            "        uint ordinalMedian = ordinalSamples[4];",
            "        ivec2 block = ivec2(gl_WorkGroupID.xy);",
            // Low byte remains average Rec.709 luma for the raw/histogram gates. The next byte is
            // the exposure-monotone structural descriptor consumed by COMPARE.
            "        uint packedBlock = blockLuma | (ordinalMedian << 8u);",
            "        imageStore(uCurrentLuma, block, uvec4(packedBlock, 0u, 0u, 1u));",
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
            // Compare all ten pairwise orderings in a cross-five neighborhood of tile medians.
            // A shared monotone exposure curve cannot reverse an ordering. If clipping creates a
            // tie, the two-sided floor makes that relation abstain instead of voting.
            "const int ORDINAL_COMPARISON_FLOOR = 4;",
            "shared uvec4 localDeltaTotals[256];",
            "uint unsignedAbsoluteDifference(int left, int right) {",
            "    return uint(abs(left - right));",
            "}",
            "ivec2 clampedBlock(ivec2 block) {",
            "    return clamp(block, ivec2(0), uBlockGrid - ivec2(1));",
            "}",
            "int blockLuma(uint packedBlock) {",
            "    return int(packedBlock & 255u);",
            "}",
            "int ordinalMedian(uint packedBlock) {",
            "    return int((packedBlock >> 8u) & 255u);",
            "}",
            "uvec2 orderingEvidence(int currentFirst, int currentSecond,",
            "                       int previousFirst, int previousSecond) {",
            "    int currentDelta = currentFirst - currentSecond;",
            "    int previousDelta = previousFirst - previousSecond;",
            "    bool reliable = abs(currentDelta) >= ORDINAL_COMPARISON_FLOOR",
            "            && abs(previousDelta) >= ORDINAL_COMPARISON_FLOOR;",
            "    bool reversed = reliable && ((currentDelta < 0) != (previousDelta < 0));",
            "    return uvec2(reliable ? 1u : 0u, reversed ? 1u : 0u);",
            "}",
            "void main() {",
            "    uint lane = gl_LocalInvocationIndex;",
            "    localDeltaTotals[lane] = uvec4(0u);",
            "    ivec2 block = ivec2(gl_GlobalInvocationID.xy);",
            "    bool comparable = uHistoryValid != 0 && all(lessThan(block, uBlockGrid))",
            "            && currentBlockCount != 0u && previousBlockCount != 0u;",
            "    if (comparable) {",
            "        ivec2 leftBlock = clampedBlock(block + ivec2(-1, 0));",
            "        ivec2 rightBlock = clampedBlock(block + ivec2(1, 0));",
            "        ivec2 upBlock = clampedBlock(block + ivec2(0, -1));",
            "        ivec2 downBlock = clampedBlock(block + ivec2(0, 1));",
            "        uint currentPacked[5];",
            "        uint previousPacked[5];",
            "        currentPacked[0] = imageLoad(uCurrentLuma, block).r;",
            "        currentPacked[1] = imageLoad(uCurrentLuma, leftBlock).r;",
            "        currentPacked[2] = imageLoad(uCurrentLuma, rightBlock).r;",
            "        currentPacked[3] = imageLoad(uCurrentLuma, upBlock).r;",
            "        currentPacked[4] = imageLoad(uCurrentLuma, downBlock).r;",
            "        previousPacked[0] = imageLoad(uPreviousLuma, block).r;",
            "        previousPacked[1] = imageLoad(uPreviousLuma, leftBlock).r;",
            "        previousPacked[2] = imageLoad(uPreviousLuma, rightBlock).r;",
            "        previousPacked[3] = imageLoad(uPreviousLuma, upBlock).r;",
            "        previousPacked[4] = imageLoad(uPreviousLuma, downBlock).r;",
            "        uint rawDelta = unsignedAbsoluteDifference(",
            "                blockLuma(currentPacked[0]), blockLuma(previousPacked[0]));",
            "        uvec2 ordinalEvidence = uvec2(0u);",
            "        for (int first = 0; first < 5; ++first) {",
            "            for (int second = first + 1; second < 5; ++second) {",
            "                ordinalEvidence += orderingEvidence(",
            "                        ordinalMedian(currentPacked[first]),",
            "                        ordinalMedian(currentPacked[second]),",
            "                        ordinalMedian(previousPacked[first]),",
            "                        ordinalMedian(previousPacked[second]));",
            "            }",
            "        }",
            // Require common support, at least two reversals, and a reversal majority. A single
            // noisy ordering cannot turn one site into structural evidence.
            "        bool structureChanged = ordinalEvidence.x >= 4u",
            "                && ordinalEvidence.y >= 2u",
            "                && ordinalEvidence.y * 2u >= ordinalEvidence.x;",
            "        localDeltaTotals[lane] = uvec4(rawDelta,",
            "                rawDelta >= RAW_MODERATE_DELTA ? 1u : 0u,",
            "                structureChanged ? 1u : 0u, 0u);",
            "    }",
            "    barrier();",
            "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
            "        if (lane < stride) {",
            "            localDeltaTotals[lane] += localDeltaTotals[lane + stride];",
            "        }",
            "        barrier();",
            "    }",
            "    if (lane == 0u) {",
            "        uvec4 delta = localDeltaTotals[0];",
            "        if (delta.x != 0u) atomicAdd(rawDeltaSum, delta.x);",
            "        if (delta.y != 0u) atomicAdd(rawModerateCount, delta.y);",
            "        if (delta.z != 0u) atomicAdd(structuralChangeCount, delta.z);",
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
            "    bool enoughRawEnergy = blocks != 0u && rawDeltaSum >= blocks * 34u;",
            "    bool broadStructuralChange = fractionAtLeast(",
            "            structuralChangeCount, blocks, 15u);",
            // Reserve a deliberately quiet band for the exposure veto. The 5-15% ambiguous band
            // is neither qualified appearance nor exposure-like, so standalone depth authority
            // remains available for low-structure editorial cuts.
            "    bool quietStructuralChange = !fractionAtLeast(",
            "            structuralChangeCount, blocks, 5u);",
            // Histogram L1 remains diagnostic, but it is deliberately not authority: exposure can
            // move a histogram while same-histogram editorial cuts are real. Local structure plus
            // broad raw change proposes the cut, and depth geometry still owns shot relatching.
            "    bool hardCut = comparable && broadRawChange && enoughRawEnergy",
            "            && broadStructuralChange;",
            "    bool exposureLike = comparable && broadRawChange && enoughRawEnergy",
            "            && quietStructuralChange;",
            "    uint evidence = hardCut ? SCENE_EVIDENCE_APPEARANCE : 0u;",
            "    if (exposureLike) evidence |= SCENE_EVIDENCE_EXPOSURE_LIKE;",
            "    sceneCutWords[uOutputWordOffset] = evidence;",
            "}"
    );

    /** Commits a resolved frame only after the renderer transfers it to the inference queue. */
    static final String COMMIT = HEADER + STATS + lines(
            "layout(local_size_x = 16) in;",
            "void main() {",
            "    uint index = gl_LocalInvocationID.x;",
            "    previousHistogram[index] = currentHistogram[index];",
            "    if (index == 0u) {",
            "        previousBlockCount = currentBlockCount;",
            "    }",
            "}"
    );
}
