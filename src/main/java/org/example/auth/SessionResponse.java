package org.example.auth;

import java.time.Instant;

public record SessionResponse(String displayName, Instant expiresAt, String userId, boolean isAdmin, UserPermissions permissions) {
}

