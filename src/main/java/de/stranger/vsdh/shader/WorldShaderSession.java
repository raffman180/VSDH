package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import dev.vitrail.pack.model.RenderStage;
import dev.vitrail.glsl.VertexInputs;
import net.vulkanmod.vulkan.VRenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.vulkanmod.gl.GlFramebuffer;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Development world host. Installation is explicit, prepared off-thread, and requires an unloaded world. */
public final class WorldShaderSession implements MainPass {
    private static WorldShaderSession current;
    public static final int NO_ENTITY_CAPTURE = Integer.MIN_VALUE;
    public static final int BLOCK_ENTITY_CAPTURE = 1 << 16;
    private static final java.util.Set<String> DEPTH_NAMES=java.util.Set.of("depthtex0","depthtex1","depthtex2","gdepthtex");
    private final MainPass delegate;
    private final WorldPackSet plans;
    private final ShaderBlockMaterials blockMaterials;
    private WorldPackPlan plan;
    private WorldUniforms uniforms;
    private final Map<TerrainRenderType,NativeTerrainPass> terrain=new EnumMap<>(TerrainRenderType.class);
    private final Map<TerrainRenderType,NativeBlend> terrainBlend=new EnumMap<>(TerrainRenderType.class);
    private final Map<TerrainRenderType,PackProgramSource.Prepared> sources=new EnumMap<>(TerrainRenderType.class);
    private final Map<SkyPackPlan.Layout,NativeGeometryPass> sky=new EnumMap<>(SkyPackPlan.Layout.class);
    private final Map<ParticlePackPlan.Pass,NativeGeometryPass> particles=new EnumMap<>(ParticlePackPlan.Pass.class);
    private final long[] particleDraws=new long[ParticlePackPlan.Pass.values().length];
    private final long[] geometryDraws=new long[RenderStage.values().length];
    private RenderStage geometryStage;
    private final NativeEntityMesh entityMesh = new NativeEntityMesh();
    private final Map<ResourceLocation, Integer> entityMaterials = new HashMap<>();
    private NativeGeometryPass entitySolid, entityCutout, blockSolid, blockCutout, entityTranslucent;
    private NativeGeometryPass handSolid, handLate, blockTranslucent, eyesAdditive, eyesEmissive;
    private long transparentBlockDraws, emissiveDraws, glintDraws;
    private NativeGeometryPass glintEarly, glintLate;
    private java.util.function.BiConsumer<VulkanImage, Boolean> glintObserver;
    private NativeHandRenderer hand;
    private final long[] handDraws = new long[2];
    private record PendingEntity(RenderType type, MeshData mesh) {}
    private final ArrayDeque<PendingEntity> pendingEntities = new ArrayDeque<>();
    private final long[] transparentEntityDraws = new long[2];
    private int entityMaterial = NO_ENTITY_CAPTURE;
    private long entityDraws, mappedEntityDraws, mappedBlockEntityDraws;
    private final Map<Long,Integer> aliases=new HashMap<>();
    private NativeShaderChain chain;
    private NativeStorageBuffers storage;
    private VulkanImage failedDepth;
    private NativeCustomImages customImages;
    private NativeWorldDepth depth;
    private NativeWorldShadows shadows;
    private NativeShadowCasters shadowCasters;
    private boolean renderingShadowCasters;
    private NativeWorldTextures textures;
    private NativeDistantTerrain distant;
    private VulkanImage borrowedDepth;
    private NativeShaderChain.View scene;
    private boolean active,deferred,closed;
    private long frames,terrainCalls;
    private long skippedFrames;
    private Consumer<WorldShaderSession> frameObserver;

    private WorldShaderSession(MainPass delegate, WorldPackSet plans, ShaderBlockMaterials blockMaterials) {
        this.delegate=delegate;
        this.plans=plans;
        this.blockMaterials=blockMaterials;
        selectPlan(plans.overworld());
    }

    private void selectPlan(WorldPackPlan next) {
        plan=next;
        readsWaterShadow.clear();
        uniforms=new WorldUniforms(next);
        uniforms.blockMaterials(blockMaterials);
        entityMaterials.clear();
        if (next.entities()!=null) next.entities().materials().forEach((name,id)->entityMaterials.put(ResourceLocation.parse(name),id));
        for(var type:TerrainRenderType.values())
            sources.put(type,next.terrain().get(type.name().toLowerCase(java.util.Locale.ROOT)));
    }

    public void changeWorld(net.minecraft.client.multiplayer.ClientLevel level) {
        RenderSystem.assertOnRenderThread();
        releaseGraph();
        failedDepth=null;pendingRelease=false;frameFailures=0;
        if(level!=null) {
            var next=plans.forDimension(level.dimension().location().toString());
            if(next!=plan)selectPlan(next);
        }
    }

    public String dimensionFolder() { return plan.chain().targets().place(); }
    public static WorldShaderSession current() { return current; }
    /** -Dvsdh.noSkyHorizon=true: diagnostic switch that leaves the closed horizon out. */
    private static final boolean NO_SKY_HORIZON=Boolean.getBoolean("vsdh.noSkyHorizon");
    /** Draws the closed horizon before the vanilla sky disc while a pack sky program replaces it. */
    public static void drawSkyHorizon(Matrix4f view, Matrix4f projection, ShaderInstance shader) {
        var session=current;
        if(NO_SKY_HORIZON||session==null||!session.active||session.geometryStage!=RenderStage.SKY
                ||session.sky.get(SkyPackPlan.Layout.DISC)==null)return;
        SkyHorizon.draw(view,projection,shader);
    }
    public boolean active() { return active; }
    public long framesCompleted() { return frames; }
    public long terrainCalls() { return terrainCalls; }
    public NativeShaderChain chain() { return chain; }
    public NativeWorldShadows shadows() {return shadows;}
    public NativeDistantTerrain distant() {return distant;}
    public boolean drawsDistantTerrain() {return active && distant!=null;}
    /** Whether the current dimension's pack plan has its own DH programs (independent of the frame). */
    public boolean packHasDistantPrograms() {return plan.distant()!=null;}
    /** Whether the game's vignette / under-water overlay stay on screen (pack directives, off by default as in Iris). */
    public boolean drawsVignette() {return plans.vignette();}
    public boolean drawsUnderwaterOverlay() {return plans.underwaterOverlay();}
    /** Whether DH's own clouds are drawn under this pack (Iris's dhClouds/clouds rule). */
    public boolean drawsDhClouds() {return plan.distant()==null||plan.distant().clouds();}
    public void captureDistant(com.braffolk.dhvulkan.core.data.RenderUniforms frame) {
        uniforms.captureDistant(frame);
    }
    private void flushDistantDrawsBody() {
        if (!drawsDistantTerrain()) throw new IllegalStateException("DH callback left its world frame");
        if (deferred && distant.hasOpaqueDraws())
            throw new IllegalStateException("DH opaque terrain arrived after deferred lighting");
        net.vulkanmod.vulkan.queries.GpuTimestamps.mark(deferred ? "translucent" : "opaque");
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            distant.render(this, deferred);
        }
        net.vulkanmod.vulkan.queries.GpuTimestamps.mark(deferred ? "dhWater" : "dhOpaque");
    }
    public net.minecraft.client.CloudStatus clouds(net.minecraft.client.CloudStatus userSetting) {
        if(!active)return userSetting;
        return switch(plan.sky().clouds()) {
            case DEFAULT->userSetting;case OFF->net.minecraft.client.CloudStatus.OFF;
            case FAST->net.minecraft.client.CloudStatus.FAST;case FANCY->net.minecraft.client.CloudStatus.FANCY;
        };
    }
    public long particleDraws(boolean afterDeferred) {
        return particleDraws[(afterDeferred?ParticlePackPlan.Pass.AFTER:ParticlePackPlan.Pass.BEFORE).ordinal()];
    }

    public long geometryDraws(RenderStage stage) { return geometryDraws[stage.ordinal()]; }

    public static boolean drawSkyStage(RenderStage stage, Runnable draw) {
        var session=current;
        if(session==null || !session.active) {
            draw.run();
            return true;
        }
        var elements=session.plan.sky();
        boolean enabled=switch(stage) {
            case SKY,SUNSET,VOID->elements.sky();
            case SUN->elements.sun();
            case MOON->elements.moon();
            case STARS->elements.stars();
            // Cloud directives already decide through getCloudsType whether renderClouds runs at all.
            case CUSTOM_SKY,CLOUDS->true;
            default->throw new IllegalArgumentException("Not a sky stage: "+stage);
        };
        if(!enabled)return false;
        session.withGeometryStage(stage,draw);
        return true;
    }

    private void withGeometryStage(RenderStage stage,Runnable draw) {
        var previous=geometryStage;
        geometryStage=stage;
        try { draw.run(); } finally { geometryStage=previous; }
    }

    public void drawWeather(Runnable draw) {
        if(!active) draw.run();
        else if(plan.particles().weather().drawn()) withGeometryStage(RenderStage.RAIN_SNOW,draw);
    }

    public boolean weatherWritesDepth(boolean original) {
        return original || active && plan.particles().rainDepth();
    }

    public boolean rainSplashes() { return plan.particles().weather().particles(); }

    public void drawParticles(boolean before, Predicate<ParticleRenderType> original,
                              Consumer<Predicate<ParticleRenderType>> draw) {
        if(!active) { draw.accept(original);return; }
        var order=plan.particles().order();
        if(order==ParticlePackPlan.Order.BEFORE && !before || order==ParticlePackPlan.Order.AFTER && before)return;
        Predicate<ParticleRenderType> filter=order==ParticlePackPlan.Order.MIXED?original:type->true;
        withGeometryStage(RenderStage.PARTICLES,()->draw.accept(filter));
    }

    private boolean renderWorldGeometryBody(VertexFormat format,VertexFormat.Mode mode,ShaderInstance shader,
                                       Matrix4f view,Matrix4f projection,Consumer<GraphicsPipeline> geometry) {
        if(!active || geometryStage==null)return false;
        var stage=geometryStage;
        ParticlePackPlan.Pass particlePass=null;
        NativeGeometryPass program;
        PackProgramSource.Prepared source;
        boolean untextured=false;
        if(stage==RenderStage.PARTICLES || stage==RenderStage.RAIN_SNOW) {
            if(!DefaultVertexFormat.PARTICLE.equals(format) || shader!=GameRenderer.getParticleShader())return false;
            particlePass=stage==RenderStage.RAIN_SNOW?ParticlePackPlan.Pass.WEATHER
                    :deferred?ParticlePackPlan.Pass.AFTER:ParticlePackPlan.Pass.BEFORE;
            program=particles.get(particlePass);
            source=plan.particles().programs().get(particlePass);
        } else {
            var layout=SkyPackPlan.Layout.forStage(stage);
            if(!NativeGeometryPass.skyFormat(layout).equals(format))return false;
            program=sky.get(layout);
            source=plan.skyPrograms().programs().get(layout);
            untextured=!layout.elements.contains("UV0");
        }
        if(program==null)return false;
        uniforms.writeDraw(program,view,projection,stage);
        bindGeometry(program,source,deferred,untextured);
        int topology=VRenderSystem.topology;
        try(var scope=Renderer.getInstance().temporaryRenderTarget()) {
            VRenderSystem.setPrimitiveTopology(mode);
            program.render(geometry);
        } finally {
            VRenderSystem.topology=topology;
        }
        geometryDraws[stage.ordinal()]++;
        if(particlePass!=null)particleDraws[particlePass.ordinal()]++;
        return true;
    }

    public int beginEntity(Entity entity) {
        int previous = entityMaterial;
        if (renderingShadowCasters || active && !deferred && plan.entities() != null) {
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            entityMaterial = entityMaterials.getOrDefault(key, -1) & 0xffff;
        } else {
            entityMaterial = NO_ENTITY_CAPTURE;
        }
        return previous;
    }

    public int beginBlockEntity(BlockEntity entity) {
        int previous = entityMaterial;
        entityMaterial = (renderingShadowCasters || active && !deferred && blockSolid != null)
                ? BLOCK_ENTITY_CAPTURE | (blockMaterials.applyAsInt(entity.getBlockState()) & 0xffff)
                : NO_ENTITY_CAPTURE;
        return previous;
    }

    public void endEntity(int previous) { entityMaterial = previous; }

    public static int materialForEntityVertex() {
        var session = current;
        return session != null && RenderSystem.isOnRenderThread() ? session.entityMaterial : NO_ENTITY_CAPTURE;
    }

    public long entityDraws() { return entityDraws; }
    public long mappedEntityDraws() { return mappedEntityDraws; }
    public long mappedBlockEntityDraws() { return mappedBlockEntityDraws; }

    public long transparentEntityDraws(boolean late) { return transparentEntityDraws[late ? 1 : 0]; }

    public long transparentBlockDraws() { return transparentBlockDraws; }
    public long emissiveDraws() { return emissiveDraws; }

    private boolean queueTransparentEntityBody(RenderType type, MeshData mesh) {
        if (!active || deferred || plan.entities() == null || !plan.entities().lateTransparency()
                || !RenderSystem.isOnRenderThread()
                || net.vulkanmod.compat.sable.render.SublevelRenderScope.current().isPresent()) return false;
        var batch = ((NativeEntityMesh.EntityIds) (Object) mesh).vsdh$getEntityIds();
        if (batch == null) return false;
        var program = switch (type.name) {
            case "entity_translucent", "entity_translucent_cull", "item_entity_translucent_cull" ->
                    batch.blocks() ? blockTranslucent : entityTranslucent;
            case "eyes" -> batch.blocks() ? null : eyesAdditive;
            case "entity_translucent_emissive" -> batch.blocks() ? null : eyesEmissive;
            default -> null;
        };
        if (program == null) return false;
        pendingEntities.addLast(new PendingEntity(type, mesh));
        return true;
    }

    public NativeHandRenderer handRenderer() { return hand; }
    public long handDraws(boolean late) { return handDraws[late ? 1 : 0]; }
    public VulkanImage worldDepth(String sampler) { return depth.image(sampler); }

    public long glintDraws() { return glintDraws; }
    public void glintObserver(java.util.function.BiConsumer<VulkanImage, Boolean> observer) { glintObserver = observer; }

    private boolean renderEntityGeometryBody(MeshData mesh) {
        if (renderingShadowCasters) {
            try (mesh) { drawShadowCasterBatch(mesh); }
            return true;
        }
        if (!active || entitySolid == null
                || net.vulkanmod.compat.sable.render.SublevelRenderScope.current().isPresent()) return false;
        var state = mesh.drawState();
        if (state.format() == DefaultVertexFormat.POSITION_TEX && glintEarly != null) return renderGlint(mesh, state);
        if (state.format() != DefaultVertexFormat.NEW_ENTITY || state.mode() != VertexFormat.Mode.QUADS
                || state.vertexCount() < 4 || state.vertexCount() % 4 != 0) return false;
        if (hand != null && hand.drawing()) {
            try (mesh) { drawHandBatch(mesh, state); }
            return true;
        }
        var batch = ((NativeEntityMesh.EntityIds) (Object) mesh).vsdh$getEntityIds();
        if (batch == null || (batch.blocks() && blockSolid == null)) return false;

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return false;
        boolean transparent = shader == GameRenderer.getRendertypeEntityTranslucentShader()
                || shader == GameRenderer.getRendertypeEntityTranslucentCullShader()
                || shader == GameRenderer.getRendertypeItemEntityTranslucentCullShader();
        boolean eyes = shader == GameRenderer.getRendertypeEyesShader();
        boolean emissive = shader == GameRenderer.getRendertypeEntityTranslucentEmissiveShader();
        if (transparent || eyes || emissive) {
            if (deferred != plan.entities().lateTransparency()) return false;
            NativeGeometryPass program;
            PackProgramSource.Prepared source;
            if (eyes || emissive) {
                if (batch.blocks() || plan.entities().eyes() == null) return false;
                program = eyes ? eyesAdditive : eyesEmissive;
                source = eyes ? plan.entities().eyes().additive() : plan.entities().eyes().emissive();
            } else if (batch.blocks()) {
                program = blockTranslucent;
                source = plan.entities().blocks().translucent();
            } else {
                program = entityTranslucent;
                source = plan.entities().translucent();
            }
            if (program == null) return false;
            try (mesh) { drawEntityBatch(mesh, batch, state, program, source, true); }
            if (eyes || emissive) emissiveDraws++;
            else if (batch.blocks()) transparentBlockDraws++;
            else transparentEntityDraws[deferred ? 1 : 0]++;
            return true;
        }
        if (deferred) return false;
        boolean solid = shader == GameRenderer.getRendertypeEntitySolidShader();
        if (!solid && shader != GameRenderer.getRendertypeEntityCutoutShader()
                && shader != GameRenderer.getRendertypeEntityCutoutNoCullShader()
                && shader != GameRenderer.getRendertypeEntityCutoutNoCullZOffsetShader()
                && shader != GameRenderer.getRendertypeEntitySmoothCutoutShader()
                && shader != GameRenderer.getRendertypeArmorCutoutNoCullShader()) return false;
        NativeGeometryPass program;
        PackProgramSource.Prepared source;
        if (batch.blocks()) {
            var blocks = plan.entities().blocks();
            program = solid ? blockSolid : blockCutout;
            source = solid ? blocks.solid() : blocks.cutout();
        } else {
            program = solid ? entitySolid : entityCutout;
            source = solid ? plan.entities().solid() : plan.entities().cutout();
        }
        try (mesh) {
            drawEntityBatch(mesh, batch, state, program, source, false);
        }
        return true;
    }

    private boolean renderGlint(MeshData mesh, MeshData.DrawState state) {
        var shader = RenderSystem.getShader();
        if (shader == null || shader != GameRenderer.getRendertypeArmorGlintShader()
                && shader != GameRenderer.getRendertypeArmorEntityGlintShader()
                && shader != GameRenderer.getRendertypeGlintShader()
                && shader != GameRenderer.getRendertypeGlintDirectShader()
                && shader != GameRenderer.getRendertypeGlintTranslucentShader()
                && shader != GameRenderer.getRendertypeEntityGlintShader()
                && shader != GameRenderer.getRendertypeEntityGlintDirectShader()) return false;
        var program = deferred ? glintLate : glintEarly;
        var stage = hand == null || !hand.drawing() ? RenderStage.ENTITIES
                : hand.late() ? RenderStage.HAND_TRANSLUCENT : RenderStage.HAND_SOLID;
        try (mesh; var scope = Renderer.getInstance().temporaryRenderTarget()) {
            uniforms.writeDraw(program, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), stage);
            bindGeometry(program, plan.entities().glint(), deferred, false);
            int topology = VRenderSystem.topology;
            try {
                VRenderSystem.setPrimitiveTopology(state.mode());
                if (glintObserver != null) glintObserver.accept(program.colorAttachment(), false);
                program.render(pipeline -> Renderer.getDrawer().draw(mesh.vertexBuffer(), state, mesh.indexBuffer()));
                glintDraws++;
                if (glintObserver != null) glintObserver.accept(program.colorAttachment(), true);
            } finally {
                VRenderSystem.topology = topology;
            }
        }
        return true;
    }

    private void drawShadowCasterBatch(MeshData mesh) {
        var state = mesh.drawState();
        if (state.format() != DefaultVertexFormat.NEW_ENTITY || state.mode() != VertexFormat.Mode.QUADS
                || state.vertexCount() < 4 || state.vertexCount() % 4 != 0) return;
        var batch = ((NativeEntityMesh.EntityIds) (Object) mesh).vsdh$getEntityIds();
        if (batch == null) return;
        var vertices = entityMesh.extend(mesh, batch.ids(), batch.blocks());
        var nativeState = new MeshData.DrawState(NativeEntityMesh.FORMAT, state.vertexCount(), state.indexCount(), state.mode(), state.indexType());
        int topology = VRenderSystem.topology;
        try {
            VRenderSystem.setPrimitiveTopology(state.mode());
            shadows.drawCaster(batch.blocks(), uniforms, textures,
                    pipeline -> Renderer.getDrawer().draw(vertices, nativeState, mesh.indexBuffer()));
        } finally {
            VRenderSystem.topology = topology;
        }
    }

    private void drawHandBatch(MeshData mesh, MeshData.DrawState state) {
        boolean late = hand.late();
        var program = late ? handLate : handSolid;
        var source = late ? plan.entities().hand().translucent() : plan.entities().hand().solid();
        var vertices = entityMesh.extendHand(mesh, hand.identifiers());
        var nativeState = new MeshData.DrawState(NativeEntityMesh.FORMAT, state.vertexCount(), state.indexCount(),
                state.mode(), state.indexType());
        uniforms.writeDraw(program, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                late ? RenderStage.HAND_TRANSLUCENT : RenderStage.HAND_SOLID);
        boolean overlay = program.requiredSamplers().contains("ofOverlay") && RenderSystem.getShaderTexture(1) == 0;
        if (overlay) Minecraft.getInstance().gameRenderer.overlayTexture().setupOverlayColor();
        try {
            bindGeometry(program, source, late, false);
            int topology = VRenderSystem.topology;
            try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
                VRenderSystem.setPrimitiveTopology(state.mode());
                program.render(pipeline -> Renderer.getDrawer().draw(vertices, nativeState, mesh.indexBuffer()));
            } finally {
                VRenderSystem.topology = topology;
            }
        } finally {
            if (overlay) Minecraft.getInstance().gameRenderer.overlayTexture().teardownOverlayColor();
        }
        handDraws[late ? 1 : 0]++;
    }

    private void drawEntityBatch(MeshData mesh, NativeEntityMesh.Batch batch, MeshData.DrawState state,
                                 NativeGeometryPass program, PackProgramSource.Prepared source, boolean transparent) {
        var vertices = entityMesh.extend(mesh, batch.ids(), batch.blocks());
        var nativeState = new MeshData.DrawState(NativeEntityMesh.FORMAT, state.vertexCount(), state.indexCount(),
                state.mode(), state.indexType());
        uniforms.writeDraw(program, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                batch.blocks() ? RenderStage.BLOCK_ENTITIES : RenderStage.ENTITIES);
        bindGeometry(program, source, deferred, false);
        int topology = VRenderSystem.topology;
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            VRenderSystem.setPrimitiveTopology(state.mode());
            program.render(pipeline -> Renderer.getDrawer().draw(vertices, nativeState, mesh.indexBuffer()));
        } finally {
            VRenderSystem.topology = topology;
        }
        if (transparent) return;
        if (!batch.blocks()) entityDraws++;
        for (int i = 0; i < state.vertexCount(); i++) {
            if (batch.ids()[i] != (short) -1) {
                if (batch.blocks()) mappedBlockEntityDraws++;
                else mappedEntityDraws++;
                break;
            }
        }
    }

    public long skippedFrames() { return skippedFrames; }
    public VulkanImage presentationImage() { return delegate.getColorAttachment(); }
    public void frameObserver(Consumer<WorldShaderSession> observer) { RenderSystem.assertOnRenderThread();frameObserver=observer; }

    public static WorldShaderSession install(WorldPackPlan plan) {
        return install(WorldPackSet.single(plan));
    }

    public static WorldShaderSession install(WorldPackSet plans) {
        RenderSystem.assertOnRenderThread();
        if (Minecraft.getInstance().level!=null || current!=null) throw new IllegalStateException("Unload world and previous shader session before installation");
        return installUnchecked(plans);
    }

    /**
     * Switches pack, profile or the standard renderer while a world is loaded ({@code next == null}: standard
     * renderer). The chunk builders stop first, so no section of the old terrain vertex contract is built or
     * uploaded; the old host is released, the new one installed for the current dimension, and every section is
     * rebuilt in the new contract. Runs on the render thread between frames (client tick).
     */
    public static void replaceInWorld(@org.jetbrains.annotations.Nullable WorldPackSet next) {
        RenderSystem.assertOnRenderThread();
        var level=Minecraft.getInstance().level;
        var worldRenderer=net.vulkanmod.render.chunk.WorldRenderer.getInstance();
        if (level==null || worldRenderer==null) throw new IllegalStateException("replaceInWorld needs a loaded world");
        if (next!=null) { // refuse unusable plans before anything is torn down
            var unique=new java.util.LinkedHashSet<>(next.dimensions().values());
            unique.add(next.overworld());
            unique.forEach(WorldShaderSession::validatePlan);
        }
        worldRenderer.getTaskDispatcher().stopThreads();
        try {
            PipelineManager.runTerrainSwitchInWorld(()->{
                var previous=current;
                if (previous!=null) previous.uninstallUnchecked();
                if (next!=null) {
                    var installed=installUnchecked(next);
                    try {
                        installed.changeWorld(level);
                    } catch (RuntimeException failure) {
                        // Never leave a host installed that shows another dimension's plan: back to the standard renderer.
                        installed.uninstallUnchecked();
                        throw failure;
                    }
                }
            });
        } finally {
            // Restarts the builders with the current contract and rebuilds every section, also after a failure.
            worldRenderer.allChanged();
        }
    }

    private static WorldShaderSession installUnchecked(WorldPackSet plans) {
        var unique=new java.util.LinkedHashSet<>(plans.dimensions().values());
        unique.add(plans.overworld());
        unique.forEach(WorldShaderSession::validatePlan);
        var materials=ShaderBlockMaterials.resolve(plans.overworld().materials());
        var session=new WorldShaderSession(Renderer.getInstance().getMainPass(),plans,materials);
        PipelineManager.configureShaderTerrain(materials);
        Renderer.getInstance().setMainPass(session);
        current=session;
        return session;
    }

    private static void validatePlan(WorldPackPlan plan) {
        WorldUniforms.validate(plan);
        // Refuse unavailable resources before creating GPU objects or changing the terrain format.
        var chainInputs=new java.util.ArrayList<java.util.Map.Entry<String,dev.vitrail.pack.target.SamplerPlan.Binding>>();
        for(var stage:plan.chain().stages().values())for(var pass:stage) {
            for(var binding:pass.inputs())chainInputs.add(java.util.Map.entry(pass.name(),binding));
            for(var compute:pass.computes())for(var binding:compute.inputs())chainInputs.add(java.util.Map.entry(compute.name(),binding));
        }
        for(var compute:plan.chain().setup())for(var binding:compute.inputs())chainInputs.add(java.util.Map.entry(compute.name(),binding));
        for(var compute:plan.chain().shadowComputes())for(var binding:compute.inputs())chainInputs.add(java.util.Map.entry(compute.name(),binding));
        for(var input:chainInputs) {
            String program=input.getKey();var binding=input.getValue();
            String sampler=binding.sampler();
            if(binding.kind()==dev.vitrail.pack.target.SamplerPlan.Kind.CUSTOM_IMAGE||binding.kind()==dev.vitrail.pack.target.SamplerPlan.Kind.COLOUR_IMAGE)continue;
            if(binding.kind()!=dev.vitrail.pack.target.SamplerPlan.Kind.COLORTEX&&!depthName(sampler)
                    &&!(shadowName(sampler)&&plan.shadow()!=null)&&!(distantDepth(sampler)&&plan.distant()!=null)&&plan.textures().resolve(program,sampler)==null
                    &&!(binding.defaulted()&&plan.textures().resolve(program,"colortex0")!=null))
                throw new IllegalArgumentException("World resource not implemented: "+program+"/"+sampler);
        }
        var geometry=new java.util.ArrayList<>(plan.terrain().values());
        if(plan.distant()!=null)geometry.addAll(plan.distant().programs());
        geometry.addAll(plan.skyPrograms().programs().values());
        geometry.addAll(plan.particles().programs().values());
        if(plan.entities()!=null)geometry.addAll(plan.entities().programs());
        for (var source:geometry) {
            var activeBindings=new java.util.HashSet<Integer>();
            source.compiled().stages().values().forEach(bytes->activeBindings.addAll(SpirvInterface.bindings(bytes)));
            for (var sampler:source.translated().samplers()) if (activeBindings.contains(source.compiled().bindings().get(sampler.name()))
                    && !java.util.Set.of("gtexture","texture","ofTexture","tex","lightmap").contains(sampler.name())
                    && !(source.translated().inputs().vulkanEntity() && sampler.name().equals("ofOverlay"))
                    && !(shadowName(sampler.name())&&plan.shadow()!=null)&&!depthName(sampler.name())
                    && !(distantDepth(sampler.name())&&plan.distant()!=null)
                    && plan.textures().resolve(source.path(),sampler.name())==null
                    && !plan.chain().images().images().stream().anyMatch(i->i.name().equals(sampler.name())||i.sampler().filter(sampler.name()::equals).isPresent())
                    && dev.vitrail.pack.model.TargetName.index(sampler.name()).stream().noneMatch(plan.chain().targets().allocated()::contains))
                throw new IllegalArgumentException("Terrain resource not implemented: "+sampler.name());
        }
        // Every storage block a program uses needs its bufferObject; refuse at install, not mid-frame.
        var programs=new java.util.ArrayList<PackProgramSource.Prepared>(geometry);
        for(var stage:plan.chain().stages().values())for(var pass:stage) {
            if(pass.draws())programs.add(pass.program());
            pass.computes().forEach(c->programs.add(c.program().asProgram()));
        }
        plan.chain().setup().forEach(c->programs.add(c.program().asProgram()));
        plan.chain().shadowComputes().forEach(c->programs.add(c.program().asProgram()));
        if(plan.shadow()!=null) {
            programs.addAll(plan.shadow().programs().values());
            if(plan.shadow().entities()!=null)programs.add(plan.shadow().entities());
            if(plan.shadow().blocks()!=null)programs.add(plan.shadow().blocks());
        }
        for(var source:programs)for(var key:source.compiled().bindings().keySet()) {
            int index=VulkanProgramCompiler.storageIndex(key);
            if(index>=0&&!plan.chain().buffers().hasIndex(index))
                throw new IllegalArgumentException("Storage block of "+source.path()+" has no bufferObject."+index);
        }
    }

    public void uninstall() {
        RenderSystem.assertOnRenderThread();
        if (Minecraft.getInstance().level!=null) throw new IllegalStateException("Unload world before uninstalling shader session");
        uninstallUnchecked();
    }

    private void uninstallUnchecked() {
        if (closed) return;
        if (current!=this || Renderer.getInstance().getMainPass()!=this) throw new IllegalStateException("Shader main-pass ownership changed");
        current=null;Renderer.getInstance().setMainPass(delegate);
        releaseGraph();closed=true;PipelineManager.restoreDefaultTerrain();
    }

    /** Called after Minecraft has selected the current fog colour and before clearing/drawing the world. */
    private void beginWorldBody(Camera camera, Matrix4f view, Matrix4f projection, float partialTick, float handPartialTick) {
        if (hand != null) hand.resetFrame();
        if (closed || active) throw new IllegalStateException("Invalid world shader frame entry");
        var position=camera.getPosition();
        if (!view.isFinite() || !projection.isFinite() || !Double.isFinite(position.x)
                || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
            if (skippedFrames++==0) org.slf4j.LoggerFactory.getLogger("VSDH").warn(
                    "Skipping shader frame with invalid camera input: viewFinite={}, projectionFinite={}, position={}",
                    view.isFinite(),projection.isFinite(),position);
            return; // Keep the established vanilla target and do not advance matrix/colour history.
        }
        VulkanImage worldDepth=delegate.getDepthAttachment(), output=delegate.getColorAttachment();
        // A graph that could not be built for this target stays on the standard renderer until the
        // target or the dimension changes, instead of failing (and crashing) every frame.
        if (failedDepth!=null && failedDepth==worldDepth) return;
        if (chain==null || borrowedDepth!=worldDepth) {
            releaseGraph();
            try {
                borrowedDepth=worldDepth;
                // depthtex2 (before the hand) and depthtex1 (before translucents) are copies of the
                // world depth; only make them when some program reads them (-0.18 ms at 1440p).
                var read=PackUsage.activeSamplers(plan);
                boolean all=Boolean.getBoolean("vsdh.allDepthSnapshots"); // diagnostics and smoke probes
                depth=new NativeWorldDepth(worldDepth,all||read.contains("depthtex2"),all||read.contains("depthtex1"));
                // Larger packs can use more of a dedicated heap without lifting the old limit on small GPUs.
                long deviceMiB = net.vulkanmod.vulkan.memory.MemoryManager.getInstance().getDeviceMemoryMB();
                long colorBudget = Math.clamp(deviceMiB / 8, 512L, 1024L) * 1024 * 1024;
                storage=new NativeStorageBuffers(plan.chain().buffers(),worldDepth.width,worldDepth.height);
                NativeStorageBuffers.install(storage);
                customImages=new NativeCustomImages(plan.chain().images(),worldDepth.width,worldDepth.height);
                NativeCustomImages.install(customImages);
                chain=new NativeShaderChain(plan.chain(),worldDepth.width,worldDepth.height,colorBudget,
                        FeedbackLoops.enabled()?FeedbackLoops.colourReads(PackUsage.programs(plan)):java.util.Map.of());
                textures=new NativeWorldTextures(plan.textures());
                if(plan.distant()!=null) {
                    distant=new NativeDistantTerrain(plan,worldDepth.width,worldDepth.height);
                    var dhRead=PackUsage.activeSamplers(plan.distant().programs());
                    // DH programs reading dhDepthTex get the opaque copy unless they are bound to the live depth.
                    boolean copyRead=false;
                    for(var program:plan.distant().programs())
                        copyRead|=!distant.liveDepth(program)&&FeedbackLoops.distantDepthLoop(PackUsage.activeSamplers(java.util.List.of(program)));
                    distant.opaqueSnapshot(read.contains("dhDepthTex1")||dhRead.contains("dhDepthTex1")||copyRead);
                }
                if(plan.shadow()!=null) {
                    shadows=new NativeWorldShadows(plan);
                    shadows.opaqueSnapshot(all||read.contains("shadowtex1")||read.contains("shadowtex1HW")||read.contains("shadow"));
                    var shadowMap=shadows;
                    chain.shadowColours(index->shadowMap.image("shadowcolor"+index));
                    if (plan.shadow().entities() != null || plan.shadow().blocks() != null) shadowCasters = new NativeShadowCasters();
                }
                for (TerrainRenderType type:TerrainRenderType.values()) {
                    var source=sources.get(type);
                    var pipeline=new NativeTerrainPass(source.path(),source.translated(),source.compiled());
                    terrain.put(type,pipeline);
                    var target=chain.geometry(bare(source.path()),false,worldDepth);
                    terrainBlend.put(type,NativeBlend.forProgram(plan.chain().targets(),bare(source.path()),target.framebuffer().getColorAttachmentCount()));
                    pipeline.prepare(target.pass());
                }
                for(var entry:plan.skyPrograms().programs().entrySet()) {
                    var source=entry.getValue();
                    String name=bare(source.path());
                    var target=chain.geometry(name,entry.getKey().afterDeferred(),worldDepth);
                    var blend=NativeBlend.forProgram(plan.chain().targets(),name,target.framebuffer().getColorAttachmentCount());
                    boolean late=entry.getKey().afterDeferred();
                    sky.put(entry.getKey(),new NativeGeometryPass(source,NativeGeometryPass.skyFormat(entry.getKey()),
                            (java.util.function.Supplier<NativeShaderChain.View>)()->chain.geometry(name,late,worldDepth),blend));
                }
                for(var entry:plan.particles().programs().entrySet()) {
                    var source=entry.getValue();
                    String name=bare(source.path());
                    var target=chain.geometry(name,entry.getKey()!=ParticlePackPlan.Pass.BEFORE,worldDepth);
                    var blend=NativeBlend.forProgram(plan.chain().targets(),name,target.framebuffer().getColorAttachmentCount());
                    boolean late=entry.getKey()!=ParticlePackPlan.Pass.BEFORE;
                    particles.put(entry.getKey(),new NativeGeometryPass(source,DefaultVertexFormat.PARTICLE,
                            (java.util.function.Supplier<NativeShaderChain.View>)()->chain.geometry(name,late,worldDepth),blend));
                }
                if (plan.entities() != null) {
                    if (plan.entities().translucent() != null) {
                        entityTranslucent = entityPass(plan.entities().translucent(), worldDepth,
                                plan.entities().lateTransparency());
                    }
                    entitySolid = entityPass(plan.entities().solid(), worldDepth, false);
                    entityCutout = entityPass(plan.entities().cutout(), worldDepth, false);
                    if (plan.entities().blocks() != null) {
                        blockSolid = entityPass(plan.entities().blocks().solid(), worldDepth, false);
                        blockCutout = entityPass(plan.entities().blocks().cutout(), worldDepth, false);
                        if (plan.entities().blocks().translucent() != null)
                            blockTranslucent = entityPass(plan.entities().blocks().translucent(), worldDepth, plan.entities().lateTransparency());
                    }
                }
                if (plan.entities() != null && plan.entities().eyes() != null) {
                    var eyes = plan.entities().eyes();
                    if (eyes.additive() != null) eyesAdditive = entityPass(eyes.additive(), worldDepth, plan.entities().lateTransparency());
                    if (eyes.emissive() != null) eyesEmissive = entityPass(eyes.emissive(), worldDepth, plan.entities().lateTransparency());
                }
                if (plan.entities() != null && plan.entities().hand() != null) {
                    handSolid = entityPass(plan.entities().hand().solid(), worldDepth, false);
                    handLate = entityPass(plan.entities().hand().translucent(), worldDepth, true);
                    hand = new NativeHandRenderer(blockMaterials, plan.items());
                }
                if (plan.entities() != null && plan.entities().glint() != null) {
                    glintEarly = geometryPass(plan.entities().glint(), DefaultVertexFormat.POSITION_TEX, worldDepth, false);
                    glintLate = geometryPass(plan.entities().glint(), DefaultVertexFormat.POSITION_TEX, worldDepth, true);
                }
                chain.prepareOutput(output);
            } catch (RuntimeException failure) {
                releaseGraph();
                failedDepth=worldDepth;
                org.slf4j.LoggerFactory.getLogger("VSDH").error("Could not build the Vulkan shaderpack graph; this world uses the standard renderer until the window size or dimension changes", failure);
                var gui=Minecraft.getInstance().gui;
                if (gui!=null) gui.getChat().addMessage(net.minecraft.network.chat.Component.translatable(
                        "vsdh.shader.graphFailed",String.valueOf(failure.getMessage())));
                return;
            }
        }
        if (hand != null) hand.beginFrame(camera, view, handPartialTick);
        uniforms.capture(camera,view,projection,partialTick,worldDepth.width,worldDepth.height);
        float[] fog=RenderSystem.getShaderFogColor();
        // ownPasses: while the main pass is unbegun (late swapchain wait) this prelude must not bind it.
        try (var scope=Renderer.getInstance().temporaryRenderTarget(true)) {
            chain.beginFrame(new dev.vitrail.pack.target.TargetDirectives.Colour(fog[0],fog[1],fog[2],1));
            storage.prepareFrame();
            customImages.beginFrame();
            depth.beginFrame();
            if(distant!=null)distant.beginFrame();
            net.vulkanmod.vulkan.queries.GpuTimestamps.mark("preWorld");
            chain.render(ShaderChainPlan.Stage.BEGIN,null,this::writeFullscreen,this::writeCompute,this::external);
            net.vulkanmod.vulkan.queries.GpuTimestamps.mark("begin");
            if(shadows!=null)shadows.render(camera,uniforms,textures,() -> {
                if (shadowCasters == null) return;
                renderingShadowCasters = true;
                try {
                    shadowCasters.draw(plan.shadow(), camera, uniforms.shadowCamera, partialTick, handPartialTick,
                            plan.directives().shadowDistance());
                } finally {
                    renderingShadowCasters = false;
                }
            });
            net.vulkanmod.vulkan.queries.GpuTimestamps.mark("shadow");
            storageBarrier();
            chain.renderShadowComputes(this::writeCompute,this::external);
            chain.render(ShaderChainPlan.Stage.PREPARE,null,this::writeFullscreen,this::writeCompute,this::external);
            net.vulkanmod.vulkan.queries.GpuTimestamps.mark("prepareTail");
        }
        deferred=false;
        scene=chain.scene("gbuffers_basic",false,worldDepth);
        if (scene.framebuffer().getColorAttachmentCount()!=1) throw new IllegalArgumentException("Vanilla fallback requires one scene colour target");
        active=true;Renderer.setMainTargetRowsBottomUp(true);rebindMainTarget();
    }

    private void beforeTerrainBody(TerrainRenderType type) {
        if (active && !deferred && (type == TerrainRenderType.TRANSLUCENT || type == TerrainRenderType.TRIPWIRE)) runDeferred();
    }

    private void renderTerrainBody(TerrainRenderType type, Consumer<GraphicsPipeline> batches) {
        if (!active) throw new IllegalStateException("No world shader frame");
        var source=sources.get(type);
        var target=chain.geometry(bare(source.path()),deferred,borrowedDepth);
        var pipeline=terrain.get(type);
        int stage=switch(type) {case SOLID->8;case CUTOUT_MIPPED->9;case CUTOUT->10;case TRANSLUCENT->17;case TRIPWIRE->18;};
        uniforms.write(pipeline,false,stage,type.alphaCutout);
        bindGeometry(pipeline,source,deferred,false);
        try (var scope=Renderer.getInstance().temporaryRenderTarget()) {
            var state=terrainBlend.get(type).apply(PipelineState.getCurrentPipelineState(target.pass(),true));
            pipeline.renderBatches(target.framebuffer(),target.pass(),state,batches);
        }
        terrainCalls++;
    }

    void writeDistant(NativeGraphicsProgram pipeline,PackProgramSource.Prepared source,boolean water) {
        uniforms.writeDistant(pipeline,water);
        bindGeometry(pipeline,source,water,false);
    }

    private void bindGeometry(NativeGraphicsProgram pipeline,PackProgramSource.Prepared source,
                              boolean afterDeferred,boolean untextured) {
        var inputs=source.translated().inputs();
        boolean dh=inputs==VertexInputs.DISTANT;
        boolean fullLight=inputs==VertexInputs.SKY || inputs.fullbright();
        for (String sampler:pipeline.requiredSamplers()) {
            var supplied=textures.image(source.path(),sampler);
            if(supplied!=null){pipeline.bindImage(sampler,supplied);continue;}
            if(depthName(sampler)){pipeline.bindImage(sampler,depth.image(sampler));continue;}
            if(distantDepth(sampler)) {
                // A DH program samples its live depth attachment only as a feedback loop (as Iris binds it).
                // Otherwise it reads the opaque copy: clear during opaque draws, the opaque terrain's
                // final depth during water.
                pipeline.bindImage(sampler,distant.image(dh&&(sampler.equals("dhDepthTex1")||!distant.liveDepth(source))?"dhDepthTex1":sampler));
                continue;
            }
            if(shadowName(sampler)) {
                String name=dev.vitrail.pack.target.SamplerPlan.withoutTranslucents(sampler,pipeline.requiredSamplers().contains("watershadow"))?"shadowtex1":sampler;
                pipeline.bindImage(sampler,shadows.image(name));continue;
            }
            var color=dev.vitrail.pack.model.TargetName.index(sampler);
            if(color.isPresent()) {
                pipeline.bindImage(sampler, chain.geometrySampler(bare(source.path()), color.getAsInt(), afterDeferred));
                continue;
            }
            if(dh&&sampler.equals("lightmap")) {
                var lightmap=com.braffolk.dhvulkan.compat.Compat.getLightmapVulkanImage();
                if(lightmap==null)throw new IllegalStateException("DH lightmap unavailable");
                pipeline.bindImage(sampler,lightmap);
                continue;
            }
            if(untextured || fullLight && sampler.equals("lightmap")) {
                pipeline.bindImage(sampler,net.vulkanmod.vulkan.texture.VTextureSelector.getWhiteTexture());
                continue;
            }
            int id=RenderSystem.getShaderTexture(sampler.equals("lightmap")?2:sampler.equals("ofOverlay")?1:0);
            var texture=GlTexture.getTexture(id);
            if (texture==null || texture.getVulkanImage()==null) throw new IllegalStateException("Missing world texture: "+sampler+" id="+id);
            pipeline.bindImage(sampler,texture.getVulkanImage());
        }
    }

    private void runDeferred() {
        net.vulkanmod.vulkan.queries.GpuTimestamps.mark("opaque");
        try (var scope=Renderer.getInstance().temporaryRenderTarget()) {
            depth.capturePreHand();
            net.vulkanmod.vulkan.queries.GpuTimestamps.mark("copyPreHand");
        }
        if (hand != null) hand.draw(false);
        net.vulkanmod.vulkan.queries.GpuTimestamps.mark("solidHand");
        try (var scope=Renderer.getInstance().temporaryRenderTarget()) {
            depth.capturePreTranslucent();
            net.vulkanmod.vulkan.queries.GpuTimestamps.mark("copyPreTranslucent");
            if(distant!=null)distant.snapshotOpaque();
            net.vulkanmod.vulkan.queries.GpuTimestamps.mark("copyDhOpaque");
            storageBarrier();
            chain.render(ShaderChainPlan.Stage.DEFERRED,null,this::writeFullscreen,this::writeCompute,this::external);
            net.vulkanmod.vulkan.queries.GpuTimestamps.mark("deferredTail");
            if(distant!=null)distant.render(this,true);
            net.vulkanmod.vulkan.queries.GpuTimestamps.mark("dhWater");
        }
        deferred=true;scene=chain.scene("gbuffers_basic",true,borrowedDepth);rebindMainTarget();
        while (!pendingEntities.isEmpty()) {
            var next = pendingEntities.removeFirst();
            try (var mesh = next.mesh()) {
                next.type().draw(mesh);
            }
        }
    }

    /** After world and DH drawing, while the world's depth is still intact. */
    /** -Dvsdh.debugFailFrame=N: throw once in frame N to exercise the frame guard (diagnostics only). */
    private static final long DEBUG_FAIL_FRAME=Long.getLong("vsdh.debugFailFrame",-1L);
    private void finishWorldBody() {
        if (!active) return;
        if (frames==DEBUG_FAIL_FRAME) throw new IllegalStateException("Requested diagnostic frame failure");
        if (!deferred) runDeferred();
        net.vulkanmod.vulkan.queries.GpuTimestamps.mark("translucent");
        if (hand != null) hand.draw(true);
        net.vulkanmod.vulkan.queries.GpuTimestamps.mark("lateHand");
        Renderer renderer=Renderer.getInstance();renderer.endRenderPass();
        active=false;Renderer.setMainTargetRowsBottomUp(false);scene=null;
        if (mainDeferred) {
            // Late swapchain wait (net.vulkanmod.vulkan.LateAcquire): nothing recorded so far uses the swapchain
            // image. COMPOSITE still draws into pack targets only, so it runs without a main target; then the
            // frame so far goes to the GPU without the image wait. FINAL is the first pass that writes the
            // swapchain image: its scope binds the main target (LOAD pass, keeps the world depth for what
            // follows) in the second command buffer, which is submitted with the wait at the end of the frame.
            // The hand order is unchanged: this runs before the vanilla hand depth clear, which lands after FINAL.
            try (var scope=renderer.temporaryRenderTarget(true)) { renderComposite(); }
            renderer.submitBeforeSwapchain();
            try (var scope=renderer.temporaryRenderTarget()) { renderFinal(); }
        } else {
            try (var scope=renderer.temporaryRenderTarget()) {
                renderComposite();
                renderFinal();
            }
        }
        bindDelegateTarget();
        if(frameObserver!=null)frameObserver.accept(this);
    }
    private void renderComposite() {
        storageBarrier();
        chain.render(ShaderChainPlan.Stage.COMPOSITE,null,this::writeFullscreen,this::writeCompute,this::external);
        // Stage tails under their own labels: a pack pass is named "composite" or "final" too.
        net.vulkanmod.vulkan.queries.GpuTimestamps.mark("compositeTail");
    }
    private void renderFinal() {
        chain.render(ShaderChainPlan.Stage.FINAL,delegate.getColorAttachment(),this::writeFullscreen,this::writeCompute,this::external);
        net.vulkanmod.vulkan.queries.GpuTimestamps.mark("finalTail");
        depth.endFrame();uniforms.commit();frames++;
    }
    private void writeFullscreen(ShaderChainPlan.Pass source,NativeFullscreenPass pipeline) {uniforms.write(pipeline,true,0,0);}
    /**
     * Geometry (terrain, entities, shadow, DH) may store into SSBOs and images without any barrier of
     * its own; one global shader-write barrier per frame phase makes those writes visible to the next.
     */
    private void storageBarrier() {
        if(plan.chain().buffers().buffers().isEmpty()&&plan.chain().images().images().isEmpty())return;
        NativeStorageBuffers.shaderWriteBarrier();
    }
    private void writeCompute(ShaderChainPlan.Compute source,NativeComputePass pipeline) {uniforms.write(pipeline,true,0,0);}
    private final java.util.Map<String,Boolean> readsWaterShadow=new java.util.HashMap<>();
    private VulkanImage external(String program,dev.vitrail.pack.target.SamplerPlan.Binding binding) {
        var supplied=textures.image(program,binding.sampler());
        if(binding.kind()==dev.vitrail.pack.target.SamplerPlan.Kind.PACK_TEXTURE&&binding.defaulted())supplied=textures.image(program,"colortex0");
        if(supplied!=null)return supplied;
        if (depthName(binding.sampler())) return depth.image(binding.sampler());
        if(distantDepth(binding.sampler())&&distant!=null)return distant.image(binding.sampler());
        if(shadowName(binding.sampler())&&shadows!=null) {
            if(binding.kind()==dev.vitrail.pack.target.SamplerPlan.Kind.SHADOW_COLOUR)return shadows.image("shadowcolor"+binding.index());
            // The plan is immutable: look the program up once, not with a stream per frame and sampler.
            boolean water=readsWaterShadow.computeIfAbsent(program,name->plan.chain().stages().values().stream().flatMap(java.util.Collection::stream)
                    .flatMap(p->java.util.stream.Stream.concat(p.draws()&&p.name().equals(name)?java.util.stream.Stream.of(p.program()):java.util.stream.Stream.empty(),
                            p.computes().stream().filter(c->c.name().equals(name)).map(c->c.program().asProgram())))
                    .flatMap(p->p.translated().samplers().stream()).anyMatch(s->s.name().equals("watershadow")));
            return shadows.image(dev.vitrail.pack.target.SamplerPlan.withoutTranslucents(binding.sampler(),water)?"shadowtex1":binding.sampler());
        }
        throw new IllegalArgumentException("Unimplemented world input "+program+"/"+binding.sampler());
    }
    private static boolean distantDepth(String s) {
        return s.equals("dhDepthTex")||s.equals("dhDepthTex0")||s.equals("dhDepthTex1");
    }
    private static boolean depthName(String s) {return DEPTH_NAMES.contains(s);}
    private static boolean shadowName(String s) {return ShadowSamplerNames.matches(s);}
    private static String bare(String path) {return path.substring(path.lastIndexOf('/')+1);}

    private NativeGeometryPass entityPass(PackProgramSource.Prepared source, VulkanImage worldDepth, boolean late) {
        return geometryPass(source, NativeEntityMesh.FORMAT, worldDepth, late);
    }

    private NativeGeometryPass geometryPass(PackProgramSource.Prepared source, VertexFormat format, VulkanImage worldDepth, boolean late) {
        String name = bare(source.path());
        var target = chain.geometry(name, late, worldDepth);
        var blend = NativeBlend.forProgram(plan.chain().targets(), name, target.framebuffer().getColorAttachmentCount());
        return new NativeGeometryPass(source, format,
                (java.util.function.Supplier<NativeShaderChain.View>) () -> chain.geometry(name, late, worldDepth), blend);
    }

    private void releaseGraph() {
        active=false;Renderer.setMainTargetRowsBottomUp(false);scene=null;uniforms.reset();
        for (int id:aliases.values()) {GlTexture.setVulkanImage(id,null);GlTexture.glDeleteTextures(id);}
        aliases.clear();
        terrain.values().forEach(NativeTerrainPass::close);terrain.clear();
        terrainBlend.clear();
        sky.values().forEach(NativeGeometryPass::close);sky.clear();
        particles.values().forEach(NativeGeometryPass::close);particles.clear();
        if (entitySolid != null) entitySolid.close();
        entitySolid = null;
        if (entityCutout != null) entityCutout.close();
        entityCutout = null;
        if (blockSolid != null) blockSolid.close();
        blockSolid = null;
        if (blockCutout != null) blockCutout.close();
        blockCutout = null;
        if (entityTranslucent != null) entityTranslucent.close();
        entityTranslucent = null;
        while (!pendingEntities.isEmpty()) pendingEntities.removeFirst().mesh().close();
        if (blockTranslucent != null) blockTranslucent.close();
        blockTranslucent = null;
        if (eyesAdditive != null) eyesAdditive.close();
        eyesAdditive = null;
        if (eyesEmissive != null) eyesEmissive.close();
        eyesEmissive = null;
        if (handSolid != null) handSolid.close();
        handSolid = null;
        if (handLate != null) handLate.close();
        handLate = null;
        if (glintEarly != null) glintEarly.close();
        glintEarly = null;
        if (glintLate != null) glintLate.close();
        glintLate = null;
        if (hand != null) hand.close();
        hand = null;
        entityMesh.close();
        if (chain!=null) chain.close();chain=null;
        if (storage!=null) storage.close();storage=null;
        if (customImages!=null) customImages.close();customImages=null;
        if(distant!=null)distant.close();distant=null;
        if (depth!=null) depth.close();depth=null;borrowedDepth=null;
        if(shadows!=null)shadows.close();shadows=null;
        if(shadowCasters!=null)shadowCasters.close();shadowCasters=null;
        if(textures!=null)textures.close();textures=null;
    }


    // ---- Frame guard: a failure while drawing ends the shader frame instead of the game. ----

    private boolean pendingRelease;
    private int frameFailures;

    /**
     * Ends the current shader frame after a failure: closes any open render pass, falls back to the
     * vanilla target for the rest of the frame and keeps this world on the standard renderer until
     * the window size or dimension changes. The graph is released at the next frame start, never
     * while this frame's command buffer may still reference it.
     */
    private void abortFrame(String where, RuntimeException failure) {
        var renderer=Renderer.getInstance();
        try { renderer.endRenderPass(); renderer.restoreGraphicsPipeline(null); } catch(RuntimeException ignored) {}
        boolean wasActive=active;
        active=false;Renderer.setMainTargetRowsBottomUp(false);scene=null;renderingShadowCasters=false;geometryStage=null;
        failedDepth=borrowedDepth;pendingRelease=true;
        if(frameFailures++<3) {
            org.slf4j.LoggerFactory.getLogger("VSDH").error("Vulkan shaderpack frame failed in {}; this world uses the standard renderer until the window size or dimension changes",where,failure);
            var gui=Minecraft.getInstance().gui;
            if(gui!=null)gui.getChat().addMessage(net.minecraft.network.chat.Component.translatable(
                    "vsdh.shader.frameFailed",where,String.valueOf(failure.getMessage())));
        }
        if(wasActive) { try { bindDelegateTarget(); } catch(RuntimeException ignored) {} }
    }

    // ---- Late swapchain wait: the default main pass stays unbegun until something needs the swapchain image. ----

    /**
     * True from {@link #begin} until the delegate's target is first bound in this frame. While it is set, no
     * command of the frame uses the swapchain image, which is what Renderer.submitBeforeSwapchain needs. Every
     * path of this host that reaches the delegate's target clears it first, so a frame that turns out not to be a
     * pack frame (invalid camera, failed graph, an abort, a mod drawing before the world) simply binds the target
     * late and goes out in one submission as before.
     */
    private boolean mainDeferred;
    @Override public boolean mainTargetDeferred() {return mainDeferred;}
    private void bindDelegateTarget() {mainDeferred=false;delegate.rebindMainTarget();}

    /** Whether the pack finished the last world frame, which is what the GUI overlays sit on. */
    private boolean packPicture;
    public boolean packPicture() {return packPicture;}

    public void beginWorld(Camera camera, Matrix4f view, Matrix4f projection, float partialTick, float handPartialTick) {
        packPicture=false;
        if(pendingRelease){releaseGraph();pendingRelease=false;}
        try { beginWorldBody(camera,view,projection,partialTick,handPartialTick); }
        catch(RuntimeException failure) { abortFrame("frame start",failure); }
    }
    public void beforeTerrain(TerrainRenderType type) {
        if(!active)return;
        try { beforeTerrainBody(type); } catch(RuntimeException failure) { abortFrame("deferred",failure); }
    }
    public void renderTerrain(TerrainRenderType type, Consumer<GraphicsPipeline> batches) {
        if(!active)return;
        try { renderTerrainBody(type,batches); } catch(RuntimeException failure) { abortFrame("terrain "+type,failure); }
    }
    public void finishWorld() {
        if(!active)return;
        try { finishWorldBody(); packPicture=true; } catch(RuntimeException failure) { abortFrame("composite",failure); }
    }
    public void flushDistantDraws() {
        if(!drawsDistantTerrain())return;
        try { flushDistantDrawsBody(); } catch(RuntimeException failure) { abortFrame("Distant Horizons",failure); }
    }
    public boolean renderWorldGeometry(VertexFormat format,VertexFormat.Mode mode,ShaderInstance shader,
                                       Matrix4f view,Matrix4f projection,Consumer<GraphicsPipeline> geometry) {
        if(!active)return false;
        try { return renderWorldGeometryBody(format,mode,shader,view,projection,geometry); }
        catch(RuntimeException failure) { abortFrame("sky/particles",failure); return true; }
    }
    public boolean queueTransparentEntity(RenderType type, MeshData mesh) {
        try { return queueTransparentEntityBody(type,mesh); }
        catch(RuntimeException failure) { abortFrame("translucent entities",failure); return false; }
    }
    public boolean renderEntityGeometry(MeshData mesh) {
        try { return renderEntityGeometryBody(mesh); }
        catch(RuntimeException failure) { abortFrame("entities",failure); return false; }
    }

    @Override public void rebindMainTarget() {
        if (!active) {bindDelegateTarget();return;}
        if (Renderer.getInstance().getBoundRenderPass()==scene.pass()) return;
        Renderer.getInstance().endRenderPass();
        try (MemoryStack stack=MemoryStack.stackPush()) {
            scene.framebuffer().beginRenderPass(Renderer.getCommandBuffer(),scene.pass(),stack);
            // The renderer scales a logical viewport to the actual world image. Supplying
            // the already scaled dimensions here would scale vanilla fallback geometry twice.
            var renderer=Renderer.getInstance();
            int width=renderer.getViewportLogicalWidth(),height=renderer.getViewportLogicalHeight();
            // GL orientation like the pack's own passes: vanilla draws into the scene unflipped (rows bottom-up).
            Renderer.setInvertedViewport(0,0,width>0?width:scene.framebuffer().getWidth(),height>0?height:scene.framebuffer().getHeight());
        }
    }
    @Override public VulkanImage getColorAttachment() {return active?scene.framebuffer().getColorAttachment():delegate.getColorAttachment();}
    @Override public VulkanImage getDepthAttachment() {return active?borrowedDepth:delegate.getDepthAttachment();}
    @Override public int getColorAttachmentGlId() {
        if (!active) return delegate.getColorAttachmentGlId();
        VulkanImage image=getColorAttachment();return aliases.computeIfAbsent(image.getId(),key->{int id=GlTexture.genTextureId();GlTexture.bindIdToImage(id,image);return id;});
    }
    @Override public int getDepthAttachmentGlId() {return delegate.getDepthAttachmentGlId();}
    @Override public void bindAsTexture() {
        if (!active) {mainDeferred=false;delegate.bindAsTexture();return;}
        Renderer.getInstance().endRenderPass();
        try (MemoryStack stack=MemoryStack.stackPush()) {getColorAttachment().transitionImageLayout(stack,Renderer.getCommandBuffer(),org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);}
        net.vulkanmod.vulkan.texture.VTextureSelector.bindTexture(getColorAttachment());
    }
    @Override public RenderPass resumeRenderPass(RenderPass pass) {return delegate.resumeRenderPass(pass);}
    @Override public void begin(VkCommandBuffer command,MemoryStack stack) {
        mainDeferred=net.vulkanmod.vulkan.LateAcquire.defersMainPass(net.vulkanmod.vulkan.LateAcquire.ENABLED,
                !closed&&Minecraft.getInstance().level!=null,failedDepth==null&&!pendingRelease)
                &&delegate.beginWithoutTarget();
        if(!mainDeferred)delegate.begin(command,stack);
    }
    @Override public void end(VkCommandBuffer command) {if(active)abortFrame("presentation",new IllegalStateException("World shader frame leaked into presentation"));mainDeferred=false;delegate.end(command);}
    @Override public void onResize() {mainDeferred=false;releaseGraph();delegate.onResize();}
    @Override public void resolveRenderScaleForGui() {if(active)abortFrame("GUI",new IllegalStateException("World shader frame leaked into GUI"));mainDeferred=false;delegate.resolveRenderScaleForGui();}
    @Override public void mainTargetBindWrite() {if(active)rebindMainTarget();else delegate.mainTargetBindWrite();}
    @Override public void mainTargetUnbindWrite() {delegate.mainTargetUnbindWrite();}
    @Override public void cleanUp() {releaseGraph();closed=true;current=null;delegate.cleanUp();}
}
