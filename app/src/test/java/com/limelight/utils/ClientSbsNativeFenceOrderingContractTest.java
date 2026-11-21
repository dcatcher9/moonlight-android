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
                "bool input_fence_ok = consume_or_retain_fence(", contractGuard);
        int inputVisibility = nativeRun.indexOf(
                "glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT", inputConsume);
        int decisionRead = nativeRun.indexOf(
                "reuse = near_identical_record_requests_reuse(", inputVisibility);
        int outputConsume = nativeRun.indexOf(
                "bool output_fence_ok = consume_or_retain_fence(", decisionRead);
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
        String source = readNativeSource();
        String nativeRun = functionBody(source,
                "Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeRun(");

        // The wait and the retention of a still-owned handle are one helper. Its body must keep
        // the wait first and retain exactly the handle whose ownership was not consumed.
        String consumeOrRetain = functionBody(source,
                "static bool consume_or_retain_fence(");
        int helperConsume = consumeOrRetain.indexOf(
                "consume_fence(engine, fence_handle, role, &ownership_consumed);");
        int helperOwnershipCheck = consumeOrRetain.indexOf(
                "if (!ownership_consumed) {", helperConsume);
        int helperRetain = consumeOrRetain.indexOf(
                "retain_failed_run_fence(engine, fence_handle);", helperOwnershipCheck);
        int helperReturn = consumeOrRetain.indexOf("return consumed;", helperRetain);
        assertOrdered("helper wait, ownership check", helperConsume, helperOwnershipCheck);
        assertOrdered("helper ownership check, retention", helperOwnershipCheck, helperRetain);
        assertOrdered("helper retention, result", helperRetain, helperReturn);

        // Every wait issued directly by nativeRun or its contract-failure path must go through
        // that helper; a bare consume_fence() would drop the retention of a failed wait.
        assertTrue("nativeRun must not wait on a fence without retaining a failed handle",
                !containsBareConsumeFence(nativeRun));
        assertTrue("consume_run_fences must not wait on a fence without retaining a failed handle",
                !containsBareConsumeFence(functionBody(source,
                        "static bool consume_run_fences(")));

        int inputConsume = nativeRun.indexOf(
                "bool input_fence_ok = consume_or_retain_fence(");
        int inputHandle = nativeRun.indexOf(
                "engine, input_ready_fence, \"input\");", inputConsume);
        int decisionRead = nativeRun.indexOf(
                "reuse = near_identical_record_requests_reuse(", inputHandle);
        int outputConsume = nativeRun.indexOf(
                "bool output_fence_ok = consume_or_retain_fence(", decisionRead);
        int outputHandle = nativeRun.indexOf(
                "engine, previous_output_consumed_fence, \"output-consumed\");",
                outputConsume);
        int failureExit = nativeRun.indexOf(
                "if (!input_fence_ok || !output_fence_ok)", outputHandle);

        assertOrdered("input wait, input retention", inputConsume, inputHandle);
        assertOrdered("input retention, decision read", inputHandle, decisionRead);
        assertOrdered("output wait, output retention", outputConsume, outputHandle);
        assertOrdered("output retention, failure exit", outputHandle, failureExit);
        assertTrue("Invalid contracts must consume transferred fences",
                nativeRun.substring(0, inputConsume).contains("consume_run_fences("));
    }

    @Test
    public void inferenceOwnerTransfersRunFencesOnlyAfterJavaValidation() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/com/limelight/utils/Stereo3DRenderer.java").toPath()),
                StandardCharsets.UTF_8);
        String worker = functionBody(source, "private class AiTask implements Runnable");

        // Everything that can throw before nativeRun() runs first, so a rejected task still
        // reaches retainUnsubmittedTaskForClose() with both of its renderer fences.
        int validation = worker.indexOf("ClientSbsGpuInferenceEngine.validateRunArguments(");
        int transfer = worker.indexOf("fencesTransferredToNative = true;", validation);
        int run = worker.indexOf("gpuEngine.run(", transfer);
        int reclaim = worker.indexOf("retainUnsubmittedTaskForClose(renderResult)", run);
        assertOrdered("Java validation, fence transfer", validation, transfer);
        assertOrdered("fence transfer, native run", transfer, run);
        assertOrdered("native run, unsubmitted reclaim", run, reclaim);

        String engine = new String(Files.readAllBytes(new File(
                "src/main/java/com/limelight/utils/ClientSbsGpuInferenceEngine.java").toPath()),
                StandardCharsets.UTF_8);
        String engineRun = functionBody(engine,
                "long run(int slotIndex, long inputReadyFence, long previousOutputConsumedFence,");
        assertOrdered("engine validation, nativeRun",
                engineRun.indexOf("validateRunArguments("), engineRun.indexOf("nativeRun("));
    }

    /** True when the text calls consume_fence() itself rather than consume_or_retain_fence(). */
    private static boolean containsBareConsumeFence(String body) {
        return body.replace("consume_or_retain_fence(", "").contains("consume_fence(");
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
