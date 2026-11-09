package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ShaderTerrainVertexTest {
    private static final float[][] SLOPE = {{0,0,0}, {1,0,0}, {1,1,1}, {0,1,1}};

    @Test void preservesMaterialLightTintMidpointsAndOriginalGeometry() {
        ByteBuffer bytes = MemoryUtil.memAlloc(4 * ShaderTerrainVertex.STRIDE).order(ByteOrder.nativeOrder());
        try {
            var writer = new ShaderTerrainVertex();
            writer.block(100000, true, 15, 0, 0, 0);
            quad(writer, bytes, SLOPE, false, 0xff123456, 0x80112233);
            for (int i = 0; i < 4; i++) {
                int at = i * ShaderTerrainVertex.STRIDE;
                assertEquals(100000, bytes.getInt(at + ShaderTerrainVertex.MATERIAL));
                assertEquals(1, bytes.getInt(at + ShaderTerrainVertex.FLUID));
                assertEquals(0xff123456, bytes.getInt(at + 8));
                assertEquals(0x80112233, bytes.getInt(at + ShaderTerrainVertex.TINT_AO));
                assertEquals(0xf080, Short.toUnsignedInt(bytes.getShort(at + 6)));
                assertEquals(16384, Short.toUnsignedInt(bytes.getShort(at + ShaderTerrainVertex.MID_UV)));
                assertEquals(16384, Short.toUnsignedInt(bytes.getShort(at + ShaderTerrainVertex.MID_UV + 2)));
                int mid = bytes.getInt(at + ShaderTerrainVertex.MID_BLOCK);
                assertEquals(Math.round((.5f - SLOPE[i][0]) * 64), (byte) mid);
                assertEquals(Math.round((.5f - SLOPE[i][1]) * 64), (byte) (mid >>> 8));
                assertEquals(Math.round((.5f - SLOPE[i][2]) * 64), (byte) (mid >>> 16));
                assertEquals(15, mid >>> 24);
                double[] n = vector(bytes.getInt(at + ShaderTerrainVertex.NORMAL));
                assertArrayEquals(new double[]{0, -Math.sqrt(.5), Math.sqrt(.5)}, n, .009);
            }
            writer.requireComplete();
        } finally { MemoryUtil.memFree(bytes); }
    }

    @Test void mirroredTextureReversesHandednessWithoutChangingSurfaceNormal() {
        ByteBuffer bytes = MemoryUtil.memAlloc(4 * ShaderTerrainVertex.STRIDE);
        try {
            var writer = new ShaderTerrainVertex();
            quad(writer, bytes, SLOPE, false, -1, -1);
            int normal = bytes.getInt(ShaderTerrainVertex.NORMAL), tangent = bytes.getInt(ShaderTerrainVertex.TANGENT);
            quad(writer, bytes, SLOPE, true, -1, -1);
            assertEquals(normal, bytes.getInt(ShaderTerrainVertex.NORMAL));
            int mirrored = bytes.getInt(ShaderTerrainVertex.TANGENT);
            assertEquals(-(byte) (tangent >>> 24), (byte) (mirrored >>> 24));
            assertEquals(-(byte) tangent, (byte) mirrored);
        } finally { MemoryUtil.memFree(bytes); }
    }

    @Test void randomSurfacesHaveFiniteOrthogonalFramesAndWindingReversesNormals() {
        ByteBuffer bytes = MemoryUtil.memAlloc(4 * ShaderTerrainVertex.STRIDE);
        try {
            var writer = new ShaderTerrainVertex();
            Random random = new Random(0x56534448);
            for (int iteration = 0; iteration < 500; iteration++) {
                float ax = random.nextFloat(), ay = random.nextFloat(), az = random.nextFloat();
                float bx = random.nextFloat(), by = random.nextFloat(), bz = random.nextFloat();
                float[][] face = {{0,0,0}, {ax,ay,az}, {ax+bx,ay+by,az+bz}, {bx,by,bz}};
                quad(writer, bytes, face, false, -1, -1);
                double[] n = vector(bytes.getInt(ShaderTerrainVertex.NORMAL));
                double[] t = vector(bytes.getInt(ShaderTerrainVertex.TANGENT));
                assertEquals(1, dot(n,n), .02); assertEquals(1, dot(t,t), .02);
                assertEquals(0, dot(n,t), .02);
                float[] swap = face[1]; face[1] = face[3]; face[3] = swap;
                quad(writer, bytes, face, false, -1, -1);
                double[] reverse = vector(bytes.getInt(ShaderTerrainVertex.NORMAL));
                for (int axis = 0; axis < 3; axis++) assertEquals(-n[axis], reverse[axis], .009);
            }
        } finally { MemoryUtil.memFree(bytes); }
    }

    @Test void degenerateGeometryAndUvRemainFiniteAndPartialQuadsCannotChangeContext() {
        ByteBuffer bytes = MemoryUtil.memAlloc(4 * ShaderTerrainVertex.STRIDE);
        try {
            var writer = new ShaderTerrainVertex();
            long ptr = MemoryUtil.memAddress(bytes);
            writer.vertex(ptr, 0,0,0, -1, 0,0, 0,0);
            assertThrows(IllegalStateException.class, writer::requireComplete);
            assertThrows(IllegalStateException.class, () -> writer.block(1, false, 0, 0,0,0));
            writer.reset();
            for (int i = 0; i < 4; i++) writer.vertex(ptr + (long) i * ShaderTerrainVertex.STRIDE, 0,0,0, -1,0,0,0,0);
            double[] n = vector(bytes.getInt(ShaderTerrainVertex.NORMAL));
            double[] t = vector(bytes.getInt(ShaderTerrainVertex.TANGENT));
            assertEquals(1, dot(n,n), .02); assertEquals(1, dot(t,t), .02); assertEquals(0, dot(n,t), .02);
            assertEquals(-1, bytes.getInt(ShaderTerrainVertex.MATERIAL));
            assertThrows(IllegalArgumentException.class, () -> writer.vertex(ptr, Float.NaN,0,0, -1,0,0,0,0));
        } finally { MemoryUtil.memFree(bytes); }
    }

    private static void quad(ShaderTerrainVertex writer, ByteBuffer bytes, float[][] xyz, boolean mirror, int color, int ao) {
        for (int i = 0; i < 4; i++) {
            float u = i == 1 || i == 2 ? 1 : 0;
            writer.vertex(MemoryUtil.memAddress(bytes) + (long) i * ShaderTerrainVertex.STRIDE,
                    xyz[i][0], xyz[i][1], xyz[i][2], color, mirror ? 1-u : u, i >= 2 ? 1 : 0,
                    0x00f00080, 0, ao);
        }
    }
    private static double[] vector(int packed) { return new double[]{(byte) packed / 127.0, (byte) (packed >>> 8) / 127.0, (byte) (packed >>> 16) / 127.0}; }
    private static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
}
