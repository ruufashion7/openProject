package org.example.auth;

import java.time.Instant;

public record LoginResponse(
        String token,
        String displayName,
        Instant expiresAt,
        String userId,
        boolean isAdmin,
        UserPermissions permissions,
        /** Returned when {@code security.csrf.login-header} is active (e.g. HttpOnly JWT cookie). Client sends {@code X-CSRF-Token}. */
        String csrfToken
) {
}

