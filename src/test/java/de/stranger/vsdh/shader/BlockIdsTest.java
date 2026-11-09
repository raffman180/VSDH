package de.stranger.vsdh.shader;

import dev.vitrail.pack.id.BlockIds;
import dev.vitrail.pack.source.ShaderPackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockIdsTest {
    @TempDir Path directory;

    @Test void preservesConditionalOrderContinuationsTagsAndOrPredicates() throws Exception {
        Files.createDirectories(directory.resolve("shaders"));
        Files.writeString(directory.resolve("shaders/block.properties"), """
                #if MC_VERSION >= 12101
                block.100=minecraft:oak_log:axis=x,z \\
                    minecraft:oak_leaves:persistent=true
                #else
                block.900=stone
                #endif
                block.101=oak_log block.102=%minecraft:logs
                """);
        try (var source = ShaderPackSource.open(directory)) {
            var parsed = BlockIds.read(source, Map.of("MC_VERSION", "12101"));
            assertEquals(java.util.List.of(100,100,101,102), parsed.entries().stream().map(BlockIds.Entry::id).toList());
            assertEquals("minecraft:oak_log", parsed.entries().getFirst().name());
            assertEquals("x,z", parsed.entries().getFirst().predicates().get("axis"));
            assertTrue(parsed.entries().getLast().tag());
            assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());
        }
    }

    @Test void malformedIdsAndFiltersAreReportedInsteadOfInventingMappings() throws Exception {
        Files.createDirectories(directory.resolve("shaders"));
        Files.writeString(directory.resolve("shaders/block.properties"), """
                block.2147483648=stone
                block.2=:
                block.3=minecraft:stone:bad
                """);
        try (var source = ShaderPackSource.open(directory)) {
            assertEquals(3, BlockIds.read(source, Map.of()).problems().size());
        }
    }
}
