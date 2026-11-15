package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.PackOptionsView;
import de.stranger.vsdh.shader.PackProgramSource;
import de.stranger.vsdh.shader.PrepareClock;
import de.stranger.vsdh.shader.SpirvCache;
import de.stranger.vsdh.shader.WorldPackSet;
import dev.vitrail.glsl.LoadClock;
import dev.vitrail.glsl.TranslationCache;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.OpenedPack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Times the preparation of an external pack the way ShaderPackStartup.open does it (open, option page, all vanilla
 * dimensions), without the game or a GPU. Arguments: pack ZIP, profile, DH programs (true/false), repetitions.
 * Each repetition is a full preparation in the same JVM. The digest covers every prepared program (translated text,
 * uniforms, samplers, final GLSL, SPIR-V, bindings) in plan order: two runs with the same digest prepared the same
 * pack, whatever the worker count or cache state was.
 * <p>
 * Switches: -Dvsdh.prepareThreads=N (0: sequential), -Dvsdh.spirvCacheDir=dir, -Dvsdh.spirvCache=false,
 * -Dvitrail.translationDir=dir.
 */
public final class PackPrepareBench {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Expected pack ZIP, profile, DH flag [, repetitions]");
        Path zip = Path.of(args[0]);
        boolean distant = Boolean.parseBoolean(args[2]);
        int repetitions = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        // As ShaderPackStartup.open sets them for a player.
        dev.vitrail.glsl.GlslTranslator.reduceTrig(Boolean.getBoolean("vsdh.reduceTrig"));
        dev.vitrail.glsl.GlslTranslator.nativeVolumes(!Boolean.getBoolean("vsdh.flatVolumes"));
        System.out.println("BENCH workers " + System.getProperty("vsdh.prepareThreads", "default") + ", SPIR-V cache "
                + (SpirvCache.active() ? "on" : "off") + ", translation cache " + (TranslationCache.installed() ? "on" : "off"));
        for (int run = 1; run <= repetitions; run++) {
            PrepareClock.reset();
            LoadClock.reset();
            SpirvCache.resetCounters();
            TranslationCache.reset();
            long started = System.nanoTime();
            long clock = PrepareClock.start();
            WorldPackSet set;
            try (var pack = OpenedPack.open(zip, Map.of(), args[1])) {
                PrepareClock.add("open", clock);
                clock = PrepareClock.start();
                PackOptionsView.read(pack, zip.getFileName().toString(), args[1], "de_de");
                PrepareClock.add("optionsView", clock);
                clock = PrepareClock.start();
                set = WorldPackSet.prepare(pack, distant);
                PrepareClock.add("worldPackSet", clock);
            }
            long total = (System.nanoTime() - started) / 1_000_000;
            var programs = new ArrayList<Object>();
            for (String dimension : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"))
                collect(set.dimensions().get(dimension), programs, new IdentityHashMap<>());
            System.out.println("BENCH " + zip.getFileName() + " run " + run + ": total " + total + " ms, programs " + programs.size()
                    + ", expansion " + LoadClock.expansionMillis() + " ms (" + LoadClock.expanded() + ", served " + LoadClock.expansionsServed() + ")"
                    + ", translation " + LoadClock.translationMillis() + " ms (" + LoadClock.translated() + ", from disk " + TranslationCache.served() + ")"
                    + ", SPIR-V cache hits " + SpirvCache.hits() + " misses " + SpirvCache.misses() + " refused " + SpirvCache.refused()
                    + ", digest " + digest(programs) + ", textures " + textureDigest(set));
            PrepareClock.snapshot().forEach((phase, value) -> System.out.println("BENCH   " + phase + " " + value));
        }
    }

    /** Every prepared program reachable from {@code value}, in the order records declare and sorted maps hold them. */
    private static void collect(Object value, List<Object> programs, IdentityHashMap<Object, Boolean> seen) throws Exception {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>
                || value instanceof byte[]) return;
        if (value instanceof PackProgramSource.Prepared || value instanceof PackProgramSource.PreparedCompute) {
            programs.add(value); // every reference: shared results and separately prepared equal ones read alike
            return;
        }
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            for (Object member : sorted.values()) collect(member, programs, seen);
        } else if (value instanceof List<?> list) {
            for (Object member : list) collect(member, programs, seen);
        } else if (value instanceof Collection<?>) {
            // Sets hold names and numbers here, never programs.
        } else if (value instanceof java.util.Optional<?> optional) {
            if (optional.isPresent()) collect(optional.get(), programs, seen);
        } else if (value.getClass().isRecord() && value.getClass().getName().startsWith("de.stranger.vsdh.")) {
            for (var component : value.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                collect(accessor.invoke(value), programs, seen);
            }
        }
    }

    private static String digest(List<Object> programs) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Object program : programs) {
            PackProgramSource.Prepared prepared = program instanceof PackProgramSource.PreparedCompute compute ? compute.asProgram()
                    : (PackProgramSource.Prepared) program;
            feed(digest, prepared.path());
            if (program instanceof PackProgramSource.PreparedCompute compute) feed(digest, compute.dispatch().toString());
            var translated = prepared.translated();
            feed(digest, translated.inputs().name());
            feed(digest, translated.uniforms().toString());
            feed(digest, translated.samplers().toString());
            feed(digest, new java.util.TreeSet<>(translated.sampled()).toString());
            feed(digest, new TreeMap<>(translated.synthesized()).toString());
            feed(digest, new TreeMap<>(prepared.compiled().bindings()).toString());
            for (ProgramStage stage : ProgramStage.values()) {
                var unit = translated.stages().get(stage);
                if (unit != null) {
                    feed(digest, "translated " + stage);
                    feed(digest, unit.text());
                    feed(digest, String.valueOf(unit.notes()));
                }
                byte[] spirv = prepared.compiled().stages().get(stage);
                if (spirv != null) {
                    feed(digest, "compiled " + stage);
                    digest.update(spirv);
                    feed(digest, prepared.compiled().sources().get(stage));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    /** Every pack texture binding of the three dimensions with its decoded content. */
    private static String textureDigest(WorldPackSet set) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String dimension : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
            for (var binding : new TreeMap<>(set.dimensions().get(dimension).textures().bindings()).entrySet()) {
                feed(digest, binding.getKey());
                var asset = binding.getValue();
                if (asset instanceof de.stranger.vsdh.shader.PackTexturePlan.Pixels pixels) {
                    feed(digest, "pixels " + pixels.width() + "x" + pixels.height() + " " + pixels.blur() + " " + pixels.clamp());
                    digest.update(pixels.rgba());
                } else if (asset instanceof de.stranger.vsdh.shader.PackTexturePlan.Texels texels) {
                    feed(digest, "texels " + texels.width() + "x" + texels.height() + " " + texels.vkFormat() + " " + texels.blur() + " " + texels.clamp());
                    digest.update(texels.data());
                } else if (asset instanceof de.stranger.vsdh.shader.PackTexturePlan.Volume volume) {
                    feed(digest, "volume " + volume.width() + "x" + volume.height() + "x" + volume.depth() + " " + volume.vkFormat() + " "
                            + volume.texelBytes() + " " + volume.blur() + " " + volume.clamp());
                    digest.update(volume.data());
                } else {
                    feed(digest, String.valueOf(asset));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    private static void feed(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
