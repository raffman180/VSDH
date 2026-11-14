package net.vulkanmod.render.optimization;

import java.util.Arrays;

/**
 * Remembers, per block position, which of the eight surrounding biome cells {@code BiomeManager.getBiome} picks.
 *
 * <p>That choice is a pure function of the world's biome zoom seed and the position: eight "fiddled distances"
 * are computed and the nearest cell wins. Only the biome stored in the chosen cell can change later, and that is
 * still read fresh on every call, so a remembered choice can never be stale. Client ticks ask for the same
 * position several times in a row (the ambient particles of vanilla and every mod that hooks them), and in a
 * large mod pack this arithmetic was the largest single item on the render thread (JFR, 14.11.2026).
 *
 * <p>Used through the eight {@code getFiddledDistance} calls of one {@code getBiome}: the first call of a lookup
 * (cell 0) names the position, the other seven follow. On a hit the remembered cell gets distance 0 and every
 * other cell 1, which makes the unchanged vanilla loop select exactly the remembered cell. One thread only (the
 * render thread); other threads keep the vanilla arithmetic. No allocation after construction.
 */
public final class BiomeCornerCache {
    private static final int BITS = 12, SIZE = 1 << BITS;

    private final long[] keys = new long[SIZE];
    private final byte[] corners = new byte[SIZE];

    // The lookup in progress.
    private int slot;
    private long key;
    private int hit = -1;
    private boolean tracking;
    private double nearest;
    private int best;

    public BiomeCornerCache() {
        Arrays.fill(corners, (byte) -1);
    }

    /** Which of the eight cells these offsets belong to, in the order of the vanilla loop (bit 4: x, 2: y, 1: z). */
    public static int corner(double dx, double dy, double dz) {
        return (dx < 0.0 ? 4 : 0) | (dy < 0.0 ? 2 : 0) | (dz < 0.0 ? 1 : 0);
    }

    /**
     * Starts a lookup from the arguments of its first distance call (cell 0: the quart cell of the position
     * and the fractions of the position inside it, each a multiple of a quarter).
     */
    public void begin(int cellX, int cellY, int cellZ, double fractionX, double fractionY, double fractionZ) {
        int x = (cellX << 2) | (int) (fractionX * 4.0);
        int y = (cellY << 2) | (int) (fractionY * 4.0);
        int z = (cellZ << 2) | (int) (fractionZ * 4.0);
        // 26 + 26 + 12 bits, the range of a block position; anything beyond it is simply not remembered.
        if (x != (x << 6 >> 6) || z != (z << 6 >> 6) || y != (y << 20 >> 20)) {
            this.hit = -1;
            this.tracking = false;
            return;
        }
        long key = ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - BITS));
        this.key = key;
        this.slot = slot;
        byte remembered = this.corners[slot];
        if (remembered >= 0 && this.keys[slot] == key) {
            this.hit = remembered;
            this.tracking = false;
        } else {
            this.hit = -1;
            this.tracking = true;
            this.nearest = Double.POSITIVE_INFINITY;
            this.best = 0;
        }
    }

    /** The remembered cell of the lookup in progress, or -1 when its distances must be computed. */
    public int hit() {
        return this.hit;
    }

    /** The distance to report for {@code corner} on a hit: only the remembered cell is the nearest. */
    public double hitDistance(int corner) {
        return corner == this.hit ? 0.0 : 1.0;
    }

    /** A computed distance of the lookup in progress; the eighth one completes and stores the choice. */
    public void record(int corner, double distance) {
        if (!this.tracking) return;
        if (this.nearest > distance) { // the comparison of the vanilla loop: the first strictly nearer cell wins
            this.nearest = distance;
            this.best = corner;
        }
        if (corner == 7) {
            this.keys[this.slot] = this.key;
            this.corners[this.slot] = (byte) this.best;
            this.tracking = false;
        }
    }
}
