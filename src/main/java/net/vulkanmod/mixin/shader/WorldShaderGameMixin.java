package net.vulkanmod.mixin.shader;

import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class WorldShaderGameMixin {
    // The vanilla hand starts with a depth clear. Finish the pack while depthtex0
    // still represents the world, then draw the vanilla hand onto the final colour.
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
