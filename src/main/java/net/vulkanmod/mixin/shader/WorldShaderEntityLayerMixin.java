package net.vulkanmod.mixin.shader;

import com.mojang.blaze3d.vertex.MeshData;
import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderType.class)
public class WorldShaderEntityLayerMixin {
    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void vsdh$queueTransparentEntity(MeshData mesh, CallbackInfo ci) {
        var session = WorldShaderSession.current();
        if (session != null && session.queueTransparentEntity((RenderType) (Object) this, mesh)) ci.cancel();
    }
}
