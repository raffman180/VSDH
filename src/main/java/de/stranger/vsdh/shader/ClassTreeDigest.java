package de.stranger.vsdh.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/** Names a set of compiled classes by content, so a store written by one build is not read by a changed one. */
final class ClassTreeDigest {
    private ClassTreeDigest() {}

    /**
     * Sixteen hex digits over the name and bytes of every class file under {@code directory}, in name order, so the
     * listing order of the file system does not matter. Null for a directory that holds none.
     */
    static String of(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) return null;
        var classes = new TreeMap<String, Path>();
        try (var tree = Files.walk(directory)) {
            for (Path file : tree.toList()) {
                String name = directory.relativize(file).toString().replace('\\', '/');
                if (name.endsWith(".class") && Files.isRegularFile(file)) classes.put(name, file);
            }
        }
        if (classes.isEmpty()) return null;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        for (var entry : classes.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] bytes = Files.readAllBytes(entry.getValue());
            digest.update(length(name.length));
            digest.update(name);
            digest.update(length(bytes.length));
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    private static byte[] length(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }
}
