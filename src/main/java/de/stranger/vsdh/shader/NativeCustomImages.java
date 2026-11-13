package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrail.pack.model.ImageInformation;
import dev.vitrail.pack.model.PackTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The pack's {@code image.<name>} directives for one shader graph: 1D, 2D (fixed or screen
 * relative) and 3D images usable as storage images under their own name and as sampled images
 * under the directive's sampler name. They stay in GENERAL layout for their whole life, the one
 * layout both kinds of descriptor accept, so no pass transitions them. Like Iris they start
 * cleared, and the directives that ask for it are cleared again at the start of every frame.
 * Render thread only.
 */
public final class NativeCustomImages implements AutoCloseable {
    private static NativeCustomImages current;

    private static final class Image {
        final ImageInformation info;
        final long handle, memory, view;
        final VulkanImage wrapper;
        final boolean integer;
        Image(ImageInformation info, long handle, long memory, long view, VulkanImage wrapper, boolean integer) {
            this.info = info; this.handle = handle; this.memory = memory; this.view = view; this.wrapper = wrapper; this.integer = integer;
        }
    }

    private final Map<String, Image> byName = new HashMap<>();
    private final List<Image> images = new ArrayList<>();
    private boolean initialized, closed;

    public NativeCustomImages(ImageInformation.Reading reading, int width, int height) {
        RenderSystem.assertOnRenderThread();
        try {
            for (var info : reading.images()) {
                var image = create(info, width, height);
                images.add(image);
                byName.put(info.name(), image);
                info.sampler().filter(sampler -> !sampler.equals("none")).ifPresent(sampler -> byName.putIfAbsent(sampler, image));
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    static void install(NativeCustomImages value) { RenderSystem.assertOnRenderThread(); current = value; }
    static NativeCustomImages current() { return current; }

    /** Whether {@code name} is a custom image's own name or its sampler name. */
    public boolean has(String name) { return byName.containsKey(name); }
    /** Whether {@code name} is the storage name (the directive's own) rather than its sampler. */
    public boolean storage(String name) { var image = byName.get(name); return image != null && image.info.name().equals(name); }
    public VulkanImage image(String name) {
        var image = closed ? null : byName.get(name);
        if (image == null) throw new IllegalStateException("No custom image " + name);
        return image.wrapper;
    }
    public int format(String name) { return image(name).format; }

    private static Image create(ImageInformation info, int screenWidth, int screenHeight) {
        int format = VulkanTargetFormat.of(info.internalFormat().used());
        boolean integer = info.internalFormat().used().integer();
        int type, viewType, w, h, d;
        if (info.relative()) {
            type = VK_IMAGE_TYPE_2D; viewType = VK_IMAGE_VIEW_TYPE_2D;
            w = Math.max(1, (int) (screenWidth * info.relativeWidth()));
            h = Math.max(1, (int) (screenHeight * info.relativeHeight()));
            d = 1;
        } else {
            switch (info.shape()) {
                case TEXTURE_1D -> { type = VK_IMAGE_TYPE_1D; viewType = VK_IMAGE_VIEW_TYPE_1D; }
                case TEXTURE_2D, TEXTURE_RECTANGLE -> { type = VK_IMAGE_TYPE_2D; viewType = VK_IMAGE_VIEW_TYPE_2D; }
                case TEXTURE_3D -> { type = VK_IMAGE_TYPE_3D; viewType = VK_IMAGE_VIEW_TYPE_3D; }
                default -> throw new IllegalArgumentException("Unsupported custom image shape " + info.describe());
            }
            w = Math.max(1, info.width());
            h = info.shape() == PackTexture.Shape.TEXTURE_1D ? 1 : Math.max(1, info.height());
            d = info.shape() == PackTexture.Shape.TEXTURE_3D ? Math.max(1, info.depth()) : 1;
        }
        var limits = DeviceManager.deviceProperties.limits();
        int max = type == VK_IMAGE_TYPE_3D ? limits.maxImageDimension3D() : type == VK_IMAGE_TYPE_1D ? limits.maxImageDimension1D() : limits.maxImageDimension2D();
        if (w > max || h > max || d > max) throw new IllegalArgumentException("Custom image exceeds device limits: " + info.describe());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, props);
            int required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if ((props.optimalTilingFeatures() & required) != required)
                throw new IllegalArgumentException("Device cannot store into custom image format " + info.describe());
            boolean linear = !integer && (props.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
            var create = VkImageCreateInfo.calloc(stack).sType$Default().imageType(type).format(format)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(w, h, d);
            var allocation = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_GPU_ONLY)
                    .requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            var pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            int result = vmaCreateImage(Vulkan.getAllocator(), create, allocation, pImage, pMemory, null);
            if (result != VK_SUCCESS) throw new IllegalStateException("Could not allocate custom image " + info.describe() + ": " + result);
            long handle = pImage.get(0), memory = pMemory.get(0);
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(handle).viewType(viewType).format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            var pView = stack.mallocLong(1);
            result = vkCreateImageView(DeviceManager.vkDevice, viewInfo, null, pView);
            if (result != VK_SUCCESS) {
                vmaDestroyImage(Vulkan.getAllocator(), handle, memory);
                throw new IllegalStateException("Could not create custom image view " + info.describe() + ": " + result);
            }
            var wrapper = new VulkanImage(handle, format, 1, w, h, texelBytes(format), create.usage(), pView.get(0));
            int filter = linear ? 9729 : 9728; // GL_LINEAR / GL_NEAREST
            wrapper.updateTextureSampler(new SamplerManager.SamplerKey(filter, filter, 33071, 33071, 33071, 0, 0, 0, 0, 515));
            return new Image(info, handle, memory, pView.get(0), wrapper, integer);
        }
    }

    /** Bytes per texel, used only for staging alignment by the wrapper; custom images are never uploaded. */
    private static int texelBytes(int format) {
        return switch (format) {
            case VK_FORMAT_R8_UNORM, VK_FORMAT_R8_SNORM, VK_FORMAT_R8_UINT, VK_FORMAT_R8_SINT -> 1;
            case VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8_UINT, VK_FORMAT_R16_UNORM, VK_FORMAT_R16_UINT, VK_FORMAT_R16_SINT,
                 VK_FORMAT_R16_SFLOAT -> 2;
            case VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_UNORM, VK_FORMAT_R16G16B16A16_UINT,
                 VK_FORMAT_R16G16B16A16_SINT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32_SINT -> 8;
            case VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R32G32B32A32_SINT -> 16;
            default -> 4;
        };
    }

    /** First frame: every image to GENERAL and zero. Later frames: the clear=true ones again. */
    void beginFrame() {
        RenderSystem.assertOnRenderThread();
        if (closed || images.isEmpty()) return;
        boolean any = !initialized || images.stream().anyMatch(image -> image.info.clear());
        if (!any) return;
        Renderer.getInstance().endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var command = Renderer.getCommandBuffer();
            var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            var zero = VkClearColorValue.calloc(stack);
            for (var image : images) {
                if (initialized && !image.info.clear()) continue;
                if (!initialized) {
                    VulkanImage.transitionLayout(stack, command, image.wrapper, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                } else {
                    // Previous frame's shader access, then the clear.
                    VulkanImage.transitionLayout(stack, command, image.wrapper, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                            VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                }
                vkCmdClearColorImage(command, image.handle, VK_IMAGE_LAYOUT_GENERAL, zero, range);
                VulkanImage.transitionLayout(stack, command, image.wrapper, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            }
        }
        initialized = true;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        if (current == this) current = null;
        for (var image : images) {
            image.wrapper.free();
            MemoryManager.getInstance().addFrameOp(() -> {
                vkDestroyImageView(DeviceManager.vkDevice, image.view, null);
                vmaDestroyImage(Vulkan.getAllocator(), image.handle, image.memory);
            });
        }
        images.clear();
        byName.clear();
    }
}
