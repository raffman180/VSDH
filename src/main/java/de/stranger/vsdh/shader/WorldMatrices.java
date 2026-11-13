package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Render-thread matrix history. The pack chain keeps OpenGL's orientation: the published projection is
 * the game's own (gbufferProjection[1][1] > 0), images hold their rows bottom-up and texture y, NDC y
 * and gl_FragCoord.y all grow upwards, as under Iris. The finished picture is flipped once into the
 * top-down main target (NativeShaderChain).
 */
public final class WorldMatrices {
    public final Matrix4f view = new Matrix4f(), inverseView = new Matrix4f();
    public final Matrix4f projection = new Matrix4f(), inverseProjection = new Matrix4f();
    public final Matrix4f previousView = new Matrix4f(), previousProjection = new Matrix4f();
    private Object world;
    private int width, height;
    private boolean initialized, pending;
    private double x, y, z, previousX, previousY, previousZ;

    public void begin(Object world, Matrix4fc view, Matrix4fc legacyProjection,
                      double x, double y, double z, int width, int height) {
        if (pending) throw new IllegalStateException("Matrix frame not committed");
        if (world == null || width <= 0 || height <= 0 || !view.isFinite() || !legacyProjection.isFinite()
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Invalid world matrices: world="+(world!=null)+", extent="+width+"x"+height
                    +", viewFinite="+view.isFinite()+", projectionFinite="+legacyProjection.isFinite()+", camera="+x+","+y+","+z);
        this.view.set(view); this.view.invert(inverseView);
        projection.set(legacyProjection);
        // Keep GL -w..w depth here: the translated vertex epilogue performs the one and only
        // forward 0..w conversion.
        projection.invert(inverseProjection);
        if (!inverseView.isFinite() || !inverseProjection.isFinite()) throw new IllegalArgumentException("Singular world matrix");
        boolean reset = !initialized || this.world != world || this.width != width || this.height != height
                || Math.abs(x-previousX) > 128 || Math.abs(y-previousY) > 128 || Math.abs(z-previousZ) > 128;
        this.x=x; this.y=y; this.z=z;
        if (reset) { previousView.set(this.view); previousProjection.set(projection); previousX=x; previousY=y; previousZ=z; }
        this.world=world; this.width=width; this.height=height;
        initialized=true; pending=true;
    }
    public void commit() {
        if (!pending) throw new IllegalStateException("No matrix frame");
        previousView.set(view); previousProjection.set(projection);
        previousX=x; previousY=y; previousZ=z; pending=false;
    }
    public void reset() { initialized=false; pending=false; world=null; }
    public double x() { return x; } public double y() { return y; } public double z() { return z; }
    public double previousX() { return previousX; } public double previousY() { return previousY; } public double previousZ() { return previousZ; }
}
