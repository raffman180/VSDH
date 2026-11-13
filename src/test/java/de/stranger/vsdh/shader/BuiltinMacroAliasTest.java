package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class BuiltinMacroAliasTest {
    @TempDir Path directory;

    /** Photon's bloom shape: a macro naming the texture() builtin, used as a call at the site. */
    @Test void macroAliasOfTheTextureBuiltinStillCompiles() throws Exception {
        Files.createDirectories(directory.resolve("shaders"));
        Files.writeString(directory.resolve("shaders/composite.vsh"), """
                #version 330 compatibility
                out vec2 uv;
                void main() {
                    uv = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(directory.resolve("shaders/composite.fsh"), """
                #version 400 compatibility
                #define UPSAMPLING_FILTER texture // [texture bicubic_filter]
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec3 bloom;
                in vec2 uv;
                uniform sampler2D colortex0;
                void main() {
                    bloom = UPSAMPLING_FILTER(colortex0, uv).rgb;
                }
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            assertDoesNotThrow(() -> PackProgramSource.prepare(pack, "composite", VertexInputs.FULLSCREEN));
        }
    }

    /** Pegasus' shape: the pack's own sampler called texture, named again inside a macro body. */
    @Test void packSamplerNamedTextureIsRenamedInsideMacrosToo() throws Exception {
        Files.createDirectories(directory.resolve("shaders"));
        Files.writeString(directory.resolve("shaders/composite.vsh"), """
                #version 120
                varying vec2 uv;
                void main() {
                    uv = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(directory.resolve("shaders/composite.fsh"), """
                #version 120
                #define ALBEDO_SOURCE texture
                uniform sampler2D texture;
                varying vec2 uv;
                void main() {
                    gl_FragData[0] = texture2DLod(ALBEDO_SOURCE, uv, 0.0);
                }
                """);
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            assertDoesNotThrow(() -> PackProgramSource.prepare(pack, "composite", VertexInputs.FULLSCREEN));
        }
    }
}
