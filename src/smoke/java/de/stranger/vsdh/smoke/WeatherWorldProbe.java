package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import dev.vitrail.pack.model.RenderStage;
import dev.vitrail.pack.target.TargetSchedule.Side;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

final class WeatherWorldProbe {
    private static boolean complete;

    static void verify(WorldShaderSession session,int ticks) {
        if(complete || ticks<980 || session.geometryDraws(RenderStage.RAIN_SNOW)==0)return;
        var image=session.chain().targets().image(9,Side.MAIN);
        if(image.format!=org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT)
            throw new AssertionError("Diagnostic weather target has unexpected format: "+image.format);
        int pixels=Math.multiplyExact(image.width,image.height);
        var bytes=MemoryUtil.memAlloc(Math.multiplyExact(pixels,8));
        try(var scope=Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTexture(image,MemoryUtil.memAddress(bytes));
            int covered=0,changed=0;
            for(int i=0;i<pixels;i++) {
                int offset=i*8;
                if(bytes.getShort(offset)!=0 || bytes.getShort(offset+2)!=0 || bytes.getShort(offset+4)!=0)changed++;
                if(NativePassGpuCheck.halfFloat(bytes.getShort(offset))==.125f
                        && NativePassGpuCheck.halfFloat(bytes.getShort(offset+2))==.5f
                        && NativePassGpuCheck.halfFloat(bytes.getShort(offset+4))==.875f
                        && NativePassGpuCheck.halfFloat(bytes.getShort(offset+6))==1)covered++;
            }
            if(covered<32)throw new AssertionError("Weather did not write textured pixels into colortex9: "+covered+" marker pixels, "+changed+" nonblack pixels");
            complete=true;
            LoggerFactory.getLogger("VSDH-Smoke").info("WEATHER WORLD GPU PASS: weather phase and texture wrote {} pixels into colortex9",covered);
        } finally { MemoryUtil.memFree(bytes); }
    }

    static void requireDraws(WorldShaderSession session) {
        long before=session.particleDraws(false),after=session.particleDraws(true),rain=session.geometryDraws(RenderStage.RAIN_SNOW);
        if(before==0 || after==0 || rain==0)
            throw new AssertionError("Missing native particle/weather draws: "+before+"/"+after+"/"+rain);
        LoggerFactory.getLogger("VSDH-Smoke").info("PARTICLE WEATHER WORLD DRAW PASS: beforeDeferred={}, afterDeferred={}, weather={}",before,after,rain);
    }

    static void requireComplete() {
        if(!complete)throw new AssertionError("No live GPU weather sample");
    }
}
