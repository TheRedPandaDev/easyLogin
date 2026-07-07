package com.easylogin.data;

import com.easylogin.EasyLoginConstants;
import com.easylogin.config.EasyLoginConfig;

import java.sql.*;
import java.util.*;

public class MysqlAuthStorage implements AuthStorage {
    private final EasyLoginConfig config;
    private DatabasePool pool;

    public MysqlAuthStorage(EasyLoginConfig config) {
        this.config = config;
    }

    @Override
    public void load() throws Exception {
        String password = System.getenv(config.passwordEnv);
        if (password == null) {
            password = "";
        }
        String url = "jdbc:mysql://" + config.dbHost + ":" + config.dbPort + "/" + config.dbDatabase + "?useSSL=false&allowPublicKeyRetrieval=true";
        this.pool = new DatabasePool(url, config.dbUsername, password, config.dbPoolSize, config.dbConnectTimeout);

        // Run initial migrations / schema setup safely
        try (Connection conn = pool.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS auth_users (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "public_id VARCHAR(36) NOT NULL UNIQUE," +
                        "username_display VARCHAR(64) NOT NULL," +
                        "username_normalized VARCHAR(64) NOT NULL UNIQUE," +
                        "password_digest VARCHAR(255) NOT NULL," +
                        "status VARCHAR(32) NOT NULL DEFAULT 'active'," +
                        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                        "password_changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "last_global_login_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS auth_server_identities (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "auth_user_id BIGINT NOT NULL," +
                        "server_id VARCHAR(64) NOT NULL," +
                        "current_uuid VARCHAR(36) NOT NULL," +
                        "first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "last_authenticated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                        "UNIQUE KEY uq_user_server (auth_user_id, server_id)," +
                        "FOREIGN KEY (auth_user_id) REFERENCES auth_users(id) ON DELETE CASCADE" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS auth_server_uuid_history (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "auth_server_identity_id BIGINT NOT NULL," +
                        "server_id VARCHAR(64) NOT NULL," +
                        "minecraft_uuid VARCHAR(36) NOT NULL," +
                        "first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "source VARCHAR(64) NOT NULL," +
                        "UNIQUE KEY uq_server_uuid (server_id, minecraft_uuid)," +
                        "FOREIGN KEY (auth_server_identity_id) REFERENCES auth_server_identities(id) ON DELETE CASCADE" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS auth_audit_logs (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "event_type VARCHAR(64) NOT NULL," +
                        "username VARCHAR(64)," +
                        "minecraft_uuid VARCHAR(36)," +
                        "server_id VARCHAR(64)," +
                        "ip_address VARCHAR(45)," +
                        "details TEXT," +
                        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS auth_migration_conflicts (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "username VARCHAR(64) NOT NULL," +
                        "server_id VARCHAR(64) NOT NULL," +
                        "minecraft_uuid VARCHAR(36) NOT NULL," +
                        "conflict_type VARCHAR(64) NOT NULL," +
                        "details TEXT," +
                        "resolved BOOLEAN NOT NULL DEFAULT FALSE," +
                        "resolved_by VARCHAR(64)," +
                        "resolved_at TIMESTAMP NULL DEFAULT NULL," +
                        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");

                stmt.execute("CREATE TABLE IF NOT EXISTS auth_migration_runs (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "run_id VARCHAR(64) NOT NULL," +
                        "status VARCHAR(64) NOT NULL," +
                        "summary TEXT," +
                        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")");
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Override
    public Optional<GlobalUser> getGlobalUser(String usernameNormalized) throws Exception {
        String sql = "SELECT * FROM auth_users WHERE username_normalized = ?";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, usernameNormalized);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapGlobalUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<GlobalUser> getGlobalUserByPublicId(String publicId) throws Exception {
        String sql = "SELECT * FROM auth_users WHERE public_id = ?";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, publicId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapGlobalUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void saveGlobalUser(GlobalUser user) throws Exception {
        try (Connection conn = pool.getConnection()) {
            if (user.getId() == 0) {
                String sql = "INSERT INTO auth_users (public_id, username_display, username_normalized, password_digest, status, created_at, updated_at, password_changed_at, last_global_login_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, user.getPublicId());
                    ps.setString(2, user.getUsernameDisplay());
                    ps.setString(3, user.getUsernameNormalized());
                    ps.setString(4, user.getPasswordDigest());
                    ps.setString(5, user.getStatus());
                    ps.setTimestamp(6, new Timestamp(user.getCreatedAt()));
                    ps.setTimestamp(7, new Timestamp(user.getUpdatedAt()));
                    ps.setTimestamp(8, new Timestamp(user.getPasswordChangedAt()));
                    ps.setTimestamp(9, new Timestamp(user.getLastGlobalLoginAt()));
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            user.setId(rs.getLong(1));
                        }
                    }
                }
            } else {
                String sql = "UPDATE auth_users SET public_id = ?, username_display = ?, username_normalized = ?, password_digest = ?, status = ?, updated_at = ?, password_changed_at = ?, last_global_login_at = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, user.getPublicId());
                    ps.setString(2, user.getUsernameDisplay());
                    ps.setString(3, user.getUsernameNormalized());
                    ps.setString(4, user.getPasswordDigest());
                    ps.setString(5, user.getStatus());
                    ps.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
                    ps.setTimestamp(7, new Timestamp(user.getPasswordChangedAt()));
                    ps.setTimestamp(8, new Timestamp(user.getLastGlobalLoginAt()));
                    ps.setLong(9, user.getId());
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public Optional<ServerIdentity> getServerIdentity(long authUserId, String serverId) throws Exception {
        String sql = "SELECT * FROM auth_server_identities WHERE auth_user_id = ? AND server_id = ?";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, authUserId);
            ps.setString(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapServerIdentity(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void saveServerIdentity(ServerIdentity identity) throws Exception {
        try (Connection conn = pool.getConnection()) {
            if (identity.getId() == 0) {
                String sql = "INSERT INTO auth_server_identities (auth_user_id, server_id, current_uuid, first_seen_at, last_seen_at, last_authenticated_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, identity.getAuthUserId());
                    ps.setString(2, identity.getServerId());
                    ps.setString(3, identity.getCurrentUuid().toString());
                    ps.setTimestamp(4, new Timestamp(identity.getFirstSeenAt()));
                    ps.setTimestamp(5, new Timestamp(identity.getLastSeenAt()));
                    ps.setTimestamp(6, new Timestamp(identity.getLastAuthenticatedAt()));
                    ps.setTimestamp(7, new Timestamp(identity.getCreatedAt()));
                    ps.setTimestamp(8, new Timestamp(identity.getUpdatedAt()));
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            identity.setId(rs.getLong(1));
                        }
                    }
                }
            } else {
                String sql = "UPDATE auth_server_identities SET current_uuid = ?, last_seen_at = ?, last_authenticated_at = ?, updated_at = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, identity.getCurrentUuid().toString());
                    ps.setTimestamp(2, new Timestamp(identity.getLastSeenAt()));
                    ps.setTimestamp(3, new Timestamp(identity.getLastAuthenticatedAt()));
                    ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
                    ps.setLong(5, identity.getId());
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public void recordUuidHistory(long authServerIdentityId, String serverId, UUID uuid, String source) throws Exception {
        String sqlSelect = "SELECT id, first_seen_at FROM auth_server_uuid_history WHERE server_id = ? AND minecraft_uuid = ?";
        try (Connection conn = pool.getConnection()) {
            long existingId = 0;
            Timestamp firstSeen = new Timestamp(System.currentTimeMillis());
            try (PreparedStatement ps = conn.prepareStatement(sqlSelect)) {
                ps.setString(1, serverId);
                ps.setString(2, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingId = rs.getLong(1);
                        firstSeen = rs.getTimestamp(2);
                    }
                }
            }

            if (existingId == 0) {
                String sqlInsert = "INSERT INTO auth_server_uuid_history (auth_server_identity_id, server_id, minecraft_uuid, first_seen_at, last_seen_at, source) VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sqlInsert)) {
                    ps.setLong(1, authServerIdentityId);
                    ps.setString(2, serverId);
                    ps.setString(3, uuid.toString());
                    ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
                    ps.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
                    ps.setString(6, source);
                    ps.executeUpdate();
                }
            } else {
                String sqlUpdate = "UPDATE auth_server_uuid_history SET auth_server_identity_id = ?, last_seen_at = ?, source = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sqlUpdate)) {
                    ps.setLong(1, authServerIdentityId);
                    ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                    ps.setString(3, source);
                    ps.setLong(4, existingId);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public boolean hasUuidConflict(String serverId, UUID uuid, long excludeAuthUserId) throws Exception {
        String sql = "SELECT COUNT(*) FROM auth_server_uuid_history h " +
                "JOIN auth_server_identities i ON h.auth_server_identity_id = i.id " +
                "WHERE h.server_id = ? AND h.minecraft_uuid = ? AND i.auth_user_id != ?";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, uuid.toString());
            ps.setLong(3, excludeAuthUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    @Override
    public void logAudit(String eventType, String username, UUID uuid, String serverId, String ipAddress, String details) throws Exception {
        String sql = "INSERT INTO auth_audit_logs (event_type, username, minecraft_uuid, server_id, ip_address, details) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventType);
            ps.setString(2, username);
            ps.setString(3, uuid != null ? uuid.toString() : null);
            ps.setString(4, serverId);
            ps.setString(5, ipAddress);
            ps.setString(6, details);
            ps.executeUpdate();
        }
    }

    @Override
    public Collection<GlobalUser> getAllGlobalUsers() throws Exception {
        List<GlobalUser> users = new ArrayList<>();
        String sql = "SELECT * FROM auth_users";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(mapGlobalUser(rs));
            }
        }
        return users;
    }

    @Override
    public Collection<ServerIdentity> getServerIdentities(long authUserId) throws Exception {
        List<ServerIdentity> identities = new ArrayList<>();
        String sql = "SELECT * FROM auth_server_identities WHERE auth_user_id = ?";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, authUserId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    identities.add(mapServerIdentity(rs));
                }
            }
        }
        return identities;
    }

    @Override
    public void deleteGlobalUser(long authUserId) throws Exception {
        String sql = "DELETE FROM auth_users WHERE id = ?";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, authUserId);
            ps.executeUpdate();
        }
    }

    @Override
    public void recordMigrationRun(String runId, String status, String summary) throws Exception {
        String sql = "INSERT INTO auth_migration_runs (run_id, status, summary) VALUES (?, ?, ?)";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setString(2, status);
            ps.setString(3, summary);
            ps.executeUpdate();
        }
    }

    @Override
    public void recordMigrationConflict(String username, String serverId, UUID uuid, String conflictType, String details) throws Exception {
        String sql = "INSERT INTO auth_migration_conflicts (username, server_id, minecraft_uuid, conflict_type, details) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, serverId);
            ps.setString(3, uuid.toString());
            ps.setString(4, conflictType);
            ps.setString(5, details);
            ps.executeUpdate();
        }
    }

    @Override
    public int getAccountCount() throws Exception {
        String sql = "SELECT COUNT(*) FROM auth_users";
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private GlobalUser mapGlobalUser(ResultSet rs) throws SQLException {
        GlobalUser u = new GlobalUser();
        u.setId(rs.getLong("id"));
        u.setPublicId(rs.getString("public_id"));
        u.setUsernameDisplay(rs.getString("username_display"));
        u.setUsernameNormalized(rs.getString("username_normalized"));
        u.setPasswordDigest(rs.getString("password_digest"));
        u.setStatus(rs.getString("status"));
        u.setCreatedAt(rs.getTimestamp("created_at").getTime());
        u.setUpdatedAt(rs.getTimestamp("updated_at").getTime());
        u.setPasswordChangedAt(rs.getTimestamp("password_changed_at").getTime());
        u.setLastGlobalLoginAt(rs.getTimestamp("last_global_login_at").getTime());
        return u;
    }

    private ServerIdentity mapServerIdentity(ResultSet rs) throws SQLException {
        ServerIdentity si = new ServerIdentity();
        si.setId(rs.getLong("id"));
        si.setAuthUserId(rs.getLong("auth_user_id"));
        si.setServerId(rs.getString("server_id"));
        si.setCurrentUuid(UUID.fromString(rs.getString("current_uuid")));
        si.setFirstSeenAt(rs.getTimestamp("first_seen_at").getTime());
        si.setLastSeenAt(rs.getTimestamp("last_seen_at").getTime());
        si.setLastAuthenticatedAt(rs.getTimestamp("last_authenticated_at").getTime());
        si.setCreatedAt(rs.getTimestamp("created_at").getTime());
        si.setUpdatedAt(rs.getTimestamp("updated_at").getTime());
        return si;
    }
}
