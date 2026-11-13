package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.InventoryMenu;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.texture.SamplerManager.SamplerKey;
import org.lwjgl.system.MemoryUtil;
import java.util.IdentityHashMap;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** GPU owner of predecoded pack images. Borrowed game atlases remain owned by Minecraft. */
public final class NativeWorldTextures implements AutoCloseable {
    private final PackTexturePlan plan;
    private final Map<PackTexturePlan.Asset,VulkanImage> images=new IdentityHashMap<>();
    private boolean closed;
    public NativeWorldTextures(PackTexturePlan plan) {
        RenderSystem.assertOnRenderThread();this.plan=plan;
        long allocated=0;
        // A quarter of the device heap, at least the old 256 MiB and at most 2 GiB (Eclipse ships many large images).
        long deviceMiB=net.vulkanmod.vulkan.memory.MemoryManager.getInstance().getDeviceMemoryMB();
        long budget=Math.clamp(deviceMiB/4,256L,2048L)*1024*1024;
        try {
            for(var asset:plan.bindings().values()) {
                if(images.containsKey(asset))continue;
                if(asset instanceof PackTexturePlan.Pixels pixels) {
                    allocated=Math.addExact(allocated,pixels.rgba().length);
                    if(allocated>budget)throw new IllegalArgumentException("Decoded native pack textures exceed "+(budget>>20)+" MiB");
                    var image=VulkanImage.builder(pixels.width(),pixels.height()).setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                            .setUsage(VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                            .setLinearFiltering(pixels.blur()).setClamp(pixels.clamp()).createVulkanImage();
                    images.put(asset,image);
                    int filter=pixels.blur()?9729:9728,wrap=pixels.clamp()?33071:10497;
                    image.updateTextureSampler(new SamplerKey(filter,filter,wrap,wrap,wrap,0,0,0,0,515));
                    var bytes=MemoryUtil.memAlloc(pixels.rgba().length);
                    try {bytes.put(pixels.rgba()).flip();image.uploadSubTextureAsync(0,pixels.width(),pixels.height(),0,0,0,0,0,bytes);}
                    finally {MemoryUtil.memFree(bytes);}
                } else if(asset instanceof PackTexturePlan.Texels texels) {
                    allocated=Math.addExact(allocated,texels.data().length);
                    if(allocated>budget)throw new IllegalArgumentException("Decoded native pack textures exceed "+(budget>>20)+" MiB");
                    boolean linear=texels.blur()&&filterable(texels.vkFormat());
                    var image=VulkanImage.builder(texels.width(),texels.height()).setFormat(texels.vkFormat())
                            .setUsage(VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                            .setLinearFiltering(linear).setClamp(texels.clamp()).createVulkanImage();
                    images.put(asset,image);
                    int filter=linear?9729:9728,wrap=texels.clamp()?33071:10497;
                    image.updateTextureSampler(new SamplerKey(filter,filter,wrap,wrap,wrap,0,0,0,0,515));
                    var bytes=MemoryUtil.memAlloc(texels.data().length);
                    try {bytes.put(texels.data()).flip();image.uploadSubTextureAsync(0,texels.width(),texels.height(),0,0,0,0,0,bytes);}
                    finally {MemoryUtil.memFree(bytes);}
                } else if(asset instanceof PackTexturePlan.Volume volume) {
                    allocated=Math.addExact(allocated,volume.data().length);
                    if(allocated>budget)throw new IllegalArgumentException("Decoded native pack textures exceed "+(budget>>20)+" MiB");
                    var created=createVolume(volume);
                    volumes.add(created);
                    images.put(asset,created.wrapper);
                } else if(asset instanceof PackTexturePlan.GameImage resource) {
                    if(!resource.location().equals(InventoryMenu.BLOCK_ATLAS.toString()))
                        throw new IllegalArgumentException("Native resource texture needs asynchronous preparation: "+resource.location());
                    // The live atlas is fetched at the draw, so resource reload cannot leave a borrowed stale image.
                }
            }
        } catch(Throwable failure) {close();throw failure;}
    }
    private record OwnedVolume(long handle,long memory,long view,VulkanImage wrapper) {}
    private final java.util.List<OwnedVolume> volumes=new java.util.ArrayList<>();

    /** A real 3D image with the pack's texels, uploaded once and left SHADER_READ_ONLY_OPTIMAL. */
    private static OwnedVolume createVolume(PackTexturePlan.Volume volume) {
        int format=volume.vkFormat();
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            var props=org.lwjgl.vulkan.VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(net.vulkanmod.vulkan.device.DeviceManager.physicalDevice,format,props);
            if((props.optimalTilingFeatures()&VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)==0)
                throw new IllegalArgumentException("Device cannot sample pack volume format "+format);
            boolean linear=volume.blur()&&(props.optimalTilingFeatures()&VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)!=0;
            var create=org.lwjgl.vulkan.VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_3D).format(format)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(volume.width(),volume.height(),volume.depth());
            var allocation=org.lwjgl.util.vma.VmaAllocationCreateInfo.calloc(stack).usage(org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_GPU_ONLY)
                    .requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            var pImage=stack.mallocLong(1);var pMemory=stack.mallocPointer(1);
            int result=org.lwjgl.util.vma.Vma.vmaCreateImage(net.vulkanmod.vulkan.Vulkan.getAllocator(),create,allocation,pImage,pMemory,null);
            if(result!=VK_SUCCESS)throw new IllegalStateException("Could not allocate pack volume: "+result);
            long handle=pImage.get(0),memory=pMemory.get(0);
            var viewInfo=org.lwjgl.vulkan.VkImageViewCreateInfo.calloc(stack).sType$Default().image(handle).viewType(VK_IMAGE_VIEW_TYPE_3D).format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            var pView=stack.mallocLong(1);
            result=vkCreateImageView(net.vulkanmod.vulkan.device.DeviceManager.vkDevice,viewInfo,null,pView);
            if(result!=VK_SUCCESS){org.lwjgl.util.vma.Vma.vmaDestroyImage(net.vulkanmod.vulkan.Vulkan.getAllocator(),handle,memory);
                throw new IllegalStateException("Could not create pack volume view: "+result);}
            var wrapper=new VulkanImage(handle,format,1,volume.width(),volume.height(),volume.texelBytes(),create.usage(),pView.get(0));
            int filter=linear?9729:9728,wrap=volume.clamp()?33071:10497;
            wrapper.updateTextureSampler(new SamplerKey(filter,filter,wrap,wrap,wrap,0,0,0,0,515));
            upload(stack,wrapper,handle,volume);
            return new OwnedVolume(handle,memory,pView.get(0),wrapper);
        }
    }

    private static void upload(org.lwjgl.system.MemoryStack stack,VulkanImage wrapper,long handle,PackTexturePlan.Volume volume) {
        var queue=net.vulkanmod.vulkan.device.DeviceManager.getGraphicsQueue();
        var commandBuffer=queue.getCommandBuffer();
        var command=commandBuffer.getHandle();
        wrapper.transitionImageLayout(stack,command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        var staging=net.vulkanmod.vulkan.Vulkan.getStagingBuffer();
        staging.align(volume.texelBytes());
        var bytes=MemoryUtil.memAlloc(volume.data().length);
        try {bytes.put(volume.data()).flip();staging.copyBuffer(volume.data().length,bytes);}
        finally {MemoryUtil.memFree(bytes);}
        var region=org.lwjgl.vulkan.VkBufferImageCopy.calloc(1,stack).bufferOffset(staging.getOffset()).bufferRowLength(0).bufferImageHeight(0);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.imageOffset().set(0,0,0);
        region.imageExtent().set(volume.width(),volume.height(),volume.depth());
        vkCmdCopyBufferToImage(command,staging.getId(),handle,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,region);
        wrapper.readOnlyLayout(stack,command);
        long fence=queue.endIfNeeded(commandBuffer);
        if(fence!=VK_NULL_HANDLE)net.vulkanmod.vulkan.Synchronization.INSTANCE.addCommandBuffer(commandBuffer);
    }

    private static boolean filterable(int format) {
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            var props=org.lwjgl.vulkan.VkFormatProperties.calloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties(net.vulkanmod.vulkan.device.DeviceManager.physicalDevice,format,props);
            return (props.optimalTilingFeatures()&org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)!=0;
        }
    }
    public VulkanImage image(String program,String sampler) {
        if(closed)throw new IllegalStateException("Closed pack textures");
        var asset=plan.resolve(program,sampler);if(asset==null)return null;
        if(asset instanceof PackTexturePlan.GameImage) {
            int id=Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId();
            var texture=GlTexture.getTexture(id);
            if(texture==null||texture.getVulkanImage()==null)throw new IllegalStateException("Real block atlas is unavailable");
            return texture.getVulkanImage();
        }
        return images.get(asset);
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();if(closed)return;closed=true;
        for(var volume:volumes) {
            images.values().remove(volume.wrapper);
            volume.wrapper.free();
            net.vulkanmod.vulkan.memory.MemoryManager.getInstance().addFrameOp(()->{
                vkDestroyImageView(net.vulkanmod.vulkan.device.DeviceManager.vkDevice,volume.view,null);
                org.lwjgl.util.vma.Vma.vmaDestroyImage(net.vulkanmod.vulkan.Vulkan.getAllocator(),volume.handle,volume.memory);
            });
        }
        volumes.clear();
        images.values().forEach(VulkanImage::free);images.clear();
    }
}
