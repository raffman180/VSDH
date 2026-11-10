package de.stranger.vsdh.shader;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.client.ClientHooks;

/** Collects actual Minecraft model batches only within the light's current view. */
public final class NativeShadowCasters implements AutoCloseable {
    public interface LevelAccess {
        void vsdh$renderShadowEntity(net.minecraft.world.entity.Entity entity, double x, double y, double z,
                                     float partial, PoseStack pose, MultiBufferSource buffers);
    }

    private final NativeModelBuffers storage = new NativeModelBuffers(8192);
    private final MultiBufferSource.BufferSource buffers = storage.source;

    void draw(ShadowPackPlan plan, Camera camera, ShadowCamera light, float partial, float realPartial, float distance) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return;
        var pos = camera.getPosition();
        var frustum = new Frustum(light.view, light.projection);
        frustum.prepare(pos.x, pos.y, pos.z);
        var pose = new PoseStack();
        var casters = plan.casters();
        if (plan.entities() != null) {
            var dispatcher = mc.getEntityRenderDispatcher();
            var renderer = (LevelAccess) mc.levelRenderer;
            for (var entity : level.entitiesForRendering()) {
                if ((!casters.entities() && entity != mc.player) || entity.isSpectator()
                        || !dispatcher.shouldRender(entity, frustum, pos.x, pos.y, pos.z)) continue;
                float delta = level.tickRateManager().isEntityFrozen(entity) ? partial : realPartial;
                renderer.vsdh$renderShadowEntity(entity, pos.x, pos.y, pos.z, delta, pose, buffers);
            }
            buffers.endBatch();
        }
        if (plan.blocks() != null) {
            var dispatcher = mc.getBlockEntityRenderDispatcher();
            int radius = Math.min(mc.options.getEffectiveRenderDistance() + 1, (int) Math.ceil(distance * Math.sqrt(2) / 16) + 1);
            int centerX = SectionPos.blockToSectionCoord(pos.x);
            int centerZ = SectionPos.blockToSectionCoord(pos.z);
            var chunks = level.getChunkSource();
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                for (int x = centerX - radius; x <= centerX + radius; x++) {
                    var chunk = chunks.getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    for (var entity : chunk.getBlockEntities().values()) {
                        if ((!casters.blockEntities() && entity.getBlockState().getLightEmission() == 0)
                                || !ClientHooks.isBlockEntityRendererVisible(dispatcher, entity, frustum)) continue;
                        var block = entity.getBlockPos();
                        pose.pushPose();
                        try {
                            pose.translate(block.getX() - pos.x, block.getY() - pos.y, block.getZ() - pos.z);
                            dispatcher.render(entity, partial, pose, buffers);
                        } finally {
                            pose.popPose();
                        }
                    }
                }
            }
            buffers.endBatch();
        }
    }

    @Override
    public void close() {
        storage.close();
    }
}
