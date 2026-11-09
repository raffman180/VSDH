package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldLightingCapture;
import de.stranger.vsdh.shader.WorldShaderSession;
import dev.vitrail.pack.target.TargetSchedule.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/** Real GPU values from pack expressions and per-hand state, never a compiler-only success marker. */
final class ExpressionWorldProbe {
    private static final Set<String> completed=new HashSet<>();
    static void requireComplete() {
        if(!completed.equals(Set.of("air","water-negative","effects","rain")))
            throw new AssertionError("Incomplete expression GPU probes: "+completed);
    }
    static void verify(WorldShaderSession session,int ticks) {
        String phase=ticks>=250&&ticks<=450?"air":ticks>=580&&ticks<=640?"water-negative":ticks>=740&&ticks<=770?"effects":ticks>=985&&ticks<=1010?"rain":null;
        if(phase==null||completed.contains(phase))return;
        var mc=Minecraft.getInstance();var normals=session.chain().targets().image(1,Side.MAIN);
        var bytes=MemoryUtil.memAlloc(Math.multiplyExact(normals.width*normals.height,8));
        try(var scope=Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTexture(normals,MemoryUtil.memAddress(bytes));
            int sample=-1;
            for(int y=0;y<normals.height&&sample<0;y++)for(int x=0;x+7<normals.width;x+=8) {
                boolean all=true;
                for(int i=0;i<8;i++)if(!normal(bytes,y*normals.width+x+i)){all=false;break;}
                if(all){sample=y*normals.width+x;break;}
            }
            if(sample<0)return;
            var image=session.chain().targets().image(7,Side.MAIN);
            ImageUtil.downloadTextureRegion(image,MemoryUtil.memAddress(bytes),sample%image.width,sample/image.width,8,1,image.aspect,image.formatSize);
            int main=material(mc.player.getMainHandItem()),off=material(mc.player.getOffhandItem());
            int mainLight=main==40008?0:14,offLight=off==40008?0:14;
            check(bytes,0,main/65536f,off/65536f,Math.max(mainLight,offLight)/15f);
            float effect=mc.player.hasEffect(MobEffects.BLINDNESS)||mc.player.hasEffect(MobEffects.DARKNESS)?1:0;
            check(bytes,1,offLight/15f,((session.framesCompleted()-1)%8)/8f,effect);
            float eye=half(bytes,2,0),rain=half(bytes,2,1),wet=half(bytes,2,2);
            if(eye<0||eye>1.001f||(!phase.equals("water-negative")&&eye<.9f))throw new AssertionError("Invalid smoothed eye brightness "+eye);
            if(phase.equals("rain")) {
                float raw=mc.level.getRainLevel(0);
                if(raw<.1f||rain<=.01f||wet<=.0001f||rain>raw+.01f||wet>raw+.01f)
                    throw new AssertionError("Rain smoothing did not follow real weather: raw="+raw+", expression="+rain+", wetness="+wet);
            } else if(Math.abs(rain)>.0015f||Math.abs(wet)>.0015f)throw new AssertionError("Unexpected rain in clear scene");
            var precipitation=mc.level.getBiome(mc.player.blockPosition()).value().getPrecipitationAt(mc.player.blockPosition());
            check(bytes,3,precipitation==net.minecraft.world.level.biome.Biome.Precipitation.NONE?1:0,
                    precipitation==net.minecraft.world.level.biome.Biome.Precipitation.RAIN?1:0,
                    precipitation==net.minecraft.world.level.biome.Biome.Precipitation.SNOW?1:0);
            close(half(bytes,4,0),WorldLightingCapture.darkness(),"captured darkness light factor");
            for(int c=1;c<3;c++)if(half(bytes,4,c)<0||half(bytes,4,c)>1.001f)throw new AssertionError("Invalid smoothed lightmap channel");
            check(bytes,5,mc.level.getRainLevel(mc.getTimer().getGameTimeDeltaPartialTick(false)),.25f,.75f);
            check(bytes,6,127/255f,mc.level.getRainLevel(0)>.1f?1:0,.25f);
            completed.add(phase);
            LoggerFactory.getLogger("VSDH-Smoke").info("EXPRESSION WORLD GPU PASS: phase={}, materials={}/{}, light={}/{}, eye={}, rainFactor={}, wetness={}, capturedDarkness={}",
                    phase,main,off,Math.max(mainLight,offLight),offLight,eye,rain,wet,WorldLightingCapture.darkness());
        } finally { MemoryUtil.memFree(bytes); }
    }
    private static int material(ItemStack stack) {
        if(stack.is(Items.AIR))return 40008;if(stack.is(Items.TORCH))return 44002;if(stack.is(Items.END_ROD))return 44003;
        throw new AssertionError("Unexpected held item in isolated QA world: "+stack);
    }
    private static boolean normal(ByteBuffer bytes,int pixel) {
        float x=NativePassGpuCheck.halfFloat(bytes.getShort(pixel*8))*2-1,y=NativePassGpuCheck.halfFloat(bytes.getShort(pixel*8+2))*2-1,z=NativePassGpuCheck.halfFloat(bytes.getShort(pixel*8+4))*2-1;
        return NativePassGpuCheck.halfFloat(bytes.getShort(pixel*8+6))>.5f&&Math.abs(x*x+y*y+z*z-1)<.03f;
    }
    private static float half(ByteBuffer bytes,int slot,int channel) {return NativePassGpuCheck.halfFloat(bytes.getShort(slot*8+channel*2));}
    private static void close(float actual,float expected,String value) {
        if(!Float.isFinite(actual)||Math.abs(actual-expected)>.0015f)throw new AssertionError(value+": expected "+expected+", actual "+actual);
    }
    private static void check(ByteBuffer bytes,int slot,float x,float y,float z) {
        close(half(bytes,slot,0),x,"slot "+slot+".x");close(half(bytes,slot,1),y,"slot "+slot+".y");close(half(bytes,slot,2),z,"slot "+slot+".z");close(half(bytes,slot,3),1,"metadata alpha");
    }
}
