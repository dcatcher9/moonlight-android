package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static agreement between each Client SBS compute program's GLSL text and the Java code that
 * drives it. The GLSL cannot run on the JVM, so this parses both sides: every uniform Java looks
 * up must be declared and used (a {@code requiredUniform} of an optimized-out uniform throws on
 * the device), every binding index must match, each dispatch must cover its extent with the
 * shader's local size, and the barrier after each dispatch must cover what that pass wrote.
 */
public class ClientSbsComputeProgramConsistencyTest {
    private static final int[][] TENSOR_SIZES = {{672, 384}, {896, 384}, {928, 384}};

    private static final Pattern UNIFORM = Pattern.compile(
            "(?m)^\\s*(layout\\(([^)]*)\\)\\s*)?uniform\\s+([^;]*?)\\s*(\\w+)\\s*;");
    private static final Pattern BUFFER_BLOCK = Pattern.compile(
            "layout\\(std430,\\s*binding\\s*=\\s*(\\d+)\\)\\s*(readonly |writeonly )?buffer\\s+(\\w+)");
    private static final Pattern LOCAL_SIZE = Pattern.compile(
            "layout\\(local_size_x\\s*=\\s*(\\d+)(?:,\\s*local_size_y\\s*=\\s*(\\d+))?"
                    + "(?:,\\s*local_size_z\\s*=\\s*(\\d+))?\\)\\s*in;");

    /** One compute program: its sources, its Java handle, and how Java dispatches it. */
    private static final class Program {
        final String handle;
        final List<String> sources = new ArrayList<>();
        /** Text of the dispatch call's arguments, as written in the processor. */
        String dispatchArguments;
        /** The dispatch stage label, which locates the call in the processor source. */
        String stage;
        int localX;
        int localY;
        /** Outputs later sampled through a texture unit need the fetch barrier as well. */
        boolean outputSampledAsTexture;

        Program(String handle, String... sources) {
            this.handle = handle;
            this.sources.addAll(Arrays.asList(sources));
        }

        Program dispatch(String stage, String arguments, int localX, int localY) {
            this.stage = stage;
            this.dispatchArguments = arguments;
            this.localX = localX;
            this.localY = localY;
            return this;
        }

        Program sampledAsTexture() {
            outputSampledAsTexture = true;
            return this;
        }
    }

    private static List<Program> depthPrograms() {
        List<Program> programs = new ArrayList<>();
        programs.add(new Program("resetStatsProgram", ClientSbsGpuDepthShaders.RESET_RAW_STATS)
                .dispatch("reset depth scratch stats", "1, 1, 1", 256, 1));
        programs.add(new Program("rawMinMaxProgram",
                ClientSbsGpuDepthShaders.rawMinMax(false),
                ClientSbsGpuDepthShaders.rawMinMax(true))
                .dispatch("raw min/max", "groups(outputWidth), groups(outputHeight), 1", 16, 16));
        programs.add(new Program("rawHistogramProgram",
                ClientSbsGpuDepthShaders.rawHistogram(false),
                ClientSbsGpuDepthShaders.rawHistogram(true))
                .dispatch("raw histogram", "groups(outputWidth), groups(outputHeight), 1",
                        16, 16));
        programs.add(new Program("resolveRawProgram",
                ClientSbsGpuDepthShaders.RESOLVE_RAW_FIELD)
                .dispatch("resolve raw field", "1, 1, 1", 1, 1));
        programs.add(new Program("writeRawDepthProgram",
                ClientSbsGpuDepthShaders.writeRawDepth(false),
                ClientSbsGpuDepthShaders.writeRawDepth(true))
                .dispatch("write raw depth", "groups(outputWidth), groups(outputHeight), 1",
                        16, 16)
                .sampledAsTexture());
        programs.add(new Program("publishProfileProgram",
                ClientSbsGpuDepthShaders.PUBLISH_PROFILE)
                .dispatch("publish depth profile", "1, 1, 1", 1, 1)
                .sampledAsTexture());
        programs.add(new Program("resetStateProgram", ClientSbsGpuDepthShaders.RESET_STATE)
                .dispatch("reset temporal state", "1, 1, 1", 1, 1)
                .sampledAsTexture());
        return programs;
    }

    private static List<Program> nearReusePrograms() {
        List<Program> programs = new ArrayList<>();
        Program pack = new Program("packAndClassifyProgram")
                .dispatch("pack model tensor and classify near-identical tiles",
                        "tileGridWidth, tileGridHeight, 1", 16, 16);
        Program resolve = new Program("nearIdenticalResolveProgram")
                .dispatch("resolve near-identical reuse", "1, 1, 1", 64, 1);
        Program commit = new Program("commitProgram")
                .dispatch("commit accepted inference input",
                        "tilesForPixels(inputWidth), tilesForPixels(inputHeight), 1", 16, 16);
        for (int[] size : TENSOR_SIZES) {
            pack.sources.add(ClientSbsGpuSceneCutShaders.createPackAndClassify(size[0], size[1]));
            resolve.sources.add(
                    ClientSbsGpuSceneCutShaders.createNearIdenticalResolve(size[0], size[1]));
            commit.sources.add(ClientSbsGpuSceneCutShaders.createCommit(size[0], size[1]));
        }
        programs.add(pack);
        programs.add(resolve);
        programs.add(commit);
        return programs;
    }

    private static List<Program> disparityPrograms() {
        List<Program> programs = new ArrayList<>();
        Program verticalForward = new Program("verticalForwardProgram")
                .dispatch("vertical forward envelope", "groups(width)", 32, 1)
                .sampledAsTexture();
        Program verticalFinish = new Program("verticalFinishProgram")
                .dispatch("vertical reverse envelope", "groups(width)", 32, 1)
                .sampledAsTexture();
        Program horizontalForward = new Program("horizontalForwardProgram")
                .dispatch("horizontal forward majorant", "groups(height)", 32, 1)
                .sampledAsTexture();
        Program horizontalFinish = new Program("horizontalFinishProgram")
                .dispatch("horizontal reverse majorant", "groups(height)", 32, 1)
                .sampledAsTexture();
        for (int[] size : TENSOR_SIZES) {
            verticalForward.sources.add(
                    ClientSbsGpuDisparityShaders.verticalForward(size[0], size[1]));
            verticalFinish.sources.add(
                    ClientSbsGpuDisparityShaders.verticalFinish(size[0], size[1]));
            horizontalForward.sources.add(
                    ClientSbsGpuDisparityShaders.horizontalForward(size[0], size[1]));
            horizontalFinish.sources.add(
                    ClientSbsGpuDisparityShaders.horizontalFinish(size[0], size[1]));
        }
        programs.add(verticalForward);
        programs.add(verticalFinish);
        programs.add(horizontalForward);
        programs.add(horizontalFinish);
        return programs;
    }

    @Test
    public void depthProcessorUniformsBindingsDispatchesAndBarriersMatchItsShaders()
            throws IOException {
        String java = readSource("sbs/ClientSbsGpuDepthProcessor.java");
        List<Program> programs = depthPrograms();
        assertProgramsCoverEveryCreatedProgram(java, programs);
        Map<String, Set<String>> lookups = javaUniformLookups(java);
        // RawUniforms looks up the shared raw-tensor uniforms for each program it wraps.
        Matcher raw = Pattern.compile(
                "new RawUniforms\\((\\w+), removeReflectedPadding\\)").matcher(java);
        int rawPrograms = 0;
        while (raw.find()) {
            rawPrograms++;
            lookups.computeIfAbsent(raw.group(1), key -> new LinkedHashSet<>()).addAll(
                    Arrays.asList("uRawByteOffset", "uRawPixelStrideBytes", "uTensorSize",
                            "uOutputSize"));
        }
        assertEquals(3, rawPrograms);
        assertTrue(java.contains("? requiredUniform(program, \"uContentScale\") : -1"));
        for (Program program : programs) {
            Set<String> expected = lookups.getOrDefault(program.handle, new LinkedHashSet<>());
            for (String source : program.sources) {
                Set<String> declared = dataUniforms(source);
                Set<String> required = new LinkedHashSet<>(expected);
                if (source.contains("uniform vec2 uContentScale;")) {
                    required.add("uContentScale");
                }
                assertEquals(program.handle + " uniforms", new TreeSet<>(required),
                        new TreeSet<>(declared));
                assertEveryUniformIsUsed(program.handle, source, declared);
            }
        }

        Map<String, Integer> constants = intConstants(java);
        int rawBinding = constants.get("RAW_SSBO_BINDING");
        int statsBinding = constants.get("RAW_STATS_SSBO_BINDING");
        int stateBinding = constants.get("STATE_SSBO_BINDING");
        int rawDepthImage = constants.get("RAW_DEPTH_IMAGE_BINDING");
        int profileImage = constants.get("PROFILE_IMAGE_BINDING");
        Map<String, Integer> blockBindings = new LinkedHashMap<>();
        blockBindings.put("RawDepth", rawBinding);
        blockBindings.put("RawStats", statsBinding);
        blockBindings.put("ProcessorState", stateBinding);
        Map<String, Integer> imageBindings = new LinkedHashMap<>();
        imageBindings.put("uCurrentRawDepth", rawDepthImage);
        imageBindings.put("uProfileTexture", profileImage);
        for (Program program : programs) {
            for (String source : program.sources) {
                assertBlockBindings(program.handle, source, blockBindings);
                assertImageBindings(program.handle, source, imageBindings);
                assertLocalSize(program, source);
            }
        }
        // The process path binds all three SSBOs once and each image before its writer.
        String process = methodBody(java, "public Result processRendererOwned(");
        assertTrue(process.contains("bindSsbo(RAW_SSBO_BINDING, packedFloatSsbo);"));
        assertTrue(process.contains("bindSsbo(RAW_STATS_SSBO_BINDING, rawStatsBuffer);"));
        assertTrue(process.contains("bindSsbo(STATE_SSBO_BINDING, stateBuffer);"));
        assertEquals(3, occurrences(process, "bindSsbo("));
        assertBoundBeforeDispatch(process,
                "glBindImageTexture(RAW_DEPTH_IMAGE_BINDING, rawDepthTexture,", "write raw depth");
        assertBoundBeforeDispatch(process,
                "glBindImageTexture(PROFILE_IMAGE_BINDING, profileTexture,",
                "publish depth profile");
        assertTrue(process.contains("GLES31.GL_WRITE_ONLY, GLES30.GL_R32F);"));
        assertTrue(process.contains("GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F);"));

        List<String> order = new ArrayList<>();
        for (Program program : programs) {
            if (!program.handle.equals("resetStateProgram")) {
                order.add(program.stage);
                assertDispatch(process, program);
            }
        }
        assertStagesInOrder(process, order);
        assertEquals(order.size(), occurrences(process, "dispatch(")
                + occurrences(process, "dispatchCurrent("));
        assertDispatch(methodBody(java, "public void resetTemporalState()"),
                programs.get(programs.size() - 1));

        // Dispatch math: one 16x16 workgroup per started 16-texel span on each axis.
        assertEquals(16, ClientSbsGpuDepthShaders.WORKGROUP_EDGE);
        for (int[] size : TENSOR_SIZES) {
            for (int length : size) {
                int groups = ClientSbsGpuDepthProcessor.groups(length);
                assertTrue(groups * 16 >= length && (groups - 1) * 16 < length);
            }
        }
        assertEquals(1, ClientSbsGpuDepthProcessor.groups(1));
        assertEquals(2, ClientSbsGpuDepthProcessor.groups(17));
    }

    @Test
    public void nearReuseClassifierUniformsBindingsDispatchesAndBarriersMatchItsShaders()
            throws IOException {
        String java = readSource("sbs/ClientSbsGpuSceneCutDetector.java");
        List<Program> programs = nearReusePrograms();
        assertProgramsCoverEveryCreatedProgram(java, programs);
        Map<String, Set<String>> lookups = javaUniformLookups(java);
        for (Program program : programs) {
            Set<String> expected = lookups.get(program.handle);
            assertNotNull(program.handle, expected);
            for (String source : program.sources) {
                // Samplers are looked up too: Java assigns their texture unit.
                Set<String> declared = dataUniforms(source);
                declared.addAll(samplerUniforms(source));
                assertEquals(program.handle + " uniforms", new TreeSet<>(expected),
                        new TreeSet<>(declared));
                assertEveryUniformIsUsed(program.handle, source, declared);
            }
        }

        Map<String, Integer> constants = intConstants(java);
        Map<String, Integer> blockBindings = new LinkedHashMap<>();
        blockBindings.put("NearReuseOwner", constants.get("OWNER_SSBO_BINDING"));
        blockBindings.put("NearIdenticalDecision", constants.get("DECISION_SSBO_BINDING"));
        blockBindings.put("ProcessorState", constants.get("PROCESSOR_STATE_SSBO_BINDING"));
        blockBindings.put("InputTensor", constants.get("INPUT_TENSOR_SSBO_BINDING"));
        blockBindings.put("PreviousInputTensor",
                constants.get("PREVIOUS_INPUT_TENSOR_SSBO_BINDING"));
        Map<String, Integer> imageBindings = new LinkedHashMap<>();
        imageBindings.put("uTileEvidence", constants.get("TILE_EVIDENCE_IMAGE_BINDING"));
        for (Program program : programs) {
            for (String source : program.sources) {
                assertBlockBindings(program.handle, source, blockBindings);
                assertImageBindings(program.handle, source, imageBindings);
                assertLocalSize(program, source);
            }
        }
        // The decision record and the processor state share binding 1 in different programs;
        // no single program may declare both.
        assertEquals(constants.get("DECISION_SSBO_BINDING"),
                constants.get("PROCESSOR_STATE_SSBO_BINDING"));
        for (Program program : programs) {
            for (String source : program.sources) {
                assertFalse(source.contains("buffer NearIdenticalDecision")
                        && source.contains("buffer ProcessorState"));
            }
        }

        String process = methodBody(java, "public void processRendererOwnedAndPack(");
        assertTrue(process.contains("bindSsbo(OWNER_SSBO_BINDING, ownerBuffer);"));
        assertTrue(process.contains(
                "bindSsbo(DECISION_SSBO_BINDING, nearIdenticalDecisionBuffer);"));
        assertTrue(process.contains("bindSsbo(INPUT_TENSOR_SSBO_BINDING, packedFloatSsbo);"));
        assertTrue(process.contains(
                "bindSsbo(PREVIOUS_INPUT_TENSOR_SSBO_BINDING, previousInputBuffer);"));
        assertEquals(4, occurrences(process, "bindSsbo("));
        assertEquals(2, occurrences(process,
                "glBindImageTexture(TILE_EVIDENCE_IMAGE_BINDING, tileEvidenceTexture,"));
        assertBoundBeforeDispatch(process, "GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32UI);",
                programs.get(0).stage);
        assertBoundBeforeDispatch(process, "GLES31.GL_READ_ONLY, GLES30.GL_RGBA32UI);",
                programs.get(1).stage);
        assertDispatch(process, programs.get(0));
        assertDispatch(process, programs.get(1));
        assertStagesInOrder(process, Arrays.asList(programs.get(0).stage,
                programs.get(1).stage));
        assertEquals(2, occurrences(process, "dispatch("));

        String commit = methodBody(java, "public void commitAcceptedFrame(");
        assertTrue(commit.contains("bindSsbo(OWNER_SSBO_BINDING, ownerBuffer);"));
        assertTrue(commit.contains(
                "bindSsbo(PROCESSOR_STATE_SSBO_BINDING, processorStateBuffer);"));
        assertTrue(commit.contains(
                "bindSsbo(PREVIOUS_INPUT_TENSOR_SSBO_BINDING, previousInputBuffer);"));
        assertEquals(3, occurrences(commit, "bindSsbo("));
        assertDispatch(commit, programs.get(2));
        assertEquals(1, occurrences(commit, "dispatch("));

        // Dispatch math: one workgroup per 16x16 tile, and a tile grid that covers the tensor.
        for (int[] size : TENSOR_SIZES) {
            for (int length : size) {
                int tiles = ClientSbsGpuSceneCutDetector.tilesForPixels(length);
                assertTrue(tiles * 16 >= length && (tiles - 1) * 16 < length);
            }
            // The 64-lane resolve strides over every tile from a single workgroup.
            String resolve = ClientSbsGpuSceneCutShaders.createNearIdenticalResolve(
                    size[0], size[1]);
            assertTrue(resolve.contains(
                    "for (uint tileIndex = lane; tileIndex < GRID_TILE_COUNT; tileIndex += 64u)"));
        }

        // The owner block's std430 layout matches the bytes Java allocates and clears.
        String shader = ClientSbsGpuSceneCutShaders.createCommit(672, 384);
        int block = shader.indexOf("buffer NearReuseOwner {");
        String fields = shader.substring(block, shader.indexOf("};", block));
        int sequence = fields.indexOf("uvec2 nearOwnerFrameSequence;");
        int captured = fields.indexOf("uvec2 nearOwnerCapturedAtNs;");
        int valid = fields.indexOf("uint nearOwnerValid;");
        assertTrue(sequence >= 0 && captured > sequence && valid > captured);
        assertEquals(3, occurrences(fields, ";"));
        assertEquals(0, ClientSbsGpuSceneCutDetector.OWNER_FRAME_SEQUENCE_BYTE_OFFSET);
        assertEquals(8, ClientSbsGpuSceneCutDetector.OWNER_CAPTURED_AT_NS_BYTE_OFFSET);
        assertEquals(16, ClientSbsGpuSceneCutDetector.OWNER_VALID_BYTE_OFFSET);
        // 20 bytes of members rounded up to the block's 8-byte (uvec2) alignment.
        assertEquals(24, ClientSbsGpuSceneCutDetector.OWNER_BYTES);
    }

    @Test
    public void disparityProcessorUniformsBindingsDispatchesAndBarriersMatchItsShaders()
            throws IOException {
        String java = readSource("sbs/ClientSbsGpuDisparityProcessor.java");
        List<Program> programs = disparityPrograms();
        assertProgramsCoverEveryCreatedProgram(java, programs);
        Map<String, Set<String>> lookups = javaUniformLookups(java);
        Map<String, Integer> constants = intConstants(java);
        int imageBinding = constants.get("IMAGE_BINDING");
        for (Program program : programs) {
            Set<String> expected = lookups.get(program.handle);
            assertNotNull(program.handle, expected);
            for (String source : program.sources) {
                Set<String> declared = dataUniforms(source);
                declared.addAll(samplerUniforms(source));
                assertEquals(program.handle + " uniforms", new TreeSet<>(expected),
                        new TreeSet<>(declared));
                assertEveryUniformIsUsed(program.handle, source, declared);
                assertFalse(BUFFER_BLOCK.matcher(source).find());
                Map<String, int[]> images = images(source);
                assertEquals(program.handle, 1, images.size());
                assertEquals(program.handle, imageBinding,
                        images.values().iterator().next()[0]);
                assertLocalSize(program, source);
            }
        }
        String process = methodBody(java, "public int process(");
        List<String> order = new ArrayList<>();
        for (Program program : programs) {
            order.add(program.stage);
            assertDispatch(process, program);
        }
        assertStagesInOrder(process, order);
        assertEquals(order.size(), occurrences(process, "dispatch("));
        assertEquals(32, ClientSbsGpuDisparityShaders.LOCAL_SIZE);
        assertTrue(java.contains("return (length + LOCAL_SIZE - 1) / LOCAL_SIZE;"));
        assertTrue(java.contains("GLES31.glDispatchCompute(groups, 1, 1);"));
    }

    @Test
    public void rendererQuadAndPackProgramsLookUpOnlyDeclaredUniforms() throws IOException {
        String bindings = readSource("utils/ClientSbsGlPrograms.java");
        Set<String> lookedUp = new TreeSet<>();
        Matcher lookup = Pattern.compile(
                "glGetUniformLocation\\(\\s*program,\\s*\"(\\w+)\"\\)").matcher(bindings);
        while (lookup.find()) {
            lookedUp.add(lookup.group(1));
        }
        Set<String> declared = new TreeSet<>();
        for (String source : rendererShaderSources()) {
            Set<String> uniforms = dataUniforms(source);
            uniforms.addAll(samplerUniforms(source));
            assertEveryUniformIsUsed("renderer shader", source, uniforms);
            declared.addAll(uniforms);
        }
        // u_Texture is bound on unit zero by default and is never looked up.
        declared.remove("u_Texture");
        assertEquals(declared, lookedUp);

        // The eager pack program's single SSBO binding and its 8x8 dispatch.
        String renderer = readSource("utils/Stereo3DRenderer.java");
        String pack = reflectString("com.limelight.utils.ClientSbsShaders",
                "createModelInputPackCompute", 672, 384);
        Matcher block = BUFFER_BLOCK.matcher(pack);
        assertTrue(block.find());
        assertEquals("0", block.group(1));
        assertEquals("InputTensor", block.group(3));
        assertFalse(block.find());
        assertTrue(pack.contains(
                "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;"));
        assertTrue(renderer.contains(
                "GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, inputBufferId);"));
        int dispatch = renderer.indexOf("GLES31.glDispatchCompute((modelInputWidth + 7) / 8,");
        assertTrue(dispatch >= 0);
        assertTrue(renderer.startsWith("(modelInputHeight + 7) / 8, 1);",
                renderer.indexOf("(modelInputHeight", dispatch)));
        int barrier = renderer.indexOf("glMemoryBarrier(", dispatch);
        assertTrue(renderer.substring(barrier, renderer.indexOf(';', barrier))
                .contains("GL_SHADER_STORAGE_BARRIER_BIT"));
    }

    private static List<String> rendererShaderSources() {
        List<String> sources = new ArrayList<>();
        String type = "com.limelight.utils.ClientSbsShaders";
        sources.add(reflectField(type, "FLAT_FRAGMENT"));
        sources.add(reflectString(type, "createModelInputFragment", false));
        sources.add(reflectString(type, "createModelInputFragment", true));
        sources.add(reflectField(type, "CONTRACTIVE_WARP_MAP_FRAGMENT"));
        sources.add(reflectField(type, "CONTRACTIVE_WARP_MAP_REFINEMENT_FRAGMENT"));
        sources.add(reflectField(type, "WARPED_REPROJECTION_FRAGMENT"));
        for (int[] size : TENSOR_SIZES) {
            sources.add(reflectString(type, "createModelInputPackCompute", size[0], size[1]));
        }
        return sources;
    }

    private static String reflectField(String type, String name) {
        try {
            java.lang.reflect.Field field = Class.forName(type).getDeclaredField(name);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static String reflectString(String type, String name, Object... arguments) {
        try {
            for (java.lang.reflect.Method method : Class.forName(type).getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == arguments.length) {
                    method.setAccessible(true);
                    return (String) method.invoke(null, arguments);
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        throw new AssertionError("Missing " + type + "." + name);
    }

    /** Every createComputeProgram assignment in the processor has a row in the table. */
    private static void assertProgramsCoverEveryCreatedProgram(String java,
                                                               List<Program> programs) {
        Set<String> created = new TreeSet<>();
        Matcher matcher = Pattern.compile("(\\w+Program) = createComputeProgram\\(")
                .matcher(java);
        while (matcher.find()) {
            created.add(matcher.group(1));
        }
        Set<String> listed = new TreeSet<>();
        for (Program program : programs) {
            listed.add(program.handle);
            assertFalse(program.handle, program.sources.isEmpty());
        }
        assertEquals(created, listed);
    }

    /** Program handle -> names passed to requiredUniform for it. */
    private static Map<String, Set<String>> javaUniformLookups(String java) {
        Map<String, Set<String>> lookups = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile(
                "requiredUniform\\(\\s*(\\w+Program),\\s*\"(\\w+)\"\\)").matcher(java);
        while (matcher.find()) {
            lookups.computeIfAbsent(matcher.group(1), key -> new LinkedHashSet<>())
                    .add(matcher.group(2));
        }
        return lookups;
    }

    private static Map<String, Integer> intConstants(String java) {
        Map<String, Integer> constants = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile(
                "static final int (\\w+) = (\\d+);").matcher(java);
        while (matcher.find()) {
            constants.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return constants;
    }

    /** Non-opaque uniforms: everything except samplers and images. */
    private static Set<String> dataUniforms(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = UNIFORM.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(3);
            if (!type.contains("sampler") && !type.contains("image")) {
                names.add(matcher.group(4));
            }
        }
        return names;
    }

    private static Set<String> samplerUniforms(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = UNIFORM.matcher(source);
        while (matcher.find()) {
            if (matcher.group(3).contains("sampler")) {
                names.add(matcher.group(4));
            }
        }
        return names;
    }

    /** Image uniform name -> {binding, 1 if the shader can write it}. */
    private static Map<String, int[]> images(String source) {
        Map<String, int[]> images = new LinkedHashMap<>();
        Matcher matcher = UNIFORM.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(3);
            if (!type.contains("image")) {
                continue;
            }
            Matcher binding = Pattern.compile("binding\\s*=\\s*(\\d+)")
                    .matcher(matcher.group(2) == null ? "" : matcher.group(2));
            assertTrue("image without binding: " + matcher.group(4), binding.find());
            images.put(matcher.group(4), new int[] {
                    Integer.parseInt(binding.group(1)), type.contains("readonly") ? 0 : 1});
        }
        return images;
    }

    private static void assertEveryUniformIsUsed(String label, String source,
                                                 Set<String> uniforms) {
        for (String name : uniforms) {
            Matcher use = Pattern.compile("\\b" + name + "\\b").matcher(source);
            int count = 0;
            while (use.find()) {
                count++;
            }
            assertTrue(label + " declares but never uses " + name, count >= 2);
        }
    }

    private static void assertBlockBindings(String label, String source,
                                            Map<String, Integer> expected) {
        Matcher matcher = BUFFER_BLOCK.matcher(source);
        Set<Integer> used = new TreeSet<>();
        while (matcher.find()) {
            Integer binding = expected.get(matcher.group(3));
            assertNotNull(label + " has an unknown block " + matcher.group(3), binding);
            assertEquals(label + " " + matcher.group(3), binding.intValue(),
                    Integer.parseInt(matcher.group(1)));
            assertTrue(label + " reuses SSBO binding " + binding, used.add(binding));
        }
    }

    private static void assertImageBindings(String label, String source,
                                            Map<String, Integer> expected) {
        for (Map.Entry<String, int[]> image : images(source).entrySet()) {
            Integer binding = expected.get(image.getKey());
            assertNotNull(label + " has an unknown image " + image.getKey(), binding);
            assertEquals(label + " " + image.getKey(), binding.intValue(),
                    image.getValue()[0]);
        }
    }

    private static void assertLocalSize(Program program, String source) {
        Matcher matcher = LOCAL_SIZE.matcher(source);
        assertTrue(program.handle + " has no local size", matcher.find());
        assertEquals(program.handle, program.localX, Integer.parseInt(matcher.group(1)));
        assertEquals(program.handle, program.localY,
                matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2)));
        assertFalse(program.handle + " declares two local sizes", matcher.find());
    }

    /**
     * The dispatch for {@code program.stage} uses the expected group counts, and the barrier
     * issued before the next dispatch covers every resource kind the program writes.
     */
    private static void assertDispatch(String method, Program program) {
        int label = method.indexOf("\"" + program.stage + "\"");
        assertTrue("Missing dispatch stage " + program.stage, label >= 0);
        int call = Math.max(method.lastIndexOf("dispatch(", label),
                method.lastIndexOf("dispatchCurrent(", label));
        assertTrue(call >= 0);
        String arguments = method.substring(method.indexOf('(', call) + 1, label)
                .replaceAll("\\s+", " ").trim();
        // dispatch(program, groups..., "stage") or dispatch(groups..., "stage").
        if (arguments.startsWith(program.handle + ",")) {
            arguments = arguments.substring(program.handle.length() + 1).trim();
        } else {
            int use = method.lastIndexOf("glUseProgram(", call);
            assertTrue(program.stage + " dispatches without selecting a program", use >= 0);
            assertTrue(program.stage + " dispatches the wrong program",
                    method.startsWith("glUseProgram(" + program.handle + ")", use));
        }
        assertEquals(program.stage, program.dispatchArguments + ",", arguments);

        int next = nextDispatch(method, label);
        String after = method.substring(label, next < 0 ? method.length() : next);
        after = after.replace("shaderStorageBarrier()", "GL_SHADER_STORAGE_BARRIER_BIT")
                .replace("packCompletionBarrierBits()",
                        "GL_SHADER_STORAGE_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT")
                .replace("imageToTextureBarrier()",
                        "GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT");
        boolean writesBuffer = false;
        boolean writesImage = false;
        for (String source : program.sources) {
            Matcher block = BUFFER_BLOCK.matcher(source);
            while (block.find()) {
                writesBuffer |= !"readonly ".equals(block.group(2));
            }
            for (int[] image : images(source).values()) {
                writesImage |= image[1] != 0;
            }
        }
        if (writesBuffer) {
            assertTrue(program.stage + " needs a shader-storage barrier",
                    after.contains("GL_SHADER_STORAGE_BARRIER_BIT"));
        }
        if (writesImage) {
            assertTrue(program.stage + " needs an image-access barrier",
                    after.contains("GL_SHADER_IMAGE_ACCESS_BARRIER_BIT"));
            if (program.outputSampledAsTexture) {
                assertTrue(program.stage + " needs a texture-fetch barrier",
                        after.contains("GL_TEXTURE_FETCH_BARRIER_BIT"));
            }
        }
        assertTrue(program.stage + " writes nothing", writesBuffer || writesImage);
    }

    private static int nextDispatch(String method, int from) {
        int plain = method.indexOf("dispatch(", from);
        int current = method.indexOf("dispatchCurrent(", from);
        if (plain < 0) {
            return current;
        }
        return current < 0 ? plain : Math.min(plain, current);
    }

    private static void assertBoundBeforeDispatch(String method, String binding, String stage) {
        int label = method.indexOf("\"" + stage + "\"");
        assertTrue(label >= 0);
        int bound = method.lastIndexOf(binding, label);
        assertTrue(stage + " dispatches before " + binding, bound >= 0);
        int previous = Math.max(method.lastIndexOf("dispatch(", bound),
                method.lastIndexOf("dispatchCurrent(", bound));
        int own = Math.max(method.lastIndexOf("dispatch(", label),
                method.lastIndexOf("dispatchCurrent(", label));
        assertTrue(stage + " does not own " + binding, previous < bound && bound < own);
    }

    private static void assertStagesInOrder(String method, List<String> stages) {
        int previous = -1;
        for (String stage : stages) {
            int index = method.indexOf("\"" + stage + "\"");
            assertTrue("Stage out of order: " + stage, index > previous);
            previous = index;
        }
    }

    private static String readSource(String relative) throws IOException {
        File file = new File("src/main/java/com/limelight/" + relative);
        assertTrue(relative + " source is missing", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static String methodBody(String source, String signature) {
        int signatureStart = source.indexOf(signature);
        assertTrue("Missing method signature: " + signature, signatureStart >= 0);
        int bodyStart = source.indexOf(") {", signatureStart);
        assertTrue("Missing method body: " + signature, bodyStart >= 0);
        bodyStart += 2;
        int depth = 0;
        for (int index = bodyStart; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return source.substring(bodyStart + 1, index);
            }
        }
        throw new AssertionError("Unterminated method body: " + signature);
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
