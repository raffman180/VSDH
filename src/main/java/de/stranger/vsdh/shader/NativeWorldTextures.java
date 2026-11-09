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
        try {
            for(var asset:plan.bindings().values()) {
                if(images.containsKey(asset))continue;
                if(asset instanceof PackTexturePlan.Pixels pixels) {
                    allocated=Math.addExact(allocated,pixels.rgba().length);
                    if(allocated>256L*1024*1024)throw new IllegalArgumentException("Decoded native pack textures exceed 256 MiB");
                    var image=VulkanImage.builder(pixels.width(),pixels.height()).setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                            .setUsage(VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                            .setLinearFiltering(pixels.blur()).setClamp(pixels.clamp()).createVulkanImage();
                    images.put(asset,image);
                    int filter=pixels.blur()?9729:9728,wrap=pixels.clamp()?33071:10497;
                    image.updateTextureSampler(new SamplerKey(filter,filter,wrap,wrap,wrap,0,0,0,0,515));
                    var bytes=MemoryUtil.memAlloc(pixels.rgba().length);
                    try {bytes.put(pixels.rgba()).flip();image.uploadSubTextureAsync(0,pixels.width(),pixels.height(),0,0,0,0,0,bytes);}
                    finally {MemoryUtil.memFree(bytes);}
                } else if(asset instanceof PackTexturePlan.GameImage resource) {
                    if(!resource.location().equals(InventoryMenu.BLOCK_ATLAS.toString()))
                        throw new IllegalArgumentException("Native resource texture needs asynchronous preparation: "+resource.location());
                    // The live atlas is fetched at the draw, so resource reload cannot leave a borrowed stale image.
                }
            }
        } catch(Throwable failure) {close();throw failure;}
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
        images.values().forEach(VulkanImage::free);images.clear();
    }
}
