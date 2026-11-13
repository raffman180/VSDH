package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.data.RenderUniforms;
import com.braffolk.dhvulkan.core.data.VkVertexData;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.*;

/**
 * Vulkan implementation of DH 3.0's render API definition.
 * Replaces the default OpenGL renderer by binding into DH's
 * SingletonInjector via {@link #bindRenderers()}.
 *
 * Our Vulkan engine handles SSAO, fog, and composite internally
 * as post-process passes, so those renderers are stubs that delegate
 * to the core VulkanBackend at the right lifecycle points.
 */
public abstract class VkRenderApiDefinition extends AbstractDhRenderApiDefinition {

    private final VulkanBackend backend;
    private final VkMetaRenderer metaRenderer;
    private final VkTerrainRenderer terrainRenderer;
    private final VkSsaoRenderer ssaoRenderer;
    private final VkFogRenderer fogRenderer;
    private final VkFarFadeRenderer farFadeRenderer;
    private final VkVanillaFadeRenderer vanillaFadeRenderer;
    private final VkDebugWireframeRenderer debugWireframeRenderer;
    private final VkTestTriangleRenderer testTriangleRenderer;

    public VkRenderApiDefinition(VulkanBackend backend) {
        this.backend = backend;
        this.metaRenderer = new VkMetaRenderer(backend);
        this.terrainRenderer = new VkTerrainRenderer(backend);
        this.ssaoRenderer = new VkSsaoRenderer();
        this.fogRenderer = new VkFogRenderer();
        this.farFadeRenderer = new VkFarFadeRenderer();
        this.vanillaFadeRenderer = new VkVanillaFadeRenderer();
        this.debugWireframeRenderer = new VkDebugWireframeRenderer();
        this.testTriangleRenderer = new VkTestTriangleRenderer();
        // Do NOT call backend.init() here — VulkanMod's VkDevice isn't ready yet.
        // Init is deferred to the first runRenderPassSetup() call.
    }

    @Override public String getEngineName() { return "VulkanMod"; }

    @Override public com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi getRenderApi() {
        return com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi.VULKAN;
    }
    @Override public com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine getRenderingEngine() {
        return com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine.OPEN_GL;
    }
    @Override public boolean isNativeRenderer() { return false; }
    @Override public IDhAntiAliasRenderer getAntiAliasRenderer() { return params -> {}; }

    // Singletons
    @Override public IDhMetaRenderer getMetaRenderer() { return metaRenderer; }
    @Override public IDhTerrainRenderer getTerrainRenderer() { return terrainRenderer; }
    @Override public IDhSsaoRenderer getSsaoRenderer() { return ssaoRenderer; }
    @Override public IDhFogRenderer getFogRenderer() { return fogRenderer; }
    @Override public IDhFarFadeRenderer getFarFadeRenderer() { return farFadeRenderer; }
    @Override public AbstractDebugWireframeRenderer getDebugWireframeRenderer() { return debugWireframeRenderer; }
    @Override public IDhVanillaFadeRenderer getVanillaFadeRenderer() { return vanillaFadeRenderer; }
    @Override public IDhTestTriangleRenderer getTestTriangleRenderer() { return testTriangleRenderer; }

    // Factories
    @Override public IDhGenericRenderer createGenericRenderer() { return new VkGenericRenderer(backend); }
    @Override public IVertexBufferWrapper createVboWrapper(String name) { return new VkVertexBufferWrapper(backend); }
    @Override public ILodContainerUniformBufferWrapper createLodContainerUniformWrapper() { return new VkLodContainerUniformWrapper(); }
    @Override public IDhGenericObjectVertexBufferContainer createGenericVboContainer() { return new VkGenericObjectVboContainer(); }

    VkMetaRenderer getVkMetaRenderer() { return metaRenderer; }

    // =========================================== //
    // Helper: convert RenderParams to RenderUniforms
    // =========================================== //

    static RenderUniforms toUniforms(RenderParams params, RenderUniforms target) {
        target.set(params.dhProjectionMatrix,
              params.dhModelViewMatrix,
              params.mcProjectionMatrix);
        target.worldYOffset = params.worldYOffset;
        target.partialTicks = params.partialTicks;
        target.nearClipPlane = params.nearClipPlane;
        target.farClipPlane = params.farClipPlane;
        return target;
    }

    // =========================================== //
    // Inner renderer implementations
    // =========================================== //

    /**
     * Meta renderer: handles frame setup, cleanup, composite, and depth/color clear.
     * This is the main lifecycle manager connecting DH's render loop to our Vulkan engine.
     */
    static class VkMetaRenderer implements IDhMetaRenderer {
        private final VulkanBackend backend;
        private final RenderUniforms cachedUniforms = new RenderUniforms();
        private boolean frameActive = false;
        private boolean initialized = false;

        VkMetaRenderer(VulkanBackend backend) {
            this.backend = backend;
        }

        @Override
        public void runRenderPassSetup(RenderParams renderParams) {
            // Deferred init: VulkanMod's VkDevice is only ready at render time
            if (!initialized) {
                backend.init();
                initialized = true;
            }
            toUniforms(renderParams, this.cachedUniforms);
            backend.beginFrame();
            backend.fillUniforms(this.cachedUniforms);
            this.frameActive = true;

            // Set up the late composite hook for SINGLE/DOUBLE Phase 2.
            // MixinLevelRenderer fires at renderLevel @RETURN → Compat.runLateCompositeHook().
            // In DH 2.4, this hook is set by MixinLodRenderer. In DH 3.0, the 2.4 mixin
            // doesn't fire, so we set it here to ensure Phase 2 + clouds work in both paths.
            // Note: no frameActive guard — runRenderPassCleanup sets it false before this fires.
            // VulkanRenderEngine.lateComposite() has its own frameReady guard.
            com.braffolk.dhvulkan.compat.Compat.setLateCompositeHook(() -> {
                toUniforms(renderParams, this.cachedUniforms);
                backend.lateComposite(this.cachedUniforms);
            });
        }

        @Override
        public void runRenderPassCleanup(RenderParams renderParams) {
            if (!frameActive) return;
            toUniforms(renderParams, this.cachedUniforms);
            backend.endFrame(this.cachedUniforms);
            this.frameActive = false;
        }

        @Override
        public void copyToMcTexture(RenderParams renderParams) {
            // Called at the end to composite DH's framebuffer onto MC's render target
            toUniforms(renderParams, this.cachedUniforms);
            backend.deferredComposite(this.cachedUniforms);
        }

        // DH 3.2 calls the same composite boundary by its older name.
        public void applyToMcTexture(RenderParams renderParams) {
            copyToMcTexture(renderParams);
        }

        @Override
        public void clearDhDepthAndColorTextures(RenderParams renderParams) {
            // Our framebuffer is cleared at the start of each frame in beginFrame().
            // This is a no-op here since VulkanBackend handles it internally.
        }

    }

    /**
     * Terrain renderer: draws LOD vertex buffers.
     * DH 3.0 passes LodBufferContainers which each hold VBO arrays.
     * We iterate them and draw through VulkanBackend, matching the GL
     * reference implementation's logic.
     */
    static class VkTerrainRenderer implements IDhTerrainRenderer {
        private final VulkanBackend backend;

        // Cached to avoid per-container allocation in the render loop
        private final DhVec3f reusableModelPos = new DhVec3f(0, 0, 0);

        VkTerrainRenderer(VulkanBackend backend) { this.backend = backend; }

        @Override
        public void render(RenderParams renderEventParam, boolean opaquePass,
                           SortedArraySet<LodBufferContainer> bufferContainers,
                           IProfilerWrapper profiler) {

            backend.setBlendState(!opaquePass);

            if (bufferContainers == null) return;

            for (int lodIndex = 0; lodIndex < bufferContainers.size(); lodIndex++) {
                LodBufferContainer container = bufferContainers.get(lodIndex);

                // Compute model offset relative to camera (matches GL reference)
                com.seibel.distanthorizons.core.util.math.DhVec3d camPos = renderEventParam.exactCameraPosition;
                if (camPos != null) {
                    this.reusableModelPos.set(
                        (float) (container.minCornerBlockPos.getX() - camPos.x),
                        (float) (container.minCornerBlockPos.getY() - camPos.y),
                        (float) (container.minCornerBlockPos.getZ() - camPos.z));
                    backend.setModelOffset(this.reusableModelPos);
                }

                // Exact DH 3.3.3 fields; other versions need a separate adapter.
                IVertexBufferWrapper[] vertexBuffers = opaquePass ? container.vboOpaqueWrappers : container.vboTransparentWrappers;
                if (vertexBuffers == null) continue;

                for (int vboIndex = 0; vboIndex < vertexBuffers.length; vboIndex++) {
                    Object vboObj = vertexBuffers[vboIndex];
                    if (!(vboObj instanceof VkVertexBufferWrapper)) continue;
                    VkVertexBufferWrapper vkVbo = (VkVertexBufferWrapper) vboObj;

                    VkVertexData data = vkVbo.getVertexData();
                    if (data == null) continue;

                    synchronized (data) {
                        // Count and upload must refer to the same generation.
                        // A worker may replace/close the buffer between draws.
                        int indexCount = vkVbo.getIndexCount() / 4 * 6;
                        if (indexCount == 0 || data.isClosed()) continue;
                        backend.drawVertexData(data, indexCount);
                    }
                }
            }
        }
    }

    // Post-process renderers are no-ops: our engine handles SSAO, fog, fade internally
    // during endFrame() and deferredComposite().

    static class VkSsaoRenderer implements IDhSsaoRenderer {
        @Override public void render(RenderParams renderParams) { /* handled internally by VulkanBackend */ }
    }

    static class VkFogRenderer implements IDhFogRenderer {
        @Override public void render(RenderParams renderParams, com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam fog) { /* handled by VulkanBackend */ }
    }

    static class VkFarFadeRenderer implements IDhFarFadeRenderer {
        @Override public void render(RenderParams renderParams) { /* handled internally by VulkanBackend */ }
    }

    static class VkVanillaFadeRenderer implements IDhVanillaFadeRenderer {
        @Override public void render(RenderParams renderParams) { /* handled internally by VulkanBackend */ }
    }

    static class VkTestTriangleRenderer implements IDhTestTriangleRenderer {
        @Override public void render(RenderParams renderParams) { /* not used */ }
    }

    static class VkDebugWireframeRenderer extends AbstractDebugWireframeRenderer {
        @Override
        public void renderBox(Box box) {
            // Debug wireframe rendering is not supported in Vulkan yet
        }
    }

    /**
     * DH generic objects (its clouds, beacon beams, API boxes) drawn as LOD quads.
     * <p>
     * DH calls this twice per frame after the opaque LOD terrain, once for groups with SSAO and once
     * for the others, and expects each group's double-buffered container to be advanced here. Under a
     * pack the quads go to the pack's dh_generic (else dh_terrain) and write the DH depth, like Iris;
     * DH's clouds stay away when the pack says dhClouds=off, or says nothing about them and clouds=off.
     */
    static class VkGenericRenderer implements IDhGenericRenderer {
        // DH registers its cloud group during world construction even when
        // drawing API geometry is disabled. Registration must remain functional.
        private final java.util.concurrent.ConcurrentMap<Long, IDhApiRenderableBoxGroup> groups =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final VulkanBackend backend;
        private boolean failed;

        VkGenericRenderer(VulkanBackend backend) { this.backend = backend; }

        @Override public void close() { groups.clear(); }

        /** -Dvsdh.genericTrace=true: log what DH hands the generic renderer every 600 calls. */
        private static final boolean TRACE = Boolean.getBoolean("vsdh.genericTrace");
        private long calls, drawnQuads;
        private boolean sawSsaoCall;

        @Override
        public void render(RenderParams renderEventParam, IProfilerWrapper profiler, boolean renderingWithSsao) {
            if (TRACE && calls++ % 600 == 0) {
                var text = new StringBuilder("GENERIC_TRACE calls=").append(calls).append(" accepts=")
                        .append(backend.acceptsGenericDraws()).append(" quads=").append(drawnQuads).append(" groups:");
                for (var group : groups.values())
                    if (group instanceof com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup box)
                        text.append(' ').append(box.resourceLocationPath).append("[active=").append(box.active)
                                .append(",ssao=").append(box.ssaoEnabled).append(",size=").append(box.size())
                                .append(",state=").append(box.vertexBufferContainer == null ? null : box.vertexBufferContainer.getState())
                                .append(']');
                org.slf4j.LoggerFactory.getLogger("DH-VulkanMod").info(text.toString());
            }
            if (failed || groups.isEmpty() || !backend.acceptsGenericDraws()) return;
            var camera = renderEventParam.exactCameraPosition;
            if (camera == null) return;
            // DH makes the SSAO call only while its own SSAO pass runs, which never happens here: the
            // call without SSAO then draws the SSAO groups too (API boxes default to SSAO).
            boolean includeSsaoGroups = renderingWithSsao || !sawSsaoCall;
            if (renderingWithSsao) sawSsaoCall = true; else sawSsaoCall = false;
            try {
                boolean shaded = !backend.packFrameActive();
                for (IDhApiRenderableBoxGroup group : groups.values()) {
                    if (!(group instanceof com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup box)) continue;
                    if (renderingWithSsao ? !box.ssaoEnabled : box.ssaoEnabled && !includeSsaoGroups) continue;
                    if ("Clouds".equalsIgnoreCase(box.resourceLocationPath) && !backend.drawsDhClouds()) continue;
                    var shading = box.shading;
                    float[] faces = shading == null ? null
                            : new float[]{shading.bottom, shading.top, shading.north, shading.south, shading.west, shading.east};
                    box.preRender(renderEventParam.apiCopy);
                    // DH may hand the next build to its worker inside this call; it reads these first.
                    configure(box, faces);
                    box.tryUpdateInstancedDataAsync();
                    if (box.active && box.vertexBufferContainer instanceof VkGenericObjectVboContainer container
                            && container.getState() == IDhGenericObjectVertexBufferContainer.EState.RENDER) {
                        var origin = box.positionBoxesRelativeToGroupOrigin ? box.getOriginBlockPos() : null;
                        double ox = origin == null ? 0 : origin.x, oy = origin == null ? 0 : origin.y, oz = origin == null ? 0 : origin.z;
                        for (var segment : container.segments(shaded)) {
                            Object buffer = segment.gpu()[shaded ? 1 : 0];
                            if (buffer == null) continue;
                            drawnQuads += segment.quads();
                            backend.drawGenericQuads(buffer, segment.quads(),
                                    (float) (ox + segment.x() - camera.x), (float) (oy + segment.y() - camera.y),
                                    (float) (oz + segment.z() - camera.z));
                        }
                    }
                    box.postRender(renderEventParam.apiCopy);
                }
            } catch (LinkageError incompatible) {
                // A DH build whose generic group differs from 3.3.3: draw nothing rather than fail every frame.
                failed = true;
                org.slf4j.LoggerFactory.getLogger("DH-VulkanMod").warn("DH generic objects disabled: incompatible DH build", incompatible);
            }
        }

        private static void configure(com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup box, float[] faces) {
            if (box.vertexBufferContainer instanceof VkGenericObjectVboContainer current)
                current.configure(box.skyLight, box.blockLight, faces);
        }

        @Override
        public String getVboRenderDebugMenuString() {
            return "VSDH: " + groups.size() + " generic object groups";
        }

        @Override
        public void add(IDhApiRenderableBoxGroup cubeGroup) throws IllegalArgumentException {
            java.util.Objects.requireNonNull(cubeGroup, "cubeGroup");
            IDhApiRenderableBoxGroup previous = groups.putIfAbsent(cubeGroup.getId(), cubeGroup);
            if (previous != null && previous != cubeGroup) {
                throw new IllegalArgumentException("A DH render group with this ID is already registered");
            }
            // A new group is not dirty; DH's own renderer schedules its first build here as well.
            try {
                if (cubeGroup instanceof com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup box && box.size() > 0)
                    box.triggerBoxChange();
            } catch (LinkageError incompatible) {
                // Other DH builds: the group stays registered and is drawn once it reports a change.
            }
        }

        @Override
        public IDhApiRenderableBoxGroup remove(long id) {
            return groups.remove(id);
        }
    }
}
