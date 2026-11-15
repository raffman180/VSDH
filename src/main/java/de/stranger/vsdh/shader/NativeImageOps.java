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
        copy(source, destination, 1, false);
    }

    /**
     * Base-level copy of the depth aspect only. For snapshots that are only ever sampled through a
     * depth view: the stencil plane of a combined format is then never read, and copying it is the
     * expensive part of the transfer on common drivers.
     */
    public static void copyDepthOnly(VulkanImage source, VulkanImage destination) {
        if ((source.aspect & VK_IMAGE_ASPECT_DEPTH_BIT) == 0)
            throw new IllegalArgumentException("Depth-only copy requires a depth image");
        copy(source, destination, 1, true);
    }

    static void copyColourMipChain(VulkanImage source, VulkanImage destination) {
        if (source.aspect != VK_IMAGE_ASPECT_COLOR_BIT || source.mipLevels != destination.mipLevels)
            throw new IllegalArgumentException("Color mip copy requires matching color images");
        copy(source, destination, source.mipLevels, false);
        destination.recordGeneratedMipmaps();
    }

    private static void copy(VulkanImage source, VulkanImage destination, int levels, boolean depthOnly) {
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
            if (depthOnly && levels != 1) throw new IllegalArgumentException("Depth-only copy covers the base level");
            var copy = VkImageCopy.calloc(combined && !depthOnly ? 2 : levels, stack);
            for (int i = 0; i < copy.capacity(); i++) {
                int aspect = depthOnly ? VK_IMAGE_ASPECT_DEPTH_BIT
                        : combined ? (i == 0 ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_STENCIL_BIT) : source.aspect;
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
        mipmaps(java.util.List.of(image), new boolean[] {linear});
    }

    /**
     * Mip chains of several images at once, level by level: one pipeline barrier per level covers
     * every image (a pack's pass may request several, Photon deferred4 two). The blits per image
     * are the same as one image at a time, so the levels hold the same values.
     */
    public static void mipmaps(java.util.List<VulkanImage> all, boolean[] linear) {
        var images = new java.util.ArrayList<VulkanImage>(all.size());
        var filters = new java.util.ArrayList<Integer>(all.size());
        int maxLevels = 1;
        for (int i = 0; i < all.size(); i++) {
            VulkanImage image = all.get(i);
            if (image.mipLevels == 1) continue;
            if (image.aspect != VK_IMAGE_ASPECT_COLOR_BIT
                    || (image.getUsage() & (VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT))
                    != (VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT))
                throw new IllegalArgumentException("Mipmap image format/usage mismatch");
            if (images.contains(image)) continue;
            images.add(image);
            filters.add(linear[i] ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            maxLevels = Math.max(maxLevels, image.mipLevels);
        }
        if (images.isEmpty()) return;
        var command = commandOutsidePass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (VulkanImage image : images) image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            var blit = VkImageBlit.calloc(1, stack);
            for (int level = 1; level < maxLevels; level++) {
                int count = 0;
                for (VulkanImage image : images) if (level < image.mipLevels) count++;
                var barriers = VkImageMemoryBarrier.calloc(count, stack);
                int b = 0;
                for (VulkanImage image : images) {
                    if (level >= image.mipLevels) continue;
                    imageBarrier(barriers.get(b++), image, level - 1, 1,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
                }
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barriers);
                for (int i = 0; i < images.size(); i++) {
                    VulkanImage image = images.get(i);
                    if (level >= image.mipLevels) continue;
                    blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level - 1).baseArrayLayer(0).layerCount(1);
                    blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0).layerCount(1);
                    blit.srcOffsets(0).set(0, 0, 0);
                    blit.srcOffsets(1).set(Math.max(1, image.width >> (level - 1)), Math.max(1, image.height >> (level - 1)), 1);
                    blit.dstOffsets(0).set(0, 0, 0);
                    blit.dstOffsets(1).set(Math.max(1, image.width >> level), Math.max(1, image.height >> level), 1);
                    vkCmdBlitImage(command, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, filters.get(i));
                }
            }
            var finals = VkImageMemoryBarrier.calloc(images.size() * 2, stack);
            int b = 0;
            for (VulkanImage image : images) {
                imageBarrier(finals.get(b++), image, 0, image.mipLevels - 1,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
                imageBarrier(finals.get(b++), image, image.mipLevels - 1, 1,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, READ_STAGES, 0, null, null, finals);
            for (VulkanImage image : images) {
                image.setCurrentLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                image.recordGeneratedMipmaps();
            }
        }
    }

    private static void imageBarrier(VkImageMemoryBarrier barrier, VulkanImage image, int first, int count,
                                     int oldLayout, int newLayout, int sourceAccess, int destinationAccess) {
        barrier.sType$Default().oldLayout(oldLayout).newLayout(newLayout).srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.getId());
        barrier.subresourceRange().aspectMask(image.aspect).baseMipLevel(first).levelCount(count).baseArrayLayer(0).layerCount(1);
    }

    /**
     * Conversion/scaling fallback for a pack with no final; callers preflight blit support.
     * {@code flipRows}: the GL-oriented (bottom-up) pack picture into the top-down main target.
     */
    public static void blitColour(VulkanImage source, VulkanImage destination, boolean flipRows) {
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
            if (flipRows) {
                blit.dstOffsets(0).set(0, destination.height, 0);
                blit.dstOffsets(1).set(destination.width, 0, 1);
            } else blit.dstOffsets(1).set(destination.width, destination.height, 1);
            vkCmdBlitImage(command, source.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    destination.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);
            destination.recordRasterOrigin(source.isRowsBottomUp() != flipRows);
        }
    }

    private static VkCommandBuffer commandOutsidePass() {
        RenderSystem.assertOnRenderThread();
        if (!Renderer.isRecording()) throw new IllegalStateException("Image operation requires an active frame");
        Renderer.getInstance().endRenderPass();
        return Renderer.getCommandBuffer();
    }
}
