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
            var plan = EntityPackPlan.prepare(pack, "", chain, Set.of(), false, false, message -> fail(message));
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
            assertNull(EntityPackPlan.prepare(pack, "", chain, Set.of(), false, false, warnings::add));
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains("gbuffers_entities"));
        }
    }

    @Test
    void selectsTranslucentProgramAndItsPackPhase(@TempDir Path root) throws Exception {
        writePack(root, """
                #version 130
                void main() { gl_FragColor = vec4(.5); }
                """);
        var shaders = root.resolve("shaders");
        Files.writeString(shaders.resolve("shaders.properties"), "separateEntityDraws=true\n");
        Files.copy(shaders.resolve("gbuffers_entities.vsh"), shaders.resolve("gbuffers_entities_translucent.vsh"));
        Files.copy(shaders.resolve("gbuffers_entities.fsh"), shaders.resolve("gbuffers_entities_translucent.fsh"));
        try (var pack = OpenedPack.open(root, Map.of(), "")) {
            var chain = ShaderChainPlan.prepare(pack, "");
            var plan = EntityPackPlan.prepare(pack, "", chain, Set.of(), false, false, message -> fail(message));
            assertNotNull(plan);
            assertTrue(plan.lateTransparency());
            assertNotNull(plan.translucent());
            assertEquals("gbuffers_entities_translucent", plan.translucent().path());
        }
    }

    @Test
    void packWithoutTranslucentProgramDrawsTranslucentEntitiesWithItsEntityProgram(@TempDir Path root) throws Exception {
        // Iris: ProgramId.EntitiesTrans falls back to Entities. Player skins are entity_translucent; with the
        // standard shader in a deferred pack's targets the player's body was invisible.
        writePack(root, """
                #version 130
                void main() { gl_FragColor = vec4(.5); }
                """);
        try (var pack = OpenedPack.open(root, Map.of(), "")) {
            var chain = ShaderChainPlan.prepare(pack, "");
            var plan = EntityPackPlan.prepare(pack, "", chain, Set.of(), false, false, message -> fail(message));
            assertNotNull(plan);
            assertSame(plan.cutout(), plan.translucent());
            assertTrue(plan.translucent().translated().stages().get(ProgramStage.FRAGMENT).text().contains("discard"),
                    "the layer's 0.1 alpha test");
            assertEquals(2, plan.programs().size(), "the shared program is listed once");
        }
    }

    @Test
    void translucentFallbackRule() {
        // Nothing to fall back from; the switch off; a program the pack has but that was refused keeps the standard draw.
        assertNull(EntityPackPlan.translucentOrFallback(null, false, null, true));
        assertNull(EntityPackPlan.translucentOrFallback(null, false, null, false));
        assertNull(EntityPackPlan.translucentOrFallback(null, true, null, true));
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
