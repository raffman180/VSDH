package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrail.pack.model.ProgramStage;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.ComputePipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.*;

/**
 * One native pack compute program. Samplers are combined image samplers, {@code colorimgN} are
 * storage images in GENERAL layout. A target sampled and stored by the same dispatch is sampled in
 * GENERAL too, which is the only layout both descriptors accept. Every dispatch is followed by a
 * global shader-write barrier, the Vulkan form of Iris's glMemoryBarrier after a compute.
 */
public final class NativeComputePass implements NativeUniformProgram, AutoCloseable {
    private static final Pattern FORMAT = Pattern.compile("layout\\(\\s*(\\w+)\\s*,[^)]*binding\\s*=\\s*(\\d+)\\s*\\)");

    private final String name;
    private final ComputePipeline pipeline;
    private final Std140Layout layout;
    private final List<Std140Layout.Member> activeUniforms;
    private final ByteBuffer uniforms;
    private final Map<String, String> samplers = new HashMap<>();
    private final Map<String, String> storage = new HashMap<>();
    private final Map<String, Integer> storageFormats = new HashMap<>();
    private final Map<String, VulkanImage> images = new HashMap<>();
    private final Map<String, ImageDescriptor> sampledDescriptors = new HashMap<>();
    private final Map<String, ImageDescriptor> storageDescriptors = new HashMap<>();
    private long dispatches;
    private boolean closed;

    NativeComputePass(PackProgramSource.PreparedCompute source) {
        RenderSystem.assertOnRenderThread();
        this.name = source.path();
        var translated = source.translated();
        var compiled = source.compiled();
        byte[] spirv = compiled.stages().get(ProgramStage.COMPUTE);
        if (compiled.stages().size() != 1 || spirv == null)
            throw new IllegalArgumentException("Native compute program requires exactly one compute stage: " + name);
        var active = new HashSet<>(SpirvInterface.bindings(spirv));
        var storageBuffers = NativeGraphicsProgram.storageDescriptors(name, compiled, active, VK_SHADER_STAGE_COMPUTE_BIT);
        var customImages = CustomImageBindings.of(name, translated, compiled, active);
        layout = new Std140Layout(translated.uniforms());
        var used = UniformUsage.names(compiled, layout);
        activeUniforms = layout.members().stream().filter(member -> used.contains(member.name())).toList();
        if (layout.size() > DeviceManager.deviceProperties.limits().maxUniformBufferRange())
            throw new IllegalArgumentException("Shader uniform block exceeds device limit: " + name);
        String text = compiled.sources().get(ProgramStage.COMPUTE);
        var declaredFormats = new HashMap<Integer, String>();
        var matcher = FORMAT.matcher(text);
        while (matcher.find()) declaredFormats.put(Integer.parseInt(matcher.group(2)), matcher.group(1));
        for (var sampler : translated.samplers()) {
            Integer binding = compiled.bindings().get(sampler.name());
            if (binding == null || !active.contains(binding)) continue;
            if (customImages.names().contains(sampler.name())) continue;
            if (sampler.declaration().contains("["))
                throw new IllegalArgumentException("Native sampler/image arrays are not implemented: " + sampler.declaration());
            if (sampler.type().matches("[iu]?image2D")) {
                String format = declaredFormats.get(binding);
                Integer vk = format == null ? null : vulkanFormat(format);
                if (vk == null)
                    throw new IllegalArgumentException("Compute image without a supported format qualifier: " + name + "/" + sampler.name() + " (" + format + ")");
                storage.put(sampler.name(), sampler.type());
                storageFormats.put(sampler.name(), vk);
                continue;
            }
            switch (sampler.type()) {
                case "sampler2D", "sampler2DShadow", "isampler2D", "usampler2D" -> {}
                case "sampler3D" -> {
                    if (dev.vitrail.pack.target.SamplerPlan.behind(sampler.name()).equals(sampler.name()))
                        throw new IllegalArgumentException("Unsupported native compute sampler: " + sampler.declaration());
                }
                default -> throw new IllegalArgumentException("Unsupported native compute sampler: " + sampler.declaration());
            }
            samplers.put(sampler.name(), sampler.type());
        }
        uniforms = MemoryUtil.memCalloc(Math.max(16, layout.size()));
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
        try {
            code.put(spirv).flip();
            var ubos = new ArrayList<UBO>();
            Integer globals = compiled.bindings().get("OfGlobals");
            if (globals != null && active.contains(globals)) {
                var ubo = new ManualUBO(globals, VK_SHADER_STAGE_COMPUTE_BIT, layout.size() / 4);
                ubo.setSrc(MemoryUtil.memAddress(uniforms), layout.size());
                ubos.add(ubo);
            }
            var descriptors = new ArrayList<ImageDescriptor>();
            for (var sampler : translated.samplers()) {
                Integer binding = compiled.bindings().get(sampler.name());
                if (samplers.containsKey(sampler.name())) {
                    var descriptor = new ImageDescriptor(binding, sampler.type(), sampler.name(), 0) {
                        @Override public VulkanImage getImage() { return images.get(this.name); }
                        @Override public boolean usesVanillaTextureBinding() { return false; }
                        @Override public long getSampler(VulkanImage image) {
                            if (!qualifier.equals("sampler2DShadow")) return image.getSampler();
                            return SamplerManager.getTextureSampler(new SamplerManager.SamplerKey(9729, 9729,
                                    33071, 33071, 33071, 0, 0, 0, 34894, 515));
                        }
                    };
                    sampledDescriptors.put(sampler.name(), descriptor);
                    descriptors.add(descriptor);
                } else if (storage.containsKey(sampler.name())) {
                    var descriptor = new ImageDescriptor(binding, sampler.type(), sampler.name(), 0, true) {
                        @Override public VulkanImage getImage() { return images.get(this.name); }
                        @Override public boolean usesVanillaTextureBinding() { return false; }
                    };
                    storageDescriptors.put(sampler.name(), descriptor);
                    descriptors.add(descriptor);
                }
            }
            descriptors.addAll(customImages.descriptors());
            pipeline = new ComputePipeline(name, ubos, descriptors, storageBuffers, code);
        } catch (Throwable failure) {
            MemoryUtil.memFree(uniforms);
            throw failure;
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    @Override public Std140Layout layout() { return layout; }
    @Override public List<Std140Layout.Member> activeUniforms() { return activeUniforms; }
    @Override public ByteBuffer uniforms() { requireOpen(); return uniforms; }
    public String name() { return name; }
    @Override public String programName() { return name; }
    public long dispatches() { return dispatches; }
    public Set<String> requiredSamplers() { return Set.copyOf(samplers.keySet()); }
    public Set<String> requiredImages() { return Set.copyOf(storage.keySet()); }

    public void bindImage(String sampler, VulkanImage image) {
        requireOpen();
        String type = samplers.get(sampler);
        if (type == null) throw new IllegalArgumentException("Compute does not sample " + sampler);
        if (image == null) throw new IllegalArgumentException("Null image " + sampler);
        boolean compatible = type.equals("sampler2DShadow") ? VulkanImage.isDepthFormat(image.format)
                : VulkanTargetFormat.acceptsSampler(image.format, type);
        if (!compatible)
            throw new IllegalArgumentException("Incompatible compute image " + sampler + ": " + type + " cannot sample VkFormat " + image.format);
        images.put(sampler, image);
    }

    public void bindStorage(String imageName, VulkanImage image) {
        requireOpen();
        Integer format = storageFormats.get(imageName);
        if (format == null) throw new IllegalArgumentException("Compute does not store into " + imageName);
        if (image == null) throw new IllegalArgumentException("Null image " + imageName);
        if ((image.getUsage() & VK_IMAGE_USAGE_STORAGE_BIT) == 0)
            throw new IllegalArgumentException("Image was not created for storage: " + imageName);
        // Vulkan requires the view format to match the shader's format qualifier; GL tolerates a
        // same-size reinterpretation but that is a separate typed view this host does not create.
        if (format != image.format)
            throw new IllegalArgumentException("Compute image " + imageName + " declares VkFormat " + format
                    + " but the target is VkFormat " + image.format);
        images.put(imageName, image);
        var descriptor = storageDescriptors.get(imageName);
        descriptor.setMipLevel(image.mipLevels > 1 ? 0 : -1);
    }

    /** Recorded on the frame command buffer outside any render pass; zero groups dispatch nothing, as in Iris. */
    public void dispatch(int x, int y, int z) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (!Renderer.isRecording()) throw new IllegalStateException("No active renderer frame");
        if (x <= 0 || y <= 0 || z <= 0) return;
        var limits = DeviceManager.deviceProperties.limits();
        if (x > limits.maxComputeWorkGroupCount(0) || y > limits.maxComputeWorkGroupCount(1) || z > limits.maxComputeWorkGroupCount(2))
            throw new IllegalStateException("Compute dispatch exceeds device limits: " + name + " " + x + "x" + y + "x" + z);
        record(command -> vkCmdDispatch(command, x, y, z));
    }

    /** Iris's indirect.<pass>: the three work group counts are read on the GPU from {@code buffer} at {@code offset}. */
    public void dispatchIndirect(long buffer, long offset) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (!Renderer.isRecording()) throw new IllegalStateException("No active renderer frame");
        if (buffer == VK_NULL_HANDLE || offset < 0 || (offset & 3) != 0)
            throw new IllegalArgumentException("Invalid indirect dispatch pointer: " + name + " offset " + offset);
        record(command -> vkCmdDispatchIndirect(command, buffer, offset));
    }

    private void record(java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> dispatchCommand) {
        for (String required : samplers.keySet())
            if (images.get(required) == null) throw new IllegalStateException("Missing compute image " + name + "/" + required);
        for (String required : storage.keySet())
            if (images.get(required) == null) throw new IllegalStateException("Missing compute storage " + name + "/" + required);
        Set<VulkanImage> stored = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (String imageName : storage.keySet()) stored.add(images.get(imageName));
        for (var entry : sampledDescriptors.entrySet()) {
            int wanted = stored.contains(images.get(entry.getKey())) ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            if (entry.getValue().getLayout() != wanted) entry.getValue().setLayout(wanted);
        }
        Renderer renderer = Renderer.getInstance();
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var command = Renderer.getCommandBuffer();
            for (VulkanImage image : stored) image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_GENERAL);
            for (String sampler : samplers.keySet()) {
                VulkanImage image = images.get(sampler);
                if (!stored.contains(image)) image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            pipeline.bind(command);
            dispatchCommand.accept(command);
            var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT
                            | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                            | VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                            | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    0, barrier, null, null);
            for (VulkanImage image : stored) image.recordRasterOrigin(true); // GL orientation (WorldMatrices)
        }
        dispatches++;
    }

    /** GL image format qualifiers this host can bind, as their exact Vulkan view formats. */
    static Integer vulkanFormat(String qualifier) {
        return switch (qualifier) {
            case "rgba32f" -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case "rgba16f" -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case "rg32f" -> VK_FORMAT_R32G32_SFLOAT;
            case "rg16f" -> VK_FORMAT_R16G16_SFLOAT;
            case "r11f_g11f_b10f" -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case "r32f" -> VK_FORMAT_R32_SFLOAT;
            case "r16f" -> VK_FORMAT_R16_SFLOAT;
            case "rgba16" -> VK_FORMAT_R16G16B16A16_UNORM;
            case "rgb10_a2" -> VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case "rgba8" -> VK_FORMAT_R8G8B8A8_UNORM;
            case "rg16" -> VK_FORMAT_R16G16_UNORM;
            case "rg8" -> VK_FORMAT_R8G8_UNORM;
            case "r16" -> VK_FORMAT_R16_UNORM;
            case "r8" -> VK_FORMAT_R8_UNORM;
            case "rgba16_snorm" -> VK_FORMAT_R16G16B16A16_SNORM;
            case "rgba8_snorm" -> VK_FORMAT_R8G8B8A8_SNORM;
            case "rg16_snorm" -> VK_FORMAT_R16G16_SNORM;
            case "rg8_snorm" -> VK_FORMAT_R8G8_SNORM;
            case "r16_snorm" -> VK_FORMAT_R16_SNORM;
            case "r8_snorm" -> VK_FORMAT_R8_SNORM;
            case "rgba32i" -> VK_FORMAT_R32G32B32A32_SINT;
            case "rgba16i" -> VK_FORMAT_R16G16B16A16_SINT;
            case "rgba8i" -> VK_FORMAT_R8G8B8A8_SINT;
            case "rg32i" -> VK_FORMAT_R32G32_SINT;
            case "rg16i" -> VK_FORMAT_R16G16_SINT;
            case "rg8i" -> VK_FORMAT_R8G8_SINT;
            case "r32i" -> VK_FORMAT_R32_SINT;
            case "r16i" -> VK_FORMAT_R16_SINT;
            case "r8i" -> VK_FORMAT_R8_SINT;
            case "rgba32ui" -> VK_FORMAT_R32G32B32A32_UINT;
            case "rgba16ui" -> VK_FORMAT_R16G16B16A16_UINT;
            case "rgb10_a2ui" -> VK_FORMAT_A2B10G10R10_UINT_PACK32;
            case "rgba8ui" -> VK_FORMAT_R8G8B8A8_UINT;
            case "rg32ui" -> VK_FORMAT_R32G32_UINT;
            case "rg16ui" -> VK_FORMAT_R16G16_UINT;
            case "rg8ui" -> VK_FORMAT_R8G8_UINT;
            case "r32ui" -> VK_FORMAT_R32_UINT;
            case "r16ui" -> VK_FORMAT_R16_UINT;
            case "r8ui" -> VK_FORMAT_R8_UINT;
            default -> null;
        };
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Compute pass is closed: " + name); }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        pipeline.scheduleCleanUp();
        images.clear();
        MemoryUtil.memFree(uniforms);
    }
}
