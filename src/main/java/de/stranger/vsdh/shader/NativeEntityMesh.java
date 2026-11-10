package de.stranger.vsdh.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.vitrail.glsl.EntityVertex;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** Staging memory for opaque Minecraft entity quads. Indices keep their original vertex order. */
public final class NativeEntityMesh implements AutoCloseable {
    private static final int BASE = 36;
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .add(EntityVertex.IDENTIFIERS, new VertexFormatElement(6, 0, VertexFormatElement.Type.USHORT,
                    VertexFormatElement.Usage.GENERIC, 4))
            .add(EntityVertex.MID_TEX_COORD, new VertexFormatElement(7, 3, VertexFormatElement.Type.FLOAT,
                    VertexFormatElement.Usage.UV, 2))
            .add(EntityVertex.TANGENT, new VertexFormatElement(8, 0, VertexFormatElement.Type.BYTE,
                    VertexFormatElement.Usage.NORMAL, 4))
            .build();

    public record Batch(short[] ids, boolean blocks) {}

    public interface EntityIds {
        void vsdh$setEntityIds(Batch batch);
        Batch vsdh$getEntityIds();
    }

    private final Vector3f tangent = new Vector3f();
    private final Vector3f bitangent = new Vector3f();
    private final Vector3f normal = new Vector3f();
    private final Vector3f edge1 = new Vector3f();
    private final Vector3f edge2 = new Vector3f();
    private ByteBuffer data;

    ByteBuffer extend(MeshData mesh, short[] ids, boolean blocks) {
        return extend(mesh, ids.length, index -> blocks ? (long) Short.toUnsignedInt(ids[index]) << 16 : Short.toUnsignedInt(ids[index]));
    }

    ByteBuffer extendHand(MeshData mesh, long identifiers) {
        return extend(mesh, mesh.drawState().vertexCount(), index -> identifiers);
    }

    private ByteBuffer extend(MeshData mesh, int materialCount, java.util.function.IntToLongFunction identifiers) {
        var state = mesh.drawState();
        if (state.format() != DefaultVertexFormat.NEW_ENTITY || state.format().getVertexSize() != BASE
                || state.vertexCount() % 4 != 0 || state.vertexCount() > materialCount)
            throw new IllegalArgumentException("Invalid Minecraft entity quad batch");
        int stride = FORMAT.getVertexSize();
        int bytes = Math.multiplyExact(stride, state.vertexCount());
        if (data == null || data.capacity() < bytes) data = MemoryUtil.memRealloc(data, bytes);
        data.limit(bytes);
        var source = mesh.vertexBuffer();
        long input = MemoryUtil.memAddress(source);
        long output = MemoryUtil.memAddress(data);
        for (int vertex = 0; vertex < state.vertexCount(); vertex += 4) {
            int a = vertex * BASE, b = a + BASE, c = b + BASE, d = c + BASE;
            float u = source.getFloat(a + 16), v = source.getFloat(a + 20);
            float u1 = source.getFloat(b + 16), v1 = source.getFloat(b + 20);
            float u2 = source.getFloat(c + 16), v2 = source.getFloat(c + 20);
            float midU = (u + u1 + u2 + source.getFloat(d + 16)) * .25f;
            float midV = (v + v1 + v2 + source.getFloat(d + 20)) * .25f;
            int packedTangent = tangent(source, a, b, c, u1 - u, v1 - v, u2 - u, v2 - v);
            for (int corner = 0; corner < 4; corner++) {
                int index = vertex + corner, offset = index * stride;
                MemoryUtil.memCopy(input + index * BASE, output + offset, BASE);
                long ids = identifiers.applyAsLong(index);
                data.putShort(offset + 36, (short) ids);
                data.putShort(offset + 38, (short) (ids >>> 16));
                data.putShort(offset + 40, (short) (ids >>> 32));
                data.putShort(offset + 42, (short) 0);
                data.putFloat(offset + 44, midU).putFloat(offset + 48, midV);
                data.putInt(offset + 52, packedTangent);
            }
        }
        return data;
    }

    private int tangent(ByteBuffer source, int a, int b, int c, float du1, float dv1, float du2, float dv2) {
        position(edge1, source, b).sub(source.getFloat(a), source.getFloat(a + 4), source.getFloat(a + 8));
        position(edge2, source, c).sub(source.getFloat(a), source.getFloat(a + 4), source.getFloat(a + 8));
        normal.set(source.get(a + 32), source.get(a + 33), source.get(a + 34));
        float determinant = du1 * dv2 - du2 * dv1;
        if (Math.abs(determinant) < 1.0e-8f || normal.lengthSquared() == 0) return 0x7F00007F;
        normal.normalize();
        tangent.set(edge1).mul(dv2).fma(-dv1, edge2).div(determinant);
        tangent.fma(-tangent.dot(normal), normal);
        if (tangent.lengthSquared() < 1.0e-12f) return 0x7F00007F;
        tangent.normalize();
        bitangent.set(edge2).mul(du1).fma(-du2, edge1).div(determinant);
        int sign = normal.cross(tangent).dot(bitangent) < 0 ? -127 : 127;
        return pack(tangent.x) | pack(tangent.y) << 8 | pack(tangent.z) << 16 | (sign & 255) << 24;
    }

    private static Vector3f position(Vector3f result, ByteBuffer source, int offset) {
        return result.set(source.getFloat(offset), source.getFloat(offset + 4), source.getFloat(offset + 8));
    }

    private static int pack(float component) { return Math.round(component * 127) & 255; }

    @Override public void close() {
        MemoryUtil.memFree(data);
        data = null;
    }
}
