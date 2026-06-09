package org.example.auth;

import java.time.Instant;

/**
 * Admin request to update a mirrored session expiry by user id (no bearer token in body).
 */
public record UpdateSessionRequest(String userId, Instant expiresAt) {
}
