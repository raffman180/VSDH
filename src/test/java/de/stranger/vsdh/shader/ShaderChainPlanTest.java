package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShaderProperties.FlipDirective;
import dev.vitrail.pack.target.TargetSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShaderChainPlanTest {
    @Test void actualPackDeclarationsProduceSeparatedReadsWritesAndPersistentHistory() throws Exception {
        var chain = fixture();
        assertEquals(List.of(2), chain.copyBack());
        assertEquals(Set.of("depthtex0", "depthtex1", "depthtex2"), chain.externalSamplers());
        for (var stage : ShaderChainPlan.Stage.values()) for (var pass : chain.stages().get(stage)) {
            var step = chain.targets().schedule().step(pass.name()).orElseThrow();
            for (var output : pass.outputs()) assertNotEquals(step.read(output.target()), output.side(), pass.name());
        }
        assertEquals(TargetSchedule.Side.ALT, chain.targets().schedule().step("gbuffers_terrain").orElseThrow().write(0));
        assertEquals(TargetSchedule.Side.MAIN, chain.targets().schedule().stepAfterDeferred("gbuffers_terrain").orElseThrow().write(0));
        assertEquals(Set.of(1), chain.stages().get(ShaderChainPlan.Stage.DEFERRED).getFirst().mipmaps());
    }

    @Test void budgetIncludesBothHalvesAndEveryMipBeforeAnyGpuAllocation() throws Exception {
        var chain = fixture();
        var layout = ShaderTargetLayout.of(chain.targets(), 17, 11, 16384, 1 << 20);
        assertEquals(6, layout.targets().size());
        // colortex0: 17*11+8*5+4*2+2*1+1 = 238 texels, two RGBA16F halves.
        // colortex1: 8*5+4*2+2*1+1 = 51; 2/4 each 17*11, also doubled.
        // colortex10/11: 8*5 texels each, two RGBA8UI/RGBA8I halves per target.
        assertEquals((238 + 51 + 187 + 187) * 8L * 2 + (40 + 40) * 4L * 2, layout.bytes());
        assertThrows(IllegalArgumentException.class, () -> ShaderTargetLayout.of(chain.targets(), 17, 11, 16384, layout.bytes() - 1));
        assertThrows(IllegalArgumentException.class, () -> ShaderTargetLayout.of(chain.targets(), 17, 11, 16, 1 << 20));
        assertThrows(IllegalArgumentException.class, () -> ShaderTargetLayout.of(chain.targets(), 0, 11, 16384, 1 << 20));
    }

    @Test void explicitFlipAndEmptyStageDirectivesAreNotLost() {
        var schedule = TargetSchedule.of(List.of(new TargetSchedule.Step("composite", List.of(0), true),
                new TargetSchedule.Step("final", List.of(), true)), List.of(
                new FlipDirective("deferred_pre", "colortex2", true),
                new FlipDirective("composite", "colortex0", false)));
        var composite = schedule.step("composite").orElseThrow();
        assertEquals(TargetSchedule.Side.ALT, composite.write(0));
        assertEquals(TargetSchedule.Side.MAIN, schedule.step("final").orElseThrow().read(0));
        assertEquals(TargetSchedule.Side.ALT, composite.read(2));
        assertEquals(Set.of(0, 2), schedule.doubled());
    }

    @Test void optimizedProgramPreservesAHoleBeforeTheActiveTextureBinding() throws Exception {
        var path = Path.of(ShaderChainPlanTest.class.getResource("/shaderpacks/vsdh-diagnostic").toURI());
        try (var pack = OpenedPack.open(path, Map.of(), "")) {
            var program = PackProgramSource.prepare(pack, "final", dev.vitrail.glsl.VertexInputs.FULLSCREEN);
            var active = new java.util.HashSet<Integer>();
            program.compiled().stages().values().forEach(bytes -> active.addAll(SpirvInterface.bindings(bytes)));
            assertEquals(1, program.compiled().bindings().get("unusedShadow"));
            assertEquals(2, program.compiled().bindings().get("colortex1"));
            assertEquals(Set.of(0, 2), active);
        }
    }

    @Test void mismatchedIntegerSamplerIsRejectedDuringPackPreparation(@TempDir Path packRoot) throws Exception {
        var shaders = Files.createDirectory(packRoot.resolve("shaders"));
        Files.writeString(shaders.resolve("final.vsh"), """
                #version 130
                void main() { gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 130
                uniform usampler2D colortex0;
                /*
                const int colortex0Format = RGBA8I;
                */
                void main() { gl_FragColor = vec4(texelFetch(colortex0, ivec2(0), 0)); }
                """);
        try (var pack = OpenedPack.open(packRoot, Map.of(), "")) {
            var error = assertThrows(IOException.class, () -> ShaderChainPlan.prepare(pack, ""));
            assertEquals("Incompatible shader input final/colortex0: usampler2D cannot sample RGBA8_SINT", error.getMessage());
        }
    }

    /** Iris reads setup.csh, setup1.csh ... setup99.csh and runs each once, in index order. */
    @Test void everyNumberedSetupComputeRunsInIndexOrder(@TempDir Path packRoot) throws Exception {
        var shaders = Files.createDirectory(packRoot.resolve("shaders"));
        Files.writeString(shaders.resolve("final.vsh"), """
                #version 130
                void main() { gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 130
                void main() { gl_FragColor = vec4(1.0); }
                """);
        for (String name : List.of("setup10", "setup2", "setup", "setup1"))
            Files.writeString(shaders.resolve(name + ".csh"), """
                    #version 430
                    layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;
                    const ivec3 workGroups = ivec3(17, 17, 17);
                    void main() {}
                    """);
        try (var pack = OpenedPack.open(packRoot, Map.of(), "")) {
            var chain = ShaderChainPlan.prepare(pack, "");
            assertEquals(List.of("setup", "setup1", "setup2", "setup10"), chain.setup().stream().map(ShaderChainPlan.Compute::name).toList());
            for (var compute : chain.setup()) assertArrayEquals(new int[]{17, 17, 17}, compute.program().groups(2560, 1440));
        }
    }

    @Test void computeHostsOfOneFamilyAreSortedByIndex() {
        var hosts = List.of("setup10", "composite", "setup", "shadowcomp1", "setup2", "shadowcomp", "setup1");
        assertEquals(List.of("setup", "setup1", "setup2", "setup10"), ShaderChainPlan.hostsOf(hosts, "setup"));
        assertEquals(List.of("shadowcomp", "shadowcomp1"), ShaderChainPlan.hostsOf(hosts, "shadowcomp"));
    }

    private static ShaderChainPlan fixture() throws Exception {
        var path = Path.of(ShaderChainPlanTest.class.getResource("/shaderpacks/vsdh-chain").toURI());
        try (var pack = OpenedPack.open(path, Map.of(), "")) { return ShaderChainPlan.prepare(pack, ""); }
    }
}
