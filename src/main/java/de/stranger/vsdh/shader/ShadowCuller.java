package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.ShadowCullState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * Decides which terrain boxes may write into the shadow map this frame.
 * <p>
 * Shader packs are tuned against the casters Iris hands them, so the shape is chosen from the
 * same inputs: {@code shadow.culling}, a geometry stage on the shadow program (voxelisation),
 * {@code shadowDistance * shadowDistanceRenderMul} and {@code voxelDistance}. Everything is
 * measured camera-relative.
 * <ul>
 * <li>{@link Shape#DISTANCE}: an axis-aligned cube around the camera, or nothing at all when that
 * cube reaches past the render distance.</li>
 * <li>{@link Shape#SWEPT}: the camera frustum swept towards the light. A box that can neither be
 * seen nor lie between the light and something seen cannot cast a visible direct shadow.
 * Optionally bounded by the cube.</li>
 * <li>{@link Shape#SAFE_ZONE}: the swept volume, bounded by the cube, plus a near cube of
 * {@code voxelDistance} that is always kept.</li>
 * </ul>
 * The swept volume follows L. Spiro's directional-light caster culling: keep each frustum plane
 * whose inside faces the light and close the volume with planes through the silhouette edges,
 * parallel to the light. Edge planes are oriented against an interior point instead of relying on
 * plane order, and degenerate edges are skipped, which only keeps more geometry.
 * <p>
 * {@link #visible} is monotone: a box that contains a visible box is itself visible. Callers may
 * therefore reject a whole area first and test its sections afterwards without changing the result.
 */
public final class ShadowCuller {
    public enum Shape { NONE, DISTANCE, SWEPT, SAFE_ZONE }

    private static final float EPSILON = 1.0e-6f;
    private final Shape shape;
    /** Half extent of the bounding cube; infinite when unbounded. */
    private final float limit;
    /** Half extent of the always-kept cube for {@link Shape#SAFE_ZONE}. */
    private final float keep;
    private final float[] planes = new float[4 * 13];
    private int planeCount;

    private ShadowCuller(Shape shape, float limit, float keep) {
        this.shape = shape;
        this.limit = limit;
        this.keep = keep;
    }

    /** Draws every loaded section, used when no shadow pass inputs are available. */
    public static ShadowCuller none() {
        return new ShadowCuller(Shape.NONE, Float.POSITIVE_INFINITY, 0);
    }

    /**
     * @param state the pack's {@code shadow.culling} state
     * @param voxelizes whether the shadow program has a geometry stage
     * @param halfPlane the pack's {@code shadowDistance}
     * @param renderMul the pack's {@code shadowDistanceRenderMul}, negative when undeclared
     * @param voxelDistance the pack's {@code voxelDistance}
     * @param renderDistance the effective block render distance
     * @param cameraClip camera-relative projection * view with GL clip depth (-w..w)
     * @param towardLight camera-relative direction from the scene towards the shadow light
     */
    public static ShadowCuller create(ShadowCullState state, boolean voxelizes, float halfPlane, float renderMul,
                                      float voxelDistance, float renderDistance, Matrix4fc cameraClip,
                                      Vector3fc towardLight) {
        if (state == null || !Float.isFinite(halfPlane) || !Float.isFinite(renderMul)
                || !Float.isFinite(voxelDistance) || !(renderDistance > 0) || !cameraClip.isFinite()
                || !towardLight.isFinite() || towardLight.lengthSquared() < EPSILON)
            throw new IllegalArgumentException("Invalid shadow culling inputs");
        if (state == ShadowCullState.DISTANCE || state == ShadowCullState.DEFAULT && voxelizes) {
            float distance = halfPlane * renderMul;
            if (distance <= 0 || distance > renderDistance) return none();
            return new ShadowCuller(Shape.DISTANCE, distance, 0);
        }
        boolean safeZone = state == ShadowCullState.SAFE_ZONE;
        float multiplier = safeZone && renderMul < 0 ? 1 : renderMul;
        ShadowCuller culler;
        if (safeZone) {
            // The near cube is always kept, the distance cube always bounds the result.
            culler = new ShadowCuller(Shape.SAFE_ZONE, halfPlane * multiplier, voxelDistance * multiplier);
        } else {
            // A negative multiplier leaves the reach to the player; Minecraft's render distance is it here.
            float distance = multiplier < 0 ? renderDistance : halfPlane * multiplier;
            culler = new ShadowCuller(Shape.SWEPT, distance >= renderDistance ? Float.POSITIVE_INFINITY : distance, 0);
        }
        culler.sweep(cameraClip, new Vector3f(towardLight).normalize());
        return culler;
    }

    public Shape shape() { return shape; }
    public float limit() { return limit; }
    public float keep() { return keep; }
    int planeCount() { return planeCount; }

    /** Camera-relative box test; true when the box may contribute to the shadow map. */
    public boolean visible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return switch (shape) {
            case NONE -> true;
            case DISTANCE -> !outsideCube(limit, minX, minY, minZ, maxX, maxY, maxZ);
            case SWEPT -> !outsideCube(limit, minX, minY, minZ, maxX, maxY, maxZ)
                    && insidePlanes(minX, minY, minZ, maxX, maxY, maxZ);
            case SAFE_ZONE -> !outsideCube(limit, minX, minY, minZ, maxX, maxY, maxZ)
                    && (!outsideCube(keep, minX, minY, minZ, maxX, maxY, maxZ)
                        || insidePlanes(minX, minY, minZ, maxX, maxY, maxZ));
        };
    }

    private static boolean outsideCube(float half, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return maxX < -half || minX > half || maxY < -half || minY > half || maxZ < -half || minZ > half;
    }

    private boolean insidePlanes(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int i = 0; i < planeCount; i++) {
            int o = i * 4;
            float a = planes[o], b = planes[o + 1], c = planes[o + 2], d = planes[o + 3];
            // The corner furthest along the inward normal decides whether any part is inside.
            float px = a < 0 ? minX : maxX, py = b < 0 ? minY : maxY, pz = c < 0 ? minZ : maxZ;
            if (a * px + b * py + c * pz + d < 0) return false;
        }
        return true;
    }

    private void sweep(Matrix4fc clip, Vector3f light) {
        // Inward planes from GL clip space, paired as left/right, bottom/top, near/far.
        Vector4f[] frustum = new Vector4f[6];
        for (int axis = 0; axis < 3; axis++) {
            frustum[axis * 2] = plane(clip, axis, 1);
            frustum[axis * 2 + 1] = plane(clip, axis, -1);
        }
        Vector3f interior = interior(clip);
        boolean[] usable = new boolean[6];
        boolean[] kept = new boolean[6];
        for (int i = 0; i < 6; i++) {
            usable[i] = frustum[i] != null;
            if (!usable[i]) continue;
            float facing = frustum[i].x * light.x + frustum[i].y * light.y + frustum[i].z * light.z;
            // Moving towards the light never leaves a plane whose inside faces the light.
            kept[i] = facing >= 0;
            if (kept[i]) add(frustum[i].x, frustum[i].y, frustum[i].z, frustum[i].w);
        }
        for (int i = 0; i < 6; i++) {
            if (!usable[i] || !kept[i]) continue;
            for (int j = 0; j < 6; j++) {
                if (j / 2 == i / 2 || !usable[j] || kept[j]) continue;
                edge(frustum[i], frustum[j], light, interior);
            }
        }
    }

    private void edge(Vector4f back, Vector4f front, Vector3f light, Vector3f interior) {
        Vector3f n1 = new Vector3f(back.x, back.y, back.z), n2 = new Vector3f(front.x, front.y, front.z);
        Vector3f line = new Vector3f(n1).cross(n2);
        float lineLength = line.lengthSquared();
        if (lineLength < EPSILON) return;
        Vector3f normal = new Vector3f(line).cross(light);
        if (normal.lengthSquared() < EPSILON) return;
        normal.normalize();
        // A point on both planes: n1.p = -d1 and n2.p = -d2.
        Vector3f point = new Vector3f(n2).cross(line).mul(-back.w)
                .add(new Vector3f(line).cross(n1).mul(-front.w)).div(lineLength);
        float d = -normal.dot(point);
        if (normal.dot(interior) + d < 0) {
            normal.negate();
            d = -d;
        }
        add(normal.x, normal.y, normal.z, d);
    }

    private void add(float a, float b, float c, float d) {
        if (planeCount * 4 >= planes.length) throw new IllegalStateException("Too many shadow culling planes");
        int o = planeCount++ * 4;
        planes[o] = a; planes[o + 1] = b; planes[o + 2] = c; planes[o + 3] = d;
    }

    /** Row 3 plus or minus one other row, normalised by its normal; null for a degenerate plane. */
    private static Vector4f plane(Matrix4fc m, int row, float sign) {
        float a = m.m03() + sign * component(m, 0, row), b = m.m13() + sign * component(m, 1, row);
        float c = m.m23() + sign * component(m, 2, row), d = m.m33() + sign * component(m, 3, row);
        float length = (float) Math.sqrt(a * a + b * b + c * c);
        if (!(length > EPSILON) || !Float.isFinite(length) || !Float.isFinite(d)) return null;
        return new Vector4f(a / length, b / length, c / length, d / length);
    }

    /** Element of the given column and row, JOML naming m[column][row]. */
    private static float component(Matrix4fc m, int column, int row) {
        return switch (column * 4 + row) {
            case 0 -> m.m00(); case 1 -> m.m01(); case 2 -> m.m02();
            case 4 -> m.m10(); case 5 -> m.m11(); case 6 -> m.m12();
            case 8 -> m.m20(); case 9 -> m.m21(); case 10 -> m.m22();
            case 12 -> m.m30(); case 13 -> m.m31(); case 14 -> m.m32();
            default -> throw new IllegalArgumentException("Not a clip row: " + row);
        };
    }

    /**
     * Centroid of the near corners and the corners at clip depth zero, strictly inside a
     * non-degenerate frustum. Depth zero stays finite for an infinite far plane as well.
     */
    private static Vector3f interior(Matrix4fc clip) {
        Matrix4f inverse = new Matrix4f(clip).invert();
        if (!inverse.isFinite()) throw new IllegalArgumentException("Camera frustum is singular");
        Vector3f sum = new Vector3f();
        Vector4f corner = new Vector4f();
        for (int i = 0; i < 8; i++) {
            corner.set((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 0, 1);
            inverse.transform(corner);
            if (!(Math.abs(corner.w) > EPSILON)) throw new IllegalArgumentException("Camera frustum has no finite corners");
            sum.add(corner.x / corner.w, corner.y / corner.w, corner.z / corner.w);
        }
        return sum.div(8);
    }
}
