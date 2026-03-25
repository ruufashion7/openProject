package org.example.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Credentials parsed from {@code Authorization: Basic base64(username:password)} so the password is not sent in a JSON body.
 */
public record LoginRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String username,
        
        @NotBlank(message = "Password is required")
        @Size(min = 1, max = 200, message = "Password is required")
        String password
) {
    private static final int MAX_BASIC_DECODED_BYTES = 512;

    /**
     * RFC 7617-style Basic credentials (UTF-8). Split on the first colon so passwords may contain ':'.
     */
    public static Optional<LoginRequest> parseBasicAuthorization(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        String trimmed = authorizationHeader.trim();
        if (!trimmed.regionMatches(true, 0, "Basic ", 0, "Basic ".length())) {
            return Optional.empty();
        }
        String b64 = trimmed.substring("Basic ".length()).trim();
        if (b64.isEmpty()) {
            return Optional.empty();
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (decoded.length > MAX_BASIC_DECODED_BYTES) {
            return Optional.empty();
        }
        String combined = new String(decoded, StandardCharsets.UTF_8);
        int colon = combined.indexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        return Optional.of(new LoginRequest(
                combined.substring(0, colon),
                combined.substring(colon + 1)
        ));
    }
}

