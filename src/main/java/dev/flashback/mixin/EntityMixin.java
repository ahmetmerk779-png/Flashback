package dev.flashback.mixin;

import dev.flashback.replay.ReplayManager;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    /** Serbest kamerada fare hareketi oyuncuyu değil kamerayı döndürür. */
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void flashback$look(double dx, double dy, CallbackInfo ci) {
        if ((Object) this instanceof ClientPlayerEntity && ReplayManager.freecamActive()) {
            ReplayManager.freecam.look(dx, dy);
            ci.cancel();
        }
    }
}
