package com.easylogin.forge;

import com.easylogin.auth.AuthManager;
import com.easylogin.config.EasyLoginConfig;
import com.easylogin.handler.ProtectionHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge event handler — mirrors NeoForge version with Forge event classes.
 */
public class ForgeEventHandler {

    private final AuthManager authManager;
    private final ProtectionHandler protectionHandler;
    private final EasyLoginConfig config;

    public ForgeEventHandler(AuthManager authManager, ProtectionHandler protectionHandler, EasyLoginConfig config) {
        this.authManager = authManager;
        this.protectionHandler = protectionHandler;
        this.config = config;
    }

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

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer() != null) {
            authManager.onServerTick(event.getServer());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingAttack(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
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
    public void onLivingHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
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
    public void onDamage(LivingDamageEvent event) {
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onItemDrop(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                event.setCanceled(true);
                player.getInventory().add(event.getEntity().getItem());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (config.blockChat && protectionHandler.shouldBlock(player)) {
            event.setCanceled(true);
            player.sendSystemMessage(authManager.getFormatter().format(config.msgBlocked, player));
        }
    }

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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (protectionHandler.shouldBlock(player)) {
                if (config.limboEnabled) {
                    com.easylogin.util.PlayerUtil.teleportToLimbo(player, config);
                }
            }
        }
    }

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
