package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.glsl.VulkanTerrainVertex;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VulkanTerrainTranslationTest {
    @Test void explicitLocationsPreserveBothColorContractsAfterOptimisation() throws Exception {
        Path path = Path.of(getClass().getResource("/shaderpacks/vsdh-terrain").toURI());
        try (var pack = OpenedPack.open(path, Map.of(), "")) {
            for (var contract : java.util.Arrays.stream(VertexInputs.values()).filter(VertexInputs::vulkanTerrain).toList()) {
                var prepared = PackProgramSource.prepare(pack, "gbuffers_terrain", contract);
                var active = SpirvInterface.inputLocations(prepared.compiled().stages().get(ProgramStage.VERTEX));
                assertFalse(active.isEmpty());
                var expected = new java.util.HashSet<>(java.util.Set.of(0,3,4,5,6,7,8));
                if (!contract.oldLighting() || contract.separateAo()) expected.add(9);
                if (!contract.separateAo()) expected.add(1);
                assertEquals(expected, active);
                String vertex = prepared.compiled().sources().get(ProgramStage.VERTEX);
                assertTrue(vertex.contains("layout(push_constant) uniform VsdhChunk"));
                assertFalse(vertex.contains("OfSodiumRegion"));
            }
        }
    }
}
