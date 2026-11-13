package net.vulkanmod.mixin.shader;

import de.stranger.vsdh.shader.WorldShaderSession;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class WorldShaderLevelMixin implements de.stranger.vsdh.shader.NativeShadowCasters.LevelAccess,
        de.stranger.vsdh.shader.IrisStandardValues.LevelTicks {
    @Override
    @org.spongepowered.asm.mixin.gen.Accessor("ticks")
    public abstract int vsdh$ticks();

    @Override
    @Invoker("renderEntity")
    public abstract void vsdh$renderShadowEntity(Entity entity, double x, double y, double z, float partial,
                                                  PoseStack pose, MultiBufferSource buffers);

    @WrapMethod(method = "renderEntity")
    private void vsdh$captureEntityMaterial(Entity entity, double x, double y, double z, float delta,
                                            PoseStack pose, MultiBufferSource buffers, Operation<Void> draw) {
        var session = WorldShaderSession.current();
        if (session == null) {
            draw.call(entity, x, y, z, delta, pose, buffers);
            return;
        }
        int previous = session.beginEntity(entity);
        try {
            draw.call(entity, x, y, z, delta, pose, buffers);
        } finally {
            session.endEntity(previous);
        }
    }
    @ModifyExpressionValue(method={"renderLevel","renderClouds"},at=@At(value="INVOKE",target="Lnet/minecraft/client/Options;getCloudsType()Lnet/minecraft/client/CloudStatus;"))
    private CloudStatus vsdh$packClouds(CloudStatus original) {
        var session=WorldShaderSession.current();return session==null?original:session.clouds(original);
    }
    @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",shift=At.Shift.BEFORE))
    private void vsdh$beginWorld(DeltaTracker delta, boolean renderBlockOutline, Camera camera, GameRenderer renderer,
                                 LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        var session=WorldShaderSession.current();
        if(session!=null) session.beginWorld(camera,view,projection,delta.getGameTimeDeltaPartialTick(false),delta.getGameTimeDeltaPartialTick(true));
    }
    /**
     * renderLevel clears the bound target with the fog colour right after beginWorld bound the pack's
     * scene target, which overwrote the pack's own colortex0ClearColor (Mellow's sky came out in fog
     * colour). Iris replaces this clear with the pack's clear passes; here the colour part is dropped
     * while a pack frame is active and the depth part still clears the world depth.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="renderLevel",
            at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void vsdh$worldClear(int mask,boolean onOsx,Operation<Void> original) {
        var session=WorldShaderSession.current();
        original.call(session!=null&&session.active()?mask&~0x4000:mask,onOsx); // GL_COLOR_BUFFER_BIT
    }
    @Inject(method="setLevel",at=@At("HEAD"))
    private void vsdh$changeWorld(ClientLevel level,CallbackInfo ci) {
        var session=WorldShaderSession.current();if(session!=null) session.changeWorld(level);
    }
}
