package net.vulkanmod.vulkan.pass;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface MainPass {

    void begin(VkCommandBuffer commandBuffer, MemoryStack stack);

    void end(VkCommandBuffer commandBuffer);

    /**
     * Late swapchain wait (net.vulkanmod.vulkan.LateAcquire): starts the frame like {@link #begin} but without
     * beginning a render pass on the main target, so no command uses the swapchain image yet. The first
     * {@link #rebindMainTarget} binds it with the LOAD pass. False when this pass cannot do that; the caller then
     * calls {@link #begin}.
     */
    default boolean beginWithoutTarget() {
        return false;
    }

    /** True from {@link #beginWithoutTarget} until the main target is first bound in this frame. */
    default boolean mainTargetDeferred() {
        return false;
    }


    default void onResize() {}


    default void cleanUp() {}

    default void mainTargetBindWrite() {}

    default void mainTargetUnbindWrite() {}

    default void rebindMainTarget() {}

    default void bindAsTexture() {}

    default void resolveRenderScaleForGui() {}


    default RenderPass resumeRenderPass(RenderPass interrupted) {
        return interrupted;
    }

    default int getColorAttachmentGlId() {
        return -1;
    }

    default int getDepthAttachmentGlId() {
        return -1;
    }

    default VulkanImage getColorAttachment() {
        return null;
    }

    default VulkanImage getDepthAttachment() {
        return null;
    }
}
