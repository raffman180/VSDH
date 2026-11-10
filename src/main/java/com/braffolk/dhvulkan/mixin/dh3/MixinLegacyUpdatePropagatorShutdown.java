package com.braffolk.dhvulkan.mixin.dh3;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdatePropagatorV2;
import de.stranger.vsdh.compat.ExecutorShutdown;
import de.stranger.vsdh.compat.RegenQueryControl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/** Older DH has one update executor and no separate regeneration queue. */
@Mixin(value = FullDataUpdatePropagatorV2.class, remap = false)
public abstract class MixinLegacyUpdatePropagatorShutdown {
    @Shadow @Final public ThreadPoolExecutor updateQueueProcessor;
    @Shadow @Final private FullDataSourceProviderV2 provider;

    @WrapMethod(method = "close")
    private void vsdh$drainChildUpdatesBeforeRepositoryClose(Operation<Void> original) {
        original.call();
        var query = ((RegenQueryControl.Owner) (Object) provider.repo).vsdh$regenQueryControl();
        query.cancel();
        ExecutorShutdown.awaitStopped(updateQueueProcessor, Duration.ofSeconds(5), query::cancel);
    }
}
