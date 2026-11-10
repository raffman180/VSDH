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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldShaderLevelMixin {
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
    @Inject(method="setLevel",at=@At("HEAD"))
    private void vsdh$changeWorld(ClientLevel level,CallbackInfo ci) {
        var session=WorldShaderSession.current();if(session!=null) session.changeWorld(level);
    }
}
