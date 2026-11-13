package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.InventoryMenu;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.Map;
import java.util.TreeMap;
import static org.lwjgl.vulkan.VK10.*;
import net.vulkanmod.vulkan.texture.SamplerManager.SamplerKey;

/**
 * Owns native shadow outputs. Every frame draws the uploaded chunk buffers the light may see,
 * selected by {@link ShadowCuller} from the pack's own culling directives.
 */
public final class NativeWorldShadows implements AutoCloseable {
    private final WorldPackPlan plan;
    private final Map<Integer,VulkanImage> colors=new TreeMap<>();
    private VulkanImage depth,opaqueDepth;
    private Framebuffer framebuffer;
    private RenderPass pass;
    private NativeTerrainPass pipeline;
    private NativeGeometryPass entities, blocks;
    private long entityDraws, blockDraws;
    private java.util.function.BiConsumer<VulkanImage, Boolean> casterDepthObserver;
    private boolean initialized,closed,cullingFallbackLogged;
    private long drawCommands;
    private ShadowCuller lastCuller=ShadowCuller.none();
    private static final java.util.regex.Pattern SHADOW_COLOUR=java.util.regex.Pattern.compile("shadowcolor[0-7]");

    public NativeWorldShadows(WorldPackPlan plan) {
        RenderSystem.assertOnRenderThread();this.plan=plan;
        if(plan.shadow()==null)throw new IllegalArgumentException("Pack has no native shadow program");
        int size=plan.directives().shadowMapResolution();
        if(size<=0||size>DeviceManager.deviceProperties.limits().maxImageDimension2D())throw new IllegalArgumentException("Unsupported shadow resolution: "+size);
        if(plan.directives().shadowDepthMipmap(0)||plan.directives().shadowDepthMipmap(1))
            throw new IllegalArgumentException("Native depth shadow mipmaps are not implemented");
        var indices=new java.util.TreeSet<>(plan.shadow().outputs());indices.add(0);indices.add(1);
        var storage=plan.chain().shadowStorage();indices.addAll(storage);
        indices.addAll(sampledColours(plan));
        long bytes=Math.multiplyExact((long)size*size,8);
        for(int index:indices)bytes=Math.addExact(bytes,Math.multiplyExact((long)size*size,plan.directives().shadowColour(index).format().used().bytesPerPixel()));
        if(bytes>512L*1024*1024)throw new IllegalArgumentException("Shadow buffers exceed 512 MiB: "+bytes);
        try {
            for(int index:indices) {
                var format=plan.directives().shadowColour(index).format().used();
                VulkanTargetFormat.requireRenderable(DeviceManager.physicalDevice,format,!format.integer());
                int colourUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                if(storage.contains(index))colourUsage|=VK_IMAGE_USAGE_STORAGE_BIT; // shadowcolorimgN in a compute
                var image=VulkanImage.builder(size,size).setFormat(VulkanTargetFormat.of(format))
                        .setUsage(colourUsage)
                        .setLinearFiltering(!format.integer()).setClamp(true).createVulkanImage();
                colors.put(index,image);
            }
            int usage=VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            depth=VulkanImage.createDepthImage(VK_FORMAT_D32_SFLOAT,size,size,usage,false,true);
            opaqueDepth=VulkanImage.createDepthImage(VK_FORMAT_D32_SFLOAT,size,size,usage,false,true);
            sampler(depth,plan.directives().shadowDepthNearest(0));sampler(opaqueDepth,plan.directives().shadowDepthNearest(1));
            var attachments=plan.shadow().outputs().stream().map(colors::get).toArray(VulkanImage[]::new);
            framebuffer=Framebuffer.builder(attachments,depth).ownsAttachments(false).build();
            var builder=RenderPass.builder(framebuffer).setLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD,VK_ATTACHMENT_STORE_OP_STORE);pass=builder.build();
            var source=plan.shadow().programs().get("solid");
            pipeline=new NativeTerrainPass(source.path(),source.translated(),source.compiled());pipeline.prepare(pass);
            entities = casterPass(plan.shadow().entities());
            blocks = casterPass(plan.shadow().blocks());
        } catch(Throwable failure) {close();throw failure;}
    }
    private static void sampler(VulkanImage image,boolean nearest) {
        int filter=nearest?9728:9729;
        image.updateTextureSampler(new SamplerKey(filter,filter,33071,33071,33071,0,0,0,0,515));
    }
    private NativeGeometryPass casterPass(PackProgramSource.Prepared source) {
        if (source == null) return null;
        String name = source.path().substring(source.path().lastIndexOf('/') + 1);
        var blend = NativeBlend.forProgram(plan.chain().targets(), name, framebuffer.getColorAttachmentCount());
        return new NativeGeometryPass(source, NativeEntityMesh.FORMAT, new NativeShaderChain.View(framebuffer, pass), blend);
    }

    public void casterDepthObserver(java.util.function.BiConsumer<VulkanImage, Boolean> observer) {
        casterDepthObserver = observer;
    }

    public long entityDraws() { return entityDraws; }
    public long blockDraws() { return blockDraws; }

    void drawCaster(boolean block, WorldUniforms uniforms, NativeWorldTextures textures,
                    java.util.function.Consumer<net.vulkanmod.vulkan.shader.GraphicsPipeline> geometry) {
        var program = block ? blocks : entities;
        if (program == null) return;
        var source = block ? plan.shadow().blocks() : plan.shadow().entities();
        var overlay = Minecraft.getInstance().gameRenderer.overlayTexture();
        boolean useOverlay = program.requiredSamplers().contains("ofOverlay") && RenderSystem.getShaderTexture(1) == 0;
        if (useOverlay) overlay.setupOverlayColor();
        try {
            for (String name : program.requiredSamplers()) {
                var supplied = textures.image(source.path(), name);
                if (supplied != null) {
                    program.bindImage(name, supplied);
                    continue;
                }
                var colour = readableColour(name);
                if (colour != null) {
                    program.bindImage(name, colour);
                    continue;
                }
                int slot = name.equals("lightmap") ? 2 : name.equals("ofOverlay") ? 1 : 0;
                var bound = GlTexture.getTexture(RenderSystem.getShaderTexture(slot));
                if (bound == null || bound.getVulkanImage() == null)
                    throw new IllegalStateException("Missing shadow caster texture: " + name);
                program.bindImage(name, bound.getVulkanImage());
            }
            uniforms.writeShadowCaster(program, block);
            program.render(geometry);
            if (block) blockDraws++;
            else entityDraws++;
        } finally {
            if (useOverlay) overlay.teardownOverlayColor();
        }
    }

    public void render(Camera camera,WorldUniforms uniforms,NativeWorldTextures textures,Runnable casters) {
        RenderSystem.assertOnRenderThread();if(closed)throw new IllegalStateException("Closed shadow pass");
        var world=WorldRenderer.getInstance();
        var cameraPosition=camera.getPosition();
        var culler=culler(uniforms);
        world.prepareShadowDraws(culler.shape()==ShadowCuller.Shape.NONE?null:culler::visible,cameraPosition.x,cameraPosition.y,cameraPosition.z);
        lastCuller=culler;
        int previousTexture=RenderSystem.getShaderTexture(0);
        int previousOverlay=RenderSystem.getShaderTexture(1), previousLightmap=RenderSystem.getShaderTexture(2);
        int atlas=Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId();
        RenderSystem.setShaderTexture(0,atlas);
        try(var scope=Renderer.getInstance().temporaryRenderTarget()) {
            for(var entry:colors.entrySet()) {
                var setting=plan.directives().shadowColour(entry.getKey());
                if(!initialized||setting.clear())NativeImageOps.clear(entry.getValue(),setting.format().used(),setting.clearColour());
            }
            NativeImageOps.clearDepth(depth,1,0);initialized=true;
            for(String name:pipeline.requiredSamplers()) {
                var supplied=textures.image(plan.shadow().programs().get("solid").path(),name);
                if(supplied!=null){pipeline.bindImage(name,supplied);continue;}
                var colour=readableColour(name);
                if(colour!=null){pipeline.bindImage(name,colour);continue;}
                int texture=switch(name) {case "tex","gtexture","texture","ofTexture"->atlas;case "lightmap"->RenderSystem.getShaderTexture(2);default->throw new IllegalArgumentException("Unsupported shadow input: "+name);};
                var bound=GlTexture.getTexture(texture);
                if(bound==null||bound.getVulkanImage()==null)throw new IllegalStateException("Missing real shadow texture: "+name);
                pipeline.bindImage(name,bound.getVulkanImage());
            }
            uniforms.writeShadow(pipeline);
            int assembly=PipelineState.AssemblyRasterState.encode(false,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,VK_POLYGON_MODE_FILL);
            int depthState=PipelineState.DepthState.DEPTH_TEST_BIT|PipelineState.DepthState.DEPTH_MASK_BIT|PipelineState.DepthState.encodeDepthFun(515);
            var state=new PipelineState(assembly,0,depthState,0,0,15,pass);
            var position=camera.getPosition();
            if (plan.shadow().casters().terrain()) {
                for (TerrainRenderType type : java.util.List.of(TerrainRenderType.SOLID, TerrainRenderType.CUTOUT_MIPPED, TerrainRenderType.CUTOUT))
                    pipeline.renderBatches(framebuffer,pass,state,program->drawCommands+=world.drawShadowSectionBatches(program,type,position.x,position.y,position.z));
            }
            if (casterDepthObserver != null) casterDepthObserver.accept(depth, false);
            casters.run();
            if (casterDepthObserver != null) casterDepthObserver.accept(depth, true);
            if (opaqueSnapshot) NativeImageOps.copyBase(depth, opaqueDepth);
            if (plan.shadow().casters().translucent())
                pipeline.renderBatches(framebuffer,pass,state,program->drawCommands+=world.drawShadowSectionBatches(program,TerrainRenderType.TRANSLUCENT,position.x,position.y,position.z));
        } finally {
            RenderSystem.setShaderTexture(0,previousTexture);
            RenderSystem.setShaderTexture(1,previousOverlay);
            RenderSystem.setShaderTexture(2,previousLightmap);
        }
    }
    public long drawCommands() {return drawCommands;}
    /** Shape chosen for the last light pass; NONE before the first frame. */
    public ShadowCuller culler() {return lastCuller;}

    /** Same caster selection Iris gives the pack; falls back to every section on invalid inputs. */
    private ShadowCuller culler(WorldUniforms uniforms) {
        var shadow=plan.shadow();var directives=plan.directives();
        var clip=new org.joml.Matrix4f(uniforms.matrices.projection).mul(uniforms.matrices.view);
        var towardLight=uniforms.shadowCamera.inverseView.transformDirection(new org.joml.Vector3f(0,0,1));
        float renderDistance=Minecraft.getInstance().options.getEffectiveRenderDistance()*16f;
        try {
            return ShadowCuller.create(shadow.cull(),shadow.voxelizes(),directives.shadowDistance(),directives.shadowDistanceRenderMul(),
                    directives.voxelDistance(),renderDistance,clip,towardLight);
        } catch(IllegalArgumentException invalid) {
            if(!cullingFallbackLogged) {
                cullingFallbackLogged=true;
                org.slf4j.LoggerFactory.getLogger("VSDH-Shaders").warn("Drawing every loaded section into the shadow map: {}",invalid.getMessage());
            }
            return ShadowCuller.none();
        }
    }
    /**
     * A shadowcolorN the shadow programs read while drawing (Rethinking Voxels' waving water reads
     * shadowcolor2, which its shadowcomp2 fills). Iris binds the shadow targets to the shadow
     * programs as well; a target the same pass writes would be a feedback loop and is refused.
     */
    private VulkanImage readableColour(String name) {
        if(!name.equals("shadowcolor")&&!SHADOW_COLOUR.matcher(name).matches())return null;
        int index=name.equals("shadowcolor")?0:Integer.parseInt(name.substring(11));
        if(plan.shadow().outputs().contains(index))
            throw new IllegalArgumentException("Shadow program reads the shadow target it writes: "+name);
        var image=colors.get(index);
        if(image==null)throw new IllegalArgumentException("Shadow color not allocated: "+name);
        return image;
    }

    /** Every shadowcolorN some program samples, so a target only read and image-written still exists. */
    static java.util.Set<Integer> sampledColours(WorldPackPlan plan) {
        var programs=new java.util.ArrayList<PackProgramSource.Prepared>(plan.terrain().values());
        if(plan.distant()!=null)programs.addAll(plan.distant().programs());
        programs.addAll(plan.skyPrograms().programs().values());
        programs.addAll(plan.particles().programs().values());
        if(plan.entities()!=null)programs.addAll(plan.entities().programs());
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
        var indices=new java.util.TreeSet<Integer>();
        for(var source:programs) {
            var active=new java.util.HashSet<Integer>();
            source.compiled().stages().values().forEach(bytes->active.addAll(SpirvInterface.bindings(bytes)));
            for(var sampler:source.translated().samplers()) {
                if(!SHADOW_COLOUR.matcher(sampler.name()).matches()||!active.contains(source.compiled().bindings().get(sampler.name())))continue;
                if(plan.textures().resolve(source.path(),sampler.name())!=null)continue; // a pack texture replaces it
                indices.add(Integer.parseInt(sampler.name().substring(11)));
            }
        }
        return indices;
    }

    private boolean opaqueSnapshot=true;

    /**
     * Whether shadowtex1 (the map before translucent casters) is copied each frame. Off only when no
     * program reads it under any of its names (shadowtex1, shadowtex1HW, or shadow beside watershadow),
     * like the world depth snapshots; the image then keeps its last copy.
     */
    public void opaqueSnapshot(boolean on) {opaqueSnapshot=on;}

    public VulkanImage image(String name) {
        if(closed)throw new IllegalStateException("Closed shadow pass");
        return switch(name) {
            // The HW names are the same depth images; sampler2DShadow binds them with a compare sampler.
            case "shadowtex0","shadowtex0HW","shadow","watershadow"->depth;case "shadowtex1","shadowtex1HW"->opaqueDepth;
            case "shadowcolor","shadowcolor0"->colors.get(0);case "shadowcolor1"->colors.get(1);
            default->{
                if(!SHADOW_COLOUR.matcher(name).matches())throw new IllegalArgumentException("Unknown shadow image: "+name);
                var image=colors.get(Integer.parseInt(name.substring(11)));
                if(image==null)throw new IllegalArgumentException("Shadow color not allocated: "+name);yield image;
            }
        };
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();if(closed)return;closed=true;
        if(entities!=null)entities.close();if(blocks!=null)blocks.close();
        if(pipeline!=null)pipeline.close();if(pass!=null)pass.cleanUp();if(framebuffer!=null)framebuffer.cleanUp();
        colors.values().forEach(VulkanImage::free);colors.clear();if(depth!=null)depth.free();if(opaqueDepth!=null)opaqueDepth.free();
        WorldRenderer.getInstance().clearShadowDraws();
    }
}
