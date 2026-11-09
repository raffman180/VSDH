package net.vulkanmod.render.chunk.build.light.data;





public class QuadLightData {



    public final float[] br = new float[4];
    /** Occlusion before directional vanilla shading; shader separateAo uses this in alpha. */
    public final float[] ao = new float[4];
    public float directionalShade = 1;




    public final int[] lm = new int[4];
}
