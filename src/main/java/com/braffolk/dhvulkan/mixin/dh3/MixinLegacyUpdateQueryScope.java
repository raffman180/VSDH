package com.braffolk.dhvulkan.mixin.dh3;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import de.stranger.vsdh.compat.RegenQueryControl;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = FullDataSourceV2Repo.class, remap = false)
public abstract class MixinLegacyUpdateQueryScope implements RegenQueryControl.Owner {
    @Unique private final RegenQueryControl vsdh$query = new RegenQueryControl();
    @Override public RegenQueryControl vsdh$regenQueryControl() { return vsdh$query; }

    @WrapMethod(method = "getChildPositionsToUpdate")
    private LongArrayList vsdh$scopeChildUpdateQuery(int x, int z, int count, Operation<LongArrayList> original) {
        if (vsdh$query.stopping()) return new LongArrayList();
        try (var scope = vsdh$query.enter()) {
            try {
                return original.call(x, z, count);
            } catch (RuntimeException failure) {
                if (vsdh$query.isOurCancellation(failure)) return new LongArrayList();
                throw failure;
            }
        }
    }
}
