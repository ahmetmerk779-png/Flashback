package dev.flashback.mixin;

import dev.flashback.record.RecordingManager;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paketleri nesne düzeyinde yakalar: hem uzak sunucuda hem tek oyunculuda (yerel bağlantı) çalışır.
 * Geçerli gelen durum (config / play) transitionInbound ile izlenir; paket o durumun codec'i ile kodlanır.
 */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Unique private NetworkState<?> flashback$inState;

    @Inject(method = "transitionInbound", at = @At("HEAD"))
    private void flashback$transition(NetworkState<?> state, PacketListener listener, CallbackInfo ci) {
        flashback$inState = state;
    }

    @Inject(method = "channelRead0", at = @At("HEAD"))
    private void flashback$read(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        NetworkState<?> st = flashback$inState;
        if (st == null || ((ClientConnectionAccessor) this).flashback$getSide() != NetworkSide.CLIENTBOUND) return;
        RecordingManager.onPacket(st, packet);
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void flashback$inactive(ChannelHandlerContext ctx, CallbackInfo ci) {
        if (((ClientConnectionAccessor) this).flashback$getSide() == NetworkSide.CLIENTBOUND) RecordingManager.onConnectionClosed();
    }
}
