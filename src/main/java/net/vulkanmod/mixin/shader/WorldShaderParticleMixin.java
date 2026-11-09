package net.vulkanmod.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.Camera;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Predicate;

@Mixin(LevelRenderer.class)
public abstract class WorldShaderParticleMixin {
    private static final String RENDER_PARTICLES =
            "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V";

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_PARTICLES, ordinal = 1))
    private void vsdh$beforeDeferred(ParticleEngine engine, LightTexture light, Camera camera, float partialTick,
                                     Frustum frustum, Predicate<ParticleRenderType> filter, Operation<Void> original) {
        var session = WorldShaderSession.current();
        if (session == null) original.call(engine, light, camera, partialTick, frustum, filter);
        else session.drawParticles(true, filter, selected -> original.call(engine, light, camera, partialTick, frustum, selected));
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_PARTICLES, ordinal = 2))
    private void vsdh$afterDeferred(ParticleEngine engine, LightTexture light, Camera camera, float partialTick,
                                    Frustum frustum, Predicate<ParticleRenderType> filter, Operation<Void> original) {
        var session = WorldShaderSession.current();
        if (session == null) original.call(engine, light, camera, partialTick, frustum, filter);
        else session.drawParticles(false, filter, selected -> original.call(engine, light, camera, partialTick, frustum, selected));
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V"),
            require = 2, allow = 2)
    private void vsdh$weather(LevelRenderer renderer, LightTexture light, float partialTick,
                               double x, double y, double z, Operation<Void> original) {
        var session = WorldShaderSession.current();
        if (session == null) original.call(renderer, light, partialTick, x, y, z);
        else session.drawWeather(() -> original.call(renderer, light, partialTick, x, y, z));
    }

    @ModifyArg(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;depthMask(Z)V"))
    private boolean vsdh$rainDepth(boolean original) {
        var session = WorldShaderSession.current();
        return session == null ? original : session.weatherWritesDepth(original);
    }

    @WrapOperation(method = "tickRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 1))
    private Object vsdh$rainSplashes(OptionInstance<ParticleStatus> option, Operation<Object> original) {
        var session = WorldShaderSession.current();
        return session != null && !session.rainSplashes() ? ParticleStatus.MINIMAL : original.call(option);
    }
}
