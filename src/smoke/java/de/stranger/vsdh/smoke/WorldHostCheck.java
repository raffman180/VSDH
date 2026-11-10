package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.*;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.TargetSchedule.Side;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Entire live-world chain, not a colour-seeded synthetic test. */
final class WorldHostCheck {
    private static CompletableFuture<WorldPackSet> pending;
    private static WorldShaderSession session;
    private static boolean verified,scaledVerified,armed;
    private static int savedScale, ticks;
    static void prepare() {
        if(pending!=null)return;
        pending=CompletableFuture.supplyAsync(()->{
            try {
                boolean real=Boolean.getBoolean("vsdh.smoke.livePack");
                var path=real?Path.of(System.getProperty("vsdh.smoke.pack")):Path.of(WorldHostCheck.class.getResource("/shaderpacks/vsdh-world").toURI());
                try(var pack=OpenedPack.open(path,Map.of(),real?"MEDIUM":"")) {
                    boolean distant=Boolean.getBoolean("vsdh.smoke.distantPack");
                    var prepared=DimensionWorldProbe.enabled()
                            ?WorldPackSet.prepare(pack,distant)
                            :WorldPackSet.single(WorldPackPlan.prepare(pack,real?"world0":"",distant));
                    WorldUniforms.validate(prepared.overworld());
                    if(real)RealPackCloudProbe.prepare(prepared.overworld());
                    return prepared;
                }
            }catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
        });
    }
    static boolean ready(){return pending!=null&&pending.isDone()&&(!configured()||WorldShaderSession.current()!=null);}
    private static boolean configured(){return Boolean.getBoolean("vsdh.smoke.configuredPack");}
    static void install(){
        savedScale=net.vulkanmod.Initializer.CONFIG.renderScale;
        var plans=pending.join();
        session=configured()?WorldShaderSession.current():WorldShaderSession.install(plans);
        if(configured())LoggerFactory.getLogger("VSDH-Smoke").info("CONFIGURED PACK STARTUP PASS: normal startup installed the session before loading a world");
        session.frameObserver(ignored->verify());
    }
    static void scaleDown(){net.vulkanmod.Initializer.CONFIG.renderScale=50;}
    static void uninstall(){if(Boolean.getBoolean("vsdh.smoke.livePack")){DimensionWorldProbe.requireComplete();RealPackWorldProbe.requireComplete();}else{WeatherWorldProbe.requireDraws(session);WeatherWorldProbe.requireComplete();SkyWorldProbe.requireComplete();BuiltinWorldProbe.requireComplete();ExpressionWorldProbe.requireComplete();if(!verified || !scaledVerified)throw new AssertionError("World shader did not complete its live-world check");}net.vulkanmod.Initializer.CONFIG.renderScale=savedScale;session.uninstall();}
    static void verifyIfReady(int ticks) {
        WorldHostCheck.ticks=ticks;
        armed=ticks>=800;
    }
    private static void verify() {
        if(Boolean.getBoolean("vsdh.smoke.livePack")){DimensionWorldProbe.verify(session,ticks);RealPackWorldProbe.verify(session,ticks);return;}
        if (session!=null && session.chain()!=null) {
            SkyWorldProbe.verify(session,ticks);
            WeatherWorldProbe.verify(session,ticks);
            BuiltinWorldProbe.verify(session,ticks);
            ExpressionWorldProbe.verify(session,ticks);
        }
        if(session==null || !armed || scaledVerified || (verified && net.vulkanmod.Initializer.CONFIG.renderScale!=50))return;
        ShadowWorldProbe.verify(session);
        NearWorldDepthProbe.verify(session);
        WaterFaceProbe.verify(session);
        if(session.active() || session.framesCompleted()<100 || session.terrainCalls()==0)
            throw new AssertionError("World shader did not own the actual render frame");
        var image=session.chain().targets().image(1,Side.MAIN);
        var bytes=MemoryUtil.memAlloc(Math.multiplyExact(image.width*image.height,8));
        try(var scope=Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTexture(image,MemoryUtil.memAddress(bytes));
            int populated=0;
            for(int pixel=0;pixel<image.width*image.height;pixel++) {
                float x=NativePassGpuCheck.halfFloat(bytes.getShort(pixel*8))*2-1;
                float y=NativePassGpuCheck.halfFloat(bytes.getShort(pixel*8+2))*2-1;
                float z=NativePassGpuCheck.halfFloat(bytes.getShort(pixel*8+4))*2-1;
                if(NativePassGpuCheck.halfFloat(bytes.getShort(pixel*8+6))>.5f && Math.abs(x*x+y*y+z*z-1)<.03f)populated++;
            }
            if(populated<32)throw new AssertionError("No actual world normals in shader MRT: "+populated);
            var output=session.presentationImage();
            ImageUtil.downloadTextureRegion(output,MemoryUtil.memAddress(bytes),0,0,1,1,output.aspect,output.formatSize);
            // The actual presentation target is BGRA8 on this pinned Vulkan swapchain.
            int[] expected=output.format==org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
                    || output.format==org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB ? new int[]{191,128,64,255}:new int[]{64,128,191,255};
            for(int channel=0;channel<4;channel++)
                if(Math.abs(Byte.toUnsignedInt(bytes.get(channel))-expected[channel])>1)
                    throw new AssertionError("World FINAL did not reach the presentation target at channel "+channel+": "+Byte.toUnsignedInt(bytes.get(channel)));
            if(net.vulkanmod.Initializer.CONFIG.renderScale==50)scaledVerified=true;
            verified=true;
            LoggerFactory.getLogger("VSDH-Smoke").info("WORLD SHADER HOST PASS: {} completed frames, {} terrain layer calls, {} actual geometry pixels in normals MRT; native FINAL sentinel on presentation target, stages/depth and vanilla/DH scene target, size={}x{}, scale={}",session.framesCompleted(),session.terrainCalls(),populated,image.width,image.height,net.vulkanmod.Initializer.CONFIG.renderScale);
        }finally{MemoryUtil.memFree(bytes);}
    }
}
