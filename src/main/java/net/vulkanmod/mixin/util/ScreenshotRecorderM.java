package net.vulkanmod.mixin.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.function.Consumer;

@Mixin(Screenshot.class)
public class ScreenshotRecorderM {

    /**
     * No frame exists while the window has no size (minimised): the swapchain owns no image then, but the client
     * tick still runs. Report the failure like vanilla does instead of reading an image that is gone.
     */
    @Inject(method = "_grab", at = @At("HEAD"), cancellable = true)
    private static void vsdh$skipWithoutFrame(File gameDirectory, String screenshotName, RenderTarget target,
                                              Consumer<Component> messageConsumer, CallbackInfo ci) {
        GlTexture texture = GlTexture.getTexture(target.getColorTextureId());
        if (texture == null || texture.getVulkanImage() == null) {
            messageConsumer.accept(Component.translatable("screenshot.failure", "no rendered frame (window has no size)"));
            ci.cancel();
        }
    }




    @Overwrite
    public static NativeImage takeScreenshot(RenderTarget target) {
        int textureId = target.getColorTextureId();
        GlTexture texture = GlTexture.getTexture(textureId);
        VulkanImage source = texture != null ? texture.getVulkanImage() : null;
        if (source == null) {
            throw new IllegalStateException("Screenshot target has no color image");
        }



        NativeImage nativeimage = new NativeImage(source.width, source.height, false);

        try {
            GlTexture.bindTextureUnit(0, textureId);
            return Renderer.getInstance().flushCmds(() -> {
                nativeimage.downloadTexture(0, true);


                if (source.requiresGlDownloadFlip(0)) nativeimage.flipY();
                return nativeimage;
            });
        } catch (RuntimeException | Error failure) {
            try { nativeimage.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
