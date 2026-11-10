package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrail.pack.target.TargetDirectives.Colour;
import dev.vitrail.pack.target.TargetSchedule.Side;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;

import static net.vulkanmod.vulkan.texture.SamplerManager.*;
import static org.lwjgl.vulkan.VK10.*;

/** Sole owner of all colortex images. Framebuffers borrow these images and never free them. */
public final class NativeColorTargets implements AutoCloseable {
    private final ShaderTargetLayout layout;
    private final long budgetBytes;
    private long feedbackBytes;
    private final Target[] targets = new Target[32];
    private boolean initialized, closed;

    private static final class Target {
        final ShaderTargetLayout.Target spec;
        final VulkanImage main;
        final SamplerKey baseSampler, mipSampler;
        VulkanImage alt, feedback;
        Target(ShaderTargetLayout.Target spec, VulkanImage main) {
            this.spec = spec; this.main = main; alt = main;
            baseSampler = policy(spec, false); mipSampler = policy(spec, true);
        }
    }

    public NativeColorTargets(ShaderTargetLayout layout, long budgetBytes) {
        RenderSystem.assertOnRenderThread();
        this.layout = layout;
        this.budgetBytes = budgetBytes;
        // Validate EVERY target before the first allocation, including format/mipmap device support.
        var indices = new java.util.HashSet<Integer>();
        for (var spec : layout.targets()) {
            if (spec.index() < 0 || spec.index() >= targets.length || !indices.add(spec.index()))
                throw new IllegalArgumentException("Invalid/duplicate shader target: " + spec.index());
            int max = DeviceManager.deviceProperties.limits().maxImageDimension2D();
            if (spec.width() < 1 || spec.height() < 1 || spec.width() > max || spec.height() > max
                    || spec.levels() < 1 || spec.levels() > 32 - Integer.numberOfLeadingZeros(Math.max(spec.width(), spec.height())))
                throw new IllegalArgumentException("Invalid target dimensions or mip count: " + spec);
            VulkanTargetFormat.requireRenderable(DeviceManager.physicalDevice, spec.format(), !spec.format().integer());
            if (spec.levels() > 1) requireBlit(spec);
        }
        try {
            for (var spec : layout.targets()) {
                var target = new Target(spec, allocate(spec));
                targets[spec.index()] = target;
                if (spec.doubled()) target.alt = allocate(spec);
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    public ShaderTargetLayout layout() { return layout; }
    public VulkanImage image(int index, Side side) {
        requireOpen();
        Target target = target(index);
        return side == Side.MAIN ? target.main : target.alt;
    }

    /** Geometry reads the image as it stood before the draw without aliasing its output. */
    VulkanImage feedbackImage(int index, VulkanImage source) {
        var target = target(index);
        if (target.feedback == null) {
            long bytes = target.spec.bytes() / (target.spec.doubled() ? 2 : 1);
            if (bytes > budgetBytes - layout.bytes() - feedbackBytes)
                throw new IllegalArgumentException("Shader color feedback exceeds allocation budget at colortex" + index);
            target.feedback = allocate(target.spec);
            feedbackBytes += bytes;
        }
        boolean mipmaps = source.mipLevels > 1 && source.getSampler() == getTextureSampler(target.mipSampler);
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            if (mipmaps) NativeImageOps.copyColourMipChain(source, target.feedback);
            else NativeImageOps.copyBase(source, target.feedback);
        }
        sampler(target.feedback, target, mipmaps);
        return target.feedback;
    }

    public void beginFrame(Colour fogColour) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        for (var spec : layout.targets()) {
            Target target = targets[spec.index()];
            // Iris starts each frame with base-level filtering, then enables mips at their first request.
            sampler(target.main, target, false);
            if (target.alt != target.main) sampler(target.alt, target, false);
            if (!initialized || spec.clears()) {
                Colour clear = spec.fogClear() ? fogColour : spec.clearColour();
                NativeImageOps.clear(target.main, spec.format(), clear);
                if (target.alt != target.main) NativeImageOps.clear(target.alt, spec.format(), clear);
            }
        }
        initialized = true;
    }

    public void mipmaps(int index, Side side) {
        requireOpen();
        var target = target(index);
        var image = image(index, side);
        NativeImageOps.mipmaps(image, !target.spec.format().integer());
        sampler(image, target, true);
    }

    public void copyBack(java.util.List<Integer> indices) {
        requireOpen();
        for (int index : indices) {
            Target target = target(index);
            if (target.main == target.alt || target.spec.clears())
                throw new IllegalArgumentException("Invalid persistent target copy-back: " + index);
            NativeImageOps.copyBase(target.alt, target.main);
        }
    }

    private Target target(int index) {
        if (index < 0 || index >= targets.length || targets[index] == null)
            throw new IllegalArgumentException("Shader target is not allocated: colortex" + index);
        return targets[index];
    }
    private static VulkanImage allocate(ShaderTargetLayout.Target spec) {
        VulkanImage image = VulkanImage.builder(spec.width(), spec.height()).setFormat(VulkanTargetFormat.of(spec.format()))
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .setMipLevels(spec.levels()).setLevelViews(spec.levels() > 1)
                .setLinearFiltering(!spec.format().integer()).setClamp(true).createVulkanImage();
        try {
            image.updateTextureSampler(policy(spec, false));
            return image;
        } catch (Throwable failure) { image.free(); throw failure; }
    }
    private static void sampler(VulkanImage image, Target target, boolean mipmaps) {
        image.updateTextureSampler(mipmaps ? target.mipSampler : target.baseSampler);
    }
    private static SamplerKey policy(ShaderTargetLayout.Target spec, boolean mipmaps) {
        int filter = spec.format().integer() ? 9728 : 9729; // GL_NEAREST / GL_LINEAR
        int min = mipmaps && spec.levels() > 1 ? (spec.format().integer() ? 9984 : 9987) : filter;
        // Shader targets use zero LOD bias; the vanilla terrain texture sampler intentionally uses -0.5.
        return new SamplerKey(min, filter, 33071, 33071, 33071, 0, mipmaps ? spec.levels() - 1 : 0, 0, 0, 515);
    }
    private static void requireBlit(ShaderTargetLayout.Target spec) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, VulkanTargetFormat.of(spec.format()), props);
            int required = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
            if ((props.optimalTilingFeatures() & required) != required)
                throw new IllegalArgumentException("Device cannot generate shader target mipmaps: " + spec.format());
        }
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Shader targets are closed"); }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (Target target : targets) if (target != null) {
            target.main.free();
            if (target.alt != target.main) target.alt.free();
            if (target.feedback != null) target.feedback.free();
        }
    }
}
