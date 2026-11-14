package net.vulkanmod.vulkan.framebuffer;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.commons.lang3.Validate;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Arrays;

import static net.vulkanmod.vulkan.Vulkan.DYNAMIC_RENDERING;
import static org.lwjgl.vulkan.VK10.*;

public class Framebuffer {
    public static final int DEFAULT_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;



    protected int format;
    protected int depthFormat;
    protected int width, height;
    protected boolean linearFiltering;
    protected boolean depthLinearFiltering;
    protected int attachmentCount;

    boolean hasColorAttachment;
    boolean hasDepthAttachment;

    private VulkanImage colorAttachment;
    private VulkanImage[] colorAttachments = new VulkanImage[0];
    private int[] colorFormats = new int[0];
    private boolean ownsAttachments = true;
    protected VulkanImage depthAttachment;

    private final ObjectArrayList<RenderPass> renderPasses = new ObjectArrayList<>();

    private final Reference2LongArrayMap<RenderPass> framebufferIds = new Reference2LongArrayMap<>();


    protected Framebuffer() {}

    public Framebuffer(Builder builder) {
        this.format = builder.format;
        this.depthFormat = builder.depthFormat;
        this.width = builder.width;
        this.height = builder.height;
        this.linearFiltering = builder.linearFiltering;
        this.depthLinearFiltering = builder.depthLinearFiltering;
        this.hasColorAttachment = builder.hasColorAttachment;
        this.hasDepthAttachment = builder.hasDepthAttachment;
        this.colorFormats = builder.colorFormats.clone();
        this.ownsAttachments = builder.ownsAttachments;
        this.attachmentCount = colorFormats.length + (hasDepthAttachment ? 1 : 0);

        if (builder.createImages)
            this.createImages();
        else {
            this.colorAttachments = builder.colorImages.clone();
            this.colorAttachment = colorAttachments.length == 0 ? null : colorAttachments[0];
            this.depthAttachment = builder.depthAttachment;
        }
    }

    public void addRenderPass(RenderPass renderPass) {
        this.renderPasses.add(renderPass);
    }

    public void createImages() {
        this.colorAttachment = null;
        this.depthAttachment = null;
        this.colorAttachments = new VulkanImage[colorFormats.length];
        try {
            for (int i = 0; i < colorFormats.length; i++) {
                this.colorAttachments[i] = VulkanImage.builder(this.width, this.height)
                        .setFormat(colorFormats[i])
                        .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .setLinearFiltering(linearFiltering).setClamp(true).createVulkanImage();
            }
            this.colorAttachment = colorAttachments.length == 0 ? null : colorAttachments[0];

            if (this.hasDepthAttachment) {
                this.depthAttachment = VulkanImage.createDepthImage(depthFormat, this.width, this.height,
                        MainDepthSnapshot.depthAttachmentUsage(),
                        depthLinearFiltering, true);

                this.attachmentCount = colorFormats.length + 1;
            }
        } catch (Throwable failure) {
            // A rejected format/allocation must also retire attachments created earlier in this batch.
            for (VulkanImage image : colorAttachments) if (image != null) image.free();
            if (depthAttachment != null) depthAttachment.free();
            throw failure;
        }
    }

    public void resize(int newWidth, int newHeight) {
        if (!ownsAttachments) throw new IllegalStateException("Resize the owner of shared framebuffer attachments");
        this.width = newWidth;
        this.height = newHeight;

        this.cleanUp();

        this.createImages();
    }

    private long createFramebuffer(RenderPass renderPass) {

        try (MemoryStack stack = MemoryStack.stackPush()) {

            LongBuffer attachments = stack.mallocLong(getColorAttachmentCount() + (hasDepthAttachment ? 1 : 0));
            for (int i = 0; i < getColorAttachmentCount(); i++) attachments.put(getColorAttachment(i).getLevelImageView(0));
            if (hasDepthAttachment) attachments.put(depthAttachment.getLevelImageView(0));
            attachments.flip();

            LongBuffer pFramebuffer = stack.mallocLong(1);

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
            framebufferInfo.sType$Default();
            framebufferInfo.renderPass(renderPass.getId());
            framebufferInfo.width(this.width);
            framebufferInfo.height(this.height);
            framebufferInfo.layers(1);
            framebufferInfo.pAttachments(attachments);

            if (VK10.vkCreateFramebuffer(Vulkan.getVkDevice(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create framebuffer");
            }

            return pFramebuffer.get(0);
        }
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, RenderPass renderPass, MemoryStack stack) {
        if (!DYNAMIC_RENDERING) {
            long framebufferId = this.getFramebufferId(renderPass);
            renderPass.beginRenderPass(commandBuffer, framebufferId, stack);
        } else {
            renderPass.beginDynamicRendering(commandBuffer, stack);
        }

        Renderer.getInstance().setBoundRenderPass(renderPass);
        Renderer.getInstance().setBoundFramebuffer(this);

        Renderer.setViewport(0, 0, this.width, this.height);
        Renderer.applyScissor();
    }

    protected long getFramebufferId(RenderPass renderPass) {
        // Looked up for every render pass begin: no capturing lambda on the hit path.
        long id = this.framebufferIds.getLong(renderPass);
        if (id == this.framebufferIds.defaultReturnValue() && !this.framebufferIds.containsKey(renderPass)) {
            id = createFramebuffer(renderPass);
            this.framebufferIds.put(renderPass, id);
        }
        return id;
    }

    public VkViewport.Buffer viewport(MemoryStack stack) {
        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(0.0f);
        viewport.y(this.height);
        viewport.width(this.width);
        viewport.height(-this.height);
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        return viewport;
    }

    public VkRect2D.Buffer scissor(MemoryStack stack) {
        VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
        scissor.offset().set(0, 0);
        scissor.extent().set(this.width, this.height);

        return scissor;
    }

    public void cleanUp() {
        cleanUp(true);
    }

    public void cleanUp(boolean cleanImages) {
        if (cleanImages && ownsAttachments) {
            for (VulkanImage image : this.colorAttachments) if (image != null) image.free();

            if (this.depthAttachment != null)
                this.depthAttachment.free();
        }

        final VkDevice device = Vulkan.getVkDevice();
        final var ids = framebufferIds.values().toLongArray();

        MemoryManager.getInstance().addFrameOp(
                () -> Arrays.stream(ids).forEach(id ->
                        vkDestroyFramebuffer(device, id, null))
        );


        framebufferIds.clear();
    }

    public long getDepthImageView() {
        return depthAttachment.getImageView();
    }

    public VulkanImage getDepthAttachment() {
        return depthAttachment;
    }

    public VulkanImage getColorAttachment() {
        return colorAttachment;
    }

    /** SwapChain overrides the single-image getter and uses the inherited one-target contract. */
    public int getColorAttachmentCount() { return colorFormats.length > 0 ? colorFormats.length : hasColorAttachment ? 1 : 0; }
    public VulkanImage getColorAttachment(int index) {
        if (index < 0 || index >= getColorAttachmentCount()) throw new IndexOutOfBoundsException(index);
        if (index == 0) return getColorAttachment();
        return colorAttachments[index];
    }
    public int[] getColorFormats() {
        return colorFormats.length == 0 && hasColorAttachment ? new int[]{getFormat()} : colorFormats.clone();
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getFormat() {
        return this.format;
    }

    public int getDepthFormat() {
        return this.depthFormat;
    }

    public static Builder builder(int width, int height, int colorAttachments, boolean hasDepthAttachment) {
        return new Builder(width, height, colorAttachments, hasDepthAttachment);
    }

    public static Builder builder(VulkanImage colorAttachment, VulkanImage depthAttachment) {
        return new Builder(colorAttachment, depthAttachment);
    }

    public static Builder builder(VulkanImage[] colors, VulkanImage depth) { return new Builder(colors, depth); }

    public static class Builder {
        final boolean createImages;
        final int width, height;
        int format, depthFormat;

        VulkanImage colorAttachment;
        VulkanImage[] colorImages = new VulkanImage[0];
        int[] colorFormats = new int[0];
        boolean ownsAttachments = true;
        VulkanImage depthAttachment;


        boolean hasColorAttachment;
        boolean hasDepthAttachment;

        boolean linearFiltering;
        boolean depthLinearFiltering;

        public Builder(int width, int height, int colorAttachments, boolean hasDepthAttachment) {
            Validate.isTrue(colorAttachments > 0 || hasDepthAttachment, "At least 1 attachment needed");


            Validate.isTrue(colorAttachments >= 0 && colorAttachments <= 8, "Expected 0..8 color attachments");
            if (net.vulkanmod.vulkan.device.DeviceManager.deviceProperties != null)
                Validate.isTrue(colorAttachments <= net.vulkanmod.vulkan.device.DeviceManager.deviceProperties.limits().maxColorAttachments(),
                        "Color attachment count exceeds the device limit");
            this.colorFormats = new int[colorAttachments];
            Arrays.fill(this.colorFormats, DEFAULT_FORMAT);

            this.createImages = true;
            this.format = DEFAULT_FORMAT;
            this.depthFormat = Vulkan.getDefaultDepthFormat();
            this.linearFiltering = true;
            this.depthLinearFiltering = false;

            this.width = width;
            this.height = height;
            this.hasColorAttachment = colorAttachments > 0;
            this.hasDepthAttachment = hasDepthAttachment;
        }

        public Builder(VulkanImage colorAttachment, VulkanImage depthAttachment) {
            this(colorAttachment == null ? new VulkanImage[0] : new VulkanImage[]{colorAttachment}, depthAttachment);
        }

        public Builder(VulkanImage[] colors, VulkanImage depth) {
            Validate.isTrue(colors != null && colors.length <= 8 && (colors.length > 0 || depth != null), "Invalid framebuffer attachments");
            if (net.vulkanmod.vulkan.device.DeviceManager.deviceProperties != null)
                Validate.isTrue(colors.length <= net.vulkanmod.vulkan.device.DeviceManager.deviceProperties.limits().maxColorAttachments(),
                        "Color attachment count exceeds the device limit");
            Validate.isTrue(colors.length == 0 || colors[0] != null, "Null color attachment");
            var unique = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<VulkanImage, Boolean>());
            for (VulkanImage image : colors)
                Validate.isTrue(image != null && unique.add(image), "Null or duplicate color attachment");
            VulkanImage size = colors.length > 0 ? colors[0] : depth;
            this.width = size.width;
            this.height = size.height;
            this.createImages = false;
            this.colorImages = colors.clone();
            this.colorFormats = new int[colors.length];
            for (int i = 0; i < colors.length; i++) {
                Validate.isTrue(colors[i] != null && colors[i].width == width && colors[i].height == height, "Attachment dimensions differ");
                colorFormats[i] = colors[i].format;
            }
            Validate.isTrue(depth == null || (depth.width == width && depth.height == height), "Depth dimensions differ");
            this.colorAttachment = colors.length == 0 ? null : colors[0];
            this.depthAttachment = depth;
            this.hasColorAttachment = colors.length > 0;
            this.hasDepthAttachment = depth != null;
            this.format = colors.length == 0 ? 0 : colors[0].format;
            this.depthFormat = depth == null ? 0 : depth.format;
            this.linearFiltering = true;
        }

        /** Use for per-pass views over targets owned by a longer-lived render graph. */
        public Builder ownsAttachments(boolean owns) { this.ownsAttachments = owns; return this; }
        public Builder setColorFormats(int... formats) {
            Validate.isTrue(formats.length == colorFormats.length, "Wrong format count");
            this.colorFormats = formats.clone();
            this.format = formats.length == 0 ? 0 : formats[0];
            return this;
        }

        public Framebuffer build() {
            return new Framebuffer(this);
        }

        public Builder setFormat(int format) {
            this.format = format;
            Arrays.fill(this.colorFormats, format);

            return this;
        }

        public Builder setLinearFiltering(boolean b) {
            this.linearFiltering = b;

            return this;
        }

        public Builder setDepthLinearFiltering(boolean b) {
            this.depthLinearFiltering = b;

            return this;
        }

    }
}
