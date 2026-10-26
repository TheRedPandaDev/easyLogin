package com.easylogin.neoforge.mixin;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = NetworkRegistry.class, remap = false)
public abstract class NetworkRegistryMixin {
    /** Reconstructed/synthetic packets must pass the same gate as socket packets. */
    @Inject(method = "handlePacketUnchecked(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V",
            at = @At("HEAD"), cancellable = true)
    private static void easylogin$gateReconstructed(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (!listener.shouldHandleMessage(packet)) ci.cancel();
    }
}
