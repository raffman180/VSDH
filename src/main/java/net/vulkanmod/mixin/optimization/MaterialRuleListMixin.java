package net.vulkanmod.mixin.optimization;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.RandomAccess;

/**
 * VSDH: the same first-match loop without an iterator object. Terrain generation calls this for every
 * block of every new chunk (DH's world generator included); the iterators were the largest single
 * allocation while flying (about 17 % of all sampled bytes), and every allocation brings the next
 * garbage-collection pause closer. Lists that are not random access keep the original iteration.
 */
@Mixin(MaterialRuleList.class)
public abstract class MaterialRuleListMixin {
    @Shadow @Final private List<NoiseChunk.BlockStateFiller> materialRuleList;

    /**
     * @author VSDH
     * @reason Index loop instead of an iterator per block; identical order and result.
     */
    @Overwrite
    public BlockState calculate(DensityFunction.FunctionContext context) {
        var rules = this.materialRuleList;
        if (rules instanceof RandomAccess) {
            for (int i = 0, n = rules.size(); i < n; i++) {
                BlockState state = rules.get(i).calculate(context);
                if (state != null) return state;
            }
            return null;
        }
        for (var rule : rules) {
            BlockState state = rule.calculate(context);
            if (state != null) return state;
        }
        return null;
    }
}
