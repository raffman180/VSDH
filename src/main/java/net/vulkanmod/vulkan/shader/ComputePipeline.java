package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One compute shader over the shared descriptor machinery of {@link Pipeline}: dynamic uniform
 * buffers from the frame's uniform ring, sampled and storage images. Created and used on the
 * render thread only; dispatches must be recorded outside a render pass.
 */
public final class ComputePipeline extends Pipeline {
    private final long module;
    private final long handle;
    private boolean closed;

    public ComputePipeline(String name, List<UBO> buffers, List<ImageDescriptor> images,
                           List<net.vulkanmod.vulkan.shader.descriptor.StorageBufferDescriptor> storage, ByteBuffer spirv) {
        super(name);
        this.buffers = buffers;
        this.imageDescriptors = images;
        this.storageBuffers = List.copyOf(storage);
        this.pushConstants = null;
        try (var construction = new ResourceRollback()) {
            createDescriptorSetLayout();
            construction.add(() -> vkDestroyDescriptorSetLayout(DeviceManager.vkDevice, descriptorSetLayout, null));
            createPipelineLayout();
            construction.add(() -> vkDestroyPipelineLayout(DeviceManager.vkDevice, pipelineLayout, null));
            this.module = createShaderModule(spirv);
            construction.add(() -> vkDestroyShaderModule(DeviceManager.vkDevice, module, null));
            this.handle = createPipeline();
            construction.add(() -> vkDestroyPipeline(DeviceManager.vkDevice, handle, null));
            createDescriptorSets(Renderer.getFramesNum());
            construction.add(this::destroyDescriptorSets);
            PIPELINES.add(this);
            construction.commit();
        }
    }

    private long createPipeline() {
        try (MemoryStack stack = stackPush()) {
            var info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.sType$Default();
            info.stage().sType$Default();
            info.stage().stage(VK_SHADER_STAGE_COMPUTE_BIT);
            info.stage().module(module);
            info.stage().pName(stack.UTF8("main"));
            info.layout(pipelineLayout);
            LongBuffer result = stack.mallocLong(1);
            long began = System.nanoTime();
            int status = vkCreateComputePipelines(DeviceManager.vkDevice, PIPELINE_CACHE, info, null, result);
            PipelineCacheStore.created(System.nanoTime() - began);
            if (status != VK_SUCCESS) throw new RuntimeException("Failed to create compute pipeline " + name + ": " + status);
            return result.get(0);
        }
    }

    /** Binds the pipeline and this frame's descriptor set (uniforms copied into the frame ring now). */
    public void bind(VkCommandBuffer commandBuffer) {
        if (closed) throw new IllegalStateException("Compute pipeline is closed: " + name);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, handle);
        descriptorSets[Renderer.getCurrentFrame()].bindSets(commandBuffer, Renderer.getDrawer().getUniformBuffer(),
                VK_PIPELINE_BIND_POINT_COMPUTE);
        Renderer.getInstance().addUsedPipeline(this);
    }

    @Override
    public void cleanUp() {
        if (closed) return;
        closed = true;
        try (var release = new ResourceRollback()) {
            release.add(() -> Renderer.getInstance().removeUsedPipeline(this));
            release.add(() -> PIPELINES.remove(this));
            release.add(() -> vkDestroyDescriptorSetLayout(DeviceManager.vkDevice, descriptorSetLayout, null));
            release.add(() -> vkDestroyPipelineLayout(DeviceManager.vkDevice, pipelineLayout, null));
            release.add(() -> vkDestroyShaderModule(DeviceManager.vkDevice, module, null));
            release.add(() -> vkDestroyPipeline(DeviceManager.vkDevice, handle, null));
            release.add(this::destroyDescriptorSets);
        }
    }
}
