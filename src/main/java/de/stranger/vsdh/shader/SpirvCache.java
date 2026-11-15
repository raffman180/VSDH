package de.stranger.vsdh.shader;

import dev.vitrail.pack.model.ProgramStage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compiled programs on disk, so that a pack prepared once (same file, profile and options) is not sent through
 * shaderc again after a restart. shaderc was three quarters of a preparation (Jurgens 18 of 24 s, 15.11.2026).
 * <p>
 * One file per program: the SPIR-V of every stage and the final GLSL the stages were compiled from (the renderer
 * reads both). The key is a SHA-256 over everything {@link VulkanProgramCompiler#compile} turns into that result: the
 * translated text of every stage, the program name (shaderc writes it into the module), the binding table, the
 * full-screen flag, and the {@link #edition} of the code and compiler that did the work. A translator change shows
 * in the translated text; a change of the compile step shows in the edition.
 * <p>
 * A file is used only when its own digest, the SPIR-V magic and the word alignment of every stage hold; anything
 * else is a miss and the program is compiled as before. Writes go to a temporary file and are moved into place.
 * The directory is capped ({@code -Dvsdh.spirvCacheMiB}, default 256); over the cap the least recently used files go
 * first. {@code -Dvsdh.spirvCache=false} turns the cache off. Workers only: no Vulkan, no render thread.
 */
public final class SpirvCache {
    private SpirvCache() {}

    static final int MAGIC = 0x56535056; // "VSPV"
    static final int FORMAT = 1;
    static final int SPIRV_MAGIC = 0x07230203;
    private static final int DIGEST_BYTES = 32;
    private static final String SUFFIX = ".spv", PART = ".part";
    /** No program comes near this; a larger file is refused unread. */
    private static final long MOST_BYTES = 64L << 20;
    private static final int MOST_SOURCE_BYTES = 32 << 20;

    private static final boolean ENABLED = !"false".equals(System.getProperty("vsdh.spirvCache"));
    private static final long CAP_BYTES = Math.max(1, Long.getLong("vsdh.spirvCacheMiB", 256L)) << 20;

    private static final Object LOCK = new Object();
    private static final AtomicLong BYTES = new AtomicLong(), HITS = new AtomicLong(), MISSES = new AtomicLong(), REFUSED = new AtomicLong();
    private static volatile Path directory;
    private static volatile String edition;
    private static volatile boolean editionFailed;

    static {
        // Tools without a game directory (the headless preparation bench).
        String outside = System.getProperty("vsdh.spirvCacheDir", "");
        if (!outside.isBlank()) {
            try {
                install(Path.of(outside));
            } catch (RuntimeException unusable) {
                // Stays off.
            }
        }
    }

    /** One program as it is kept: SPIR-V and final GLSL per stage. */
    public record Entry(Map<ProgramStage, byte[]> stages, Map<ProgramStage, String> sources) {}

    record Stored(String name, long bytes, long usedMillis) {}

    /** Takes {@code folder} into service; a failure leaves the cache off and preparation as it was. */
    public static void install(Path folder) {
        if (!ENABLED) return;
        synchronized (LOCK) {
            try {
                Files.createDirectories(folder);
                long total = 0;
                try (var files = Files.newDirectoryStream(folder)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (name.endsWith(PART)) Files.deleteIfExists(file); // a write that never finished
                        else if (name.endsWith(SUFFIX)) total += Files.size(file);
                    }
                }
                BYTES.set(total);
                directory = folder;
            } catch (IOException | RuntimeException unusable) {
                directory = null;
            }
        }
    }

    /** Takes the cache out of service again (tests). */
    static void uninstall() { directory = null; }

    public static boolean active() { return directory != null; }

    /** Installed and able to name its results (the compiler classes and library version were readable). */
    public static boolean usable() { return directory != null && edition() != null; }
    public static long hits() { return HITS.get(); }
    public static long misses() { return MISSES.get(); }
    public static long refused() { return REFUSED.get(); }
    public static void resetCounters() { HITS.set(0); MISSES.set(0); REFUSED.set(0); }

    /**
     * What names the code and the compiler behind a cached result: the bytes of the classes that turn translated
     * GLSL into linked SPIR-V and the compiler library's version. Null (cache off) when they cannot be read.
     */
    private static String edition() {
        String known = edition;
        if (known != null || editionFailed) return known;
        synchronized (LOCK) {
            if (edition != null || editionFailed) return edition;
            try {
                MessageDigest digest = sha256();
                digest.update(intBytes(FORMAT));
                for (Class<?> type : List.of(VulkanProgramCompiler.class, SpirvInterface.class, dev.vitrail.glsl.GeometryFold.class))
                    feedClass(digest, type);
                feed(digest, org.lwjgl.Version.getVersion());
                try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    var version = stack.mallocInt(1);
                    var revision = stack.mallocInt(1);
                    org.lwjgl.util.shaderc.Shaderc.shaderc_get_spv_version(version, revision);
                    digest.update(intBytes(version.get(0)));
                    digest.update(intBytes(revision.get(0)));
                }
                edition = HexFormat.of().formatHex(digest.digest());
            } catch (IOException | RuntimeException | LinkageError unreadable) {
                editionFailed = true;
            }
            return edition;
        }
    }

    /** The class, its member classes and its numbered anonymous classes. */
    private static void feedClass(MessageDigest digest, Class<?> type) throws IOException {
        feedResource(digest, type, type.getName(), true);
        for (Class<?> member : type.getDeclaredClasses()) feedClass(digest, member);
        for (int number = 1; number <= 32; number++)
            if (!feedResource(digest, type, type.getName() + "$" + number, false)) break;
    }

    private static boolean feedResource(MessageDigest digest, Class<?> owner, String className, boolean required) throws IOException {
        try (InputStream in = owner.getResourceAsStream("/" + className.replace('.', '/') + ".class")) {
            if (in == null) {
                if (required) throw new IOException("Class bytes not readable: " + className);
                return false;
            }
            byte[] bytes = in.readAllBytes();
            feed(digest, className);
            digest.update(intBytes(bytes.length));
            digest.update(bytes);
            return true;
        }
    }

    /** The key of one compilation, or null while the cache is off. */
    static String keyFor(String name, boolean fullscreen, Map<String, Integer> bindings, Map<ProgramStage, String> texts) {
        if (directory == null) return null;
        String code = edition();
        return code == null ? null : key(code, name, fullscreen, bindings, texts);
    }

    /**
     * Every piece goes in behind its own length, so two different splits of the same characters cannot hash alike.
     * Stages are fed in the enum's order and bindings in the table's own order (the order decides the numbers).
     */
    static String key(String edition, String name, boolean fullscreen, Map<String, Integer> bindings, Map<ProgramStage, String> texts) {
        MessageDigest digest = sha256();
        feed(digest, edition);
        feed(digest, name);
        digest.update((byte) (fullscreen ? 1 : 0));
        digest.update(intBytes(bindings.size()));
        for (var binding : bindings.entrySet()) {
            feed(digest, binding.getKey());
            digest.update(intBytes(binding.getValue()));
        }
        for (ProgramStage stage : ProgramStage.values()) {
            String text = texts.get(stage);
            if (text == null) continue;
            feed(digest, stage.name());
            feed(digest, text);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The file of one program: header, stages, then a SHA-256 of all bytes before it. */
    static byte[] encode(Entry entry) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                out.writeInt(entry.stages().size());
                for (ProgramStage stage : ProgramStage.values()) {
                    byte[] spirv = entry.stages().get(stage);
                    if (spirv == null) continue;
                    String source = entry.sources().get(stage);
                    if (source == null) throw new IllegalArgumentException("Stage without source: " + stage);
                    out.writeUTF(stage.name());
                    out.writeInt(spirv.length);
                    out.write(spirv);
                    byte[] text = source.getBytes(StandardCharsets.UTF_8);
                    byte[] packed = deflate(text);
                    out.writeInt(text.length);
                    out.writeInt(packed.length);
                    out.write(packed);
                }
            }
            byte[] body = bytes.toByteArray();
            byte[] file = Arrays.copyOf(body, body.length + DIGEST_BYTES);
            System.arraycopy(sha256().digest(body), 0, file, body.length, DIGEST_BYTES);
            return file;
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The program in {@code file}, or null for anything that is not exactly what {@link #encode} wrote. */
    static Entry decode(byte[] file) {
        int length = file.length - DIGEST_BYTES;
        if (length < 12) return null;
        MessageDigest digest = sha256();
        digest.update(file, 0, length);
        if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(file, length, file.length))) return null;
        try {
            var in = ByteBuffer.wrap(file, 0, length); // big-endian, as DataOutputStream writes
            if (in.getInt() != MAGIC || in.getInt() != FORMAT) return null;
            int count = in.getInt();
            if (count <= 0 || count > ProgramStage.values().length) return null;
            var stages = new EnumMap<ProgramStage, byte[]>(ProgramStage.class);
            var sources = new EnumMap<ProgramStage, String>(ProgramStage.class);
            for (int index = 0; index < count; index++) {
                byte[] name = new byte[in.getShort() & 0xffff];
                in.get(name);
                ProgramStage stage = ProgramStage.valueOf(new String(name, StandardCharsets.UTF_8));
                int spirvBytes = in.getInt();
                if (spirvBytes < 20 || spirvBytes % 4 != 0 || spirvBytes > in.remaining()) return null;
                byte[] spirv = new byte[spirvBytes];
                in.get(spirv);
                if (ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt(0) != SPIRV_MAGIC) return null;
                int textBytes = in.getInt(), packedBytes = in.getInt();
                if (textBytes < 0 || textBytes > MOST_SOURCE_BYTES || packedBytes < 0 || packedBytes > in.remaining()) return null;
                byte[] packed = new byte[packedBytes];
                in.get(packed);
                byte[] text = inflate(packed, textBytes);
                if (text == null || stages.put(stage, spirv) != null) return null;
                sources.put(stage, new String(text, StandardCharsets.UTF_8));
            }
            return in.hasRemaining() ? null : new Entry(stages, sources);
        } catch (RuntimeException damaged) { // short buffer, unknown stage name
            return null;
        }
    }

    /** The kept result under {@code key}, or null: absent, unreadable or damaged are all a miss. */
    static Entry lookup(String key) {
        Path root = directory;
        if (key == null || root == null) return null;
        Path file = root.resolve(key + SUFFIX);
        Entry entry = null;
        boolean present = false;
        try {
            if (Files.size(file) <= MOST_BYTES) {
                present = true;
                entry = decode(Files.readAllBytes(file));
            }
        } catch (IOException | OutOfMemoryError absent) {
            // A miss.
        }
        if (entry == null) {
            if (present) REFUSED.incrementAndGet();
            MISSES.incrementAndGet();
            return null;
        }
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis())); // most recently used
        } catch (IOException ignored) {
            // Only the eviction order suffers.
        }
        HITS.incrementAndGet();
        return entry;
    }

    /** Keeps a freshly compiled program; a failure to write costs nothing but the next compile. */
    static void store(String key, Entry entry) {
        Path root = directory;
        if (key == null || root == null) return;
        Path file = root.resolve(key + SUFFIX);
        Path part = root.resolve(key + "-" + Long.toHexString(Thread.currentThread().threadId()) + "-" + Long.toHexString(System.nanoTime()) + PART);
        try {
            byte[] bytes = encode(entry);
            Files.write(part, bytes);
            try {
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException plain) {
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (BYTES.addAndGet(bytes.length) > CAP_BYTES) sweep(root);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // The next install removes it.
            }
        }
    }

    /** Brings the directory back to three quarters of the cap, least recently used first. */
    private static void sweep(Path root) {
        synchronized (LOCK) {
            if (BYTES.get() <= CAP_BYTES) return; // another worker swept meanwhile
            long total = 0;
            try {
                var stored = new ArrayList<Stored>();
                try (var files = Files.newDirectoryStream(root, "*" + SUFFIX)) {
                    for (Path file : files) {
                        try {
                            stored.add(new Stored(file.getFileName().toString(), Files.size(file), Files.getLastModifiedTime(file).toMillis()));
                        } catch (IOException gone) {
                            // Deleted by someone else.
                        }
                    }
                }
                var bytesByName = new java.util.HashMap<String, Long>();
                for (Stored one : stored) {
                    total += one.bytes();
                    bytesByName.put(one.name(), one.bytes());
                }
                for (String name : evictions(stored, CAP_BYTES, CAP_BYTES / 4 * 3)) {
                    try {
                        if (Files.deleteIfExists(root.resolve(name))) total -= bytesByName.get(name);
                    } catch (IOException kept) {
                        // Open elsewhere; it stays and is counted.
                    }
                }
            } catch (IOException unreadable) {
                total = 0; // counted again at the next start; never sweep in a loop over a directory that cannot be read
            } finally {
                BYTES.set(total);
            }
        }
    }

    /**
     * The files to delete so that at most {@code target} bytes remain, least recently used first (ties by name, so
     * the choice does not depend on the directory's listing order). Nothing while the total is within {@code cap}.
     */
    static List<String> evictions(List<Stored> stored, long cap, long target) {
        long total = 0;
        for (Stored one : stored) total += one.bytes();
        if (total <= cap) return List.of();
        var oldestFirst = new ArrayList<>(stored);
        oldestFirst.sort(Comparator.comparingLong(Stored::usedMillis).thenComparing(Stored::name));
        var result = new ArrayList<String>();
        for (Stored one : oldestFirst) {
            if (total <= target) break;
            result.add(one.name());
            total -= one.bytes();
        }
        return result;
    }

    private static byte[] deflate(byte[] text) {
        var deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(text);
            deflater.finish();
            var out = new ByteArrayOutputStream(Math.max(64, text.length / 4));
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Exactly {@code length} bytes, or null when the packed data holds anything else. */
    private static byte[] inflate(byte[] packed, int length) {
        var inflater = new Inflater();
        try {
            inflater.setInput(packed);
            byte[] text = new byte[length];
            int read = 0;
            while (read < length) {
                int step = inflater.inflate(text, read, length - read);
                if (step == 0) return null; // ended early or needs input that is not there
                read += step;
            }
            // Nothing may follow the announced length.
            return inflater.inflate(new byte[1]) == 0 && inflater.finished() ? text : null;
        } catch (DataFormatException damaged) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private static void feed(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(intBytes(bytes.length));
        digest.update(bytes);
    }

    private static byte[] intBytes(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
