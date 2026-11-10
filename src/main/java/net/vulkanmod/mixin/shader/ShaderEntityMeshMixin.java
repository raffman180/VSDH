package net.vulkanmod.mixin.shader;

import com.mojang.blaze3d.vertex.MeshData;
import de.stranger.vsdh.shader.NativeEntityMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MeshData.class)
public class ShaderEntityMeshMixin implements NativeEntityMesh.EntityIds {
    @Unique private NativeEntityMesh.Batch vsdh$entityIds;
    @Override public void vsdh$setEntityIds(NativeEntityMesh.Batch ids) { this.vsdh$entityIds = ids; }
    @Override public NativeEntityMesh.Batch vsdh$getEntityIds() { return this.vsdh$entityIds; }
}
