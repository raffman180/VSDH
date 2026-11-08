package com.braffolk.dhvulkan.mixin.dh3;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import de.stranger.vsdh.compat.RegenQueryControl;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = FullDataSourceV2Repo.class, remap = false)
public abstract class MixinRegenQueryScope implements RegenQueryControl.Owner {
    @Unique private final RegenQueryControl vsdh$regenQuery = new RegenQueryControl();
    @Override public RegenQueryControl vsdh$regenQueryControl() { return vsdh$regenQuery; }

    @WrapMethod(method = "getChildPositionsToRegen")
    private LongArrayList vsdh$scopeReadOnlyRegenQuery(int x, int z, int distance, int count,
                                                     Operation<LongArrayList> original) {
        if (vsdh$regenQuery.stopping()) return new LongArrayList();
        try (var scope = vsdh$regenQuery.enter()) {
            try { return original.call(x, z, distance, count); }
            catch (RuntimeException failure) {
                if (vsdh$regenQuery.isOurCancellation(failure)) return new LongArrayList();
                throw failure;
            }
        }
    }
}
