package com.braffolk.dhvulkan.mixin.dh3;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkPosQueue;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DH 3.3.x's chunk update thread only sleeps when both of its queues are empty. While the closest
 * update is still inside its 250 ms debounce window (or, after the world was left, while the LOD
 * builder is shut down) every pass pops it, re-adds it and starts over: a busy loop that keeps a core
 * spinning and allocated about 250 MB/s in a flight (over a GB/s after leaving the world). A pass that
 * took in nothing and dispatched nothing now waits {@value #STALL_MS} ms. This runs only on that
 * thread (runQueueingLoop is private and started by the manager's own executor); the dispatch order,
 * the debounce and the queues are DH's own, and an interrupt still ends the loop.
 */
@Mixin(value = ChunkUpdateQueueManager.class, remap = false)
public abstract class MixinChunkUpdateQueueBackoff {
    private static final long STALL_MS = 2;

    @Shadow @Final public ChunkPosQueue updateQueue;
    @Shadow @Final public ChunkPosQueue preUpdateQueue;

    @WrapOperation(method = "runQueueingLoop", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/api/internal/chunkUpdating/ChunkUpdateQueueManager;processQueue()V"))
    private void vsdh$waitWhenStalled(ChunkUpdateQueueManager manager, Operation<Void> original) {
        boolean intake = !preUpdateQueue.isEmpty();
        int before = updateQueue.getQueuedCount();
        original.call(manager);
        if (intake || before == 0 || !preUpdateQueue.isEmpty() || updateQueue.getQueuedCount() < before) return;
        try {
            Thread.sleep(STALL_MS);
        } catch (InterruptedException interrupted) {
            // close() interrupts: keep the flag so DH's loop condition ends the thread.
            Thread.currentThread().interrupt();
        }
    }
}
