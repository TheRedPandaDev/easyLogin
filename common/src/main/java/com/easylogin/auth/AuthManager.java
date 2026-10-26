package com.easylogin.auth;

import com.easylogin.EasyLoginConstants;
import com.easylogin.config.EasyLoginConfig;
import com.easylogin.data.AuthStorage;
import com.easylogin.data.GlobalUser;
import com.easylogin.data.PlayerDataStore;
import com.easylogin.data.ServerIdentity;
import com.easylogin.mods.ClientModTracker;
import com.easylogin.platform.PlatformHelper;
import com.easylogin.util.MessageFormatter;
import com.easylogin.util.PlayerUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    public static AuthManager INSTANCE;

    private final EasyLoginConfig config;
    private final PlayerDataStore dataStore;
    private final RateLimiter rateLimiter;
    private final MessageFormatter formatter;

    private final ConnectionSessions<ServerGamePacketListenerImpl> sessions = new ConnectionSessions<>();
    private final Map<UUID, Long> joinTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastReminder = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> savedPositions = new ConcurrentHashMap<>();
    private final Map<UUID, String> savedDimensions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> logoutCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> invincibilityExpiry = new ConcurrentHashMap<>();

    // Cache to store if a player's nickname has a registered account (queried on join)
    private final Map<UUID, Boolean> isRegisteredCache = new ConcurrentHashMap<>();

    public AuthManager(EasyLoginConfig config, PlayerDataStore dataStore) {
        this.config = config;
        this.dataStore = dataStore;
        this.dataStore.init(config);
        this.rateLimiter = new RateLimiter(config.maxAttempts, config.attemptWindow, config.lockoutDuration);
        this.formatter = new MessageFormatter(config);
        INSTANCE = this;
    }

    private void runOnMainThread(ServerPlayer player,
            ConnectionSessions.Token<ServerGamePacketListenerImpl> session, Runnable action) {
        //? if <=1.21.1 {
        /*MinecraftServer server = player.getServer();
        *///?} else {
        MinecraftServer server = player.level().getServer();
        //?}
        if (server != null) {
            server.execute(() -> {
                // Respawn retains the listener but replaces its ServerPlayer instance.
                if (sessions.isCurrent(session) && isCurrentPlayer(player)) action.run();
            });
        }
    }

    public boolean isAuthenticated(ServerPlayer player) {
        return sessions.isAuthenticated(player.getUUID(), player.connection);
    }

    public boolean isCurrentConnection(ServerPlayer player) {
        return sessions.isCurrent(player.getUUID(), player.connection);
    }

    private boolean isCurrentPlayer(ServerPlayer player) {
        // Entity-targeted work needs this additional check. During respawn events
        // the new player shares the listener before listener.player is reassigned.
        return isCurrentConnection(player) && player.connection.player == player;
    }

    public boolean isInvincible(ServerPlayer player) {
        if (!isAuthenticated(player)) return false;
        Long expiry = invincibilityExpiry.get(player.getUUID());
        if (expiry == null)
            return false;
        if (System.currentTimeMillis() >= expiry) {
            invincibilityExpiry.remove(player.getUUID());
            return false;
        }
        return true;
    }

    public boolean shouldBlock(ServerPlayer player) {
        if (PlatformHelper.INSTANCE.isFakePlayer(player))
            return false;
        return !isAuthenticated(player);
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (PlatformHelper.INSTANCE.isFakePlayer(player))
            return;

        UUID uuid = player.getUUID();
        var session = sessions.begin(uuid, player.connection);
        logoutCooldowns.remove(uuid);
        invincibilityExpiry.remove(uuid);
        isRegisteredCache.remove(uuid);
        joinTimestamps.put(uuid, System.currentTimeMillis());
        lastReminder.put(uuid, 0L);

        // Disable gravity & clear movement velocity to prevent falling/jitter
        player.setNoGravity(true);
        player.setDeltaMovement(0, 0, 0);
        player.resetFallDistance();

        // Save position before limbo
        savePlayerPosition(player);

        if (config.limboEnabled) {
            PlayerUtil.teleportToLimbo(player, config);
        }

        String username = player.getName().getString();
        String normalized = UsernameNormalizer.normalize(username);

        // Notify that authentication storage is loading
        player.sendSystemMessage(Component.literal("§6[EasyLogin] §7Connecting to global accounts server..."));

        CompletableFuture.runAsync(() -> {
            if (!sessions.isCurrent(session)) return;
            try {
                AuthStorage storage = dataStore.getStorage();
                boolean registered = storage.getGlobalUser(normalized).isPresent();
                runOnMainThread(player, session, () -> {
                    if (!joinTimestamps.containsKey(uuid)) return;
                    isRegisteredCache.put(uuid, registered);

                    String msg = registered ? config.msgWelcome : config.msgWelcomeFirstTime;
                    player.sendSystemMessage(formatter.format(msg, player));

                    String titleText = registered ? "&eUse /login" : "&eUse /register";
                    String subtitleText = registered ? "&7Enter your password" : "&7Create your password";
                    PlayerUtil.sendTitle(player, formatter.colorize(titleText), formatter.colorize(subtitleText));

                    EasyLoginConstants.LOGGER.info("Player {} joined, awaiting auth (registered globally: {})", username, registered);
                });
            } catch (Exception e) {
                EasyLoginConstants.LOGGER.error("Failed to fetch account status for " + username, e);
                runOnMainThread(player, session, () -> {
                    player.connection.disconnect(Component.literal("§cAuthentication database is offline. Please contact staff."));
                });
            }
        });
    }

    public void onPlayerLeave(ServerPlayer player) {
        if (PlatformHelper.INSTANCE.isFakePlayer(player))
            return;

        UUID uuid = player.getUUID();
        if (!isCurrentPlayer(player)) return;
        if (!sessions.end(uuid, player.connection)) return;
        player.setNoGravity(false);
        joinTimestamps.remove(uuid);
        lastReminder.remove(uuid);
        savedPositions.remove(uuid);
        savedDimensions.remove(uuid);
        logoutCooldowns.remove(uuid);
        invincibilityExpiry.remove(uuid);
        isRegisteredCache.remove(uuid);
        ClientModTracker.removePlayer(uuid);
    }

    public void attemptLogin(ServerPlayer player, String password) {
        var session = sessions.capture(player.getUUID(), player.connection);
        if (session == null || !isCurrentPlayer(player)) return;
        UUID uuid = player.getUUID();
        String username = player.getName().getString();
        String ip = getPlayerIp(player);

        if (isAuthenticated(player)) {
            player.sendSystemMessage(formatter.format(config.msgAlreadyLoggedIn, player));
            return;
        }

        String rateKey = "ip:" + ip;
        String uuidKey = "uuid:" + uuid;

        long lockedIp = rateLimiter.isLocked(rateKey);
        long lockedUuid = rateLimiter.isLocked(uuidKey);
        long locked = Math.max(lockedIp, lockedUuid);
        if (locked > 0) {
            long minutes = (locked / 60000) + 1;
            player.sendSystemMessage(formatter.format(config.msgRateLimited, player,
                    Map.of("minutes", String.valueOf(minutes))));
            return;
        }

        if (!UsernameNormalizer.isValid(username)) {
            player.sendSystemMessage(Component.literal("§cInvalid nickname."));
            return;
        }

        String normalized = UsernameNormalizer.normalize(username);
        player.sendSystemMessage(Component.literal("§6[EasyLogin] §7Verifying password..."));

        CompletableFuture.runAsync(() -> {
            if (!sessions.isCurrent(session)) return;
            try {
                AuthStorage storage = dataStore.getStorage();
                Optional<GlobalUser> userOpt = storage.getGlobalUser(normalized);

                if (!userOpt.isPresent()) {
                    runOnMainThread(player, session, () -> {
                        player.sendSystemMessage(formatter.format(config.msgNotRegistered, player));
                        PlayerUtil.playSound(player, config.soundLoginFail);
                    });
                    return;
                }

                GlobalUser user = userOpt.get();
                if (!sessions.isCurrent(session)) return;

                if (!"active".equalsIgnoreCase(user.getStatus())) {
                    if (!sessions.isCurrent(session)) return;
                    storage.logAudit("LOGIN_BLOCKED", username, uuid, config.serverId, ip, "Attempt to login to a blocked account");
                    runOnMainThread(player, session, () -> {
                        player.sendSystemMessage(Component.literal("§cYour account is currently blocked."));
                        PlayerUtil.playSound(player, config.soundLoginFail);
                    });
                    return;
                }

                if (PasswordService.verify(password, user.getPasswordDigest())) {
                    // Check local UUID conflict
                    if (storage.hasUuidConflict(config.serverId, uuid, user.getId())) {
                        if (!sessions.isCurrent(session)) return;
                        storage.logAudit("LOGIN_CONFLICT", username, uuid, config.serverId, ip, "Minecraft UUID belongs to another global account on this server");
                        runOnMainThread(player, session, () -> {
                            player.sendSystemMessage(Component.literal("§cThis Minecraft identity is registered to another account."));
                            PlayerUtil.playSound(player, config.soundLoginFail);
                        });
                        return;
                    }

                    // Update identity
                    Optional<ServerIdentity> identityOpt = storage.getServerIdentity(user.getId(), config.serverId);
                    ServerIdentity identity;
                    if (identityOpt.isPresent()) {
                        identity = identityOpt.get();
                        if (!sessions.isCurrent(session)) return;
                        identity.setCurrentUuid(uuid);
                        identity.setLastSeenAt(System.currentTimeMillis());
                        identity.setLastAuthenticatedAt(System.currentTimeMillis());
                        identity.setUpdatedAt(System.currentTimeMillis());
                    } else {
                        identity = new ServerIdentity();
                        identity.setAuthUserId(user.getId());
                        identity.setServerId(config.serverId);
                        identity.setCurrentUuid(uuid);
                    }
                    if (!sessions.isCurrent(session)) return;
                    storage.saveServerIdentity(identity);

                    if (!sessions.isCurrent(session)) return;
                    storage.recordUuidHistory(identity.getId(), config.serverId, uuid, "login");

                    if (!sessions.isCurrent(session)) return;
                    user.setLastGlobalLoginAt(System.currentTimeMillis());
                    if (!sessions.isCurrent(session)) return;
                    storage.saveGlobalUser(user);

                    if (!sessions.isCurrent(session)) return;
                    storage.logAudit("LOGIN_SUCCESS", username, uuid, config.serverId, ip, "Successful login");
                    if (!sessions.isCurrent(session)) return;
                    rateLimiter.clear(rateKey);
                    if (!sessions.isCurrent(session)) return;
                    rateLimiter.clear(uuidKey);

                    // Rails WebStore Sync (Asynchronous)
                    if (!sessions.isCurrent(session)) return;
                    WebStoreSyncService.syncAccount(config, user, uuid);

                    runOnMainThread(player, session, () -> {
                        if (!authenticatePlayer(player, session)) return;
                        isRegisteredCache.put(uuid, true);
                        player.sendSystemMessage(formatter.format(config.msgLoginSuccess, player));
                        PlayerUtil.playSound(player, config.soundLoginSuccess);
                        EasyLoginConstants.LOGGER.info("Player {} logged in successfully from {}", username, ip);
                    });
                } else {
                    if (!sessions.isCurrent(session)) return;
                    int remainingIp = rateLimiter.recordFailure(rateKey);
                    int remainingUuid = rateLimiter.recordFailure(uuidKey);
                    int remaining = Math.min(
                            remainingIp == -1 ? 0 : remainingIp,
                            remainingUuid == -1 ? 0 : remainingUuid);

                    if (!sessions.isCurrent(session)) return;
                    storage.logAudit("LOGIN_FAIL_PASSWORD", username, uuid, config.serverId, ip, "Wrong password attempt");

                    runOnMainThread(player, session, () -> {
                        if (remaining <= 0) {
                            long lockoutMinutes = config.lockoutDuration;
                            player.sendSystemMessage(formatter.format(config.msgRateLimited, player,
                                    Map.of("minutes", String.valueOf(lockoutMinutes))));
                            EasyLoginConstants.LOGGER.warn("Player {} locked out (too many attempts) from {}", username, ip);
                        } else {
                            player.sendSystemMessage(formatter.format(config.msgWrongPassword, player,
                                    Map.of("attempts", String.valueOf(remaining))));
                        }
                        PlayerUtil.playSound(player, config.soundLoginFail);
                    });
                }
            } catch (Exception e) {
                EasyLoginConstants.LOGGER.error("Failed to authenticate user " + username, e);
                runOnMainThread(player, session, () -> {
                    player.sendSystemMessage(Component.literal("§cAn internal database error occurred. Login failed."));
                });
            }
        });
    }

    public void attemptRegister(ServerPlayer player, String password, String confirmPassword) {
        var session = sessions.capture(player.getUUID(), player.connection);
        if (session == null || !isCurrentPlayer(player)) return;
        UUID uuid = player.getUUID();
        String username = player.getName().getString();
        String ip = getPlayerIp(player);

        if (isAuthenticated(player)) {
            player.sendSystemMessage(formatter.format(config.msgAlreadyLoggedIn, player));
            return;
        }

        if (!password.equals(confirmPassword)) {
            player.sendSystemMessage(formatter.format(config.msgPasswordMismatch, player));
            return;
        }

        if (password.length() < config.minPasswordLength) {
            player.sendSystemMessage(formatter.format(config.msgMinPasswordLength, player,
                    Map.of("min", String.valueOf(config.minPasswordLength))));
            return;
        }

        if (!UsernameNormalizer.isValid(username)) {
            player.sendSystemMessage(Component.literal("§cInvalid username. Only alphanumeric characters and underscores are allowed."));
            return;
        }

        String normalized = UsernameNormalizer.normalize(username);
        player.sendSystemMessage(Component.literal("§6[EasyLogin] §7Creating account..."));

        CompletableFuture.runAsync(() -> {
            if (!sessions.isCurrent(session)) return;
            try {
                AuthStorage storage = dataStore.getStorage();
                Optional<GlobalUser> existing = storage.getGlobalUser(normalized);

                if (existing.isPresent()) {
                    runOnMainThread(player, session, () -> {
                        player.sendSystemMessage(formatter.format(config.msgAlreadyRegistered, player));
                    });
                    return;
                }

                // Check local UUID conflict
                if (storage.hasUuidConflict(config.serverId, uuid, 0)) {
                    if (!sessions.isCurrent(session)) return;
                    storage.logAudit("REGISTER_CONFLICT", username, uuid, config.serverId, ip, "Minecraft UUID belongs to another global account on this server");
                    runOnMainThread(player, session, () -> {
                        player.sendSystemMessage(Component.literal("§cThis Minecraft identity is registered to another account."));
                        PlayerUtil.playSound(player, config.soundLoginFail);
                    });
                    return;
                }

                String hash = PasswordService.hash(password);

                GlobalUser user = new GlobalUser();
                user.setUsernameDisplay(username);
                user.setUsernameNormalized(normalized);
                user.setPasswordDigest(hash);
                user.setStatus("active");
                if (!sessions.isCurrent(session)) return;
                storage.saveGlobalUser(user);

                ServerIdentity identity = new ServerIdentity();
                identity.setAuthUserId(user.getId());
                identity.setServerId(config.serverId);
                identity.setCurrentUuid(uuid);
                if (!sessions.isCurrent(session)) return;
                storage.saveServerIdentity(identity);

                if (!sessions.isCurrent(session)) return;
                storage.recordUuidHistory(identity.getId(), config.serverId, uuid, "register");
                if (!sessions.isCurrent(session)) return;
                storage.logAudit("REGISTER_SUCCESS", username, uuid, config.serverId, ip, "Account registered successfully");

                // Rails WebStore Sync (Asynchronous)
                if (!sessions.isCurrent(session)) return;
                WebStoreSyncService.syncAccount(config, user, uuid);

                runOnMainThread(player, session, () -> {
                    if (!authenticatePlayer(player, session)) return;
                    isRegisteredCache.put(uuid, true);
                    player.sendSystemMessage(formatter.format(config.msgRegisterSuccess, player));
                    PlayerUtil.playSound(player, config.soundLoginSuccess);
                    EasyLoginConstants.LOGGER.info("Player {} registered and logged in from {}", username, ip);
                });
            } catch (Exception e) {
                EasyLoginConstants.LOGGER.error("Failed to register account for " + username, e);
                runOnMainThread(player, session, () -> {
                    player.sendSystemMessage(Component.literal("§cAn internal database error occurred. Registration failed."));
                });
            }
        });
    }

    public void changePassword(ServerPlayer player, String oldPassword, String newPassword, String confirmNew) {
        var session = sessions.capture(player.getUUID(), player.connection);
        if (session == null || !isCurrentPlayer(player)) return;
        UUID uuid = player.getUUID();
        String username = player.getName().getString();

        if (!isAuthenticated(player)) {
            player.sendSystemMessage(formatter.format(config.msgBlocked, player));
            return;
        }

        if (!newPassword.equals(confirmNew)) {
            player.sendSystemMessage(formatter.format(config.msgPasswordMismatch, player));
            return;
        }

        if (newPassword.length() < config.minPasswordLength) {
            player.sendSystemMessage(formatter.format(config.msgMinPasswordLength, player,
                    Map.of("min", String.valueOf(config.minPasswordLength))));
            return;
        }

        String normalized = UsernameNormalizer.normalize(username);
        player.sendSystemMessage(Component.literal("§6[EasyLogin] §7Changing password..."));

        CompletableFuture.runAsync(() -> {
            if (!sessions.isCurrent(session)) return;
            try {
                AuthStorage storage = dataStore.getStorage();
                Optional<GlobalUser> userOpt = storage.getGlobalUser(normalized);

                if (!userOpt.isPresent()) {
                    runOnMainThread(player, session, () -> {
                        player.sendSystemMessage(formatter.format(config.msgNotRegistered, player));
                    });
                    return;
                }

                GlobalUser user = userOpt.get();
                if (!sessions.isCurrent(session)) return;

                if (!PasswordService.verify(oldPassword, user.getPasswordDigest())) {
                    runOnMainThread(player, session, () -> {
                        player.sendSystemMessage(formatter.format(config.msgWrongOldPassword, player));
                    });
                    return;
                }

                String hash = PasswordService.hash(newPassword);
                if (!sessions.isCurrent(session)) return;
                user.setPasswordDigest(hash);
                user.setPasswordChangedAt(System.currentTimeMillis());
                if (!sessions.isCurrent(session)) return;
                storage.saveGlobalUser(user);

                if (!sessions.isCurrent(session)) return;
                storage.logAudit("CHANGE_PASSWORD", username, uuid, config.serverId, getPlayerIp(player), "Password changed successfully");

                runOnMainThread(player, session, () -> {
                    player.sendSystemMessage(formatter.format(config.msgPasswordChanged, player));
                    EasyLoginConstants.LOGGER.info("Player {} changed their password", username);
                });
            } catch (Exception e) {
                EasyLoginConstants.LOGGER.error("Failed to change password for " + username, e);
                runOnMainThread(player, session, () -> {
                    player.sendSystemMessage(Component.literal("§cAn internal database error occurred. Password not changed."));
                });
            }
        });
    }

    public boolean logout(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String username = player.getName().getString();
        if (!isCurrentPlayer(player) || !isAuthenticated(player))
            return false;
        if (!config.enableLogout)
            return false;

        Long lastLogout = logoutCooldowns.get(uuid);
        if (lastLogout != null) {
            long elapsed = (System.currentTimeMillis() - lastLogout) / 1000;
            if (elapsed < config.logoutCooldown) {
                long remaining = config.logoutCooldown - elapsed;
                player.sendSystemMessage(formatter.format(config.msgLogoutCooldown, player,
                        Map.of("seconds", String.valueOf(remaining))));
                return false;
            }
        }

        deauthenticatePlayer(player);
        logoutCooldowns.put(uuid, System.currentTimeMillis());
        player.sendSystemMessage(formatter.format(config.msgLogoutSuccess, player));

        CompletableFuture.runAsync(() -> {
            try {
                dataStore.getStorage().logAudit("LOGOUT", username, uuid, config.serverId, getPlayerIp(player), "Logout performed locally");
            } catch (Exception e) {
                // Ignore audit failure on logout
            }
        });

        return true;
    }

    public void forceLogin(ServerPlayer target) {
        var pending = sessions.capture(target.getUUID(), target.connection);
        if (!authenticatePlayer(target, pending)) return;
        var session = sessions.capture(target.getUUID(), target.connection);
        UUID uuid = target.getUUID();
        String username = target.getName().getString();
        String normalized = UsernameNormalizer.normalize(username);
        String ip = getPlayerIp(target);

        CompletableFuture.runAsync(() -> {
            if (!sessions.isCurrent(session)) return;
            try {
                AuthStorage storage = dataStore.getStorage();
                Optional<GlobalUser> userOpt = storage.getGlobalUser(normalized);
                if (userOpt.isPresent()) {
                    GlobalUser user = userOpt.get();
                    if (!sessions.isCurrent(session)) return;
                    Optional<ServerIdentity> identityOpt = storage.getServerIdentity(user.getId(), config.serverId);
                    ServerIdentity identity;
                    if (identityOpt.isPresent()) {
                        identity = identityOpt.get();
                        if (!sessions.isCurrent(session)) return;
                        identity.setCurrentUuid(uuid);
                        identity.setLastSeenAt(System.currentTimeMillis());
                        identity.setLastAuthenticatedAt(System.currentTimeMillis());
                        identity.setUpdatedAt(System.currentTimeMillis());
                    } else {
                        identity = new ServerIdentity();
                        identity.setAuthUserId(user.getId());
                        identity.setServerId(config.serverId);
                        identity.setCurrentUuid(uuid);
                    }
                    if (!sessions.isCurrent(session)) return;
                    storage.saveServerIdentity(identity);
                    if (!sessions.isCurrent(session)) return;
                    storage.recordUuidHistory(identity.getId(), config.serverId, uuid, "force_login");
                }
                if (!sessions.isCurrent(session)) return;
                storage.logAudit("FORCE_LOGIN", username, uuid, config.serverId, ip, "Admin force-logged in user");
            } catch (Exception e) {
                EasyLoginConstants.LOGGER.error("Error in forceLogin async", e);
            }
        });
    }

    public void forceRegister(ServerPlayer target, String password) {
        var session = sessions.capture(target.getUUID(), target.connection);
        if (session == null || !isCurrentPlayer(target)) return;
        UUID uuid = target.getUUID();
        String username = target.getName().getString();
        String normalized = UsernameNormalizer.normalize(username);
        String ip = getPlayerIp(target);

        CompletableFuture.runAsync(() -> {
            if (!sessions.isCurrent(session)) return;
            try {
                AuthStorage storage = dataStore.getStorage();
                String hash = PasswordService.hash(password);

                Optional<GlobalUser> userOpt = storage.getGlobalUser(normalized);
                GlobalUser user;
                if (userOpt.isPresent()) {
                    user = userOpt.get();
                    if (!sessions.isCurrent(session)) return;
                    user.setPasswordDigest(hash);
                    user.setPasswordChangedAt(System.currentTimeMillis());
                    if (!sessions.isCurrent(session)) return;
                    storage.saveGlobalUser(user);
                } else {
                    user = new GlobalUser();
                    user.setUsernameDisplay(username);
                    user.setUsernameNormalized(normalized);
                    user.setPasswordDigest(hash);
                    user.setStatus("active");
                    if (!sessions.isCurrent(session)) return;
                    storage.saveGlobalUser(user);
                }

                Optional<ServerIdentity> identityOpt = storage.getServerIdentity(user.getId(), config.serverId);
                ServerIdentity identity;
                if (identityOpt.isPresent()) {
                    identity = identityOpt.get();
                    if (!sessions.isCurrent(session)) return;
                    identity.setCurrentUuid(uuid);
                    identity.setLastSeenAt(System.currentTimeMillis());
                    identity.setLastAuthenticatedAt(System.currentTimeMillis());
                    identity.setUpdatedAt(System.currentTimeMillis());
                } else {
                    identity = new ServerIdentity();
                    identity.setAuthUserId(user.getId());
                    identity.setServerId(config.serverId);
                    identity.setCurrentUuid(uuid);
                }
                if (!sessions.isCurrent(session)) return;
                storage.saveServerIdentity(identity);
                if (!sessions.isCurrent(session)) return;
                storage.recordUuidHistory(identity.getId(), config.serverId, uuid, "force_register");

                if (!sessions.isCurrent(session)) return;
                storage.logAudit("FORCE_REGISTER", username, uuid, config.serverId, ip, "Admin force-registered user");
                runOnMainThread(target, session, () -> {
                    if (!authenticatePlayer(target, session)) return;
                    isRegisteredCache.put(uuid, true);
                });
            } catch (Exception e) {
                EasyLoginConstants.LOGGER.error("Error in forceRegister async", e);
            }
        });
    }

    public void onServerTick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());

        for (ServerPlayer player : players) {
            if (PlatformHelper.INSTANCE.isFakePlayer(player))
                continue;

            UUID uuid = player.getUUID();
            if (!isCurrentConnection(player) || isAuthenticated(player))
                continue;

            Long joinTime = joinTimestamps.get(uuid);
            if (joinTime != null) {
                long elapsed = (now - joinTime) / 1000;
                if (elapsed >= config.loginTimeout) {
                    player.connection.disconnect(Component.literal(formatter.colorize(config.msgKicked)));
                    EasyLoginConstants.LOGGER.info("Player {} kicked for login timeout", player.getName().getString());
                    continue;
                }
            }

            Long lastRem = lastReminder.get(uuid);
            if (lastRem != null) {
                long elapsed = (now - lastRem) / 1000;
                if (elapsed >= config.reminderInterval) {
                    boolean registered = isRegisteredCache.getOrDefault(uuid, false);
                    String msg = registered ? config.msgActionBarReminder : config.msgActionBarReminderNew;
                    PlayerUtil.sendActionBar(player, formatter.format(msg, player));
                    lastReminder.put(uuid, now);
                }
            }

            if (config.blockMovement) {
                player.setNoGravity(true);
                player.setDeltaMovement(0, 0, 0);
                player.resetFallDistance();

                if (config.limboEnabled) {
                    double dx = Math.abs(player.getX() - config.limboX);
                    double dz = Math.abs(player.getZ() - config.limboZ);
                    if (dx > 1.5 || dz > 1.5) {
                        PlayerUtil.teleportToLimbo(player, config);
                    }
                } else {
                    double[] pos = savedPositions.get(uuid);
                    if (pos != null) {
                        double dx = Math.abs(player.getX() - pos[0]);
                        double dy = Math.abs(player.getY() - pos[1]);
                        double dz = Math.abs(player.getZ() - pos[2]);

                        if (dx > 0.1 || dy > 0.1 || dz > 0.1) {
                            player.teleportTo(pos[0], pos[1], pos[2]);
                            player.setDeltaMovement(0, 0, 0);
                            player.hurtMarked = true;
                        }
                    }
                }
            }
        }

        if (server.getTickCount() % 600 == 0) {
            rateLimiter.cleanup();
        }
    }

    private boolean authenticatePlayer(ServerPlayer player,
            ConnectionSessions.Token<ServerGamePacketListenerImpl> session) {
        if (!isCurrentPlayer(player)) return false;
        if (!sessions.authenticate(session)) return false;
        UUID uuid = player.getUUID();
        joinTimestamps.remove(uuid);
        lastReminder.remove(uuid);

        // Restore gravity & reset fall distance
        player.setNoGravity(false);
        player.resetFallDistance();

        // Teleport back
        if (config.limboEnabled) {
            restorePlayerPosition(player);
        }

        if (config.invincibilityDuration > 0) {
            invincibilityExpiry.put(uuid, System.currentTimeMillis() + (config.invincibilityDuration * 1000L));
        }

        PlayerUtil.clearTitle(player);
        resyncInventory(player);
        return true;
    }

    private void deauthenticatePlayer(ServerPlayer player) {
        if (!isCurrentPlayer(player)) return;
        UUID uuid = player.getUUID();
        if (!sessions.deauthenticate(uuid, player.connection)) return;
        invincibilityExpiry.remove(uuid);
        joinTimestamps.put(uuid, System.currentTimeMillis());
        lastReminder.put(uuid, 0L);

        savePlayerPosition(player);

        if (config.limboEnabled) {
            PlayerUtil.teleportToLimbo(player, config);
        }
        resyncInventory(player);
    }

    private static void resyncInventory(ServerPlayer player) {
        player.inventoryMenu.sendAllDataToRemote();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.sendAllDataToRemote();
        }
    }

    private void savePlayerPosition(ServerPlayer player) {
        savedPositions.put(player.getUUID(), new double[] {
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()
        });
        savedDimensions.put(player.getUUID(),
                player.level().dimension().location().toString());
    }

    private void restorePlayerPosition(ServerPlayer player) {
        double[] pos = savedPositions.remove(player.getUUID());
        String dim = savedDimensions.remove(player.getUUID());

        if (pos != null && dim != null) {
            //? if <=1.21.1 {
            /*MinecraftServer server = player.getServer();
            *///?} else {
            MinecraftServer server = player.level().getServer();
            //?}
            if (server != null) {
                ResourceLocation dimRL = ResourceLocation.parse(dim);
                for (ServerLevel level : server.getAllLevels()) {
                    if (level.dimension().location().equals(dimRL)) {
                        player.teleportTo(level, pos[0], pos[1], pos[2], java.util.EnumSet.noneOf(net.minecraft.world.entity.RelativeMovement.class), (float) pos[3], (float) pos[4]);
                        return;
                    }
                }
            }
            // Fallback
            player.teleportTo((ServerLevel) player.level(), pos[0], pos[1], pos[2], java.util.EnumSet.noneOf(net.minecraft.world.entity.RelativeMovement.class), (float) pos[3], (float) pos[4]);
        }
    }

    private String getPlayerIp(ServerPlayer player) {
        try {
            return player.getIpAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public EasyLoginConfig getConfig() {
        return config;
    }

    public PlayerDataStore getDataStore() {
        return dataStore;
    }

    public MessageFormatter getFormatter() {
        return formatter;
    }
}
