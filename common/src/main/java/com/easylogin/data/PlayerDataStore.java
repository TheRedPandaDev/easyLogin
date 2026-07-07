package com.easylogin.data;

import com.easylogin.config.EasyLoginConfig;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public class PlayerDataStore {
    private final Path configDir;
    private Path storageDir;
    private AuthStorage storage;
    private EasyLoginConfig config;

    public PlayerDataStore(Path configDir) {
        this.configDir = configDir;
        this.storageDir = configDir;
    }

    public void init(EasyLoginConfig config) {
        this.config = config;
        initializeStorage();
    }

    private void initializeStorage() {
        if (config == null) return;

        if (this.storage != null) {
            try {
                this.storage.close();
            } catch (Exception e) {
                // Ignore
            }
        }

        if ("mysql".equalsIgnoreCase(config.storageMode)) {
            this.storage = new MysqlAuthStorage(config);
        } else {
            this.storage = new JsonAuthStorage(storageDir != null ? storageDir : configDir);
        }
    }

    public void setStoragePath(Path storageDir) {
        this.storageDir = storageDir;
        if (config != null) {
            initializeStorage();
        }
    }

    public void migrateIfNeeded(Path oldConfigDir) {
        // No-op for MySQL (handled by migration tool)
        // For JSON, handled in JsonAuthStorage.load()
    }

    public void load() {
        if (this.storage != null) {
            try {
                this.storage.load();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load auth storage!", e);
            }
        }
    }

    public void save() {
        // Safe save/close
        if (this.storage != null) {
            try {
                this.storage.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    public AuthStorage getStorage() {
        return storage;
    }

    public int getAccountCount() {
        if (storage == null) return 0;
        try {
            return storage.getAccountCount();
        } catch (Exception e) {
            return 0;
        }
    }

    // Deprecated compat methods for any platform references
    public boolean isRegistered(UUID uuid) {
        if (storage == null) return false;
        try {
            // Find by UUID in history
            return storage.hasUuidConflict(config.serverId, uuid, -1);
        } catch (Exception e) {
            return false;
        }
    }
}
