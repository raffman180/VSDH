package net.vulkanmod.render.optimization;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the loop of {@code BiomeManager.getBiome} (Minecraft 1.21.1) with the distance call routed through
 * the cache, exactly as the mixin does, and compares the chosen cell with the plain loop.
 */
class BiomeCornerCacheTest {
    private static final long SEED = 0x5DEECE66DL;

    // BiomeManager.getFiddledDistance / getFiddle with LinearCongruentialGenerator.next, as in vanilla.
    private static long next(long left, long right) {
        left *= left * 6364136223846793005L + 1442695040888963407L;
        return left + right;
    }

    private static double fiddle(long seed) {
        double d = (double) Math.floorMod(seed >> 24, 1024) / 1024.0;
        return (d - 0.5) * 0.9;
    }

    private static double fiddledDistance(long seed, int x, int y, int z, double dx, double dy, double dz) {
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

    private interface Distance {
        double of(long seed, int x, int y, int z, double dx, double dy, double dz);
    }

    /** The vanilla loop; returns the chosen quart cell packed into one long. */
    private static long vanillaLoop(int blockX, int blockY, int blockZ, Distance distance) {
        int i = blockX - 2, j = blockY - 2, k = blockZ - 2;
        int l = i >> 2, i1 = j >> 2, j1 = k >> 2;
        double d0 = (double) (i & 3) / 4.0, d1 = (double) (j & 3) / 4.0, d2 = (double) (k & 3) / 4.0;
        int k1 = 0;
        double d3 = Double.POSITIVE_INFINITY;
        for (int l1 = 0; l1 < 8; l1++) {
            boolean flag = (l1 & 4) == 0, flag1 = (l1 & 2) == 0, flag2 = (l1 & 1) == 0;
            int i2 = flag ? l : l + 1, j2 = flag1 ? i1 : i1 + 1, k2 = flag2 ? j1 : j1 + 1;
            double d4 = flag ? d0 : d0 - 1.0, d5 = flag1 ? d1 : d1 - 1.0, d6 = flag2 ? d2 : d2 - 1.0;
            double d7 = distance.of(SEED, i2, j2, k2, d4, d5, d6);
            if (d3 > d7) {
                k1 = l1;
                d3 = d7;
            }
        }
        int l2 = (k1 & 4) == 0 ? l : l + 1, i3 = (k1 & 2) == 0 ? i1 : i1 + 1, j3 = (k1 & 1) == 0 ? j1 : j1 + 1;
        return ((long) l2 & 0x3FFFFFFL) << 38 | ((long) j3 & 0x3FFFFFFL) << 12 | ((long) i3 & 0xFFFL);
    }

    /** The redirect of the mixin, counting how often the real arithmetic ran. */
    private static final class Cached implements Distance {
        final BiomeCornerCache cache = new BiomeCornerCache();
        int computed;

        @Override public double of(long seed, int x, int y, int z, double dx, double dy, double dz) {
            int corner = BiomeCornerCache.corner(dx, dy, dz);
            if (corner == 0) cache.begin(x, y, z, dx, dy, dz);
            if (cache.hit() >= 0) return cache.hitDistance(corner);
            double distance = fiddledDistance(seed, x, y, z, dx, dy, dz);
            computed++;
            cache.record(corner, distance);
            return distance;
        }
    }

    @Test
    void choosesTheVanillaCellOnMissesAndHits() {
        var cached = new Cached();
        var random = new Random(42);
        for (int round = 0; round < 200_000; round++) {
            // A small volume, so positions repeat and slots are overwritten by colliding positions too.
            int x = random.nextInt(96) - 48, y = random.nextInt(64) - 32, z = random.nextInt(96) - 48;
            long expected = vanillaLoop(x, y, z, BiomeCornerCacheTest::fiddledDistance);
            assertEquals(expected, vanillaLoop(x, y, z, cached), "first lookup");
            assertEquals(expected, vanillaLoop(x, y, z, cached), "repeated lookup");
        }
        assertTrue(cached.computed < 200_000 * 16 * 3 / 4, "repeated lookups must be answered from memory");
    }

    @Test
    void repeatedPositionComputesOnce() {
        var cached = new Cached();
        for (int i = 0; i < 5; i++) vanillaLoop(123, 70, -456, cached);
        assertEquals(8, cached.computed);
    }

    @Test
    void worldBordersAndHeightLimits() {
        var cached = new Cached();
        int[] xs = {-30_000_000, -29_999_999, -1, 0, 1, 2, 3, 29_999_999, 30_000_000, 33_554_431, -33_554_432};
        int[] ys = {-2048, -2046, -64, -1, 0, 1, 2, 319, 2031, 2049};
        for (int x : xs)
            for (int y : ys)
                for (int z : xs) {
                    long expected = vanillaLoop(x, y, z, BiomeCornerCacheTest::fiddledDistance);
                    assertEquals(expected, vanillaLoop(x, y, z, cached));
                    assertEquals(expected, vanillaLoop(x, y, z, cached));
                }
    }

    @Test
    void positionsOutsideTheBlockRangeAreNotRemembered() {
        var cached = new Cached();
        // Far outside any world: two positions that would share a key if the range check were missing.
        long first = vanillaLoop(40_000_000, 5000, 7, BiomeCornerCacheTest::fiddledDistance);
        assertEquals(first, vanillaLoop(40_000_000, 5000, 7, cached));
        assertEquals(first, vanillaLoop(40_000_000, 5000, 7, cached));
        assertEquals(16, cached.computed);
        long second = vanillaLoop(40_000_000 - (1 << 26), 5000 - 4096, 7, BiomeCornerCacheTest::fiddledDistance);
        assertEquals(second, vanillaLoop(40_000_000 - (1 << 26), 5000 - 4096, 7, cached));
    }
}
