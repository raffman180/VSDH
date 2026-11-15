package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetDirectives.Colour;
import dev.vitrail.pack.target.TargetSchedule.Side;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/** Ordered fullscreen half of a world pipeline. Geometry/shadow hooks run between its explicit stages. */
public final class NativeShaderChain implements AutoCloseable {
    @FunctionalInterface public interface UniformWriter {
        void update(ShaderChainPlan.Pass source, NativeFullscreenPass pipeline);
    }
    @FunctionalInterface public interface ComputeUniformWriter {
        void update(ShaderChainPlan.Compute source, NativeComputePass pipeline);
    }
    @FunctionalInterface public interface ExternalImages {
        VulkanImage resolve(String program, SamplerPlan.Binding input);
    }
    /** Views are keyed by the images themselves: target halves swap roles between frames. */
    private record ViewKey(List<Long> colours, long depth, int loops) {
        ViewKey { colours = List.copyOf(colours); }
    }
    /** Bit of {@link ViewKey#loops} for the depth attachment; bit N stands for colour attachment N. */
    private static final int DEPTH_LOOP = 1 << 31;
    public record View(Framebuffer framebuffer, RenderPass pass) implements AutoCloseable {
        @Override public void close() { pass.cleanUp(); framebuffer.cleanUp(false); }
    }
    private record RuntimeCompute(ShaderChainPlan.Compute source, NativeComputePass pipeline) {}
    private record RuntimePass(ShaderChainPlan.Pass source, NativeFullscreenPass pipeline, List<ShaderChainPlan.Attachment> outputs,
                               List<RuntimeCompute> computes) {
        boolean external() { return outputs.isEmpty(); }
    }
    private enum State { READY, FRAME, FAILED, CLOSED }

    private final ShaderChainPlan plan;
    private final NativeColorTargets targets;
    private final Map<ShaderChainPlan.Stage, List<RuntimePass>> stages = new EnumMap<>(ShaderChainPlan.Stage.class);
    private final Map<ViewKey, View> views = new HashMap<>();
    /** Persistent targets written only by unblended fullscreen passes: swapped instead of copied back. */
    private final java.util.Set<Integer> swappable;
    private final List<RuntimeCompute> setup = new ArrayList<>();
    private final List<RuntimeCompute> shadowComputes = new ArrayList<>();
    private boolean setupDone;
    private State state = State.READY;
    private int nextStage;
    private long frames;
    private final int width, height;

    public NativeShaderChain(ShaderChainPlan plan, int width, int height, long colorBudgetBytes) {
        this(plan, width, height, colorBudgetBytes, Map.of());
    }

    /**
     * @param geometryReads colour targets each geometry program samples (FeedbackLoops.colourReads); those it
     *                      also draws into are bound live as feedback loops. Empty: always snapshot copies.
     */
    public NativeShaderChain(ShaderChainPlan plan, int width, int height, long colorBudgetBytes,
                             Map<String, java.util.Set<Integer>> geometryReads) {
        RenderSystem.assertOnRenderThread();
        this.plan = plan;
        this.geometryReads = Map.copyOf(geometryReads);
        this.width = width;
        this.height = height;
        var layout = ShaderTargetLayout.of(plan.targets(), width, height,
                DeviceManager.deviceProperties.limits().maxImageDimension2D(), colorBudgetBytes);
        var storage = new java.util.HashSet<Integer>();
        for (var passes : plan.stages().values()) for (var pass : passes) {
            for (var compute : pass.computes()) compute.images().values().forEach(image -> storage.add(image.target()));
            pass.images().values().forEach(image -> storage.add(image.target()));
        }
        for (var compute : plan.setup()) compute.images().values().forEach(image -> storage.add(image.target()));
        var loops = new java.util.HashSet<Integer>();
        for (var step : plan.targets().schedule().steps()) {
            if (step.fullscreen()) continue;
            var reads = this.geometryReads.getOrDefault(FeedbackLoops.bare(step.program()), java.util.Set.of());
            var writes = plan.targets().writes(step.program());
            loops.addAll(FeedbackLoops.loops(step, writes, reads));
            plan.targets().schedule().stepAfterDeferred(step.program())
                    .ifPresent(late -> loops.addAll(FeedbackLoops.loops(late, writes, reads)));
        }
        targets = new NativeColorTargets(layout, colorBudgetBytes, storage, loops);
        if (!loops.isEmpty()) {
            var described = new java.util.TreeMap<String, java.util.Set<Integer>>();
            for (var step : plan.targets().schedule().steps()) {
                if (step.fullscreen()) continue;
                String name = FeedbackLoops.bare(step.program());
                var found = new java.util.TreeSet<>(loops(name, false));
                found.addAll(loops(name, true));
                if (!found.isEmpty()) described.put(name, found);
            }
            org.slf4j.LoggerFactory.getLogger("VSDH").info("Attachment feedback loops (colortex sampled while drawn into): {}", described);
        }
        swappable = swappable(plan);
        if (PersistentSwap.AFTER_GEOMETRY)
            org.slf4j.LoggerFactory.getLogger("VSDH").info("Persistent targets swapped instead of copied back (swapAfterGeometry): {} of {}",
                    new java.util.TreeSet<>(swappable), plan.copyBack());
        try {
            for (var compute : plan.setup()) setup.add(runtimeCompute(compute));
            for (var compute : plan.shadowComputes()) shadowComputes.add(runtimeCompute(compute));
            for (var stage : ShaderChainPlan.Stage.values()) {
                var runtime = new ArrayList<RuntimePass>();
                stages.put(stage, runtime);
                for (var source : plan.stages().get(stage)) {
                    var computes = new ArrayList<RuntimeCompute>();
                    try {
                        for (var compute : source.computes()) computes.add(runtimeCompute(compute));
                    } catch (Throwable failure) {
                        computes.forEach(compute -> compute.pipeline().close());
                        throw failure;
                    }
                    if (!source.draws()) {
                        runtime.add(new RuntimePass(source, null, List.of(), computes));
                        continue;
                    }
                    View view = source.outputs().isEmpty() ? null : view(source.outputs(), null, 0);
                    // Iris presents FINAL with blending disabled. Earlier passes can blend into
                    // their scheduled destination, whose existing contents are loaded by the pass.
                    int blend = stage == ShaderChainPlan.Stage.FINAL ? 0
                            : plan.targets().blend(source.name()).map(NativeBlend::encode).orElse(0);
                    var pipeline = new NativeFullscreenPass(source.program().path(), source.program().translated(),
                            source.program().compiled(), blend);
                    runtime.add(new RuntimePass(source, pipeline, source.outputs(), computes));
                    if (view != null) pipeline.prepare(view.pass());
                    for (var input : source.inputs())
                        if (input.kind() == SamplerPlan.Kind.COLORTEX)
                            pipeline.bindImage(input.sampler(), targets.image(input.index(), input.side()));
                    for (var image : source.images().entrySet())
                        pipeline.bindStorage(image.getKey(), targets.image(image.getValue().target(), image.getValue().side()));
                }
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    private RuntimeCompute runtimeCompute(ShaderChainPlan.Compute compute) {
        var pass = new NativeComputePass(compute.program());
        try {
            for (var input : compute.inputs())
                if (input.kind() == SamplerPlan.Kind.COLORTEX)
                    pass.bindImage(input.sampler(), targets.image(input.index(), input.side()));
            for (var image : compute.images().entrySet())
                pass.bindStorage(image.getKey(), targets.image(image.getValue().target(), image.getValue().side()));
            return new RuntimeCompute(compute, pass);
        } catch (Throwable failure) {
            pass.close();
            throw failure;
        }
    }

    /** Shadow colour target N for shadowcolorimgN; set by the session once its shadow map exists. */
    private java.util.function.IntFunction<VulkanImage> shadowColours = index -> null;
    public void shadowColours(java.util.function.IntFunction<VulkanImage> lookup) { shadowColours = lookup; }

    public static java.util.Set<Integer> swappable(ShaderChainPlan plan) {
        var unsafe = new java.util.HashSet<Integer>();
        var targetPlan = plan.targets();
        for (var step : targetPlan.schedule().steps()) {
            var writes = targetPlan.writes(step.program());
            for (int rank = 0; rank < writes.size(); rank++) {
                int index = writes.get(rank);
                boolean blended = targetPlan.blend(step.program(), rank).filter(mode -> !mode.off()).isPresent();
                if (!step.fullscreen() || blended) unsafe.add(index);
            }
        }
        // Image stores write any subset of the texels.
        for (var passes : plan.stages().values()) for (var pass : passes) {
            pass.images().values().forEach(attachment -> unsafe.add(attachment.target()));
            pass.computes().forEach(compute -> compute.images().values().forEach(attachment -> unsafe.add(attachment.target())));
        }
        plan.setup().forEach(compute -> compute.images().values().forEach(attachment -> unsafe.add(attachment.target())));
        var result = new java.util.HashSet<>(plan.copyBack());
        result.removeAll(unsafe);
        // Opt-in (-Dvsdh.swapAfterGeometry=true): also targets geometry draws into, see PersistentSwap.
        if (PersistentSwap.AFTER_GEOMETRY) result.addAll(PersistentSwap.afterGeometry(plan));
        return java.util.Set.copyOf(result);
    }

    /** The pass's target halves as they stand this frame (they swap roles, see NativeColorTargets.copyBack). */
    private void bindTargets(ShaderChainPlan.Pass source, NativeFullscreenPass pipeline) {
        for (var input : source.inputs())
            if (input.kind() == SamplerPlan.Kind.COLORTEX)
                pipeline.bindImage(input.sampler(), targets.image(input.index(), input.side()));
        for (var image : source.images().entrySet())
            pipeline.bindStorage(image.getKey(), targets.image(image.getValue().target(), image.getValue().side()));
    }

    private void dispatch(RuntimeCompute compute, ComputeUniformWriter computeUniforms, ExternalImages external) {
        for (var input : compute.source().inputs())
            if (input.kind() == SamplerPlan.Kind.COLORTEX)
                compute.pipeline().bindImage(input.sampler(), targets.image(input.index(), input.side()));
        for (var image : compute.source().images().entrySet())
            compute.pipeline().bindStorage(image.getKey(), targets.image(image.getValue().target(), image.getValue().side()));
        for (var entry : compute.source().shadowImages().entrySet()) {
            VulkanImage image = shadowColours.apply(entry.getValue());
            if (image == null) throw new IllegalStateException("Missing shadow colour image: " + compute.source().name() + "/" + entry.getKey());
            compute.pipeline().bindStorage(entry.getKey(), image);
        }
        for (var input : compute.source().inputs()) {
            if (!ShaderChainPlan.external(input)) continue;
            VulkanImage image = external.resolve(compute.source().name(), input);
            if (image == null) throw new IllegalStateException("Missing external compute image: " + compute.source().name() + "/" + input.sampler());
            compute.pipeline().bindImage(input.sampler(), image);
        }
        computeUniforms.update(compute.source(), compute.pipeline());
        var indirect = compute.source().indirect();
        if (indirect != null) {
            var buffers = NativeStorageBuffers.current();
            long buffer = buffers == null ? VK_NULL_HANDLE : buffers.buffer(indirect.buffer());
            if (buffer == VK_NULL_HANDLE)
                throw new IllegalStateException("Missing indirect dispatch buffer: " + compute.source().name() + "/bufferObject." + indirect.buffer());
            compute.pipeline().dispatchIndirect(buffer, indirect.offset());
        } else {
            int[] groups = compute.source().program().groups(width, height);
            compute.pipeline().dispatch(groups[0], groups[1], groups[2]);
        }
        net.vulkanmod.vulkan.queries.GpuTimestamps.mark(compute.source().name());
    }

    /** Iris' shadowcomp computes: after the shadow pass of this frame, before PREPARE. */
    public void renderShadowComputes(ComputeUniformWriter computeUniforms, ExternalImages external) {
        RenderSystem.assertOnRenderThread();
        requireUsable();
        if (state != State.FRAME || nextStage != ShaderChainPlan.Stage.PREPARE.ordinal())
            throw new IllegalStateException("shadowcomp must run between BEGIN and PREPARE");
        try {
            for (var compute : shadowComputes) dispatch(compute, computeUniforms, external);
        } catch (Throwable failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    public NativeColorTargets targets() { requireUsable(); return targets; }
    public ShaderChainPlan plan() { return plan; }
    public long framesCompleted() { return frames; }

    /**
     * FINAL draws GL-oriented (rows bottom-up) like every pass of the chain; this image takes it and
     * is flipped once into the top-down presentation image. Same size and format as that image.
     */
    private VulkanImage finalImage;
    private VulkanImage finalImage(VulkanImage output) {
        if (finalImage != null && finalImage.width == output.width && finalImage.height == output.height
                && finalImage.format == output.format) return finalImage;
        if (finalImage != null) {
            lastViews.clear();
            var stale = views.remove(new ViewKey(List.of(finalImage.getId()), 0, 0));
            if (stale != null) stale.close();
            finalImage.free();
        }
        finalImage = VulkanImage.builder(output.width, output.height).setFormat(output.format)
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(false).setClamp(true).createVulkanImage();
        return finalImage;
    }

    public void prepareOutput(VulkanImage output) {
        RenderSystem.assertOnRenderThread();
        requireUsable();
        var view = externalView(finalImage(output));
        for (var pass : stages.get(ShaderChainPlan.Stage.FINAL)) if (pass.pipeline() != null) pass.pipeline().prepare(view.pass());
    }

    public void beginFrame(Colour fogColour) {
        RenderSystem.assertOnRenderThread();
        requireUsable();
        if (state != State.READY) throw new IllegalStateException("Previous shader frame has not finished");
        if (!Renderer.isRecording()) throw new IllegalStateException("Shader chain requires an active renderer frame");
        state = State.FRAME;
        nextStage = 0;
        try { targets.beginFrame(fogColour); }
        catch (Throwable failure) { state = State.FAILED; throw failure; }
    }

    /** Execute even empty stages so callers cannot silently draw deferred/composite at the wrong boundary. */
    public void render(ShaderChainPlan.Stage stage, VulkanImage finalOutput, UniformWriter uniforms, ExternalImages external) {
        render(stage, finalOutput, uniforms, (source, pipeline) -> {
            throw new IllegalStateException("A compute uniform writer is required for " + source.name());
        }, external);
    }

    public void render(ShaderChainPlan.Stage stage, VulkanImage finalOutput, UniformWriter uniforms,
                       ComputeUniformWriter computeUniforms, ExternalImages external) {
        RenderSystem.assertOnRenderThread();
        requireUsable();
        if (state != State.FRAME || nextStage != stage.ordinal())
            throw new IllegalStateException("Shader stage out of order: " + stage + ", expected index " + nextStage);
        try {
            if (stage == ShaderChainPlan.Stage.FINAL && finalOutput == null)
                throw new IllegalArgumentException("Final shader output is required");
            // Once per chain, on its first frame after the initial target clear (Iris: at pipeline build).
            if (stage == ShaderChainPlan.Stage.BEGIN && !setupDone) {
                for (var compute : setup) dispatch(compute, computeUniforms, external);
                setupDone = true;
                // Once per graph: the proof in the log that a pack's setup1..setupN ran (Jurgens' cloud noise).
                if (!setup.isEmpty())
                    org.slf4j.LoggerFactory.getLogger("VSDH").info("Setup computes dispatched once: {}; custom images: {}",
                            setup.stream().map(compute -> compute.source().name()).toList(),
                            plan.images().images().stream().map(dev.vitrail.pack.model.ImageInformation::describe).toList());
            }
            boolean drew = false;
            for (RuntimePass runtime : stages.get(stage)) {
                var source = runtime.source();
                for (var compute : runtime.computes()) dispatch(compute, computeUniforms, external);
                if (runtime.pipeline() == null) continue;
                drew = true;
                for (var input : source.inputs()) {
                    if (!ShaderChainPlan.external(input)) continue;
                    VulkanImage image = external.resolve(source.name(), input);
                    if (image == null) throw new IllegalStateException("Missing external shader image: " + source.name() + "/" + input.sampler());
                    runtime.pipeline().bindImage(input.sampler(), image);
                }
                var step = plan.targets().schedule().step(source.name()).orElseThrow();
                if (!source.mipmaps().isEmpty()) targets.mipmaps(source.mipmaps(), step::read);
                if (net.vulkanmod.vulkan.queries.GpuTimestamps.ENABLED && !source.mipmaps().isEmpty())
                    net.vulkanmod.vulkan.queries.GpuTimestamps.mark(source.name() + "/mips");
                uniforms.update(source, runtime.pipeline());
                bindTargets(source, runtime.pipeline());
                View output = runtime.external() ? externalView(finalImage(finalOutput)) : view(runtime.outputs(), null, 0);
                // Layout transitions and barriers recorded so far, apart from the pass itself (Iris: sN/setupM).
                if (net.vulkanmod.vulkan.queries.GpuTimestamps.ENABLED) {
                    runtime.pipeline().transitionInputs();
                    net.vulkanmod.vulkan.queries.GpuTimestamps.mark(source.name() + "/setup");
                }
                runtime.pipeline().render(output.framebuffer(), output.pass());
                net.vulkanmod.vulkan.queries.GpuTimestamps.mark(source.name());
            }
            if (stage == ShaderChainPlan.Stage.FINAL) {
                if (!drew) presentWithoutFinal(finalOutput);
                else NativeImageOps.blitColour(finalImage(finalOutput), finalOutput, true);
                targets.copyBack(plan.copyBack(), swappable);
                net.vulkanmod.vulkan.queries.GpuTimestamps.mark("copyBack");
                state = State.READY;
                frames++;
            }
            nextStage++;
        } catch (Throwable failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    /** Borrowed geometry outputs on the same schedule; no fallback onto the normal world framebuffer. */
    public View geometry(String program, boolean afterDeferred, VulkanImage depth) {
        return geometry(program, afterDeferred, depth, false);
    }

    /** @param depthLoop the program samples {@code depth} itself while drawing into it (DH depth, FeedbackLoops) */
    public View geometry(String program, boolean afterDeferred, VulkanImage depth, boolean depthLoop) {
        RenderSystem.assertOnRenderThread();
        requireUsable();
        var memo = afterDeferred ? geometryOutputsLate : geometryOutputs;
        var attachments = memo.get(program);
        if (attachments == null) {
            var step = (afterDeferred ? plan.targets().schedule().stepAfterDeferred(program)
                    : plan.targets().schedule().step(program)).orElseThrow(() -> new IllegalArgumentException("Unknown geometry program " + program));
            attachments = plan.targets().writes(program).stream()
                    .map(index -> new ShaderChainPlan.Attachment(index, step.write(index))).toList();
            memo.put(program, attachments);
            var loops = loops(program, afterDeferred);
            int mask = 0;
            for (int i = 0; i < attachments.size(); i++) if (loops.contains(attachments.get(i).target())) mask |= 1 << i;
            geometryLoopMasks.put(attachments, mask);
        }
        return view(attachments, depth, geometryLoopMasks.get(attachments) | (depthLoop && depth != null ? DEPTH_LOOP : 0));
    }

    private final Map<String, java.util.Set<Integer>> geometryReads;
    private final Map<String, java.util.Set<Integer>> loopMemo = new HashMap<>(), loopMemoLate = new HashMap<>();
    /** By list identity, like {@link #lastViews}: the lists are the memoised ones of {@link #geometry}. */
    private final java.util.IdentityHashMap<List<ShaderChainPlan.Attachment>, Integer> geometryLoopMasks = new java.util.IdentityHashMap<>();

    /** Targets this program samples from the half it draws into at that moment, bound live (see FeedbackLoops). */
    private java.util.Set<Integer> loops(String program, boolean afterDeferred) {
        var memo = afterDeferred ? loopMemoLate : loopMemo;
        var found = memo.get(program);
        if (found == null) {
            var reads = geometryReads.getOrDefault(program, java.util.Set.of());
            var step = (afterDeferred ? plan.targets().schedule().stepAfterDeferred(program) : plan.targets().schedule().step(program));
            var result = new java.util.HashSet<Integer>();
            if (step.isPresent() && !reads.isEmpty())
                for (int index : FeedbackLoops.loops(step.get(), plan.targets().writes(program), reads))
                    if (targets.loop(index)) result.add(index);
            found = java.util.Set.copyOf(result);
            memo.put(program, found);
        }
        return found;
    }

    /**
     * The outputs of a geometry program depend on the immutable plan alone. One list per program and moment:
     * this is asked for every geometry draw, and {@link #view} remembers its last answer per list identity. A fresh
     * list per call never matched there and left one more entry behind each time (about 200 per frame with
     * Jurgens), so the map grew without bound until the next resize of the window.
     */
    private final Map<String, List<ShaderChainPlan.Attachment>> geometryOutputs = new HashMap<>(), geometryOutputsLate = new HashMap<>();
    private final Map<String, List<ShaderChainPlan.Attachment>> sceneOutputs = new HashMap<>(), sceneOutputsLate = new HashMap<>();

    VulkanImage geometrySampler(String program, int index, boolean afterDeferred) {
        var step = (afterDeferred ? plan.targets().schedule().stepAfterDeferred(program)
                : plan.targets().schedule().step(program)).orElseThrow();
        var source = targets.image(index, step.read(index));
        if (plan.targets().writes(program).contains(index) && source == targets.image(index, step.write(index))) {
            // Live attachment where the pass was built as a feedback loop, else a snapshot from before the draw.
            if (loops(program, afterDeferred).contains(index)) return source;
            return targets.feedbackImage(index, source);
        }
        return source;
    }

    /** Vanilla fallback shaders emit only location zero, independently of a pack's multi-output basic program. */
    public View scene(String program,boolean afterDeferred,VulkanImage depth) {
        var memo=afterDeferred?sceneOutputsLate:sceneOutputs;
        var attachments=memo.get(program);
        if(attachments==null) {
            attachments=sceneAttachments(program,afterDeferred);
            memo.put(program,attachments);
        }
        return view(attachments,depth,0);
    }

    private List<ShaderChainPlan.Attachment> sceneAttachments(String program,boolean afterDeferred) {
        var schedule=plan.targets().schedule();
        var step=afterDeferred?schedule.stepAfterDeferred(program):schedule.step(program);
        if(step.isPresent())return List.of(new ShaderChainPlan.Attachment(0,step.get().write(0)));
        // A pack without the program (Miniature ships no gbuffers_basic): the vanilla draw still lands
        // in colortex0, on the half every geometry step of that moment writes, i.e. the half it reads.
        var geometry=schedule.steps().stream().filter(s->!s.fullscreen()).findFirst()
                .flatMap(s->afterDeferred?schedule.stepAfterDeferred(s.program()):java.util.Optional.of(s));
        Side side=geometry.map(s->s.read(0)).orElse(Side.MAIN);
        return List.of(new ShaderChainPlan.Attachment(0,side));
    }
    /** Exposed to the existing frame observer for diagnostics; the chain retains ownership. */
    public NativeFullscreenPass program(String name) {
        return stages.values().stream().flatMap(java.util.Collection::stream)
                .filter(p->p.source().name().equals(name) && p.pipeline()!=null).map(RuntimePass::pipeline).findFirst().orElseThrow();
    }

    /** Last answer per attachment list: images are looked up per frame, but rarely change. */
    private final java.util.IdentityHashMap<List<ShaderChainPlan.Attachment>, CachedView> lastViews = new java.util.IdentityHashMap<>();
    private record CachedView(VulkanImage[] colors, VulkanImage depth, int loops, View view) {}

    private View view(List<ShaderChainPlan.Attachment> attachments, VulkanImage depth, int loops) {
        var last = lastViews.get(attachments);
        if (last != null && last.depth() == depth && last.loops() == loops && last.colors().length == attachments.size()) {
            boolean same = true;
            for (int i = 0; same && i < attachments.size(); i++) {
                var a = attachments.get(i);
                same = targets.image(a.target(), a.side()) == last.colors()[i];
            }
            if (same) return last.view();
        }
        View found = lookupView(attachments, depth, loops);
        VulkanImage[] colors = new VulkanImage[attachments.size()];
        for (int i = 0; i < colors.length; i++) colors[i] = targets.image(attachments.get(i).target(), attachments.get(i).side());
        lastViews.put(attachments, new CachedView(colors, depth, loops, found));
        return found;
    }

    private View lookupView(List<ShaderChainPlan.Attachment> attachments, VulkanImage depth, int loops) {
        if (attachments.isEmpty() || attachments.size() > DeviceManager.deviceProperties.limits().maxColorAttachments())
            throw new IllegalArgumentException("Invalid shader color output count: " + attachments.size());
        VulkanImage[] colors = attachments.stream().map(a -> targets.image(a.target(), a.side())).toArray(VulkanImage[]::new);
        var ids = new ArrayList<Long>(colors.length);
        for (VulkanImage color : colors) ids.add(color.getId());
        ViewKey key = new ViewKey(ids, depth == null ? 0 : depth.getId(), loops);
        return views.computeIfAbsent(key, ignored -> createView(colors, depth, loops));
    }
    private View externalView(VulkanImage color) {
        if (color == null) throw new IllegalArgumentException("Missing presentation image");
        ViewKey key = new ViewKey(List.of(color.getId()), 0, 0);
        return views.computeIfAbsent(key, ignored -> createView(new VulkanImage[]{color}, null, 0));
    }
    private static View createView(VulkanImage[] colors, VulkanImage depth, int loops) {
        var framebuffer = Framebuffer.builder(colors, depth).ownsAttachments(false).build();
        try {
            var builder = RenderPass.builder(framebuffer).setLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            if (depth != null) builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
            // Attachments the pass samples itself use the feedback-loop layout (their images carry the usage).
            for (int i = 0; i < colors.length; i++) if ((loops & 1 << i) != 0) builder.getColorAttachmentInfo(i).setFeedbackLoop();
            if ((loops & DEPTH_LOOP) != 0) builder.getDepthAttachmentInfo().setFeedbackLoop();
            return new View(framebuffer, builder.build());
        } catch (Throwable failure) { framebuffer.cleanUp(false); throw failure; }
    }
    private void presentWithoutFinal(VulkanImage destination) {
        Side side = plan.targets().schedule().flippedAtEnd().contains(0) ? Side.ALT : Side.MAIN;
        var source = targets.image(0, side);
        var spec = targets.layout().targets().stream().filter(t -> t.index() == 0).findFirst().orElseThrow();
        if (spec.format().integer()) throw new IllegalStateException("Integer colortex0 needs a typed presentation shader");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, source.format, props);
            if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_SRC_BIT) == 0)
                throw new IllegalStateException("Device cannot present colortex0 without a final shader");
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, destination.format, props);
            if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0)
                throw new IllegalStateException("Device cannot blit to presentation image");
        }
        NativeImageOps.blitColour(source, destination, true);
    }
    private void requireUsable() {
        if (state == State.CLOSED || state == State.FAILED) throw new IllegalStateException("Shader chain is " + state);
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        setup.forEach(compute -> compute.pipeline().close());
        shadowComputes.forEach(compute -> compute.pipeline().close());
        for (var passes : stages.values()) for (var pass : passes) {
            if (pass.pipeline() != null) pass.pipeline().close();
            pass.computes().forEach(compute -> compute.pipeline().close());
        }
        lastViews.clear();
        views.values().forEach(View::close);
        views.clear();
        if (finalImage != null) { finalImage.free(); finalImage = null; }
        targets.close();
    }
}
