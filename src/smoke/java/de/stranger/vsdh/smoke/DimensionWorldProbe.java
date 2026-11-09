package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.NativeShaderChain;
import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Confirms the live pack graph changes with the client level and can return to Overworld. */
final class DimensionWorldProbe {
    private static final Map<String,String> folders=Map.of(
            "minecraft:overworld","world0","minecraft:the_nether","world-1","minecraft:the_end","world1");
    private static final Set<String> seen=new HashSet<>();
    private static NativeShaderChain previous;
    private static String pendingDimension;
    private static long enteredAt;
    private static boolean returned;

    static boolean enabled() {
        return Boolean.getBoolean("vsdh.smoke.livePack") && Boolean.getBoolean("vsdh.smoke.dimensions");
    }

    static void verify(WorldShaderSession session,int ticks) {
        if(!enabled() || session==null || session.chain()==null || ticks<800)return;
        var mc=Minecraft.getInstance();
        String dimension=mc.level.dimension().location().toString();
        String expected=folders.get(dimension);
        if(expected==null || !session.dimensionFolder().equals(expected))
            throw new AssertionError("Wrong pack folder in "+dimension+": "+session.dimensionFolder());
        boolean newDimension=!seen.contains(dimension);
        if(!newDimension && (returned || seen.size()!=folders.size() || !dimension.equals("minecraft:overworld")))return;
        if(!dimension.equals(pendingDimension)) {
            pendingDimension=dimension;
            enteredAt=session.framesCompleted();
        }
        if(session.framesCompleted()-enteredAt<30 || !hasTerrain())return;
        if(previous==session.chain())throw new AssertionError("Reused GPU graph across dimensions");
        seen.add(dimension);
        previous=session.chain();
        if(!newDimension)returned=true;
        LoggerFactory.getLogger("VSDH-Smoke").info("DIMENSION WORLD PASS: {} using {}, returned={}",dimension,expected,returned);
        if(!dimension.equals("minecraft:overworld")) {
            Screenshot.grab(mc.gameDirectory,"vsdh-complementary-"+expected+".png",mc.getMainRenderTarget(),
                    message->LoggerFactory.getLogger("VSDH-Smoke").info("Dimension screenshot: {}",message.getString()));
        }
    }

    private static boolean hasTerrain() {
        var areas=net.vulkanmod.render.chunk.WorldRenderer.getInstance().getChunkAreaManager();
        for(int i=0;i<areas.size();i++) {
            var buffers=areas.getChunkArea(i).getDrawBuffers();
            if(buffers.getVertexBuffers().values().stream().anyMatch(buffer->buffer.getUsed()>0))return true;
        }
        return false;
    }

    static void requireComplete() {
        if(enabled() && (!seen.equals(folders.keySet()) || !returned))
            throw new AssertionError("Incomplete dimension game test: "+seen+", returned="+returned);
    }
}
