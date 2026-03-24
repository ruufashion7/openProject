package org.example.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Signs and verifies session JWTs. Permissions are not embedded (loaded from DB on validate) to avoid
 * Jackson/claim deserialization failures that caused immediate 401 → frontend logout.
 */
@Service
public class JwtTokenService {
    private static final Logger logger = LoggerFactory.getLogger(JwtTokenService.class);

    private static final String DEV_FALLBACK_SECRET = "openProject-dev-jwt-secret-change-in-prod-32b!";

    private final SecretKey signingKey;

    public JwtTokenService(@Value("${security.jwt.secret:}") String configuredSecret) {
        String secret = configuredSecret != null ? configuredSecret.trim() : "";
        if (secret.length() < 32) {
            if (!secret.isEmpty()) {
                logger.warn("security.jwt.secret is shorter than 32 characters; using development default. Set SECURITY_JWT_SECRET (min 32 chars) in production.");
            } else {
                logger.warn("security.jwt.secret is empty; using fixed development key. Set SECURITY_JWT_SECRET in production.");
            }
            secret = DEV_FALLBACK_SECRET;
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String createToken(
            String userId,
            String displayName,
            boolean isAdmin,
            org.example.auth.UserPermissions ignoredPermissions,
            Instant expiresAt
    ) {
        return Jwts.builder()
                .subject(userId)
                .claim("displayName", displayName != null ? displayName : "")
                .claim("isAdmin", isAdmin)
                .expiration(Date.from(expiresAt))
                .issuedAt(Date.from(Instant.now()))
                .signWith(signingKey)
                .compact();
    }

    public Optional<ParsedJwt> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.getSubject();
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            String displayName = claims.get("displayName", String.class);
            Boolean isAdmin = claims.get("isAdmin", Boolean.class);
            Instant exp = claims.getExpiration() != null ? claims.getExpiration().toInstant() : null;
            if (exp == null || Instant.now().isAfter(exp)) {
                return Optional.empty();
            }
            return Optional.of(new ParsedJwt(
                    userId,
                    displayName != null ? displayName : "",
                    Boolean.TRUE.equals(isAdmin),
                    exp
            ));
        } catch (Exception e) {
            logger.debug("JWT parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public record ParsedJwt(
            String userId,
            String displayName,
            boolean isAdmin,
            Instant expiresAt
    ) {}
}
