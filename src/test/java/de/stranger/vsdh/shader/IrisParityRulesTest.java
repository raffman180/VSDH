package de.stranger.vsdh.shader;

import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Rules found by comparing VSDH and Iris 1.8 on the same scene (11.11.2026). */
class IrisParityRulesTest {
    @TempDir Path directory;

    private void program(String name) throws Exception {
        Files.createDirectories(directory.resolve("shaders"));
        Files.writeString(directory.resolve("shaders/" + name + ".vsh"), "#version 330 compatibility\nvoid main() { gl_Position = ftransform(); }\n");
        Files.writeString(directory.resolve("shaders/" + name + ".fsh"), "#version 330 compatibility\nvoid main() { gl_FragData[0] = vec4(1.0); }\n");
    }

    /** Iris serves solid with gbuffers_terrain_solid and both cutout layers with gbuffers_terrain_cutout. */
    @Test void terrainLayersUseTheirOwnProgramsThenTheFallbackTree() throws Exception {
        program("gbuffers_terrain");
        program("gbuffers_terrain_solid");
        program("gbuffers_terrain_cutout");
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            assertEquals("gbuffers_terrain_solid", WorldPackPlan.terrainProgram(pack, "", "solid"));
            assertEquals("gbuffers_terrain_cutout", WorldPackPlan.terrainProgram(pack, "", "cutout"));
            assertEquals("gbuffers_terrain_cutout", WorldPackPlan.terrainProgram(pack, "", "cutout_mipped"));
            assertEquals("gbuffers_terrain", WorldPackPlan.terrainProgram(pack, "", "translucent"));
        }
    }

    /** A pack shipping only gbuffers_textured still draws its terrain with it, as under Iris. */
    @Test void terrainFallsBackToTexturedPrograms() throws Exception {
        program("gbuffers_textured");
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            for (String layer : new String[]{"solid", "cutout", "cutout_mipped", "translucent", "tripwire"})
                assertEquals("gbuffers_textured", WorldPackPlan.terrainProgram(pack, "", layer));
        }
    }

    @Test void vignetteAndUnderwaterOverlayAreOffUnlessThePackAsks() throws Exception {
        program("gbuffers_basic");
        Files.writeString(directory.resolve("shaders/shaders.properties"), "underwaterOverlay = true\n");
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var defines = pack.settings().globalDefines(pack.options());
            assertFalse(pack.properties().vignette(defines), "Iris default: no vignette under a pack");
            assertTrue(pack.properties().underwaterOverlay(defines));
        }
        Files.writeString(directory.resolve("shaders/shaders.properties"), "vignette=false\nvignette = 1\n");
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var defines = pack.settings().globalDefines(pack.options());
            assertTrue(pack.properties().vignette(defines), "the last live line wins");
            assertFalse(pack.properties().underwaterOverlay(defines));
        }
    }

    @Test void indirectDispatchPointersAreReadOnLiveLines() throws Exception {
        program("gbuffers_basic");
        Files.writeString(directory.resolve("shaders/shaders.properties"), """
                bufferObject.0 = 16
                indirect.composite_b = 0 4
                indirect.broken = zero 0
                #ifdef NEVER
                indirect.deferred_a = 0 0
                #endif
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var pointers = pack.properties().indirectDispatches(pack.settings().globalDefines(pack.options()));
            assertEquals(Map.of("composite_b", new dev.vitrail.pack.source.ShaderProperties.IndirectPointer(0, 4)), pointers);
        }
    }

    /** Announced like Iris; each has a working implementation behind it (see EngineDefines.FEATURES). */
    @Test void implementedIrisFeaturesAreAnnounced() {
        for (String feature : new String[]{"HIGHER_SHADOWCOLOR", "BLOCK_EMISSION_ATTRIBUTE", "ENTITY_TRANSLUCENT",
                "COMPUTE_SHADERS", "SSBO", "CUSTOM_IMAGES"})
            assertTrue(EngineDefines.FEATURES.contains(feature), feature);
    }
}
