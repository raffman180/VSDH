package de.stranger.vsdh.shader;

import dev.vitrail.glsl.ProgramTranslator.TranslatedProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpirvCacheTest {
    @AfterEach void off() { SpirvCache.uninstall(); }

    private static byte[] spirv(int words, int filler) {
        var buffer = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(SpirvCache.SPIRV_MAGIC);
        while (buffer.hasRemaining()) buffer.putInt(filler);
        return buffer.array();
    }

    private static SpirvCache.Entry entry() {
        var stages = new EnumMap<ProgramStage, byte[]>(ProgramStage.class);
        var sources = new EnumMap<ProgramStage, String>(ProgramStage.class);
        stages.put(ProgramStage.FRAGMENT, spirv(40, 7));
        stages.put(ProgramStage.VERTEX, spirv(12, 3));
        sources.put(ProgramStage.FRAGMENT, "#version 450\nvoid main() { /* Größe */ }\n".repeat(50));
        sources.put(ProgramStage.VERTEX, "");
        return new SpirvCache.Entry(stages, sources);
    }

    private static Map<String, Integer> bindings(String... names) {
        var bindings = new LinkedHashMap<String, Integer>();
        for (String name : names) bindings.put(name, bindings.size());
        return bindings;
    }

    private static String key(String edition, String name, boolean fullscreen, Map<String, Integer> bindings, String vertex, String fragment) {
        var texts = new EnumMap<ProgramStage, String>(ProgramStage.class);
        if (vertex != null) texts.put(ProgramStage.VERTEX, vertex);
        if (fragment != null) texts.put(ProgramStage.FRAGMENT, fragment);
        return SpirvCache.key(edition, name, fullscreen, bindings, texts);
    }

    @Test void theKeyIsTheSameForTheSameInput() {
        assertEquals(key("e", "world0/composite", true, bindings("OfGlobals", "colortex0"), "v", "f"),
                key("e", "world0/composite", true, bindings("OfGlobals", "colortex0"), "v", "f"));
    }

    @Test void everyInputOfACompilationChangesTheKey() {
        String base = key("e", "world0/composite", true, bindings("OfGlobals", "colortex0"), "v", "f");
        assertNotEquals(base, key("other build", "world0/composite", true, bindings("OfGlobals", "colortex0"), "v", "f"));
        assertNotEquals(base, key("e", "world1/composite", true, bindings("OfGlobals", "colortex0"), "v", "f"));
        assertNotEquals(base, key("e", "world0/composite", false, bindings("OfGlobals", "colortex0"), "v", "f"));
        assertNotEquals(base, key("e", "world0/composite", true, bindings("colortex0", "OfGlobals"), "v", "f")); // other numbers
        assertNotEquals(base, key("e", "world0/composite", true, bindings("OfGlobals"), "v", "f"));
        assertNotEquals(base, key("e", "world0/composite", true, bindings("OfGlobals", "colortex0"), "v2", "f"));
        assertNotEquals(base, key("e", "world0/composite", true, bindings("OfGlobals", "colortex0"), "v", "f2"));
        assertNotEquals(base, key("e", "world0/composite", true, bindings("OfGlobals", "colortex0"), "v", null));
        // The same text as another stage is another program.
        var compute = new EnumMap<ProgramStage, String>(ProgramStage.class);
        compute.put(ProgramStage.COMPUTE, "v");
        var vertex = new EnumMap<ProgramStage, String>(ProgramStage.class);
        vertex.put(ProgramStage.VERTEX, "v");
        assertNotEquals(SpirvCache.key("e", "p", false, Map.of(), compute), SpirvCache.key("e", "p", false, Map.of(), vertex));
    }

    @Test void twoSplitsOfTheSameCharactersAreTwoKeys() {
        assertNotEquals(key("e", "ab", false, Map.of(), "c", null), key("e", "a", false, Map.of(), "bc", null));
        assertNotEquals(key("ea", "b", false, Map.of(), "c", null), key("e", "ab", false, Map.of(), "c", null));
    }

    @Test void aProgramComesBackAsItWasWritten() {
        var written = entry();
        var read = SpirvCache.decode(SpirvCache.encode(written));
        assertNotNull(read);
        assertEquals(written.stages().keySet(), read.stages().keySet());
        for (var stage : written.stages().keySet()) {
            assertArrayEquals(written.stages().get(stage), read.stages().get(stage));
            assertEquals(written.sources().get(stage), read.sources().get(stage));
        }
    }

    @Test void anyDamagedByteIsAMiss() {
        byte[] file = SpirvCache.encode(entry());
        for (int at = 0; at < file.length; at += Math.max(1, file.length / 97)) {
            byte[] damaged = file.clone();
            damaged[at] ^= 0x10;
            assertNull(SpirvCache.decode(damaged), "flipped byte " + at);
        }
        assertNull(SpirvCache.decode(Arrays.copyOf(file, file.length - 1)));
        assertNull(SpirvCache.decode(Arrays.copyOf(file, file.length / 2)));
        assertNull(SpirvCache.decode(Arrays.copyOf(file, file.length + 4)));
        assertNull(SpirvCache.decode(new byte[0]));
        assertNull(SpirvCache.decode(new byte[40]));
    }

    @Test void bytecodeThatIsNotSpirvIsRefusedEvenWithAValidDigest() {
        var wrongMagic = new EnumMap<ProgramStage, byte[]>(ProgramStage.class);
        wrongMagic.put(ProgramStage.VERTEX, new byte[40]);
        assertNull(SpirvCache.decode(SpirvCache.encode(new SpirvCache.Entry(wrongMagic, Map.of(ProgramStage.VERTEX, "")))));
        var unaligned = new EnumMap<ProgramStage, byte[]>(ProgramStage.class);
        unaligned.put(ProgramStage.VERTEX, Arrays.copyOf(spirv(8, 1), 30));
        assertNull(SpirvCache.decode(SpirvCache.encode(new SpirvCache.Entry(unaligned, Map.of(ProgramStage.VERTEX, "")))));
        var tooShort = new EnumMap<ProgramStage, byte[]>(ProgramStage.class);
        tooShort.put(ProgramStage.VERTEX, spirv(4, 1));
        assertNull(SpirvCache.decode(SpirvCache.encode(new SpirvCache.Entry(tooShort, Map.of(ProgramStage.VERTEX, "")))));
    }

    @Test void nothingIsEvictedWithinTheCap() {
        var stored = List.of(new SpirvCache.Stored("a.spv", 40, 1), new SpirvCache.Stored("b.spv", 60, 2));
        assertEquals(List.of(), SpirvCache.evictions(stored, 100, 75));
    }

    @Test void theLeastRecentlyUsedGoFirstUntilTheTargetIsMet() {
        var stored = new ArrayList<>(List.of(
                new SpirvCache.Stored("newest.spv", 40, 400),
                new SpirvCache.Stored("oldest.spv", 30, 100),
                new SpirvCache.Stored("old.spv", 30, 200),
                new SpirvCache.Stored("new.spv", 20, 300)));
        // 120 bytes over a cap of 100; target 75: oldest (90 left), old (60 left), stop.
        assertEquals(List.of("oldest.spv", "old.spv"), SpirvCache.evictions(stored, 100, 75));
        Collections.reverse(stored);
        assertEquals(List.of("oldest.spv", "old.spv"), SpirvCache.evictions(stored, 100, 75), "listing order must not matter");
    }

    @Test void filesUsedAtTheSameTimeGoInNameOrder() {
        var stored = List.of(new SpirvCache.Stored("b.spv", 50, 7), new SpirvCache.Stored("a.spv", 50, 7), new SpirvCache.Stored("c.spv", 50, 7));
        assertEquals(List.of("a.spv", "b.spv"), SpirvCache.evictions(stored, 100, 50));
    }

    @Test void aStoredProgramIsFoundAndADamagedFileIsNot(@TempDir Path directory) throws Exception {
        SpirvCache.install(directory.resolve("spirv"));
        assertTrue(SpirvCache.active());
        SpirvCache.resetCounters();
        String key = key("e", "p", false, Map.of(), "v", "f");
        assertNull(SpirvCache.lookup(key));
        SpirvCache.store(key, entry());
        var found = SpirvCache.lookup(key);
        assertNotNull(found);
        assertArrayEquals(entry().stages().get(ProgramStage.FRAGMENT), found.stages().get(ProgramStage.FRAGMENT));
        assertEquals(1, SpirvCache.hits());
        assertEquals(1, SpirvCache.misses());
        // No temporary file is left behind by an atomic write.
        try (var files = Files.list(directory.resolve("spirv"))) {
            assertEquals(List.of(key + ".spv"), files.map(file -> file.getFileName().toString()).toList());
        }
        Path file = directory.resolve("spirv").resolve(key + ".spv");
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 9)); // a write cut short by a crash
        assertNull(SpirvCache.lookup(key));
        assertEquals(1, SpirvCache.refused());
        SpirvCache.store(key, entry()); // the compile that follows a miss repairs the file
        assertNotNull(SpirvCache.lookup(key));
    }

    @Test void installRemovesUnfinishedWritesAndUninstalledMeansOff(@TempDir Path directory) throws Exception {
        Files.write(directory.resolve("abc-1-2.part"), new byte[10]);
        SpirvCache.install(directory);
        assertFalse(Files.exists(directory.resolve("abc-1-2.part")));
        SpirvCache.uninstall();
        assertFalse(SpirvCache.active());
        String key = key("e", "p", false, Map.of(), "v", "f");
        SpirvCache.store(key, entry());
        assertNull(SpirvCache.lookup(key));
        assertNull(SpirvCache.keyFor("p", false, Map.of(), Map.of()));
    }

    @Test void aProgramReadBackFromDiskEqualsTheCompiledOne(@TempDir Path directory) {
        var program = new TranslatedProgram(Map.of(
                ProgramStage.VERTEX, unit(ProgramStage.VERTEX, """
                        #version 450
                        out vec2 texcoord;
                        void main() { texcoord = vec2(1.0); gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }
                        """),
                ProgramStage.FRAGMENT, unit(ProgramStage.FRAGMENT, """
                        #version 450
                        in vec2 texcoord;
                        layout(location = 0) out vec4 color;
                        void main() { color = vec4(texcoord, 0.0, 1.0); }
                        """)), List.of(), List.of(), Set.of(), Map.of(), VertexInputs.WORLD);
        var uncached = VulkanProgramCompiler.compile("cache_round_trip", program);
        SpirvCache.install(directory);
        SpirvCache.resetCounters();
        var compiled = VulkanProgramCompiler.compile("cache_round_trip", program);
        var fromDisk = VulkanProgramCompiler.compile("cache_round_trip", program);
        assertEquals(1, SpirvCache.misses());
        assertEquals(1, SpirvCache.hits());
        for (var result : List.of(compiled, fromDisk)) {
            assertEquals(uncached.stages().keySet(), result.stages().keySet());
            for (var stage : uncached.stages().keySet()) {
                assertArrayEquals(uncached.stages().get(stage), result.stages().get(stage));
                assertEquals(uncached.sources().get(stage), result.sources().get(stage));
            }
            assertEquals(uncached.bindings(), result.bindings());
        }
        // Another name is another program: shaderc writes the name into the module.
        VulkanProgramCompiler.compile("cache_round_trip_2", program);
        assertEquals(2, SpirvCache.misses());
    }

    private static TranslatedUnit unit(ProgramStage stage, String source) {
        return new TranslatedUnit("cache_round_trip", stage, source, null, List.of(), List.of(), List.of());
    }
}
