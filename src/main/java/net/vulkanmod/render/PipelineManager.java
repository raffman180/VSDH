package net.vulkanmod.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.compat.external.ExternalRenderPathSupport;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;

import java.util.function.Function;
import java.util.function.ToIntFunction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;

import static net.vulkanmod.vulkan.shader.SPIRVUtils.compileShaderAbsoluteFile;

public abstract class PipelineManager {
    private static final String shaderPath = "/assets/vulkanmod/shaders/";
    public static VertexFormat TERRAIN_VERTEX_FORMAT;

    public static void setTerrainVertexFormat(VertexFormat format) {
        TERRAIN_VERTEX_FORMAT = format;
    }

    static GraphicsPipeline terrainShaderEarlyZ, terrainShader, fastBlitPipeline, renderScaleBlitPipeline, externalLodPipeline;
    private static GraphicsPipeline glFramebufferBlitPipeline;

    private static Function<TerrainRenderType, GraphicsPipeline> shaderGetter;

    public static void init() {
        setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        createBasicPipelines();
        setDefaultShader();
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    public static void setDefaultShader() {
        setShaderGetter(renderType -> terrainShader);
    }

    /** Install before loading a world, so no mesh or worker retains the previous stride/material table. */
    public static void configureShaderTerrain(ToIntFunction<BlockState> materials) {
        java.util.Objects.requireNonNull(materials, "Shader material snapshot");
        configureTerrain(CustomVertexFormat.SHADER_TERRAIN, renderType -> new TerrainBufferBuilder(
                TerrainRenderType.getRenderType(renderType).bufferSize(), CustomVertexFormat.SHADER_TERRAIN, materials));
    }

    public static void restoreDefaultTerrain() {
        configureTerrain(CustomVertexFormat.COMPRESSED_TERRAIN, renderType -> new TerrainBufferBuilder(
                TerrainRenderType.getRenderType(renderType).bufferSize(), CustomVertexFormat.COMPRESSED_TERRAIN));
    }

    /**
     * Set only by WorldShaderSession.replaceInWorld while the chunk builders are stopped and before every section is
     * rebuilt: then no buffer of the old vertex contract can be built, uploaded or drawn with the new pipelines.
     */
    private static boolean terrainSwitchInWorld;

    public static void runTerrainSwitchInWorld(Runnable switchTerrain) {
        RenderSystem.assertOnRenderThread();
        terrainSwitchInWorld = true;
        try {
            switchTerrain.run();
        } finally {
            terrainSwitchInWorld = false;
        }
    }

    private static void configureTerrain(VertexFormat format, Function<TerrainRenderType, TerrainBufferBuilder> builders) {
        RenderSystem.assertOnRenderThread();
        if (Minecraft.getInstance().level != null && !terrainSwitchInWorld)
            throw new IllegalStateException("Unload the world before replacing the terrain vertex contract");
        GraphicsPipeline early = createPipeline("terrain", "terrain", "terrain_z", format);
        GraphicsPipeline color;
        try { color = createPipeline("terrain", "terrain", "terrain", format); }
        catch (Throwable failure) { early.scheduleCleanUp(); throw failure; }
        terrainShaderEarlyZ.scheduleCleanUp();
        terrainShader.scheduleCleanUp();
        terrainShaderEarlyZ = early;
        terrainShader = color;
        setTerrainVertexFormat(format);
        ThreadBuilderPack.setTerrainBuilderConstructor(builders);
        setDefaultShader();
    }

    private static void createBasicPipelines() {
        terrainShaderEarlyZ = createPipeline("terrain","terrain", "terrain_z", TERRAIN_VERTEX_FORMAT);
        terrainShader = createPipeline("terrain", "terrain", "terrain", TERRAIN_VERTEX_FORMAT);
        fastBlitPipeline = createPipeline("blit", "blit", "blit", CustomVertexFormat.NONE);
        glFramebufferBlitPipeline = createPipeline("blit", "blit_gl", "blit", CustomVertexFormat.NONE);
        renderScaleBlitPipeline = createPipeline("render_scale_blit", "render_scale_blit", "render_scale_blit", CustomVertexFormat.NONE);
        if (ExternalRenderPathSupport.shouldCreateExternalLodPipeline()) {
            externalLodPipeline = createPipeline("external_lod", "lod", "lod", CustomVertexFormat.EXTERNAL_LOD);
        }
    }

    private static GraphicsPipeline createPipeline(String baseName, String vertName, String fragName,VertexFormat vertexFormat) {
        String pathB = String.format("basic/%s/%s", baseName, baseName);
        String pathV = String.format("basic/%s/%s", baseName, vertName);
        String pathF = String.format("basic/%s/%s", baseName, fragName);

        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, pathB);
        pipelineBuilder.parseBindingsJSON();

        SPIRVUtils.SPIRV vertShaderSPIRV = compileShaderAbsoluteFile(String.format("%s%s.vsh", shaderPath, pathV), SPIRVUtils.ShaderKind.VERTEX_SHADER);
        SPIRVUtils.SPIRV fragShaderSPIRV = compileShaderAbsoluteFile(String.format("%s%s.fsh", shaderPath, pathF), SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        pipelineBuilder.setSPIRVs(vertShaderSPIRV, fragShaderSPIRV);

        return pipelineBuilder.createGraphicsPipeline();
    }

    public static GraphicsPipeline getTerrainShader(TerrainRenderType renderType) {
        return shaderGetter.apply(renderType);
    }

    public static void setShaderGetter(Function<TerrainRenderType, GraphicsPipeline> consumer) {
        shaderGetter = consumer;
    }

    public static GraphicsPipeline getTerrainDirectShader(RenderType renderType) {
        return terrainShader;
    }

    public static GraphicsPipeline getTerrainIndirectShader(RenderType renderType) {
        return terrainShaderEarlyZ;
    }

    public static GraphicsPipeline getFastBlitPipeline() { return fastBlitPipeline; }

    public static GraphicsPipeline getGlFramebufferBlitPipeline() { return glFramebufferBlitPipeline; }

    public static GraphicsPipeline getRenderScaleBlitPipeline() { return renderScaleBlitPipeline; }

    public static GraphicsPipeline getExternalLodPipeline() { return externalLodPipeline; }

    public static void destroyPipelines() {
        terrainShaderEarlyZ.cleanUp();
        terrainShader.cleanUp();
        fastBlitPipeline.cleanUp();
        glFramebufferBlitPipeline.cleanUp();
        renderScaleBlitPipeline.cleanUp();
        if (externalLodPipeline != null) {
            externalLodPipeline.cleanUp();
        }
    }
}
