package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;
import java.util.HashSet;
import java.util.Set;

/** Checks live pack execution and output. Does not certify all pack features or DH shader support. */
final class RealPackWorldProbe {
    private static final Set<Integer> completed=new HashSet<>();
    static void requireComplete(){if(!completed.equals(Set.of(100,50)))throw new AssertionError("Incomplete real-pack world checks: "+completed);}
    static void verify(WorldShaderSession session,int ticks) {
        int scale=net.vulkanmod.Initializer.CONFIG.renderScale;
        if(session==null||session.chain()==null||ticks<800||completed.contains(scale))return;
        if(session.framesCompleted()<100||session.terrainCalls()==0||session.shadows()==null||session.shadows().drawCommands()==0)
            throw new AssertionError("Real shaderpack did not draw world and shadows");
        var output=session.presentationImage();
        var bytes=MemoryUtil.memAlloc(output.width*output.height*output.formatSize);
        try(var scope=Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTexture(output,MemoryUtil.memAddress(bytes));
            var colors=new HashSet<Integer>();int nonblack=0;
            for(int i=0;i<output.width*output.height;i++) {
                int pixel=bytes.getInt(i*4);colors.add(pixel);
                int luminance=Byte.toUnsignedInt(bytes.get(i*4))+Byte.toUnsignedInt(bytes.get(i*4+1))+Byte.toUnsignedInt(bytes.get(i*4+2));
                if(luminance>12)nonblack++;
            }
            if(colors.size()<64||nonblack<128)throw new AssertionError("Real-pack output lacks world image content: colors="+colors.size()+", nonblack="+nonblack);
            completed.add(scale);
            if (Boolean.getBoolean("vsdh.smoke.distantPack")) DistantWorldProbe.verify(session);
            RealPackCloudProbe.run(session);
            var deferred=session.chain().program("deferred1");
            var data=deferred.uniforms();var layout=deferred.layout();
            var p=new float[16];var v=new float[16];
            for(int i=0;i<16;i++) {
                p[i]=data.getFloat(layout.member("gbufferProjectionInverse").offset()+i*4);
                v[i]=data.getFloat(layout.member("gbufferModelViewInverse").offset()+i*4);
            }
            var ray=new org.joml.Vector4f(0,.6f,0,1).mul(new org.joml.Matrix4f().set(p));ray.div(ray.w);ray.mul(new org.joml.Matrix4f().set(v));
            LoggerFactory.getLogger("VSDH-Smoke").info("REAL PACK COORDINATES: cloudHeight={}, cameraY={}, bottomWorldRay={}, inverseProjectionY={}, inverseViewY={}",
                    data.getFloat(layout.member("cloudHeight").offset()),data.getFloat(layout.member("cameraPosition").offset()+4),ray,p[5],v[5]);
            LoggerFactory.getLogger("VSDH-Smoke").info("COMPLEMENTARY WORLD EXECUTION PASS: frames={}, terrainCalls={}, shadowDraws={}, distinctColors={}, nonblackPixels={}, size={}x{}, scale={}; dedicated sky/entity/hand/DH programs remain incomplete",
                    session.framesCompleted(),session.terrainCalls(),session.shadows().drawCommands(),colors.size(),nonblack,output.width,output.height,scale);
            var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,"vsdh-complementary-world-scale-"+scale+".png",mc.getMainRenderTarget(),message->LoggerFactory.getLogger("VSDH-Smoke").info("Real-pack screenshot: {}",message.getString()));
        } finally {MemoryUtil.memFree(bytes);}
    }
}
