package dev.flashback.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientConnection.class)
public interface ClientConnectionAccessor {
    @Accessor("channel")
    Channel flashback$getChannel();

    @Accessor("side")
    NetworkSide flashback$getSide();
}
