package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vitrail.glsl.ProgramTranslator.TranslatedProgram;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.SPIRVUtils.SPIRV;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/** One native pack pass. CPU translation/compilation must finish before constructing on the render thread. */
public abstract class NativeGraphicsProgram implements AutoCloseable {
    protected final GraphicsPipeline pipeline;
    private final Std140Layout layout;
    private final ByteBuffer uniforms;
    private final Map<String, VulkanImage> images = new HashMap<>();
    private final Map<String, Boolean> samplers = new HashMap<>();
    private final java.util.Set<String> samplerNames;
    private boolean closed;

    protected NativeGraphicsProgram(String name, TranslatedProgram translated, VulkanProgramCompiler.Compiled compiled, VertexFormat format) {
        RenderSystem.assertOnRenderThread();
        if ((translated.inputs() != VertexInputs.FULLSCREEN && !translated.inputs().vulkanTerrain()) || compiled.stages().size() != 2
                || !compiled.stages().containsKey(ProgramStage.VERTEX) || !compiled.stages().containsKey(ProgramStage.FRAGMENT))
            throw new IllegalArgumentException("Native graphics program requires a supported input format and exactly vertex and fragment stages");
        var active = new HashSet<Integer>();
        compiled.stages().values().forEach(bytes -> active.addAll(SpirvInterface.bindings(bytes)));
        if (compiled.bindings().containsKey("DynamicTransforms"))
            throw new IllegalArgumentException("Native program unexpectedly reads unsupported per-draw game transforms");
        if (translated.stages().values().stream().anyMatch(unit -> !unit.notes().storageBlocks().isEmpty()))
            throw new IllegalArgumentException("Native graphics storage buffers are not implemented: " + name);
        layout = new Std140Layout(translated.uniforms());
        if (layout.size() > DeviceManager.deviceProperties.limits().maxUniformBufferRange())
            throw new IllegalArgumentException("Shader uniform block exceeds device limit: " + name);
        for (var sampler : translated.samplers()) {
            if (!active.contains(compiled.bindings().get(sampler.name()))) continue;
            if (!sampler.type().equals("sampler2D") && !sampler.type().equals("sampler2DShadow"))
                throw new IllegalArgumentException("Unsupported native graphics sampler: " + sampler.declaration());
            if (sampler.declaration().contains("["))
                throw new IllegalArgumentException("Native sampler arrays are not implemented: " + sampler.declaration());
            samplers.put(sampler.name(), sampler.type().equals("sampler2DShadow"));
        }
        if (samplers.size() > DeviceManager.deviceProperties.limits().maxPerStageDescriptorSamplers())
            throw new IllegalArgumentException("Shader sampler count exceeds device limit: " + name);
        samplerNames=java.util.Set.copyOf(samplers.keySet());
        uniforms = MemoryUtil.memCalloc(Math.max(16, layout.size()));
        try {
            var builder = new Pipeline.Builder(format, name);
            if (translated.inputs().vulkanTerrain()) {
                var push = new net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder();
                push.addUniformInfo("vec3", "vs_ChunkOffset");
                builder.setPushConstants(push.buildPushConstant());
            }
            var ubos = new ArrayList<UBO>();
            Integer globals = compiled.bindings().get("OfGlobals");
            if (globals != null && active.contains(globals)) {
                var ubo = new ManualUBO(globals, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, layout.size() / 4);
                ubo.setSrc(MemoryUtil.memAddress(uniforms), layout.size());
                ubos.add(ubo);
            }
            var descriptors = new ArrayList<ImageDescriptor>();
            for (var sampler : translated.samplers()) {
                if (!samplers.containsKey(sampler.name())) continue;
                descriptors.add(new ImageDescriptor(compiled.bindings().get(sampler.name()), sampler.type(), sampler.name(), 0) {
                    @Override public VulkanImage getImage() { return images.get(this.name); }
                    @Override public boolean usesVanillaTextureBinding() { return false; }
                    @Override public long getSampler(VulkanImage image) {
                        if (!samplers.get(this.name)) return image.getSampler();
                        // GL_LINEAR, CLAMP_TO_EDGE, COMPARE_REF_TO_TEXTURE, LEQUAL; cache owned by renderer.
                        return SamplerManager.getTextureSampler(new SamplerManager.SamplerKey(9729, 9729,
                                33071, 33071, 33071, 0, 0, 0, 34894, 515));
                    }
                });
            }
            builder.setUniforms(ubos, descriptors);
            try (var vertex = owned(compiled.stages().get(ProgramStage.VERTEX));
                 var fragment = owned(compiled.stages().get(ProgramStage.FRAGMENT))) {
                builder.setSPIRVs(vertex, fragment);
                pipeline = builder.createGraphicsPipeline();
            }
        } catch (Throwable failure) {
            MemoryUtil.memFree(uniforms);
            throw failure;
        }
    }

    public Std140Layout layout() { return layout; }
    public ByteBuffer uniforms() { requireOpen(); return uniforms; }
    public java.util.Set<String> requiredSamplers() { return samplerNames; }
    /** Build a render-pass-compatible Vulkan pipeline before the pack's first visible frame. */
    protected void prepare(PipelineState state) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        pipeline.getHandle(state);
    }
    public void bindImage(String name, VulkanImage image) {
        requireOpen();
        if (!samplers.containsKey(name)) throw new IllegalArgumentException("Program does not sample " + name);
        if (image == null) throw new IllegalArgumentException("Null image " + name);
        if (samplers.get(name) && !VulkanImage.isDepthFormat(image.format))
            throw new IllegalArgumentException("Comparison sampler requires depth image: " + name);
        images.put(name, image);
    }

    /** Caller chooses ordering and owns target lifetime. This method rejects read/write feedback. */
    protected void renderDraw(Framebuffer target, RenderPass pass, PipelineState state, java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> draw) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (!Renderer.isRecording()) throw new IllegalStateException("No active renderer frame");
        if (pass.getFramebuffer() != target) throw new IllegalArgumentException("Render pass target mismatch");
        for (String name : samplers.keySet()) {
            VulkanImage image = images.get(name);
            if (image == null) throw new IllegalStateException("Missing shader image " + name);
            for (int i = 0; i < target.getColorAttachmentCount(); i++)
                if (image == target.getColorAttachment(i)) throw new IllegalArgumentException("Shader image feedback: " + name);
            if (image == target.getDepthAttachment()) throw new IllegalArgumentException("Shader depth feedback: " + name);
        }
        Renderer renderer = Renderer.getInstance();
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var command = Renderer.getCommandBuffer();
            for (var image : images.values()) image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            target.beginRenderPass(command, pass, stack);
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getHandle(state));
            renderer.uploadAndBindUBOs(pipeline);
            vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).x(0).y(0)
                    .width(target.getWidth()).height(target.getHeight()).minDepth(0).maxDepth(1));
            var scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(target.getWidth(), target.getHeight());
            vkCmdSetScissor(command, 0, scissor);
            vkCmdSetDepthBias(command, 0, 0, 0);
            vkCmdSetBlendConstants(command, stack.floats(0, 0, 0, 0));
            draw.accept(command);
            for (int i = 0; i < target.getColorAttachmentCount(); i++) target.getColorAttachment(i).recordRasterOrigin(false);
        } finally {
            renderer.endRenderPass();
            renderer.restoreGraphicsPipeline(null);
        }
    }

    private static SPIRV owned(byte[] bytes) {
        ByteBuffer nativeBytes = MemoryUtil.memAlloc(bytes.length);
        nativeBytes.put(bytes).flip();
        return SPIRV.ownedBuffer(nativeBytes, () -> MemoryUtil.memFree(nativeBytes));
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Shader pass is closed"); }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        pipeline.scheduleCleanUp();
        images.clear();
        MemoryUtil.memFree(uniforms);
    }
}
