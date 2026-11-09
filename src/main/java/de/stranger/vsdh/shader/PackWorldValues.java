package de.stranger.vsdh.shader;

import dev.vitrail.uniform.Smoothed;
import dev.vitrail.uniform.Val;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.nio.ByteBuffer;
import java.util.Set;

/** Real held materials, biome inputs and persistent vanilla smoothing for this world session. */
public final class PackWorldValues {
    public static final Set<String> NAMES=Set.of("heldItemId","heldItemId2","heldBlockLightValue","heldBlockLightValue2",
            "darknessLightFactor","wetness","eyeBrightnessSmooth","biome","biome_precipitation");
    private final WorldPackPlan plan;
    private final Smoothed wet=new Smoothed(),eyeBlock=new Smoothed(),eyeSky=new Smoothed();
    private final Val scratch=new Val();
    private final int[] oneInt=new int[1],twoInt=new int[2];
    private final float[] one=new float[1];
    private int mainId,offId,mainLight,offLight,biome,precipitation;
    private float wetness,blockLight,skyLight,darkness;

    public PackWorldValues(WorldPackPlan plan) { this.plan=plan; }
    public void reset() { wet.reset();eyeBlock.reset();eyeSky.reset(); }
    public void capture(Camera camera,BuiltinWorldValues builtins,float rain,float dt) {
        var mc=Minecraft.getInstance(); var player=mc.player;
        var main=player.getMainHandItem(); var off=player.getOffhandItem();
        mainId=material(main);offId=material(off);mainLight=emission(main);offLight=emission(off);
        if(plan.oldHandLight())mainLight=Math.max(mainLight,offLight);
        var holder=mc.level.getBiome(player.blockPosition());
        var key=holder.unwrapKey().orElseThrow(()->new IllegalStateException("Unregistered biome in shader frame")).location();
        biome=plan.expressions().biomeId(key.getNamespace(),key.getPath());
        precipitation=switch(holder.value().getPrecipitationAt(player.blockPosition())) {case NONE->0;case RAIN->1;case SNOW->2;};
        wetness=wet.updateAndGet(rain,plan.directives().wetnessHalflife(),plan.directives().drynessHalflife(),dt);
        builtins.read("eyeBrightness",scratch);
        float eyeHalf=plan.directives().eyeBrightnessHalflife();
        blockLight=eyeBlock.updateAndGet(scratch.i(0),eyeHalf,eyeHalf,dt);
        skyLight=eyeSky.updateAndGet(scratch.i(1),eyeHalf,eyeHalf,dt);
        darkness=WorldLightingCapture.darkness();
    }
    private int material(ItemStack stack) { return plan.items().getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),-1); }
    public static int emission(ItemStack stack) {
        if(!(stack.getItem() instanceof BlockItem item))return 0;
        var state=item.getBlock().defaultBlockState();
        var properties=stack.get(DataComponents.BLOCK_STATE); if(properties!=null)state=properties.apply(state);
        var mc=Minecraft.getInstance();return state.getLightEmission(mc.level,mc.player.blockPosition());
    }
    public boolean read(String name,Val out) {
        switch(name) {
            case "heldItemId"->out.set(mainId);case "heldItemId2"->out.set(offId);
            case "heldBlockLightValue"->out.set(mainLight);case "heldBlockLightValue2"->out.set(offLight);
            case "darknessLightFactor"->out.set(darkness);case "wetness"->out.set(wetness);
            case "eyeBrightnessSmooth"->out.set((int)blockLight,(int)skyLight);
            case "biome"->out.set(biome);case "biome_precipitation"->out.set(precipitation);
            default->{return false;}
        }
        return true;
    }
    public boolean write(Std140Layout.Member member,Std140Layout layout,ByteBuffer destination) {
        if(!NAMES.contains(member.name()))return false;
        read(member.name(),scratch);
        if(member.name().equals("eyeBrightnessSmooth")) {
            twoInt[0]=scratch.i(0);twoInt[1]=scratch.i(1);layout.putInts(destination,member.name(),0,twoInt);
        } else if(member.type().equals("int")) {
            oneInt[0]=scratch.i(0);layout.putInts(destination,member.name(),0,oneInt);
        } else {one[0]=scratch.f(0);layout.putFloats(destination,member.name(),0,one);}
        return true;
    }
}
