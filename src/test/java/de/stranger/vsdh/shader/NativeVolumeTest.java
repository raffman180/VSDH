package de.stranger.vsdh.shader;

import dev.vitrail.glsl.GlslTranslator;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.PixelType;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/** Volumes a pack ships stay real 3D textures (12.11.2026), the 2D atlas remains the fallback. */
class NativeVolumeTest {
    @TempDir Path directory;

    private void write(String name, String text) throws Exception {
        Path file = directory.resolve("shaders").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void pack() throws Exception {
        write("shaders.properties", "texture.composite.noisevol = image/noise.dat TEXTURE_3D R8 4 4 4 RED UNSIGNED_BYTE\n");
        Files.createDirectories(directory.resolve("shaders/image"));
        Files.write(directory.resolve("shaders/image/noise.dat"), new byte[64]);
        write("composite.vsh", """
                #version 330 compatibility
                out vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        write("composite.fsh", """
                #version 330 compatibility
                uniform sampler3D noisevol;
                in vec2 uv;
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main() { color = texture(noisevol, vec3(uv, 0.5)) + textureLod(noisevol, vec3(uv, 0.25), 0.0); }
                """);
    }

    private String fragment() throws Exception {
        try (var pack = OpenedPack.open(directory, Map.of(), "")) {
            return PackProgramSource.prepare(pack, "composite", VertexInputs.FULLSCREEN).compiled().sources().get(ProgramStage.FRAGMENT);
        }
    }

    @Test void shippedVolumeKeepsItsThreeDimensionalSampler() throws Exception {
        boolean previous = GlslTranslator.nativeVolumes();
        try {
            pack();
            GlslTranslator.nativeVolumes(true);
            String native3d = fragment();
            assertTrue(native3d.matches("(?s).*uniform sampler3D ofPackTexture_noisevol;.*"), native3d);
            assertFalse(native3d.contains("ofTexture3D_"), "no atlas helper for a real volume");
            assertTrue(native3d.contains("textureLod(ofPackTexture_noisevol"), native3d);

            GlslTranslator.nativeVolumes(false);
            String flat = fragment();
            assertTrue(flat.contains("ofTexture3D_noisevol"), "the atlas fallback still rewrites the lookup");
            assertTrue(flat.matches("(?s).*uniform sampler2D ofPackTexture_noisevol;.*"), flat);
        } finally {
            GlslTranslator.nativeVolumes(previous);
        }
    }

    @Test void volumeFormatsKeepTheBlobsChannels() throws Exception {
        assertEquals(VK_FORMAT_R8_UNORM, PackTexturePlan.volumeFormat(PixelType.UNSIGNED_BYTE, 1));
        assertEquals(VK_FORMAT_R8G8_UNORM, PackTexturePlan.volumeFormat(PixelType.UNSIGNED_BYTE, 2));
        assertEquals(VK_FORMAT_R16_SFLOAT, PackTexturePlan.volumeFormat(PixelType.HALF_FLOAT, 1));
        // Three channels are widened to four with a unit alpha, as GL reads them.
        assertEquals(VK_FORMAT_R16G16B16A16_SFLOAT, PackTexturePlan.volumeFormat(PixelType.HALF_FLOAT, 4));
        assertEquals(VK_FORMAT_R32G32_SFLOAT, PackTexturePlan.volumeFormat(PixelType.FLOAT, 2));
    }
}
