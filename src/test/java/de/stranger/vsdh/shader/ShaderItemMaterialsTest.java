package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShaderItemMaterialsTest {
    @TempDir Path directory;
    @Test void continuationsVersionBranchesAndNamespacesKeepFirstMaterial() throws Exception {
        var shaders=Files.createDirectories(directory.resolve("shaders"));
        Files.writeString(shaders.resolve("item.properties"),"""
                #if MC_VERSION == 12101
                item.44002 = torch \\
                    mod:colored_lantern
                #else
                item.999 = torch
                #endif
                item.40008 = air
                item.44003 = end_rod invalid:double:namespace torch
                """);
        try(var pack=OpenedPack.open(directory,Map.of(),"")) {
            assertEquals(Map.of("minecraft:torch",44002,"mod:colored_lantern",44002,"minecraft:air",40008,"minecraft:end_rod",44003),ShaderItemMaterials.read(pack));
        }
    }
}
