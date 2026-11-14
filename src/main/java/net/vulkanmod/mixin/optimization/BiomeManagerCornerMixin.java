package net.vulkanmod.mixin.optimization;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.level.biome.BiomeManager;
import net.vulkanmod.render.optimization.BiomeCornerCache;
import net.vulkanmod.render.optimization.BiomeFiddleCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * VSDH: {@code BiomeManager.getBiome} on the render thread remembers which neighbouring biome cell a block
 * position resolves to (see {@link BiomeCornerCache}); the biome itself is still read from the world on every
 * call, so the result is the vanilla one. The ambient-particle tick of vanilla and the mods hooked into it ask
 * for the same position several times in a row; in the profiled 128-mod pack this method was about a third of
 * the sampled render-thread time. Other threads (chunk builders, the integrated server, Distant Horizons) are
 * left alone. Not required: if another mod replaces the call, vanilla behaviour stays.
 * {@code -Dvsdh.biomeCornerCache=false} switches it off (A/B checks).
 *
 * <p>A position that is not remembered (the ambient-particle probes are random) takes its eight distances from
 * {@link BiomeFiddleCache}: the per-cell part of the arithmetic is remembered, the result is bit-identical.
 * {@code -Dvsdh.biomeFiddleCache=false} keeps the vanilla distance call for those.
 */
@Mixin(BiomeManager.class)
public abstract class BiomeManagerCornerMixin {
    @Shadow
    private static double getFiddledDistance(long seed, int x, int y, int z, double xNoise, double yNoise, double zNoise) {
        throw new AssertionError();
    }

    @Unique
    private BiomeCornerCache vsdh$corners;

    @Unique
    private BiomeFiddleCache vsdh$fiddles;

    @Redirect(method = "getBiome", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/BiomeManager;getFiddledDistance(JIIIDDD)D"), require = 0)
    private double vsdh$rememberedCorner(long seed, int x, int y, int z, double xNoise, double yNoise, double zNoise) {
        if (!RenderSystem.isOnRenderThread()) {
            return getFiddledDistance(seed, x, y, z, xNoise, yNoise, zNoise);
        }

        BiomeCornerCache corners = this.vsdh$corners;
        if (corners == null) {
            corners = this.vsdh$corners = new BiomeCornerCache();
        }

        int corner = BiomeCornerCache.corner(xNoise, yNoise, zNoise);
        if (corner == 0) {
            corners.begin(x, y, z, xNoise, yNoise, zNoise);
        }
        if (corners.hit() >= 0) {
            return corners.hitDistance(corner);
        }

        double distance;
        if (BiomeFiddleCache.ENABLED) {
            BiomeFiddleCache fiddles = this.vsdh$fiddles;
            if (fiddles == null || fiddles.seed() != seed) {
                fiddles = this.vsdh$fiddles = new BiomeFiddleCache(seed);
            }
            distance = fiddles.distance(x, y, z, xNoise, yNoise, zNoise);
        } else {
            distance = getFiddledDistance(seed, x, y, z, xNoise, yNoise, zNoise);
        }
        corners.record(corner, distance);
        return distance;
    }
}
