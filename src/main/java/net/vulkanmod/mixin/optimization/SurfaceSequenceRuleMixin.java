package net.vulkanmod.mixin.optimization;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.RandomAccess;

/** VSDH: surface rules of new chunks without an iterator per block (see {@link MaterialRuleListMixin}). */
@Mixin(SurfaceRules.SequenceRule.class)
public abstract class SurfaceSequenceRuleMixin {
    @Shadow @Final private List<SurfaceRules.SurfaceRule> rules;

    /**
     * @author VSDH
     * @reason Index loop instead of an iterator per block; identical order and result.
     */
    @Overwrite
    public BlockState tryApply(int x, int y, int z) {
        var list = this.rules;
        if (list instanceof RandomAccess) {
            for (int i = 0, n = list.size(); i < n; i++) {
                BlockState state = list.get(i).tryApply(x, y, z);
                if (state != null) return state;
            }
            return null;
        }
        for (var rule : list) {
            BlockState state = rule.tryApply(x, y, z);
            if (state != null) return state;
        }
        return null;
    }
}
