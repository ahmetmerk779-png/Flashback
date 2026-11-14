package dev.flashback.mixin;

import dev.flashback.replay.ExportManager;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dışa aktarma sırasında çerçeve tamponu boyutunu hedef çözünürlüğe (ör. 3840x2160) zorlar. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "getFramebufferWidth", at = @At("HEAD"), cancellable = true)
    private void flashback$w(CallbackInfoReturnable<Integer> cir) {
        if (ExportManager.forcing()) cir.setReturnValue(ExportManager.width);
    }

    @Inject(method = "getFramebufferHeight", at = @At("HEAD"), cancellable = true)
    private void flashback$h(CallbackInfoReturnable<Integer> cir) {
        if (ExportManager.forcing()) cir.setReturnValue(ExportManager.height);
    }
}
