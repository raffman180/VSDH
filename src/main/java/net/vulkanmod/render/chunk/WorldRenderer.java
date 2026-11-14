package net.vulkanmod.render.chunk;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.compat.sable.aeronautics.AeronauticsWorldLayers;
import net.vulkanmod.compat.sable.aeronautics.AeronauticsWorldRenderer;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import net.vulkanmod.render.chunk.build.BlockRenderer;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.TaskDispatcher;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.Buffer;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.memory.IndirectBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.*;

import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.optimization.AdaptiveChunkUploadBudget;
import net.vulkanmod.render.optimization.ChunkFrameDiagnostics;

public class WorldRenderer {
    private static final float GRAPH_MOVEMENT_THRESHOLD_SQUARED = 0.25f;
    private static WorldRenderer INSTANCE;

    private final Minecraft minecraft;
    private ClientLevel level;
    private int renderDistance;
    private final RenderBuffers renderBuffers;

    private Vec3 cameraPos;
    private int lastCameraSectionX;
    private int lastCameraSectionY;
    private int lastCameraSectionZ;
    private float lastCameraX;
    private float lastCameraY;
    private float lastCameraZ;
    private float lastCamRotX;
    private float lastCamRotY;

    private SectionGrid sectionGrid;
    private final Map<ChunkArea,StaticQueue<RenderSection>> shadowDrawQueues=new IdentityHashMap<>();
    /** The same queues in insertion order: the per-type draw loop must not allocate map entries. */
    private final ArrayList<ChunkArea> shadowDrawAreas=new ArrayList<>();
    private final ArrayList<StaticQueue<RenderSection>> shadowDrawList=new ArrayList<>();
    /** Per-frame area verdicts for the light pass by ChunkArea.index: 0 unknown, 1 kept, 2 culled. */
    private byte[] shadowAreaState=new byte[0];
    private IndirectBuffer[] shadowIndirectBuffers;
    private int shadowSectionsQueued, shadowSectionsCulled;

    private SectionGraph sectionGraph;
    private boolean graphNeedsUpdate;

    private final Set<BlockEntity> globalBlockEntities = Sets.newHashSet();

    private final TaskDispatcher taskDispatcher;

    private double xTransparentOld;
    private double yTransparentOld;
    private double zTransparentOld;

    IndirectBuffer[] indirectBuffers;

    public RenderRegionBuilder renderRegionCache;

    private final List<Runnable> onAllChangedCallbacks = new ObjectArrayList<>();

    private WorldRenderer(RenderBuffers renderBuffers) {
        this.minecraft = Minecraft.getInstance();
        this.renderBuffers = renderBuffers;
        this.renderRegionCache = new RenderRegionBuilder();
        this.taskDispatcher = new TaskDispatcher();
        ChunkTask.setTaskDispatcher(this.taskDispatcher);
        allocateIndirectBuffers();

        BlockRenderer.setBlockColors(this.minecraft.getBlockColors());

        Renderer.getInstance().addOnResizeCallback(() -> {
            if (this.indirectBuffers.length != Renderer.getFramesNum())
                allocateIndirectBuffers();
        });
    }

    private void allocateIndirectBuffers() {
        if (this.indirectBuffers != null)
            Arrays.stream(this.indirectBuffers).forEach(Buffer::freeBuffer);

        this.indirectBuffers = new IndirectBuffer[Renderer.getFramesNum()];

        for (int i = 0; i < this.indirectBuffers.length; ++i) {
            this.indirectBuffers[i] = new IndirectBuffer(1000000, MemoryTypes.HOST_MEM);

        }

    }

    public static WorldRenderer init(RenderBuffers renderBuffers) {
        if (INSTANCE != null)
            return INSTANCE;
        else
            return INSTANCE = new WorldRenderer(renderBuffers);
    }

    public static WorldRenderer getInstance() {
        return INSTANCE;
    }

    public static ClientLevel getLevel() {
        return INSTANCE.level;
    }

    public static Vec3 getCameraPos() {
        return INSTANCE.cameraPos;
    }

    public void setupRenderer(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator) {
        this.cameraPos = camera.getPosition();
        if (this.minecraft.options.getEffectiveRenderDistance() != this.renderDistance) {
            this.allChanged();
        }

        this.applyPendingVanillaInvalidation();

        this.level.getProfiler().push("camera");
        float cameraX = (float) cameraPos.x();
        float cameraY = (float) cameraPos.y();
        float cameraZ = (float) cameraPos.z();
        int sectionX = SectionPos.posToSectionCoord(cameraX);
        int sectionY = SectionPos.posToSectionCoord(cameraY);
        int sectionZ = SectionPos.posToSectionCoord(cameraZ);

        boolean cameraSectionChanged = this.lastCameraSectionX != sectionX
                || this.lastCameraSectionY != sectionY
                || this.lastCameraSectionZ != sectionZ;
        if (cameraSectionChanged) {
            this.lastCameraSectionX = sectionX;
            this.lastCameraSectionY = sectionY;
            this.lastCameraSectionZ = sectionZ;
            this.sectionGrid.repositionCamera(cameraX, cameraZ);
        }

        double entityDistanceScaling = this.minecraft.options.entityDistanceScaling().get();
        Entity.setViewScale(Mth.clamp((double) this.renderDistance / 8.0D, 1.0D, 2.5D) * entityDistanceScaling);

        this.level.getProfiler().popPush("cull");
        this.minecraft.getProfiler().popPush("culling");

        this.minecraft.getProfiler().popPush("update");

        boolean cameraMoved = false;
        float d_xRot = Math.abs(camera.getXRot() - this.lastCamRotX);
        float d_yRot = Math.abs(camera.getYRot() - this.lastCamRotY);
        cameraMoved |= d_xRot > 2.0f || d_yRot > 2.0f;

        float cameraDeltaX = cameraX - this.lastCameraX;
        float cameraDeltaY = cameraY - this.lastCameraY;
        float cameraDeltaZ = cameraZ - this.lastCameraZ;
        cameraMoved |= cameraSectionChanged
                || cameraDeltaX * cameraDeltaX + cameraDeltaY * cameraDeltaY + cameraDeltaZ * cameraDeltaZ
                >= GRAPH_MOVEMENT_THRESHOLD_SQUARED;
        this.graphNeedsUpdate |= cameraMoved;

        if (!isCapturedFrustum) {

            if (this.graphNeedsUpdate) {
                this.graphNeedsUpdate = false;
                this.lastCameraX = cameraX;
                this.lastCameraY = cameraY;
                this.lastCameraZ = cameraZ;
                this.lastCamRotX = camera.getXRot();
                this.lastCamRotY = camera.getYRot();

                long graphStart = System.nanoTime();
                this.sectionGraph.update(camera, frustum, spectator);
                ChunkFrameDiagnostics.recordGraphUpdate(System.nanoTime() - graphStart);
            }
        }

        long scheduleStart = System.nanoTime();
        this.sectionGraph.schedulePendingRebuilds();
        ChunkFrameDiagnostics.recordSchedule(System.nanoTime() - scheduleStart,
                this.sectionGraph.readyRebuildCount(), this.sectionGraph.delayedRebuildCount());

        this.indirectBuffers[Renderer.getCurrentFrame()].reset();

        this.minecraft.getProfiler().pop();
    }

    public boolean uploadSections() {
        this.applyPendingVanillaInvalidation();
        if (this.sectionGrid == null) {
            return false;
        }

        this.minecraft.getProfiler().push("upload");

        boolean performedUploadWork = false;
        try {
            if (this.taskDispatcher.updateSections()) {
                this.scheduleGraphUpdate();
            }
            performedUploadWork = this.taskDispatcher.performedUploadWorkLastUpdate();
        } catch (Exception e) {

            Initializer.LOGGER.error("Failed to upload chunk sections; resetting renderer", e);
            allChanged();
        }

        this.minecraft.getProfiler().pop();
        return performedUploadWork;
    }

    public boolean isSectionCompiled(BlockPos blockPos) {
        RenderSection renderSection = this.sectionGrid.getSectionAtBlockPos(blockPos);
        return renderSection != null && renderSection.isCompiled();
    }

    public void allChanged() {
        AdaptiveChunkUploadBudget.reset();
        if (this.level != null) {
            AeronauticsWorldLayers.initialize();

            this.level.clearTintCaches();

            this.renderRegionCache.clear();
            this.taskDispatcher.createThreads(this.minecraft.hasSingleplayerServer());

            this.graphNeedsUpdate = true;

            this.renderDistance = this.minecraft.options.getEffectiveRenderDistance();
            if (this.sectionGrid != null) {
                this.sectionGrid.releaseAllBuffers();
            }

            this.taskDispatcher.clearBatchQueue();
            // The shadow draw lists are keyed by the chunk areas of the grid released above; without this every
            // rebuild (render distance, live shader switch) kept the old areas and their draw buffers reachable.
            clearShadowDraws();
            synchronized (this.globalBlockEntities) {
                this.globalBlockEntities.clear();
            }

            this.sectionGrid = new SectionGrid(this.level, this.renderDistance);
            this.sectionGraph = new SectionGraph(this.level, this.sectionGrid, this.taskDispatcher);

            this.onAllChangedCallbacks.forEach(Runnable::run);

            Entity entity = this.minecraft.getCameraEntity();
            if (entity != null) {
                this.sectionGrid.repositionCamera(entity.getX(), entity.getZ());
            }

        }
    }

    public void setLevel(@Nullable ClientLevel level) {
        clearShadowDraws();
        AdaptiveChunkUploadBudget.reset();
        this.lastCameraX = Float.MIN_VALUE;
        this.lastCameraY = Float.MIN_VALUE;
        this.lastCameraZ = Float.MIN_VALUE;
        this.lastCameraSectionX = Integer.MIN_VALUE;
        this.lastCameraSectionY = Integer.MIN_VALUE;
        this.lastCameraSectionZ = Integer.MIN_VALUE;

        this.level = level;
        ChunkStatusMap.createInstance(renderDistance);
        if (level != null) {
            this.allChanged();
        } else {
            if (this.sectionGrid != null) {
                this.sectionGrid.releaseAllBuffers();
                this.sectionGrid = null;
            }

            this.taskDispatcher.stopThreads();

            this.graphNeedsUpdate = true;
        }

    }

    public void addOnAllChangedCallback(Runnable runnable) {
        this.onAllChangedCallbacks.add(runnable);
    }

    public void clearOnAllChangedCallbacks() {
        this.onAllChangedCallbacks.clear();
    }

    public void renderSectionLayer(RenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {


        if (AeronauticsWorldLayers.isBase(renderType)) {
            AeronauticsWorldRenderer.renderBase(renderType, this.sectionGraph.getChunkAreaQueue().queue(),
                    camX, camY, camZ, modelView, projection);
            return;
        }
        if (AeronauticsWorldLayers.isGhost(renderType)) return;
        TerrainRenderType terrainRenderType = TerrainRenderType.get(renderType);
        var shaders = de.stranger.vsdh.shader.WorldShaderSession.current();
        boolean shaderFrame = shaders != null && shaders.active();
        if (shaderFrame) shaders.beforeTerrain(terrainRenderType);
        TerrainRenderState.prepareWorldTerrainState();
        renderType.setupRenderState();
        // Keep vanilla culling for translucent pack draws. The liquid mesh can
        // contain both windings, with a different normal on each side.
        if (!Initializer.CONFIG.backFaceCulling
                || (terrainRenderType == TerrainRenderType.TRANSLUCENT && !shaderFrame)) {
            RenderSystem.disableCull();
        }

        this.sortTranslucentSections(camX, camY, camZ);

        this.minecraft.getProfiler().push("filterempty");
        this.minecraft.getProfiler().popPush(() -> "render_" + renderType);

        VRenderSystem.applyMVP(modelView, projection);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

        if (shaderFrame) {
            shaders.renderTerrain(terrainRenderType,
                    pipeline -> drawSectionBatches(pipeline, terrainRenderType, camX, camY, camZ, false));
        } else {
            Renderer renderer = Renderer.getInstance();
            GraphicsPipeline pipeline = PipelineManager.getTerrainShader(terrainRenderType);
            renderer.bindGraphicsPipeline(pipeline);
            VTextureSelector.bindShaderTextures(pipeline);
            drawSectionBatches(pipeline, terrainRenderType, camX, camY, camZ, true);
        }

        this.minecraft.getProfiler().pop();
        renderType.clearRenderState();

        VRenderSystem.applyMVP(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix());
    }

    /** Shared draw walk: native shader programs reuse the same uploaded area buffers and visibility. */
    private void drawSectionBatches(GraphicsPipeline pipeline, TerrainRenderType terrainRenderType,
                                    double camX, double camY, double camZ, boolean bindUniforms) {
        Renderer renderer = Renderer.getInstance();
        boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        boolean indirectDraw = Initializer.CONFIG.indirectDraw && DeviceManager.supportsFastIndirectDraw();
        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer);

        int currentFrame = Renderer.getCurrentFrame();
        Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
        if (allowedRenderTypes.contains(terrainRenderType)) {
            terrainRenderType.setCutoutUniform();
            boolean ubosBound = !bindUniforms;

            final StaticQueue<ChunkArea> chunkAreaQueue = this.sectionGraph.getChunkAreaQueue().queue();
            final int areaCount = chunkAreaQueue.size();
            for (int areaIdx = 0; areaIdx < areaCount; areaIdx++) {
                ChunkArea chunkArea = chunkAreaQueue.get(isTranslucent ? areaCount - 1 - areaIdx : areaIdx);
                var queue = chunkArea.sectionQueue;
                DrawBuffers drawBuffers = chunkArea.drawBuffers;

                if (drawBuffers.getAreaBuffer(terrainRenderType) != null && queue.size() > 0) {

                    if (!ubosBound) {
                        renderer.uploadAndBindUBOs(pipeline);
                        ubosBound = true;
                    }

                    drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, terrainRenderType, camX, camY, camZ);

                    if (indirectDraw)
                        drawBuffers.buildDrawBatchesIndirect(indirectBuffers[currentFrame], queue, terrainRenderType);
                    else
                        drawBuffers.buildDrawBatchesDirect(queue, terrainRenderType);
                }
            }
        }

        if (terrainRenderType == TerrainRenderType.CUTOUT || terrainRenderType == TerrainRenderType.TRIPWIRE) {
            indirectBuffers[currentFrame].submitUploads();

        }

        if (bindUniforms && !indirectDraw) {
            VRenderSystem.setChunkOffset(0, 0, 0);
            renderer.pushConstants(pipeline);
        }

    }

    private void sortTranslucentSections(double camX, double camY, double camZ) {
        this.minecraft.getProfiler().push("translucent_sort");
        double d0 = camX - this.xTransparentOld;
        double d1 = camY - this.yTransparentOld;
        double d2 = camZ - this.zTransparentOld;

        if (d0 * d0 + d1 * d1 + d2 * d2 > 2.0D) {
            this.xTransparentOld = camX;
            this.yTransparentOld = camY;
            this.zTransparentOld = camZ;
            var sectionQueue = this.sectionGraph.getSectionQueue();
            final int sortCount = Math.min(sectionQueue.size(), 15);

            for (int i = 0; i < sortCount; i++) {
                sectionQueue.get(i).resortTransparency(this.taskDispatcher);
            }
        }

        this.minecraft.getProfiler().pop();
    }

    public void renderBlockEntities(PoseStack poseStack, double camX, double camY, double camZ,
                                    Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress, float gameTime) {
        MultiBufferSource bufferSource = this.renderBuffers.bufferSource();
        VFrustum frustum = this.sectionGraph.getFrustum();

        var blockEntitiesSections = this.sectionGraph.getBlockEntitiesSections();
        final int blockEntitySectionCount = blockEntitiesSections.size();
        final boolean blockEntityCulling = Initializer.CONFIG.blockEntityCulling;
        for (int sectionIdx = 0; sectionIdx < blockEntitySectionCount; sectionIdx++) {
            RenderSection renderSection = blockEntitiesSections.get(sectionIdx);
            List<BlockEntity> list = renderSection.getCompiledSection().getBlockEntities();
            if (!list.isEmpty()) {
                for (BlockEntity blockEntity : list) {
                    BlockPos blockPos = blockEntity.getBlockPos();
                    if (blockEntityCulling) {
                        double dx = (double) blockPos.getX() + 0.5 - camX;
                        double dy = (double) blockPos.getY() + 0.5 - camY;
                        double dz = (double) blockPos.getZ() + 0.5 - camZ;
                        if (dx * dx + dy * dy + dz * dz > 9216.0) {
                            continue;
                        }
                        if (frustum != null) {
                            float x1 = (float) (blockPos.getX() - 1);
                            float y1 = (float) (blockPos.getY() - 1);
                            float z1 = (float) (blockPos.getZ() - 1);
                            float x2 = (float) (blockPos.getX() + 2);
                            float y2 = (float) (blockPos.getY() + 2);
                            float z2 = (float) (blockPos.getZ() + 2);
                            if (!frustum.testFrustum(x1, y1, z1, x2, y2, z2)) {
                                continue;
                            }
                        }
                    }
                    MultiBufferSource bufferSource1 = bufferSource;
                    poseStack.pushPose();
                    poseStack.translate((double) blockPos.getX() - camX, (double) blockPos.getY() - camY, (double) blockPos.getZ() - camZ);
                    SortedSet<BlockDestructionProgress> sortedset = destructionProgress.get(blockPos.asLong());
                    if (sortedset != null && !sortedset.isEmpty()) {
                        int j1 = sortedset.last().getProgress();
                        if (j1 >= 0) {
                            PoseStack.Pose pose = poseStack.last();
                            VertexConsumer vertexconsumer = new SheetedDecalTextureGenerator(this.renderBuffers.crumblingBufferSource().getBuffer(ModelBakery.DESTROY_TYPES.get(j1)), pose, 1.0f);
                            bufferSource1 = (renderType) -> {
                                VertexConsumer vertexConsumer2 = bufferSource.getBuffer(renderType);
                                return renderType.affectsCrumbling() ? VertexMultiConsumer.create(vertexconsumer, vertexConsumer2) : vertexConsumer2;
                            };
                        }
                    }

                    this.minecraft.getBlockEntityRenderDispatcher().render(blockEntity, gameTime, poseStack, bufferSource1);
                    poseStack.popPose();
                }
            }
        }
    }

    public void scheduleGraphUpdate() {
        this.graphNeedsUpdate = true;
    }

    public boolean graphNeedsUpdate() {
        return this.graphNeedsUpdate;
    }

    public boolean hasPendingSectionWork() {
        return this.graphNeedsUpdate
                || VanillaSectionInvalidation.INSTANCE.hasPending()
                || this.sectionGraph != null && this.sectionGraph.hasPendingRebuilds()
                || !this.taskDispatcher.isIdle();
    }

    public int getVisibleSectionsCount() {
        return this.sectionGraph.getSectionQueue().size();
    }

    public void setSectionDirty(int x, int y, int z, boolean flag) {
        if (this.sectionGrid == null) return;
        this.sectionGrid.setDirty(x, y, z, flag);

        this.renderRegionCache.remove(x, z);
    }

    private void applyPendingVanillaInvalidation() {
        if (!VanillaSectionInvalidation.INSTANCE.consume() || this.level == null || this.sectionGrid == null) return;
        this.renderRegionCache.clear();
        for (RenderSection section : this.sectionGrid.sections) {
            section.setDirty(false);
        }
    }

    /** Camera-relative box test for the light pass; must keep any box that contains a kept box. */
    @FunctionalInterface
    public interface ShadowBoxTest {
        boolean visible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);
    }

    /** Separate light visibility: never reuse the camera's occlusion-filtered section queues. */
    public void prepareShadowDraws() {
        prepareShadowDraws(null,0,0,0);
    }

    /**
     * Queues the uploaded sections the light may see. A null test keeps every section. Areas are
     * tested first; because the test is monotone, a rejected area cannot hold a kept section.
     */
    public void prepareShadowDraws(ShadowBoxTest test,double cameraX,double cameraY,double cameraZ) {
        for(int i=0;i<shadowDrawList.size();i++)shadowDrawList.get(i).clear();
        shadowSectionsQueued=0;shadowSectionsCulled=0;
        var indirect=shadowIndirectBuffer();
        if(indirect!=null)indirect.reset();
        if(sectionGrid==null)return;
        int areas=sectionGrid.chunkAreaManager.size;
        if(shadowAreaState.length!=areas)shadowAreaState=new byte[areas];
        else java.util.Arrays.fill(shadowAreaState,(byte)0);
        final int areaWidth=ChunkAreaManager.WIDTH<<4, areaHeight=ChunkAreaManager.HEIGHT<<4;
        // Neighbouring sections of the grid share their area: remember the last area's queue instead of asking
        // the map for each of the ten thousand and more sections of every shadow pass.
        ChunkArea lastArea=null;StaticQueue<RenderSection> lastQueue=null;
        for(RenderSection section:sectionGrid.sections) {
            var area=section.getChunkArea();
            if(area==null||section.isCompletelyEmpty())continue;
            if(test!=null) {
                byte state=shadowAreaState[area.index];
                if(state==0) {
                    float ax=(float)(area.position.x()-cameraX),ay=(float)(area.position.y()-cameraY),az=(float)(area.position.z()-cameraZ);
                    state=test.visible(ax,ay,az,ax+areaWidth,ay+areaHeight,az+areaWidth)?(byte)1:(byte)2;
                    shadowAreaState[area.index]=state;
                }
                boolean areaVisible=state==1;
                float sx=(float)(section.xOffset-cameraX),sy=(float)(section.yOffset-cameraY),sz=(float)(section.zOffset-cameraZ);
                if(!areaVisible||!test.visible(sx,sy,sz,sx+16,sy+16,sz+16)) {shadowSectionsCulled++;continue;}
            }
            StaticQueue<RenderSection> queue;
            if(area==lastArea)queue=lastQueue;
            else {
                queue=shadowDrawQueues.get(area);
                if(queue==null) {queue=new StaticQueue<>(512);shadowDrawQueues.put(area,queue);shadowDrawAreas.add(area);shadowDrawList.add(queue);}
                lastArea=area;lastQueue=queue;
            }
            queue.add(section);
            shadowSectionsQueued++;
        }
    }
    /** Non-empty sections queued for the last light pass. */
    public int shadowSectionsQueued() {return shadowSectionsQueued;}
    /** Non-empty sections the last light pass rejected. */
    public int shadowSectionsCulled() {return shadowSectionsCulled;}
    public void clearShadowDraws() {shadowDrawQueues.clear();shadowDrawAreas.clear();shadowDrawList.clear();}
    /** Smoke diagnostics only: uploaded light casters absent from the camera's current queue. */
    public int shadowOffCameraSections() {
        var visible=Collections.newSetFromMap(new IdentityHashMap<RenderSection,Boolean>());
        var areas=sectionGraph.getChunkAreaQueue().queue();
        for(int i=0;i<areas.size();i++)for(var section:areas.get(i).sectionQueue)visible.add(section);
        int count=0;
        for(var queue:shadowDrawQueues.values())for(var section:queue) {
            if(visible.contains(section))continue;
            for(var type:TerrainRenderType.values())if(section.getDrawParameters(type).indexCount()>0){count++;break;}
        }
        return count;
    }
    public long drawShadowSectionBatches(GraphicsPipeline pipeline,TerrainRenderType type,double x,double y,double z) {
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(),Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer());
        var indirect=shadowIndirectBuffer();
        long draws=0;
        for(int a=0;a<shadowDrawList.size();a++) {
            var buffers=shadowDrawAreas.get(a).getDrawBuffers();var queue=shadowDrawList.get(a);
            if(buffers.getAreaBuffer(type)==null)continue;
            int count=0;for(int i=0;i<queue.size();i++)if(queue.get(i).getDrawParameters(type).indexCount()>0)count++;
            if(count==0)continue;
            buffers.bindBuffers(Renderer.getCommandBuffer(),pipeline,type,x,y,z);
            if(indirect!=null)buffers.buildDrawBatchesIndirect(indirect,queue,type);
            else buffers.buildDrawBatchesDirect(queue,type);
            draws+=count;
        }
        if(indirect!=null)indirect.submitUploads();
        return draws;
    }

    /**
     * The light pass owns its per-frame indirect buffers: it may run before the camera pass resets
     * its own, and must never share their offsets.
     */
    private IndirectBuffer shadowIndirectBuffer() {
        if(!Initializer.CONFIG.indirectDraw||!DeviceManager.supportsFastIndirectDraw())return null;
        int frames=Renderer.getFramesNum();
        if(shadowIndirectBuffers==null||shadowIndirectBuffers.length!=frames) {
            if(shadowIndirectBuffers!=null)Arrays.stream(shadowIndirectBuffers).forEach(Buffer::freeBuffer);
            shadowIndirectBuffers=new IndirectBuffer[frames];
            for(int i=0;i<frames;i++)shadowIndirectBuffers[i]=new IndirectBuffer(1000000,MemoryTypes.HOST_MEM);
        }
        return shadowIndirectBuffers[Renderer.getCurrentFrame()];
    }

    public SectionGrid getSectionGrid() {
        return this.sectionGrid;
    }

    public ChunkAreaManager getChunkAreaManager() {
        return this.sectionGrid.chunkAreaManager;
    }

    public TaskDispatcher getTaskDispatcher() {
        return taskDispatcher;
    }

    public short getLastFrame() {
        return this.sectionGraph.getLastFrame();
    }

    public int getRenderDistance() {
        return this.renderDistance;
    }

    public String getChunkStatistics() {
        return this.sectionGraph.getStatistics();
    }

    public void cleanUp() {
        if (indirectBuffers != null)
            Arrays.stream(indirectBuffers).forEach(Buffer::freeBuffer);
        if (shadowIndirectBuffers != null)
            Arrays.stream(shadowIndirectBuffers).forEach(Buffer::freeBuffer);
        shadowIndirectBuffers = null;
    }

}
