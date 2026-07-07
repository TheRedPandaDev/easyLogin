package com.easylogin.data;

import java.util.UUID;

public class ServerIdentity {
    private long id;
    private long authUserId;
    private String serverId;
    private UUID currentUuid;
    private long firstSeenAt;
    private long lastSeenAt;
    private long lastAuthenticatedAt;
    private long createdAt;
    private long updatedAt;

    public ServerIdentity() {
        long now = System.currentTimeMillis();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.lastAuthenticatedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getAuthUserId() { return authUserId; }
    public void setAuthUserId(long authUserId) { this.authUserId = authUserId; }

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    public UUID getCurrentUuid() { return currentUuid; }
    public void setCurrentUuid(UUID currentUuid) { this.currentUuid = currentUuid; }

    public long getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(long firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public long getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(long lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public long getLastAuthenticatedAt() { return lastAuthenticatedAt; }
    public void setLastAuthenticatedAt(long lastAuthenticatedAt) { this.lastAuthenticatedAt = lastAuthenticatedAt; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
