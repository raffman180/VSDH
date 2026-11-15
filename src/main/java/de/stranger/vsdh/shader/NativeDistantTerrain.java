package de.stranger.vsdh.shader;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;

import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

/** Dedicated DH depths and transient draw lists. Mesh buffers stay owned by the DH backend. */
public final class NativeDistantTerrain implements AutoCloseable {
    private final DistantPackPlan plan;
    private final Batch opaque = new Batch(), transparent = new Batch(), generic = new Batch();
    private VulkanImage depth, opaqueDepth;
    private NativeDistantPass terrain, water, genericPass;
    private long terrainDraws, waterDraws;
    private boolean closed;

    NativeDistantTerrain(WorldPackPlan worldPlan, int width, int height) {
        this.plan = worldPlan.distant();
        var targets = worldPlan.chain().targets();
        try {
            if (FeedbackLoops.distantDepthEnabled() && loopDepthSupported(width, height))
                for (var source : plan.programs())
                    if (FeedbackLoops.distantDepthLoop(PackUsage.activeSamplers(java.util.List.of(source)))) liveDepth.add(source);
            if (!liveDepth.isEmpty())
                org.slf4j.LoggerFactory.getLogger("VSDH").info("DH depth feedback loop (dhDepthTex bound live): {}",
                        liveDepth.stream().map(source -> FeedbackLoops.bare(source.path())).sorted().toList());
            else {
                var copied = plan.programs().stream()
                        .filter(source -> FeedbackLoops.distantDepthLoop(PackUsage.activeSamplers(java.util.List.of(source))))
                        .map(source -> FeedbackLoops.bare(source.path())).sorted().toList();
                if (!copied.isEmpty())
                    org.slf4j.LoggerFactory.getLogger("VSDH").info("DH depth read from the opaque copy (dhDepthTex in {}); "
                            + "-Dvsdh.dhDepthFeedbackLoop=true binds the live depth", copied);
            }
            depth = createDepth(width, height, !liveDepth.isEmpty());
            opaqueDepth = createDepth(width, height, false);
            terrain = new NativeDistantPass(plan.terrain(), NativeBlend.forProgram(targets,"dh_terrain",targets.writes("dh_terrain").size()));
            water = new NativeDistantPass(plan.water(), NativeBlend.forProgram(targets,"dh_water",targets.writes("dh_water").size()), true);
            if (plan.generic() != null)
                genericPass = new NativeDistantPass(plan.generic(), NativeBlend.forProgram(targets,"dh_generic",targets.writes("dh_generic").size()));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static VulkanImage createDepth(int width, int height, boolean loop) {
        int usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | (loop ? VulkanImage.FEEDBACK_LOOP_USAGE : 0);
        return VulkanImage.createDepthImage(VK_FORMAT_D32_SFLOAT, width, height, usage, false, true);
    }

    /**
     * DH programs that sample dhDepthTex/dhDepthTex0 read the opaque copy: stable for the whole water pass.
     * Only with -Dvsdh.dhDepthFeedbackLoop=true (and the feedback-loop layout) do they read the full DH depth
     * while drawing into it, as under Iris; that read races with the depth writes of the same pass and made
     * distant water flicker (FeedbackLoops.DISTANT_DEPTH_REQUESTED). The full and the opaque depth stay two
     * images either way.
     */
    private final java.util.Set<PackProgramSource.Prepared> liveDepth = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    boolean liveDepth(PackProgramSource.Prepared source) { return liveDepth.contains(source); }
    private static boolean loopDepthSupported(int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = org.lwjgl.vulkan.VkImageFormatProperties.calloc(stack);
            int usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VulkanImage.FEEDBACK_LOOP_USAGE;
            return vkGetPhysicalDeviceImageFormatProperties(net.vulkanmod.vulkan.device.DeviceManager.physicalDevice, VK_FORMAT_D32_SFLOAT,
                    VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL, usage, 0, props) == VK_SUCCESS
                    && props.maxExtent().width() >= width && props.maxExtent().height() >= height;
        }
    }

    void beginFrame() {
        opaque.clear();
        transparent.clear();
        generic.clear();
        NativeImageOps.clearDepth(depth, 1, 0);
        if (opaqueSnapshot) NativeImageOps.clearDepth(opaqueDepth, 1, 0);
    }

    /** Whether anything reads dhDepthTex1 (pack programs, or DH programs reading DH depth). */
    private boolean opaqueSnapshot = true;
    void opaqueSnapshot(boolean needed) { opaqueSnapshot = needed; }

    public VulkanImage image(String name) {
        return switch (name) {
            case "dhDepthTex", "dhDepthTex0" -> depth;
            case "dhDepthTex1" -> opaqueDepth;
            default -> throw new IllegalArgumentException("Not a DH depth sampler: " + name);
        };
    }

    public void enqueue(boolean water, Object vertices, Object indices, int count, float x, float y, float z) {
        (water ? transparent : opaque).add(vertices, indices, count, x, y, z);
    }

    /** DH generic objects: opaque, after the LOD terrain and before its translucent half. */
    public void enqueueGeneric(Object vertices, Object indices, int count, float x, float y, float z) {
        generic.add(vertices, indices, count, x, y, z);
    }

    void snapshotOpaque() {
        if (opaqueSnapshot) NativeImageOps.copyBase(depth, opaqueDepth);
    }

    void render(WorldShaderSession session, boolean translucent) {
        draw(session, translucent ? transparent : opaque, translucent ? plan.water() : plan.terrain(),
                translucent ? water : terrain, translucent);
        if (!translucent && generic.size != 0)
            draw(session, generic, plan.genericProgram(), genericPass != null ? genericPass : terrain, false);
    }

    private void draw(WorldShaderSession session, Batch batch, PackProgramSource.Prepared source,
                      NativeDistantPass pipeline, boolean translucent) {
        if (batch.size == 0) return;
        var target = session.chain().geometry(source.path().substring(source.path().lastIndexOf('/') + 1), translucent, depth, liveDepth(source));
        session.writeDistant(pipeline, source, translucent);
        try (var stack = MemoryStack.stackPush()) {
            var offset = stack.malloc(12);
            pipeline.render(target.framebuffer(), target.pass(), command -> {
                for (int i = 0; i < batch.size; i++) {
                    int xyz = i * 3;
                    pipeline.draw(command, offset, batch.vertices[i], batch.indices[i], batch.counts[i],
                            batch.offsets[xyz], batch.offsets[xyz + 1], batch.offsets[xyz + 2]);
                }
            });
        }
        if (translucent) waterDraws += batch.size;
        else terrainDraws += batch.size;
        batch.clear();
    }

    public long terrainDraws() { return terrainDraws; }
    public long waterDraws() { return waterDraws; }
    boolean hasOpaqueDraws() { return opaque.size != 0 || generic.size != 0; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        opaque.clear();
        transparent.clear();
        generic.clear();
        if (terrain != null) terrain.close();
        if (genericPass != null) genericPass.close();
        if (water != null) water.close();
        if (depth != null) depth.free();
        if (opaqueDepth != null) opaqueDepth.free();
    }

    private static final class Batch {
        private Object[] vertices = new Object[256], indices = new Object[256];
        private int[] counts = new int[256];
        private float[] offsets = new float[768];
        private int size;

        void add(Object vertex, Object index, int count, float x, float y, float z) {
            if (size == counts.length) {
                int capacity = Math.multiplyExact(size, 2);
                vertices = Arrays.copyOf(vertices, capacity);
                indices = Arrays.copyOf(indices, capacity);
                counts = Arrays.copyOf(counts, capacity);
                offsets = Arrays.copyOf(offsets, capacity * 3);
            }
            vertices[size] = vertex;
            indices[size] = index;
            counts[size] = count;
            int xyz = size++ * 3;
            offsets[xyz] = x;
            offsets[xyz + 1] = y;
            offsets[xyz + 2] = z;
        }

        void clear() {
            Arrays.fill(vertices, 0, size, null);
            Arrays.fill(indices, 0, size, null);
            size = 0;
        }
    }
}
