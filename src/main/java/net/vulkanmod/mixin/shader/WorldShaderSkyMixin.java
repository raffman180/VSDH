package net.vulkanmod.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import de.stranger.vsdh.shader.WorldShaderSession;
import dev.vitrail.pack.model.RenderStage;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class WorldShaderSkyMixin {
    private static final String BUFFER_DRAW =
            "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V";
    private static final String IMMEDIATE_DRAW =
            "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V";

    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = BUFFER_DRAW, ordinal = 0))
    private void vsdh$skyDisc(VertexBuffer buffer, Matrix4f view, Matrix4f projection, ShaderInstance shader, Operation<Void> original) {
        WorldShaderSession.drawSkyStage(RenderStage.SKY, () -> {
            WorldShaderSession.drawSkyHorizon(view, projection, shader);
            original.call(buffer, view, projection, shader);
        });
    }

    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = BUFFER_DRAW, ordinal = 1))
    private void vsdh$stars(VertexBuffer buffer, Matrix4f view, Matrix4f projection, ShaderInstance shader, Operation<Void> original) {
        WorldShaderSession.drawSkyStage(RenderStage.STARS, () -> original.call(buffer, view, projection, shader));
    }

    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = BUFFER_DRAW, ordinal = 2))
    private void vsdh$darkDisc(VertexBuffer buffer, Matrix4f view, Matrix4f projection, ShaderInstance shader, Operation<Void> original) {
        WorldShaderSession.drawSkyStage(RenderStage.VOID, () -> original.call(buffer, view, projection, shader));
    }

    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = IMMEDIATE_DRAW, ordinal = 0))
    private void vsdh$sunset(MeshData mesh, Operation<Void> original) {
        if (!WorldShaderSession.drawSkyStage(RenderStage.SUNSET, () -> original.call(mesh))) mesh.close();
    }

    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = IMMEDIATE_DRAW, ordinal = 1))
    private void vsdh$sun(MeshData mesh, Operation<Void> original) {
        if (!WorldShaderSession.drawSkyStage(RenderStage.SUN, () -> original.call(mesh))) mesh.close();
    }

    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = IMMEDIATE_DRAW, ordinal = 2))
    private void vsdh$moon(MeshData mesh, Operation<Void> original) {
        if (!WorldShaderSession.drawSkyStage(RenderStage.MOON, () -> original.call(mesh))) mesh.close();
    }

    /** Both the depth-only and the colour draw of fancy clouds; the colour mask comes from the render type. */
    @WrapOperation(method = "renderClouds", at = @At(value = "INVOKE", target = BUFFER_DRAW))
    private void vsdh$clouds(VertexBuffer buffer, Matrix4f view, Matrix4f projection, ShaderInstance shader, Operation<Void> original) {
        WorldShaderSession.drawSkyStage(RenderStage.CLOUDS, () -> original.call(buffer, view, projection, shader));
    }

    @WrapOperation(method = "renderEndSky", at = @At(value = "INVOKE", target = IMMEDIATE_DRAW))
    private void vsdh$endSky(MeshData mesh, Operation<Void> original) {
        WorldShaderSession.drawSkyStage(RenderStage.CUSTOM_SKY, () -> original.call(mesh));
    }
}
