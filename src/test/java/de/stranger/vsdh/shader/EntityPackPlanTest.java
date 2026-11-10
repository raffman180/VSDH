package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.model.ProgramStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EntityPackPlanTest {
    @Test
    void preparesBothAlphaVariantsAndPreservesMappedMaterialIds(@TempDir Path root) throws Exception {
        writePack(root, """
                #version 130
                uniform int entityId;
                void main() { gl_FragColor = vec4(float(entityId) / 65535.0, 0, 0, 1); }
                """);
        try (var pack = OpenedPack.open(root, Map.of(), "")) {
            var chain = ShaderChainPlan.prepare(pack, "");
            var plan = EntityPackPlan.prepare(pack, "", chain, Set.of(), false, message -> fail(message));
            assertNotNull(plan);
            assertEquals(Map.of("minecraft:iron_golem", 50012, "example:chicken", 50084), plan.materials());
            assertFalse(plan.solid().compiled().bindings().containsKey("DynamicTransforms"));
            assertFalse(plan.solid().translated().stages().get(ProgramStage.FRAGMENT).text().contains("discard"));
            assertTrue(plan.cutout().translated().stages().get(ProgramStage.FRAGMENT).text().contains("discard"));
        }
    }

    @Test
    void unsupportedEntitySamplerKeepsStandardEntityRendering(@TempDir Path root) throws Exception {
        writePack(root, """
                #version 130
                uniform sampler3D volume;
                void main() { gl_FragColor = texture(volume, vec3(.5)); }
                """);
        try (var pack = OpenedPack.open(root, Map.of(), "")) {
            var chain = ShaderChainPlan.prepare(pack, "");
            var warnings = new java.util.ArrayList<String>();
            assertNull(EntityPackPlan.prepare(pack, "", chain, Set.of(), false, warnings::add));
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains("gbuffers_entities"));
        }
    }

    private static void writePack(Path root, String fragment) throws Exception {
        var shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 130
                void main() { gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), fragment);
        Files.writeString(shaders.resolve("entity.properties"), """
                entity.50012=iron_golem
                entity.50084=example:chicken
                """);
    }
}
