package de.stranger.vsdh.smoke;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

final class TransparentBlockWorldProbe {
    private static final BlockPos CHEST = new BlockPos(-5, 98, 2);
    private static final RenderType TYPE = RenderType.entityTranslucent(
            ResourceLocation.withDefaultNamespace("textures/atlas/chest.png"));

    static void draw() {
        var mc = Minecraft.getInstance();
        var chest = mc.level.getBlockEntity(CHEST);
        if (chest == null) return;
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        var pose = new PoseStack();
        pose.translate(CHEST.getX() - camera.x - 1.25, CHEST.getY() - camera.y, CHEST.getZ() - camera.z);
        var buffers = mc.renderBuffers().bufferSource();
        mc.getBlockEntityRenderDispatcher().render(chest, 0, pose, ignored -> buffers.getBuffer(TYPE));
        buffers.endBatch(TYPE);
    }
}
