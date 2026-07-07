package com.easylogin.data;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AuthStorage {
    void load() throws Exception;
    void close() throws Exception;

    Optional<GlobalUser> getGlobalUser(String usernameNormalized) throws Exception;
    Optional<GlobalUser> getGlobalUserByPublicId(String publicId) throws Exception;
    void saveGlobalUser(GlobalUser user) throws Exception;

    Optional<ServerIdentity> getServerIdentity(long authUserId, String serverId) throws Exception;
    void saveServerIdentity(ServerIdentity identity) throws Exception;

    void recordUuidHistory(long authServerIdentityId, String serverId, UUID uuid, String source) throws Exception;
    boolean hasUuidConflict(String serverId, UUID uuid, long excludeAuthUserId) throws Exception;

    void logAudit(String eventType, String username, UUID uuid, String serverId, String ipAddress, String details) throws Exception;

    Collection<GlobalUser> getAllGlobalUsers() throws Exception;
    Collection<ServerIdentity> getServerIdentities(long authUserId) throws Exception;
    void deleteGlobalUser(long authUserId) throws Exception;

    void recordMigrationRun(String runId, String status, String summary) throws Exception;
    void recordMigrationConflict(String username, String serverId, UUID uuid, String conflictType, String details) throws Exception;
    
    // Commands and status
    int getAccountCount() throws Exception;
}
