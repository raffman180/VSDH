/* VSDH MRT adaptation of VulkanMod Reforged; LGPL-3.0-only. See NOTICE. */
package net.vulkanmod.vulkan.framebuffer;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class RenderPass {
    Framebuffer framebuffer;
    long id;
    final int attachmentCount;
    AttachmentInfo colorAttachmentInfo;
    final AttachmentInfo[] colors;
    AttachmentInfo depthAttachmentInfo;

    public RenderPass(Framebuffer framebuffer, AttachmentInfo color, AttachmentInfo depth) {
        this(framebuffer, color == null ? new AttachmentInfo[0] : new AttachmentInfo[]{color}, depth);
    }
    public RenderPass(Framebuffer framebuffer, AttachmentInfo[] colors, AttachmentInfo depth) {
        if (colors.length != framebuffer.getColorAttachmentCount()) throw new IllegalArgumentException("Render pass color count differs from framebuffer");
        this.framebuffer = framebuffer;
        this.colors = colors.clone();
        this.colorAttachmentInfo = colors.length == 0 ? null : colors[0];
        this.depthAttachmentInfo = depth;
        this.attachmentCount = colors.length + (depth == null ? 0 : 1);
        if (!Vulkan.DYNAMIC_RENDERING) {
            framebuffer.addRenderPass(this);
            createRenderPass();
        }
    }

    public int getColorAttachmentCount() { return colors.length; }

    private void createRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer descriptions = VkAttachmentDescription.calloc(attachmentCount, stack);
            VkAttachmentReference.Buffer colorRefs = colors.length == 0 ? null : VkAttachmentReference.calloc(colors.length, stack);
            for (int i = 0; i < colors.length; i++) {
                AttachmentInfo info = colors[i];
                describe(descriptions.get(i), info, info.attachmentLayout());
                colorRefs.get(i).attachment(i).layout(info.attachmentLayout());
            }
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).pColorAttachments(colorRefs);
            subpass.colorAttachmentCount(colors.length);
            if (depthAttachmentInfo != null) {
                describe(descriptions.get(colors.length), depthAttachmentInfo, depthAttachmentInfo.attachmentLayout());
                VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                        .attachment(colors.length).layout(depthAttachmentInfo.attachmentLayout());
                subpass.pDepthStencilAttachment(depthRef);
            }
            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(descriptions).pSubpasses(subpass)
                    .pDependencies(createSubpassDependencies(stack, colors, depthAttachmentInfo));
            LongBuffer handle = stack.mallocLong(1);
            int result = vkCreateRenderPass(Vulkan.getVkDevice(), info, null, handle);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateRenderPass failed: " + result);
            id = handle.get(0);
        }
    }

    private static void describe(VkAttachmentDescription description, AttachmentInfo info, int initialLayout) {
        description.format(info.format).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(info.loadOp).storeOp(info.storeOp)
                .stencilLoadOp(info.type == AttachmentInfo.Type.DEPTH ? info.loadOp : VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(info.type == AttachmentInfo.Type.DEPTH ? info.storeOp : VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(initialLayout).finalLayout(info.finalLayout);
    }

    static VkSubpassDependency.Buffer createSubpassDependencies(MemoryStack stack, AttachmentInfo color, AttachmentInfo depth) {
        return createSubpassDependencies(stack, color == null ? new AttachmentInfo[0] : new AttachmentInfo[]{color}, depth);
    }
    private static VkSubpassDependency.Buffer createSubpassDependencies(MemoryStack stack, AttachmentInfo[] colors, AttachmentInfo depth) {
        boolean loads = depth != null && depth.loadOp == VK_ATTACHMENT_LOAD_OP_LOAD;
        boolean presents = false;
        boolean samples = depth != null && depth.finalLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        boolean feedback = depth != null && depth.feedbackLoop;
        for (AttachmentInfo color : colors) feedback |= color.feedbackLoop;
        int stages = depth == null ? 0 : VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        int writes = depth == null ? 0 : VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        int reads = depth == null ? 0 : VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
        for (AttachmentInfo color : colors) {
            loads |= color.loadOp == VK_ATTACHMENT_LOAD_OP_LOAD;
            presents |= color.finalLayout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            samples |= color.finalLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            stages |= VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            writes |= VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            reads |= VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
        }
        if (feedback) {
            // The pass samples its own attachments: their earlier writes must be visible to the shader.
            stages |= VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            reads |= VK_ACCESS_SHADER_READ_BIT;
        }
        int count = (loads ? 1 : 0) + (presents ? 1 : 0) + (samples ? 1 : 0);
        if (count == 0) return null;
        VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(count, stack);
        int n = 0;
        if (loads) dependencies.get(n++).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(stages).dstStageMask(stages).srcAccessMask(writes).dstAccessMask(reads | writes);
        if (presents) dependencies.get(n++).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                .srcStageMask(stages).dstStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT).srcAccessMask(writes).dstAccessMask(0);
        if (samples) dependencies.get(n).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                .srcStageMask(stages).dstStageMask(VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(writes).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
        return dependencies;
    }

    private void transitionForRendering(VkCommandBuffer cmd, MemoryStack stack) {
        for (int i = 0; i < colors.length; i++)
            framebuffer.getColorAttachment(i).transitionImageLayout(stack, cmd, colors[i].attachmentLayout());
        if (depthAttachmentInfo != null)
            framebuffer.getDepthAttachment().transitionImageLayout(stack, cmd, depthAttachmentInfo.attachmentLayout());
    }
    /** Whether colour attachment {@code index} may be sampled by the pass itself (feedback loop layout). */
    public boolean colorFeedbackLoop(int index) { return colors[index].feedbackLoop; }
    public boolean depthFeedbackLoop() { return depthAttachmentInfo != null && depthAttachmentInfo.feedbackLoop; }
    /** VkPipelineCreateFlags a pipeline drawing in this pass needs for its feedback loops. */
    public int feedbackLoopPipelineFlags() {
        int flags = depthFeedbackLoop() ? 0x04000000 /* DEPTH_STENCIL_ATTACHMENT_FEEDBACK_LOOP_BIT_EXT */ : 0;
        for (AttachmentInfo color : colors)
            if (color.feedbackLoop) flags |= 0x02000000; // COLOR_ATTACHMENT_FEEDBACK_LOOP_BIT_EXT
        return flags;
    }
    private VkClearValue.Buffer clearValues(MemoryStack stack) {
        VkClearValue.Buffer values = VkClearValue.calloc(attachmentCount, stack);
        for (int i = 0; i < colors.length; i++) values.get(i).color().float32(VRenderSystem.clearColor);
        if (depthAttachmentInfo != null) values.get(colors.length).depthStencil().set(VRenderSystem.clearDepthValue, VRenderSystem.clearStencilValue);
        return values;
    }
    public void beginRenderPass(VkCommandBuffer cmd, long framebufferId, MemoryStack stack) {
        transitionForRendering(cmd, stack);
        VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(id).framebuffer(framebufferId);
        info.renderArea().offset().set(0, 0);
        info.renderArea().extent().set(framebuffer.getWidth(), framebuffer.getHeight());
        info.pClearValues(clearValues(stack));
        vkCmdBeginRenderPass(cmd, info, VK_SUBPASS_CONTENTS_INLINE);
        Renderer.getInstance().setBoundRenderPass(this);
    }
    public void endRenderPass(VkCommandBuffer cmd) {
        vkCmdEndRenderPass(cmd);
        recordFinalLayouts(cmd);
        Renderer.getInstance().setBoundRenderPass(null);
    }
    private void recordFinalLayouts(VkCommandBuffer cmd) {
        for (int i = 0; i < colors.length; i++)
            completeFinalLayout(cmd, framebuffer.getColorAttachment(i), colors[i].attachmentLayout(), colors[i].finalLayout);
        if (depthAttachmentInfo != null)
            completeFinalLayout(cmd, framebuffer.getDepthAttachment(), depthAttachmentInfo.attachmentLayout(), depthAttachmentInfo.finalLayout);
    }
    private static void completeFinalLayout(VkCommandBuffer cmd, VulkanImage image, int attachmentLayout, int finalLayout) {
        // The render pass transitions only the attached base mip. Keep the remaining levels in
        // the same layout before publishing a whole-image state to VulkanImage's transition tracker.
        if (image.mipLevels > 1 && finalLayout != attachmentLayout) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .oldLayout(attachmentLayout).newLayout(finalLayout).image(image.getId())
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
                barrier.subresourceRange().aspectMask(image.aspect).baseMipLevel(1).levelCount(image.mipLevels - 1)
                        .baseArrayLayer(0).layerCount(1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        0, null, null, barrier);
            }
        }
        image.setCurrentLayout(finalLayout);
    }
    public void beginDynamicRendering(VkCommandBuffer cmd, MemoryStack stack) {
        transitionForRendering(cmd, stack);
        VkClearValue.Buffer values = clearValues(stack);
        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1);
        info.renderArea().offset().set(0, 0);
        info.renderArea().extent().set(framebuffer.getWidth(), framebuffer.getHeight());
        if (colors.length > 0) {
            VkRenderingAttachmentInfo.Buffer attachments = VkRenderingAttachmentInfo.calloc(colors.length, stack);
            for (int i = 0; i < colors.length; i++) attachments.get(i).sType$Default()
                    .imageView(framebuffer.getColorAttachment(i).getLevelImageView(0)).imageLayout(colors[i].attachmentLayout())
                    .loadOp(colors[i].loadOp).storeOp(colors[i].storeOp).clearValue(values.get(i));
            info.pColorAttachments(attachments);
        }
        if (depthAttachmentInfo != null) {
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(framebuffer.getDepthAttachment().getLevelImageView(0)).imageLayout(depthAttachmentInfo.attachmentLayout())
                    .loadOp(depthAttachmentInfo.loadOp).storeOp(depthAttachmentInfo.storeOp).clearValue(values.get(colors.length));
            info.pDepthAttachment(depth);
            if (VulkanImage.hasStencilComponent(depthAttachmentInfo.format)) info.pStencilAttachment(depth);
        }
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
        Renderer.getInstance().setBoundRenderPass(this);
    }
    public void endDynamicRendering(VkCommandBuffer cmd) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        // Dynamic rendering has no implicit final-layout transitions.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < colors.length; i++) framebuffer.getColorAttachment(i).transitionImageLayout(stack, cmd, colors[i].finalLayout);
            if (depthAttachmentInfo != null) framebuffer.getDepthAttachment().transitionImageLayout(stack, cmd, depthAttachmentInfo.finalLayout);
        }
        Renderer.getInstance().setBoundRenderPass(null);
    }
    public Framebuffer getFramebuffer() { return framebuffer; }
    public long getId() { return id; }
    public void cleanUp() {
        if (!Vulkan.DYNAMIC_RENDERING) {
            long retiring = id;
            VkDevice device = Vulkan.getVkDevice();
            MemoryManager.getInstance().addFrameOp(() -> vkDestroyRenderPass(device, retiring, null));
        }
    }
    public static class AttachmentInfo {
        final Type type;
        final int format;
        int finalLayout;
        int loadOp;
        int storeOp;
        boolean feedbackLoop;

        /**
         * The pass may sample this attachment while drawing into it (VK_EXT_attachment_feedback_loop_layout).
         * The image needs FEEDBACK_LOOP usage and stays in the feedback layout after the pass.
         */
        public AttachmentInfo setFeedbackLoop() {
            if (finalLayout == type.defaultLayout) finalLayout = VulkanImage.FEEDBACK_LOOP_LAYOUT;
            feedbackLoop = true;
            return this;
        }

        int attachmentLayout() { return feedbackLoop ? VulkanImage.FEEDBACK_LOOP_LAYOUT : type.defaultLayout; }

        public AttachmentInfo(Type type, int format) {
            this.type = type;
            this.format = format;
            this.finalLayout = type.defaultLayout;

            this.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            this.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        }

        public AttachmentInfo setOps(int loadOp, int storeOp) {
            this.loadOp = loadOp;
            this.storeOp = storeOp;

            return this;
        }

        public AttachmentInfo setLoadOp(int loadOp) {
            this.loadOp = loadOp;

            return this;
        }

        public AttachmentInfo setFinalLayout(int finalLayout) {
            this.finalLayout = finalLayout;

            return this;
        }

        public enum Type {
            COLOR(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL),
            DEPTH(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            final int defaultLayout;

            Type(int layout) {
                defaultLayout = layout;
            }
        }
    }


    public static Builder builder(Framebuffer framebuffer) { return new Builder(framebuffer); }
    public static class Builder {
        final Framebuffer framebuffer;
        final AttachmentInfo[] colors;
        AttachmentInfo depthAttachmentInfo;
        public Builder(Framebuffer framebuffer) {
            this.framebuffer = framebuffer;
            int[] formats = framebuffer.getColorFormats();
            colors = new AttachmentInfo[formats.length];
            for (int i = 0; i < formats.length; i++) colors[i] = new AttachmentInfo(AttachmentInfo.Type.COLOR, formats[i])
                    .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
            if (framebuffer.hasDepthAttachment) depthAttachmentInfo = new AttachmentInfo(AttachmentInfo.Type.DEPTH, framebuffer.depthFormat)
                    .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        }
        public RenderPass build() { return new RenderPass(framebuffer, colors, depthAttachmentInfo); }
        public Builder setLoadOp(int op) {
            for (AttachmentInfo color : colors) color.setLoadOp(op);
            if (depthAttachmentInfo != null) depthAttachmentInfo.setLoadOp(op);
            return this;
        }
        public AttachmentInfo getColorAttachmentInfo() { return colors.length == 0 ? null : colors[0]; }
        public AttachmentInfo getColorAttachmentInfo(int index) { return colors[index]; }
        public AttachmentInfo getDepthAttachmentInfo() { return depthAttachmentInfo; }
    }
}
