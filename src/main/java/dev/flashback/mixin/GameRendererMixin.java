package dev.flashback.mixin;

import dev.flashback.replay.ExportManager;
import dev.flashback.replay.ReplayManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** Kare tamamen çizildikten sonra (dünya + arayüz) ana çerçeve tamponunu yakalar. */
    @Inject(method = "render", at = @At("TAIL"))
    private void flashback$frameEnd(CallbackInfo ci) {
        ExportManager.onFrameEnd(MinecraftClient.getInstance());
    }

    /** Kamera yolu oynarken anahtar karelerden gelen FOV'u uygular. */
    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void flashback$fov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (ReplayManager.pathPlaying() && ReplayManager.freecam.fov > 0) cir.setReturnValue(ReplayManager.freecam.fov);
    }
}
