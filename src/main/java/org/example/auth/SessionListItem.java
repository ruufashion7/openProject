package org.example.auth;

import java.time.Instant;

/**
 * Represents a session item in the session list.
 * Includes the token for identification and session details.
 */
public record SessionListItem(
        String token,
        String displayName,
        Instant expiresAt,
        String userId,
        boolean isAdmin,
        boolean isExpired
) {
    public static SessionListItem from(String token, SessionInfo sessionInfo) {
        boolean expired = Instant.now().isAfter(sessionInfo.expiresAt());
        return new SessionListItem(
                token,
                sessionInfo.displayName(),
                sessionInfo.expiresAt(),
                sessionInfo.userId(),
                sessionInfo.isAdmin(),
                expired
        );
    }
}

