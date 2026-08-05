package com.easylogin.fabric;

import com.easylogin.auth.AuthManager;
import com.easylogin.config.EasyLoginConfig;
import com.easylogin.handler.ProtectionHandler;
import com.easylogin.util.PlayerUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Fabric event handler using Fabric API callbacks.
 */
public class FabricEventHandler {

    public static void register(AuthManager authManager, ProtectionHandler protectionHandler, EasyLoginConfig config) {

        // ─── Player Join / Leave ────────────────────────────
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            authManager.onPlayerJoin(player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            authManager.onPlayerLeave(player);
        });

        // ─── Server Tick ────────────────────────────────────
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            authManager.onServerTick(server);
        });

        // ─── Damage Protection ──────────────────────────────
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player) {
                if (config.blockDamageReceived && (protectionHandler.shouldBlock(player) || authManager.isInvincible(player))) {
                    return false;
                }
            }
            if (source.getEntity() instanceof ServerPlayer attacker) {
                if (config.blockDamageDealt && (protectionHandler.shouldBlock(attacker) || authManager.isInvincible(attacker))) {
                    return false;
                }
            }
            return true;
        });

        // ─── Block Interaction ──────────────────────────────
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayer sp && protectionHandler.shouldBlock(sp)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer sp && protectionHandler.shouldBlock(sp)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer sp && protectionHandler.shouldBlock(sp)) {
                return InteractionResultHolder.fail(ItemStack.EMPTY);
            }
            return InteractionResultHolder.pass(ItemStack.EMPTY);
        });

        // ─── Entity Interaction ─────────────────────────────
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer sp && protectionHandler.shouldBlock(sp)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer sp && protectionHandler.shouldBlock(sp)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // ─── Chat ───────────────────────────────────────────
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (config.blockChat && protectionHandler.shouldBlock(sender)) {
                sender.sendSystemMessage(authManager.getFormatter().format(config.msgBlocked, sender));
                return false;
            }
            return true;
        });

        // ─── Command Execution ──────────────────────────────
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message, source, params) -> {
            if (source.getPlayer() != null) {
                ServerPlayer player = source.getPlayer();
                if (protectionHandler.shouldBlock(player)) {
                    String cmd = message.signedContent();
                    if (!protectionHandler.isCommandAllowed(cmd)) {
                        player.sendSystemMessage(authManager.getFormatter().format(config.msgBlocked, player));
                        return false;
                    }
                }
            }
            return true;
        });
    }
}
