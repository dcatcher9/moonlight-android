package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsGpuNearReuseShadersTest {
    @Test
    public void fusedPassPacksAndReducesOnlyNearIdenticalTileEvidence() {
        String shader = ClientSbsGpuNearReuseShaders.createPackAndClassify(350, 196);
        assertTrue(shader.contains("layout(local_size_x = 16, local_size_y = 16)"));
        assertTrue(shader.contains("binding = 2) buffer InputTensor"));
        assertTrue(shader.contains("float tensorValues[]"));
        assertTrue(shader.contains(
                "layout(rgba32ui, binding = 0) uniform writeonly highp uimage2D uTileEvidence"));
        assertTrue(shader.contains("imageStore(uTileEvidence, ivec2(gl_WorkGroupID.xy),"));
        // Packing and Near share the same fetched current RGB values; alpha is not read.
        assertEquals(1, occurrences(shader, "texelFetch(uCurrentColor"));
        assertTrue(shader.contains("texelFetch(uCurrentColor, ivec2(point), 0).rgb"));
        for (String removed : new String[] {
                "blockTotals", "blockOrdinalValues", "ordinalMedian", "sourceSample.a",
                "currentHistogram", "currentBlockCount", "atomicAdd", "0.2126"}) {
            assertFalse(removed, shader.contains(removed));
        }
    }

    @Test
    public void fusedPassMeasuresNearIdenticalEvidenceAgainstCommittedTensorOnly() {
        String shader = ClientSbsGpuNearReuseShaders.createPackAndClassify(350, 196);
        assertTrue(shader.contains("binding = 3) readonly buffer PreviousInputTensor"));
        assertTrue(shader.contains("binding = 0) readonly buffer NearReuseOwner"));
        assertTrue(shader.contains("uniform int uNearIdenticalCandidate"));
        assertTrue(shader.contains("uniform uvec2 uCurrentFrameSequence"));
        assertTrue(shader.contains("uniform uvec2 uCurrentCapturedAtNs"));
        assertTrue(shader.contains("nearOwnerValid == 0u"));
        assertTrue(shader.contains("!lessThan64(nearOwnerFrameSequence, uCurrentFrameSequence)"));
        assertTrue(shader.contains("lessThan64(uCurrentCapturedAtNs, nearOwnerCapturedAtNs)"));
        assertTrue(shader.contains("all(equal(nearOwnerFrameSequence, uvec2(0u)))"));
        assertFalse(shader.contains("subtract64("));
        assertTrue(shader.contains("if (inBounds && effectiveNearIdenticalCandidate)"));
        assertTrue(shader.contains("vec3 currentRgb = tensorRgb"));
        assertTrue(shader.contains("previousTensorValues[firstValue]"));
        assertTrue(shader.contains("const float MEDIUM_DELTA = 0.015625"));
        assertTrue(shader.contains("const float STRONG_DELTA = 0.2"));
        assertTrue(shader.contains("maxDelta >= MEDIUM_DELTA"));
        assertTrue(shader.contains("maxDelta >= STRONG_DELTA"));
        assertTrue(shader.contains("shared uvec4 nearIdenticalTotals[256]"));
        assertTrue(shader.contains("nearIdenticalTotals[lane] = uvec4(0u)"));
        assertTrue(shader.contains(
                "nearIdenticalTotals[lane] += nearIdenticalTotals[lane + stride]"));

        int modelWrite = shader.indexOf("tensorValues[firstValue + 2u] = tensorRgb.b");
        int candidateBranch = shader.indexOf(
                "if (inBounds && effectiveNearIdenticalCandidate)");
        int currentValues = shader.indexOf("vec3 currentRgb = tensorRgb", candidateBranch);
        int previousRead = shader.indexOf("previousTensorValues[firstValue]", candidateBranch);
        int branchEnd = shader.indexOf("        }", previousRead);
        assertTrue(modelWrite >= 0 && candidateBranch > modelWrite
                && currentValues > candidateBranch && previousRead > currentValues
                && branchEnd > previousRead);
        assertFalse(shader.substring(0, candidateBranch).contains(
                "previousTensorValues[firstValue]"));
    }

    @Test
    public void nearIdenticalResolveValidatesExactHostBoundsAndPublishesAuthenticatedRecord() {
        String shader = ClientSbsGpuNearReuseShaders.createNearIdenticalResolve(350, 196);
        assertTrue(shader.contains("layout(local_size_x = 64)"));
        assertTrue(shader.contains(
                "layout(rgba32ui, binding = 0) uniform readonly highp uimage2D uTileEvidence"));
        assertTrue(shader.contains("binding = 0) readonly buffer NearReuseOwner"));
        assertTrue(shader.contains("binding = 1) buffer NearIdenticalDecision"));
        assertTrue(shader.contains("GRID_TILE_COUNT = GRID_WIDTH * GRID_HEIGHT"));
        assertTrue(shader.contains("const uint TILE_SIZE = 16u"));
        assertTrue(shader.contains("uint admitted = tileEvidence.x"));
        assertTrue(shader.contains("uint medium = tileEvidence.y"));
        assertTrue(shader.contains("uint strong = tileEvidence.z"));
        assertTrue(shader.contains("uint nonfinite = tileEvidence.w"));
        assertTrue(shader.contains("admitted != expectedAdmitted"));
        assertTrue(shader.contains("medium > admitted || strong > medium"));
        assertTrue(shader.contains("nonfinite != 0u"));
        assertTrue(shader.contains("admitted >= 64u"));
        assertTrue(shader.contains("strong * 4u > admitted * 3u"));
        assertTrue(shader.contains("evidence.y * 10u <= evidence.x"));
        assertTrue(shader.contains("evidence.z * 40u <= evidence.x"));
        assertTrue(shader.contains("uint ownerRejectionReason()"));
        assertTrue(shader.contains("return REASON_OWNER_INVALID"));
        assertFalse(shader.contains("subtract64("));
        assertTrue(shader.contains("if (!complete) reason = REASON_EVIDENCE_INVALID"));
        assertTrue(shader.contains("else if (rejected.y != 0u) reason = REASON_CONTENT_LOCAL"));
        assertTrue(shader.contains("else if (!strongQuiet) reason = REASON_CONTENT_STRONG"));
        assertTrue(shader.contains("else if (!mediumQuiet) reason = REASON_CONTENT_MEDIUM"));
        assertTrue(shader.contains("DECISION_REUSE = 0u"));
        assertTrue(shader.contains("DECISION_INFER = 1u"));
        assertTrue(shader.contains("REASON_REUSE = 0u"));
        assertTrue(shader.contains("REASON_NOT_CANDIDATE = 1u"));
        assertTrue(shader.contains("REASON_OWNER_INVALID = 2u"));
        assertTrue(shader.contains("REASON_CONTENT_MEDIUM = 5u"));
        assertTrue(shader.contains("REASON_CONTENT_STRONG = 6u"));
        assertTrue(shader.contains("REASON_CONTENT_LOCAL = 7u"));
        assertTrue(shader.contains("REASON_EVIDENCE_INVALID = 8u"));
        assertTrue(shader.contains("0xd1ec15a5u"));
        assertTrue(shader.contains("0xa3756c91u"));
        assertTrue(shader.contains("0x5c8a936eu"));
        assertTrue(shader.contains("0x504f5250u"));
        assertTrue(shader.contains("decisionWords[uDecisionWordOffset + 6u] = 0u"));
        assertTrue(shader.contains("memoryBarrierBuffer()"));
        assertTrue(shader.contains(
                "decisionWords[uDecisionWordOffset + 6u] = RECORD_MAGIC"));
        assertTrue(shader.contains(
                "decisionWords[uDecisionWordOffset + 7u] = reason"));

        int invalidate = shader.indexOf("decisionWords[uDecisionWordOffset + 6u] = 0u");
        int payload = shader.indexOf("decisionWords[uDecisionWordOffset] = decision");
        int publishBarrier = shader.lastIndexOf("memoryBarrierBuffer()");
        int publish = shader.indexOf(
                "decisionWords[uDecisionWordOffset + 6u] = RECORD_MAGIC");
        assertTrue(invalidate >= 0 && payload > invalidate && publishBarrier > payload
                && publish > publishBarrier);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nearIdenticalResolveRejectsInvalidTensorShape() {
        ClientSbsGpuNearReuseShaders.createNearIdenticalResolve(350, 0);
    }

    @Test
    public void fusedPassPreservesTopFirstTensorAndRectangularPartialTileMath() {
        String shader = ClientSbsGpuNearReuseShaders.createPackAndClassify(392, 168);
        assertTrue(shader.contains("const uint TENSOR_WIDTH = 392u"));
        assertTrue(shader.contains("const uint TENSOR_HEIGHT = 168u"));
        assertTrue(shader.contains("bool inBounds = all(lessThan(point, INPUT_SIZE))"));
        assertTrue(shader.contains("tensorRgb = clamp(sourceRgb, vec3(0.0), vec3(1.0))"));
        assertTrue(shader.contains("uint tensorY = TENSOR_HEIGHT - 1u - point.y"));
        assertTrue(shader.contains("(tensorY * TENSOR_WIDTH + point.x) * 3u"));
        assertTrue(shader.contains("if (uLazyInputPacking == 0) {"));
        assertTrue(shader.contains("tensorValues[firstValue] = tensorRgb.r"));
        assertTrue(shader.contains("tensorValues[firstValue + 1u] = tensorRgb.g"));
        assertTrue(shader.contains("tensorValues[firstValue + 2u] = tensorRgb.b"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fusedPassRejectsInvalidTensorShape() {
        ClientSbsGpuNearReuseShaders.createPackAndClassify(0, 196);
    }

    @Test
    public void v2ValidRealInferenceCommitsItsInputAndBecomesTheReuseOwner() {
        String commit = ClientSbsGpuNearReuseShaders.createCommit(896, 384);
        assertTrue(commit.contains("binding = 0) buffer NearReuseOwner"));
        assertFalse(commit.contains("binding = 0) readonly buffer NearReuseOwner"));
        assertTrue(commit.contains("binding = 1) readonly buffer ProcessorState"));
        assertTrue(commit.contains("binding = 3) writeonly buffer PreviousInputTensor"));
        assertTrue(commit.contains("const ivec2 TENSOR_SIZE = ivec2(896, 384)"));
        // The authority is the depth processor's final frame-state word: both the complete-field
        // bit and the V2 bit (noncollapsed, quantiles valid, adaptive zero/scale valid).
        assertTrue(commit.contains("const uint PROCESSOR_FRAME_STATE_WORD = "
                + ClientSbsGpuDepthProcessor.FRAME_STATE_WORD_INDEX + "u;"));
        int required = ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_DEPTH_VALID
                | ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_V2_VALID;
        assertTrue(commit.contains("const uint FRAME_STATE_V2_VALID_FIELD = " + required + "u;"));
        assertTrue(commit.contains(
                "bool v2ValidField = (processorStateWords[PROCESSOR_FRAME_STATE_WORD]"));
        assertTrue(commit.contains(
                "& FRAME_STATE_V2_VALID_FIELD) == FRAME_STATE_V2_VALID_FIELD"));

        int invalidBranch = commit.indexOf("if (!v2ValidField) {");
        int invalidReturn = commit.indexOf("return;", invalidBranch);
        int tensorWrite = commit.indexOf("previousTensorValues[tensorIndex] = rgb.r");
        int ownerSequence = commit.indexOf("nearOwnerFrameSequence = uCurrentFrameSequence");
        int ownerCapture = commit.indexOf("nearOwnerCapturedAtNs = uCurrentCapturedAtNs");
        int publishBarrier = commit.indexOf("memoryBarrierBuffer()", ownerCapture);
        int ownerValid = commit.indexOf("nearOwnerValid = 1u");
        assertTrue(invalidBranch >= 0 && invalidReturn > invalidBranch
                && tensorWrite > invalidReturn && ownerSequence > tensorWrite
                && ownerCapture > ownerSequence && publishBarrier > ownerCapture
                && ownerValid > publishBarrier);
        // Top-first RGB history comes from the retained model texture, clamped like the tensor.
        assertTrue(commit.contains(
                "ivec2 texturePoint = ivec2(point.x, TENSOR_SIZE.y - 1 - point.y)"));
        assertTrue(commit.contains("clamp(texelFetch(uCurrentColor, texturePoint, 0).rgb,"));
        assertEquals(1, occurrences(commit, "nearOwnerValid = 1u"));
    }

    @Test
    public void invalidOrCollapsedRealInferenceCommitsNothingAndInvalidatesTheOwner() {
        String commit = ClientSbsGpuNearReuseShaders.createCommit(672, 384);
        int invalidBranch = commit.indexOf("if (!v2ValidField) {");
        int invalidReturn = commit.indexOf("return;", invalidBranch);
        assertTrue(invalidBranch >= 0 && invalidReturn > invalidBranch);
        String rejected = commit.substring(invalidBranch, invalidReturn);
        assertTrue(rejected.contains("if (originInvocation) nearOwnerValid = 0u;"));
        // The rejected path touches neither the retained model input nor the owner identity.
        assertFalse(rejected.contains("previousTensorValues"));
        assertFalse(rejected.contains("nearOwnerFrameSequence"));
        assertFalse(rejected.contains("nearOwnerCapturedAtNs"));
        assertFalse(rejected.contains("texelFetch"));
        // No write of any kind precedes the validity decision.
        String beforeDecision = commit.substring(commit.indexOf("void main()"), invalidBranch);
        assertFalse(beforeDecision.contains("previousTensorValues["));
        assertFalse(beforeDecision.contains("nearOwnerValid ="));
        assertEquals(1, occurrences(commit, "nearOwnerValid = 0u"));
    }

    @Test
    public void noShaderRetainsSceneCutEvidenceOrReliableHistoryState() {
        String[] shaders = {
                ClientSbsGpuNearReuseShaders.createPackAndClassify(672, 384),
                ClientSbsGpuNearReuseShaders.createNearIdenticalResolve(672, 384),
                ClientSbsGpuNearReuseShaders.createCommit(672, 384)
        };
        for (String shader : shaders) {
            for (String removed : new String[] {
                    "SceneCut", "sceneCut", "Luma", "ordinal", "Histogram", "HISTORY_",
                    "detectorHistoryValid", "FRAME_STATE_HISTORY_ADVANCES",
                    "FRAME_STATE_STRUCTURELESS_GAP", "structural", "uBlockGrid",
                    "uHistoryValid", "uOutputWordOffset", "uClearHistory"}) {
                assertFalse(removed, shader.contains(removed));
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void commitRejectsInvalidTensorShape() {
        ClientSbsGpuNearReuseShaders.createCommit(672, -1);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
