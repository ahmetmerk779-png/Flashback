package dev.flashback.mixin;

import dev.flashback.replay.ReplayManager;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPos(double x, double y, double z);

    @Inject(method = "update", at = @At("TAIL"))
    private void flashback$update(CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        if (ReplayManager.freecamActive()) {
            var fc = ReplayManager.freecam;
            if (!fc.inited) fc.init(self.getPos().x, self.getPos().y, self.getPos().z, self.getYaw(), self.getPitch());
            fc.frame();
            setRotation(fc.yaw, fc.pitch);
            setPos(fc.x, fc.y, fc.z);
        } else if (ReplayManager.localCameraActive()) {
            // Kayıttaki oyuncunun gözünden, zamana göre akıcı (enterpolasyonlu) bakış
            var l = ReplayManager.localNow();
            if (l != null) {
                setRotation(l.yaw(), l.pitch());
                setPos(l.x(), l.y() + l.eyeH(), l.z());
            }
        }
    }

    /** Serbest kamerada kaydedilen oyuncunun modeli de görünsün. */
    @Inject(method = "isThirdPerson", at = @At("HEAD"), cancellable = true)
    private void flashback$thirdPerson(CallbackInfoReturnable<Boolean> cir) {
        if (ReplayManager.freecamActive()) cir.setReturnValue(true);
    }
}
