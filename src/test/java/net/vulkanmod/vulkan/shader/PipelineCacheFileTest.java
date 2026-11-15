package net.vulkanmod.vulkan.shader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineCacheFileTest {
    private static final int VENDOR = 0x10DE, DEVICE = 0x2684;
    private static final byte[] UUID = uuid(7);
    private static final long SECOND = 1_000_000_000L, QUIET = 3 * SECOND, LATEST = 20 * SECOND;

    private static byte[] uuid(int seed) {
        byte[] uuid = new byte[16];
        for (int i = 0; i < 16; i++) uuid[i] = (byte) (seed * 31 + i * 17 + 200);
        return uuid;
    }

    private static byte[] file(int vendor, int device, byte[] uuid, int payload) {
        byte[] data = Arrays.copyOf(PipelineCacheFile.header(vendor, device, uuid), 32 + payload);
        for (int i = 32; i < data.length; i++) data[i] = (byte) i;
        return data;
    }

    @Test
    void headerIsThirtyTwoLittleEndianBytes() {
        byte[] header = PipelineCacheFile.header(VENDOR, DEVICE, UUID);
        assertEquals(32, header.length);
        assertArrayEquals(new byte[]{32, 0, 0, 0, 1, 0, 0, 0, (byte) 0xDE, 0x10, 0, 0, (byte) 0x84, 0x26, 0, 0},
                Arrays.copyOf(header, 16));
        assertArrayEquals(UUID, Arrays.copyOfRange(header, 16, 32));
    }

    @Test
    void acceptsTheCurrentDevice() {
        assertNull(PipelineCacheFile.rejection(file(VENDOR, DEVICE, UUID, 4096), VENDOR, DEVICE, UUID));
        assertNull(PipelineCacheFile.rejection(file(VENDOR, DEVICE, UUID, 0), VENDOR, DEVICE, UUID));
        // Ids with the top bit set compare as raw 32-bit values.
        assertNull(PipelineCacheFile.rejection(file(0x80000001, -2, UUID, 8), 0x80000001, -2, UUID));
    }

    @Test
    void rejectsAnotherVendorDeviceOrDriver() {
        byte[] data = file(VENDOR, DEVICE, UUID, 64);
        assertNotNull(PipelineCacheFile.rejection(data, 0x1002, DEVICE, UUID));
        assertNotNull(PipelineCacheFile.rejection(data, VENDOR, DEVICE + 1, UUID));
        assertNotNull(PipelineCacheFile.rejection(data, VENDOR, DEVICE, uuid(8)));
        byte[] lastByte = UUID.clone();
        lastByte[15] ^= 1;
        assertNotNull(PipelineCacheFile.rejection(data, VENDOR, DEVICE, lastByte));
    }

    @Test
    void rejectsEmptyTruncatedAndCorruptFiles() {
        assertNotNull(PipelineCacheFile.rejection(null, VENDOR, DEVICE, UUID));
        assertNotNull(PipelineCacheFile.rejection(new byte[0], VENDOR, DEVICE, UUID));
        byte[] data = file(VENDOR, DEVICE, UUID, 64);
        for (int length = 1; length < 32; length++) {
            assertNotNull(PipelineCacheFile.rejection(Arrays.copyOf(data, length), VENDOR, DEVICE, UUID), "length " + length);
        }
        byte[] wrongSize = data.clone();
        wrongSize[0] = 16;
        assertNotNull(PipelineCacheFile.rejection(wrongSize, VENDOR, DEVICE, UUID));
        byte[] hugeSize = data.clone();
        hugeSize[3] = (byte) 0xFF;
        assertNotNull(PipelineCacheFile.rejection(hugeSize, VENDOR, DEVICE, UUID));
        byte[] wrongVersion = data.clone();
        wrongVersion[4] = 2;
        assertNotNull(PipelineCacheFile.rejection(wrongVersion, VENDOR, DEVICE, UUID));
        assertNotNull(PipelineCacheFile.rejection(new byte[4096], VENDOR, DEVICE, UUID)); // zero-filled file
        assertNotNull(PipelineCacheFile.rejection(data, VENDOR, DEVICE, null));
        assertNotNull(PipelineCacheFile.rejection(data, VENDOR, DEVICE, new byte[8]));
    }

    @Test
    void savesAfterAQuietIntervalOnly() {
        long now = 100 * SECOND;
        assertFalse(PipelineCacheFile.due(true, 0, false, now, now - 60 * SECOND, now - 60 * SECOND, QUIET, LATEST),
                "nothing created");
        assertFalse(PipelineCacheFile.due(true, 5, false, now, now - SECOND, now - 2 * SECOND, QUIET, LATEST),
                "burst still running");
        assertTrue(PipelineCacheFile.due(true, 5, false, now, now - QUIET, now - 4 * SECOND, QUIET, LATEST));
        assertTrue(PipelineCacheFile.due(true, 1, false, now, now - 10 * SECOND, now - 10 * SECOND, QUIET, LATEST));
    }

    @Test
    void savesAnEndlessBurstAtTheLatestTime() {
        long now = 100 * SECOND;
        assertFalse(PipelineCacheFile.due(true, 900, false, now, now - 1, now - 19 * SECOND, QUIET, LATEST));
        assertTrue(PipelineCacheFile.due(true, 900, false, now, now - 1, now - LATEST, QUIET, LATEST));
    }

    @Test
    void neverSavesWhenSwitchedOffOrWhileWriting() {
        long now = 100 * SECOND;
        assertFalse(PipelineCacheFile.due(false, 5, false, now, now - 60 * SECOND, now - 60 * SECOND, QUIET, LATEST));
        assertFalse(PipelineCacheFile.due(true, 5, true, now, now - 60 * SECOND, now - 60 * SECOND, QUIET, LATEST));
    }

    @Test
    void dueSurvivesNanoTimeWrapAround() {
        long now = Long.MIN_VALUE + SECOND; // nanoTime may wrap; only differences count
        assertTrue(PipelineCacheFile.due(true, 1, false, now, now - 4 * SECOND, now - 4 * SECOND, QUIET, LATEST));
        assertFalse(PipelineCacheFile.due(true, 1, false, now, now - 2 * SECOND, now - 2 * SECOND, QUIET, LATEST));
    }

    @Test
    void writesOnlyAChangedSize() {
        assertTrue(PipelineCacheFile.worthWriting(4096, -1), "never saved");
        assertTrue(PipelineCacheFile.worthWriting(8192, 4096));
        assertFalse(PipelineCacheFile.worthWriting(4096, 4096));
        assertFalse(PipelineCacheFile.worthWriting(0, -1));
        assertFalse(PipelineCacheFile.worthWriting(31, -1), "less than a header");
    }

    @Test
    void atomicWriteCreatesReplacesAndLeavesNoTemporaryFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("config").resolve("cache.bin");
        byte[] first = file(VENDOR, DEVICE, UUID, 100);
        PipelineCacheFile.writeAtomically(target, first);
        assertArrayEquals(first, Files.readAllBytes(target));
        byte[] second = file(VENDOR, DEVICE, UUID, 5000);
        PipelineCacheFile.writeAtomically(target, second);
        assertArrayEquals(second, Files.readAllBytes(target));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
    }
}
