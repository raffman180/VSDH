package net.vulkanmod.mixin.optimization;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.vulkanmod.render.optimization.ClientBiomeSections;
import net.vulkanmod.render.optimization.UniformBiomeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * VSDH: {@code ClientLevel.getBiome(BlockPos)} on the render thread answers from the chunk sections where all
 * candidate cells of the position lie in sections holding one and the same biome (see {@link UniformBiomeProbe});
 * every other lookup, and every other thread, forwards to the {@code BiomeManager} like {@code LevelReader.getBiome}.
 *
 * <p>The method is added to the class (vanilla inherits the interface default, which only forwards), so no callback
 * object is created per call: the ambient-particle loop of the client tick alone asks 1334 times per tick, mods
 * hooked into it ask again. {@code -Dvsdh.uniformBiomeSkip=false} forwards like the default, {@code =verify}
 * compares both answers and logs the hit rate.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelUniformBiomeMixin {
    @Unique
    private ClientBiomeSections vsdh$biomeSections;

    public Holder<Biome> getBiome(BlockPos pos) {
        ClientLevel level = (ClientLevel) (Object) this;
        if (UniformBiomeProbe.ENABLED && RenderSystem.isOnRenderThread()) {
            ClientBiomeSections sections = this.vsdh$biomeSections;
            if (sections == null) {
                sections = this.vsdh$biomeSections = new ClientBiomeSections(level);
            }
            Holder<Biome> quick = sections.uniform(pos);
            if (UniformBiomeProbe.VERIFY) {
                Holder<Biome> vanilla = level.getBiomeManager().getBiome(pos);
                sections.verify(pos, quick, vanilla);
                return vanilla;
            }
            if (quick != null) {
                return quick;
            }
        }
        return level.getBiomeManager().getBiome(pos);
    }
}
