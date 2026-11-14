package de.stranger.vsdh.dh330;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place that knows how VSDH is adapted to the NeoForge half of Distant Horizons 3.3.0.
 *
 * <p>That DH build ships many classes twice: {@code X} for Fabric and {@code X_neoforge}, the one
 * NeoForge actually uses. VSDH is compiled against the plain names; every reference to an {@code X}
 * that has such a twin is rewritten to the twin, including string constants that carry a class name
 * and Mixin member selectors such as {@code @At(target = "Lowner;name(desc)")}.
 *
 * <p>Used at load time by {@link Dh330CoreMod}, by {@code tools/VerifyDhLinks.java} and by the unit
 * tests. Depends on ASM and the JDK only.
 */
public final class Dh330Mapping {
    /** The only DH version that needs the adaptation. */
    public static final String DH_VERSION = "3.3.0";
    /** SHA-1 of the DH 3.3.0 Fabric/NeoForge JAR for Minecraft 1.21.1 the mapping was verified with. */
    public static final String PINNED_SHA1 = "685808f4d3cbff5dec903cbea08fa9ccffc1a925";
    /** Player-side opt-in for a DH 3.3.0 file with another hash. */
    public static final String ALLOW_UNPINNED_PROPERTY = "vsdh.dh330.allowUnpinned";
    /** Where DH keeps its classes, as a path inside its JAR. */
    public static final String DH_ROOT = "com/seibel/distanthorizons/";

    private static final String NEOFORGE_SUFFIX = "_neoforge";
    private static final String CLASS_SUFFIX = ".class";

    private Dh330Mapping() {}

    public enum Decision {
        /** Any other DH version: VSDH's classes load unchanged. */
        NOT_NEEDED(false),
        /** The pinned DH 3.3.0 file. */
        REMAP(true),
        /** DH 3.3.0 with an unknown hash, explicitly allowed by the player. */
        REMAP_UNPINNED(true),
        /** DH 3.3.0 with an unknown hash: nothing is rewritten and VSDH keeps its DH adapter off. */
        REFUSED_UNPINNED(false);

        public final boolean remap;

        Decision(boolean remap) {
            this.remap = remap;
        }
    }

    /** @param sha1 hex SHA-1 of the installed DH file, or null when it cannot be read */
    public static Decision decide(String dhVersion, String sha1, boolean allowUnpinned) {
        if (!DH_VERSION.equals(dhVersion)) return Decision.NOT_NEEDED;
        if (PINNED_SHA1.equalsIgnoreCase(sha1)) return Decision.REMAP;
        return allowUnpinned ? Decision.REMAP_UNPINNED : Decision.REFUSED_UNPINNED;
    }

    public static String sha1(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Entry names ({@code a/b/C.class}) below {@code root}: the root of a JAR file system, or a directory. */
    public static List<String> classEntries(Path root) throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(root)) return names;
        try (var files = Files.walk(root)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                String name = root.relativize(iterator.next()).toString().replace('\\', '/');
                if (name.startsWith("/")) name = name.substring(1);
                if (name.endsWith(CLASS_SUFFIX)) names.add(name);
            }
        }
        return names;
    }

    /**
     * Internal names of DH classes that have a {@code _neoforge} twin, mapped to that twin.
     * Empty for every supported DH version except 3.3.0.
     */
    public static Map<String, String> platformTypes(Collection<String> dhEntryNames) {
        Set<String> entries = dhEntryNames instanceof Set<String> set ? set : new HashSet<>(dhEntryNames);
        Map<String, String> types = new HashMap<>();
        for (String name : entries) {
            if (!name.startsWith(DH_ROOT) || !name.endsWith(NEOFORGE_SUFFIX + CLASS_SUFFIX)) continue;
            String target = name.substring(0, name.length() - CLASS_SUFFIX.length());
            String common = target.substring(0, target.length() - NEOFORGE_SUFFIX.length());
            if (entries.contains(common + CLASS_SUFFIX)) types.put(common, target);
        }
        return types;
    }

    /** Cheap pre-filter: only classes that name DH at all can be affected. */
    public static boolean mentionsDistantHorizons(byte[] classBytes) {
        return contains(classBytes, "com/seibel/") || contains(classBytes, "com.seibel.");
    }

    private static boolean contains(byte[] bytes, String text) {
        outer:
        for (int start = 0, last = bytes.length - text.length(); start <= last; start++) {
            for (int at = 0; at < text.length(); at++) if (bytes[start + at] != (byte) text.charAt(at)) continue outer;
            return true;
        }
        return false;
    }

    /** The class rewritten for DH 3.3.0, or null when it references no twinned DH class. */
    public static byte[] remap(byte[] classBytes, Map<String, String> types) {
        var remapper = new TypeRemapper(types);
        var writer = new ClassWriter(0);
        new ClassReader(classBytes).accept(new ClassRemapper(writer, remapper), 0);
        return remapper.hits() == 0 ? null : writer.toByteArray();
    }

    /** Renames types only; member names stay as they are. */
    public static final class TypeRemapper extends Remapper {
        private final Map<String, String> types;
        private int hits;

        public TypeRemapper(Map<String, String> types) {
            this.types = types;
        }

        /** Number of rewritten references so far. */
        public int hits() {
            return hits;
        }

        @Override public String map(String internalName) {
            String target = types.get(internalName);
            if (target != null) hits++;
            return target;
        }

        @Override public Object mapValue(Object value) {
            if (value instanceof String name) {
                String target = types.get(name.replace('.', '/'));
                if (target != null) {
                    hits++;
                    return name.indexOf('.') >= 0 ? target.replace('/', '.') : target;
                }
                String member = mapMemberReference(name);
                if (member != null) return member;
            }
            return super.mapValue(value);
        }

        /** Mixin selectors such as {@code @At(target = "Lowner;name(desc)")}: owner and descriptor. */
        private String mapMemberReference(String name) {
            int semi = name.indexOf(';');
            if (!name.startsWith("L") || semi < 2) return null;
            String owner = name.substring(1, semi);
            if (owner.indexOf('.') >= 0 || owner.indexOf('(') >= 0) return null;
            String rest = name.substring(semi + 1);
            int paren = rest.indexOf('(');
            int colon = rest.indexOf(':');
            int before = hits;
            String mappedRest;
            try {
                mappedRest = paren >= 0 ? rest.substring(0, paren) + mapMethodDesc(rest.substring(paren))
                        : colon >= 0 ? rest.substring(0, colon + 1) + mapDesc(rest.substring(colon + 1)) : rest;
            } catch (RuntimeException notADescriptor) {
                // An ordinary string shaped like "L...;...(" (a log text) is no selector: leave it as it is,
                // instead of letting the parse error switch off the whole DH 3.3.0 adaptation.
                hits = before;
                return null;
            }
            String result = "L" + types.getOrDefault(owner, owner) + ";" + mappedRest;
            if (result.equals(name)) {
                hits = before;
                return null;
            }
            hits = before + 1;
            return result;
        }
    }
}
