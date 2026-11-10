import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/** Build-time adaptation for the separately namespaced NeoForge half of DH 3.3.0. */
class RemapDh330 {
    private static final String DH_SHA1 = "685808f4d3cbff5dec903cbea08fa9ccffc1a925";
    private static final String NEOFORGE_SUFFIX = "_neoforge";
    private static final String CLASS_SUFFIX = ".class";
    private static final String STANDARD_RANGE = "[3.3.1,3.3.3]";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected DH 3.3.0 JAR, standard VSDH JAR and output JAR");
        Path dependency = Path.of(args[0]);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(dependency)));
        if (!hash.equals(DH_SHA1)) throw new IOException("Expected the pinned DH 3.3.0 NeoForge JAR");
        Map<String, String> types = platformTypes(dependency);
        var remapper = new SimpleRemapper(Opcodes.ASM9, types) {
            @Override public Object mapValue(Object value) {
                if (value instanceof String name) {
                    String target = types.get(name.replace('.', '/'));
                    if (target != null) return name.indexOf('.') >= 0 ? target.replace('/', '.') : target;
                }
                return super.mapValue(value);
            }
        };
        Path result = Path.of(args[2]).toAbsolutePath();
        Path temporary = Files.createTempFile(result.getParent(), "vsdh-dh330-", ".tmp");
        try {
            remapJar(Path.of(args[1]), temporary, remapper);
            Files.move(temporary, result, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void remapJar(Path standard, Path destination, SimpleRemapper remapper) throws IOException {
        try (var input = new JarFile(standard.toFile());
             var output = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (var original : input.stream().toList()) {
                String name = original.getName();
                byte[] bytes;
                try (var stream = input.getInputStream(original)) {
                    bytes = stream.readAllBytes();
                }
                if (name.endsWith(CLASS_SUFFIX)) {
                    var reader = new ClassReader(bytes);
                    var writer = new ClassWriter(0);
                    reader.accept(new ClassRemapper(writer, remapper), 0);
                    bytes = writer.toByteArray();
                } else if (name.equals("META-INF/neoforge.mods.toml")) {
                    String toml = new String(bytes, StandardCharsets.UTF_8);
                    if (!toml.contains(STANDARD_RANGE)) throw new IOException("Unexpected VSDH dependency range");
                    bytes = toml.replace("DH 3.3.1–3.3.3", "DH 3.3.0 (NeoForge build)")
                            .replace(STANDARD_RANGE, "[3.3.0]").getBytes(StandardCharsets.UTF_8);
                }
                var entry = new ZipEntry(name);
                entry.setTime(original.getTime());
                output.putNextEntry(entry);
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    private static Map<String, String> platformTypes(Path dependency) throws IOException {
        Map<String, String> types = new HashMap<>();
        try (var dh = new JarFile(dependency.toFile())) {
            dh.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("com/seibel/distanthorizons/") && name.endsWith(NEOFORGE_SUFFIX + CLASS_SUFFIX))
                    .forEach(name -> {
                        String target = name.substring(0, name.length() - CLASS_SUFFIX.length());
                        String common = target.substring(0, target.length() - NEOFORGE_SUFFIX.length());
                        if (dh.getEntry(common + CLASS_SUFFIX) != null) types.put(common, target);
                    });
        }
        return types;
    }
}
