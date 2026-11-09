package de.stranger.vsdh.shader;

/** Absolute camera split: floor rather than truncation is required west/below the world origin. */
public final class CameraCoordinates {
    private CameraCoordinates() {}
    public static void split(double x,double y,double z,int[] whole,float[] fraction) {
        if(whole.length!=3 || fraction.length!=3)throw new IllegalArgumentException("Camera split requires three components");
        axis(x,0,whole,fraction);axis(y,1,whole,fraction);axis(z,2,whole,fraction);
    }
    private static void axis(double value,int axis,int[] whole,float[] fraction) {
        if(!Double.isFinite(value) || value<Integer.MIN_VALUE || value>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Camera coordinate is outside the integer uniform range");
        double floor=Math.floor(value);whole[axis]=(int)floor;fraction[axis]=(float)(value-floor);
    }
}
