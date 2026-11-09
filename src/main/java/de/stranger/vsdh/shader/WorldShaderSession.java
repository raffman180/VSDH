package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
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

/** Development world host. Installation is explicit, prepared off-thread, and requires an unloaded world. */
public final class WorldShaderSession implements MainPass {
    private static WorldShaderSession current;
    private static final java.util.Set<String> DEPTH_NAMES=java.util.Set.of("depthtex0","depthtex1","depthtex2","gdepthtex");
    private final MainPass delegate;
    private final WorldPackPlan plan;
    private final WorldUniforms uniforms;
    private final Map<TerrainRenderType,NativeTerrainPass> terrain=new EnumMap<>(TerrainRenderType.class);
    private final Map<TerrainRenderType,PackProgramSource.Prepared> sources=new EnumMap<>(TerrainRenderType.class);
    private final Map<Long,Integer> aliases=new HashMap<>();
    private NativeShaderChain chain;
    private NativeWorldDepth depth;
    private NativeWorldShadows shadows;
    private NativeWorldTextures textures;
    private VulkanImage borrowedDepth;
    private NativeShaderChain.View scene;
    private boolean active,deferred,closed;
    private long frames,terrainCalls;
    private long skippedFrames;
    private Consumer<WorldShaderSession> frameObserver;

    private WorldShaderSession(MainPass delegate, WorldPackPlan plan) {
        this.delegate=delegate;this.plan=plan;this.uniforms=new WorldUniforms(plan);
        for(var type:TerrainRenderType.values())sources.put(type,plan.terrain().get(type.name().toLowerCase(java.util.Locale.ROOT)));
    }
    public static WorldShaderSession current() { return current; }
    public boolean active() { return active; }
    public long framesCompleted() { return frames; }
    public long terrainCalls() { return terrainCalls; }
    public NativeShaderChain chain() { return chain; }
    public NativeWorldShadows shadows() {return shadows;}
    public net.minecraft.client.CloudStatus clouds(net.minecraft.client.CloudStatus userSetting) {
        if(!active)return userSetting;
        return switch(plan.sky().clouds()) {
            case DEFAULT->userSetting;case OFF->net.minecraft.client.CloudStatus.OFF;
            case FAST->net.minecraft.client.CloudStatus.FAST;case FANCY->net.minecraft.client.CloudStatus.FANCY;
        };
    }
    public long skippedFrames() { return skippedFrames; }
    public VulkanImage presentationImage() { return delegate.getColorAttachment(); }
    public void frameObserver(Consumer<WorldShaderSession> observer) { RenderSystem.assertOnRenderThread();frameObserver=observer; }

    public static WorldShaderSession install(WorldPackPlan plan) {
        RenderSystem.assertOnRenderThread();
        if (Minecraft.getInstance().level!=null || current!=null) throw new IllegalStateException("Unload world and previous shader session before installation");
        WorldUniforms.validate(plan);
        // Refuse unavailable resources before creating GPU objects or changing the terrain format.
        for(var stage:plan.chain().stages().values())for(var pass:stage)for(var binding:pass.inputs()) {
            String sampler=binding.sampler();
            if(binding.kind()!=dev.vitrail.pack.target.SamplerPlan.Kind.COLORTEX&&!depthName(sampler)
                    &&!(shadowName(sampler)&&plan.shadow()!=null)&&plan.textures().resolve(pass.name(),sampler)==null
                    &&!(binding.defaulted()&&plan.textures().resolve(pass.name(),"colortex0")!=null))
                throw new IllegalArgumentException("World resource not implemented: "+pass.name()+"/"+sampler);
        }
        for (var source:plan.terrain().values()) {
            var activeBindings=new java.util.HashSet<Integer>();
            source.compiled().stages().values().forEach(bytes->activeBindings.addAll(SpirvInterface.bindings(bytes)));
            for (var sampler:source.translated().samplers()) if (activeBindings.contains(source.compiled().bindings().get(sampler.name()))
                    && !java.util.Set.of("gtexture","texture","tex","lightmap").contains(sampler.name())
                    && !(shadowName(sampler.name())&&plan.shadow()!=null)&&!depthName(sampler.name())
                    && plan.textures().resolve(source.path(),sampler.name())==null
                    && dev.vitrail.pack.model.TargetName.index(sampler.name()).stream().noneMatch(plan.chain().targets().allocated()::contains))
                throw new IllegalArgumentException("Terrain resource not implemented: "+sampler.name());
        }
        var session=new WorldShaderSession(Renderer.getInstance().getMainPass(),plan);
        PipelineManager.configureShaderTerrain(ShaderBlockMaterials.resolve(plan.materials()));
        Renderer.getInstance().setMainPass(session); current=session;
        return session;
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
                if(plan.shadow()!=null)shadows=new NativeWorldShadows(plan);
                for (TerrainRenderType type:TerrainRenderType.values()) {
                    var source=sources.get(type);
                    var pipeline=new NativeTerrainPass(source.path(),source.translated(),source.compiled());
                    terrain.put(type,pipeline);
                    var target=chain.geometry(bare(source.path()),false,worldDepth);
                    pipeline.prepare(target.pass());
                }
                chain.prepareOutput(output);
            } catch (Throwable failure) {releaseGraph();throw failure;}
        }
        uniforms.capture(camera,view,projection,partialTick,worldDepth.width,worldDepth.height);
        float[] fog=RenderSystem.getShaderFogColor();
        try (var scope=Renderer.getInstance().temporaryRenderTarget()) {
            chain.beginFrame(new dev.vitrail.pack.target.TargetDirectives.Colour(fog[0],fog[1],fog[2],1));
            depth.beginFrame();
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
        for (String sampler:pipeline.requiredSamplers()) {
            var supplied=textures.image(source.path(),sampler);
            if(supplied!=null){pipeline.bindImage(sampler,supplied);continue;}
            if(depthName(sampler)){pipeline.bindImage(sampler,depth.image(sampler));continue;}
            if(shadowName(sampler)) {
                String name=dev.vitrail.pack.target.SamplerPlan.withoutTranslucents(sampler,pipeline.requiredSamplers().contains("watershadow"))?"shadowtex1":sampler;
                pipeline.bindImage(sampler,shadows.image(name));continue;
            }
            var color=dev.vitrail.pack.model.TargetName.index(sampler);
            if(color.isPresent()) {
                var step=(deferred?plan.chain().targets().schedule().stepAfterDeferred(bare(source.path())):plan.chain().targets().schedule().step(bare(source.path()))).orElseThrow();
                pipeline.bindImage(sampler,chain.targets().image(color.getAsInt(),step.read(color.getAsInt())));continue;
            }
            int id=RenderSystem.getShaderTexture(sampler.equals("lightmap")?2:0);
            var texture=GlTexture.getTexture(id);
            if (texture==null || texture.getVulkanImage()==null) throw new IllegalStateException("Missing world texture: "+sampler+" id="+id);
            pipeline.bindImage(sampler,texture.getVulkanImage());
        }
        try (var scope=Renderer.getInstance().temporaryRenderTarget()) {
            pipeline.renderBatches(target.framebuffer(),target.pass(),PipelineState.getCurrentPipelineState(target.pass()),batches);
        }
        terrainCalls++;
    }

    private void runDeferred() {
        try (var scope=Renderer.getInstance().temporaryRenderTarget()) {
            // Hand reordering is not implemented yet; the two pre-translucent snapshots coincide.
            depth.capturePreHand();depth.capturePreTranslucent();
            chain.render(ShaderChainPlan.Stage.DEFERRED,null,this::writeFullscreen,this::external);
        }
        deferred=true;scene=chain.scene("gbuffers_basic",true,borrowedDepth);rebindMainTarget();
    }

    /** After world/DH/hand drawing, before GUI. The normal main target receives the final pass. */
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
        if(shadowName(binding.sampler())&&shadows!=null) {
            if(binding.kind()==dev.vitrail.pack.target.SamplerPlan.Kind.SHADOW_COLOUR)return shadows.image("shadowcolor"+binding.index());
            boolean water=plan.chain().stages().values().stream().flatMap(java.util.Collection::stream)
                    .filter(p->p.name().equals(program)).flatMap(p->p.program().translated().samplers().stream()).anyMatch(s->s.name().equals("watershadow"));
            return shadows.image(dev.vitrail.pack.target.SamplerPlan.withoutTranslucents(binding.sampler(),water)?"shadowtex1":binding.sampler());
        }
        throw new IllegalArgumentException("Unimplemented world input "+program+"/"+binding.sampler());
    }
    private static boolean depthName(String s) {return DEPTH_NAMES.contains(s);}
    private static boolean shadowName(String s) {return s.equals("shadow")||s.equals("watershadow")||s.matches("shadowtex[01](?:HW)?|shadowcolor[0-7]?");}
    private static String bare(String path) {return path.substring(path.lastIndexOf('/')+1);}

    public void detachWorld() { releaseGraph(); }
    private void releaseGraph() {
        active=false;scene=null;uniforms.reset();
        for (int id:aliases.values()) {GlTexture.setVulkanImage(id,null);GlTexture.glDeleteTextures(id);}
        aliases.clear();
        terrain.values().forEach(NativeTerrainPass::close);terrain.clear();
        if (chain!=null) chain.close();chain=null;
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
