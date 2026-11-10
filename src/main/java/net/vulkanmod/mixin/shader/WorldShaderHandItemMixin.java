package net.vulkanmod.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemRenderer.class)
public class WorldShaderHandItemMixin {
    @WrapMethod(method = "render")
    private void vsdh$handItemMaterial(ItemStack stack, ItemDisplayContext context, boolean left,
                                        PoseStack pose, MultiBufferSource buffers, int light, int overlay,
                                        BakedModel model, Operation<Void> draw) {
        var session = WorldShaderSession.current();
        var hand = session == null ? null : session.handRenderer();
        if (hand == null || !hand.drawing() || !hand.owns(buffers)) {
            draw.call(stack, context, left, pose, buffers, light, overlay, model);
            return;
        }
        long previous = hand.beginItem(stack);
        try {
            draw.call(stack, context, left, pose, buffers, light, overlay, model);
        } finally {
            hand.endItem(previous);
        }
    }
}
