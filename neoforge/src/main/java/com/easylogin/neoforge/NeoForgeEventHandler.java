package com.easylogin.neoforge;

import com.easylogin.auth.AuthManager;
import com.easylogin.config.EasyLoginConfig;
import com.easylogin.handler.ProtectionHandler;
import com.easylogin.platform.PlatformHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge event handler — subscribes to all relevant events and delegates
 * to the common ProtectionHandler / AuthManager.
 */
public class NeoForgeEventHandler {

    private final AuthManager authManager;
    private final ProtectionHandler protectionHandler;
    private final EasyLoginConfig config;

    public NeoForgeEventHandler(AuthManager authManager, ProtectionHandler protectionHandler, EasyLoginConfig config) {
        this.authManager = authManager;
        this.protectionHandler = protectionHandler;
        this.config = config;
    }

    // ─── Player Join / Leave ────────────────────────────────

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            authManager.onPlayerJoin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            authManager.onPlayerLeave(player);
        }
    }

    // ─── Server Tick ────────────────────────────────────────

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        authManager.onServerTick(event.getServer());
    }

    // ─── Damage ─────────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof ServerPlayer player) {
            if ((config.blockDamageReceived && protectionHandler.shouldBlock(player))
                    || authManager.isInvincible(player)) {
                event.setCanceled(true);
                return;
            }
        }
        Entity source = event.getSource().getEntity();
        if (source instanceof ServerPlayer attacker) {
            if (config.blockDamageDealt
                    && (protectionHandler.shouldBlock(attacker) || authManager.isInvincible(attacker))) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDamage(LivingDamageEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity instanceof ServerPlayer player) {
            if ((config.blockDamageReceived && protectionHandler.shouldBlock(player))
                    || authManager.isInvincible(player)) {
                event.setNewDamage(0);
                return;
            }
        }

        // Block damage dealt by unauthenticated or invincible players
        Entity source = event.getSource().getEntity();
        if (source instanceof ServerPlayer attacker) {
            if (config.blockDamageDealt
                    && (protectionHandler.shouldBlock(attacker) || authManager.isInvincible(attacker))) {
                event.setNewDamage(0);
            }
        }
    }

    // ─── Block Interaction ──────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                event.setCanceled(true);
            }
        }
    }

    // ─── Item Drop ──────────────────────────────────────────

    // Pickup has no serverbound packet, so it needs an authoritative event guard.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer() instanceof ServerPlayer player && protectionHandler.shouldBlock(player)) {
            event.setCanPickup(TriState.FALSE);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onItemDrop(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                event.setCanceled(true);
                player.getInventory().add(event.getEntity().getItem());
            }
        }
    }

    // ─── Chat ───────────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (config.blockChat && protectionHandler.shouldBlock(player)) {
            event.setCanceled(true);
            player.sendSystemMessage(authManager.getFormatter().format(config.msgBlocked, player));
        }
    }

    // ─── Commands ───────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCommand(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        if (source.getPlayer() != null) {
            ServerPlayer player = source.getPlayer();
            if (protectionHandler.shouldBlock(player)) {
                String cmd = event.getParseResults().getReader().getString();
                if (!protectionHandler.isCommandAllowed(cmd)) {
                    event.setCanceled(true);
                    player.sendSystemMessage(authManager.getFormatter().format(config.msgBlocked, player));
                }
            }
        }
    }

    // ─── Dimension Change ───────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                // Re-teleport to limbo
                if (config.limboEnabled) {
                    com.easylogin.util.PlayerUtil.teleportToLimbo(player, config);
                }
            }
        }
    }

    // ─── Respawn ────────────────────────────────────────────

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                if (config.limboEnabled) {
                    com.easylogin.util.PlayerUtil.teleportToLimbo(player, config);
                }
            }
        }
    }
}
