package net.vulkanmod.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import de.stranger.vsdh.shader.ForwardDepthProjection;
import de.stranger.vsdh.shader.NativeHandRenderer;
import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class WorldShaderGameMixin implements NativeHandRenderer.GameAccess {
    @Shadow private boolean renderHand;

    @Override
    public boolean vsdh$renderHandEnabled() { return renderHand; }

    @Override
    @Invoker("renderItemInHand")
    public abstract void vsdh$renderItemInHand(Camera camera, float partialTick, Matrix4f view);

    /** See {@link de.stranger.vsdh.shader.CameraBobbing}: the bob of this frame, kept out of the projection. */
    @org.spongepowered.asm.mixin.Unique private final Matrix4f vsdh$bobbing = new Matrix4f();
    @org.spongepowered.asm.mixin.Unique private final Matrix4f vsdh$bobbedView = new Matrix4f();
    @org.spongepowered.asm.mixin.Unique private boolean vsdh$bobbingMoved;

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", ordinal = 0, remap = false))
    private Matrix4f vsdh$keepBobbingOutOfProjection(Matrix4f projection, org.joml.Matrix4fc bobbing, Operation<Matrix4f> multiply) {
        vsdh$bobbingMoved = de.stranger.vsdh.shader.CameraBobbing.ENABLED && WorldShaderSession.current() != null;
        if (!vsdh$bobbingMoved) return multiply.call(projection, bobbing);
        vsdh$bobbing.set(bobbing);
        return projection;
    }

    @ModifyArg(method = "renderLevel", index = 5, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private Matrix4f vsdh$bobbingIntoModelView(Matrix4f view) {
        if (!vsdh$bobbingMoved) return view;
        vsdh$bobbingMoved = false;
        // A matrix of its own: the caller's view goes on to the hand unchanged, which applies its own bob.
        return de.stranger.vsdh.shader.CameraBobbing.intoView(vsdh$bobbing, view, vsdh$bobbedView);
    }

    private static NativeHandRenderer vsdh$hand() {
        var session = WorldShaderSession.current();
        return session == null ? null : session.handRenderer();
    }

    @ModifyArg(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V"))
    private Matrix4f vsdh$handProjection(Matrix4f projection) {
        var hand = vsdh$hand();
        return hand != null && hand.drawing() ? ForwardDepthProjection.hand(projection) : projection;
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void vsdh$drawHandGeometry(ItemInHandRenderer renderer, float partial, PoseStack pose,
                                       MultiBufferSource.BufferSource buffers, LocalPlayer player, int light, Operation<Void> draw) {
        var hand = vsdh$hand();
        if (hand != null && hand.drawing()) {
            draw.call(renderer, partial, pose, hand.useBuffers(), player, light);
        } else if (hand == null || !hand.replaced()) {
            draw.call(renderer, partial, pose, buffers, player, light);
        }
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderScreenEffect(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void vsdh$screenEffects(Minecraft minecraft, PoseStack pose, Operation<Void> draw) {
        var hand = vsdh$hand();
        if (hand == null || !hand.drawing()) draw.call(minecraft, pose);
    }

    @Inject(method="renderLevel",at=@At(value="INVOKE",
            target="Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"),require=1,allow=1)
    private void vsdh$finishBeforeHandDepthClear(CallbackInfo ci) {
        var session=WorldShaderSession.current();
        if(session!=null) session.finishWorld();
    }

    @Inject(method="renderLevel",at=@At("RETURN"))
    private void vsdh$finishWorld(CallbackInfo ci) {
        var session=WorldShaderSession.current();if(session!=null) session.finishWorld();
    }
}
