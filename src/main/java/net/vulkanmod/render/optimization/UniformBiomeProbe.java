package net.vulkanmod.render.optimization;

/**
 * Answers {@code BiomeManager.getBiome} without its arithmetic where the answer cannot depend on it.
 *
 * <p>Vanilla picks one of the eight biome cells around a block position by eight "fiddled distances" and reads the
 * biome stored in the chosen cell. Where the chunk sections holding those eight cells store one single biome each,
 * and the same one, every choice reads that biome: the distances need not be computed and no cell need be read.
 * A chunk section is 4x4x4 cells and most sections of a world hold a single biome, so this covers most lookups; the
 * rest (a section with several biomes, a chunk that is not loaded) return {@code null} here and take the vanilla
 * path with its memories ({@link BiomeCornerCache}, {@link BiomeFiddleCache}).
 *
 * <p>In the profiled 128-mod pack the client tick asks for the biome of 1334 random positions (ambient particles),
 * mods hooked into that loop ask again per position, and map mods ask for tint areas; the lookups were the largest
 * item of the tick (JFR, 14.11.2026). {@code -Dvsdh.uniformBiomeSkip=false} keeps the vanilla path alone,
 * {@code =verify} computes both, compares them and reports the hit rate.
 *
 * <p>Pure arithmetic over a section lookup, no allocation; the cells and the height clamp are those of Minecraft
 * 1.21.1 ({@code BiomeManager.getBiome}, {@code ChunkAccess.getNoiseBiome}).
 */
public final class UniformBiomeProbe {
    /*
     * Opt-in since the first in-game check (15.11.2026, Eclipse flight over ocean, jungle and hills): 2,716 hits in
     * 1.18 million lookups (0.2 %), mismatches 0. Correct, but almost never applicable, so the probe only cost time.
     */
    private static final String MODE = System.getProperty("vsdh.uniformBiomeSkip", "false");
    public static final boolean ENABLED = !"false".equals(MODE);
    public static final boolean VERIFY = "verify".equals(MODE);

    private UniformBiomeProbe() {}

    /** The biome storage of loaded chunk sections. */
    public interface Sections<T> {
        /**
         * The one value the section stores in all of its cells, or {@code null} when it stores several, is not
         * loaded or cannot be read this way. {@code sectionIndex} counts from the lowest section of the world.
         */
        T single(int chunkX, int sectionIndex, int chunkZ);
    }

    /**
     * The value every one of the eight candidate cells of block position (x, y, z) holds, or {@code null} when
     * that is not known to be a single value.
     *
     * @param minBuildHeight lowest block of the world
     * @param height number of blocks from there to the top of the world
     */
    public static <T> T probe(Sections<T> sections, int x, int y, int z, int minBuildHeight, int height) {
        int cells = height >> 2;
        if (cells <= 0) return null;
        // BiomeManager.getBiome: the cell of the position moved by half a cell, and its upper neighbours.
        int cellX = (x - 2) >> 2, cellY = (y - 2) >> 2, cellZ = (z - 2) >> 2;
        // ChunkAccess.getNoiseBiome: cells above or below the world read the outermost cell.
        int minCellY = minBuildHeight >> 2, maxCellY = minCellY + cells - 1;
        int lowY = Math.max(minCellY, Math.min(maxCellY, cellY));
        int highY = Math.max(minCellY, Math.min(maxCellY, cellY + 1));
        int minSection = minBuildHeight >> 4;
        int section0 = (lowY >> 2) - minSection, section1 = (highY >> 2) - minSection;
        int chunkX0 = cellX >> 2, chunkX1 = (cellX + 1) >> 2;
        int chunkZ0 = cellZ >> 2, chunkZ1 = (cellZ + 1) >> 2;

        T first = sections.single(chunkX0, section0, chunkZ0);
        if (first == null) return null;
        for (int chunkX = chunkX0; chunkX <= chunkX1; chunkX++) {
            for (int chunkZ = chunkZ0; chunkZ <= chunkZ1; chunkZ++) {
                for (int section = section0; section <= section1; section++) {
                    if (chunkX == chunkX0 && chunkZ == chunkZ0 && section == section0) continue;
                    if (sections.single(chunkX, section, chunkZ) != first) return null;
                }
            }
        }
        return first;
    }
}
