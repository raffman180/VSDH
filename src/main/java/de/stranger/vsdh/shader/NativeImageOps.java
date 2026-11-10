package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.target.TargetDirectives.Colour;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;

/** Records transfers in the active frame, without separate submissions, fences or CPU readback. */
public final class NativeImageOps {
    private NativeImageOps() {}
    private static final int READ_STAGES = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    public static void clear(VulkanImage image, TargetFormat format, Colour colour) {
        if (image.aspect != VK_IMAGE_ASPECT_COLOR_BIT || image.format != VulkanTargetFormat.of(format)
                || (image.getUsage() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
            throw new IllegalArgumentException("Color clear format/usage mismatch");
        var command = commandOutsidePass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            var value = VkClearColorValue.calloc(stack);
            for (int i = 0; i < 4; i++) {
                float channel = switch (i) { case 0 -> colour.r(); case 1 -> colour.g(); case 2 -> colour.b(); default -> colour.a(); };
                if (!format.integer()) value.float32(i, channel);
                else if (format.name().endsWith("_SINT")) value.int32(i, (int) channel);
                else value.uint32(i, (int) channel);
            }
            var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(image.mipLevels).baseArrayLayer(0).layerCount(1);
            vkCmdClearColorImage(command, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, range);
            image.recordRasterOrigin(false);
        }
    }

    /** Exact copy of the final persistent ALT image back to MAIN, matching Iris 1.21.1. */
    public static void copyBase(VulkanImage source, VulkanImage destination) {
        copy(source, destination, 1);
    }

    static void copyColourMipChain(VulkanImage source, VulkanImage destination) {
        if (source.aspect != VK_IMAGE_ASPECT_COLOR_BIT || source.mipLevels != destination.mipLevels)
            throw new IllegalArgumentException("Color mip copy requires matching color images");
        copy(source, destination, source.mipLevels);
        destination.recordGeneratedMipmaps();
    }

    private static void copy(VulkanImage source, VulkanImage destination, int levels) {
        if (source == destination) throw new IllegalArgumentException("Cannot copy an image onto itself");
        if (source.width != destination.width || source.height != destination.height || source.format != destination.format
                || source.aspect != destination.aspect)
            throw new IllegalArgumentException("Exact image copy requires equal dimensions, formats and aspects");
        if ((source.getUsage() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0 || (destination.getUsage() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
            throw new IllegalArgumentException("Image copy requires transfer source/destination usage");
        var command = commandOutsidePass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            source.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            destination.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            boolean combined = source.aspect == (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT);
            var copy = VkImageCopy.calloc(combined ? 2 : levels, stack);
            for (int i = 0; i < copy.capacity(); i++) {
                int aspect = combined ? (i == 0 ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_STENCIL_BIT) : source.aspect;
                int level = combined ? 0 : i;
                copy.get(i).srcSubresource().aspectMask(aspect).mipLevel(level).baseArrayLayer(0).layerCount(1);
                copy.get(i).dstSubresource().aspectMask(aspect).mipLevel(level).baseArrayLayer(0).layerCount(1);
                copy.get(i).extent().set(Math.max(1, source.width >> level), Math.max(1, source.height >> level), 1);
            }
            vkCmdCopyImage(command, source.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    destination.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
            destination.recordRasterOrigin(source.isRowsBottomUp());
        }
    }

    public static void clearDepth(VulkanImage image, float depth, int stencil) {
        if (!VulkanImage.isDepthFormat(image.format) || !Float.isFinite(depth) || depth < 0 || depth > 1
                || (image.getUsage() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
            throw new IllegalArgumentException("Invalid depth clear");
        var command = commandOutsidePass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            var value = VkClearDepthStencilValue.calloc(stack).depth(depth).stencil(stencil);
            var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(image.aspect)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdClearDepthStencilImage(command, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, range);
            image.recordRasterOrigin(false);
        }
    }

    public static void mipmaps(VulkanImage image, boolean linear) {
        if (image.mipLevels == 1) return;
        if (image.aspect != VK_IMAGE_ASPECT_COLOR_BIT
                || (image.getUsage() & (VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT))
                != (VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT))
            throw new IllegalArgumentException("Mipmap image format/usage mismatch");
        var command = commandOutsidePass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            for (int level = 1; level < image.mipLevels; level++) {
                barrier(stack, command, image, level - 1, 1,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                var blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level - 1).baseArrayLayer(0).layerCount(1);
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0).layerCount(1);
                blit.srcOffsets(0).set(0, 0, 0);
                blit.srcOffsets(1).set(Math.max(1, image.width >> (level - 1)), Math.max(1, image.height >> (level - 1)), 1);
                blit.dstOffsets(0).set(0, 0, 0);
                blit.dstOffsets(1).set(Math.max(1, image.width >> level), Math.max(1, image.height >> level), 1);
                vkCmdBlitImage(command, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            }
            barrier(stack, command, image, 0, image.mipLevels - 1,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, READ_STAGES);
            barrier(stack, command, image, image.mipLevels - 1, 1,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, READ_STAGES);
            image.setCurrentLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            image.recordGeneratedMipmaps();
        }
    }

    /** Conversion/scaling fallback for a pack with no final; callers preflight blit support. */
    public static void blitColour(VulkanImage source, VulkanImage destination) {
        if (source == destination) throw new IllegalArgumentException("Image feedback in presentation");
        if (source.aspect != VK_IMAGE_ASPECT_COLOR_BIT || destination.aspect != VK_IMAGE_ASPECT_COLOR_BIT
                || VulkanTargetFormat.isInteger(source.format) || VulkanTargetFormat.isInteger(destination.format)
                || (source.getUsage() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0
                || (destination.getUsage() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
            throw new IllegalArgumentException("Color presentation requires noninteger transferable color images");
        var command = commandOutsidePass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            source.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            destination.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            var blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.srcOffsets(1).set(source.width, source.height, 1);
            blit.dstOffsets(1).set(destination.width, destination.height, 1);
            vkCmdBlitImage(command, source.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    destination.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);
            destination.recordRasterOrigin(source.isRowsBottomUp());
        }
    }

    private static void barrier(MemoryStack stack, VkCommandBuffer command, VulkanImage image, int first, int count,
                                int oldLayout, int newLayout, int sourceAccess, int destinationAccess, int destinationStage) {
        var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .oldLayout(oldLayout).newLayout(newLayout).srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.getId());
        barrier.subresourceRange().aspectMask(image.aspect).baseMipLevel(first).levelCount(count).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, destinationStage, 0, null, null, barrier);
    }

    private static VkCommandBuffer commandOutsidePass() {
        RenderSystem.assertOnRenderThread();
        if (!Renderer.isRecording()) throw new IllegalStateException("Image operation requires an active frame");
        Renderer.getInstance().endRenderPass();
        return Renderer.getCommandBuffer();
    }
}
