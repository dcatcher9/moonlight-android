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
     * either advances them to an accepted supported frame or bridges a structureless interval, so
     * no CPU-side history or readback is needed.
     */
    private static final String STATS = lines(
            "layout(std430, binding = 0) buffer SceneCutStats {",
            "    uint currentBlockCount;",
            "    uint previousBlockCount;",
            "    uint rawDeltaSum;",
            "    uint rawModerateCount;",
            "    uint structuralChangeCount;",
            "    uint histogramL1;",
            // Reuse the two reserved words for current-frame and cross-frame structural support.
            // The histogram offset and the detector's fixed SSBO size remain unchanged.
            "    uint currentStructuralSupportCount;",
            "    uint commonStructuralSupportCount;",
            "    uint currentHistogram[16];",
            "    uint previousHistogram[16];",
            "    uint detectorHistoryValid;",
            "    uint nearOwnerValid;",
            "    uvec2 nearOwnerFrameSequence;",
            "    uvec2 nearOwnerCapturedAtNs;",
            "};",
            // Together these two bits encode the accepted-history state without growing the SSBO:
            // 00 normal unsupported, 01 accepted persistent-low, 10 normal supported, 11 one-frame
            // supported-history hold. The low 30 bits remain the exact block count.
            "const uint HISTORY_GAP_PENDING = 0x80000000u;",
            "const uint HISTORY_STRUCTURE_SUPPORTED = 0x40000000u;",
            "const uint HISTORY_BLOCK_COUNT_MASK = 0x3fffffffu;"
    );

    private static final String FRACTION_AT_LEAST = lines(
            "bool fractionAtLeast(uint value, uint total, uint percent) {",
            "    return total != 0u && value >= (total * percent + 99u) / 100u;",
            "}"
    );

    private static final String OUTPUT = lines(
            "layout(std430, binding = 1) buffer SceneCutOutput {",
            "    uint sceneCutWords[];",
            "};",
            "const uint SCENE_CUT_RECORD_WORD_COUNT = "
                    + ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_WORD_COUNT + "u;",
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
                    + ClientSbsShotCutPolicy.SCENE_EVIDENCE_SUPPORTED_RETURN + "u;"
    );

    static final String RESET = HEADER + STATS + OUTPUT + lines(
            "layout(local_size_x = 16) in;",
            "uniform uint uOutputWordOffset;",
            "uniform int uClearHistory;",
            "void main() {",
            "    uint index = gl_LocalInvocationID.x;",
            "    currentHistogram[index] = 0u;",
            "    if (uClearHistory != 0) previousHistogram[index] = 0u;",
            "    if (index < SCENE_CUT_RECORD_WORD_COUNT)",
            "        sceneCutWords[uOutputWordOffset + index] = 0u;",
            "    if (index == 0u) {",
            "        currentBlockCount = 0u;",
            "        if (uClearHistory != 0) {",
            "            previousBlockCount = 0u;",
            "            detectorHistoryValid = 0u;",
            "            nearOwnerValid = 0u;",
            "            nearOwnerFrameSequence = uvec2(0u);",
            "            nearOwnerCapturedAtNs = uvec2(0u);",
            "        }",
            "        rawDeltaSum = 0u;",
            "        rawModerateCount = 0u;",
            "        structuralChangeCount = 0u;",
            "        currentStructuralSupportCount = 0u;",
            "        commonStructuralSupportCount = 0u;",
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
            "layout(rgba32ui, binding = 1) uniform writeonly highp uimage2D uCurrentLuma;",
            "uniform highp sampler2D uCurrentColor;",
            "uniform int uNearIdenticalCandidate;",
            "uniform uvec2 uCurrentFrameSequence;",
            "uniform uvec2 uCurrentCapturedAtNs;",
            "layout(std430, binding = 2) buffer InputTensor {",
            "    float tensorValues[];",
            "};",
            "layout(std430, binding = 3) readonly buffer PreviousInputTensor {",
            "    float previousTensorValues[];",
            "};",
            "const uint TENSOR_WIDTH = " + tensorWidth + "u;",
            "const uint TENSOR_HEIGHT = " + tensorHeight + "u;",
            "const uvec2 INPUT_SIZE = uvec2(TENSOR_WIDTH, TENSOR_HEIGHT);",
            "const float MEDIUM_DELTA = "
                    + ClientSbsNearIdenticalPolicy.MEDIUM_DELTA + ";",
            "const float STRONG_DELTA = "
                    + ClientSbsNearIdenticalPolicy.STRONG_DELTA + ";",
            "shared uvec2 blockTotals[256];",
            // One max-RGB sample per model-input texel. Lane zero later gathers a fixed 3x3
            // lattice and takes its median. An order statistic commutes with any shared monotone
            // per-channel exposure curve, including clamp-created ties.
            "shared uint blockOrdinalValues[256];",
            // (admitted, medium, strong, non-finite) evidence for the exact same 16x16 tile.
            "shared uvec4 nearIdenticalTotals[256];",
            "bool lessThan64(uvec2 left, uvec2 right) {",
            "    return left.y < right.y || (left.y == right.y && left.x < right.x);",
            "}",
            "uvec2 subtract64(uvec2 larger, uvec2 smaller) {",
            "    uint borrow = larger.x < smaller.x ? 1u : 0u;",
            "    return uvec2(larger.x - smaller.x, larger.y - smaller.y - borrow);",
            "}",
            "bool nearOwnerEligible() {",
            "    if (nearOwnerValid == 0u",
            "            || !lessThan64(nearOwnerFrameSequence, uCurrentFrameSequence)",
            "            || lessThan64(uCurrentCapturedAtNs, nearOwnerCapturedAtNs)) return false;",
            "    uvec2 frameDelta = subtract64(uCurrentFrameSequence, nearOwnerFrameSequence);",
            "    uvec2 age = subtract64(uCurrentCapturedAtNs, nearOwnerCapturedAtNs);",
            "    return frameDelta.y == 0u && frameDelta.x <= "
                    + ClientSbsNearIdenticalPolicy.MAX_INFER_OWNER_FRAME_GAP + "u",
            "            && age.y == 0u && age.x < "
                    + ClientSbsNearIdenticalPolicy.MAX_INFER_OWNER_AGE_NS + "u;",
            "}",
            "void main() {",
            "    uint lane = gl_LocalInvocationIndex;",
            "    uvec2 point = gl_GlobalInvocationID.xy;",
            "    blockTotals[lane] = uvec2(0u);",
            "    blockOrdinalValues[lane] = 0u;",
            "    nearIdenticalTotals[lane] = uvec4(0u);",
            "    bool inBounds = all(lessThan(point, INPUT_SIZE));",
            "    uint firstValue = 0u;",
            "    if (inBounds) {",
            "        vec3 sourceRgb = texelFetch(uCurrentColor, ivec2(point), 0).rgb;",
            // Preserve the former pack shader exactly: clamp only, and flip GL's source row into
            // the model's top-first NHWC destination row.
            "        vec3 tensorRgb = clamp(sourceRgb, vec3(0.0), vec3(1.0));",
            "        uint tensorY = TENSOR_HEIGHT - 1u - point.y;",
            "        firstValue = (tensorY * TENSOR_WIDTH + point.x) * 3u;",
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
            // Observe the exact words committed to the model-input SSBO, rather than relying on
            // a compiler-retained source value. Every lane participates before any history read.
            "    memoryBarrierBuffer();",
            "    barrier();",
            // The candidate branch is the only place that reads actual-inference history. A
            // disabled candidate therefore has zero evidence and no history dependency.
            "    bool effectiveNearIdenticalCandidate = uNearIdenticalCandidate != 0",
            "            && nearOwnerEligible();",
            "    if (inBounds && effectiveNearIdenticalCandidate) {",
            "        vec3 currentRgb = vec3(tensorValues[firstValue],",
            "                tensorValues[firstValue + 1u], tensorValues[firstValue + 2u]);",
            "        vec3 previousRgb = vec3(previousTensorValues[firstValue],",
            "                previousTensorValues[firstValue + 1u],",
            "                previousTensorValues[firstValue + 2u]);",
            "        bool finitePair = !any(isnan(currentRgb)) && !any(isinf(currentRgb))",
            "                && !any(isnan(previousRgb)) && !any(isinf(previousRgb));",
            "        float maxDelta = finitePair",
            "                ? max(abs(currentRgb.r - previousRgb.r),",
            "                max(abs(currentRgb.g - previousRgb.g),",
            "                abs(currentRgb.b - previousRgb.b))) : 0.0;",
            "        nearIdenticalTotals[lane] = uvec4(1u,",
            "                finitePair && maxDelta >= MEDIUM_DELTA ? 1u : 0u,",
            "                finitePair && maxDelta >= STRONG_DELTA ? 1u : 0u,",
            "                finitePair ? 0u : 1u);",
            "    }",
            "    barrier();",
            // Fixed tree reduction avoids two contended shared-memory atomics per model pixel.
            "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
            "        if (lane < stride) {",
            "            blockTotals[lane] += blockTotals[lane + stride];",
            "            nearIdenticalTotals[lane] += nearIdenticalTotals[lane + stride];",
            "        }",
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
            "        uvec4 nearEvidence = nearIdenticalTotals[0];",
            "        uint packedStrongAndNonfinite = nearEvidence.z",
            "                | (nearEvidence.w << 16u);",
            "        imageStore(uCurrentLuma, block, uvec4(packedBlock, nearEvidence.x,",
            "                nearEvidence.y, packedStrongAndNonfinite));",
            "        atomicAdd(currentBlockCount, 1u);",
            "        atomicAdd(currentHistogram[min(blockLuma >> 4u, 15u)], 1u);",
            "    }",
            "}"
        );
    }

    static final String COMPARE = HEADER + STATS + lines(
            "layout(local_size_x = 16, local_size_y = 16) in;",
            "layout(rgba32ui, binding = 0) uniform readonly highp uimage2D uPreviousLuma;",
            "layout(rgba32ui, binding = 1) uniform readonly highp uimage2D uCurrentLuma;",
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
            "uvec3 orderingEvidence(int currentFirst, int currentSecond,",
            "                       int previousFirst, int previousSecond) {",
            "    int currentDelta = currentFirst - currentSecond;",
            "    int previousDelta = previousFirst - previousSecond;",
            "    bool currentReliable = abs(currentDelta) >= ORDINAL_COMPARISON_FLOOR;",
            "    bool commonReliable = currentReliable",
            "            && abs(previousDelta) >= ORDINAL_COMPARISON_FLOOR;",
            "    bool reversed = commonReliable && ((currentDelta < 0) != (previousDelta < 0));",
            "    return uvec3(currentReliable ? 1u : 0u,",
            "            commonReliable ? 1u : 0u, reversed ? 1u : 0u);",
            "}",
            "void main() {",
            "    uint lane = gl_LocalInvocationIndex;",
            "    localDeltaTotals[lane] = uvec4(0u);",
            "    ivec2 block = ivec2(gl_GlobalInvocationID.xy);",
            "    bool comparable = uHistoryValid != 0 && detectorHistoryValid != 0u",
            "            && all(lessThan(block, uBlockGrid))",
            "            && currentBlockCount != 0u",
            "            && (previousBlockCount & HISTORY_BLOCK_COUNT_MASK) != 0u;",
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
            "        uvec3 ordinalEvidence = uvec3(0u);",
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
            "        bool currentStructureSupported = ordinalEvidence.x >= 4u;",
            "        bool commonStructureSupported = ordinalEvidence.y >= 4u;",
            "        bool structureChanged = commonStructureSupported",
            "                && ordinalEvidence.z >= 2u",
            "                && ordinalEvidence.z * 2u >= ordinalEvidence.y;",
            // Each workgroup reduces at most 256 one-bit support votes. Pack current/common into
            // the low/high 16-bit halves; their sums cannot carry across the boundary.
            "        uint packedSupport = (currentStructureSupported ? 1u : 0u)",
            "                | ((commonStructureSupported ? 1u : 0u) << 16u);",
            "        localDeltaTotals[lane] = uvec4(rawDelta,",
            "                rawDelta >= RAW_MODERATE_DELTA ? 1u : 0u,",
            "                structureChanged ? 1u : 0u,",
            "                packedSupport);",
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
            "        uint currentSupport = delta.w & 0xffffu;",
            "        uint commonSupport = delta.w >> 16u;",
            "        if (currentSupport != 0u)",
            "            atomicAdd(currentStructuralSupportCount, currentSupport);",
            "        if (commonSupport != 0u)",
            "            atomicAdd(commonStructuralSupportCount, commonSupport);",
            "    }",
            "}"
    );

    /**
     * Resolves the fused per-tile evidence into an authenticated, fail-closed 32-byte record.
     * The renderer passes the record to the native worker only after the enclosing GL fence.
     */
    static String createNearIdenticalResolve(int tensorWidth, int tensorHeight) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("Model input dimensions must be positive");
        }
        return HEADER + STATS + lines(
                "layout(local_size_x = 64) in;",
                "layout(rgba32ui, binding = 1) uniform readonly highp uimage2D uCurrentLuma;",
                "layout(std430, binding = 1) buffer NearIdenticalDecision {",
                "    uint decisionWords[];",
                "};",
                "uniform int uNearIdenticalCandidate;",
                "uniform uint uDecisionWordOffset;",
                "uniform uvec2 uDecisionToken;",
                "uniform uvec2 uCurrentFrameSequence;",
                "uniform uvec2 uCurrentCapturedAtNs;",
                "const uint TENSOR_WIDTH = " + tensorWidth + "u;",
                "const uint TENSOR_HEIGHT = " + tensorHeight + "u;",
                "const uint TILE_SIZE = 16u;",
                "const uint GRID_WIDTH = (TENSOR_WIDTH + TILE_SIZE - 1u) / TILE_SIZE;",
                "const uint GRID_HEIGHT = (TENSOR_HEIGHT + TILE_SIZE - 1u) / TILE_SIZE;",
                "const uint GRID_TILE_COUNT = GRID_WIDTH * GRID_HEIGHT;",
                "const uint EXPECTED_TEXELS = TENSOR_WIDTH * TENSOR_HEIGHT;",
                "const uint DECISION_REUSE = 0u;",
                "const uint DECISION_INFER = 1u;",
                "const uint DECISION_COOKIE = 0xd1ec15a5u;",
                "const uint TOKEN_LOW_COOKIE = 0xa3756c91u;",
                "const uint TOKEN_HIGH_COOKIE = 0x5c8a936eu;",
                "const uint RECORD_MAGIC = 0x504f5250u;",
                "const uint REASON_REUSE = "
                        + ClientSbsNearIdenticalPolicy.REASON_REUSE + "u;",
                "const uint REASON_NOT_CANDIDATE = "
                        + ClientSbsNearIdenticalPolicy.REASON_NOT_CANDIDATE + "u;",
                "const uint REASON_OWNER_INVALID = "
                        + ClientSbsNearIdenticalPolicy.REASON_OWNER_INVALID + "u;",
                "const uint REASON_OWNER_FRAME_GAP = "
                        + ClientSbsNearIdenticalPolicy.REASON_OWNER_FRAME_GAP + "u;",
                "const uint REASON_OWNER_AGE = "
                        + ClientSbsNearIdenticalPolicy.REASON_OWNER_AGE + "u;",
                "const uint REASON_CONTENT_MEDIUM = "
                        + ClientSbsNearIdenticalPolicy.REASON_CONTENT_MEDIUM + "u;",
                "const uint REASON_CONTENT_STRONG = "
                        + ClientSbsNearIdenticalPolicy.REASON_CONTENT_STRONG + "u;",
                "const uint REASON_CONTENT_LOCAL = "
                        + ClientSbsNearIdenticalPolicy.REASON_CONTENT_LOCAL + "u;",
                "const uint REASON_EVIDENCE_INVALID = "
                        + ClientSbsNearIdenticalPolicy.REASON_EVIDENCE_INVALID + "u;",
                // (admitted, medium, strong, invalid) totals across each lane's tile stripe.
                "shared uvec4 evidenceTotals[64];",
                // Keep malformed evidence distinct from a valid local strong-change veto.
                "shared uvec2 rejectionTotals[64];",
                "bool lessThan64(uvec2 left, uvec2 right) {",
                "    return left.y < right.y || (left.y == right.y && left.x < right.x);",
                "}",
                "uvec2 subtract64(uvec2 larger, uvec2 smaller) {",
                "    uint borrow = larger.x < smaller.x ? 1u : 0u;",
                "    return uvec2(larger.x - smaller.x, larger.y - smaller.y - borrow);",
                "}",
                "uint ownerRejectionReason() {",
                "    if (nearOwnerValid == 0u",
                "            || !lessThan64(nearOwnerFrameSequence, uCurrentFrameSequence)",
                "            || lessThan64(uCurrentCapturedAtNs, nearOwnerCapturedAtNs))",
                "        return REASON_OWNER_INVALID;",
                "    uvec2 frameDelta = subtract64(uCurrentFrameSequence,",
                "            nearOwnerFrameSequence);",
                "    if (frameDelta.y != 0u || frameDelta.x > "
                        + ClientSbsNearIdenticalPolicy.MAX_INFER_OWNER_FRAME_GAP + "u)",
                "        return REASON_OWNER_FRAME_GAP;",
                "    uvec2 age = subtract64(uCurrentCapturedAtNs, nearOwnerCapturedAtNs);",
                "    if (age.y != 0u || age.x >= "
                        + ClientSbsNearIdenticalPolicy.MAX_INFER_OWNER_AGE_NS + "u)",
                "        return REASON_OWNER_AGE;",
                "    return REASON_REUSE;",
                "}",
                "void main() {",
                "    uint lane = gl_LocalInvocationID.x;",
                // Invalidate the slot before doing any work. The final magic store publishes all
                // authenticated fields only after their buffer writes are made visible.
                "    if (lane == 0u) decisionWords[uDecisionWordOffset + 6u] = 0u;",
                "    memoryBarrierBuffer();",
                "    barrier();",
                "    uvec4 totals = uvec4(0u);",
                "    uvec2 rejections = uvec2(0u);",
                "    if (uNearIdenticalCandidate != 0) {",
                "        for (uint tileIndex = lane; tileIndex < GRID_TILE_COUNT; tileIndex += 64u) {",
                "            uvec2 tile = uvec2(tileIndex % GRID_WIDTH, tileIndex / GRID_WIDTH);",
                "            uvec4 packed = imageLoad(uCurrentLuma, ivec2(tile));",
                "            uint admitted = packed.y;",
                "            uint medium = packed.z;",
                "            uint strong = packed.w & 0xffffu;",
                "            uint nonfinite = packed.w >> 16u;",
                "            uint tileWidth = min(TILE_SIZE, TENSOR_WIDTH - tile.x * TILE_SIZE);",
                "            uint tileHeight = min(TILE_SIZE, TENSOR_HEIGHT - tile.y * TILE_SIZE);",
                "            uint expectedAdmitted = tileWidth * tileHeight;",
                "            bool malformed = admitted != expectedAdmitted",
                "                    || medium > admitted || strong > medium || nonfinite != 0u;",
                "            bool localStrongVeto = admitted >= 64u",
                "                    && strong * 4u > admitted * 3u;",
                "            totals += uvec4(admitted, medium, strong, nonfinite);",
                "            rejections += uvec2(malformed ? 1u : 0u,",
                "                    localStrongVeto ? 1u : 0u);",
                "        }",
                "    } else {",
                // A non-candidate dispatch authenticates INFER but consumes no evidence and the
                // pack pass made no previous-input reads.
                "        rejections.x = 1u;",
                "    }",
                "    evidenceTotals[lane] = totals;",
                "    rejectionTotals[lane] = rejections;",
                "    barrier();",
                "    for (uint stride = 32u; stride != 0u; stride >>= 1u) {",
                "        if (lane < stride) {",
                "            evidenceTotals[lane] += evidenceTotals[lane + stride];",
                "            rejectionTotals[lane] += rejectionTotals[lane + stride];",
                "        }",
                "        barrier();",
                "    }",
                "    if (lane == 0u) {",
                "        uvec4 evidence = evidenceTotals[0];",
                "        uvec2 rejected = rejectionTotals[0];",
                "        bool complete = evidence.x == EXPECTED_TEXELS",
                "                && evidence.w == 0u && rejected.x == 0u;",
                // Inclusive host bounds: medium <= 10%, strong <= 2.5%.
                "        bool mediumQuiet = evidence.y * 10u <= evidence.x;",
                "        bool strongQuiet = evidence.z * 40u <= evidence.x;",
                "        uint reason = REASON_NOT_CANDIDATE;",
                "        if (uNearIdenticalCandidate != 0) {",
                "            reason = ownerRejectionReason();",
                "            if (reason == REASON_REUSE) {",
                "                if (!complete) reason = REASON_EVIDENCE_INVALID;",
                "                else if (rejected.y != 0u) reason = REASON_CONTENT_LOCAL;",
                "                else if (!strongQuiet) reason = REASON_CONTENT_STRONG;",
                "                else if (!mediumQuiet) reason = REASON_CONTENT_MEDIUM;",
                "            }",
                "        }",
                "        uint decision = reason == REASON_REUSE",
                "                ? DECISION_REUSE : DECISION_INFER;",
                "        decisionWords[uDecisionWordOffset] = decision;",
                "        decisionWords[uDecisionWordOffset + 1u] = decision ^ DECISION_COOKIE;",
                "        decisionWords[uDecisionWordOffset + 2u] = uDecisionToken.x;",
                "        decisionWords[uDecisionWordOffset + 3u] = uDecisionToken.y;",
                "        decisionWords[uDecisionWordOffset + 4u] =",
                "                uDecisionToken.x ^ TOKEN_LOW_COOKIE;",
                "        decisionWords[uDecisionWordOffset + 5u] =",
                "                uDecisionToken.y ^ TOKEN_HIGH_COOKIE;",
                "        decisionWords[uDecisionWordOffset + 7u] = reason;",
                "        memoryBarrierBuffer();",
                "        decisionWords[uDecisionWordOffset + 6u] = RECORD_MAGIC;",
                "    }",
                "}"
        );
    }

    static final String RESOLVE = HEADER + STATS + OUTPUT + FRACTION_AT_LEAST + lines(
            "layout(local_size_x = 1) in;",
            "uniform int uHistoryValid;",
            "uniform uint uOutputWordOffset;",
            "void main() {",
            "    uint historyBlockCount = previousBlockCount & HISTORY_BLOCK_COUNT_MASK;",
            "    bool historyStructureSupported =",
            "            (previousBlockCount & HISTORY_STRUCTURE_SUPPORTED) != 0u;",
            "    bool historyGapPending =",
            "            (previousBlockCount & HISTORY_GAP_PENDING) != 0u",
            "            && historyStructureSupported;",
            "    bool lowStructureScene =",
            "            (previousBlockCount & HISTORY_GAP_PENDING) != 0u",
            "            && !historyStructureSupported;",
            "    bool comparable = uHistoryValid != 0 && detectorHistoryValid != 0u",
            "            && currentBlockCount != 0u",
            "            && currentBlockCount == historyBlockCount;",
            "    uint l1 = 0u;",
            "    if (comparable) {",
            "        for (uint bin = 0u; bin < 16u; ++bin) {",
            "            uint current = currentHistogram[bin];",
            "            uint previous = previousHistogram[bin];",
            "            l1 += current >= previous ? current - previous : previous - current;",
            "        }",
            "    }",
            "    uint blocks = currentBlockCount;",
            "    bool broadRawChange = fractionAtLeast(rawModerateCount, blocks, 55u);",
            "    bool enoughRawEnergy = blocks != 0u && rawDeltaSum >= blocks * 34u;",
            "    bool broadStructuralChange = fractionAtLeast(",
            "            structuralChangeCount, blocks, 15u);",
            // Five percent is the existing client quiet-band boundary and remains below the
            // measured preserved-exposure support floor. Requiring broad current support prevents
            // a few reliable sites from deciding a frame-level classification.
            "    bool sufficientCurrentSupport = fractionAtLeast(",
            "            currentStructuralSupportCount, blocks, 5u);",
            "    bool sufficientCommonSupport = fractionAtLeast(",
            "            commonStructuralSupportCount, blocks, 5u);",
            // Reserve a deliberately quiet band for the exposure veto. The 5-15% reversal band is
            // neither qualified appearance nor exposure-like, so standalone depth authority
            // remains available for ambiguous editorial cuts.
            "    bool quietStructuralChange = !fractionAtLeast(",
            "            structuralChangeCount, blocks, 5u);",
            // Histogram L1 remains diagnostic, but it is deliberately not authority: exposure can
            // move a histogram while same-histogram editorial cuts are real. Local structure plus
            // broad raw change proposes the cut, and depth geometry still owns shot relatching.
            "    bool hardCut = comparable && broadRawChange && enoughRawEnergy",
            "            && broadStructuralChange;",
            "    bool broadAppearanceReplacement = broadRawChange && enoughRawEnergy;",
            // If reliable history first loses structure, defer one accepted update and let COMMIT
            // retain history. Bounding the deferral is essential: a real title/fog/sky/flat shot
            // must expose A-vs-current depth geometry on its second accepted update rather than
            // remaining classified as brightness forever. The first supported frame after a
            // one-update gap is still suppressed when it closely matches the retained frame (the
            // return edge of A->flat->A). A supported frame with broad raw replacement but no
            // common structure remains ambiguous, leaving geometry authority for A->flat->B.
            "    bool structurelessInterval = historyStructureSupported",
            "            && !historyGapPending && !sufficientCurrentSupport;",
            "    histogramL1 = l1;",
            "    bool preservedExposure = broadAppearanceReplacement",
            "            && sufficientCommonSupport;",
            // Only a strict endpoint match may veto the supported edge after a one-frame hold.
            // "Not broadly different" is far too wide: a quiet-color A->flat->B edit can carry
            // authoritative depth geometry without crossing the appearance proposal thresholds.
            "    bool sameSceneEndpoint = blocks != 0u && rawDeltaSum <= blocks * 2u",
            "            && !fractionAtLeast(rawModerateCount, blocks, 1u);",
            "    bool bridgedReturn = historyGapPending && sufficientCurrentSupport",
            "            && sameSceneEndpoint;",
            "    bool persistentLowStart = comparable && historyGapPending",
            "            && !sufficientCurrentSupport;",
            "    bool supportedReturn = comparable && lowStructureScene",
            "            && sufficientCurrentSupport;",
            "    bool exposureLike = comparable && quietStructuralChange",
            "            && (structurelessInterval || preservedExposure || bridgedReturn);",
            "    uint evidence = hardCut ? SCENE_EVIDENCE_APPEARANCE : 0u;",
            "    if (exposureLike) evidence |= SCENE_EVIDENCE_EXPOSURE_LIKE;",
            "    if (persistentLowStart)",
            "        evidence |= SCENE_EVIDENCE_PERSISTENT_LOW_START;",
            "    if (supportedReturn) evidence |= SCENE_EVIDENCE_SUPPORTED_RETURN;",
            "    uint diagnosticFlags =",
            "            (comparable ? " + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_COMPARABLE
                    + "u : 0u)",
            "            | (broadRawChange ? "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_BROAD_RAW_CHANGE + "u : 0u)",
            "            | (enoughRawEnergy ? "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_ENOUGH_RAW_ENERGY + "u : 0u)",
            "            | (broadStructuralChange ? "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_BROAD_STRUCTURAL_CHANGE
                    + "u : 0u)",
            "            | (sufficientCurrentSupport ? "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_CURRENT_STRUCTURE_SUPPORTED
                    + "u : 0u)",
            "            | (sufficientCommonSupport ? "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_COMMON_STRUCTURE_SUPPORTED
                    + "u : 0u)",
            "            | (quietStructuralChange ? "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_QUIET_STRUCTURAL_CHANGE
                    + "u : 0u)",
            "            | (exposureLike ? "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_EXPOSURE_LIKE + "u : 0u)",
            "            | (historyStructureSupported ? "
                    + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_PREVIOUS_STRUCTURE_SUPPORTED
                    + "u : 0u);",
            "    sceneCutWords[uOutputWordOffset + SCENE_CUT_RECORD_BLOCK_COUNT] = blocks;",
            "    sceneCutWords[uOutputWordOffset + SCENE_CUT_RECORD_RAW_MODERATE_COUNT] =",
            "            rawModerateCount;",
            "    sceneCutWords[uOutputWordOffset + SCENE_CUT_RECORD_RAW_DELTA_SUM] = rawDeltaSum;",
            "    sceneCutWords[uOutputWordOffset + SCENE_CUT_RECORD_STRUCTURAL_CHANGE_COUNT] =",
            "            structuralChangeCount;",
            "    sceneCutWords[uOutputWordOffset + SCENE_CUT_RECORD_CURRENT_SUPPORT_COUNT] =",
            "            currentStructuralSupportCount;",
            "    sceneCutWords[uOutputWordOffset + SCENE_CUT_RECORD_COMMON_SUPPORT_COUNT] =",
            "            commonStructuralSupportCount;",
            "    sceneCutWords[uOutputWordOffset + SCENE_CUT_RECORD_DIAGNOSTIC_FLAGS] =",
            "            diagnosticFlags;",
            // Publish the classification last within the one-invocation record writer.
            "    sceneCutWords[uOutputWordOffset + SCENE_CUT_RECORD_EVIDENCE] = evidence;",
            "}"
    );

    /** Commits exactly the histories authorized by the depth resolver's GPU-owned state bit. */
    static String createCommit(int tensorWidth, int tensorHeight) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("Model input dimensions must be positive");
        }
        return HEADER + STATS + FRACTION_AT_LEAST + lines(
                "layout(local_size_x = 16, local_size_y = 16) in;",
                "layout(rgba32ui, binding = 0) uniform readonly highp uimage2D uPreviousLuma;",
                "layout(rgba32ui, binding = 1) uniform writeonly highp uimage2D uCurrentLuma;",
                "layout(std430, binding = 1) readonly buffer ProcessorState {",
                "    uint processorStateWords[];",
                "};",
                "layout(std430, binding = 2) readonly buffer CurrentInputTensor {",
                "    float currentTensorValues[];",
                "};",
                "layout(std430, binding = 3) writeonly buffer PreviousInputTensor {",
                "    float previousTensorValues[];",
                "};",
                "uniform ivec2 uBlockGrid;",
                "uniform uvec2 uCurrentFrameSequence;",
                "uniform uvec2 uCurrentCapturedAtNs;",
                "const ivec2 TENSOR_SIZE = ivec2(" + tensorWidth + ", " + tensorHeight + ");",
                // stateFlags.z is word 18 in the processor's ABI-stable state prefix.
                "const uint PROCESSOR_FRAME_STATE_WORD = 18u;",
                "const uint FRAME_STATE_HISTORY_ADVANCES = "
                        + ClientSbsShotCutPolicy.FRAME_STATE_HISTORY_ADVANCES + "u;",
                "const uint FRAME_STATE_STRUCTURELESS_GAP = "
                        + ClientSbsShotCutPolicy.FRAME_STATE_STRUCTURELESS_GAP + "u;",
                "const uint FRAME_STATE_CURRENT_DEPTH_VALID = "
                        + ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_DEPTH_VALID + "u;",
                "const uint FRAME_STATE_CURRENT_V2_VALID = "
                        + ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_V2_VALID + "u;",
                "void main() {",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    uint processorFrameState = processorStateWords[PROCESSOR_FRAME_STATE_WORD];",
                "    bool historyAdvances = (processorFrameState",
                "            & FRAME_STATE_HISTORY_ADVANCES) != 0u;",
                "    bool structurelessGap = (processorFrameState",
                "            & FRAME_STATE_STRUCTURELESS_GAP) != 0u;",
                "    bool currentDepthValid = (processorFrameState",
                "            & FRAME_STATE_CURRENT_DEPTH_VALID) != 0u;",
                "    bool currentV2Valid = (processorFrameState",
                "            & FRAME_STATE_CURRENT_V2_VALID) != 0u;",
                "    if (!historyAdvances) {",
                // Java still swaps ping-pong indices after the dispatch. Copying the old image
                // into the pending texture freezes the detector without needing CPU knowledge.
                "        if (detectorHistoryValid != 0u && all(lessThan(point, uBlockGrid)))",
                "            imageStore(uCurrentLuma, point, imageLoad(uPreviousLuma, point));",
                "        if (all(equal(point, ivec2(0)))) {",
                // A first structureless update must persist state 2 even though the reliable tuple
                // itself is held. A geometry-confirmation hold instead clears any stale gap state;
                // invalid depth changes neither detector metadata nor the tuple.
                "            if (currentDepthValid) {",
                "                uint retained = previousBlockCount",
                "                        & (HISTORY_BLOCK_COUNT_MASK",
                "                        | HISTORY_STRUCTURE_SUPPORTED);",
                "                previousBlockCount = structurelessGap",
                "                        ? (retained | HISTORY_STRUCTURE_SUPPORTED",
                "                                | HISTORY_GAP_PENDING)",
                "                        : retained;",
                "            }",
                "            nearOwnerValid = 0u;",
                "        }",
                "        return;",
                "    }",
                "    if (all(lessThan(point, TENSOR_SIZE))) {",
                "        uint tensorIndex = uint(point.y * TENSOR_SIZE.x + point.x) * 3u;",
                "        previousTensorValues[tensorIndex] = currentTensorValues[tensorIndex];",
                "        previousTensorValues[tensorIndex + 1u] = currentTensorValues[tensorIndex + 1u];",
                "        previousTensorValues[tensorIndex + 2u] = currentTensorValues[tensorIndex + 2u];",
                "    }",
                "    bool currentStructureSupported = fractionAtLeast(",
                "            currentStructuralSupportCount, currentBlockCount, 5u);",
                "    if (point.y == 0 && point.x < 16)",
                "        previousHistogram[point.x] = currentHistogram[point.x];",
                "    if (all(equal(point, ivec2(0)))) {",
                "        bool historyStructureSupported =",
                "                (previousBlockCount & HISTORY_STRUCTURE_SUPPORTED) != 0u;",
                "        bool historyGapPending =",
                "                (previousBlockCount & HISTORY_GAP_PENDING) != 0u",
                "                && historyStructureSupported;",
                "        bool lowStructureScene =",
                "                (previousBlockCount & HISTORY_GAP_PENDING) != 0u",
                "                && !historyStructureSupported;",
                "        bool persistentLowScene = !currentStructureSupported",
                "                && (historyGapPending || lowStructureScene);",
                "        previousBlockCount = currentBlockCount",
                "                | (currentStructureSupported",
                "                ? HISTORY_STRUCTURE_SUPPORTED",
                "                : (persistentLowScene ? HISTORY_GAP_PENDING : 0u));",
                "        detectorHistoryValid = 1u;",
                "        nearOwnerFrameSequence = uCurrentFrameSequence;",
                "        nearOwnerCapturedAtNs = uCurrentCapturedAtNs;",
                "        memoryBarrierBuffer();",
                // A finite collapsed field is allowed to advance private color/cut history, but it
                // cannot own cached geometry. Force another real inference until V2 is usable.
                "        nearOwnerValid = currentV2Valid ? 1u : 0u;",
                "    }",
                "}"
        );
    }
}
