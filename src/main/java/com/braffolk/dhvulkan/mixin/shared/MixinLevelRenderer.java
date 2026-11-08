/* Adapted from Braffolk's DH Vulkan extension, LGPL-3.0. See NOTICE. */
package com.braffolk.dhvulkan.mixin.shared;

import com.braffolk.dhvulkan.compat.Compat;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Completes DH depth compositing after Minecraft terrain and weather. */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void dhvulkan$lateComposite(CallbackInfo ci) {
        Compat.runLateCompositeHook();
    }
}
