package com.easylogin.config;

import com.easylogin.EasyLoginConstants;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple TOML-like config parser and manager.
 * Supports sections, string/int/double/boolean values, and comments.
 */
public class EasyLoginConfig {

    // ─── General ────────────────────────────────────────────
    public int loginTimeout = 60;
    public int reminderInterval = 10;
    public boolean enableLogout = true;
    public int logoutCooldown = 30;

    // ─── Limbo ──────────────────────────────────────────────
    public boolean limboEnabled = false;
    public double limboX = 0.5;
    public double limboY = 120.0;
    public double limboZ = 0.5;
    public String limboWorld = "minecraft:overworld";

    // ─── Security ───────────────────────────────────────────
    public int bcryptCost = 12;
    public int maxAttempts = 5;
    public int attemptWindow = 5;
    public int lockoutDuration = 10;

    // ─── Protection ─────────────────────────────────────────
    public boolean blockMovement = true;
    public boolean blockChat = true;
    public boolean blockDamageReceived = true;
    public boolean blockDamageDealt = true;
    public int invincibilityDuration = 5;

    // ─── Sounds ─────────────────────────────────────────────
    public String soundLoginSuccess = "minecraft:entity.player.levelup";
    public String soundLoginFail = "minecraft:entity.villager.no";

    // ─── Messages (all in English by default, translatable via config) ──
    public String msgWelcome = "&aWelcome, {player}! &7Use &e/login <password> &7to authenticate.";
    public String msgWelcomeFirstTime = "&aWelcome, {player}! &7First time? Use &e/register <password> <password>";
    public String msgLoginSuccess = "&aSuccessfully logged in! Welcome back, {player}!";
    public String msgRegisterSuccess = "&aAccount created! You are now logged in.";
    public String msgWrongPassword = "&cIncorrect password. {attempts} attempts remaining.";
    public String msgAlreadyLoggedIn = "&7You are already logged in.";
    public String msgNotRegistered = "&cYou are not registered. Use &e/register <password> <password>";
    public String msgAlreadyRegistered = "&cYou are already registered. Use &e/login <password>";
    public String msgKicked = "&cYou took too long to login. Please reconnect and try again.";
    public String msgRateLimited = "&cToo many failed attempts. Try again in {minutes} minutes.";
    public String msgPasswordChanged = "&aPassword changed successfully!";
    public String msgPasswordMismatch = "&cPasswords do not match. Try again.";
    public String msgWrongOldPassword = "&cOld password is incorrect.";
    public String msgLogoutSuccess = "&7You have been logged out.";
    public String msgLogoutCooldown = "&cPlease wait {seconds} seconds before logging out.";
    public String msgActionBarReminder = "&eUse /login <password> to authenticate";
    public String msgActionBarReminderNew = "&eUse /register <password> <password> to create an account";
    public String msgBlocked = "&cYou must authenticate first!";
    public String msgLoginUsage = "&7Usage: &e/login <password>";
    public String msgRegisterUsage = "&7Usage: &e/register <password> <confirm_password>";
    public String msgChangePasswordUsage = "&7Usage: &e/changepassword <old> <new> <confirm_new>";
    public String msgAdminForceLogin = "&aForce-logged in {player}.";
    public String msgAdminForceRegister = "&aForce-registered {player} with a temporary password.";
    public String msgAdminResetPassword = "&aPassword reset for {player}. They must re-register.";
    public String msgAdminPurge = "&aAccount purged for {player}.";
    public String msgAdminReload = "&aConfiguration reloaded.";
    public String msgAdminPlayerNotFound = "&cPlayer {player} not found.";
    public String msgAdminNotRegistered = "&c{player} is not registered.";
    public String msgMinPasswordLength = "&cPassword must be at least {min} characters.";
    public int minPasswordLength = 4;

    // ─── Storage ────────────────────────────────────────────
    public String storageMode = "json";
    public String dbHost = "127.0.0.1";
    public int dbPort = 3306;
    public String dbDatabase = "bigbang_auth";
    public String dbUsername = "easylogin";
    public String dbPassword = "";
    public String passwordEnv = "";
    public int dbPoolSize = 10;
    public int dbConnectTimeout = 10;

    // ─── Server ─────────────────────────────────────────────
    public String serverId = "allthemons";
    public String serverName = "Minecraft Server";
    public String discordLink = "";

    // ─── WebStore ───────────────────────────────────────────
    public boolean webstoreEnabled = false;
    public String webstoreUrl = "http://localhost:3000/api/v1/auth_sync";
    public String webstoreToken = "webstore_secret_token";

    private final Path configFile;

    public EasyLoginConfig(Path configDir) {
        this.configFile = configDir.resolve("easylogin").resolve("config.toml");
    }

    /**
     * Load config from disk. Creates default if missing.
     */
    public void load() {
        try {
            if (!Files.exists(configFile)) {
                Files.createDirectories(configFile.getParent());
                saveDefault();
                EasyLoginConstants.LOGGER.info("Created default config file");
                return;
            }

            Map<String, Map<String, String>> sections = parseToml(configFile);

            // General
            Map<String, String> general = sections.getOrDefault("general", Map.of());
            loginTimeout = getInt(general, "loginTimeout", loginTimeout);
            reminderInterval = getInt(general, "reminderInterval", reminderInterval);
            enableLogout = getBool(general, "enableLogout", enableLogout);
            logoutCooldown = getInt(general, "logoutCooldown", logoutCooldown);

            // Limbo
            Map<String, String> limbo = sections.getOrDefault("limbo", Map.of());
            limboEnabled = getBool(limbo, "enabled", limboEnabled);
            limboX = getDouble(limbo, "x", limboX);
            limboY = getDouble(limbo, "y", limboY);
            limboZ = getDouble(limbo, "z", limboZ);
            limboWorld = getString(limbo, "world", limboWorld);

            // Security
            Map<String, String> security = sections.getOrDefault("security", Map.of());
            bcryptCost = getInt(security, "bcryptCost", bcryptCost);
            maxAttempts = getInt(security, "maxAttempts", maxAttempts);
            attemptWindow = getInt(security, "attemptWindow", attemptWindow);
            lockoutDuration = getInt(security, "lockoutDuration", lockoutDuration);
            minPasswordLength = getInt(security, "minPasswordLength", minPasswordLength);

            // Protection
            Map<String, String> protection = sections.getOrDefault("protection", Map.of());
            blockMovement = getBool(protection, "blockMovement", blockMovement);
            blockChat = getBool(protection, "blockChat", blockChat);
            blockDamageReceived = getBool(protection, "blockDamageReceived", blockDamageReceived);
            blockDamageDealt = getBool(protection, "blockDamageDealt", blockDamageDealt);
            invincibilityDuration = getInt(protection, "invincibilityDuration", invincibilityDuration);

            // Sounds
            Map<String, String> sounds = sections.getOrDefault("sounds", Map.of());
            soundLoginSuccess = getString(sounds, "loginSuccess", soundLoginSuccess);
            soundLoginFail = getString(sounds, "loginFail", soundLoginFail);

            // Messages
            Map<String, String> messages = sections.getOrDefault("messages", Map.of());
            msgWelcome = getString(messages, "welcome", msgWelcome);
            msgWelcomeFirstTime = getString(messages, "welcomeFirstTime", msgWelcomeFirstTime);
            msgLoginSuccess = getString(messages, "loginSuccess", msgLoginSuccess);
            msgRegisterSuccess = getString(messages, "registerSuccess", msgRegisterSuccess);
            msgWrongPassword = getString(messages, "wrongPassword", msgWrongPassword);
            msgAlreadyLoggedIn = getString(messages, "alreadyLoggedIn", msgAlreadyLoggedIn);
            msgNotRegistered = getString(messages, "notRegistered", msgNotRegistered);
            msgAlreadyRegistered = getString(messages, "alreadyRegistered", msgAlreadyRegistered);
            msgKicked = getString(messages, "kicked", msgKicked);
            msgRateLimited = getString(messages, "rateLimited", msgRateLimited);
            msgPasswordChanged = getString(messages, "passwordChanged", msgPasswordChanged);
            msgPasswordMismatch = getString(messages, "passwordMismatch", msgPasswordMismatch);
            msgWrongOldPassword = getString(messages, "wrongOldPassword", msgWrongOldPassword);
            msgLogoutSuccess = getString(messages, "logoutSuccess", msgLogoutSuccess);
            msgLogoutCooldown = getString(messages, "logoutCooldown", msgLogoutCooldown);
            msgActionBarReminder = getString(messages, "actionBarReminder", msgActionBarReminder);
            msgActionBarReminderNew = getString(messages, "actionBarReminderNew", msgActionBarReminderNew);
            msgBlocked = getString(messages, "blocked", msgBlocked);
            msgLoginUsage = getString(messages, "loginUsage", msgLoginUsage);
            msgRegisterUsage = getString(messages, "registerUsage", msgRegisterUsage);
            msgChangePasswordUsage = getString(messages, "changePasswordUsage", msgChangePasswordUsage);
            msgAdminForceLogin = getString(messages, "adminForceLogin", msgAdminForceLogin);
            msgAdminForceRegister = getString(messages, "adminForceRegister", msgAdminForceRegister);
            msgAdminResetPassword = getString(messages, "adminResetPassword", msgAdminResetPassword);
            msgAdminPurge = getString(messages, "adminPurge", msgAdminPurge);
            msgAdminReload = getString(messages, "adminReload", msgAdminReload);
            msgAdminPlayerNotFound = getString(messages, "adminPlayerNotFound", msgAdminPlayerNotFound);
            msgAdminNotRegistered = getString(messages, "adminNotRegistered", msgAdminNotRegistered);
            msgMinPasswordLength = getString(messages, "minPasswordLength", msgMinPasswordLength);

            // Storage
            Map<String, String> storage = sections.getOrDefault("storage", Map.of());
            storageMode = getString(storage, "mode", storageMode);
            dbHost = getString(storage, "host", dbHost);
            dbPort = getInt(storage, "port", dbPort);
            dbDatabase = getString(storage, "database", dbDatabase);
            dbUsername = getString(storage, "username", dbUsername);
            dbPassword = getString(storage, "password", dbPassword);
            passwordEnv = getString(storage, "password_env", passwordEnv);
            dbPoolSize = getInt(storage, "pool_size", dbPoolSize);
            dbConnectTimeout = getInt(storage, "connect_timeout_seconds", dbConnectTimeout);

            // Server
            Map<String, String> server = sections.getOrDefault("server", Map.of());
            serverId = getString(server, "server_id", serverId);
            serverName = getString(server, "name", serverName);
            discordLink = getString(server, "discord", discordLink);

            // Webstore
            Map<String, String> webstore = sections.getOrDefault("webstore", Map.of());
            webstoreEnabled = getBool(webstore, "enabled", webstoreEnabled);
            webstoreUrl = getString(webstore, "url", webstoreUrl);
            webstoreToken = getString(webstore, "token", webstoreToken);

            EasyLoginConstants.LOGGER.info("Configuration loaded successfully");

        } catch (IOException e) {
            EasyLoginConstants.LOGGER.error("Failed to load configuration", e);
        }
    }

    /**
     * Write the default config file with comments.
     */
    private void saveDefault() throws IOException {
        try (Writer w = Files.newBufferedWriter(configFile)) {
            w.write("# ═══════════════════════════════════════════════════════════\n");
            w.write("# EasyLogin Configuration\n");
            w.write("# Server-only authentication mod\n");
            w.write("# ═══════════════════════════════════════════════════════════\n\n");

            w.write("[general]\n");
            w.write("# Seconds until unauthenticated player is kicked\n");
            w.write("loginTimeout = " + loginTimeout + "\n");
            w.write("# Seconds between action bar reminders\n");
            w.write("reminderInterval = " + reminderInterval + "\n");
            w.write("# Allow /logout command\n");
            w.write("enableLogout = " + enableLogout + "\n");
            w.write("# Logout cooldown in seconds\n");
            w.write("logoutCooldown = " + logoutCooldown + "\n\n");

            w.write("[limbo]\n");
            w.write("# Teleport to isolated coordinates while unauthenticated\n");
            w.write("enabled = " + limboEnabled + "\n");
            w.write("x = " + limboX + "\n");
            w.write("y = " + limboY + "\n");
            w.write("z = " + limboZ + "\n");
            w.write("world = \"" + limboWorld + "\"\n\n");

            w.write("[security]\n");
            w.write("# BCrypt cost factor (10-14 recommended)\n");
            w.write("bcryptCost = " + bcryptCost + "\n");
            w.write("# Max login attempts before lockout\n");
            w.write("maxAttempts = " + maxAttempts + "\n");
            w.write("# Rate limit window (minutes)\n");
            w.write("attemptWindow = " + attemptWindow + "\n");
            w.write("# Lockout duration (minutes)\n");
            w.write("lockoutDuration = " + lockoutDuration + "\n");
            w.write("# Minimum password length\n");
            w.write("minPasswordLength = " + minPasswordLength + "\n\n");

            w.write("[protection]\n");
            w.write("# Block player movement while unauthenticated\n");
            w.write("blockMovement = " + blockMovement + "\n");
            w.write("# Block chat messages while unauthenticated\n");
            w.write("blockChat = " + blockChat + "\n");
            w.write("# Block damage received while unauthenticated\n");
            w.write("blockDamageReceived = " + blockDamageReceived + "\n");
            w.write("# Block damage dealt by unauthenticated players\n");
            w.write("blockDamageDealt = " + blockDamageDealt + "\n");
            w.write("# Seconds of invincibility after successful login/register\n");
            w.write("invincibilityDuration = " + invincibilityDuration + "\n\n");

            w.write("[sounds]\n");
            w.write("# Vanilla sound events\n");
            w.write("loginSuccess = \"" + soundLoginSuccess + "\"\n");
            w.write("loginFail = \"" + soundLoginFail + "\"\n\n");

            w.write("[messages]\n");
            w.write("# All messages support color codes (&a, &c, etc.) and placeholders:\n");
            w.write("# {player}, {server}, {online}, {discord}, {attempts}, {minutes}, {seconds}, {min}\n");
            w.write("welcome = \"" + escapeToml(msgWelcome) + "\"\n");
            w.write("welcomeFirstTime = \"" + escapeToml(msgWelcomeFirstTime) + "\"\n");
            w.write("loginSuccess = \"" + escapeToml(msgLoginSuccess) + "\"\n");
            w.write("registerSuccess = \"" + escapeToml(msgRegisterSuccess) + "\"\n");
            w.write("wrongPassword = \"" + escapeToml(msgWrongPassword) + "\"\n");
            w.write("alreadyLoggedIn = \"" + escapeToml(msgAlreadyLoggedIn) + "\"\n");
            w.write("notRegistered = \"" + escapeToml(msgNotRegistered) + "\"\n");
            w.write("alreadyRegistered = \"" + escapeToml(msgAlreadyRegistered) + "\"\n");
            w.write("kicked = \"" + escapeToml(msgKicked) + "\"\n");
            w.write("rateLimited = \"" + escapeToml(msgRateLimited) + "\"\n");
            w.write("passwordChanged = \"" + escapeToml(msgPasswordChanged) + "\"\n");
            w.write("passwordMismatch = \"" + escapeToml(msgPasswordMismatch) + "\"\n");
            w.write("wrongOldPassword = \"" + escapeToml(msgWrongOldPassword) + "\"\n");
            w.write("logoutSuccess = \"" + escapeToml(msgLogoutSuccess) + "\"\n");
            w.write("logoutCooldown = \"" + escapeToml(msgLogoutCooldown) + "\"\n");
            w.write("actionBarReminder = \"" + escapeToml(msgActionBarReminder) + "\"\n");
            w.write("actionBarReminderNew = \"" + escapeToml(msgActionBarReminderNew) + "\"\n");
            w.write("blocked = \"" + escapeToml(msgBlocked) + "\"\n");
            w.write("loginUsage = \"" + escapeToml(msgLoginUsage) + "\"\n");
            w.write("registerUsage = \"" + escapeToml(msgRegisterUsage) + "\"\n");
            w.write("changePasswordUsage = \"" + escapeToml(msgChangePasswordUsage) + "\"\n");
            w.write("adminForceLogin = \"" + escapeToml(msgAdminForceLogin) + "\"\n");
            w.write("adminForceRegister = \"" + escapeToml(msgAdminForceRegister) + "\"\n");
            w.write("adminResetPassword = \"" + escapeToml(msgAdminResetPassword) + "\"\n");
            w.write("adminPurge = \"" + escapeToml(msgAdminPurge) + "\"\n");
            w.write("adminReload = \"" + escapeToml(msgAdminReload) + "\"\n");
            w.write("adminPlayerNotFound = \"" + escapeToml(msgAdminPlayerNotFound) + "\"\n");
            w.write("adminNotRegistered = \"" + escapeToml(msgAdminNotRegistered) + "\"\n");
            w.write("minPasswordLength = \"" + escapeToml(msgMinPasswordLength) + "\"\n\n");

            w.write("[storage]\n");
            w.write("# Storage mode: 'json' or 'mysql'\n");
            w.write("mode = \"" + storageMode + "\"\n");
            w.write("host = \"" + dbHost + "\"\n");
            w.write("port = " + dbPort + "\n");
            w.write("database = \"" + dbDatabase + "\"\n");
            w.write("username = \"" + dbUsername + "\"\n");
            w.write("password = \"" + escapeToml(dbPassword) + "\"\n");
            w.write("password_env = \"" + passwordEnv + "\"\n");
            w.write("pool_size = " + dbPoolSize + "\n");
            w.write("connect_timeout_seconds = " + dbConnectTimeout + "\n\n");

            w.write("[server]\n");
            w.write("# Unique identifier for this server instance\n");
            w.write("server_id = \"" + serverId + "\"\n");
            w.write("# Server display name for placeholders\n");
            w.write("name = \"" + escapeToml(serverName) + "\"\n");
            w.write("# Discord invite link (for {discord} placeholder)\n");
            w.write("discord = \"" + escapeToml(discordLink) + "\"\n\n");

            w.write("[webstore]\n");
            w.write("# Enable WebStore identity integration\n");
            w.write("enabled = " + webstoreEnabled + "\n");
            w.write("url = \"" + escapeToml(webstoreUrl) + "\"\n");
            w.write("token = \"" + escapeToml(webstoreToken) + "\"\n");
        }
    }

    // ─── Simple TOML parser ─────────────────────────────────────

    private Map<String, Map<String, String>> parseToml(Path path) throws IOException {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String currentSection = "";

        for (String line : Files.readAllLines(path)) {
            line = line.trim();

            // Skip comments and empty lines
            if (line.isEmpty() || line.startsWith("#"))
                continue;

            // Section header
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                sections.putIfAbsent(currentSection, new LinkedHashMap<>());
                continue;
            }

            // Key = Value
            int eq = line.indexOf('=');
            if (eq > 0) {
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                // Remove inline comments (only if not inside quotes)
                if (!value.startsWith("\"")) {
                    int commentIdx = value.indexOf('#');
                    if (commentIdx > 0) {
                        value = value.substring(0, commentIdx).trim();
                    }
                }

                // Remove surrounding quotes
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                    value = value.replace("\\\"", "\"").replace("\\\\", "\\");
                }

                sections.computeIfAbsent(currentSection, k -> new LinkedHashMap<>()).put(key, value);
            }
        }

        return sections;
    }

    private static String getString(Map<String, String> section, String key, String def) {
        return section.getOrDefault(key, def);
    }

    private static int getInt(Map<String, String> section, String key, int def) {
        String value = section.get(key);
        if (value == null)
            return def;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double getDouble(Map<String, String> section, String key, double def) {
        String value = section.get(key);
        if (value == null)
            return def;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean getBool(Map<String, String> section, String key, boolean def) {
        String value = section.get(key);
        if (value == null)
            return def;
        return Boolean.parseBoolean(value);
    }

    private static String escapeToml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
