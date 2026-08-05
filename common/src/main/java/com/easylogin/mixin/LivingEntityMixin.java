package com.easylogin.mixin;

import com.easylogin.auth.AuthManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
    private void onIsInvulnerableTo(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity entity = (LivingEntity) (Object) this;
        AuthManager auth = AuthManager.INSTANCE;
        if (auth == null)
            return;

        if (entity instanceof ServerPlayer player) {
            if (auth.shouldBlock(player) || auth.isInvincible(player)) {
                if (auth.getConfig().blockDamageReceived) {
                    cir.setReturnValue(true);
                }
            }
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity entity = (LivingEntity) (Object) this;

        AuthManager auth = AuthManager.INSTANCE;
        if (auth == null)
            return;

        // Block damage received
        if (entity instanceof ServerPlayer player) {
            if (auth.shouldBlock(player) || auth.isInvincible(player)) {
                if (auth.getConfig().blockDamageReceived) {
                    cir.setReturnValue(false);
                    return;
                }
            }
        }

        // Block damage dealt
        if (source.getEntity() instanceof ServerPlayer attacker) {
            if (auth.shouldBlock(attacker) || auth.isInvincible(attacker)) {
                if (auth.getConfig().blockDamageDealt) {
                    cir.setReturnValue(false);
                }
            }
        }
    }
}
