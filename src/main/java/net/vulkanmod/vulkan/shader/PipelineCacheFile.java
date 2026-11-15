package net.vulkanmod.vulkan.shader;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The file side of the Vulkan pipeline cache, free of Vulkan calls so it can be unit-tested: the header check of
 * a loaded file, the decision when the live cache is written back, and the atomic write.
 * <p>
 * Header layout (Vulkan specification, VkPipelineCacheHeaderVersionOne; 32 bytes, every field least significant
 * byte first regardless of host byte order): uint32 headerSize (must be 32), uint32 headerVersion (must be
 * VK_PIPELINE_CACHE_HEADER_VERSION_ONE = 1), uint32 vendorID, uint32 deviceID, uint8 pipelineCacheUUID[16].
 */
public final class PipelineCacheFile {
    public static final int HEADER_SIZE = 32;
    public static final int HEADER_VERSION_ONE = 1;
    public static final int UUID_SIZE = 16;
    /** A cache file larger than this is not read (a real one is a few MiB to some tens of MiB). */
    public static final long MAX_FILE_BYTES = 1L << 30;

    private PipelineCacheFile() {
    }

    /**
     * Why a loaded cache file must not be handed to the driver, or {@code null} when its header belongs to the
     * current physical device and driver. A rejected file simply starts an empty cache.
     */
    public static String rejection(byte[] data, int vendorId, int deviceId, byte[] pipelineCacheUuid) {
        if (pipelineCacheUuid == null || pipelineCacheUuid.length != UUID_SIZE) return "device reports no cache UUID";
        if (data == null || data.length == 0) return "empty file";
        if (data.length < HEADER_SIZE) return "truncated header (" + data.length + " bytes)";
        int headerSize = littleEndianInt(data, 0);
        if (headerSize != HEADER_SIZE) return "header size " + Integer.toUnsignedString(headerSize);
        int version = littleEndianInt(data, 4);
        if (version != HEADER_VERSION_ONE) return "header version " + Integer.toUnsignedString(version);
        if (littleEndianInt(data, 8) != vendorId) return "other GPU vendor";
        if (littleEndianInt(data, 12) != deviceId) return "other GPU";
        for (int i = 0; i < UUID_SIZE; i++) {
            if (data[16 + i] != pipelineCacheUuid[i]) return "other driver version";
        }
        return null;
    }

    /** The 32-byte header a driver with these properties writes; for tests. */
    public static byte[] header(int vendorId, int deviceId, byte[] pipelineCacheUuid) {
        byte[] header = new byte[HEADER_SIZE];
        putLittleEndianInt(header, 0, HEADER_SIZE);
        putLittleEndianInt(header, 4, HEADER_VERSION_ONE);
        putLittleEndianInt(header, 8, vendorId);
        putLittleEndianInt(header, 12, deviceId);
        System.arraycopy(pipelineCacheUuid, 0, header, 16, UUID_SIZE);
        return header;
    }

    /**
     * Whether the live cache should be snapshotted now. Pipelines appear in bursts (the first frames of a world
     * after a pack was installed, a new dimension), so the snapshot waits until no pipeline was created for
     * {@code quietNanos}; a burst that never pauses is still saved {@code latestNanos} after its first pipeline.
     *
     * @param enabled      {@code -Dvsdh.pipelineCacheSave} (false: only the save at exit)
     * @param unsaved      pipelines created since the last snapshot
     * @param writing      a previous snapshot is still being written
     * @param now          {@link System#nanoTime()}
     * @param lastCreated  time of the newest pipeline creation
     * @param firstUnsaved time of the oldest creation not covered by a snapshot
     */
    public static boolean due(boolean enabled, long unsaved, boolean writing, long now, long lastCreated,
                              long firstUnsaved, long quietNanos, long latestNanos) {
        if (!enabled || unsaved <= 0 || writing) return false;
        return now - lastCreated >= quietNanos || now - firstUnsaved >= latestNanos;
    }

    /** A snapshot is written only when the cache holds data and its size differs from the last saved or loaded one. */
    public static boolean worthWriting(long size, long lastSavedSize) {
        return size >= HEADER_SIZE && size != lastSavedSize;
    }

    /**
     * Writes through a temporary file in the same directory and an atomic move, so a killed game leaves either
     * the old or the new cache, never half of one. File systems without atomic moves get a plain replace.
     */
    public static void writeAtomically(Path target, byte[] data) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = directory.resolve(target.getFileName() + ".tmp");
        try {
            Files.write(temporary, data);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | AccessDeniedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int littleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }

    private static void putLittleEndianInt(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }
}
