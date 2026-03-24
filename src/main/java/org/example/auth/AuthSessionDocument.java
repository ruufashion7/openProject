package org.example.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Server-side session stored in MongoDB so tokens work across instances and JVM restarts.
 * The session token is stored as {@code _id} (field {@code id}); use {@link #getToken()} for the raw value.
 */
@Document(collection = "auth_sessions")
public class AuthSessionDocument {

    /** Session token — must be the {@code @Id} so {@code findById(token)} matches MongoDB {@code _id}. */
    @Id
    private String id;

    @Indexed
    private Instant expiresAt;

    private String displayName;
    private String userId;
    private boolean admin;
    private UserPermissions permissions;

    public String getToken() {
        return id;
    }

    public void setToken(String token) {
        this.id = token;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public UserPermissions getPermissions() {
        return permissions;
    }

    public void setPermissions(UserPermissions permissions) {
        this.permissions = permissions;
    }
}
