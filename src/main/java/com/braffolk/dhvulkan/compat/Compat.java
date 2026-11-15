package com.braffolk.dhvulkan.compat;

import net.vulkanmod.vulkan.memory.VertexBuffer;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.memory.Buffer;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.texture.VTextureSelector;

import java.nio.ByteBuffer;

/**
 * Single source of truth for ALL version-specific API differences.
 * NO other file should contain #if blocks.
 */
public final class Compat {

    private static final java.util.List<Runnable> resizeCallbacks = new java.util.ArrayList<>();
    private static Renderer resizeOwner;

    public static void addResizeCallback(Runnable callback) {
        Renderer renderer = Renderer.getInstance();
        if (resizeOwner != renderer) {
            resizeOwner = renderer;
            renderer.addOnResizeCallback(() -> {
                for (Runnable registered : java.util.List.copyOf(resizeCallbacks)) registered.run();
            });
        }
        resizeCallbacks.add(callback);
    }

    // ========================= //
    // Cached reflection fields  //
    // ========================= //

    private static java.lang.reflect.Field vulkanImageIdField;
    private static java.lang.reflect.Field vulkanImageViewField;
    private static java.lang.reflect.Field rendererCmdBufferField;

    // Pre-allocated native memory for drawIndexed VM 0.4.2 path
    // Avoids per-draw nmemAlloc/nmemFree (was ~400-1000× per frame)
    private static final long drawBufPtr = org.lwjgl.system.MemoryUtil.nmemAllocChecked(8);
    private static final long drawOffPtr = org.lwjgl.system.MemoryUtil.nmemAllocChecked(8);

    // Cached method for setModelOffset (VM 0.6+ only)
    private static java.lang.reflect.Method setModelOffsetMethod;
    private static boolean setModelOffsetResolved = false;

    // Reusable float[3] for getCloudColorRGB — avoids per-frame allocation
    private static final float[] cloudColorResult = new float[3];

    static {
        try {
            vulkanImageIdField = VulkanImage.class.getDeclaredField("id");
            vulkanImageIdField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            vulkanImageViewField = VulkanImage.class.getDeclaredField("mainImageView");
            vulkanImageViewField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            rendererCmdBufferField = Renderer.class.getDeclaredField("currentCmdBuffer");
            rendererCmdBufferField.setAccessible(true);
        } catch (Exception ignored) {}
    }

    // ========================= //
    // VulkanMod detection       //
    // ========================= //

    private static final boolean VULKANMOD_ACTIVE = detectVulkanMod();

    private static boolean detectVulkanMod() {
        try {
            Class.forName("net.vulkanmod.vulkan.Renderer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** @return true if VulkanMod is loaded (no GL context available) */
    public static boolean isVulkanModActive() {
        return VULKANMOD_ACTIVE;
    }

    // ========================= //
    // Late composite hook       //
    // ========================= //

    /**
     * Static hook for Phase 2b late re-composite.
     * Set by MixinLodRenderer (dh24 module), called by MixinLevelRenderer (shared module)
     * at renderLevel @RETURN.
     */
    private static Runnable lateCompositeHook;

    public static void setLateCompositeHook(Runnable hook) {
        lateCompositeHook = hook;
    }

    public static void runLateCompositeHook() {
        Runnable hook = lateCompositeHook;
        if (hook != null) hook.run();
    }

    // ========================= //
    // Buffer factories          //
    // ========================= //

    public static Object createGpuVertexBuffer(int sizeBytes) {
        return new VertexBuffer(sizeBytes, MemoryTypes.GPU_MEM);
    }

    /**
     * The shared quad index buffer every DH draw reads. In device memory like the renderer's own AutoIndexBuffer: in host
     * memory each DH draw fetched its indices over PCIe (VSDH, 15.11.2026). {@code -Dvsdh.dhIndexHostMemory=true} restores
     * the old placement for A/B.
     */
    public static Object createIndexBuffer(int sizeBytes) {
        return new IndexBuffer(sizeBytes, DH_INDEX_HOST_MEMORY ? MemoryTypes.HOST_MEM : MemoryTypes.GPU_MEM, IndexBuffer.IndexType.INT);
    }

    private static final boolean DH_INDEX_HOST_MEMORY = Boolean.getBoolean("vsdh.dhIndexHostMemory");

    // ========================= //
    // Buffer operations //
    // ========================= //

    /**
     * Wait for the GPU to finish all in-flight work.
     * Must be called before freeing resources that may still be in use by
     * a previous frame. This is expensive — only use during cleanup/reinit,
     * never per-frame.
     */
    public static void waitDeviceIdle() {
        org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(net.vulkanmod.vulkan.Vulkan.getVkDevice());
    }

    public static void scheduleFree(Object buffer) {

        ((Buffer) buffer).freeBuffer();

    }

    public static void copyBuffer(Object buffer, ByteBuffer data, int size) {

        if (buffer instanceof VertexBuffer) {
            ((VertexBuffer) buffer).copyToVertexBuffer(size, 1, data);
        } else if (buffer instanceof IndexBuffer) {
            ((IndexBuffer) buffer).copyBuffer(data);
        }

    }

    // ========================= //
    // Draw calls //
    // ========================= //

    public static void draw(Object vertexBuffer, int vertexCount) {
        Renderer.getDrawer().draw((VertexBuffer) vertexBuffer, vertexCount);
    }

    public static void drawIndexed(Object vertexBuffer, Object indexBuffer, int indexCount) {

        // VM 0.4.2 Drawer.drawIndexed() hardcodes VK_INDEX_TYPE_UINT16 (= 0),
        // but DH uses UINT32 indices. Issue raw Vulkan commands with UINT32.
        org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
        VertexBuffer vb = (VertexBuffer) vertexBuffer;
        IndexBuffer ib = (IndexBuffer) indexBuffer;

        // Bind vertex buffer — uses pre-allocated static native pointers
        org.lwjgl.system.MemoryUtil.memPutLong(drawBufPtr, vb.getId());
        org.lwjgl.system.MemoryUtil.memPutLong(drawOffPtr, vb.getOffset());
        org.lwjgl.vulkan.VK10.nvkCmdBindVertexBuffers(cmd, 0, 1, drawBufPtr, drawOffPtr);

        // Bind index buffer with VK_INDEX_TYPE_UINT32 = 1
        org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer(cmd, ib.getId(), ib.getOffset(), 1);

        // Draw
        org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);

    }

    // ========================= //
    // VertexFormatElement //
    // ========================= //

    public static VertexFormatElement vertexFormatElement(
            int id, int index,
            VertexFormatElement.Type type, VertexFormatElement.Usage usage,
            int count) {

        return new VertexFormatElement(id, index, type, usage, count);

    }

    // ========================= //
    // VertexFormat builder //
    // ========================= //

    public static VertexFormat buildVertexFormat(String[] names, VertexFormatElement[] elements) {

        VertexFormat.Builder builder = VertexFormat.builder();
        for (int i = 0; i < names.length; i++) {
            builder.add(names[i], elements[i]);
        }
        return builder.build();

    }

    // ========================= //
    // Renderer / SwapChain //
    // ========================= //

    public static int getSwapChainWidth() {

        var main = Renderer.getInstance().getMainPass();
        VulkanImage color = main != null ? main.getColorAttachment() : null;
        return color != null ? color.width : net.vulkanmod.vulkan.Vulkan.getSwapChain().getWidth();

    }

    public static int getSwapChainHeight() {

        var main = Renderer.getInstance().getMainPass();
        VulkanImage color = main != null ? main.getColorAttachment() : null;
        return color != null ? color.height : net.vulkanmod.vulkan.Vulkan.getSwapChain().getHeight();

    }

    public static void beginRenderPass(
            net.vulkanmod.vulkan.framebuffer.RenderPass renderPass,
            net.vulkanmod.vulkan.framebuffer.Framebuffer framebuffer) {

        try {
            // End any currently active render pass first
            Renderer.getInstance().endRenderPass();

            org.lwjgl.vulkan.VkCommandBuffer cmdBuffer =
                    (org.lwjgl.vulkan.VkCommandBuffer) rendererCmdBufferField.get(Renderer.getInstance());
            org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush();
            framebuffer.beginRenderPass(cmdBuffer, renderPass, stack);
            stack.close();

            // CRITICAL: update Renderer's state so endRenderPass() works.
            // Without this, endRenderPass() sees boundFramebuffer==null and no-ops,
            // leaving nested render passes open → DEVICE_LOST on NVIDIA.
            Renderer.getInstance().setBoundFramebuffer(framebuffer);
            Renderer.getInstance().setBoundRenderPass(renderPass);
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to begin render pass on VM 0.4.2", e);
        }

    }

    /** Rebind the selected world target. Reforged owns renderer state and may use a shader/scaled target. */
    public static void rebindMainTarget() {
        // The pinned Reforged implementation updates both renderer bindings itself.
        // Respect the MainPass interface: a shader session or scaled world target may own it.
        Renderer.getInstance().getMainPass().rebindMainTarget();

    }

    // ========================= //
    // Uniform setup //
    // ========================= //

    public static void addUniformWithBuffer(
            net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder builder,
            String type, String name, int count,
            java.util.function.Supplier<net.vulkanmod.vulkan.util.MappedBuffer> bufferSupplier) {

        builder.addUniformInfo(type, name, count);

    }

    // ========================= //
    // Push Constants            //
    // ========================= //

    /**
     * Compile-time constant: true on VM 0.6.1+, false on VM 0.4.2.
     * Used at init time only (shader preprocessing, pipeline creation).
     */
    public static boolean hasPushConstants() {

        return false;

    }

    /**
     * Build a push constant block and attach it to the pipeline builder.
     * On VM 0.4.2: no-op (push constants not supported).
     *
     * @param pipelineBuilder the Pipeline.Builder being constructed
     * @param type   uniform type string (e.g. "float")
     * @param name   uniform name (e.g. "uModelOffset")
     * @param count  element count (e.g. 3 for vec3)
     * @param bufferSupplier supplier for the MappedBuffer backing this uniform
     */
    public static void buildAndSetPushConstants(
            net.vulkanmod.vulkan.shader.Pipeline.Builder pipelineBuilder,
            String type, String name, int count,
            java.util.function.Supplier<net.vulkanmod.vulkan.util.MappedBuffer> bufferSupplier) {

    }

    /**
     * Per-draw state application:
     * - VM 0.6.1: issues vkCmdPushConstants (12 bytes, zero-copy to cmd buffer)
     * - VM 0.4.2: full uploadAndBindUBOs (descriptor set alloc + UBO copy)
     */
    public static void applyPerDrawState(net.vulkanmod.vulkan.shader.GraphicsPipeline pipeline) {

        Renderer.getInstance().uploadAndBindUBOs(pipeline);

    }

    /**
     * On VM 0.4.2, Uniform.setSupplier() in the constructor only resolves MC's
     * built-in uniforms (ModelViewMat etc). Custom DH uniforms have values=null.
     * Call this AFTER buildUBO() to set suppliers on each Uniform.
     * On VM 0.6.1, this is a no-op (Info.setBufferSupplier handles it).
     */
    public static void setUniformSuppliers(
            net.vulkanmod.vulkan.shader.descriptor.UBO ubo,
            java.util.Map<String, net.vulkanmod.vulkan.util.MappedBuffer> bufferMap) {

        for (net.vulkanmod.vulkan.shader.layout.Uniform uniform : ubo.getUniforms()) {
            net.vulkanmod.vulkan.util.MappedBuffer mb = bufferMap.get(uniform.getName());
            if (mb != null) {
                uniform.setSupplier(() -> mb);
            }
        }

    }

    // ========================= //
    // GlTexture / Lightmap //
    // ========================= //

    public static VulkanImage getLightmapVulkanImage() {
        try {

            // VM 0.4.2: MC already binds lightmap to slot 2 before our hook fires.
            // Just read it directly from VTextureSelector.
            return VTextureSelector.getBoundTexture(2);

        } catch (Exception e) {
            return null;
        }
    }

    // ========================= //
    // Config value scaling //
    // ========================= //

    /**
     * DH 1.20.6 config returns noise intensity as an unscaled integer (e.g. 40 = 40%).
     * DH 1.21.1+ returns it as a pre-scaled 0-1 float. Normalize here.
     */
    public static float scaleNoiseIntensity(float raw) {

        return raw;

    }

    // ========================= //
    // Swapchain access //
    // ========================= //

    /**
     * Get the current MC scene depth for depth-compared compositing.
     * The legacy method name is retained; the image may belong to a scaled world target.
     */
    public static VulkanImage getSwapChainDepthAttachment() {

        var main = Renderer.getInstance().getMainPass();
        return main != null ? main.getDepthAttachment() : net.vulkanmod.vulkan.Vulkan.getSwapChain().getDepthAttachment();

    }

    // ========================= //
    // Depth-only sampling //
    // ========================= //

    /** Cached depth-only image view for D24S8 / D32S8 formats */
    private static long depthOnlyView = 0;
    private static long lastDepthImageId = 0;
    /** Saved original view for restore after draw */
    private static long savedOriginalView = 0;
    private static VulkanImage savedDepthImage = null;

    /**
     * Prepare MC's depth texture for sampling by setting the depth-only
     * image view on combined D+S formats and binding to the given slot.
     * <p>
     * vm.5 logic: only create a depth-only view for combined depth+stencil
     * formats (D24S8, D32S8). For depth-only formats like D32_SFLOAT (Mac),
     * the default mainImageView already has DEPTH_BIT aspect — just bind directly.
     * <p>
     * IMPORTANT: Does NOT restore the original view — you MUST call
     * {@link #restoreMcDepthView()} after the draw completes.
     */
    public static void prepareMcDepthForSampling(int slot, VulkanImage depthImage) {
        int VK_IMAGE_ASPECT_STENCIL_BIT = 4;
        int VK_IMAGE_ASPECT_DEPTH_BIT = 2;

        // vm.5 logic: only create depth-only view for combined D+S formats
        // (aspect has stencil bit set). D32_SFLOAT (aspect=2, no stencil)
        // works fine with the default mainImageView.
        if ((depthImage.aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
            try {
                long imageId = (long) vulkanImageIdField.get(depthImage);

                if (imageId != lastDepthImageId || depthOnlyView == 0) {
                    if (depthOnlyView != 0) {
                        org.lwjgl.vulkan.VK10.vkDestroyImageView(
                                net.vulkanmod.vulkan.Vulkan.getVkDevice(), depthOnlyView, null);
                    }
                    // vm.5 used the 5-param overload which maps to createImageView(id, viewType=1, format, aspect, arrayLayers=0→1, baseMip=0, mips)
                    depthOnlyView = VulkanImage.createImageView(
                            imageId, depthImage.format,
                            VK_IMAGE_ASPECT_DEPTH_BIT,
                            0, depthImage.mipLevels);
                    lastDepthImageId = imageId;
                    LOGGER.info("[DH-Vulkan] Created depth-only view for D+S format={} aspect={} (view={})",
                            depthImage.format, depthImage.aspect, depthOnlyView);
                }

                savedOriginalView = (long) vulkanImageViewField.get(depthImage);
                savedDepthImage = depthImage;
                vulkanImageViewField.setLong(depthImage, depthOnlyView);

                VTextureSelector.bindTexture(slot, depthImage);
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] Failed to create depth-only view, binding as-is", e);
                savedDepthImage = null;
                VTextureSelector.bindTexture(slot, depthImage);
            }
        } else {
            // D32_SFLOAT or other depth-only formats — just bind directly
            savedDepthImage = null;
            VTextureSelector.bindTexture(slot, depthImage);
        }
    }

    /**
     * Restore the original mainImageView on the depth image after draw.
     * Must be called after the draw that samples MC depth completes.
     */
    public static void restoreMcDepthView() {
        if (savedDepthImage != null && savedOriginalView != 0) {
            try {
                vulkanImageViewField.setLong(savedDepthImage, savedOriginalView);
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] Failed to restore MC depth view", e);
            }
            savedDepthImage = null;
            savedOriginalView = 0;
        }
    }

    /**
     * Free all static Vulkan resources held by Compat.
     * Must be called during VulkanRenderDelegate.cleanup() to prevent leaks
     * across server transitions (leave/join world cycles).
     */
    public static void cleanupStaticResources() {
        resizeCallbacks.clear();
        // Free the depth-only image view used for combined D+S formats
        if (depthOnlyView != 0) {
            org.lwjgl.vulkan.VK10.vkDestroyImageView(
                    net.vulkanmod.vulkan.Vulkan.getVkDevice(), depthOnlyView, null);
            depthOnlyView = 0;
        }
        lastDepthImageId = 0;
        savedOriginalView = 0;
        savedDepthImage = null;
    }

    // ========================= //
    // Cloud rendering helpers   //
    // ========================= //

    /**
     * Load a resource by path from Minecraft's resource manager.
     * ResourceLocation was renamed to Identifier in MC 1.21.11.
     */
    public static java.io.InputStream openMcResource(String path) throws java.io.IOException {

        var loc = net.minecraft.resources.ResourceLocation.withDefaultNamespace(path);

        return net.minecraft.client.Minecraft.getInstance().getResourceManager()
                .getResourceOrThrow(loc).open();
    }

    /**
     * Get the cloud render range in blocks.
     * MC 1.21.x has options.cloudRange(), MC 1.20.x uses renderDistance.
     */
    public static int getCloudRenderRange() {

        return net.minecraft.client.Minecraft.getInstance().options.renderDistance().get() * 16;

    }

    /**
     * Get cloud pixel data from a NativeImage as int array.
     * MC 1.21.x has getPixelsABGR(), MC 1.20.x has getPixelRGBA per-pixel.
     */
    public static int[] getCloudPixels(com.mojang.blaze3d.platform.NativeImage image) {

        return image.getPixelsRGBA();

    }

    /**
     * Get cloud height for the current dimension. Returns -1 if none (e.g. nether).
     */
    public static int getCloudHeight(net.minecraft.client.multiplayer.ClientLevel level) {
        try {

            // MC 1.20.x: cloudHeight might be an OptionalInt
            java.lang.reflect.Method m = level.dimensionType().getClass().getMethod("cloudHeight");
            Object result = m.invoke(level.dimensionType());
            if (result instanceof java.util.OptionalInt) {
                return ((java.util.OptionalInt) result).orElse(-1);
            } else if (result instanceof java.util.Optional) {
                @SuppressWarnings("unchecked")
                java.util.Optional<Integer> opt = (java.util.Optional<Integer>) result;
                return opt.orElse(-1);
            }
            return 192; // fallback overworld

        } catch (Exception e) {
            return 192;
        }
    }

    /**
     * Get cloud color as RGB float[3] for the current level.
     * API varies significantly across MC versions.
     */
    public static float[] getCloudColorRGB(net.minecraft.client.multiplayer.ClientLevel level, float partialTicks) {
        try {

            // MC 1.20.x - 1.21.1: returns Vec3
            net.minecraft.world.phys.Vec3 color = level.getCloudColor(partialTicks);
            cloudColorResult[0] = (float) color.x;
            cloudColorResult[1] = (float) color.y;
            cloudColorResult[2] = (float) color.z;

        } catch (Exception e) {
            cloudColorResult[0] = 1.0f;
            cloudColorResult[1] = 1.0f;
            cloudColorResult[2] = 1.0f;
        }
        return cloudColorResult;
    }

    /**
     * Begin building cloud mesh geometry.
     * MC 1.21.x: Tesselator.getInstance().begin(Mode, Format)
     * MC 1.20.x: new BufferBuilder(size); builder.begin(Mode, Format)
     */
    public static com.mojang.blaze3d.vertex.BufferBuilder beginCloudMesh() {

        return com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);

    }

    /**
     * Add a vertex with position + ARGB color to the cloud mesh builder.
     */
    public static void putCloudVertex(com.mojang.blaze3d.vertex.BufferBuilder builder, float x, float y, float z, int color) {

        builder.addVertex(x, y, z).setColor(color);

    }

    /**
     * Finish building the cloud mesh, returning the mesh data (or null if empty).
     * Returns Object to avoid referencing MeshData which doesn't exist on 1.20.x.
     */
    public static Object finishCloudMesh(com.mojang.blaze3d.vertex.BufferBuilder builder) {

        return builder.build();

    }

    /**
     * Set VRenderSystem model offset. Only exists on VM 0.6.x.
     */
    public static void setModelOffset(float x, float y, float z) {
        if (!setModelOffsetResolved) {
            setModelOffsetResolved = true;
            try {
                setModelOffsetMethod = net.vulkanmod.vulkan.VRenderSystem.class.getMethod(
                        "setModelOffset", float.class, float.class, float.class);
            } catch (Exception ignored) {
                // VM 0.4.2 doesn't have setModelOffset — no-op
            }
        }
        if (setModelOffsetMethod != null) {
            try {
                setModelOffsetMethod.invoke(null, x, y, z);
            } catch (Exception ignored) {}
        }
    }

    private static final com.seibel.distanthorizons.core.logging.DhLogger LOGGER = new com.seibel.distanthorizons.core.logging.DhLoggerBuilder()
            .build();

    private Compat() {
    }
}
