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
    private final java.util.Set<Integer> storage;
    private boolean initialized, closed;

    private static final class Target {
        boolean swapped;
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
        this(layout, budgetBytes, java.util.Set.of());
    }

    /** @param storage targets a shader stores into as colorimgN; they get storage usage on every copy. */
    public NativeColorTargets(ShaderTargetLayout layout, long budgetBytes, java.util.Set<Integer> storage) {
        RenderSystem.assertOnRenderThread();
        this.layout = layout;
        this.budgetBytes = budgetBytes;
        this.storage = java.util.Set.copyOf(storage);
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
            if (this.storage.contains(spec.index())) requireStorage(spec);
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
        // A persistent target that ended a frame on its alternate half swaps the two roles instead
        // of being copied back (copyBack): "main" is then the image that half was drawn into.
        return (side == Side.MAIN) != target.swapped ? target.main : target.alt;
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
        mipmaps(java.util.List.of(index), ignored -> side);
    }

    /** Mip chains of several targets, generated together (see NativeImageOps.mipmaps). */
    public void mipmaps(java.util.Collection<Integer> indices, java.util.function.IntFunction<Side> side) {
        requireOpen();
        var images = new java.util.ArrayList<VulkanImage>(indices.size());
        var linear = new boolean[indices.size()];
        var owners = new java.util.ArrayList<Target>(indices.size());
        for (int index : indices) {
            var target = target(index);
            linear[images.size()] = !target.spec.format().integer();
            images.add(image(index, side.apply(index)));
            owners.add(target);
        }
        NativeImageOps.mipmaps(images, linear);
        for (int i = 0; i < images.size(); i++) sampler(images.get(i), owners.get(i), true);
    }

    /**
     * Iris copies a persistent target that ended the frame on its alternate half back to the main
     * one (FinalPassRenderer). Swapping which image plays "main" has the same effect for every
     * read of the next frame, without the full-image copy (0.37 ms per frame for Photon at 1440p).
     * -Dvsdh.copyBack=true restores the copy.
     */
    public void copyBack(java.util.List<Integer> indices) {
        copyBack(indices, java.util.Set.of());
    }

    /**
     * {@code swappable}: targets whose next write fully replaces the alternate half (fullscreen
     * passes without blending). Iris leaves both halves equal after its copy; a swapped target's
     * alternate half holds an older state instead, which only a blended or partial write (geometry,
     * image stores) could see, so those keep the copy.
     */
    public void copyBack(java.util.List<Integer> indices, java.util.Set<Integer> swappable) {
        requireOpen();
        for (int index : indices) {
            Target target = target(index);
            if (target.main == target.alt || target.spec.clears())
                throw new IllegalArgumentException("Invalid persistent target copy-back: " + index);
            if (COPY_BACK || !swappable.contains(index)) NativeImageOps.copyBase(image(index, Side.ALT), image(index, Side.MAIN));
            else target.swapped = !target.swapped;
        }
    }
    private static final boolean COPY_BACK = Boolean.getBoolean("vsdh.copyBack");

    private Target target(int index) {
        if (index < 0 || index >= targets.length || targets[index] == null)
            throw new IllegalArgumentException("Shader target is not allocated: colortex" + index);
        return targets[index];
    }
    private VulkanImage allocate(ShaderTargetLayout.Target spec) {
        int storageUsage = storage.contains(spec.index()) ? VK_IMAGE_USAGE_STORAGE_BIT : 0;
        VulkanImage image = VulkanImage.builder(spec.width(), spec.height()).setFormat(VulkanTargetFormat.of(spec.format()))
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | storageUsage)
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
    private static void requireStorage(ShaderTargetLayout.Target spec) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, VulkanTargetFormat.of(spec.format()), props);
            if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0)
                throw new IllegalArgumentException("Device cannot store into shader target colortex" + spec.index() + ": " + spec.format());
        }
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
