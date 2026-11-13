package net.vulkanmod.mixin.shader;

import com.mojang.blaze3d.vertex.PoseStack;
import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The under-water screen texture, off under an active pack unless it sets {@code underwaterOverlay=true} (Iris rule). */
@Mixin(ScreenEffectRenderer.class)
public class WorldShaderWaterOverlayMixin {
    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void vsdh$packWaterOverlay(Minecraft minecraft, PoseStack poseStack, CallbackInfo ci) {
        var session = WorldShaderSession.current();
        if (session != null && session.packPicture() && !session.drawsUnderwaterOverlay()) ci.cancel();
    }
}
