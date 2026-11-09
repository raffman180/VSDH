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

class DistantPackPlanTest {
    @TempDir Path directory;

    @Test void expansionAndShaderCompilerShareThePerPackDhFlag() throws Exception {
        var shaders = Files.createDirectory(directory.resolve("shaders"));
        Files.writeString(shaders.resolve("dh.glsl"), "float enabledBranch(){return 1.0;}\n");
        Files.writeString(shaders.resolve("plain.glsl"), "float enabledBranch(){return 0.0;}\n");
        Files.writeString(shaders.resolve("final.vsh"), "#version 330 compatibility\nvoid main(){gl_Position=ftransform();}\n");
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 330 compatibility
                #ifdef DISTANT_HORIZONS
                #include "/dh.glsl"
                #else
                #include "/plain.glsl"
                #endif
                void main(){gl_FragColor=vec4(enabledBranch());}
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var dh = new OpenedPack(pack.packPath(), pack.source(), pack.options(), pack.properties(),
                    pack.settings().withDistantHorizons());
            var distant = PackProgramSource.prepare(dh, "final", VertexInputs.FULLSCREEN);
            var ordinary = PackProgramSource.prepare(pack, "final", VertexInputs.FULLSCREEN);
            assertTrue(distant.compiled().sources().get(ProgramStage.FRAGMENT).contains("#define DISTANT_HORIZONS"));
            assertFalse(ordinary.compiled().sources().get(ProgramStage.FRAGMENT).contains("#define DISTANT_HORIZONS"));
        }
    }

    @Test void dhProgramUsesThePackedBufferLocationsAndPushConstant() throws Exception {
        var shaders = Files.createDirectory(directory.resolve("shaders"));
        Files.writeString(shaders.resolve("dh_terrain.vsh"), """
                #version 330 compatibility
                out vec4 testValue;
                void main() {
                    gl_Position=ftransform();
                    testValue=gl_Color+vec4(gl_MultiTexCoord2.x, float(dhMaterialId), gl_Normal.y, 0.0);
                }
                """);
        Files.writeString(shaders.resolve("dh_terrain.fsh"), """
                #version 330 compatibility
                in vec4 testValue;
                void main(){gl_FragColor=testValue;}
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var program = PackProgramSource.prepare(pack, "dh_terrain", VertexInputs.DISTANT).compiled();
            assertEquals(Set.of(0, 1, 2), SpirvInterface.inputLocations(program.stages().get(ProgramStage.VERTEX)));
            assertFalse(program.bindings().containsKey("OfDistantSection"));
            assertTrue(program.sources().get(ProgramStage.VERTEX).contains("layout(push_constant)"));
        }
    }
}
