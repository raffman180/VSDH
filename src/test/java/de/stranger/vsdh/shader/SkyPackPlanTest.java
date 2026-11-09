package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkyPackPlanTest {
    @TempDir Path pack;

    @Test void keepsEndSkyColourAtItsBoundLocationWhenUvIsUnused() throws Exception {
        var shaders=Files.createDirectories(pack.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_skytextured.vsh"), """
                #version 330 compatibility
                out vec4 tint;
                void main() { gl_Position=ftransform(); tint=gl_Color; }
                """);
        Files.writeString(shaders.resolve("gbuffers_skytextured.fsh"), """
                #version 330 compatibility
                in vec4 tint;
                void main() { gl_FragData[0]=tint; }
                """);
        try(var opened=OpenedPack.open(pack,Map.of(),"")) {
            var sky=SkyPackPlan.prepare(opened,"",ShaderChainPlan.prepare(opened,""));
            var end=sky.programs().get(SkyPackPlan.Layout.END);
            assertEquals(VertexInputs.SKY,end.translated().inputs());
            assertEquals(Set.of(0,2),SpirvInterface.inputLocations(end.compiled().stages().get(ProgramStage.VERTEX)));
            assertEquals(Set.of(0),SpirvInterface.inputLocations(sky.programs().get(SkyPackPlan.Layout.CELESTIAL)
                    .compiled().stages().get(ProgramStage.VERTEX)));
            assertFalse(sky.programs().containsKey(SkyPackPlan.Layout.DISC));
            assertTrue(UniformUsage.names(end.compiled(),new Std140Layout(end.translated().uniforms())).contains("of_PassColour"));
        }
    }
}
