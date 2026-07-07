package com.easylogin.data;

import com.easylogin.EasyLoginConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JsonAuthStorage implements AuthStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dataFile;
    private final Path tempFile;
    private final Path backupFile;
    private final Path oldDataFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static class JsonData {
        long nextUserId = 1;
        long nextIdentityId = 1;
        Map<String, GlobalUser> users = new HashMap<>(); // normalized -> user
        Map<String, ServerIdentity> identities = new HashMap<>(); // userId_serverId -> identity
        List<UuidHistoryEntry> history = new ArrayList<>();
    }

    private static class UuidHistoryEntry {
        long authServerIdentityId;
        String serverId;
        String minecraftUuid;
        long firstSeenAt;
        long lastSeenAt;
        String source;
    }

    private JsonData data = new JsonData();

    public JsonAuthStorage(Path storageDir) {
        Path modDir = storageDir.resolve("easylogin");
        this.dataFile = modDir.resolve("players_global.json");
        this.tempFile = modDir.resolve("players_global.json.tmp");
        this.backupFile = modDir.resolve("players_global.json.bak");
        this.oldDataFile = modDir.resolve("players.json");
    }

    @Override
    public void load() throws Exception {
        lock.writeLock().lock();
        try {
            if (Files.exists(dataFile)) {
                try (Reader reader = Files.newBufferedReader(dataFile)) {
                    JsonData loaded = GSON.fromJson(reader, JsonData.class);
                    if (loaded != null) {
                        this.data = loaded;
                    }
                }
                EasyLoginConstants.LOGGER.info("Loaded {} global user accounts from JSON", data.users.size());
            } else if (Files.exists(oldDataFile)) {
                // Try migrating old players.json format
                EasyLoginConstants.LOGGER.info("Found old players.json format, migrating to players_global.json...");
                try (Reader reader = Files.newBufferedReader(oldDataFile)) {
                    Type oldType = new TypeToken<Map<String, PlayerAccount>>() {}.getType();
                    Map<String, PlayerAccount> oldAccounts = GSON.fromJson(reader, oldType);
                    if (oldAccounts != null) {
                        for (Map.Entry<String, PlayerAccount> entry : oldAccounts.entrySet()) {
                            UUID uuid = UUID.fromString(entry.getKey());
                            PlayerAccount oldAcc = entry.getValue();

                            String normalized = oldAcc.getUsername().trim().toLowerCase(Locale.ROOT);

                            GlobalUser user = new GlobalUser();
                            user.setId(data.nextUserId++);
                            user.setUsernameDisplay(oldAcc.getUsername());
                            user.setUsernameNormalized(normalized);
                            user.setPasswordDigest(oldAcc.getPasswordHash());
                            user.setCreatedAt(oldAcc.getCreatedAt());
                            user.setUpdatedAt(System.currentTimeMillis());
                            user.setPasswordChangedAt(oldAcc.getCreatedAt());
                            user.setLastGlobalLoginAt(oldAcc.getLastLogin());
                            user.setStatus("active");
                            data.users.put(normalized, user);

                            ServerIdentity identity = new ServerIdentity();
                            identity.setId(data.nextIdentityId++);
                            identity.setAuthUserId(user.getId());
                            identity.setServerId("local");
                            identity.setCurrentUuid(uuid);
                            identity.setFirstSeenAt(oldAcc.getCreatedAt());
                            identity.setLastSeenAt(oldAcc.getLastLogin());
                            identity.setLastAuthenticatedAt(oldAcc.getLastLogin());
                            identity.setCreatedAt(oldAcc.getCreatedAt());
                            identity.setUpdatedAt(System.currentTimeMillis());
                            data.identities.put(user.getId() + "_local", identity);

                            UuidHistoryEntry hist = new UuidHistoryEntry();
                            hist.authServerIdentityId = identity.getId();
                            hist.serverId = "local";
                            hist.minecraftUuid = uuid.toString();
                            hist.firstSeenAt = oldAcc.getCreatedAt();
                            hist.lastSeenAt = oldAcc.getLastLogin();
                            hist.source = "migration";
                            data.history.add(hist);
                        }
                    }
                }
                save();
                EasyLoginConstants.LOGGER.info("Successfully migrated old players.json format!");
            } else {
                EasyLoginConstants.LOGGER.info("No player data file found, starting fresh in JSON mode");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void save() {
        lock.readLock().lock();
        try {
            Files.createDirectories(dataFile.getParent());
            try (Writer writer = Files.newBufferedWriter(tempFile)) {
                GSON.toJson(data, JsonData.class, writer);
            }
            if (Files.exists(dataFile)) {
                Files.copy(dataFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            EasyLoginConstants.LOGGER.error("Failed to save player data to JSON", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws Exception {
        save();
    }

    @Override
    public Optional<GlobalUser> getGlobalUser(String usernameNormalized) throws Exception {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(data.users.get(usernameNormalized));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<GlobalUser> getGlobalUserByPublicId(String publicId) throws Exception {
        lock.readLock().lock();
        try {
            return data.users.values().stream()
                    .filter(u -> u.getPublicId().equals(publicId))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void saveGlobalUser(GlobalUser user) throws Exception {
        lock.writeLock().lock();
        try {
            if (user.getId() == 0) {
                user.setId(data.nextUserId++);
            }
            data.users.put(user.getUsernameNormalized(), user);
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<ServerIdentity> getServerIdentity(long authUserId, String serverId) throws Exception {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(data.identities.get(authUserId + "_" + serverId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void saveServerIdentity(ServerIdentity identity) throws Exception {
        lock.writeLock().lock();
        try {
            if (identity.getId() == 0) {
                identity.setId(data.nextIdentityId++);
            }
            data.identities.put(identity.getAuthUserId() + "_" + identity.getServerId(), identity);
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void recordUuidHistory(long authServerIdentityId, String serverId, UUID uuid, String source) throws Exception {
        lock.writeLock().lock();
        try {
            UuidHistoryEntry existing = data.history.stream()
                    .filter(h -> h.serverId.equals(serverId) && h.minecraftUuid.equals(uuid.toString()))
                    .findFirst()
                    .orElse(null);

            if (existing == null) {
                UuidHistoryEntry entry = new UuidHistoryEntry();
                entry.authServerIdentityId = authServerIdentityId;
                entry.serverId = serverId;
                entry.minecraftUuid = uuid.toString();
                entry.firstSeenAt = System.currentTimeMillis();
                entry.lastSeenAt = System.currentTimeMillis();
                entry.source = source;
                data.history.add(entry);
            } else {
                existing.authServerIdentityId = authServerIdentityId;
                existing.lastSeenAt = System.currentTimeMillis();
                existing.source = source;
            }
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean hasUuidConflict(String serverId, UUID uuid, long excludeAuthUserId) throws Exception {
        lock.readLock().lock();
        try {
            for (UuidHistoryEntry hist : data.history) {
                if (hist.serverId.equals(serverId) && hist.minecraftUuid.equals(uuid.toString())) {
                    ServerIdentity identity = data.identities.values().stream()
                            .filter(i -> i.getId() == hist.authServerIdentityId)
                            .findFirst()
                            .orElse(null);
                    if (identity != null && identity.getAuthUserId() != excludeAuthUserId) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void logAudit(String eventType, String username, UUID uuid, String serverId, String ipAddress, String details) throws Exception {
        // Simple log for JSON mode
        EasyLoginConstants.LOGGER.info("[AUDIT] {} - User: {} - UUID: {} - Server: {} - IP: {} - Details: {}",
                eventType, username, uuid, serverId, ipAddress, details);
    }

    @Override
    public Collection<GlobalUser> getAllGlobalUsers() throws Exception {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableCollection(new ArrayList<>(data.users.values()));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Collection<ServerIdentity> getServerIdentities(long authUserId) throws Exception {
        lock.readLock().lock();
        try {
            List<ServerIdentity> list = new ArrayList<>();
            for (ServerIdentity si : data.identities.values()) {
                if (si.getAuthUserId() == authUserId) {
                    list.add(si);
                }
            }
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void deleteGlobalUser(long authUserId) throws Exception {
        lock.writeLock().lock();
        try {
            GlobalUser toRemove = null;
            for (GlobalUser u : data.users.values()) {
                if (u.getId() == authUserId) {
                    toRemove = u;
                    break;
                }
            }
            if (toRemove != null) {
                data.users.remove(toRemove.getUsernameNormalized());
                data.identities.entrySet().removeIf(entry -> entry.getValue().getAuthUserId() == authUserId);
                save();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void recordMigrationRun(String runId, String status, String summary) throws Exception {
        EasyLoginConstants.LOGGER.info("[Migration Run] Run ID: {} - Status: {} - Summary: {}", runId, status, summary);
    }

    @Override
    public void recordMigrationConflict(String username, String serverId, UUID uuid, String conflictType, String details) throws Exception {
        EasyLoginConstants.LOGGER.warn("[Migration Conflict] User: {} - Server: {} - UUID: {} - Type: {} - Details: {}",
                username, serverId, uuid, conflictType, details);
    }

    @Override
    public int getAccountCount() throws Exception {
        lock.readLock().lock();
        try {
            return data.users.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
