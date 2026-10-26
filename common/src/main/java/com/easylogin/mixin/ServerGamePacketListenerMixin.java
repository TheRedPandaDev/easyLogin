package com.easylogin.mixin;

import com.easylogin.handler.AuthPacketGate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Unique private long easylogin$nextInventorySync = System.nanoTime();
    @Unique private final AtomicBoolean easylogin$inventorySyncPending = new AtomicBoolean();
    @Unique private final AtomicInteger easylogin$deniedPrediction = new AtomicInteger(-1);

    // Native ACK processing rolls back every client-predicted cell (including
    // doors/beds/buckets) without trusting coordinates or loading world chunks.
    // Always finish denied predictions, even if login completed in the meantime.
    @Inject(method = "tick", at = @At("HEAD"))
    private void easylogin$correctDeniedPredictions(CallbackInfo ci) {
        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        int sequence = easylogin$deniedPrediction.getAndSet(-1);
        if (sequence >= 0) {
            listener.ackBlockChangesUpTo(sequence);
        }
        // Keep the final correction pending during bursts. Only the main thread
        // accesses the deadline; network threads merely publish the pending bit.
        long now = System.nanoTime();
        if (now - easylogin$nextInventorySync >= 0 && easylogin$inventorySyncPending.getAndSet(false)) {
            easylogin$nextInventorySync = now + 500_000_000L;
            if (!listener.isAcceptingMessages()) return;
            listener.player.inventoryMenu.sendAllDataToRemote();
            if (listener.player.containerMenu != listener.player.inventoryMenu) {
                listener.player.containerMenu.sendAllDataToRemote();
            }
            listener.send(new ClientboundSetCarriedItemPacket(listener.player.getInventory().selected));
        }
    }

    // Called both by Connection before dispatch and by PacketUtils immediately
    // before queued vanilla work executes. Never return early from PacketUtils.
    @Inject(method = "shouldHandleMessage(Lnet/minecraft/network/protocol/Packet;)Z",
            at = @At("HEAD"), cancellable = true)
    private void easylogin$gate(Packet<?> packet, CallbackInfoReturnable<Boolean> cir) {
        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        if (AuthPacketGate.allows(listener, packet)) return;
        cir.setReturnValue(false);
        // This is separate from the throttled inventory resync: no prediction
        // sequence may be lost during a burst of rejected actions.
        int sequence = AuthPacketGate.predictionSequence(packet);
        if (sequence >= 0) easylogin$deniedPrediction.accumulateAndGet(sequence, Math::max);
        if (AuthPacketGate.needsInventoryResync(packet)) easylogin$inventorySyncPending.set(true);
    }
}
