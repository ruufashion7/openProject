package org.example.auth;

import java.time.Instant;

/**
 * Request to update a session's expiry time.
 * SECURITY: Token is included in request body instead of URL path to avoid logging in server/proxy logs.
 */
public record UpdateSessionRequest(String token, Instant expiresAt) {
}

