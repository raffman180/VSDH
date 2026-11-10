package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.gl.GlTexture;
import dev.vitrail.uniform.Val;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.Set;

/** Minecraft-1.21.1 providers. Snapshot values once; fog/atlas follow the actual terrain draw state. */
public final class BuiltinWorldValues {
    public static final Set<String> NAMES=Set.of("atlasSize","blindness","darknessFactor","nightVision","isEyeInWater",
            "eyeBrightness","eyePosition","relativeEyePosition","playerLookVector","cameraPositionInt","cameraPositionFract",
            "previousCameraPositionInt","previousCameraPositionFract","cloudHeight","moonPhase","sunAngle","screenBrightness","of_Fog",
            "bedrockLevel", "heightLimit", "sunPosition", "upPosition", "isRightHanded", "is_hurt", "thunderStrength", "currentPlayerHealth", "maxPlayerHealth",
            "lightningBoltPosition");
    private final int[] cameraInt=new int[3],previousInt=new int[3],eyeLight=new int[2],pair=new int[2],oneInt=new int[1];
    private final float[] cameraFract=new float[3],previousFract=new float[3],eye=new float[3],relativeEye=new float[3],look=new float[3],one=new float[1];
    private float blindness,darkness,nightVision,cloudHeight,sunAngle,gamma,waterDensity;
    private int moonPhase,eyeMedium,bedrockLevel,heightLimit;
    private boolean rightHanded, hurt;
    private float health, maxHealth, thunder;
    private ClientLevel level, lightningLevel;
    private Vec3 cameraPosition;
    private long lightningTick = Long.MIN_VALUE;
    private LightningBolt lightning;
    private float tickDelta;
    private final float[] lightningPosition = new float[4];
    private boolean lightningCaptured;
    private final Matrix4f celestialView = new Matrix4f();
    private final Vector3f sun = new Vector3f();
    private final float[] sunPosition = new float[3], upPosition = new float[3];

    public void capture(Camera camera,WorldMatrices matrices,float partialTick,float sunPathRotation) {
        var mc=Minecraft.getInstance();var entity=camera.getEntity();var position=camera.getPosition();
        level=mc.level;
        cameraPosition=position;
        tickDelta=partialTick;
        lightningCaptured=false;
        thunder=Math.clamp(level.getThunderLevel(partialTick),0f,1f);
        var localPlayer=mc.player;
        boolean survival=localPlayer!=null && mc.gameMode!=null && mc.gameMode.getPlayerMode().isSurvival();
        maxHealth=survival?localPlayer.getMaxHealth():-1;
        health=maxHealth>0?localPlayer.getHealth()/maxHealth:-1;
        hurt=localPlayer!=null && localPlayer.hurtTime>0;
        CameraCoordinates.split(position.x,position.y,position.z,cameraInt,cameraFract);
        CameraCoordinates.split(matrices.previousX(),matrices.previousY(),matrices.previousZ(),previousInt,previousFract);
        var eyes=entity.getEyePosition(partialTick);eye[0]=(float)eyes.x;eye[1]=(float)eyes.y;eye[2]=(float)eyes.z;
        relativeEye[0]=(float)(position.x-eyes.x);relativeEye[1]=(float)(position.y-eyes.y);relativeEye[2]=(float)(position.z-eyes.z);
        var direction=entity.getViewVector(partialTick);look[0]=(float)direction.x;look[1]=(float)direction.y;look[2]=(float)direction.z;
        var eyeBlock=BlockPos.containing(entity.getX(),entity.getEyeY(),entity.getZ());
        eyeLight[0]=level.getBrightness(LightLayer.BLOCK,eyeBlock)*16;
        eyeLight[1]=level.getBrightness(LightLayer.SKY,eyeBlock)*16;
        var medium=camera.getFluidInCamera();
        eyeMedium=medium==FogType.WATER?1:medium==FogType.LAVA?2:medium==FogType.POWDER_SNOW?3:0;
        blindness=0;darkness=0;nightVision=0;
        if(entity instanceof LivingEntity living) {
            var blind=living.getEffect(MobEffects.BLINDNESS);
            if(blind!=null)blindness=blind.isInfiniteDuration()?1:Math.clamp(blind.getDuration()/20f,0,1);
            var dark=living.getEffect(MobEffects.DARKNESS);
            if(dark!=null)darkness=dark.getBlendFactor(living,partialTick);
            if(living.hasEffect(MobEffects.NIGHT_VISION))nightVision=Math.clamp(GameRenderer.getNightVisionScale(living,partialTick),0,1);
        }
        if(nightVision==0 && mc.player!=null && mc.player.hasEffect(MobEffects.CONDUIT_POWER))
            nightVision=Math.clamp(mc.player.getWaterVision(),0,1);
        moonPhase=level.getMoonPhase();
        float skyAngle=level.getTimeOfDay(partialTick);sunAngle=skyAngle<.75f?skyAngle+.25f:skyAngle-.75f;
        celestialView.set(matrices.view)
                .rotateY((float) (-Math.PI / 2))
                .rotateZ((float) Math.toRadians(sunPathRotation))
                .rotateX((float) (skyAngle * 2 * Math.PI))
                .transformDirection(sun.set(0, 100, 0));
        sunPosition[0] = sun.x;
        sunPosition[1] = sun.y;
        sunPosition[2] = sun.z;
        // The fixed sky Y rotation leaves the up axis unchanged.
        matrices.view.transformDirection(sun.set(0, 100, 0));
        upPosition[0] = sun.x;
        upPosition[1] = sun.y;
        upPosition[2] = sun.z;
        bedrockLevel = level.dimensionType().minY();
        heightLimit = level.dimensionType().height();
        rightHanded = mc.options.mainHand().get() == HumanoidArm.RIGHT;
        cloudHeight=level.effects().getCloudHeight();gamma=mc.options.gamma().get().floatValue();
        waterDensity=0;
        if(medium==FogType.WATER) {
            waterDensity=.05f;
            if(entity instanceof LocalPlayer player) {
                float vision=player.getWaterVision();waterDensity-=vision*vision*.03f;
                if(level.getBiome(player.blockPosition()).is(BiomeTags.HAS_CLOSER_WATER_FOG))waterDensity+=.005f;
            }
        }
    }

    private float[] lightningPosition() {
        if (lightningCaptured) return lightningPosition;
        lightningCaptured = true;
        long tick = level.getGameTime();
        if (lightningLevel != level || lightningTick != tick) {
            lightningLevel = level;
            lightningTick = tick;
            lightning = null;
            for (var entity : level.entitiesForRendering()) {
                if (entity instanceof LightningBolt bolt) {
                    lightning = bolt;
                    break;
                }
            }
        }
        if (lightning == null) {
            java.util.Arrays.fill(lightningPosition, 0f);
        } else {
            var position = lightning.getPosition(tickDelta);
            lightningPosition[0] = (float) (position.x - cameraPosition.x);
            lightningPosition[1] = (float) (position.y - cameraPosition.y);
            lightningPosition[2] = (float) (position.z - cameraPosition.z);
            lightningPosition[3] = 1f;
        }
        return lightningPosition;
    }

    public boolean read(String name,Val out) {
        switch(name) {
            case "cameraPositionInt"->out.set(cameraInt[0],cameraInt[1],cameraInt[2]);
            case "previousCameraPositionInt"->out.set(previousInt[0],previousInt[1],previousInt[2]);
            case "cameraPositionFract"->out.set(cameraFract[0],cameraFract[1],cameraFract[2]);
            case "previousCameraPositionFract"->out.set(previousFract[0],previousFract[1],previousFract[2]);
            case "eyePosition"->out.set(eye[0],eye[1],eye[2]);
            case "relativeEyePosition"->out.set(relativeEye[0],relativeEye[1],relativeEye[2]);
            case "playerLookVector"->out.set(look[0],look[1],look[2]);
            case "eyeBrightness"->out.set(eyeLight[0],eyeLight[1]);
            case "isEyeInWater"->out.set(eyeMedium);case "moonPhase"->out.set(moonPhase);
            case "blindness"->out.set(blindness);case "darknessFactor"->out.set(darkness);
            case "nightVision"->out.set(nightVision);case "cloudHeight"->out.set(cloudHeight);
            case "sunAngle"->out.set(sunAngle);case "screenBrightness"->out.set(gamma);
            case "bedrockLevel" -> out.set(bedrockLevel);
            case "heightLimit" -> out.set(heightLimit);
            case "isRightHanded" -> out.set(rightHanded);
            case "sunPosition" -> out.set(sunPosition[0], sunPosition[1], sunPosition[2]);
            case "upPosition" -> out.set(upPosition[0], upPosition[1], upPosition[2]);
            case "currentPlayerHealth" -> out.set(health);
            case "maxPlayerHealth" -> out.set(maxHealth);
            case "thunderStrength" -> out.set(thunder);
            case "is_hurt" -> out.set(hurt);
            case "lightningBoltPosition" -> {
                var position = lightningPosition();
                out.set(position[0], position[1], position[2], position[3]);
            }
            default->{return false;}
        }
        return true;
    }

    public boolean write(Std140Layout.Member member,Std140Layout layout,ByteBuffer destination) {
        String name=member.name();if(!NAMES.contains(name))return false;
        switch(name) {
            case "cameraPositionInt"->layout.putInts(destination,name,0,cameraInt);
            case "previousCameraPositionInt"->layout.putInts(destination,name,0,previousInt);
            case "cameraPositionFract"->layout.putFloats(destination,name,0,cameraFract);
            case "previousCameraPositionFract"->layout.putFloats(destination,name,0,previousFract);
            case "eyePosition"->layout.putFloats(destination,name,0,eye);
            case "relativeEyePosition"->layout.putFloats(destination,name,0,relativeEye);
            case "playerLookVector"->layout.putFloats(destination,name,0,look);
            case "eyeBrightness"->layout.putInts(destination,name,0,eyeLight);
            case "sunPosition" -> layout.putFloats(destination, name, 0, sunPosition);
            case "upPosition" -> layout.putFloats(destination, name, 0, upPosition);
            case "lightningBoltPosition" -> layout.putFloats(destination, name, 0, lightningPosition());
            case "isRightHanded", "is_hurt" -> {
                oneInt[0] = (name.equals("isRightHanded") ? rightHanded : hurt) ? 1 : 0;
                layout.putInts(destination, name, 0, oneInt);
            }
            case "bedrockLevel", "heightLimit" -> {
                oneInt[0] = name.equals("bedrockLevel") ? bedrockLevel : heightLimit;
                layout.putInts(destination, name, 0, oneInt);
            }
            case "atlasSize"->{
                var texture=GlTexture.getTexture(RenderSystem.getShaderTexture(0));
                if(texture==null || texture.getVulkanImage()==null)throw new IllegalStateException("Atlas size requires the real bound terrain texture");
                var image=texture.getVulkanImage();pair[0]=image.width;pair[1]=image.height;layout.putInts(destination,name,0,pair);
            }
            case "isEyeInWater","moonPhase"->{oneInt[0]=name.equals("moonPhase")?moonPhase:eyeMedium;layout.putInts(destination,name,0,oneInt);}
            case "of_Fog"->{
                if(!member.type().equals("OfFog") || member.arrayLength()!=0)throw new IllegalArgumentException("Unexpected legacy fog layout");
                int at=member.offset();var color=RenderSystem.getShaderFogColor();
                for(int i=0;i<4;i++)destination.putFloat(at+i*4,color[i]);
                float start=RenderSystem.getShaderFogStart(),end=RenderSystem.getShaderFogEnd();
                destination.putFloat(at+16,waterDensity);destination.putFloat(at+20,start);destination.putFloat(at+24,end);
                destination.putFloat(at+28,end==start?0:1/(end-start));
            }
            default->{
                one[0]=switch(name){case "blindness"->blindness;case "darknessFactor"->darkness;case "nightVision"->nightVision;
                    case "cloudHeight"->cloudHeight;case "sunAngle"->sunAngle;case "screenBrightness"->gamma;
                    case "currentPlayerHealth" -> health; case "maxPlayerHealth" -> maxHealth; case "thunderStrength" -> thunder; default->throw new AssertionError(name);};
                layout.putFloats(destination,name,0,one);
            }
        }
        return true;
    }
}
