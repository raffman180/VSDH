package net.vulkanmod.mixin.shader;

import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class WorldShaderGameMixin {
    @Inject(method="renderLevel",at=@At("RETURN"))
    private void vsdh$finishWorld(CallbackInfo ci) {
        var session=WorldShaderSession.current();if(session!=null) session.finishWorld();
    }
}
