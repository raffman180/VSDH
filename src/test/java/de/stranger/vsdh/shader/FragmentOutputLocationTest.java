package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FragmentOutputLocationTest {
    @TempDir Path directory;

    /** Super Duper Vanilla's composite1 shape: an explicit location nought beside an unplaced output. */
    @Test void unplacedOutputTakesTheLowestFreeLocationLikeAGlLinker() throws Exception {
        Files.createDirectories(directory.resolve("shaders"));
        Files.writeString(directory.resolve("shaders/composite.vsh"), """
                #version 330 compatibility
                out vec2 texCoord;
                void main() {
                    texCoord = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(directory.resolve("shaders/composite.fsh"), """
                #version 330 compatibility
                /* RENDERTARGETS: 4,5 */
                layout(location = 0) out vec3 sceneColOut;
                out vec4 temporalDataOut;
                in vec2 texCoord;
                void main() {
                    sceneColOut = vec3(texCoord, 0.0);
                    temporalDataOut = vec4(sceneColOut, 1.0);
                }
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            var prepared = PackProgramSource.prepare(pack, "composite", VertexInputs.FULLSCREEN);
            var outputs = SpirvInterface.stageLocations(prepared.compiled().stages().get(ProgramStage.FRAGMENT),
                    SpirvInterface.OUTPUT);
            assertEquals(0, outputs.get("sceneColOut").value());
            assertEquals(1, outputs.get("temporalDataOut").value());
        }
    }
}
