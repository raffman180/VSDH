package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** CPU-side contracts of the compute, storage buffer, geometry fold and expression additions. */
class NativeFeaturePreparationTest {
    @TempDir Path directory;

    private void write(String name, String text) throws Exception {
        Path file = directory.resolve("shaders").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static final String FULLSCREEN_VERTEX = """
            #version 330 compatibility
            out vec2 uv;
            void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
            """;

    @Test void computeIsTranslatedWithItsDispatchAndColourImage() throws Exception {
        write("deferred4_a.csh", """
                #version 430
                layout(local_size_x = 8, local_size_y = 4) in;
                const vec2 workGroupsRender = vec2(0.5, 0.5);
                layout(rgba16f) writeonly uniform image2D colorimg4;
                uniform sampler2D colortex4;
                void main() {
                    ivec2 texel = ivec2(gl_GlobalInvocationID.xy);
                    imageStore(colorimg4, texel, texelFetch(colortex4, texel, 0));
                }
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var compute = PackProgramSource.prepareCompute(pack, "deferred4_a");
            assertEquals(Set.of(ProgramStage.COMPUTE), compute.compiled().stages().keySet());
            assertArrayEquals(new int[]{100, 100, 1}, compute.groups(1600, 800));
            String text = compute.compiled().sources().get(ProgramStage.COMPUTE);
            assertTrue(text.matches("(?s).*layout\\(rgba16f, set = 0, binding = \\d+\\) writeonly uniform image2D colorimg4;.*"), text);
        }
    }

    @Test void packBindingQualifiersGiveWayToHostBindings() throws Exception {
        write("composite_a.csh", """
                #version 430
                layout(local_size_x = 8, local_size_y = 8) in;
                layout(binding = 0, rgba16f) writeonly uniform image2D colorimg4;
                layout(binding = 3) uniform sampler2D colortex4;
                void main() { imageStore(colorimg4, ivec2(gl_GlobalInvocationID.xy), texelFetch(colortex4, ivec2(0), 0)); }
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            String text = PackProgramSource.prepareCompute(pack, "composite_a").compiled().sources().get(ProgramStage.COMPUTE);
            assertTrue(text.matches("(?s).*layout\\(rgba16f, set = 0, binding = \\d+\\) writeonly uniform image2D colorimg4;.*"), text);
        }
    }

    @Test void fixedWorkGroupsIgnoreTheScreenSize() throws Exception {
        write("composite_a.csh", """
                #version 430
                layout(local_size_x = 256) in;
                const ivec3 workGroups = ivec3(1, 1, 1);
                void main() {}
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            assertArrayEquals(new int[]{1, 1, 1}, PackProgramSource.prepareCompute(pack, "composite_a").groups(2560, 1440));
        }
    }

    @Test void storageBlocksGetHostBindingsKeyedByTheirBufferObject() throws Exception {
        write("composite.vsh", FULLSCREEN_VERTEX);
        write("composite.fsh", """
                #version 430 compatibility
                layout(std430, binding = 4) buffer SSBO1 { vec4 value; } ssbo;
                uniform sampler2D colortex0;
                in vec2 uv;
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main() { color = texture(colortex0, uv) + ssbo.value; ssbo.value = color; }
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var prepared = PackProgramSource.prepare(pack, "composite", VertexInputs.FULLSCREEN);
            Integer binding = prepared.compiled().bindings().get(VulkanProgramCompiler.storageKey(4));
            assertNotNull(binding, prepared.compiled().bindings().toString());
            assertEquals(4, VulkanProgramCompiler.storageIndex(VulkanProgramCompiler.storageKey(4)));
            assertTrue(SpirvInterface.bindings(prepared.compiled().stages().get(ProgramStage.FRAGMENT)).contains(binding));
            assertTrue(prepared.compiled().sources().get(ProgramStage.FRAGMENT).contains("layout(std430, set = 0, binding = " + binding + ") buffer SSBO1"));
        }
    }

    @Test void passThroughGeometryStageIsFoldedIntoTheFragmentStage() throws Exception {
        write("composite.vsh", """
                #version 430 compatibility
                out DATA { vec4 color; } data_out;
                void main() { data_out.color = gl_Color; gl_Position = ftransform(); }
                """);
        write("composite.gsh", """
                #version 430 compatibility
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                in DATA { vec4 color; } data_in[];
                out DATA { vec4 color; } data_out;
                void main() {
                    for (int i = 0; i < 3; i++) {
                        gl_Position = gl_in[i].gl_Position;
                        data_out.color = data_in[i].color;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
                """);
        write("composite.fsh", """
                #version 430 compatibility
                in DATA { vec4 color; } data_in;
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main() { color = data_in.color; }
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var prepared = PackProgramSource.prepare(pack, "composite", VertexInputs.FULLSCREEN);
            assertEquals(Set.of(ProgramStage.VERTEX, ProgramStage.FRAGMENT), prepared.compiled().stages().keySet());
        }
    }

    /** RV's voxelising shape: a geometry stage that emits more than it receives keeps its stage. */
    @Test void realGeometryStageIsCompiledAndLinkedToBothNeighbours() throws Exception {
        write("composite.vsh", """
                #version 430 compatibility
                out DATA { vec4 color; vec2 uv; } data_out;
                void main() { data_out.color = gl_Color; data_out.uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        write("composite.gsh", """
                #version 430 compatibility
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 6) out;
                in DATA { vec4 color; vec2 uv; } data_in[];
                out DATA { vec4 color; vec2 uv; } data_out;
                void main() {
                    for (int copy = 0; copy < 2; copy++) {
                        for (int i = 0; i < 3; i++) {
                            gl_Position = gl_in[i].gl_Position + vec4(float(copy), 0.0, 0.0, 0.0);
                            data_out.color = data_in[i].color;
                            data_out.uv = data_in[i].uv;
                            EmitVertex();
                        }
                        EndPrimitive();
                    }
                }
                """);
        write("composite.fsh", """
                #version 430 compatibility
                in DATA { vec4 color; vec2 uv; } data_in;
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main() { color = data_in.color * vec4(data_in.uv, 1.0, 1.0); }
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var stages = PackProgramSource.prepare(pack, "composite", VertexInputs.FULLSCREEN).compiled().stages();
            assertEquals(Set.of(ProgramStage.VERTEX, ProgramStage.GEOMETRY, ProgramStage.FRAGMENT), stages.keySet());
            var vertexOut = SpirvInterface.stageLocations(stages.get(ProgramStage.VERTEX), SpirvInterface.OUTPUT);
            var geometryIn = SpirvInterface.stageLocations(stages.get(ProgramStage.GEOMETRY), SpirvInterface.INPUT);
            var geometryOut = SpirvInterface.stageLocations(stages.get(ProgramStage.GEOMETRY), SpirvInterface.OUTPUT);
            var fragmentIn = SpirvInterface.stageLocations(stages.get(ProgramStage.FRAGMENT), SpirvInterface.INPUT);
            for (String varying : List.of("of_DATA_color", "of_DATA_uv")) {
                assertEquals(vertexOut.get(varying).value(), geometryIn.get(varying).value(), varying);
                assertEquals(geometryOut.get(SpirvInterface.GEOMETRY_OUTPUT + varying).value(), fragmentIn.get(varying).value(), varying);
            }
        }
    }

    /** RV's entity shadow caster: the engine's entity values become array inputs and are handed on. */
    @Test void geometryStageForwardsEngineEntityValues() {
        String geometry = """
                #version 460 core
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                in vec4 entityColor;
                flat in int entityId;
                flat out int seen;
                void main() {
                    for (int i = 0; i < 3; i++) {
                        gl_Position = gl_in[i].gl_Position * entityColor.a;
                        seen = entityId;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
                """;
        String forwarded = VulkanProgramCompiler.forwardEngineVaryings(geometry);
        assertTrue(forwarded.contains("in vec4 of_EngineIn_entityColor[];"), forwarded);
        assertTrue(forwarded.contains("flat in int of_EngineIn_entityId[];"), forwarded);
        assertTrue(forwarded.contains("out vec4 " + SpirvInterface.GEOMETRY_OUTPUT + "entityColor;"), forwarded);
        assertTrue(forwarded.contains("#define EmitVertex() {"), forwarded);
        long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
        long options = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize();
        try {
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env(options,
                    org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan, org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_2);
            long result = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(compiler, forwarded,
                    org.lwjgl.util.shaderc.Shaderc.shaderc_geometry_shader, "rv_shadow.gsh", "main", options);
            try {
                assertEquals(org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success,
                        org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result),
                        org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result));
            } finally { org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result); }
        } finally {
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release(options);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);
        }
        String plain = "#version 460 core\nlayout(points) in;\nlayout(points, max_vertices = 1) out;\nvoid main() { EmitVertex(); }\n";
        assertEquals(plain, VulkanProgramCompiler.forwardEngineVaryings(plain), "no engine values: unchanged");
    }

    /** Eclipse's terrain shape: tessellation stages that hand their corner blocks on. */
    @Test void tessellationStagesAreCompiledAndLinkedInOrder() throws Exception {
        write("composite.vsh", """
                #version 430 compatibility
                out DATA { vec4 color; } data_out;
                void main() { data_out.color = gl_Color; gl_Position = ftransform(); }
                """);
        write("composite.tcs", """
                #version 430 compatibility
                layout (vertices=3) out;
                in DATA { vec4 color; } data_in[];
                out DATA { vec4 color; } data_out[];
                void main() {
                    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
                    data_out[gl_InvocationID].color = data_in[gl_InvocationID].color;
                    if (gl_InvocationID == 0) {
                        gl_TessLevelOuter[0] = 1; gl_TessLevelOuter[1] = 1; gl_TessLevelOuter[2] = 1;
                        gl_TessLevelInner[0] = 0;
                    }
                }
                """);
        write("composite.tes", """
                #version 430 compatibility
                layout (triangles, fractional_odd_spacing, ccw) in;
                in DATA { vec4 color; } data_in[];
                out DATA { vec4 color; } data_out;
                void main() {
                    gl_Position = gl_TessCoord.x * gl_in[0].gl_Position + gl_TessCoord.y * gl_in[1].gl_Position + gl_TessCoord.z * gl_in[2].gl_Position;
                    data_out.color = gl_TessCoord.x * data_in[0].color + gl_TessCoord.y * data_in[1].color + gl_TessCoord.z * data_in[2].color;
                }
                """);
        write("composite.fsh", """
                #version 430 compatibility
                in DATA { vec4 color; } data_in;
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main() { color = data_in.color; }
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var compiledProgram = PackProgramSource.prepare(pack, "composite", VertexInputs.FULLSCREEN).compiled();
            var stages = compiledProgram.stages();
            assertEquals(Set.of(ProgramStage.VERTEX, ProgramStage.TESSELLATION_CONTROL, ProgramStage.TESSELLATION_EVALUATION,
                    ProgramStage.FRAGMENT), stages.keySet());
            String color = "of_DATA_color";
            int vertex = SpirvInterface.stageLocations(stages.get(ProgramStage.VERTEX), SpirvInterface.OUTPUT).get(color).value();
            var control = SpirvInterface.stageLocations(stages.get(ProgramStage.TESSELLATION_CONTROL), SpirvInterface.INPUT).get(color).value();
            int controlOut = SpirvInterface.stageLocations(stages.get(ProgramStage.TESSELLATION_CONTROL), SpirvInterface.OUTPUT)
                    .get(dev.vitrail.glsl.GlslTranslator.TESSELLATION_OUTPUT + "DATA_color").value();
            int evaluation = SpirvInterface.stageLocations(stages.get(ProgramStage.TESSELLATION_EVALUATION), SpirvInterface.INPUT).get(color).value();
            int evaluationOut = SpirvInterface.stageLocations(stages.get(ProgramStage.TESSELLATION_EVALUATION), SpirvInterface.OUTPUT)
                    .get(SpirvInterface.GEOMETRY_OUTPUT + color).value();
            int fragment = SpirvInterface.stageLocations(stages.get(ProgramStage.FRAGMENT), SpirvInterface.INPUT).get(color).value();
            assertEquals(vertex, control);
            assertEquals(controlOut, evaluation);
            assertEquals(evaluationOut, fragment);
        }
    }

    @Test void biomeCategoriesAndInverseProjectionResolveInPackFormulas() {
        var plan = PackExpressions.prepare(List.of(
                new dev.vitrail.pack.source.ShaderProperties.CustomUniform(true, "float", "biome_arid",
                        "if(biome_category == CAT_DESERT || biome_category == CAT_MESA, 1.0, 0.0)"),
                new dev.vitrail.pack.source.ShaderProperties.CustomUniform(true, "vec4", "inverse_0",
                        "vec4(gbufferProjectionInverse.0.0, 0.0, 0.0, 0.0)")));
        assertEquals(List.of(), plan.problems());
        assertTrue(plan.resolvedNames().containsAll(Set.of("biome_arid", "inverse_0")));
        assertEquals(12, PackExpressions.CATEGORIES.indexOf("DESERT"));
    }
}
