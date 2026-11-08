package com.braffolk.dhvulkan.mixin.dh3;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import de.stranger.vsdh.compat.RegenQueryControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.sql.PreparedStatement;

@Mixin(value = AbstractDhRepo.class, remap = false)
public abstract class MixinRegenStatementCancellation {
    @WrapOperation(method = "query", at = @At(value = "INVOKE", target = "Ljava/sql/PreparedStatement;execute()Z"))
    private boolean vsdh$registerCancellableRegenStatement(PreparedStatement statement, Operation<Boolean> original) throws Throwable {
        RegenQueryControl control = RegenQueryControl.current();
        if (control == null) return original.call(statement);
        control.executing(statement);
        if (control.stopping()) return false;
        try { return original.call(statement); }
        catch (Throwable failure) {
            // Return the existing "no result" path only for our explicit SQLite cancellation.
            if (control.isOurCancellation(failure)) return false;
            throw failure;
        }
    }
}
