package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.vulkan.texture.VulkanImage;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Borrows the world's depth image; owns only the two optional, exact-format snapshots. Packs read
 * them through depth views only, so the stencil plane of a combined format is not copied.
 */
public final class NativeWorldDepth implements AutoCloseable {
    private final VulkanImage world;
    private final VulkanImage preHand, preTranslucent;
    private boolean initialized, inFrame, capturedHand, capturedTranslucent, closed;

    public NativeWorldDepth(VulkanImage world, boolean needPreHand, boolean needPreTranslucent) {
        RenderSystem.assertOnRenderThread();
        if (world == null || !VulkanImage.isDepthFormat(world.format)
                || (world.getUsage() & (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT))
                != (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT))
            throw new IllegalArgumentException("World depth must be sampleable and support transfer reads");
        this.world = world;
        VulkanImage hand = null, translucent = null;
        try {
            if (needPreHand) hand = allocate(world);
            if (needPreTranslucent) translucent = allocate(world);
        } catch (Throwable failure) {
            if (hand != null) hand.free();
            if (translucent != null) translucent.free();
            throw failure;
        }
        preHand = hand;
        preTranslucent = translucent;
    }

    public void beginFrame() {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (inFrame) throw new IllegalStateException("Previous depth frame is unfinished");
        // Defined initial contents; later frames retain the previous snapshots until the actual copy point.
        if (!initialized) {
            if (preHand != null) NativeImageOps.clearDepth(preHand, 1, 0);
            if (preTranslucent != null) NativeImageOps.clearDepth(preTranslucent, 1, 0);
            initialized = true;
        }
        capturedHand = preHand == null;
        capturedTranslucent = preTranslucent == null;
        inFrame = true;
    }

    public void capturePreHand() {
        requireFrame();
        if (preHand == null) return;
        if (capturedHand || capturedTranslucent && preTranslucent != null)
            throw new IllegalStateException("Pre-hand depth captured at the wrong boundary");
        NativeImageOps.copyDepthOnly(world, preHand);
        capturedHand = true;
    }
    public void capturePreTranslucent() {
        requireFrame();
        if (preTranslucent == null) return;
        if (!capturedHand || capturedTranslucent) throw new IllegalStateException("Pre-translucent depth captured at the wrong boundary");
        NativeImageOps.copyDepthOnly(world, preTranslucent);
        capturedTranslucent = true;
    }
    public void endFrame() {
        requireFrame();
        if (!capturedHand || !capturedTranslucent) throw new IllegalStateException("Required depth snapshots were not captured");
        inFrame = false;
    }
    public VulkanImage image(String sampler) {
        requireOpen();
        VulkanImage image = switch (sampler) {
            case "depthtex0", "gdepthtex" -> world;
            case "depthtex1" -> preTranslucent;
            case "depthtex2" -> preHand;
            default -> throw new IllegalArgumentException("Unknown world depth sampler " + sampler);
        };
        if (image == null) throw new IllegalStateException("Depth snapshot was not requested: " + sampler);
        return image;
    }
    private static VulkanImage allocate(VulkanImage world) {
        return VulkanImage.createDepthImage(world.format, world.width, world.height,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, false, true);
    }
    private void requireFrame() {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (!inFrame) throw new IllegalStateException("No active depth frame");
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("World depth snapshots are closed"); }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        if (preHand != null) preHand.free();
        if (preTranslucent != null) preTranslucent.free();
    }
}
