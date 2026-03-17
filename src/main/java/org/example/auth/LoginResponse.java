package org.example.auth;

import java.time.Instant;

public record LoginResponse(String token, String displayName, Instant expiresAt, String userId, boolean isAdmin, UserPermissions permissions) {
}

