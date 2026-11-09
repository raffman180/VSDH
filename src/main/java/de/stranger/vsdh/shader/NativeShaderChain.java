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
    @FunctionalInterface public interface ExternalImages {
        VulkanImage resolve(String program, SamplerPlan.Binding input);
    }
    private record ViewKey(List<ShaderChainPlan.Attachment> attachments, long externalColour, long depth) {
        ViewKey { attachments = List.copyOf(attachments); }
    }
    public record View(Framebuffer framebuffer, RenderPass pass) implements AutoCloseable {
        @Override public void close() { pass.cleanUp(); framebuffer.cleanUp(false); }
    }
    private record RuntimePass(ShaderChainPlan.Pass source, NativeFullscreenPass pipeline, View output) {}
    private enum State { READY, FRAME, FAILED, CLOSED }

    private final ShaderChainPlan plan;
    private final NativeColorTargets targets;
    private final Map<ShaderChainPlan.Stage, List<RuntimePass>> stages = new EnumMap<>(ShaderChainPlan.Stage.class);
    private final Map<ViewKey, View> views = new HashMap<>();
    private State state = State.READY;
    private int nextStage;
    private long frames, drawCalls;

    public NativeShaderChain(ShaderChainPlan plan, int width, int height, long colorBudgetBytes) {
        RenderSystem.assertOnRenderThread();
        this.plan = plan;
        var layout = ShaderTargetLayout.of(plan.targets(), width, height,
                DeviceManager.deviceProperties.limits().maxImageDimension2D(), colorBudgetBytes);
        targets = new NativeColorTargets(layout);
        try {
            for (var stage : ShaderChainPlan.Stage.values()) {
                var runtime = new ArrayList<RuntimePass>();
                stages.put(stage, runtime);
                for (var source : plan.stages().get(stage)) {
                    View view = source.outputs().isEmpty() ? null : view(source.outputs(), null);
                    var pipeline = new NativeFullscreenPass(source.program().path(), source.program().translated(), source.program().compiled());
                    runtime.add(new RuntimePass(source, pipeline, view));
                    if (view != null) pipeline.prepare(view.pass());
                    for (var input : source.inputs())
                        if (input.kind() == SamplerPlan.Kind.COLORTEX)
                            pipeline.bindImage(input.sampler(), targets.image(input.index(), input.side()));
                }
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    public NativeColorTargets targets() { requireUsable(); return targets; }
    public ShaderChainPlan plan() { return plan; }
    public long framesCompleted() { return frames; }
    public long drawCalls() { return drawCalls; }

    public void prepareOutput(VulkanImage output) {
        RenderSystem.assertOnRenderThread();
        requireUsable();
        var view = externalView(output);
        for (var pass : stages.get(ShaderChainPlan.Stage.FINAL)) pass.pipeline().prepare(view.pass());
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
        RenderSystem.assertOnRenderThread();
        requireUsable();
        if (state != State.FRAME || nextStage != stage.ordinal())
            throw new IllegalStateException("Shader stage out of order: " + stage + ", expected index " + nextStage);
        try {
            if (stage == ShaderChainPlan.Stage.FINAL && finalOutput == null)
                throw new IllegalArgumentException("Final shader output is required");
            for (RuntimePass runtime : stages.get(stage)) {
                var source = runtime.source();
                for (var input : source.inputs()) {
                    if (input.kind() == SamplerPlan.Kind.COLORTEX) continue;
                    VulkanImage image = external.resolve(source.name(), input);
                    if (image == null) throw new IllegalStateException("Missing external shader image: " + source.name() + "/" + input.sampler());
                    runtime.pipeline().bindImage(input.sampler(), image);
                }
                var step = plan.targets().schedule().step(source.name()).orElseThrow();
                for (int index : source.mipmaps()) targets.mipmaps(index, step.read(index));
                uniforms.update(source, runtime.pipeline());
                View output = runtime.output() == null ? externalView(finalOutput) : runtime.output();
                runtime.pipeline().render(output.framebuffer(), output.pass());
                drawCalls++;
            }
            if (stage == ShaderChainPlan.Stage.FINAL) {
                if (stages.get(stage).isEmpty()) presentWithoutFinal(finalOutput);
                targets.copyBack(plan.copyBack());
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
        RenderSystem.assertOnRenderThread();
        requireUsable();
        var step = (afterDeferred ? plan.targets().schedule().stepAfterDeferred(program)
                : plan.targets().schedule().step(program)).orElseThrow(() -> new IllegalArgumentException("Unknown geometry program " + program));
        var attachments = plan.targets().writes(program).stream()
                .map(index -> new ShaderChainPlan.Attachment(index, step.write(index))).toList();
        return view(attachments, depth);
    }

    private View view(List<ShaderChainPlan.Attachment> attachments, VulkanImage depth) {
        if (attachments.isEmpty() || attachments.size() > DeviceManager.deviceProperties.limits().maxColorAttachments())
            throw new IllegalArgumentException("Invalid shader color output count: " + attachments.size());
        ViewKey key = new ViewKey(attachments, 0, depth == null ? 0 : depth.getId());
        return views.computeIfAbsent(key, ignored -> {
            VulkanImage[] colors = attachments.stream().map(a -> targets.image(a.target(), a.side())).toArray(VulkanImage[]::new);
            return createView(colors, depth);
        });
    }
    private View externalView(VulkanImage color) {
        if (color == null) throw new IllegalArgumentException("Missing presentation image");
        ViewKey key = new ViewKey(List.of(), color.getId(), 0);
        return views.computeIfAbsent(key, ignored -> createView(new VulkanImage[]{color}, null));
    }
    private static View createView(VulkanImage[] colors, VulkanImage depth) {
        var framebuffer = Framebuffer.builder(colors, depth).ownsAttachments(false).build();
        try {
            var builder = RenderPass.builder(framebuffer).setLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            if (depth != null) builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
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
        NativeImageOps.blitColour(source, destination);
    }
    private void requireUsable() {
        if (state == State.CLOSED || state == State.FAILED) throw new IllegalStateException("Shader chain is " + state);
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        for (var passes : stages.values()) for (var pass : passes) pass.pipeline().close();
        views.values().forEach(View::close);
        views.clear();
        targets.close();
    }
}
