package com.easylogin.command;

import com.easylogin.EasyLoginConstants;
import com.easylogin.auth.AuthManager;
import com.easylogin.auth.UsernameNormalizer;
import com.easylogin.config.EasyLoginConfig;
import com.easylogin.data.AuthStorage;
import com.easylogin.data.GlobalUser;
import com.easylogin.data.ServerIdentity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AuthCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            AuthManager authManager,
            EasyLoginConfig config,
            Runnable reloadAction) {

        dispatcher.register(Commands.literal("auth")
                // /auth status
                .then(Commands.literal("status")
                        .requires(src -> {
                            return src.hasPermission(2);
                        })
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            int online = source.getServer().getPlayerCount();
                            
                            CompletableFuture.runAsync(() -> {
                                try {
                                    int total = authManager.getDataStore().getAccountCount();
                                    source.sendSuccess(() -> Component.literal(
                                            "§6[EasyLogin] §7Registered accounts: §e" + total +
                                                    " §7| Online: §e" + online +
                                                    " §7| Storage mode: §e" + config.storageMode),
                                            false);
                                } catch (Exception e) {
                                    source.sendFailure(Component.literal("§cDatabase unavailable."));
                                }
                            });
                            return 1;
                        }))

                // /auth reload
                .then(Commands.literal("reload")
                        .requires(src -> {
                            return src.hasPermission(3);
                        })
                        .executes(ctx -> {
                            reloadAction.run();
                            authManager.getDataStore().init(config); // re-init pool
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    authManager.getFormatter().colorize(config.msgAdminReload)), true);
                            EasyLoginConstants.LOGGER.info("Configuration reloaded by {}",
                                    ctx.getSource().getTextName());
                            return 1;
                        }))

                // /auth force-login <nickname>
                .then(Commands.literal("force-login")
                        .requires(src -> {
                            return src.hasPermission(3);
                        })
                        .then(Commands.argument("nickname", StringArgumentType.word())
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String nickname = StringArgumentType.getString(ctx, "nickname");
                                    ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(nickname);
                                    if (target == null) {
                                        source.sendFailure(Component.literal("§cPlayer is not online: " + nickname));
                                        return 1;
                                    }
                                    authManager.forceLogin(target);
                                    source.sendSuccess(() -> Component.literal("§aForce-logged in online player " + target.getName().getString()), true);
                                    EasyLoginConstants.LOGGER.info("Admin {} force-logged in {}",
                                            source.getTextName(), target.getName().getString());
                                    return 1;
                                })))

                // /auth force-register <nickname> <password>
                .then(Commands.literal("force-register")
                        .requires(src -> {
                            return src.hasPermission(3);
                        })
                        .then(Commands.argument("nickname", StringArgumentType.word())
                                .then(Commands.argument("password", StringArgumentType.word())
                                        .executes(ctx -> {
                                            CommandSourceStack source = ctx.getSource();
                                            String nickname = StringArgumentType.getString(ctx, "nickname");
                                            String password = StringArgumentType.getString(ctx, "password");
                                            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(nickname);
                                            if (target != null) {
                                                authManager.forceRegister(target, password);
                                                source.sendSuccess(() -> Component.literal("§aForce-registered online player " + target.getName().getString()), true);
                                            } else {
                                                // Register offline player in db
                                                CompletableFuture.runAsync(() -> {
                                                    try {
                                                        AuthStorage storage = authManager.getDataStore().getStorage();
                                                        String normalized = UsernameNormalizer.normalize(nickname);
                                                        if (storage.getGlobalUser(normalized).isPresent()) {
                                                            source.sendFailure(Component.literal("§cGlobal account already exists: " + nickname));
                                                            return;
                                                        }
                                                        GlobalUser user = new GlobalUser();
                                                        user.setUsernameDisplay(nickname);
                                                        user.setUsernameNormalized(normalized);
                                                        user.setPasswordDigest(com.easylogin.auth.PasswordService.hash(password));
                                                        user.setStatus("active");
                                                        storage.saveGlobalUser(user);
                                                        source.sendSuccess(() -> Component.literal("§aForce-registered offline player " + nickname + " (global ID: " + user.getPublicId() + ")"), true);
                                                        EasyLoginConstants.LOGGER.info("Admin {} force-registered offline player {}", source.getTextName(), nickname);
                                                    } catch (Exception e) {
                                                        source.sendFailure(Component.literal("§cDatabase error force-registering offline player."));
                                                    }
                                                });
                                            }
                                            return 1;
                                        }))))

                // /auth reset-password <nickname>
                .then(Commands.literal("reset-password")
                        .requires(src -> {
                            return src.hasPermission(3);
                        })
                        .then(Commands.argument("nickname", StringArgumentType.word())
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String nickname = StringArgumentType.getString(ctx, "nickname");
                                    String normalized = UsernameNormalizer.normalize(nickname);

                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            AuthStorage storage = authManager.getDataStore().getStorage();
                                            Optional<GlobalUser> userOpt = storage.getGlobalUser(normalized);
                                            if (!userOpt.isPresent()) {
                                                source.sendFailure(Component.literal("§cGlobal account not found: " + nickname));
                                                return;
                                            }
                                            GlobalUser user = userOpt.get();
                                            storage.deleteGlobalUser(user.getId());
                                            storage.logAudit("RESET_PASSWORD", nickname, null, config.serverId, "admin", "Admin reset account (deleted user)");
                                            
                                            // If player is online, disconnect them
                                            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(nickname);
                                            if (target != null) {
                                                source.getServer().execute(() -> {
                                                    target.connection.disconnect(Component.literal("§cYour password was reset. Please reconnect and register again."));
                                                });
                                            }
                                            source.sendSuccess(() -> Component.literal("§aAccount deleted/reset for " + nickname), true);
                                        } catch (Exception e) {
                                            source.sendFailure(Component.literal("§cDatabase error resetting password."));
                                        }
                                    });
                                    return 1;
                                })))

                // /auth purge <nickname> (requires confirm)
                .then(Commands.literal("purge")
                        .requires(src -> {
                            return src.hasPermission(3);
                        })
                        .then(Commands.argument("nickname", StringArgumentType.word())
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String nickname = StringArgumentType.getString(ctx, "nickname");
                                    source.sendSuccess(() -> Component.literal("§cWARNING: Purging will permanently delete the global account '" + nickname + "' and ALL its local server identities and histories across ALL servers!"), false);
                                    source.sendSuccess(() -> Component.literal("§7To confirm, run: §e/auth purge " + nickname + " confirm"), false);
                                    return 1;
                                })
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> {
                                            CommandSourceStack source = ctx.getSource();
                                            String nickname = StringArgumentType.getString(ctx, "nickname");
                                            String normalized = UsernameNormalizer.normalize(nickname);

                                            CompletableFuture.runAsync(() -> {
                                                try {
                                                    AuthStorage storage = authManager.getDataStore().getStorage();
                                                    Optional<GlobalUser> userOpt = storage.getGlobalUser(normalized);
                                                    if (!userOpt.isPresent()) {
                                                        source.sendFailure(Component.literal("§cGlobal account not found: " + nickname));
                                                        return;
                                                    }
                                                    GlobalUser user = userOpt.get();
                                                    storage.deleteGlobalUser(user.getId());
                                                    storage.logAudit("PURGE", nickname, null, config.serverId, "admin", "Admin purged account globally");

                                                    ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(nickname);
                                                    if (target != null) {
                                                        source.getServer().execute(() -> {
                                                            target.connection.disconnect(Component.literal("§cYour account has been purged globally."));
                                                        });
                                                    }
                                                    source.sendSuccess(() -> Component.literal("§aSuccessfully purged account globally: " + nickname), true);
                                                } catch (Exception e) {
                                                    source.sendFailure(Component.literal("§cDatabase error purging account."));
                                                }
                                            });
                                            return 1;
                                        }))))

                // /auth lookup <nickname>
                .then(Commands.literal("lookup")
                        .requires(src -> {
                            return src.hasPermission(3);
                        })
                        .then(Commands.argument("nickname", StringArgumentType.word())
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String nickname = StringArgumentType.getString(ctx, "nickname");
                                    String normalized = UsernameNormalizer.normalize(nickname);

                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            AuthStorage storage = authManager.getDataStore().getStorage();
                                            Optional<GlobalUser> userOpt = storage.getGlobalUser(normalized);
                                            if (!userOpt.isPresent()) {
                                                source.sendFailure(Component.literal("§cGlobal account not found: " + nickname));
                                                return;
                                            }
                                            GlobalUser u = userOpt.get();
                                            source.sendSuccess(() -> Component.literal("§6--- Global Account Details ---"), false);
                                            source.sendSuccess(() -> Component.literal("§7ID: §e" + u.getId() + " §7| Public ID: §e" + u.getPublicId()), false);
                                            source.sendSuccess(() -> Component.literal("§7Display Name: §e" + u.getUsernameDisplay() + " §7| Normalized: §e" + u.getUsernameNormalized()), false);
                                            source.sendSuccess(() -> Component.literal("§7Status: §e" + u.getStatus()), false);
                                            
                                            // Print server identities
                                            Collection<ServerIdentity> identities = storage.getServerIdentities(u.getId());
                                            source.sendSuccess(() -> Component.literal("§7Server Identities:"), false);
                                            for (ServerIdentity si : identities) {
                                                source.sendSuccess(() -> Component.literal("  §8- §7Server: §e" + si.getServerId() + " §7| Current UUID: §b" + si.getCurrentUuid()), false);
                                            }
                                        } catch (Exception e) {
                                            source.sendFailure(Component.literal("§cDatabase error retrieving lookup details."));
                                        }
                                    });
                                    return 1;
                                })))

                // /auth migration
                .then(Commands.literal("migration")
                        .requires(src -> {
                            return src.hasPermission(3);
                        })
                        .then(Commands.literal("import")
                                .then(Commands.argument("jsonPath", StringArgumentType.string())
                                        .executes(ctx -> {
                                            CommandSourceStack source = ctx.getSource();
                                            String jsonStr = StringArgumentType.getString(ctx, "jsonPath");

                                            CompletableFuture.runAsync(() -> {
                                                try {
                                                    java.nio.file.Path json = java.nio.file.Paths.get(jsonStr);
                                                    java.nio.file.Path report = json.getParent().resolve("migration_report.txt");
                                                    com.easylogin.data.MigrationManager.MigrationReport rep = com.easylogin.data.MigrationManager.importSingleJson(json, report, authManager.getDataStore().getStorage());
                                                    source.sendSuccess(() -> Component.literal("§6[Migration Import] §aCompleted! Report saved to: §e" + report.toAbsolutePath()), false);
                                                    source.sendSuccess(() -> Component.literal(String.format("§7Imported: %d, Skipped/Conflicts: %d", rep.uniqueGlobalUsers - rep.passwordConflicts.size() - rep.corruptedRecords.size(), rep.passwordConflicts.size() + rep.corruptedRecords.size())), false);
                                                } catch (Exception e) {
                                                    source.sendFailure(Component.literal("§cImport failed: " + e.getMessage()));
                                                }
                                            });
                                            return 1;
                                        })))
                        .then(Commands.literal("status")
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    source.sendSuccess(() -> Component.literal("§6[EasyLogin Migration] §7Storage Mode: §e" + config.storageMode), false);
                                    return 1;
                                }))
                        .then(Commands.literal("dryrun")
                                .then(Commands.argument("atmPath", StringArgumentType.string())
                                        .then(Commands.argument("cvPath", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    String atmStr = StringArgumentType.getString(ctx, "atmPath");
                                                    String cvStr = StringArgumentType.getString(ctx, "cvPath");
                                                    
                                                    CompletableFuture.runAsync(() -> {
                                                        try {
                                                            java.nio.file.Path atm = java.nio.file.Paths.get(atmStr);
                                                            java.nio.file.Path cv = java.nio.file.Paths.get(cvStr);
                                                            java.nio.file.Path report = atm.getParent().resolve("migration_report.txt");
                                                            
                                                            com.easylogin.data.MigrationManager.MigrationReport rep = com.easylogin.data.MigrationManager.dryRun(atm, cv, report);
                                                            source.sendSuccess(() -> Component.literal("§6[Migration Dry-Run] §aCompleted! Report saved to: §e" + report.toAbsolutePath()), false);
                                                            source.sendSuccess(() -> Component.literal(String.format("§7ATM: %d, CV: %d, Unique: %d, Password Conflicts: %d", rep.allTheMonsCount, rep.cobbleverseCount, rep.uniqueGlobalUsers, rep.passwordConflicts.size())), false);
                                                        } catch (Exception e) {
                                                            source.sendFailure(Component.literal("§cDry-run failed: " + e.getMessage()));
                                                        }
                                                    });
                                                    return 1;
                                                }))))
                        .then(Commands.literal("execute")
                                .then(Commands.argument("atmPath", StringArgumentType.string())
                                        .then(Commands.argument("cvPath", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    String atmStr = StringArgumentType.getString(ctx, "atmPath");
                                                    String cvStr = StringArgumentType.getString(ctx, "cvPath");
                                                    
                                                    CompletableFuture.runAsync(() -> {
                                                        try {
                                                            java.nio.file.Path atm = java.nio.file.Paths.get(atmStr);
                                                            java.nio.file.Path cv = java.nio.file.Paths.get(cvStr);
                                                            java.nio.file.Path report = atm.getParent().resolve("migration_report.txt");
                                                            
                                                            com.easylogin.data.MigrationManager.MigrationReport rep = com.easylogin.data.MigrationManager.executeMigration(atm, cv, report, authManager.getDataStore().getStorage());
                                                            source.sendSuccess(() -> Component.literal("§6[Migration Execute] §aCompleted! Report saved to: §e" + report.toAbsolutePath()), false);
                                                            source.sendSuccess(() -> Component.literal(String.format("§7Migrated unique accounts. Conflicts: %d (recorded in conflicts table).", rep.passwordConflicts.size())), false);
                                                        } catch (Exception e) {
                                                            source.sendFailure(Component.literal("§cMigration failed: " + e.getMessage()));
                                                        }
                                                    });
                                                    return 1;
                                                }))))
                        .then(Commands.literal("resolve")
                                .then(Commands.argument("nickname", StringArgumentType.word())
                                        .then(Commands.literal("choose")
                                                .then(Commands.argument("server", StringArgumentType.word())
                                                        .then(Commands.argument("atmPath", StringArgumentType.string())
                                                                .then(Commands.argument("cvPath", StringArgumentType.string())
                                                                        .executes(ctx -> {
                                                                            CommandSourceStack source = ctx.getSource();
                                                                            String nickname = StringArgumentType.getString(ctx, "nickname");
                                                                            String server = StringArgumentType.getString(ctx, "server");
                                                                            String atmStr = StringArgumentType.getString(ctx, "atmPath");
                                                                            String cvStr = StringArgumentType.getString(ctx, "cvPath");
                                                                            
                                                                            CompletableFuture.runAsync(() -> {
                                                                                try {
                                                                                    java.nio.file.Path atm = java.nio.file.Paths.get(atmStr);
                                                                                    java.nio.file.Path cv = java.nio.file.Paths.get(cvStr);
                                                                                    boolean ok = com.easylogin.data.MigrationManager.resolveConflictChoose(nickname, server, atm, cv, authManager.getDataStore().getStorage());
                                                                                    if (ok) {
                                                                                        source.sendSuccess(() -> Component.literal("§6[Migration Resolve] §aConflict resolved for " + nickname + " choosing " + server), true);
                                                                                    } else {
                                                                                        source.sendFailure(Component.literal("§cConflict resolution failed. Check names and paths."));
                                                                                    }
                                                                                } catch (Exception e) {
                                                                                    source.sendFailure(Component.literal("§cError: " + e.getMessage()));
                                                                                }
                                                                            });
                                                                            return 1;
                                                                        })))))
                                        .then(Commands.literal("reset")
                                                .then(Commands.argument("tempPassword", StringArgumentType.word())
                                                        .then(Commands.argument("atmPath", StringArgumentType.string())
                                                                .then(Commands.argument("cvPath", StringArgumentType.string())
                                                                        .executes(ctx -> {
                                                                            CommandSourceStack source = ctx.getSource();
                                                                            String nickname = StringArgumentType.getString(ctx, "nickname");
                                                                            String tempPass = StringArgumentType.getString(ctx, "tempPassword");
                                                                            String atmStr = StringArgumentType.getString(ctx, "atmPath");
                                                                            String cvStr = StringArgumentType.getString(ctx, "cvPath");
                                                                            
                                                                            CompletableFuture.runAsync(() -> {
                                                                                try {
                                                                                    java.nio.file.Path atm = java.nio.file.Paths.get(atmStr);
                                                                                    java.nio.file.Path cv = java.nio.file.Paths.get(cvStr);
                                                                                    boolean ok = com.easylogin.data.MigrationManager.resolveConflictReset(nickname, tempPass, atm, cv, authManager.getDataStore().getStorage());
                                                                                    if (ok) {
                                                                                        source.sendSuccess(() -> Component.literal("§6[Migration Resolve] §aConflict resolved for " + nickname + " with password reset."), true);
                                                                                    } else {
                                                                                        source.sendFailure(Component.literal("§cConflict resolution failed. Check nickname."));
                                                                                    }
                                                                                } catch (Exception e) {
                                                                                    source.sendFailure(Component.literal("§cError: " + e.getMessage()));
                                                                                }
                                                                            });
                                                                            return 1;
                                                                        }))))))))
        );
    }
}
