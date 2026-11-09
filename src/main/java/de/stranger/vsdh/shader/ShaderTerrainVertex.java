package de.stranger.vsdh.shader;

import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;

/** VSDH's terrain wire format. CPU workers only; contains no Vulkan resources. */
public final class ShaderTerrainVertex implements VertexBuilder {
    public static final int STRIDE = 44;
    public static final int NORMAL = 16, TANGENT = 20, MATERIAL = 24, FLUID = 28;
    public static final int MID_UV = 32, MID_BLOCK = 36, TINT_AO = 40;
    private final CompressedVertexBuilder base = new CompressedVertexBuilder();
    // Retain the original corners, rather than deriving a slope from quantised positions.
    private final float[] corners = new float[20];
    private int corner, material = -1, emission, fluid;
    private float centerX, centerY, centerZ;
    private long first;
    private int fallbackNormal;

    public void block(int material, boolean fluid, int emission, int x, int y, int z) {
        requireComplete();
        if (material < -1 || emission < 0 || emission > 15)
            throw new IllegalArgumentException("Invalid shader material or emission");
        this.material = material;
        this.fluid = fluid ? 1 : 0;
        this.emission = emission;
        centerX = (x & 15) + .5f;
        centerY = (y & 15) + .5f;
        centerZ = (z & 15) + .5f;
    }

    @Override
    public void vertex(long ptr, float x, float y, float z, int color, float u, float v,
                       int light, int normal) {
        vertex(ptr, x, y, z, color, u, v, light, normal, color);
    }

    public void vertex(long ptr, float x, float y, float z, int color, float u, float v,
                       int light, int normal, int tintAndAo) {
        vertex(ptr, x, y, z, color, u, v, light, normal, tintAndAo, 1);
    }

    public void vertex(long ptr, float x, float y, float z, int color, float u, float v,
                       int light, int normal, int tintAndAo, float directionalShade) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(u) || !Float.isFinite(v) || !Float.isFinite(directionalShade))
            throw new IllegalArgumentException("Non-finite terrain position or texture coordinate");
        if (corner == 0) {
            first = ptr;
            fallbackNormal = normal;
        } else if (ptr != first + (long) corner * STRIDE) {
            throw new IllegalStateException("A terrain quad must occupy four contiguous vertices");
        }
        base.vertex(ptr, x, y, z, color, u, v, light, normal);
        MemoryUtil.memPutInt(ptr + NORMAL, Math.clamp(Math.round(directionalShade * 255), 0, 255) << 24);
        MemoryUtil.memPutInt(ptr + MATERIAL, material);
        MemoryUtil.memPutInt(ptr + FLUID, fluid);
        MemoryUtil.memPutInt(ptr + MID_BLOCK, signedByte((centerX - x) * 64)
                | signedByte((centerY - y) * 64) << 8
                | signedByte((centerZ - z) * 64) << 16 | emission << 24);
        MemoryUtil.memPutInt(ptr + TINT_AO, tintAndAo);
        int at = corner * 5;
        corners[at] = x; corners[at + 1] = y; corners[at + 2] = z;
        corners[at + 3] = u; corners[at + 4] = v;
        if (++corner == 4) {
            finishQuad();
            corner = 0;
        }
    }

    private void finishQuad() {
        // Cross the diagonals: both triangles contribute on a non-planar fluid surface.
        double ax = corners[10] - corners[0], ay = corners[11] - corners[1], az = corners[12] - corners[2];
        double bx = corners[15] - corners[5], by = corners[16] - corners[6], bz = corners[17] - corners[7];
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 1e-12)) {
            nx = (byte) fallbackNormal; ny = (byte) (fallbackNormal >>> 8); nz = (byte) (fallbackNormal >>> 16);
            length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(length > 0)) { nx = 0; ny = 1; nz = 0; length = 1; }
        }
        nx /= length; ny /= length; nz /= length;

        double ex = corners[5] - corners[0], ey = corners[6] - corners[1], ez = corners[7] - corners[2];
        double fx = corners[10] - corners[0], fy = corners[11] - corners[1], fz = corners[12] - corners[2];
        double du = corners[8] - corners[3], dv = corners[9] - corners[4];
        double su = corners[13] - corners[3], sv = corners[14] - corners[4];
        double determinant = du * sv - su * dv;
        double tx = 0, ty = 0, tz = 0, bitX = 0, bitY = 0, bitZ = 0;
        if (Math.abs(determinant) > 1e-15) {
            tx = (ex * sv - fx * dv) / determinant;
            ty = (ey * sv - fy * dv) / determinant;
            tz = (ez * sv - fz * dv) / determinant;
            bitX = (fx * du - ex * su) / determinant;
            bitY = (fy * du - ey * su) / determinant;
            bitZ = (fz * du - ez * su) / determinant;
        }
        double dot = tx * nx + ty * ny + tz * nz;
        tx -= dot * nx; ty -= dot * ny; tz -= dot * nz;
        length = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (!(length > 1e-12)) {
            // Choose an axis that cannot be parallel to the normal (degenerate UV/model).
            if (Math.abs(ny) < .9) { tx = nz; ty = 0; tz = -nx; }
            else { tx = 0; ty = -nz; tz = ny; }
            length = Math.sqrt(tx * tx + ty * ty + tz * tz);
        }
        tx /= length; ty /= length; tz /= length;
        int handedness = ((ny * tz - nz * ty) * bitX + (nz * tx - nx * tz) * bitY
                + (nx * ty - ny * tx) * bitZ) < 0 ? -127 : 127;
        int normal = snorm(nx) | snorm(ny) << 8 | snorm(nz) << 16;
        int tangent = snorm(tx) | snorm(ty) << 8 | snorm(tz) << 16 | (handedness & 255) << 24;
        float u = 0, v = 0;
        for (int i = 0; i < 4; i++) { u += corners[i * 5 + 3]; v += corners[i * 5 + 4]; }
        for (int i = 0; i < 4; i++) {
            long ptr = first + (long) i * STRIDE;
            MemoryUtil.memPutInt(ptr + NORMAL, normal | (MemoryUtil.memGetInt(ptr + NORMAL) & 0xff000000));
            MemoryUtil.memPutInt(ptr + TANGENT, tangent);
            MemoryUtil.memPutShort(ptr + MID_UV, (short) (u * .25f * CompressedVertexBuilder.UV_CONV_MUL));
            MemoryUtil.memPutShort(ptr + MID_UV + 2, (short) (v * .25f * CompressedVertexBuilder.UV_CONV_MUL));
        }
    }

    private static int snorm(double value) { return signedByte((float) (value * 127)); }
    private static int signedByte(float value) { return Math.clamp(Math.round(value), -127, 127) & 255; }
    public void requireComplete() {
        if (corner != 0) throw new IllegalStateException("Incomplete shader terrain quad: " + corner + " vertices");
    }
    public void reset() { corner = 0; }
    @Override public int getStride() { return STRIDE; }
}
