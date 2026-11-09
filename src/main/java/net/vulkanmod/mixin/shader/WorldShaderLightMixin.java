package net.vulkanmod.mixin.shader;

import de.stranger.vsdh.shader.WorldLightingCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightTexture.class)
public class WorldShaderLightMixin {
    @Shadow @Final private Minecraft minecraft;
    @Inject(method="updateLightTexture",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getSkyDarken(F)F"))
    private void vsdh$resetDarkness(float partialTick,CallbackInfo ci) { WorldLightingCapture.darkness(0); }
    @Inject(method="calculateDarknessScale",at=@At("RETURN"))
    private void vsdh$captureDarkness(LivingEntity entity,float strength,float partialTick,CallbackInfoReturnable<Float> cir) {
        WorldLightingCapture.darkness(cir.getReturnValue()*minecraft.options.darknessEffectScale().get().floatValue());
    }
}
