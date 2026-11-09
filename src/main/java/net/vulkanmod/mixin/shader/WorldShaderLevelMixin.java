package net.vulkanmod.mixin.shader;

import de.stranger.vsdh.shader.WorldShaderSession;
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
    @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",shift=At.Shift.BEFORE))
    private void vsdh$beginWorld(DeltaTracker delta, boolean renderBlockOutline, Camera camera, GameRenderer renderer,
                                 LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        var session=WorldShaderSession.current();
        if(session!=null) session.beginWorld(camera,view,projection,delta.getGameTimeDeltaPartialTick(false));
    }
    @Inject(method="setLevel",at=@At("HEAD"))
    private void vsdh$detachWorld(ClientLevel level,CallbackInfo ci) {
        var session=WorldShaderSession.current();if(level==null && session!=null) session.detachWorld();
    }
}
