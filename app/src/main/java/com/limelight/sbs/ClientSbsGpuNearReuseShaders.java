package com.limelight.sbs;

/** Compute shaders used by {@link ClientSbsGpuNearReuseClassifier}. */
final class ClientSbsGpuNearReuseShaders {
    private ClientSbsGpuNearReuseShaders() {
    }

    private static String lines(String... source) {
        return String.join("\n", source) + "\n";
    }

    /** Joins lines for use as one element of {@link #lines}; adds no trailing newline. */
    private static String fragment(String... source) {
        return String.join("\n", source);
    }

    private static final String LESS_THAN_64 = fragment(
            "bool lessThan64(uvec2 left, uvec2 right) {",
            "    return left.y < right.y || (left.y == right.y && left.x < right.x);",
            "}"
    );

    /** Opening terms of the owner-eligibility test; each caller appends its own final term. */
    private static final String NEAR_OWNER_INVALID_CONDITION = fragment(
            "    if (nearOwnerValid == 0u",
            "            || all(equal(nearOwnerFrameSequence, uvec2(0u)))",
            "            || nearOwnerFrameSequence.y >= 0x80000000u",
            "            || uCurrentFrameSequence.y >= 0x80000000u",
            "            || !lessThan64(nearOwnerFrameSequence, uCurrentFrameSequence)"
    );

    private static final String TILE_WORKGROUP_LAYOUT = "layout(local_size_x = "
            + ClientSbsGpuNearReuseClassifier.TILE_SIZE + ", local_size_y = "
            + ClientSbsGpuNearReuseClassifier.TILE_SIZE + ") in;";

    /** Formats one 32-bit word exactly as the lower-case GLSL literals it replaces. */
    private static String hexUintLiteral(int word) {
        return String.format(java.util.Locale.US, "0x%08xu", word);
    }

    private static final String HEADER = lines(
            "#version 310 es",
            "precision highp float;",
            "precision highp int;"
    );

    /*
     * Identity of the real inference whose model input is the comparison reference. Keep this
     * std430 declaration in step with ClientSbsGpuNearReuseClassifier.OWNER_BYTES: two uvec2 at
     * bytes 0 and 8, one uint at 16, and the block rounded up to its 8-byte alignment (24).
     */
    private static String owner(String access) {
        return lines(
                "layout(std430, binding = 0) " + access + "buffer NearReuseOwner {",
                "    uvec2 nearOwnerFrameSequence;",
                "    uvec2 nearOwnerCapturedAtNs;",
                "    uint nearOwnerValid;",
                "};"
        );
    }

    private static final String OWNER_READ = owner("readonly ");
    private static final String OWNER_WRITE = owner("");

    /**
     * Builds the stream-fixed pass that packs LiteRT's Float32 NHWC tensor and reduces the same
     * model-input texels into per-tile near-identical evidence. Texture row zero is GL's bottom
     * row, so the tensor destination row is flipped to retain the model's top-first layout.
     */
    static String createPackAndClassify(int tensorWidth, int tensorHeight) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("Model input dimensions must be positive");
        }
        return HEADER + OWNER_READ + lines(
            TILE_WORKGROUP_LAYOUT,
            "layout(rgba32ui, binding = 0) uniform writeonly highp uimage2D uTileEvidence;",
            "uniform highp sampler2D uCurrentColor;",
            "uniform int uNearIdenticalCandidate;",
            "uniform int uLazyInputPacking;",
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
            // (admitted, medium, strong, non-finite) evidence for one 16x16 tile.
            "shared uvec4 nearIdenticalTotals[256];",
            LESS_THAN_64,
            "bool nearOwnerEligible() {",
            NEAR_OWNER_INVALID_CONDITION,
            "            || lessThan64(uCurrentCapturedAtNs, nearOwnerCapturedAtNs)) return false;",
            "    return true;",
            "}",
            "void main() {",
            "    uint lane = gl_LocalInvocationIndex;",
            "    uvec2 point = gl_GlobalInvocationID.xy;",
            "    nearIdenticalTotals[lane] = uvec4(0u);",
            "    bool inBounds = all(lessThan(point, INPUT_SIZE));",
            "    uint firstValue = 0u;",
            "    vec3 tensorRgb = vec3(0.0);",
            "    if (inBounds) {",
            "        vec3 sourceRgb = texelFetch(uCurrentColor, ivec2(point), 0).rgb;",
            // Clamp only, and flip GL's source row into the model's top-first NHWC row.
            "        tensorRgb = clamp(sourceRgb, vec3(0.0), vec3(1.0));",
            "        uint tensorY = TENSOR_HEIGHT - 1u - point.y;",
            "        firstValue = (tensorY * TENSOR_WIDTH + point.x) * 3u;",
            "        if (uLazyInputPacking == 0) {",
            "            tensorValues[firstValue] = tensorRgb.r;",
            "            tensorValues[firstValue + 1u] = tensorRgb.g;",
            "            tensorValues[firstValue + 2u] = tensorRgb.b;",
            "        }",
            "    }",
            // The candidate branch is the only place that reads actual-inference history. A
            // disabled candidate therefore has zero evidence and no history dependency.
            "    bool effectiveNearIdenticalCandidate = uNearIdenticalCandidate != 0",
            "            && nearOwnerEligible();",
            "    if (inBounds && effectiveNearIdenticalCandidate) {",
            // Compare the same local values used by eager packing. Lazy arbitration therefore
            // also avoids reading a stale tensor slot or fetching current RGB a second time.
            "        vec3 currentRgb = tensorRgb;",
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
            // Fixed tree reduction avoids contended shared-memory atomics per model pixel.
            "    for (uint stride = 128u; stride != 0u; stride >>= 1u) {",
            "        if (lane < stride) {",
            "            nearIdenticalTotals[lane] += nearIdenticalTotals[lane + stride];",
            "        }",
            "        barrier();",
            "    }",
            "    if (lane == 0u) {",
            "        imageStore(uTileEvidence, ivec2(gl_WorkGroupID.xy),",
            "                nearIdenticalTotals[0]);",
            "    }",
            "}"
        );
    }

    /**
     * Resolves the per-tile evidence into an authenticated, fail-closed 32-byte record.
     * The renderer passes the record to the native worker only after the enclosing GL fence.
     */
    static String createNearIdenticalResolve(int tensorWidth, int tensorHeight) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("Model input dimensions must be positive");
        }
        return HEADER + OWNER_READ + lines(
                "layout(local_size_x = 64) in;",
                "layout(rgba32ui, binding = 0) uniform readonly highp uimage2D uTileEvidence;",
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
                "const uint TILE_SIZE = " + ClientSbsNearIdenticalPolicy.TILE_SIZE + "u;",
                "const uint GRID_WIDTH = (TENSOR_WIDTH + TILE_SIZE - 1u) / TILE_SIZE;",
                "const uint GRID_HEIGHT = (TENSOR_HEIGHT + TILE_SIZE - 1u) / TILE_SIZE;",
                "const uint GRID_TILE_COUNT = GRID_WIDTH * GRID_HEIGHT;",
                "const uint EXPECTED_TEXELS = TENSOR_WIDTH * TENSOR_HEIGHT;",
                "const uint DECISION_REUSE = "
                        + ClientSbsNearIdenticalPolicy.DECISION_REUSE_TAG + "u;",
                "const uint DECISION_INFER = "
                        + ClientSbsNearIdenticalPolicy.DECISION_INFER_TAG + "u;",
                "const uint DECISION_COOKIE = "
                        + hexUintLiteral(ClientSbsNearIdenticalPolicy.DECISION_COOKIE) + ";",
                "const uint TOKEN_LOW_COOKIE = "
                        + hexUintLiteral(ClientSbsNearIdenticalPolicy.TOKEN_LOW_COOKIE) + ";",
                "const uint TOKEN_HIGH_COOKIE = "
                        + hexUintLiteral(ClientSbsNearIdenticalPolicy.TOKEN_HIGH_COOKIE) + ";",
                "const uint RECORD_MAGIC = "
                        + hexUintLiteral(ClientSbsNearIdenticalPolicy.PROPOSAL_MAGIC) + ";",
                "const uint REASON_REUSE = "
                        + ClientSbsNearIdenticalPolicy.REASON_REUSE + "u;",
                "const uint REASON_NOT_CANDIDATE = "
                        + ClientSbsNearIdenticalPolicy.REASON_NOT_CANDIDATE + "u;",
                "const uint REASON_OWNER_INVALID = "
                        + ClientSbsNearIdenticalPolicy.REASON_OWNER_INVALID + "u;",
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
                LESS_THAN_64,
                "uint ownerRejectionReason() {",
                NEAR_OWNER_INVALID_CONDITION,
                "            || lessThan64(uCurrentCapturedAtNs, nearOwnerCapturedAtNs))",
                "        return REASON_OWNER_INVALID;",
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
                "            uvec4 tileEvidence = imageLoad(uTileEvidence, ivec2(tile));",
                "            uint admitted = tileEvidence.x;",
                "            uint medium = tileEvidence.y;",
                "            uint strong = tileEvidence.z;",
                "            uint nonfinite = tileEvidence.w;",
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

    /**
     * Makes an accepted real inference the comparison reference. A raw field that is not valid
     * for V2 commits no model input and invalidates the reuse owner instead.
     */
    static String createCommit(int tensorWidth, int tensorHeight) {
        if (tensorWidth <= 0 || tensorHeight <= 0) {
            throw new IllegalArgumentException("Model input dimensions must be positive");
        }
        return HEADER + OWNER_WRITE + lines(
                TILE_WORKGROUP_LAYOUT,
                "layout(std430, binding = 1) readonly buffer ProcessorState {",
                "    uint processorStateWords[];",
                "};",
                "layout(std430, binding = 3) writeonly buffer PreviousInputTensor {",
                "    float previousTensorValues[];",
                "};",
                "uniform highp sampler2D uCurrentColor;",
                "uniform uvec2 uCurrentFrameSequence;",
                "uniform uvec2 uCurrentCapturedAtNs;",
                "const ivec2 TENSOR_SIZE = ivec2(" + tensorWidth + ", " + tensorHeight + ");",
                "const uint PROCESSOR_FRAME_STATE_WORD = "
                        + ClientSbsGpuDepthProcessor.FRAME_STATE_WORD_INDEX + "u;",
                "const uint FRAME_STATE_V2_VALID_FIELD = "
                        + (ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_DEPTH_VALID
                        | ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_V2_VALID) + "u;",
                "void main() {",
                "    ivec2 point = ivec2(gl_GlobalInvocationID.xy);",
                "    bool originInvocation = all(equal(point, ivec2(0)));",
                "    bool v2ValidField = (processorStateWords[PROCESSOR_FRAME_STATE_WORD]",
                "            & FRAME_STATE_V2_VALID_FIELD) == FRAME_STATE_V2_VALID_FIELD;",
                "    if (!v2ValidField) {",
                // An incomplete or collapsed field cannot own cached geometry. Leave the retained
                // model input untouched and force real inference until a V2-valid field arrives.
                "        if (originInvocation) nearOwnerValid = 0u;",
                "        return;",
                "    }",
                "    if (all(lessThan(point, TENSOR_SIZE))) {",
                "        uint tensorIndex = uint(point.y * TENSOR_SIZE.x + point.x) * 3u;",
                // The current texture is retained through commit and supplies the same
                // top-first RGB history with eager or native deferred tensor packing.
                "        ivec2 texturePoint = ivec2(point.x, TENSOR_SIZE.y - 1 - point.y);",
                "        vec3 rgb = clamp(texelFetch(uCurrentColor, texturePoint, 0).rgb,",
                "                vec3(0.0), vec3(1.0));",
                "        previousTensorValues[tensorIndex] = rgb.r;",
                "        previousTensorValues[tensorIndex + 1u] = rgb.g;",
                "        previousTensorValues[tensorIndex + 2u] = rgb.b;",
                "    }",
                "    if (originInvocation) {",
                "        nearOwnerFrameSequence = uCurrentFrameSequence;",
                "        nearOwnerCapturedAtNs = uCurrentCapturedAtNs;",
                "        memoryBarrierBuffer();",
                "        nearOwnerValid = 1u;",
                "    }",
                "}"
        );
    }
}
