package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * DH generic boxes (DH's own clouds, beacon beams, API geometry) as ordinary LOD quads.
 * <p>
 * DH's OpenGL path draws a unit cube per box with instancing. Here every box becomes six faces in
 * the same 16-byte vertex layout DH uses for LOD terrain (three unsigned shorts of position, a light
 * word, four colour bytes, material and face bytes), so the existing LOD pipelines draw them: a
 * pack's {@code dh_generic}, else its {@code dh_terrain} as under Iris, or the plain DH pipeline.
 * Each face is written in both windings, because the LOD passes cull back faces and a box has to
 * look right from every side without depending on one winding convention.
 * <p>
 * Threads follow DH's protocol: {@link #updateVertexData} runs on a DH worker and only builds CPU
 * arrays; {@link #uploadDataToGpu} is called from the render thread and is the only place that
 * creates or frees Vulkan buffers.
 */
public class VkGenericObjectVboContainer implements IDhGenericObjectVertexBufferContainer {
    static final int VERTEX_BYTES = 16;
    private static final int QUADS_PER_BOX = 12; // six faces, two windings
    private static final int RANGE = 65535;
    /** DH face order (EDhDirection.faceIndex): DOWN, UP, NORTH, SOUTH, WEST, EAST. */
    private static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;

    /** One vertex buffer whose positions are relative to its own corner. */
    record Segment(double x, double y, double z, int quads, ByteBuffer plain, ByteBuffer shaded,
                   Object[] gpu) {}

    private volatile EState state = EState.NEW;
    private volatile List<Segment> pending;
    private List<Segment> uploaded = List.of();
    private byte skyLight = 15, blockLight = 15;
    private float[] shading = {0.7f, 1f, 0.9f, 0.9f, 0.8f, 0.8f};

    /** Light and DH face shading for the next build; read from the owning group by the renderer. */
    void configure(int sky, int block, float[] faceShading) {
        skyLight = (byte) sky;
        blockLight = (byte) block;
        if (faceShading != null) shading = faceShading.clone();
    }

    @Override
    public void updateVertexData(List<DhApiRenderableBox> boxes) {
        var segments = new ArrayList<Segment>();
        List<DhApiRenderableBox> run = new ArrayList<>();
        double bx = 0, by = 0, bz = 0;
        for (DhApiRenderableBox box : boxes) {
            if (box == null || box.minPos == null || box.maxPos == null) continue;
            double x0 = Math.floor(Math.min(box.minPos.x, box.maxPos.x)), y0 = Math.floor(Math.min(box.minPos.y, box.maxPos.y));
            double z0 = Math.floor(Math.min(box.minPos.z, box.maxPos.z));
            double x1 = Math.ceil(Math.max(box.minPos.x, box.maxPos.x)), y1 = Math.ceil(Math.max(box.minPos.y, box.maxPos.y));
            double z1 = Math.ceil(Math.max(box.minPos.z, box.maxPos.z));
            if (x1 - x0 > RANGE || y1 - y0 > RANGE || z1 - z0 > RANGE) continue; // cannot be held by the LOD format
            if (run.isEmpty()) { bx = x0; by = y0; bz = z0; }
            boolean fits = x0 >= bx && y0 >= by && z0 >= bz && x1 - bx <= RANGE && y1 - by <= RANGE && z1 - bz <= RANGE;
            if (!fits) {
                segments.add(build(run, bx, by, bz));
                run = new ArrayList<>();
                bx = x0; by = y0; bz = z0;
            }
            run.add(box);
        }
        if (!run.isEmpty()) segments.add(build(run, bx, by, bz));
        var previous = pending;
        pending = segments;
        if (previous != null) release(previous, false);
    }

    private Segment build(List<DhApiRenderableBox> boxes, double bx, double by, double bz) {
        int quads = boxes.size() * QUADS_PER_BOX;
        ByteBuffer plain = MemoryUtil.memAlloc(quads * 4 * VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer shaded = MemoryUtil.memAlloc(quads * 4 * VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int light = (skyLight & 15) | (blockLight & 15) << 4;
        for (DhApiRenderableBox box : boxes) {
            int x0 = (int) (Math.floor(Math.min(box.minPos.x, box.maxPos.x)) - bx);
            int y0 = (int) (Math.floor(Math.min(box.minPos.y, box.maxPos.y)) - by);
            int z0 = (int) (Math.floor(Math.min(box.minPos.z, box.maxPos.z)) - bz);
            int x1 = (int) (Math.ceil(Math.max(box.minPos.x, box.maxPos.x)) - bx);
            int y1 = (int) (Math.ceil(Math.max(box.minPos.y, box.maxPos.y)) - by);
            int z1 = (int) (Math.ceil(Math.max(box.minPos.z, box.maxPos.z)) - bz);
            var colour = box.color;
            int r = colour == null ? 255 : colour.getRed(), g = colour == null ? 255 : colour.getGreen();
            int b = colour == null ? 255 : colour.getBlue(), a = colour == null ? 255 : colour.getAlpha();
            int material = box.material & 255;
            face(plain, shaded, DOWN, light, r, g, b, a, material, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            face(plain, shaded, UP, light, r, g, b, a, material, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            face(plain, shaded, NORTH, light, r, g, b, a, material, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            face(plain, shaded, SOUTH, light, r, g, b, a, material, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            face(plain, shaded, WEST, light, r, g, b, a, material, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            face(plain, shaded, EAST, light, r, g, b, a, material, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        }
        plain.flip();
        shaded.flip();
        return new Segment(bx, by, bz, quads, plain, shaded, new Object[2]);
    }

    private void face(ByteBuffer plain, ByteBuffer shaded, int face, int light, int r, int g, int b, int a, int material,
                      int... corners) {
        float shade = shading[face];
        int sr = Math.round(r * shade), sg = Math.round(g * shade), sb = Math.round(b * shade);
        for (int winding = 0; winding < 2; winding++) {
            for (int i = 0; i < 4; i++) {
                int corner = winding == 0 ? i : 3 - i;
                int cx = corners[corner * 3], cy = corners[corner * 3 + 1], cz = corners[corner * 3 + 2];
                vertex(plain, cx, cy, cz, light, r, g, b, a, material, face);
                vertex(shaded, cx, cy, cz, light, sr, sg, sb, a, material, face);
            }
        }
    }

    private static void vertex(ByteBuffer out, int x, int y, int z, int light, int r, int g, int b, int a, int material, int face) {
        out.putShort((short) x).putShort((short) y).putShort((short) z).putShort((short) light);
        out.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        out.putInt(material | face << 8);
    }

    @Override
    public void uploadDataToGpu() {
        RenderSystem.assertOnRenderThread();
        var segments = pending;
        if (segments == null) return;
        pending = null;
        release(uploaded, true);
        uploaded = segments;
    }

    /** The CPU build waiting for the next upload (tests). */
    List<Segment> pendingSegments() { var segments = pending; return segments == null ? List.of() : segments; }

    /** Render thread: the segments to draw, uploading the requested variant on first use. */
    List<Segment> segments(boolean shadedVariant) {
        RenderSystem.assertOnRenderThread();
        int index = shadedVariant ? 1 : 0;
        for (Segment segment : uploaded) {
            if (segment.gpu()[index] != null) continue;
            ByteBuffer data = shadedVariant ? segment.shaded() : segment.plain();
            if (data == null || segment.quads() == 0) continue;
            Object buffer = Compat.createGpuVertexBuffer(data.remaining());
            data.position(0);
            Compat.copyBuffer(buffer, data, data.remaining());
            segment.gpu()[index] = buffer;
        }
        return uploaded;
    }

    private static void release(List<Segment> segments, boolean gpu) {
        for (Segment segment : segments) {
            if (gpu) for (Object buffer : segment.gpu()) if (buffer != null) Compat.scheduleFree(buffer);
            MemoryUtil.memFree(segment.plain());
            MemoryUtil.memFree(segment.shaded());
        }
    }

    @Override public EState getState() { return state; }
    @Override public void setState(EState state) { this.state = state; }

    @Override
    public void close() {
        var segments = pending;
        pending = null;
        if (segments != null) release(segments, false);
        if (uploaded.isEmpty()) return;
        if (RenderSystem.isOnRenderThread()) {
            release(uploaded, true);
            uploaded = List.of();
        } else {
            var stale = uploaded;
            uploaded = List.of();
            RenderSystem.recordRenderCall(() -> release(stale, true));
        }
    }
}
