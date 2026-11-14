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
public abstract class NativeGraphicsProgram implements NativeUniformProgram, AutoCloseable {
    protected final GraphicsPipeline pipeline;
    private final Std140Layout layout;
    private final java.util.List<Std140Layout.Member> activeUniforms;
    private final ByteBuffer uniforms;
    private final Map<String, VulkanImage> images = new HashMap<>();
    private final Map<String, String> samplers = new HashMap<>();
    private final java.util.Set<String> samplerNames;
    /** colorimgN the program stores into, by name, with the VkFormat its qualifier names. */
    private final Map<String, Integer> colourImages = new HashMap<>();
    private final Map<String, ImageDescriptor> sampledDescriptors = new HashMap<>();
    private final boolean storage;
    private boolean closed;

    protected NativeGraphicsProgram(String name, TranslatedProgram translated, VulkanProgramCompiler.Compiled compiled, VertexFormat format) {
        RenderSystem.assertOnRenderThread();
        if ((translated.inputs() != VertexInputs.FULLSCREEN && !translated.inputs().vulkanTerrain() && translated.inputs() != VertexInputs.DISTANT
                && translated.inputs() != VertexInputs.SKY && translated.inputs() != VertexInputs.PARTICLE
                && !translated.inputs().nativeDrawTransforms())
                || !compiled.stages().containsKey(ProgramStage.VERTEX) || !compiled.stages().containsKey(ProgramStage.FRAGMENT)
                || !PackProgramSource.graphicsStages(compiled.stages().keySet()))
            throw new IllegalArgumentException("Native graphics program requires a supported input format and vertex, optional geometry and fragment stages");
        if (compiled.stages().containsKey(ProgramStage.GEOMETRY) && !net.vulkanmod.vulkan.device.DeviceManager.isGeometryShaderEnabled())
            throw new IllegalArgumentException("Device has no geometry shaders, needed by " + name);
        if (compiled.stages().containsKey(ProgramStage.TESSELLATION_CONTROL) && !net.vulkanmod.vulkan.device.DeviceManager.isTessellationEnabled())
            throw new IllegalArgumentException("Device has no tessellation shaders, needed by " + name);
        var active = new HashSet<Integer>();
        compiled.stages().values().forEach(bytes -> active.addAll(SpirvInterface.bindings(bytes)));
        if (compiled.bindings().containsKey("DynamicTransforms"))
            throw new IllegalArgumentException("Native program unexpectedly reads unsupported per-draw game transforms");
        var storageBuffers = storageDescriptors(name, compiled, active, VK_SHADER_STAGE_ALL_GRAPHICS);
        var customImages = CustomImageBindings.of(name, translated, compiled, active);
        var formats = new HashMap<Integer, String>();
        for (String source : compiled.sources().values()) {
            var matcher = java.util.regex.Pattern.compile("layout\\(\\s*(\\w+)\\s*,[^)]*binding\\s*=\\s*(\\d+)\\s*\\)").matcher(source);
            while (matcher.find()) formats.putIfAbsent(Integer.parseInt(matcher.group(2)), matcher.group(1));
        }
        for (var sampler : translated.samplers()) {
            Integer binding = compiled.bindings().get(sampler.name());
            if (binding == null || !active.contains(binding) || customImages.names().contains(sampler.name())) continue;
            if (!sampler.name().matches("colorimg\\d+")) continue;
            if (!sampler.type().matches("[iu]?image2D") || sampler.declaration().contains("["))
                throw new IllegalArgumentException("Unsupported colour image " + sampler.declaration() + " in " + name);
            Integer imageFormat = formats.get(binding) == null ? null : NativeComputePass.vulkanFormat(formats.get(binding));
            if (imageFormat == null) throw new IllegalArgumentException("Colour image without a supported format qualifier: " + name + "/" + sampler.name());
            colourImages.put(sampler.name(), imageFormat);
        }
        storage = !storageBuffers.isEmpty() || customImages.stores() || !colourImages.isEmpty();
        if (storage && (!net.vulkanmod.vulkan.device.DeviceManager.isFragmentStoresEnabled()
                || !net.vulkanmod.vulkan.device.DeviceManager.isVertexStoresEnabled()))
            throw new IllegalArgumentException("Device lacks graphics-stage storage writes needed by " + name);
        layout = new Std140Layout(translated.uniforms());
        var usedUniforms=UniformUsage.names(compiled,layout);
        activeUniforms=layout.members().stream().filter(member->usedUniforms.contains(member.name())).toList();
        if (layout.size() > DeviceManager.deviceProperties.limits().maxUniformBufferRange())
            throw new IllegalArgumentException("Shader uniform block exceeds device limit: " + name);
        for (var sampler : translated.samplers()) {
            if (!active.contains(compiled.bindings().get(sampler.name()))) continue;
            if (customImages.names().contains(sampler.name()) || colourImages.containsKey(sampler.name())) continue;
            switch (sampler.type()) {
                case "sampler2D", "sampler2DShadow", "isampler2D", "usampler2D" -> {}
                // A volume the pack ships, kept three dimensional and bound as a real 3D image.
                case "sampler3D" -> {
                    if (dev.vitrail.pack.target.SamplerPlan.behind(sampler.name()).equals(sampler.name()))
                        throw new IllegalArgumentException("Unsupported native graphics sampler: " + sampler.declaration());
                }
                default -> throw new IllegalArgumentException("Unsupported native graphics sampler: " + sampler.declaration());
            }
            if (sampler.declaration().contains("["))
                throw new IllegalArgumentException("Native sampler arrays are not implemented: " + sampler.declaration());
            samplers.put(sampler.name(), sampler.type());
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
            if (translated.inputs() == VertexInputs.DISTANT) {
                var push = new net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder();
                push.addUniformInfo("vec3", dev.vitrail.glsl.DistantVertex.SECTION_OFFSET);
                builder.setPushConstants(push.buildPushConstant());
            }
            var ubos = new ArrayList<UBO>();
            Integer globals = compiled.bindings().get("OfGlobals");
            if (globals != null && active.contains(globals)) {
                var ubo = new ManualUBO(globals, VK_SHADER_STAGE_ALL_GRAPHICS, layout.size() / 4);
                ubo.setSrc(MemoryUtil.memAddress(uniforms), layout.size());
                ubos.add(ubo);
            }
            var descriptors = new ArrayList<ImageDescriptor>();
            for (var sampler : translated.samplers()) {
                if (!samplers.containsKey(sampler.name())) continue;
                var sampled = new ImageDescriptor(compiled.bindings().get(sampler.name()), sampler.type(), sampler.name(), 0) {
                    @Override public VulkanImage getImage() { return images.get(this.name); }
                    @Override public boolean usesVanillaTextureBinding() { return false; }
                    @Override public long getSampler(VulkanImage image) {
                        if (!qualifier.equals("sampler2DShadow")) return image.getSampler();
                        // GL_LINEAR, CLAMP_TO_EDGE, COMPARE_REF_TO_TEXTURE, LEQUAL; cache owned by renderer.
                        return SamplerManager.getTextureSampler(new SamplerManager.SamplerKey(9729, 9729,
                                33071, 33071, 33071, 0, 0, 0, 34894, 515));
                    }
                };
                sampledDescriptors.put(sampler.name(), sampled);
                descriptors.add(sampled);
            }
            for (var sampler : translated.samplers()) {
                if (!colourImages.containsKey(sampler.name())) continue;
                descriptors.add(new ImageDescriptor(compiled.bindings().get(sampler.name()), sampler.type(), sampler.name(), 0, true) {
                    @Override public VulkanImage getImage() { return images.get(this.name); }
                    @Override public boolean usesVanillaTextureBinding() { return false; }
                });
            }
            descriptors.addAll(customImages.descriptors());
            builder.setUniforms(ubos, descriptors);
            builder.setStorageBuffers(storageBuffers);
            SPIRV tessellationControl = null, tessellationEvaluation = null;
            try (var vertex = owned(compiled.stages().get(ProgramStage.VERTEX));
                 var fragment = owned(compiled.stages().get(ProgramStage.FRAGMENT));
                 var geometry = compiled.stages().containsKey(ProgramStage.GEOMETRY) ? owned(compiled.stages().get(ProgramStage.GEOMETRY)) : null) {
                builder.setSPIRVs(vertex, fragment);
                if (geometry != null) builder.setGeometrySPIRV(geometry);
                if (compiled.stages().containsKey(ProgramStage.TESSELLATION_CONTROL)) {
                    var vertices = java.util.regex.Pattern.compile("layout\\s*\\(\\s*vertices\\s*=\\s*(\\d+)\\s*\\)\\s*out")
                            .matcher(compiled.sources().get(ProgramStage.TESSELLATION_CONTROL));
                    if (!vertices.find()) throw new IllegalArgumentException("Tessellation control stage without layout(vertices) in " + name);
                    tessellationControl = owned(compiled.stages().get(ProgramStage.TESSELLATION_CONTROL));
                    tessellationEvaluation = owned(compiled.stages().get(ProgramStage.TESSELLATION_EVALUATION));
                    builder.setTessellationSPIRVs(tessellationControl, tessellationEvaluation, Integer.parseInt(vertices.group(1)));
                }
                pipeline = builder.createGraphicsPipeline();
            } finally {
                if (tessellationControl != null) tessellationControl.close();
                if (tessellationEvaluation != null) tessellationEvaluation.close();
            }
        } catch (Throwable failure) {
            MemoryUtil.memFree(uniforms);
            throw failure;
        }
    }

    /** Storage blocks the program really uses, bound to the current graph's bufferObject buffers. */
    static java.util.List<net.vulkanmod.vulkan.shader.descriptor.StorageBufferDescriptor> storageDescriptors(
            String name, VulkanProgramCompiler.Compiled compiled, java.util.Set<Integer> active, int stages) {
        var result = new ArrayList<net.vulkanmod.vulkan.shader.descriptor.StorageBufferDescriptor>();
        for (var entry : compiled.bindings().entrySet()) {
            int index = VulkanProgramCompiler.storageIndex(entry.getKey());
            if (index < 0 || !active.contains(entry.getValue())) continue;
            var buffers = NativeStorageBuffers.current();
            if (buffers == null || !buffers.has(index))
                throw new IllegalArgumentException("Pack declares no bufferObject." + index + " for " + name);
            result.add(new net.vulkanmod.vulkan.shader.descriptor.StorageBufferDescriptor(entry.getValue(), stages,
                    () -> buffers.buffer(index)));
        }
        return result;
    }

    @Override public String programName() { return pipeline.name; }
    public Std140Layout layout() { return layout; }
    public java.util.List<Std140Layout.Member> activeUniforms() { return activeUniforms; }
    /** Borrowed binding for frame-observer diagnostics; ownership remains with the producer. */
    public VulkanImage boundImage(String sampler) {
        requireOpen();var image=images.get(sampler);
        if(image==null)throw new IllegalArgumentException("No image bound for "+sampler);return image;
    }
    public ByteBuffer uniforms() { requireOpen(); return uniforms; }
    public java.util.Set<String> requiredSamplers() { return samplerNames; }
    /** Build a render-pass-compatible Vulkan pipeline before the pack's first visible frame. */
    protected void prepare(PipelineState state) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        pipeline.getHandle(state);
    }
    public java.util.Set<String> requiredColourImages() { return java.util.Set.copyOf(colourImages.keySet()); }

    /** A colour target stored into as colorimgN; Vulkan needs the view format to match the qualifier. */
    public void bindStorage(String name, VulkanImage image) {
        requireOpen();
        Integer format = colourImages.get(name);
        if (format == null) throw new IllegalArgumentException("Program does not store into " + name);
        if ((image.getUsage() & VK_IMAGE_USAGE_STORAGE_BIT) == 0)
            throw new IllegalArgumentException("Image was not created for storage: " + name);
        if (format != image.format)
            throw new IllegalArgumentException("Colour image " + name + " declares VkFormat " + format + " but the target is VkFormat " + image.format);
        images.put(name, image);
    }

    public void bindImage(String name, VulkanImage image) {
        requireOpen();
        String type = samplers.get(name);
        if (type == null) throw new IllegalArgumentException("Program does not sample " + name);
        if (image == null) throw new IllegalArgumentException("Null image " + name);
        boolean compatible = type.equals("sampler2DShadow") ? VulkanImage.isDepthFormat(image.format)
                : VulkanTargetFormat.acceptsSampler(image.format, type);
        if (!compatible)
            throw new IllegalArgumentException("Incompatible shader image " + name + ": " + type + " cannot sample VkFormat " + image.format);
        images.put(name, image);
    }

    /** Caller chooses ordering and owns target lifetime. This method rejects read/write feedback. */
    /**
     * Records the layout transitions of the bound sampled images now, outside any render pass, so a
     * GPU profile can time them apart from the draw (renderDraw then finds them in place).
     */
    public void transitionInputs() {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        Renderer.getInstance().endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var command = Renderer.getCommandBuffer();
            for (var image : images.values())
                image.transitionImageLayout(stack, command, storedImage(image) ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    /**
     * Whether {@code image} is bound as a storage (colorimg) image and so stays in GENERAL layout.
     * A scan of the few colour images, not a new identity set per draw: that set was the render
     * thread's largest allocation (flight bench, Complementary) and fed G1 pauses.
     */
    private boolean storedImage(VulkanImage image) {
        if (image == null || colourImages.isEmpty()) return false;
        for (String name : colourImages.keySet()) if (images.get(name) == image) return true;
        return false;
    }

    /** The layout a bound image is sampled in: the feedback-loop layout while it is an attachment of the pass. */
    private int sampledLayout(VulkanImage image, Framebuffer target, boolean loops) {
        if (loops) {
            if (image == target.getDepthAttachment()) return VulkanImage.FEEDBACK_LOOP_LAYOUT;
            for (int i = 0; i < target.getColorAttachmentCount(); i++)
                if (image == target.getColorAttachment(i)) return VulkanImage.FEEDBACK_LOOP_LAYOUT;
        }
        return storedImage(image) ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    protected void renderDraw(Framebuffer target, RenderPass pass, PipelineState state, java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> draw) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (!Renderer.isRecording()) throw new IllegalStateException("No active renderer frame");
        if (pass.getFramebuffer() != target) throw new IllegalArgumentException("Render pass target mismatch");
        for (String name : colourImages.keySet()) {
            VulkanImage image = images.get(name);
            if (image == null) throw new IllegalStateException("Missing shader colour image " + name);
            for (int i = 0; i < target.getColorAttachmentCount(); i++)
                if (image == target.getColorAttachment(i)) throw new IllegalArgumentException("Colour image is also an attachment: " + name);
        }
        // An attachment of this very pass may be sampled only where the pass was built as a feedback loop
        // (NativeShaderChain decides that from the plan); anything else is a wiring error.
        boolean loops = pass.feedbackLoopPipelineFlags() != 0;
        for (String name : samplers.keySet()) {
            VulkanImage image = images.get(name);
            if (image == null) throw new IllegalStateException("Missing shader image " + name);
            for (int i = 0; i < target.getColorAttachmentCount(); i++)
                if (image == target.getColorAttachment(i) && !pass.colorFeedbackLoop(i)) throw new IllegalArgumentException("Shader image feedback: " + name);
            if (image == target.getDepthAttachment() && !pass.depthFeedbackLoop()) throw new IllegalArgumentException("Shader depth feedback: " + name);
        }
        for (var entry : sampledDescriptors.entrySet()) {
            int wanted = sampledLayout(images.get(entry.getKey()), target, loops);
            if (entry.getValue().getLayout() != wanted) entry.getValue().setLayout(wanted);
        }
        Renderer renderer = Renderer.getInstance();
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var command = Renderer.getCommandBuffer();
            for (var image : images.values())
                image.transitionImageLayout(stack, command, sampledLayout(image, target, loops));
            target.beginRenderPass(command, pass, stack);
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getHandle(state));
            // Registered so its descriptor ring is reset every frame; without it the ring grew until the
            // set allocation ran out of stack space after a few thousand frames at uncapped FPS.
            renderer.addUsedPipeline(pipeline);
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
            // GL orientation: rows bottom-up (WorldMatrices).
            for (int i = 0; i < target.getColorAttachmentCount(); i++) target.getColorAttachment(i).recordRasterOrigin(true);
            if (target.getDepthAttachment() != null) target.getDepthAttachment().recordRasterOrigin(true);
        } finally {
            renderer.endRenderPass();
            renderer.restoreGraphicsPipeline(null);
        }
        if (storage) NativeStorageBuffers.shaderWriteBarrier();
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
