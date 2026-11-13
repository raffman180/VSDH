package net.vulkanmod.mixin.shader;

import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game's vignette darkens the pack's finished picture. Like Iris, it stays off while a pack is
 * active unless the pack's shaders.properties says {@code vignette=true}; every tested pack says false.
 */
@Mixin(Gui.class)
public class WorldShaderOverlayMixin {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void vsdh$packVignette(GuiGraphics graphics, Entity entity, CallbackInfo ci) {
        var session = WorldShaderSession.current();
        if (session != null && session.packPicture() && !session.drawsVignette()) ci.cancel();
    }
}
