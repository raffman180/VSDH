package net.vulkanmod.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import de.stranger.vsdh.shader.WorldShaderSession;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemInHandRenderer.class)
public class WorldShaderHandMixin {
    @WrapOperation(method = "renderHandsWithItems", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/ClientHooks;renderSpecificFirstPersonHand(Lnet/minecraft/world/InteractionHand;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFLnet/minecraft/world/item/ItemStack;)Z"))
    private boolean vsdh$selectPhase(InteractionHand side, PoseStack pose, MultiBufferSource buffers, int light,
                                     float partial, float pitch, float swing, float equip, ItemStack stack, Operation<Boolean> draw) {
        var session = WorldShaderSession.current();
        var hand = session == null ? null : session.handRenderer();
        return hand != null && hand.drawing() && hand.owns(buffers) && hand.skipHand(stack)
                || draw.call(side, pose, buffers, light, partial, pitch, swing, equip, stack);
    }
}
