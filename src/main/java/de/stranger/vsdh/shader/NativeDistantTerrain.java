package de.stranger.vsdh.shader;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;

import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

/** Dedicated DH depths and transient draw lists. Mesh buffers stay owned by the DH backend. */
public final class NativeDistantTerrain implements AutoCloseable {
    private final DistantPackPlan plan;
    private final Batch opaque = new Batch(), transparent = new Batch();
    private VulkanImage depth, opaqueDepth;
    private NativeDistantPass terrain, water;
    private long terrainDraws, waterDraws;
    private boolean closed;

    NativeDistantTerrain(WorldPackPlan worldPlan, int width, int height) {
        this.plan = worldPlan.distant();
        var targets = worldPlan.chain().targets();
        try {
            depth = createDepth(width, height);
            opaqueDepth = createDepth(width, height);
            terrain = new NativeDistantPass(plan.terrain(), NativeBlend.forProgram(targets,"dh_terrain",targets.writes("dh_terrain").size()));
            water = new NativeDistantPass(plan.water(), NativeBlend.forProgram(targets,"dh_water",targets.writes("dh_water").size()));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static VulkanImage createDepth(int width, int height) {
        int usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        return VulkanImage.createDepthImage(VK_FORMAT_D32_SFLOAT, width, height, usage, false, true);
    }

    void beginFrame() {
        opaque.clear();
        transparent.clear();
        NativeImageOps.clearDepth(depth, 1, 0);
        NativeImageOps.clearDepth(opaqueDepth, 1, 0);
    }

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

    void snapshotOpaque() {
        NativeImageOps.copyBase(depth, opaqueDepth);
    }

    void render(WorldShaderSession session, boolean translucent) {
        var batch = translucent ? transparent : opaque;
        if (batch.size == 0) return;
        var source = translucent ? plan.water() : plan.terrain();
        var pipeline = translucent ? water : terrain;
        var target = session.chain().geometry(source.path().substring(source.path().lastIndexOf('/') + 1), translucent, depth);
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
    boolean hasOpaqueDraws() { return opaque.size != 0; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        opaque.clear();
        transparent.clear();
        if (terrain != null) terrain.close();
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
