package net.vulkanmod.vulkan.texture;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Staging only the range must hand the buffer-to-image copy the same pixels as staging the whole buffer and
 * starting the copy at the skip offset (the path before, kept behind -Dvsdh.tightSpriteUpload=false).
 */
class SubImageRangeTest {
    /** What vkCmdCopyBufferToImage reads: {@code height} rows of {@code width} pixels, rows {@code rowLength} apart. */
    private static byte[] copied(ByteBuffer staged, long offset, int width, int height, int rowLength, int pixelBytes) {
        byte[] pixels = new byte[width * height * pixelBytes];
        for (int y = 0; y < height; y++)
            for (int i = 0; i < width * pixelBytes; i++)
                pixels[y * width * pixelBytes + i] = staged.get((int) (offset + (long) y * rowLength * pixelBytes + i));
        return pixels;
    }

    @Test
    void sameCopiedPixelsAsStagingTheWholeBuffer() {
        var random = new Random(11);
        int tighter = 0;
        for (int round = 0; round < 20_000; round++) {
            int pixelBytes = 1 + random.nextInt(4);
            int sheetWidth = 1 + random.nextInt(40), sheetHeight = 1 + random.nextInt(70);
            byte[] bytes = new byte[sheetWidth * sheetHeight * pixelBytes];
            random.nextBytes(bytes);
            ByteBuffer sheet = ByteBuffer.wrap(bytes);
            int width = 1 + random.nextInt(sheetWidth), height = 1 + random.nextInt(sheetHeight);
            int skipPixels = random.nextInt(sheetWidth - width + 1), skipRows = random.nextInt(sheetHeight - height + 1);

            // Before: the whole buffer is staged and the copy starts at the skip offset.
            long oldOffset = ((long) sheetWidth * skipRows + skipPixels) * pixelBytes;
            byte[] expected = copied(sheet, oldOffset, width, height, sheetWidth, pixelBytes);

            long start = SubImageRange.start(width, height, skipRows, skipPixels, sheetWidth, pixelBytes, sheet.limit());
            long length = SubImageRange.length(width, height, sheetWidth, pixelBytes);
            assertEquals(oldOffset, start);
            assertTrue(start + length <= sheet.limit());
            ByteBuffer staged = sheet.slice((int) start, (int) length);
            assertEquals(length, staged.limit());
            assertArrayEquals(expected, copied(staged, 0, width, height, sheetWidth, pixelBytes));
            if (length < sheet.limit()) tighter++;
        }
        assertTrue(tighter > 15_000, "most sub-images must stage less than the sheet");
    }

    @Test
    void oneFrameOfAnAnimationSheet() {
        // 16x16 frames stacked 32 high, RGBA: frame 5 is 1 KiB of a 32 KiB sheet.
        assertEquals(5 * 16 * 16 * 4, SubImageRange.start(16, 16, 5 * 16, 0, 16, 4, 16 * 512 * 4));
        assertEquals(16 * 16 * 4, SubImageRange.length(16, 16, 16, 4));
    }

    @Test
    void regionsOutsideTheBufferKeepTheOldPath() {
        assertEquals(-1, SubImageRange.start(16, 16, 31 * 16 + 1, 0, 16, 4, 16 * 512 * 4)); // one row too far
        assertEquals(-1, SubImageRange.start(16, 16, 0, 0, 0, 4, 4096));  // row length not given
        assertEquals(-1, SubImageRange.start(16, 16, 0, 0, 8, 4, 4096));  // rows shorter than the region
        assertEquals(-1, SubImageRange.start(0, 16, 0, 0, 16, 4, 4096));
        assertEquals(-1, SubImageRange.start(16, 16, -1, 0, 16, 4, 4096));
        assertEquals(-1, SubImageRange.start(16, 16, 0, 0, 16, 4, 1023)); // buffer smaller than its pixel format says
        assertEquals(0, SubImageRange.start(16, 16, 0, 0, 16, 4, 1024));
        assertEquals(-1, SubImageRange.start(40_000, 40_000, 0, 0, 40_000, 4, Long.MAX_VALUE)); // beyond an int offset
    }
}
