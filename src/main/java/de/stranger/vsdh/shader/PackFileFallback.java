package de.stranger.vsdh.shader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A configured shader pack whose file is gone because a newer version replaced it ({@code Jurgens-Shader-v1.0.zip}
 * became {@code Jurgens-Shader-v1.0.1.zip}): the newest file of the same pack is used instead of no pack at all.
 * The player's saved choice is not rewritten.
 */
final class PackFileFallback {
    private PackFileFallback() {}

    /** A trailing version: "-v1.0.1", "_1.2", " V3.9", "-r5.9.3", optionally followed by a short suffix such as "b". */
    private static final Pattern VERSION = Pattern.compile("[\\s_.-]*[vVrR]?\\d+(?:\\.\\d+)*[a-zA-Z]?$");

    /** The pack name without ".zip" and without a trailing version, lower case; blank if nothing is left. */
    static String family(String name) {
        String base = name.strip();
        if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) base = base.substring(0, base.length() - 4);
        base = VERSION.matcher(base).replaceFirst("");
        return base.strip().toLowerCase(Locale.ROOT);
    }

    /** The newest candidate of the same family as {@code missing}, or null; candidates are file or folder names. */
    static String pick(String missing, List<String> candidates) {
        String family = family(missing);
        if (family.isEmpty()) return null;
        String best = null;
        for (String candidate : candidates) {
            if (candidate.equals(missing) || !family(candidate).equals(family)) continue;
            if (best == null || compareVersions(versionOf(candidate), versionOf(best)) > 0) best = candidate;
        }
        return best;
    }

    /** The configured name if it exists, otherwise the newest file of the same pack, otherwise the configured name. */
    static String resolve(Path directory, String name) {
        try {
            if (Files.exists(directory.resolve(name))) return name;
            var candidates = new ArrayList<String>();
            try (var listing = Files.list(directory)) {
                listing.forEach(path -> {
                    String file = path.getFileName().toString();
                    if (Files.isDirectory(path) || file.toLowerCase(Locale.ROOT).endsWith(".zip")) candidates.add(file);
                });
            }
            String replacement = pick(name, candidates);
            return replacement == null ? name : replacement;
        } catch (IOException | RuntimeException unreadable) {
            return name;
        }
    }

    private static int[] versionOf(String name) {
        String base = name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
        var matcher = Pattern.compile("(\\d+(?:\\.\\d+)*)[a-zA-Z]?$").matcher(base.strip());
        if (!matcher.find()) return new int[0];
        String[] parts = matcher.group(1).split("\\.");
        int[] version = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { version[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException tooLong) { version[i] = Integer.MAX_VALUE; }
        }
        return version;
    }

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
