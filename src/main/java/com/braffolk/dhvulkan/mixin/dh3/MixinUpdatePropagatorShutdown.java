package com.braffolk.dhvulkan.mixin.dh3;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdatePropagatorV2;
import de.stranger.vsdh.compat.ExecutorShutdown;
import de.stranger.vsdh.compat.RegenQueryControl;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/** DH 3.3.3 shuts workers down but returns before a running SQL query has released its statement. */
@Mixin(value = FullDataUpdatePropagatorV2.class, remap = false)
public abstract class MixinUpdatePropagatorShutdown {
    @Shadow @Final private ExecutorService regenQueueingThread;
    @Shadow @Final public ThreadPoolExecutor updateQueueProcessor;
    @Shadow @Final private FullDataSourceProviderV2 provider;

    @WrapMethod(method = "close")
    private void vsdh$drainBeforeRepositoryClose(Operation<Void> original) {
        original.call();
        // This runs only during level shutdown, never during regular rendering or tick work.
        var query = ((RegenQueryControl.Owner) (Object) provider.repo).vsdh$regenQueryControl();
        query.cancel();
        ExecutorShutdown.awaitStopped(updateQueueProcessor, Duration.ofSeconds(5), query::cancel);
        ExecutorShutdown.awaitStopped(regenQueueingThread, Duration.ofSeconds(5), query::cancel);
    }
}
