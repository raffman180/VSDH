package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShaderProperties.FlipDirective;
import dev.vitrail.pack.target.TargetSchedule;
import org.junit.jupiter.api.Test;

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
        assertEquals(5, layout.targets().size());
        // colortex0: 17*11+8*5+4*2+2*1+1 = 238 texels, two RGBA16F halves.
        // colortex1: 8*5+4*2+2*1+1 = 51; 2/4 each 17*11, also doubled.
        // colortex10: 8*5 texels, two RGBA8UI halves.
        assertEquals((238 + 51 + 187 + 187) * 8L * 2 + 40 * 4L * 2, layout.bytes());
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

    private static ShaderChainPlan fixture() throws Exception {
        var path = Path.of(ShaderChainPlanTest.class.getResource("/shaderpacks/vsdh-chain").toURI());
        try (var pack = OpenedPack.open(path, Map.of(), "")) { return ShaderChainPlan.prepare(pack, ""); }
    }
}
