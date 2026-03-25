package org.example.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Login: per username + per IP (failed attempts only). API: per IP.
 * Uses Redis when configured ({@code backend=redis} or {@code auto} with a reachable {@link StringRedisTemplate}), else Caffeine per JVM.
 */
@Service
public class RateLimitingService {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitingService.class);
    private static final String RL_LOGIN_USER = "openproject:rl:lf:user:";
    private static final String RL_LOGIN_IP = "openproject:rl:lf:ip:";
    private static final String RL_API = "openproject:rl:api:";

    private final Cache<String, LoginAttemptInfo> loginAttemptsCache;
    private final Cache<String, Integer> apiRequestCache;

    private final int loginMaxAttempts;
    private final int loginWindowMinutes;
    private final int apiMaxRequests;
    private final int apiWindowMinutes;
    private final StringRedisTemplate redisTemplate;
    private final boolean useRedis;

    public RateLimitingService(
            @Value("${security.rate-limit.login.max-attempts:5}") int loginMaxAttempts,
            @Value("${security.rate-limit.login.window-minutes:15}") int loginWindowMinutes,
            @Value("${security.rate-limit.api.max-requests:100}") int apiMaxRequests,
            @Value("${security.rate-limit.api.window-minutes:1}") int apiWindowMinutes,
            @Value("${security.rate-limit.backend:auto}") String backend,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.loginMaxAttempts = loginMaxAttempts;
        this.loginWindowMinutes = loginWindowMinutes;
        this.apiMaxRequests = apiMaxRequests;
        this.apiWindowMinutes = apiWindowMinutes;
        this.redisTemplate = redisTemplate;
        boolean wantRedis = "redis".equalsIgnoreCase(backend) || "auto".equalsIgnoreCase(backend);
        if ("memory".equalsIgnoreCase(backend)) {
            wantRedis = false;
        }
        boolean redisConfigured = wantRedis && redisTemplate != null;
        if ("redis".equalsIgnoreCase(backend) && redisTemplate == null) {
            logger.error("security.rate-limit.backend=redis but Redis is not configured; falling back to memory.");
        }
        boolean redisReachable = redisConfigured && pingRedis(redisTemplate);
        if (redisConfigured && !redisReachable) {
            if ("redis".equalsIgnoreCase(backend)) {
                logger.error("security.rate-limit.backend=redis but Redis is not reachable; falling back to in-memory rate limiting.");
            } else {
                logger.warn("security.rate-limit.backend=auto but Redis is not reachable; using in-memory rate limiting (per JVM). Start Redis or set spring.data.redis.url to use a shared backend.");
            }
        }
        this.useRedis = redisReachable;
        if (useRedis) {
            logger.info("Rate limiting: Redis backend (shared across instances).");
        } else {
            logger.info("Rate limiting: in-memory (per JVM only).");
        }

        this.loginAttemptsCache = Caffeine.newBuilder()
                .expireAfterWrite(loginWindowMinutes, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();

        this.apiRequestCache = Caffeine.newBuilder()
                .expireAfterWrite(apiWindowMinutes, TimeUnit.MINUTES)
                .maximumSize(50000)
                .build();
    }

    private static boolean pingRedis(StringRedisTemplate template) {
        if (template == null) {
            return false;
        }
        var factory = template.getConnectionFactory();
        if (factory == null) {
            return false;
        }
        try (var conn = factory.getConnection()) {
            String pong = conn.ping();
            return pong != null && "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            logger.debug("Redis ping failed: {}", e.toString());
            return false;
        }
    }

    private static String userKey(String username) {
        return "login:user:" + username.toLowerCase();
    }

    private static String ipKey(String ip) {
        return "login:ip:" + (ip != null ? ip : "unknown");
    }

    public boolean areLoginAttemptsAllowed(String username, String ipAddress) {
        if (useRedis) {
            if (username != null && !username.isBlank() && redisLoginLocked(RL_LOGIN_USER + username.toLowerCase())) {
                return false;
            }
            return !redisLoginLocked(RL_LOGIN_IP + safeIp(ipAddress));
        }
        if (username != null && !username.isBlank() && isLockedLocal(userKey(username))) {
            return false;
        }
        return !isLockedLocal(ipKey(ipAddress));
    }

    public void onFailedLogin(String username, String ipAddress) {
        if (useRedis) {
            if (username != null && !username.isBlank()) {
                redisIncrementFail(RL_LOGIN_USER + username.toLowerCase());
            }
            redisIncrementFail(RL_LOGIN_IP + safeIp(ipAddress));
            return;
        }
        if (username != null && !username.isBlank()) {
            incrementAttemptLocal(userKey(username));
        }
        incrementAttemptLocal(ipKey(ipAddress));
    }

    public void onSuccessfulLogin(String username, String ipAddress) {
        if (useRedis) {
            if (username != null && !username.isBlank()) {
                redisTemplate.delete(RL_LOGIN_USER + username.toLowerCase());
            }
            redisTemplate.delete(RL_LOGIN_IP + safeIp(ipAddress));
            return;
        }
        if (username != null && !username.isBlank()) {
            loginAttemptsCache.invalidate(userKey(username));
        }
        loginAttemptsCache.invalidate(ipKey(ipAddress));
    }

    /**
     * Admin unlock: clears login failure counters for username and optionally IP.
     */
    public void clearLoginLockouts(String username, String ipAddress) {
        if (username != null && !username.isBlank()) {
            if (useRedis) {
                redisTemplate.delete(RL_LOGIN_USER + username.toLowerCase());
            } else {
                loginAttemptsCache.invalidate(userKey(username));
            }
        }
        if (ipAddress != null && !ipAddress.isBlank()) {
            if (useRedis) {
                redisTemplate.delete(RL_LOGIN_IP + safeIp(ipAddress));
            } else {
                loginAttemptsCache.invalidate(ipKey(ipAddress));
            }
        }
    }

    public int getRemainingLoginAttempts(String username, String ipAddress) {
        if (useRedis) {
            int byUser = username != null && !username.isBlank()
                    ? redisRemaining(RL_LOGIN_USER + username.toLowerCase())
                    : loginMaxAttempts;
            int byIp = redisRemaining(RL_LOGIN_IP + safeIp(ipAddress));
            return Math.min(byUser, byIp);
        }
        int byUser = username != null && !username.isBlank()
                ? getRemainingLocal(userKey(username))
                : loginMaxAttempts;
        int byIp = getRemainingLocal(ipKey(ipAddress));
        return Math.min(byUser, byIp);
    }

    private boolean redisLoginLocked(String key) {
        String v = redisTemplate.opsForValue().get(key);
        if (v == null) {
            return false;
        }
        try {
            return Long.parseLong(v) >= loginMaxAttempts;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void redisIncrementFail(String key) {
        Long n = redisTemplate.opsForValue().increment(key);
        if (n != null && n == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(loginWindowMinutes));
        }
        if (n != null && n >= loginMaxAttempts) {
            logger.warn("Login lockout threshold reached for redis key={}", key);
        }
    }

    private int redisRemaining(String key) {
        String v = redisTemplate.opsForValue().get(key);
        if (v == null) {
            return loginMaxAttempts;
        }
        try {
            long c = Long.parseLong(v);
            return (int) Math.max(0, loginMaxAttempts - c);
        } catch (NumberFormatException e) {
            return loginMaxAttempts;
        }
    }

    private static String safeIp(String ip) {
        return ip != null ? ip : "unknown";
    }

    public boolean isApiRequestAllowed(String identifier) {
        if (useRedis) {
            long minute = Instant.now().getEpochSecond() / 60;
            String key = RL_API + identifier.toLowerCase() + ":" + minute;
            Long c = redisTemplate.opsForValue().increment(key);
            if (c != null && c == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(apiWindowMinutes + 1L));
            }
            if (c != null && c > apiMaxRequests) {
                logger.warn("API rate limit exceeded. Identifier: {}", identifier);
                return false;
            }
            return true;
        }
        String key = "api:" + identifier.toLowerCase();
        Integer count = apiRequestCache.getIfPresent(key);
        if (count == null) {
            apiRequestCache.put(key, 1);
            return true;
        }
        if (count >= apiMaxRequests) {
            logger.warn("API rate limit exceeded. Identifier: {}", identifier);
            return false;
        }
        apiRequestCache.put(key, count + 1);
        return true;
    }

    private boolean isLockedLocal(String key) {
        LoginAttemptInfo info = loginAttemptsCache.getIfPresent(key);
        if (info == null) {
            return false;
        }
        if (windowExpiredLocal(info.firstAttempt())) {
            return false;
        }
        return info.attemptCount() >= loginMaxAttempts;
    }

    private int getRemainingLocal(String key) {
        LoginAttemptInfo info = loginAttemptsCache.getIfPresent(key);
        if (info == null) {
            return loginMaxAttempts;
        }
        if (windowExpiredLocal(info.firstAttempt())) {
            return loginMaxAttempts;
        }
        return Math.max(0, loginMaxAttempts - info.attemptCount());
    }

    private boolean windowExpiredLocal(Instant firstAttempt) {
        return Duration.between(firstAttempt, Instant.now()).toMinutes() >= loginWindowMinutes;
    }

    private void incrementAttemptLocal(String key) {
        LoginAttemptInfo info = loginAttemptsCache.getIfPresent(key);
        if (info == null) {
            loginAttemptsCache.put(key, new LoginAttemptInfo(1, Instant.now()));
            return;
        }
        if (windowExpiredLocal(info.firstAttempt())) {
            loginAttemptsCache.put(key, new LoginAttemptInfo(1, Instant.now()));
            return;
        }
        int next = info.attemptCount() + 1;
        loginAttemptsCache.put(key, new LoginAttemptInfo(next, info.firstAttempt()));
        if (next >= loginMaxAttempts) {
            logger.warn("Login lockout threshold reached for key={}", key);
        }
    }

    private record LoginAttemptInfo(int attemptCount, Instant firstAttempt) {
    }
}
