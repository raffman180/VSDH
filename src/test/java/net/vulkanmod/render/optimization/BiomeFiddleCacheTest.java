package net.vulkanmod.render.optimization;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The remembered per-cell part of {@code BiomeManager.getFiddledDistance} (Minecraft 1.21.1) must give the same
 * bits as the plain arithmetic, on first use, on repeats and after colliding cells replaced a slot.
 */
class BiomeFiddleCacheTest {
    // BiomeManager.getFiddledDistance / getFiddle with LinearCongruentialGenerator.next, as in vanilla.
    private static long next(long left, long right) {
        left *= left * 6364136223846793005L + 1442695040888963407L;
        return left + right;
    }

    private static double fiddle(long seed) {
        double d = (double) Math.floorMod(seed >> 24, 1024) / 1024.0;
        return (d - 0.5) * 0.9;
    }

    private static double vanilla(long seed, int x, int y, int z, double dx, double dy, double dz) {
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
        return (dz + fz) * (dz + fz) + (dy + fy) * (dy + fy) + (dx + fx) * (dx + fx);
    }

    private static void assertSameBits(double expected, double actual, String what) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), what);
    }

    @Test
    void sameBitsAsVanillaForRandomCellsAndOffsets() {
        var random = new Random(7);
        for (long seed : new long[] {0L, 1L, -1L, 0x5DEECE66DL, Long.MIN_VALUE, 8682522807148012L}) {
            var cache = new BiomeFiddleCache(seed);
            for (int round = 0; round < 300_000; round++) {
                // More cells than slots, so slots are replaced; a small volume, so cells also repeat.
                int x = random.nextInt(64) - 32, y = random.nextInt(24) - 12, z = random.nextInt(64) - 32;
                double dx = random.nextInt(4) / 4.0 - (random.nextBoolean() ? 1.0 : 0.0);
                double dy = random.nextInt(4) / 4.0 - (random.nextBoolean() ? 1.0 : 0.0);
                double dz = random.nextInt(4) / 4.0 - (random.nextBoolean() ? 1.0 : 0.0);
                double expected = vanilla(seed, x, y, z, dx, dy, dz);
                assertSameBits(expected, cache.distance(x, y, z, dx, dy, dz), "cached");
                assertSameBits(expected, BiomeFiddleCache.compute(seed, x, y, z, dx, dy, dz), "reference");
            }
        }
    }

    @Test
    void worldBordersHeightLimitsAndCellsOutsideTheRange() {
        long seed = 0x1234_5678_9ABC_DEFL;
        var cache = new BiomeFiddleCache(seed);
        int[] xs = {-7_500_000, -7_499_999, -1, 0, 1, 7_499_999, 7_500_000, 8_388_607, -8_388_608, 8_388_608,
                -8_388_609, 10_000_000, 10_000_000 - (1 << 24), Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[] ys = {-513, -512, -511, -16, -1, 0, 1, 80, 511, 512, 512 - 1024, 1250};
        for (int pass = 0; pass < 2; pass++)
            for (int x : xs)
                for (int y : ys)
                    for (int z : xs)
                        assertSameBits(vanilla(seed, x, y, z, 0.25, -0.5, 0.75),
                                cache.distance(x, y, z, 0.25, -0.5, 0.75), x + " " + y + " " + z);
    }

    /** The whole lookup as the mixin runs it: position memory first, cell memory for the rest. */
    @Test
    void biomeLookupChoosesTheVanillaCell() {
        long seed = 42L;
        var corners = new BiomeCornerCache();
        var fiddles = new BiomeFiddleCache(seed);
        var random = new Random(3);
        for (int round = 0; round < 200_000; round++) {
            int bx = random.nextInt(160) - 80, by = random.nextInt(96) - 48, bz = random.nextInt(160) - 80;
            int i = bx - 2, j = by - 2, k = bz - 2;
            int l = i >> 2, i1 = j >> 2, j1 = k >> 2;
            double d0 = (double) (i & 3) / 4.0, d1 = (double) (j & 3) / 4.0, d2 = (double) (k & 3) / 4.0;
            int expected = 0, actual = 0;
            double nearestExpected = Double.POSITIVE_INFINITY, nearestActual = Double.POSITIVE_INFINITY;
            for (int l1 = 0; l1 < 8; l1++) {
                boolean flag = (l1 & 4) == 0, flag1 = (l1 & 2) == 0, flag2 = (l1 & 1) == 0;
                int i2 = flag ? l : l + 1, j2 = flag1 ? i1 : i1 + 1, k2 = flag2 ? j1 : j1 + 1;
                double d4 = flag ? d0 : d0 - 1.0, d5 = flag1 ? d1 : d1 - 1.0, d6 = flag2 ? d2 : d2 - 1.0;
                double plain = vanilla(seed, i2, j2, k2, d4, d5, d6);
                if (nearestExpected > plain) {
                    expected = l1;
                    nearestExpected = plain;
                }
                int corner = BiomeCornerCache.corner(d4, d5, d6);
                if (corner == 0) corners.begin(i2, j2, k2, d4, d5, d6);
                double routed;
                if (corners.hit() >= 0) {
                    routed = corners.hitDistance(corner);
                } else {
                    routed = fiddles.distance(i2, j2, k2, d4, d5, d6);
                    corners.record(corner, routed);
                }
                if (nearestActual > routed) {
                    actual = l1;
                    nearestActual = routed;
                }
            }
            assertEquals(expected, actual, bx + " " + by + " " + bz);
        }
    }
}
