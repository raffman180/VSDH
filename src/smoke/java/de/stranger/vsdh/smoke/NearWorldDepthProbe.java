package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

/** Read Minecraft's live depth at the Composite/Final boundary. */
final class NearWorldDepthProbe {
    static void verify(WorldShaderSession session) {
        var depth=session.getDepthAttachment();
        int samples=Math.multiplyExact(depth.width,depth.height);
        var bytes=MemoryUtil.memAlloc(Math.multiplyExact(samples,4));
        try(var scope=Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTextureRegion(depth,MemoryUtil.memAddress(bytes),0,0,depth.width,depth.height,VK_IMAGE_ASPECT_DEPTH_BIT,4);
            int populated=0;
            for(int i=0;i<samples;i++) {
                float value=switch(depth.format) {
                    case VK_FORMAT_D32_SFLOAT,VK_FORMAT_D32_SFLOAT_S8_UINT -> bytes.getFloat(i*4);
                    case VK_FORMAT_D24_UNORM_S8_UINT -> (bytes.getInt(i*4)&0xffffff)/16777215f;
                    default -> throw new AssertionError("Unknown world depth format "+depth.format);
                };
                if(value>=0&&value<.99999f)populated++;
            }
            if(populated<32)throw new AssertionError("World depth lost before Composite: "+populated+"/"+samples);
            LoggerFactory.getLogger("VSDH-Smoke").info("NEAR DEPTH AT COMPOSITE PASS: populated={}/{}, size={}x{}",populated,samples,depth.width,depth.height);
        } finally { MemoryUtil.memFree(bytes); }
    }
}
