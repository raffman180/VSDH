package net.vulkanmod.vulkan.texture;

/**
 * The bytes of a pixel buffer that a sub-image upload with GL unpack parameters actually reads.
 *
 * <p>An animated sprite uploads one frame out of the sheet that holds all of its frames (skip rows / skip pixels /
 * row length). Staging the whole sheet for every frame, mip level and tick copied many times the bytes the GPU copy
 * reads: a 32-frame sprite staged 32 times its frame. The range here is exactly what
 * {@code vkCmdCopyBufferToImage} reads for the region (the last row ends after {@code width} pixels), so staging
 * only this range and starting the copy at its first byte uploads the same pixels.
 * {@code -Dvsdh.tightSpriteUpload=false} stages the whole buffer as before (A/B checks).
 */
public final class SubImageRange {
    public static final boolean ENABLED = !"false".equals(System.getProperty("vsdh.tightSpriteUpload"));

    private SubImageRange() {}

    /** First byte read, or -1 when the parameters do not describe a region inside {@code bufferBytes}. */
    public static long start(int width, int height, int skipRows, int skipPixels, int rowLength, int pixelBytes,
                             long bufferBytes) {
        if (width < 1 || height < 1 || skipRows < 0 || skipPixels < 0 || rowLength < width || pixelBytes < 1) return -1;
        long start = ((long) rowLength * skipRows + skipPixels) * pixelBytes;
        long length = length(width, height, rowLength, pixelBytes);
        // The byte offset of the copy is an int in the existing path; keep to what it can express.
        if (start + length > bufferBytes || start > Integer.MAX_VALUE || length > Integer.MAX_VALUE) return -1;
        return start;
    }

    /** Bytes read from the first one: full rows of {@code rowLength} pixels, the last row only {@code width}. */
    public static long length(int width, int height, int rowLength, int pixelBytes) {
        return ((long) (height - 1) * rowLength + width) * pixelBytes;
    }
}
