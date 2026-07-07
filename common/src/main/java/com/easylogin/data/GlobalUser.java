package com.easylogin.data;

import java.util.UUID;

public class GlobalUser {
    private long id;
    private String publicId;
    private String usernameDisplay;
    private String usernameNormalized;
    private String passwordDigest;
    private String status;
    private long createdAt;
    private long updatedAt;
    private long passwordChangedAt;
    private long lastGlobalLoginAt;

    public GlobalUser() {
        this.publicId = UUID.randomUUID().toString();
        this.status = "active";
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
        this.passwordChangedAt = now;
        this.lastGlobalLoginAt = now;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }

    public String getUsernameDisplay() { return usernameDisplay; }
    public void setUsernameDisplay(String usernameDisplay) { this.usernameDisplay = usernameDisplay; }

    public String getUsernameNormalized() { return usernameNormalized; }
    public void setUsernameNormalized(String usernameNormalized) { this.usernameNormalized = usernameNormalized; }

    public String getPasswordDigest() { return passwordDigest; }
    public void setPasswordDigest(String passwordDigest) { this.passwordDigest = passwordDigest; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public long getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(long passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }

    public long getLastGlobalLoginAt() { return lastGlobalLoginAt; }
    public void setLastGlobalLoginAt(long lastGlobalLoginAt) { this.lastGlobalLoginAt = lastGlobalLoginAt; }
}
