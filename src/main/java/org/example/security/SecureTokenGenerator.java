package org.example.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service for generating cryptographically secure tokens.
 */
public class SecureTokenGenerator {
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bits
    
    /**
     * Generate a cryptographically secure random token.
     * @return Base64-encoded secure token
     */
    public static String generateSecureToken() {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
    
    /**
     * Generate a secure UUID-like token (for session tokens).
     * @return Secure token string
     */
    public static String generateSessionToken() {
        // Generate 32 random bytes and encode as URL-safe base64
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}

