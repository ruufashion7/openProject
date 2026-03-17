package org.example.auth;

import java.time.Instant;

public record SessionInfo(String displayName, Instant expiresAt, String userId, boolean isAdmin, UserPermissions permissions) {
    public SessionInfo(String displayName, Instant expiresAt) {
        this(displayName, expiresAt, null, false, null);
    }
    
    public SessionInfo(String displayName, Instant expiresAt, String userId, boolean isAdmin, UserPermissions permissions) {
        this.displayName = displayName;
        this.expiresAt = expiresAt;
        this.userId = userId;
        this.isAdmin = isAdmin;
        this.permissions = permissions;
    }
}

