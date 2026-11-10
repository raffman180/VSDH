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
    private NativeGeometryPass entitySolid, entityCutout, blockSolid, blockCutout;
    private int entityMaterial = NO_ENTITY_CAPTURE;
    private long entityDraws, mappedEntityDraws, mappedBlockEntityDraws;
    private final Map<Long,Integer> aliases=new HashMap<>();
    private NativeShaderChain chain;
    private NativeWorldDepth depth;
    private NativeWorldShadows shadows;
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
        uniforms=new WorldUniforms(next);
        entityMaterials.clear();
        if (next.entities()!=null) next.entities().materials().forEach((name,id)->entityMaterials.put(ResourceLocation.parse(name),id));
        for(var type:TerrainRenderType.values())
            sources.put(type,next.terrain().get(type.name().toLowerCase(java.util.Locale.ROOT)));
    }

    public void changeWorld(net.minecraft.client.multiplayer.ClientLevel level) {
        RenderSystem.assertOnRenderThread();
        releaseGraph();
        if(level!=null) {
            var next=plans.forDimension(level.dimension().location().toString());
            if(next!=plan)selectPlan(next);
        }
    }

    public String dimensionFolder() { return plan.chain().targets().place(); }
    public static WorldShaderSession current() { return current; }
    public boolean active() { return active; }
    public long framesCompleted() { return frames; }
    public long terrainCalls() { return terrainCalls; }
    public NativeShaderChain chain() { return chain; }
    public NativeWorldShadows shadows() {return shadows;}
    public NativeDistantTerrain distant() {return distant;}
    public boolean drawsDistantTerrain() {return active && distant!=null;}
    public void captureDistant(com.braffolk.dhvulkan.core.data.RenderUniforms frame) {
        uniforms.captureDistant(frame);
    }
    public void flushDistantDraws() {
        if (!drawsDistantTerrain()) throw new IllegalStateException("DH callback left its world frame");
        if (deferred && distant.hasOpaqueDraws())
            throw new IllegalStateException("DH opaque terrain arrived after deferred lighting");
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            distant.render(this, deferred);
        }
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
            case CUSTOM_SKY->true;
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

    public boolean renderWorldGeometry(VertexFormat format,VertexFormat.Mode mode,ShaderInstance shader,
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
        if (active && !deferred && plan.entities() != null) {
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            entityMaterial = entityMaterials.getOrDefault(key, -1) & 0xffff;
        } else {
            entityMaterial = NO_ENTITY_CAPTURE;
        }
        return previous;
    }

    public int beginBlockEntity(BlockEntity entity) {
        int previous = entityMaterial;
        entityMaterial = active && !deferred && blockSolid != null
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

    public boolean renderOpaqueEntities(MeshData mesh) {
        if (!active || deferred || entitySolid == null
                || net.vulkanmod.compat.sable.render.SublevelRenderScope.current().isPresent()) return false;
        var state = mesh.drawState();
        if (state.format() != DefaultVertexFormat.NEW_ENTITY || state.mode() != VertexFormat.Mode.QUADS
                || state.vertexCount() < 4 || state.vertexCount() % 4 != 0) return false;
        var batch = ((NativeEntityMesh.EntityIds) (Object) mesh).vsdh$getEntityIds();
        if (batch == null || (batch.blocks() && blockSolid == null)) return false;

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return false;
        boolean solid = shader == GameRenderer.getRendertypeEntitySolidShader();
        if (!solid && shader != GameRenderer.getRendertypeEntityCutoutShader()
                && shader != GameRenderer.getRendertypeEntityCutoutNoCullShader()
                && shader != GameRenderer.getRendertypeEntityCutoutNoCullZOffsetShader()) return false;
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
            drawEntityBatch(mesh, batch, state, program, source);
        }
        return true;
    }

    private void drawEntityBatch(MeshData mesh, NativeEntityMesh.Batch batch, MeshData.DrawState state,
                                 NativeGeometryPass program, PackProgramSource.Prepared source) {
        var vertices = entityMesh.extend(mesh, batch.ids(), batch.blocks());
        var nativeState = new MeshData.DrawState(NativeEntityMesh.FORMAT, state.vertexCount(), state.indexCount(),
                state.mode(), state.indexType());
        uniforms.writeDraw(program, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                batch.blocks() ? RenderStage.BLOCK_ENTITIES : RenderStage.ENTITIES);
        bindGeometry(program, source, false, false);
        int topology = VRenderSystem.topology;
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            VRenderSystem.setPrimitiveTopology(state.mode());
            program.render(pipeline -> Renderer.getDrawer().draw(vertices, nativeState, mesh.indexBuffer()));
        } finally {
            VRenderSystem.topology = topology;
        }
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
        for(var stage:plan.chain().stages().values())for(var pass:stage)for(var binding:pass.inputs()) {
            String sampler=binding.sampler();
            if(binding.kind()!=dev.vitrail.pack.target.SamplerPlan.Kind.COLORTEX&&!depthName(sampler)
                    &&!(shadowName(sampler)&&plan.shadow()!=null)&&!(distantDepth(sampler)&&plan.distant()!=null)&&plan.textures().resolve(pass.name(),sampler)==null
                    &&!(binding.defaulted()&&plan.textures().resolve(pass.name(),"colortex0")!=null))
                throw new IllegalArgumentException("World resource not implemented: "+pass.name()+"/"+sampler);
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
                    && !java.util.Set.of("gtexture","texture","tex","lightmap").contains(sampler.name())
                    && !(source.translated().inputs()==VertexInputs.VULKAN_ENTITY && sampler.name().equals("ofOverlay"))
                    && !(shadowName(sampler.name())&&plan.shadow()!=null)&&!depthName(sampler.name())
                    && !(distantDepth(sampler.name())&&plan.distant()!=null)
                    && plan.textures().resolve(source.path(),sampler.name())==null
                    && dev.vitrail.pack.model.TargetName.index(sampler.name()).stream().noneMatch(plan.chain().targets().allocated()::contains))
                throw new IllegalArgumentException("Terrain resource not implemented: "+sampler.name());
        }
    }

    public void uninstall() {
        RenderSystem.assertOnRenderThread();
        if (Minecraft.getInstance().level!=null) throw new IllegalStateException("Unload world before uninstalling shader session");
        if (closed) return;
        if (current!=this || Renderer.getInstance().getMainPass()!=this) throw new IllegalStateException("Shader main-pass ownership changed");
        current=null;Renderer.getInstance().setMainPass(delegate);
        releaseGraph();closed=true;PipelineManager.restoreDefaultTerrain();
    }

    /** Called after Minecraft has selected the current fog colour and before clearing/drawing the world. */
    public void beginWorld(Camera camera, Matrix4f view, Matrix4f projection, float partialTick) {
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
        if (chain==null || borrowedDepth!=worldDepth) {
            releaseGraph();
            try {
                borrowedDepth=worldDepth;
                depth=new NativeWorldDepth(worldDepth,true,true);
                chain=new NativeShaderChain(plan.chain(),worldDepth.width,worldDepth.height,512L*1024*1024);
                textures=new NativeWorldTextures(plan.textures());
                if(plan.distant()!=null)distant=new NativeDistantTerrain(plan,worldDepth.width,worldDepth.height);
                if(plan.shadow()!=null)shadows=new NativeWorldShadows(plan);
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
                    var target=chain.geometry(name,false,worldDepth);
                    var blend=NativeBlend.forProgram(plan.chain().targets(),name,target.framebuffer().getColorAttachmentCount());
                    sky.put(entry.getKey(),new NativeGeometryPass(source,NativeGeometryPass.skyFormat(entry.getKey()),target,blend));
                }
                for(var entry:plan.particles().programs().entrySet()) {
                    var source=entry.getValue();
                    String name=bare(source.path());
                    var target=chain.geometry(name,entry.getKey()!=ParticlePackPlan.Pass.BEFORE,worldDepth);
                    var blend=NativeBlend.forProgram(plan.chain().targets(),name,target.framebuffer().getColorAttachmentCount());
                    particles.put(entry.getKey(),new NativeGeometryPass(source,DefaultVertexFormat.PARTICLE,target,blend));
                }
                if (plan.entities() != null) {
                    entitySolid = entityPass(plan.entities().solid(), worldDepth);
                    entityCutout = entityPass(plan.entities().cutout(), worldDepth);
                    if (plan.entities().blocks() != null) {
                        blockSolid = entityPass(plan.entities().blocks().solid(), worldDepth);
                        blockCutout = entityPass(plan.entities().blocks().cutout(), worldDepth);
                    }
                }
                chain.prepareOutput(output);
            } catch (Throwable failure) {releaseGraph();throw failure;}
        }
        uniforms.capture(camera,view,projection,partialTick,worldDepth.width,worldDepth.height);
        float[] fog=RenderSystem.getShaderFogColor();
        try (var scope=Renderer.getInstance().temporaryRenderTarget()) {
            chain.beginFrame(new dev.vitrail.pack.target.TargetDirectives.Colour(fog[0],fog[1],fog[2],1));
            depth.beginFrame();
            if(distant!=null)distant.beginFrame();
            chain.render(ShaderChainPlan.Stage.BEGIN,null,this::writeFullscreen,this::external);
            if(shadows!=null)shadows.render(camera,uniforms,textures);
            chain.render(ShaderChainPlan.Stage.PREPARE,null,this::writeFullscreen,this::external);
        }
        deferred=false;
        scene=chain.scene("gbuffers_basic",false,worldDepth);
        if (scene.framebuffer().getColorAttachmentCount()!=1) throw new IllegalArgumentException("Vanilla fallback requires one scene colour target");
        active=true;rebindMainTarget();
    }

    public void renderTerrain(TerrainRenderType type, Consumer<GraphicsPipeline> batches) {
        if (!active) throw new IllegalStateException("No world shader frame");
        if ((type==TerrainRenderType.TRANSLUCENT || type==TerrainRenderType.TRIPWIRE) && !deferred) runDeferred();
        var source=sources.get(type);
        var target=chain.geometry(bare(source.path()),deferred,borrowedDepth);
        var pipeline=terrain.get(type);
        int stage=switch(type) {case SOLID->8;case CUTOUT_MIPPED->9;case CUTOUT->10;case TRANSLUCENT->17;case TRIPWIRE->18;};
        uniforms.write(pipeline,false,stage,type.alphaCutout);
        bindGeometry(pipeline,source,deferred,false);
        try (var scope=Renderer.getInstance().temporaryRenderTarget()) {
            var state=terrainBlend.get(type).apply(PipelineState.getCurrentPipelineState(target.pass()));
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
        boolean fullLight=inputs==VertexInputs.SKY;
        for (String sampler:pipeline.requiredSamplers()) {
            var supplied=textures.image(source.path(),sampler);
            if(supplied!=null){pipeline.bindImage(sampler,supplied);continue;}
            if(depthName(sampler)){pipeline.bindImage(sampler,depth.image(sampler));continue;}
            if(distantDepth(sampler)) {
                // A DH program cannot sample its live depth attachment. During opaque draws the
                // snapshot is clear, and during water it holds the opaque terrain's final depth.
                pipeline.bindImage(sampler,distant.image(dh?"dhDepthTex1":sampler));
                continue;
            }
            if(shadowName(sampler)) {
                String name=dev.vitrail.pack.target.SamplerPlan.withoutTranslucents(sampler,pipeline.requiredSamplers().contains("watershadow"))?"shadowtex1":sampler;
                pipeline.bindImage(sampler,shadows.image(name));continue;
            }
            var color=dev.vitrail.pack.model.TargetName.index(sampler);
            if(color.isPresent()) {
                var step=(afterDeferred?plan.chain().targets().schedule().stepAfterDeferred(bare(source.path())):plan.chain().targets().schedule().step(bare(source.path()))).orElseThrow();
                pipeline.bindImage(sampler,chain.targets().image(color.getAsInt(),step.read(color.getAsInt())));continue;
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
        try (var scope=Renderer.getInstance().temporaryRenderTarget()) {
            // Hand reordering is not implemented yet; the two pre-translucent snapshots coincide.
            depth.capturePreHand();depth.capturePreTranslucent();
            if(distant!=null)distant.snapshotOpaque();
            chain.render(ShaderChainPlan.Stage.DEFERRED,null,this::writeFullscreen,this::external);
            if(distant!=null)distant.render(this,true);
        }
        deferred=true;scene=chain.scene("gbuffers_basic",true,borrowedDepth);rebindMainTarget();
    }

    /** After world and DH drawing, while the world's depth is still intact. */
    public void finishWorld() {
        if (!active) return;
        if (!deferred) runDeferred();
        Renderer renderer=Renderer.getInstance();renderer.endRenderPass();
        active=false;scene=null;
        try (var scope=renderer.temporaryRenderTarget()) {
            chain.render(ShaderChainPlan.Stage.COMPOSITE,null,this::writeFullscreen,this::external);
            chain.render(ShaderChainPlan.Stage.FINAL,delegate.getColorAttachment(),this::writeFullscreen,this::external);
            depth.endFrame();uniforms.commit();frames++;
        }
        delegate.rebindMainTarget();
        if(frameObserver!=null)frameObserver.accept(this);
    }
    private void writeFullscreen(ShaderChainPlan.Pass source,NativeFullscreenPass pipeline) {uniforms.write(pipeline,true,0,0);}
    private VulkanImage external(String program,dev.vitrail.pack.target.SamplerPlan.Binding binding) {
        var supplied=textures.image(program,binding.sampler());
        if(binding.kind()==dev.vitrail.pack.target.SamplerPlan.Kind.PACK_TEXTURE&&binding.defaulted())supplied=textures.image(program,"colortex0");
        if(supplied!=null)return supplied;
        if (depthName(binding.sampler())) return depth.image(binding.sampler());
        if(distantDepth(binding.sampler())&&distant!=null)return distant.image(binding.sampler());
        if(shadowName(binding.sampler())&&shadows!=null) {
            if(binding.kind()==dev.vitrail.pack.target.SamplerPlan.Kind.SHADOW_COLOUR)return shadows.image("shadowcolor"+binding.index());
            boolean water=plan.chain().stages().values().stream().flatMap(java.util.Collection::stream)
                    .filter(p->p.name().equals(program)).flatMap(p->p.program().translated().samplers().stream()).anyMatch(s->s.name().equals("watershadow"));
            return shadows.image(dev.vitrail.pack.target.SamplerPlan.withoutTranslucents(binding.sampler(),water)?"shadowtex1":binding.sampler());
        }
        throw new IllegalArgumentException("Unimplemented world input "+program+"/"+binding.sampler());
    }
    private static boolean distantDepth(String s) {
        return s.equals("dhDepthTex")||s.equals("dhDepthTex0")||s.equals("dhDepthTex1");
    }
    private static boolean depthName(String s) {return DEPTH_NAMES.contains(s);}
    private static boolean shadowName(String s) {return s.equals("shadow")||s.equals("watershadow")||s.matches("shadowtex[01](?:HW)?|shadowcolor[0-7]?");}
    private static String bare(String path) {return path.substring(path.lastIndexOf('/')+1);}

    private NativeGeometryPass entityPass(PackProgramSource.Prepared source, VulkanImage worldDepth) {
        String name = bare(source.path());
        var target = chain.geometry(name, false, worldDepth);
        var blend = NativeBlend.forProgram(plan.chain().targets(), name, target.framebuffer().getColorAttachmentCount());
        return new NativeGeometryPass(source, NativeEntityMesh.FORMAT, target, blend);
    }

    private void releaseGraph() {
        active=false;scene=null;uniforms.reset();
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
        entityMesh.close();
        if (chain!=null) chain.close();chain=null;
        if(distant!=null)distant.close();distant=null;
        if (depth!=null) depth.close();depth=null;borrowedDepth=null;
        if(shadows!=null)shadows.close();shadows=null;
        if(textures!=null)textures.close();textures=null;
    }

    @Override public void rebindMainTarget() {
        if (!active) {delegate.rebindMainTarget();return;}
        if (Renderer.getInstance().getBoundRenderPass()==scene.pass()) return;
        Renderer.getInstance().endRenderPass();
        try (MemoryStack stack=MemoryStack.stackPush()) {
            scene.framebuffer().beginRenderPass(Renderer.getCommandBuffer(),scene.pass(),stack);
            // The renderer scales a logical viewport to the actual world image. Supplying
            // the already scaled dimensions here would scale vanilla fallback geometry twice.
            var renderer=Renderer.getInstance();
            int width=renderer.getViewportLogicalWidth(),height=renderer.getViewportLogicalHeight();
            Renderer.setViewport(0,0,width>0?width:scene.framebuffer().getWidth(),height>0?height:scene.framebuffer().getHeight());
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
        if (!active) {delegate.bindAsTexture();return;}
        Renderer.getInstance().endRenderPass();
        try (MemoryStack stack=MemoryStack.stackPush()) {getColorAttachment().transitionImageLayout(stack,Renderer.getCommandBuffer(),org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);}
        net.vulkanmod.vulkan.texture.VTextureSelector.bindTexture(getColorAttachment());
    }
    @Override public RenderPass resumeRenderPass(RenderPass pass) {return delegate.resumeRenderPass(pass);}
    @Override public void begin(VkCommandBuffer command,MemoryStack stack) {delegate.begin(command,stack);}
    @Override public void end(VkCommandBuffer command) {if(active) throw new IllegalStateException("World shader frame leaked into presentation");delegate.end(command);}
    @Override public void onResize() {releaseGraph();delegate.onResize();}
    @Override public void resolveRenderScaleForGui() {if(active)throw new IllegalStateException("World shader frame leaked into GUI");delegate.resolveRenderScaleForGui();}
    @Override public void mainTargetBindWrite() {if(active)rebindMainTarget();else delegate.mainTargetBindWrite();}
    @Override public void mainTargetUnbindWrite() {delegate.mainTargetUnbindWrite();}
    @Override public void cleanUp() {releaseGraph();closed=true;current=null;delegate.cleanUp();}
}
