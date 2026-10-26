package com.easylogin.neoforge.mixin;

import com.easylogin.handler.AuthPacketGate;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = ServerPayloadContext.class, remap = false)
public abstract class ServerPayloadContextMixin {
    @Shadow public abstract ServerCommonPacketListener listener();

    @ModifyVariable(method = "enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), argsOnly = true)
    private Runnable easylogin$guardRunnable(Runnable task) {
        return () -> {
            if (AuthPacketGate.allowsPayloadWork(listener())) task.run();
        };
    }

    @ModifyVariable(method = "enqueueWork(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), argsOnly = true)
    private <T> Supplier<T> easylogin$guardSupplier(Supplier<T> task) {
        return () -> {
            if (!AuthPacketGate.allowsPayloadWork(listener())) {
                throw new CancellationException("EasyLogin: connection is not authenticated");
            }
            return task.get();
        };
    }
}
