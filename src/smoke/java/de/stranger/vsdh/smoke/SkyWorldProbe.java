package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import dev.vitrail.pack.model.RenderStage;
import dev.vitrail.pack.target.TargetSchedule.Side;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

final class SkyWorldProbe {
    private static boolean complete;

    static void verify(WorldShaderSession session,int ticks) {
        if(complete || ticks<365 || ticks>=400)return;
        var mc=Minecraft.getInstance();
        var camera=mc.gameRenderer.getMainCamera();
        if(camera.getXRot()>-80 || session.geometryDraws(RenderStage.SKY)==0)return;
        float partial=mc.getTimer().getGameTimeDeltaPartialTick(false);
        float expectedRed=(float)mc.level.getSkyColor(camera.getPosition(),partial).x;
        var image=session.chain().targets().image(0,Side.MAIN);
        try(var scope=Renderer.getInstance().temporaryRenderTarget();var stack=MemoryStack.stackPush()) {
            var pixel=stack.malloc(8);
            ImageUtil.downloadTextureRegion(image,MemoryUtil.memAddress(pixel),image.width/2,image.height/2,1,1,image.aspect,8);
            float red=NativePassGpuCheck.halfFloat(pixel.getShort(0));
            float green=NativePassGpuCheck.halfFloat(pixel.getShort(2));
            float blue=NativePassGpuCheck.halfFloat(pixel.getShort(4));
            float alpha=NativePassGpuCheck.halfFloat(pixel.getShort(6));
            if(Math.abs(red-expectedRed)>.01f || green!=.25f || blue!=.75f || alpha!=1)
                throw new AssertionError("Sky geometry did not reach the frame with actual draw colour: "
                        +red+"/"+green+"/"+blue+"/"+alpha+" expected red="+expectedRed);
            complete=true;
            LoggerFactory.getLogger("VSDH-Smoke").info("SKY WORLD GPU PASS: actual sky disk phase, pixel RGB={}/{}/{}",red,green,blue);
        }
    }

    static void requireComplete() {
        if(!complete)throw new AssertionError("No GPU sky geometry sample");
    }
}
