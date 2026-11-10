package net.vulkanmod.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntityRenderDispatcher.class)
public class WorldShaderBlockEntityMixin {
    @WrapMethod(method = "render")
    private void vsdh$captureBlockMaterial(BlockEntity entity, float tickDelta, PoseStack pose,
                                          MultiBufferSource buffers, Operation<Void> draw) {
        var session = WorldShaderSession.current();
        if (session == null) {
            draw.call(entity, tickDelta, pose, buffers);
            return;
        }
        int previous = session.beginBlockEntity(entity);
        try {
            draw.call(entity, tickDelta, pose, buffers);
        } finally {
            session.endEntity(previous);
        }
    }
}
