package de.stranger.vsdh.dh330;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Dh330MappingTest {
    private static final String CONFIG = "com/seibel/distanthorizons/core/config/Config";
    private static final String QUEUE = "com/seibel/distanthorizons/core/api/internal/chunkUpdating/ChunkUpdateQueueManager";
    private static final String API_ONLY = "com/seibel/distanthorizons/api/enums/rendering/EDhApiBlockMaterial";
    private static final Set<String> DH_330 = Set.of(
            CONFIG + ".class", CONFIG + "_neoforge.class",
            CONFIG + "$Client.class", CONFIG + "$Client_neoforge.class",
            QUEUE + ".class", QUEUE + "_neoforge.class",
            API_ONLY + ".class",
            // A NeoForge-only class without a common twin is not a rename target.
            "com/seibel/distanthorizons/common/wrappers/DependencySetup_neoforge.class",
            "other/mod/Thing.class", "other/mod/Thing_neoforge.class");

    @Test void onlyDh330IsAdaptedAndOnlyThePinnedFileWithoutOptIn() {
        for (String version : List.of("3.2.0-b", "3.3.1", "3.3.2", "3.3.3", "3.4.0", ""))
            assertEquals(Dh330Mapping.Decision.NOT_NEEDED, Dh330Mapping.decide(version, "anything", true), version);
        assertEquals(Dh330Mapping.Decision.NOT_NEEDED, Dh330Mapping.decide(null, null, false));
        assertEquals(Dh330Mapping.Decision.REMAP, Dh330Mapping.decide("3.3.0", Dh330Mapping.PINNED_SHA1, false));
        assertEquals(Dh330Mapping.Decision.REMAP, Dh330Mapping.decide("3.3.0", Dh330Mapping.PINNED_SHA1.toUpperCase(), false));
        assertEquals(Dh330Mapping.Decision.REFUSED_UNPINNED, Dh330Mapping.decide("3.3.0", "0000", false));
        assertEquals(Dh330Mapping.Decision.REFUSED_UNPINNED, Dh330Mapping.decide("3.3.0", null, false));
        assertEquals(Dh330Mapping.Decision.REMAP_UNPINNED, Dh330Mapping.decide("3.3.0", "0000", true));
        assertTrue(Dh330Mapping.Decision.REMAP.remap && Dh330Mapping.Decision.REMAP_UNPINNED.remap);
        assertFalse(Dh330Mapping.Decision.NOT_NEEDED.remap || Dh330Mapping.Decision.REFUSED_UNPINNED.remap);
    }

    @Test void twinsAreDhClassesThatExistUnderBothNames() {
        Map<String, String> types = Dh330Mapping.platformTypes(DH_330);
        assertEquals(Map.of(CONFIG, CONFIG + "_neoforge", CONFIG + "$Client", CONFIG + "$Client_neoforge", QUEUE, QUEUE + "_neoforge"), types);
        // DH 3.3.1 and later keep only the NeoForge-only wrappers: nothing to rename.
        assertTrue(Dh330Mapping.platformTypes(List.of(CONFIG + ".class",
                "com/seibel/distanthorizons/common/wrappers/DependencySetup_neoforge.class")).isEmpty());
    }

    @Test void typesClassNameStringsAndMixinSelectorsAreRenamed() {
        Map<String, String> types = Dh330Mapping.platformTypes(DH_330);
        byte[] rewritten = Dh330Mapping.remap(sample(), types);
        assertNotNull(rewritten);
        List<String> seen = describe(rewritten);
        assertTrue(seen.contains("field L" + CONFIG + "_neoforge;"), seen.toString());
        assertTrue(seen.contains("field L" + API_ONLY + ";"), "a class without a twin keeps its name");
        assertTrue(seen.contains("call " + QUEUE + "_neoforge.processQueue(L" + CONFIG + "$Client_neoforge;)V"), seen.toString());
        assertTrue(seen.contains("ldc com.seibel.distanthorizons.core.config.Config_neoforge"), seen.toString());
        assertTrue(seen.contains("ldc " + CONFIG + "_neoforge"), seen.toString());
        assertTrue(seen.contains("ldc L" + QUEUE + "_neoforge;processQueue()V"), seen.toString());
        assertTrue(seen.contains("ldc Lnet/minecraft/Foo;bar(L" + CONFIG + "_neoforge;)V"), seen.toString());
        assertTrue(seen.contains("ldc L" + QUEUE + "_neoforge;updateQueue:L" + CONFIG + "_neoforge;"), seen.toString());
        // Already suffixed names, resource paths and ordinary text stay as they are.
        assertTrue(seen.contains("ldc com.seibel.distanthorizons.common.wrappers.DependencySetup_neoforge"), seen.toString());
        assertTrue(seen.contains("ldc " + CONFIG + "_neoforge.class"), seen.toString());
        assertTrue(seen.contains("ldc Loading; please wait"), seen.toString());
        // Applying it to the result changes nothing more.
        assertNull(Dh330Mapping.remap(rewritten, types));
    }

    @Test void classesWithoutTwinnedReferencesAreLeftAlone() {
        Map<String, String> types = Dh330Mapping.platformTypes(DH_330);
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Plain", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "material", "L" + API_ONLY + ";", null, null).visitEnd();
        writer.visitEnd();
        byte[] plain = writer.toByteArray();
        assertTrue(Dh330Mapping.mentionsDistantHorizons(plain));
        assertNull(Dh330Mapping.remap(plain, types));
        assertNull(Dh330Mapping.remap(sample(), Map.of()), "no twins (every other DH version): nothing is rewritten");
        assertFalse(Dh330Mapping.mentionsDistantHorizons("net/minecraft/client/Minecraft".getBytes()));
        assertTrue(Dh330Mapping.mentionsDistantHorizons("x com.seibel.distanthorizons y".getBytes()));
    }

    @Test void classEntriesListAJarFileSystemAndADirectory(@TempDir Path directory) throws Exception {
        Path jar = directory.resolve("dh.jar");
        try (var zip = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), Map.of("create", "true"))) {
            Files.createDirectories(zip.getPath("com/seibel/distanthorizons/core/config"));
            Files.write(zip.getPath(CONFIG + ".class"), new byte[]{1});
            Files.write(zip.getPath(CONFIG + "_neoforge.class"), new byte[]{1});
            Files.write(zip.getPath("com/seibel/distanthorizons/core/config/notes.txt"), new byte[]{1});
        }
        try (var zip = FileSystems.newFileSystem(jar)) {
            Path root = zip.getPath("/");
            assertEquals(Set.of(CONFIG + ".class", CONFIG + "_neoforge.class"), Set.copyOf(Dh330Mapping.classEntries(root)));
            assertEquals(Set.of("core/config/Config.class", "core/config/Config_neoforge.class"),
                    Set.copyOf(Dh330Mapping.classEntries(root.resolve(Dh330Mapping.DH_ROOT))));
            assertEquals(Map.of(CONFIG, CONFIG + "_neoforge"), Dh330Mapping.platformTypes(Dh330Mapping.classEntries(root)));
        }
        Path folder = Files.createDirectories(directory.resolve("classes/a/b"));
        Files.write(folder.resolve("C.class"), new byte[]{1});
        assertEquals(List.of("a/b/C.class"), Dh330Mapping.classEntries(directory.resolve("classes")));
        assertTrue(Dh330Mapping.classEntries(directory.resolve("absent")).isEmpty());
        assertEquals("bf8b4530d8d246dd74ac53a13471bba17941dff7", Dh330Mapping.sha1(folder.resolve("C.class")));
    }

    private static byte[] sample() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Adapter", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "config", "L" + CONFIG + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "material", "L" + API_ONLY + ";", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        for (String constant : List.of(
                "com.seibel.distanthorizons.core.config.Config", CONFIG,
                "L" + QUEUE + ";processQueue()V",
                "Lnet/minecraft/Foo;bar(L" + CONFIG + ";)V",
                "L" + QUEUE + ";updateQueue:L" + CONFIG + ";",
                "com.seibel.distanthorizons.common.wrappers.DependencySetup_neoforge",
                CONFIG + "_neoforge.class",
                "Loading; please wait")) {
            method.visitLdcInsn(constant);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, QUEUE, "processQueue", "(L" + CONFIG + "$Client;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> describe(byte[] bytes) {
        List<String> seen = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                seen.add("field " + descriptor);
                return null;
            }

            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitLdcInsn(Object value) {
                        seen.add("ldc " + value);
                    }

                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                        seen.add("call " + owner + "." + method + desc);
                    }
                };
            }
        }, 0);
        return seen;
    }
}
