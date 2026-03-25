package org.example.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * CSRF for cookie-based JWT: issues a per-login token (returned in {@link org.example.auth.LoginResponse})
 * and expects {@code X-CSRF-Token} on mutating /api calls. Works cross-origin (unlike browser-readable XSRF cookies).
 */
@Service
public class LoginCsrfProtectionService {

    private static final String REDIS_PREFIX = "csrf:user:";
    private static final int TOKEN_BYTES = 32;

    private final boolean enabled;
    private final int ttlMinutes;
    private final StringRedisTemplate redisTemplate;
    private final Cache<String, String> memoryStore = Caffeine.newBuilder()
            .expireAfterWrite(45, TimeUnit.MINUTES)
            .maximumSize(50_000)
            .build();
    private final SecureRandom random = new SecureRandom();

    public LoginCsrfProtectionService(
            JwtCookieService jwtCookieService,
            @Value("${security.csrf.login-header.enabled:}") String enabledOverride,
            @Value("${security.session.duration-minutes:45}") int ttlMinutes,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.ttlMinutes = Math.max(5, ttlMinutes);
        this.redisTemplate = redisTemplate;
        if (enabledOverride != null && !enabledOverride.isBlank()) {
            this.enabled = Boolean.parseBoolean(enabledOverride.trim());
        } else {
            this.enabled = jwtCookieService.isEnabled();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String issueTokenForUser(String userId) {
        if (!enabled || userId == null || userId.isBlank()) {
            return null;
        }
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String key = REDIS_PREFIX + userId;
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(key, token, Duration.ofMinutes(ttlMinutes));
        } else {
            memoryStore.put(userId, token);
        }
        return token;
    }

    public boolean validate(String userId, String headerToken) {
        if (!enabled || userId == null || userId.isBlank() || headerToken == null || headerToken.isBlank()) {
            return false;
        }
        String expected = redisTemplate != null
                ? redisTemplate.opsForValue().get(REDIS_PREFIX + userId)
                : memoryStore.getIfPresent(userId);
        if (expected == null) {
            return false;
        }
        return constantTimeEquals(expected, headerToken.trim());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    public void clearUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        if (redisTemplate != null) {
            redisTemplate.delete(REDIS_PREFIX + userId);
        }
        memoryStore.invalidate(userId);
    }
}
