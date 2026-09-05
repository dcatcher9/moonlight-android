package com.limelight.utils;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Source-level guardrails for native Client SBS fence ownership and arbitration order. */
public final class ClientSbsNativeFenceOrderingContractTest {
    @Test
    public void decisionReadPrecedesPreviousOutputWait() throws Exception {
        String nativeRun = functionBody(readNativeSource(),
                "Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeRun(");

        int contractGuard = nativeRun.indexOf(
                "if (input_ready_fence == 0 || fences_alias");
        int inputConsume = nativeRun.indexOf(
                "bool input_fence_ok = consume_fence(", contractGuard);
        int inputVisibility = nativeRun.indexOf(
                "glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT", inputConsume);
        int decisionRead = nativeRun.indexOf(
                "reuse = near_identical_record_requests_reuse(", inputVisibility);
        int outputConsume = nativeRun.indexOf(
                "bool output_fence_ok = consume_fence(", decisionRead);
        int bothFencesRequired = nativeRun.indexOf(
                "if (!input_fence_ok || !output_fence_ok)", outputConsume);
        int finalVisibility = nativeRun.indexOf(
                "glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT", bothFencesRequired);
        int outputStorageReleased = nativeRun.indexOf(
                "engine->output_requires_consumed_fence[slot_index] = false;",
                finalVisibility);
        int reuseFence = nativeRun.indexOf(
                "GLsync reuse_ready = glFenceSync(", outputStorageReleased);
        int liteRtRun = nativeRun.indexOf(
                "LiteRtRunCompiledModel", outputStorageReleased);

        assertOrdered("contract guard, input wait", contractGuard, inputConsume);
        assertOrdered("input wait, input visibility", inputConsume, inputVisibility);
        assertOrdered("input visibility, decision read", inputVisibility, decisionRead);
        assertOrdered("decision read, previous-output wait", decisionRead, outputConsume);
        assertOrdered("previous-output wait, success check", outputConsume, bothFencesRequired);
        assertOrdered("success check, final visibility", bothFencesRequired, finalVisibility);
        assertOrdered("final visibility, output release", finalVisibility,
                outputStorageReleased);
        assertOrdered("output release, reuse fence", outputStorageReleased, reuseFence);
        assertOrdered("output release, LiteRT", outputStorageReleased, liteRtRun);
    }

    @Test
    public void everyDirectWaitRetainsAnUnconsumedHandle() throws Exception {
        String nativeRun = functionBody(readNativeSource(),
                "Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeRun(");

        int inputConsume = nativeRun.indexOf("bool input_fence_ok = consume_fence(");
        int inputRetain = nativeRun.indexOf(
                "retain_failed_run_fence(engine, input_ready_fence);", inputConsume);
        int decisionRead = nativeRun.indexOf(
                "reuse = near_identical_record_requests_reuse(", inputRetain);
        int outputConsume = nativeRun.indexOf(
                "bool output_fence_ok = consume_fence(", decisionRead);
        int outputRetain = nativeRun.indexOf(
                "retain_failed_run_fence(engine, previous_output_consumed_fence);",
                outputConsume);
        int failureExit = nativeRun.indexOf(
                "if (!input_fence_ok || !output_fence_ok)", outputRetain);

        assertOrdered("input wait, input retention", inputConsume, inputRetain);
        assertOrdered("input retention, decision read", inputRetain, decisionRead);
        assertOrdered("output wait, output retention", outputConsume, outputRetain);
        assertOrdered("output retention, failure exit", outputRetain, failureExit);
        assertTrue("Invalid contracts must consume transferred fences",
                nativeRun.substring(0, inputConsume).contains("consume_run_fences("));
    }

    private static String readNativeSource() throws IOException {
        File file = new File("src/main/jni/client_sbs_gpu/client_sbs_gpu.c");
        assertTrue("Client SBS native source is missing", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String functionBody(String source, String signature) {
        int signatureStart = source.indexOf(signature);
        assertTrue("Missing function signature: " + signature, signatureStart >= 0);
        int bodyStart = source.indexOf('{', signatureStart + signature.length());
        assertTrue("Missing function body: " + signature, bodyStart >= 0);

        int depth = 0;
        for (int index = bodyStart; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return source.substring(bodyStart + 1, index);
            }
        }
        throw new AssertionError("Unterminated function body: " + signature);
    }

    private static void assertOrdered(String label, int first, int second) {
        assertTrue("Missing or misordered " + label, first >= 0 && second > first);
    }
}
