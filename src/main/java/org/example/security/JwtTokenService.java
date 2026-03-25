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
    private static final int MIN_SECRET_LENGTH = 32;

    private final SecretKey signingKey;

    public JwtTokenService(
            @Value("${security.jwt.secret:}") String configuredSecret,
            @Value("${security.jwt.fail-on-weak-secret:false}") boolean failOnWeakSecret) {
        String secret = configuredSecret != null ? configuredSecret.trim() : "";
        if (secret.length() < MIN_SECRET_LENGTH) {
            if (failOnWeakSecret) {
                throw new IllegalStateException(
                        "SECURITY_JWT_SECRET must be at least " + MIN_SECRET_LENGTH
                                + " characters when security.jwt.fail-on-weak-secret=true (set in production).");
            }
            if (!secret.isEmpty()) {
                logger.error("security.jwt.secret is shorter than {} characters; using INSECURE development default. "
                                + "Set SECURITY_JWT_SECRET and security.jwt.fail-on-weak-secret=true in production.",
                        MIN_SECRET_LENGTH);
            } else {
                logger.error("security.jwt.secret is empty; using INSECURE fixed development key. "
                        + "Set SECURITY_JWT_SECRET (min {} chars) and security.jwt.fail-on-weak-secret=true in production.",
                        MIN_SECRET_LENGTH);
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
            Instant expiresAt,
            int sessionEpoch
    ) {
        return Jwts.builder()
                .subject(userId)
                .claim("displayName", displayName != null ? displayName : "")
                .claim("isAdmin", isAdmin)
                .claim("se", sessionEpoch)
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
            int sessionEpoch = 0;
            Object seObj = claims.get("se");
            if (seObj instanceof Number) {
                sessionEpoch = ((Number) seObj).intValue();
            }
            return Optional.of(new ParsedJwt(
                    userId,
                    displayName != null ? displayName : "",
                    Boolean.TRUE.equals(isAdmin),
                    exp,
                    sessionEpoch
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
            Instant expiresAt,
            int sessionEpoch
    ) {}
}
