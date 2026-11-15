package de.stranger.vsdh.shader;

import dev.vitrail.glsl.ProgramTranslator;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.OpenedPack;

import java.io.IOException;
import java.util.EnumMap;

/** Resource-independent prepared source: no zip filesystem handles escape the caller's OpenedPack. */
public final class PackProgramSource {
    private PackProgramSource() {}
    public record Prepared(String path, ProgramTranslator.TranslatedProgram translated,
                           VulkanProgramCompiler.Compiled compiled) {}

    /** Vertex and fragment, with optional paired tessellation stages and an optional geometry stage. */
    public static boolean graphicsStages(java.util.Set<ProgramStage> stages) {
        return stages.contains(ProgramStage.VERTEX) && stages.contains(ProgramStage.FRAGMENT)
                && stages.contains(ProgramStage.TESSELLATION_CONTROL) == stages.contains(ProgramStage.TESSELLATION_EVALUATION)
                && !stages.contains(ProgramStage.COMPUTE);
    }

    /** One {@code .csh} entry point with the directives its dispatch is sized from. */
    public record PreparedCompute(String path, ProgramTranslator.TranslatedProgram translated,
                                  VulkanProgramCompiler.Compiled compiled, dev.vitrail.glsl.PackProgram.Dispatch dispatch) {
        /** The same program for code that reads only path, translation and bytecode (textures, uniforms). */
        public Prepared asProgram() { return new Prepared(path, translated, compiled); }

        /** Iris ComputeProgram.getWorkGroups: a fixed count, else the (scaled) render size over the local size. */
        public int[] groups(int width, int height) {
            if (dispatch.groupsX() >= 0) return new int[]{dispatch.groupsX(), dispatch.groupsY(), dispatch.groupsZ()};
            if (dispatch.localX() <= 0 || dispatch.localY() <= 0)
                throw new IllegalStateException("Compute " + path + " has no readable local_size for a derived dispatch");
            float scaleX = dispatch.renderX() >= 0 ? dispatch.renderX() : 1f, scaleY = dispatch.renderY() >= 0 ? dispatch.renderY() : 1f;
            return new int[]{(int) Math.ceil(width * scaleX / dispatch.localX()), (int) Math.ceil(height * scaleY / dispatch.localY()), 1};
        }
    }

    /**
     * What one program is asked for with. Settings compare by identity (a SettingSet is immutable and one reading of
     * a pack hands the same one to every program), like the pack source's own memo of flattened units.
     */
    private record Request(dev.vitrail.pack.option.SettingSet settings, String path, VertexInputs inputs,
                           java.util.List<String> elements, dev.vitrail.pack.model.AlphaTest alpha, boolean compute) {}

    /**
     * The programs of one opening of a pack that were handed to the workers, by request. It belongs to the thread
     * that reads through the opening, like every other derived answer of the pack source, and goes with it.
     */
    private static final class Pending {
        final java.util.HashMap<Request, java.util.concurrent.Future<?>> tasks = new java.util.HashMap<>();
        /** Other work of the opening on the same workers (texture decoding), awaited with the programs. */
        final java.util.ArrayList<java.util.concurrent.Future<?>> aside = new java.util.ArrayList<>();
        /** Set while a preparation winds up: a task that has not begun then does nothing. */
        volatile boolean abandoned;
    }

    /** Runs side work of this opening on the compile workers; {@link #settle} waits for it like for a program. */
    static <T> java.util.concurrent.Future<T> submitAside(OpenedPack pack, java.util.concurrent.Callable<T> work) throws IOException {
        var pending = pending(pack);
        var task = PreparePool.submit(() -> {
            requireWanted(pending);
            return work.call();
        });
        pending.aside.add(task);
        return task;
    }

    /** The result of side work, with its failure thrown here as if it had run on this thread. */
    static <T> T joinAside(java.util.concurrent.Future<T> task) throws IOException {
        try {
            return join(task);
        } catch (java.util.concurrent.CancellationException dropped) {
            return null; // an earlier plan of this opening ended before the work began
        }
    }

    private static Pending pending(OpenedPack pack) throws IOException {
        return pack.source().derived(Pending.class, Pending::new);
    }

    /**
     * Ends the read-ahead of this opening: programs nobody asked for in the end (a preparation that failed, a
     * read-ahead the plan did not follow) are skipped if they have not begun and awaited if they have. After this
     * no worker translates for this pack any more, so the next pack may set the translator's process-wide switches
     * and registries without a straggler reading them halfway through (and filing the result on disk).
     */
    static void settle(OpenedPack pack) {
        if (!PreparePool.parallel()) return;
        Pending pending;
        try {
            pending = pending(pack);
        } catch (IOException | RuntimeException nothingQueued) {
            return;
        }
        pending.abandoned = true;
        boolean interrupted = false;
        var all = new java.util.ArrayList<java.util.concurrent.Future<?>>(pending.tasks.values());
        all.addAll(pending.aside);
        for (var task : all) {
            while (true) {
                try {
                    task.get();
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException finished) {
                    // Its failure was thrown where the plan asked for it, or nobody asked.
                } catch (InterruptedException interrupt) {
                    interrupted = true;
                    continue; // still wait: the point is that no worker is left behind
                }
                break;
            }
        }
        pending.tasks.clear();
        pending.aside.clear();
        pending.abandoned = false;
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void requireWanted(Pending pending) {
        if (pending != null && pending.abandoned) throw new java.util.concurrent.CancellationException("Shader preparation ended");
    }

    /** The result of a queued program, with its failure thrown here as the sequential preparation threw it. */
    @SuppressWarnings("unchecked")
    private static <T> T join(java.util.concurrent.Future<?> task) throws IOException {
        try {
            return (T) task.get();
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException(cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Shader preparation interrupted", interrupted);
        }
    }

    /** Hands a compute program to the workers ahead of the plan asking for it; does nothing when sequential. */
    static void prefetchCompute(OpenedPack pack, String path) {
        if (!PreparePool.parallel()) return;
        try {
            computeTask(pack, path);
        } catch (IOException | RuntimeException later) {
            // The plan meets the same failure where it asks for the program.
        }
    }

    public static PreparedCompute prepareCompute(OpenedPack pack, String path) throws IOException {
        return join(computeTask(pack, path));
    }

    /** Flattens the entry file here (the pack source is single-threaded); translation and compilation go to a worker. */
    private static java.util.concurrent.Future<?> computeTask(OpenedPack pack, String path) throws IOException {
        var request = new Request(pack.settings(), path, VertexInputs.FULLSCREEN, VertexInputs.FULLSCREEN.elements(),
                dev.vitrail.pack.model.AlphaTest.OFF, true);
        var pending = PreparePool.parallel() ? pending(pack) : null;
        if (pending != null) {
            var known = pending.tasks.get(request);
            if (known != null && !known.isCancelled()) return known;
        }
        var file = pack.source().file(path + "." + ProgramStage.COMPUTE.extension())
                .orElseThrow(() -> new IOException("Missing compute program: " + path));
        var unit = new IncludeExpander(pack.source(), pack.settings()).expand(file);
        var units = new EnumMap<ProgramStage, IncludeExpander.ExpandedUnit>(ProgramStage.class);
        units.put(ProgramStage.COMPUTE, unit);
        var dispatch = dev.vitrail.glsl.PackProgram.dispatchOf(unit);
        if (dispatch.groupsX() < 0 && (dispatch.localX() <= 0 || dispatch.localY() <= 0))
            throw new IOException("Compute " + path + " has no readable work group size: " + dispatch.unresolved());
        String bare = path.substring(path.lastIndexOf('/') + 1);
        var volumes = volumes(pack);
        var task = PreparePool.submit(() -> {
            requireWanted(pending);
            var translated = ProgramTranslator.translate(units, VertexInputs.FULLSCREEN, VertexInputs.FULLSCREEN.elements(),
                    dev.vitrail.pack.model.AlphaTest.OFF, false, bare, volumes);
            return new PreparedCompute(path, translated, VulkanProgramCompiler.compile(path, translated), dispatch);
        });
        if (pending != null) pending.tasks.put(request, task);
        return task;
    }

    public static Prepared prepareTerrain(OpenedPack pack, String path) throws IOException {
        return prepareTerrain(pack, path, dev.vitrail.pack.model.AlphaTest.OFF);
    }

    public static Prepared prepareTerrain(OpenedPack pack, String path, dev.vitrail.pack.model.AlphaTest alpha) throws IOException {
        return prepare(pack, path, terrainInputs(pack), alpha);
    }

    public static Prepared prepare(OpenedPack pack, String path, VertexInputs inputs) throws IOException {
        return prepare(pack, path, inputs, dev.vitrail.pack.model.AlphaTest.OFF);
    }

    private static Prepared prepare(OpenedPack pack, String path, VertexInputs inputs, dev.vitrail.pack.model.AlphaTest alpha) throws IOException {
        return prepare(pack, path, inputs, inputs.elements(), alpha);
    }

    /** The vertex format terrain programs of this pack are translated for. */
    static VertexInputs terrainInputs(OpenedPack pack) {
        var defines = pack.settings().globalDefines(pack.options());
        boolean separateAo = pack.properties().separateAo(defines), oldLighting = pack.properties().oldLighting(defines);
        return oldLighting ? (separateAo ? VertexInputs.VULKAN_TERRAIN_OLD_LIGHTING_SEPARATE_AO : VertexInputs.VULKAN_TERRAIN_OLD_LIGHTING)
                : separateAo ? VertexInputs.VULKAN_TERRAIN_SEPARATE_AO : VertexInputs.VULKAN_TERRAIN;
    }

    /**
     * Hands a program to the workers ahead of the plan asking for it, so that programs compile side by side while
     * the plan is still at an earlier one. The plan's own call then takes exactly this result; a request the plan
     * never makes costs worker time and changes nothing. Does nothing when preparation is sequential.
     */
    static void prefetch(OpenedPack pack, String path, VertexInputs inputs, java.util.List<String> elements,
                         dev.vitrail.pack.model.AlphaTest alpha) {
        if (!PreparePool.parallel()) return;
        try {
            task(pack, path, inputs, elements, alpha);
        } catch (IOException | RuntimeException later) {
            // The plan meets the same failure where it asks for the program.
        }
    }

    static Prepared prepare(OpenedPack pack, String path, VertexInputs inputs, java.util.List<String> elements,
                            dev.vitrail.pack.model.AlphaTest alpha) throws IOException {
        return join(task(pack, path, inputs, elements, alpha));
    }

    /** Flattens the stage files here (the pack source is single-threaded); translation and compilation go to a worker. */
    private static java.util.concurrent.Future<?> task(OpenedPack pack, String path, VertexInputs inputs, java.util.List<String> elements,
                            dev.vitrail.pack.model.AlphaTest alpha) throws IOException {
        var request = new Request(pack.settings(), path, inputs, java.util.List.copyOf(elements), alpha, false);
        var pending = PreparePool.parallel() ? pending(pack) : null;
        if (pending != null) {
            var known = pending.tasks.get(request);
            if (known != null && !known.isCancelled()) return known;
        }
        var expander = new IncludeExpander(pack.source(), pack.settings());
        var units = new EnumMap<ProgramStage, IncludeExpander.ExpandedUnit>(ProgramStage.class);
        for (ProgramStage stage : ProgramStage.values()) {
            // A compute of the same name is its own program (deferred1.csh beside deferred1.fsh).
            if (stage == ProgramStage.COMPUTE) continue;
            var file = pack.source().file(path + "." + stage.extension());
            if (file.isPresent()) units.put(stage, expander.expand(file.get()));
        }
        if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT))
            throw new IOException("Incomplete shader program: " + path);
        String bare = path.substring(path.lastIndexOf('/') + 1);
        var volumes = volumes(pack);
        var task = PreparePool.submit(() -> {
            requireWanted(pending);
            var translated = ProgramTranslator.translate(units, inputs, elements, alpha, false, bare, volumes);
            return new Prepared(path, translated, VulkanProgramCompiler.compile(path, translated));
        });
        if (pending != null) pending.tasks.put(request, task);
        return task;
    }

    /**
     * Volumes the pack ships, laid out flat. Every program declaring one is rewritten onto the same
     * atlas, which PackTexturePlan builds from the same first declaration.
     */
    public static java.util.Map<String, dev.vitrail.pack.texture.VolumeAtlas> volumes(OpenedPack pack) throws IOException {
        var defines = pack.settings().globalDefines(pack.options());
        return pack.source().derived(java.util.List.of(dev.vitrail.pack.texture.PackTextures.class, defines),
                () -> dev.vitrail.pack.texture.PackTextures.read(pack.properties(), defines, pack.source())).volumes();
    }
}
