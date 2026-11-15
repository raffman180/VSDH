package net.vulkanmod.render.optimization;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wherever the probe answers, the answer must be the biome {@code BiomeManager.getBiome} of Minecraft 1.21.1 reads,
 * and it must answer exactly where every chunk section behind the eight candidate cells holds the same single biome.
 */
class UniformBiomeProbeTest {
    private static final String[] BIOMES = {"plains", "forest", "river", "lush_caves", "deep_dark"};

    /** A generated world: biome regions of several chunks, some sections with a second biome in a few cells. */
    private record World(long seed, int minBuildHeight, int height, int mixedPercent) implements UniformBiomeProbe.Sections<String> {
        private static long mix(long value) {
            value = (value ^ (value >>> 33)) * 0xFF51AFD7ED558CCDL;
            value = (value ^ (value >>> 33)) * 0xC4CEB9FE1A85EC53L;
            return value ^ (value >>> 33);
        }

        private long hash(int a, int b, int c, int salt) {
            return mix(mix(mix(mix(this.seed + salt) + a) + b) + c);
        }

        /** The biome stored in cell (x, y, z) of a section; all arguments as the chunk stores them. */
        String cell(int chunkX, int section, int chunkZ, int x, int y, int z) {
            String region = BIOMES[(int) Math.floorMod(hash(chunkX >> 2, section >> 1, chunkZ >> 2, 1), 3)];
            boolean mixed = Math.floorMod(hash(chunkX, section, chunkZ, 2), 100) < this.mixedPercent;
            if (mixed && Math.floorMod(hash(chunkX * 64 + x, section * 64 + y, chunkZ * 64 + z, 3), 4) == 0)
                return BIOMES[3 + (int) Math.floorMod(hash(x, y, z, 4), 2)];
            return region;
        }

        boolean loaded(int chunkX, int chunkZ) {
            return Math.floorMod(hash(chunkX, 0, chunkZ, 5), 50) != 0;
        }

        int sections() {
            return this.height >> 4;
        }

        /** LevelReader.getNoiseBiome with ChunkAccess.getNoiseBiome; null stands for the answer of a missing chunk. */
        String noiseBiome(int cellX, int cellY, int cellZ) {
            int chunkX = cellX >> 2, chunkZ = cellZ >> 2;
            if (!loaded(chunkX, chunkZ)) return null;
            int min = this.minBuildHeight >> 2;
            int max = min + (this.height >> 2) - 1;
            int clamped = Math.max(min, Math.min(max, cellY));
            int section = ((clamped << 2) >> 4) - (this.minBuildHeight >> 4);
            return cell(chunkX, section, chunkZ, cellX & 3, clamped & 3, cellZ & 3);
        }

        @Override
        public String single(int chunkX, int sectionIndex, int chunkZ) {
            if (!loaded(chunkX, chunkZ) || sectionIndex < 0 || sectionIndex >= sections()) return null;
            String first = cell(chunkX, sectionIndex, chunkZ, 0, 0, 0);
            for (int i = 1; i < 64; i++)
                if (cell(chunkX, sectionIndex, chunkZ, i & 3, (i >> 2) & 3, i >> 4) != first) return null;
            return first;
        }

        /** BiomeManager.getBiome of Minecraft 1.21.1. */
        String vanilla(long zoomSeed, int blockX, int blockY, int blockZ) {
            int i = blockX - 2, j = blockY - 2, k = blockZ - 2;
            int l = i >> 2, i1 = j >> 2, j1 = k >> 2;
            double d0 = (double) (i & 3) / 4.0, d1 = (double) (j & 3) / 4.0, d2 = (double) (k & 3) / 4.0;
            int best = 0;
            double nearest = Double.POSITIVE_INFINITY;
            for (int corner = 0; corner < 8; corner++) {
                boolean fx = (corner & 4) == 0, fy = (corner & 2) == 0, fz = (corner & 1) == 0;
                double distance = BiomeFiddleCache.compute(zoomSeed, fx ? l : l + 1, fy ? i1 : i1 + 1, fz ? j1 : j1 + 1,
                        fx ? d0 : d0 - 1.0, fy ? d1 : d1 - 1.0, fz ? d2 : d2 - 1.0);
                if (nearest > distance) {
                    best = corner;
                    nearest = distance;
                }
            }
            return noiseBiome((best & 4) == 0 ? l : l + 1, (best & 2) == 0 ? i1 : i1 + 1, (best & 1) == 0 ? j1 : j1 + 1);
        }

        /** Whether all eight candidate cells lie in loaded sections that hold one and the same biome. */
        String expected(int blockX, int blockY, int blockZ) {
            int l = (blockX - 2) >> 2, i1 = (blockY - 2) >> 2, j1 = (blockZ - 2) >> 2;
            int min = this.minBuildHeight >> 2, max = min + (this.height >> 2) - 1;
            String common = null;
            for (int corner = 0; corner < 8; corner++) {
                int cellX = l + (corner >> 2 & 1), cellY = i1 + (corner >> 1 & 1), cellZ = j1 + (corner & 1);
                int clamped = Math.max(min, Math.min(max, cellY));
                String single = single(cellX >> 2, ((clamped << 2) >> 4) - (this.minBuildHeight >> 4), cellZ >> 2);
                if (single == null || common != null && single != common) return null;
                common = single;
            }
            return common;
        }
    }

    @Test
    void answersOnlyWithTheVanillaBiomeAndWhereverTheSectionsAreUniform() {
        var random = new Random(11);
        World[] worlds = {new World(1, -64, 384, 25), new World(2, 0, 256, 60), new World(3, -2032, 4064, 10),
                new World(4, 16, 16, 30), new World(5, -64, 384, 0)};
        for (World world : worlds) {
            int answered = 0, declined = 0;
            for (int round = 0; round < 200_000; round++) {
                int x = random.nextInt(600) - 300, z = random.nextInt(600) - 300;
                // Also below and above the world: those cells read the outermost section.
                int y = world.minBuildHeight() - 40 + random.nextInt(world.height() + 80);
                String probe = UniformBiomeProbe.probe(world, x, y, z, world.minBuildHeight(), world.height());
                assertSame(world.expected(x, y, z), probe, "span at " + x + "," + y + "," + z);
                if (probe == null) {
                    declined++;
                    continue;
                }
                answered++;
                assertSame(world.vanilla(0x5DEECE66DL + world.seed(), x, y, z), probe, "biome at " + x + "," + y + "," + z);
            }
            assertTrue(answered > 10_000, "answered " + answered);
            if (world.mixedPercent() > 0) assertTrue(declined > 2_000, "declined " + declined);
        }
    }

    @Test
    void everySectionBoundaryIsCovered() {
        World world = new World(9, -64, 384, 40);
        // Every position of two chunks' width around a chunk corner and a section border, and at both world limits.
        for (int x = -20; x < 20; x++)
            for (int z = -20; z < 20; z++)
                for (int y : new int[] {-70, -64, -63, -61, -50, -49, -48, -47, -46, 14, 15, 16, 17, 18, 19, 316, 317, 318, 319, 320, 330}) {
                    String probe = UniformBiomeProbe.probe(world, x, y, z, -64, 384);
                    assertSame(world.expected(x, y, z), probe);
                    if (probe != null) assertSame(world.vanilla(42L, x, y, z), probe);
                }
    }

    @Test
    void asksEachSectionOnceAndNoneBeyondTheCandidates() {
        int[] calls = {0};
        UniformBiomeProbe.Sections<String> counting = (chunkX, section, chunkZ) -> {
            calls[0]++;
            return "plains";
        };
        // Position 8,8,8: cells 1..2 on every axis, all inside chunk 0 / section 0 of a world from 0.
        assertSame("plains", UniformBiomeProbe.probe(counting, 8, 8, 8, 0, 256));
        assertEquals(1, calls[0]);
        // Position 16,16,16: cells 3..4 on every axis, so two chunks on x and z and two sections.
        calls[0] = 0;
        assertSame("plains", UniformBiomeProbe.probe(counting, 16, 16, 16, 0, 256));
        assertEquals(8, calls[0]);
        assertNull(UniformBiomeProbe.probe(counting, 8, 8, 8, 0, 0), "a world without height");
        assertNull(UniformBiomeProbe.probe((chunkX, section, chunkZ) -> null, 8, 8, 8, 0, 256));
    }
}
