package org.example.auth;

import java.time.Instant;

/**
 * Session row for admin session list. Does not expose bearer tokens.
 */
public record SessionListItem(
        String userId,
        String tokenPreview,
        String displayName,
        Instant expiresAt,
        boolean isAdmin,
        boolean isExpired
) {
    public static SessionListItem from(String token, SessionInfo sessionInfo) {
        boolean expired = Instant.now().isAfter(sessionInfo.expiresAt());
        return new SessionListItem(
                sessionInfo.userId(),
                maskToken(token),
                sessionInfo.displayName(),
                sessionInfo.expiresAt(),
                sessionInfo.isAdmin(),
                expired
        );
    }

    static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "—";
        }
        if (token.length() <= 12) {
            return token.substring(0, Math.min(4, token.length())) + "…";
        }
        return token.substring(0, 6) + "…" + token.substring(token.length() - 4);
    }
}
