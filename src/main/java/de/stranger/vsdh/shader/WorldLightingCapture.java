package de.stranger.vsdh.shader;

/** Actual last lightmap darkness value; updated on the render thread by the 1.21.1 mixin. */
public final class WorldLightingCapture {
    private static float darkness;
    private WorldLightingCapture() {}
    public static void darkness(float value) { darkness=value; }
    public static float darkness() { return darkness; }
}
