package net.vulkanmod.render.optimization;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.vulkanmod.Initializer;

import java.util.function.Predicate;

/**
 * The loaded chunk sections of a client level for {@link UniformBiomeProbe}: a section answers with its biome only
 * when its palette holds nothing else. Render thread only (one lookup at a time, no allocation); a chunk is
 * remembered only within one lookup.
 */
public final class ClientBiomeSections implements UniformBiomeProbe.Sections<Holder<Biome>>, Predicate<Holder<Biome>> {
    private final ClientLevel level;

    private Holder<Biome> expected;
    private LevelChunk chunk;
    private int chunkX, chunkZ;

    private long probes, hits, mismatches;

    public ClientBiomeSections(ClientLevel level) {
        this.level = level;
    }

    /** The biome {@code BiomeManager.getBiome} returns at {@code pos} where that needs no arithmetic, else null. */
    public Holder<Biome> uniform(BlockPos pos) {
        Holder<Biome> found = UniformBiomeProbe.probe(this, pos.getX(), pos.getY(), pos.getZ(),
                this.level.getMinBuildHeight(), this.level.getHeight());
        this.chunk = null;
        this.expected = null;
        return found;
    }

    @Override
    public Holder<Biome> single(int chunkX, int sectionIndex, int chunkZ) {
        LevelChunk chunk = this.chunk;
        if (chunk == null || chunkX != this.chunkX || chunkZ != this.chunkZ) {
            // The chunk LevelReader.getNoiseBiome reads; a missing one has its own vanilla answer.
            chunk = this.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null || chunk.getClass() != LevelChunk.class) return null;
            this.chunk = chunk;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
        LevelChunkSection[] sections = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= sections.length) return null;
        PalettedContainerRO<Holder<Biome>> biomes = sections[sectionIndex].getBiomes();
        Holder<Biome> first = biomes.get(0, 0, 0);
        this.expected = first;
        return biomes.maybeHas(this) ? null : first;
    }

    /** Palette test: is this entry another biome than the one in the first cell? */
    @Override
    public boolean test(Holder<Biome> entry) {
        return entry != this.expected;
    }

    /** {@code -Dvsdh.uniformBiomeSkip=verify}: compare with the vanilla answer and report now and then. */
    public void verify(BlockPos pos, Holder<Biome> quick, Holder<Biome> vanilla) {
        this.probes++;
        if (quick != null) {
            this.hits++;
            if (quick != vanilla && this.mismatches++ < 20) {
                Initializer.LOGGER.warn("UNIFORM_BIOME mismatch at {}: quick={} vanilla={}", pos, quick, vanilla);
            }
        }
        if ((this.probes & 0xFFFF) == 0) {
            Initializer.LOGGER.info("UNIFORM_BIOME verify probes={} hits={} ({}%) mismatches={}", this.probes, this.hits,
                    this.hits * 100 / this.probes, this.mismatches);
        }
    }
}
