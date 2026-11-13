package de.stranger.vsdh.shader;

import dev.vitrail.pack.option.PackOption;
import dev.vitrail.pack.source.ShaderPackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Settings kept in an included header of any extension are options, as under Iris (Miniature's shader.h). */
class IncludedOptionIndexTest {
    @TempDir Path directory;

    @Test void indexesOptionsOfIncludedFilesWhateverTheirExtension() throws Exception {
        Path shaders = Files.createDirectories(directory.resolve("shaders"));
        Files.createDirectories(shaders.resolve("world0"));
        Files.createDirectories(shaders.resolve("common"));
        Files.writeString(shaders.resolve("shader.h"), """
                #define WATER_BRIGHTNESS 0.8 //[0.0 0.4 0.8]
                #define ENABLE_FOG
                #include "common/nested.hpp"
                """);
        Files.writeString(shaders.resolve("common/nested.hpp"), """
                #define NESTED_LEVEL 2 //[1 2 3]
                """);
        Files.writeString(shaders.resolve("world0/gbuffers_water.fsh"), """
                #version 120
                #include "/shader.h"
                #ifdef ENABLE_FOG
                #endif
                void main() { gl_FragData[0] = vec4(WATER_BRIGHTNESS); }
                """);
        Files.writeString(shaders.resolve("never-included.h"), """
                #define UNUSED_HEADER_OPTION 1 //[1 2]
                """);
        try (var source = ShaderPackSource.open(directory)) {
            var index = source.options();
            var water = index.get("WATER_BRIGHTNESS").orElseThrow();
            assertEquals(PackOption.Kind.VALUE, water.kind());
            assertEquals(java.util.List.of("0.0", "0.4", "0.8"), water.values());
            assertEquals(PackOption.Kind.TOGGLE, index.get("ENABLE_FOG").orElseThrow().kind());
            assertTrue(index.get("NESTED_LEVEL").isPresent(), "nested include through a relative path");
            assertTrue(index.get("UNUSED_HEADER_OPTION").isEmpty(), "a header nothing includes declares nothing");
        }
    }
}
