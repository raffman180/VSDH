package net.vulkanmod.render.optimization;

import java.util.Arrays;

/**
 * Remembers the three "fiddle" offsets of a biome cell, the expensive part of {@code BiomeManager.getFiddledDistance}.
 *
 * <p>The offsets are a pure function of the world's biome zoom seed and the quart cell (eight chained 64-bit
 * generator steps); only the last three additions and squares depend on the position inside the cell. Sixty-four
 * block positions share each cell and every lookup asks for eight cells, so random positions near the player (the
 * 1334 ambient-particle probes of every client tick, the same probes of the mods hooked into them) miss the
 * per-position {@link BiomeCornerCache} but hit here. The distance is then formed with the same double operations
 * in the same order as vanilla, so the result is bit-identical. One instance per {@code BiomeManager} (one seed),
 * one thread only; no allocation after construction. {@code -Dvsdh.biomeFiddleCache=false} keeps the vanilla call.
 */
public final class BiomeFiddleCache {
    public static final boolean ENABLED = !"false".equals(System.getProperty("vsdh.biomeFiddleCache"));

    private static final int BITS = 13, SIZE = 1 << BITS;

    private final long seed;
    private final long[] keys = new long[SIZE];
    private final double[] fiddles = new double[SIZE * 3];

    public BiomeFiddleCache(long seed) {
        this.seed = seed;
        Arrays.fill(this.keys, -1L); // no cell packs to -1: a key uses 58 bits
    }

    public long seed() {
        return this.seed;
    }

    // LinearCongruentialGenerator.next of Minecraft 1.21.1.
    private static long next(long left, long right) {
        left *= left * 6364136223846793005L + 1442695040888963407L;
        return left + right;
    }

    // BiomeManager.getFiddle of Minecraft 1.21.1.
    private static double fiddle(long seed) {
        double d = (double) Math.floorMod(seed >> 24, 1024) / 1024.0;
        return (d - 0.5) * 0.9;
    }

    /** {@code BiomeManager.getFiddledDistance} of Minecraft 1.21.1 without any memory (reference and fallback). */
    public static double compute(long seed, int x, int y, int z, double xNoise, double yNoise, double zNoise) {
        long l = next(seed, x);
        l = next(l, y);
        l = next(l, z);
        l = next(l, x);
        l = next(l, y);
        l = next(l, z);
        double fx = fiddle(l);
        l = next(l, seed);
        double fy = fiddle(l);
        l = next(l, seed);
        double fz = fiddle(l);
        return square(zNoise + fz) + square(yNoise + fy) + square(xNoise + fx);
    }

    private static double square(double value) {
        return value * value;
    }

    /** The fiddled distance of quart cell (x, y, z) for the given offsets, as vanilla computes it. */
    public double distance(int x, int y, int z, double xNoise, double yNoise, double zNoise) {
        // 24 + 24 + 10 bits: the quart cells of every block position; anything beyond is computed directly.
        if (x != (x << 8 >> 8) || z != (z << 8 >> 8) || y != (y << 22 >> 22)) {
            return compute(this.seed, x, y, z, xNoise, yNoise, zNoise);
        }
        long key = ((long) x & 0xFFFFFFL) << 34 | ((long) z & 0xFFFFFFL) << 10 | ((long) y & 0x3FFL);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - BITS));
        int at = slot * 3;
        double[] fiddles = this.fiddles;
        if (this.keys[slot] != key) {
            long l = next(this.seed, x);
            l = next(l, y);
            l = next(l, z);
            l = next(l, x);
            l = next(l, y);
            l = next(l, z);
            fiddles[at] = fiddle(l);
            l = next(l, this.seed);
            fiddles[at + 1] = fiddle(l);
            l = next(l, this.seed);
            fiddles[at + 2] = fiddle(l);
            this.keys[slot] = key;
        }
        return square(zNoise + fiddles[at + 2]) + square(yNoise + fiddles[at + 1]) + square(xNoise + fiddles[at]);
    }
}
