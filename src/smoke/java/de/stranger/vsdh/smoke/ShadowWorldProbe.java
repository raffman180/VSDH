package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

/** Actual light-depth outputs, including opaque-only snapshot and off-camera uploaded casters. */
final class ShadowWorldProbe {
    static void verify(WorldShaderSession session) {
        var shadows=session.shadows();
        if(shadows==null||shadows.drawCommands()==0)throw new AssertionError("No actual shadow terrain draws");
        var all=shadows.image("shadowtex0");var opaque=shadows.image("shadowtex1");
        var a=MemoryUtil.memAlloc(all.width*all.height*4);var b=MemoryUtil.memAlloc(opaque.width*opaque.height*4);
        try(var scope=Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTexture(all,MemoryUtil.memAddress(a));ImageUtil.downloadTexture(opaque,MemoryUtil.memAddress(b));
            int populated=0,transparent=0;
            for(int i=0;i<all.width*all.height;i++) {
                float total=a.getFloat(i*4),solid=b.getFloat(i*4);
                if(!Float.isFinite(total)||!Float.isFinite(solid)||total<0||solid>1||total>solid+1e-5f)
                    throw new AssertionError("Invalid ordered shadow depth at "+i+": total="+total+", opaque="+solid);
                if(total<.99999f)populated++;
                if(total<solid-1e-5f)transparent++;
            }
            int hidden=WorldRenderer.getInstance().shadowOffCameraSections();
            if(populated<32||transparent<32||hidden==0)throw new AssertionError("Missing real shadow coverage: pixels="+populated+", transparent="+transparent+", offCamera="+hidden);
            LoggerFactory.getLogger("VSDH-Smoke").info("SHADOW WORLD GPU PASS: draws={}, depthPixels={}, transparentDepthPixels={}, offCameraSections={}, size={}x{}, scale={}",
                    shadows.drawCommands(),populated,transparent,hidden,all.width,all.height,net.vulkanmod.Initializer.CONFIG.renderScale);
        } finally {MemoryUtil.memFree(a);MemoryUtil.memFree(b);}
    }
}
