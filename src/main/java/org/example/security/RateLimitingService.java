package org.example.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Service for rate limiting to prevent brute force attacks and API abuse.
 */
@Service
public class RateLimitingService {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitingService.class);
    
    // Cache for login attempts: key -> attempt count
    private final Cache<String, LoginAttemptInfo> loginAttemptsCache;
    
    // Cache for API rate limiting: key -> request count
    private final Cache<String, Integer> apiRequestCache;
    
    private final int loginMaxAttempts;
    private final int loginWindowMinutes;
    private final int apiMaxRequests;
    
    public RateLimitingService(
            @Value("${security.rate-limit.login.max-attempts:5}") int loginMaxAttempts,
            @Value("${security.rate-limit.login.window-minutes:15}") int loginWindowMinutes,
            @Value("${security.rate-limit.api.max-requests:100}") int apiMaxRequests,
            @Value("${security.rate-limit.api.window-minutes:1}") int apiWindowMinutes) {
        this.loginMaxAttempts = loginMaxAttempts;
        this.loginWindowMinutes = loginWindowMinutes;
        this.apiMaxRequests = apiMaxRequests;
        // apiWindowMinutes is used in cache expiration configuration
        
        // Initialize caches with expiration
        this.loginAttemptsCache = Caffeine.newBuilder()
                .expireAfterWrite(loginWindowMinutes, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        
        this.apiRequestCache = Caffeine.newBuilder()
                .expireAfterWrite(apiWindowMinutes, TimeUnit.MINUTES)
                .maximumSize(50000)
                .build();
    }
    
    /**
     * Check if login attempt is allowed for the given identifier (IP or username).
     * @param identifier IP address or username
     * @return true if allowed, false if rate limited
     */
    public boolean isLoginAllowed(String identifier) {
        String key = "login:" + identifier.toLowerCase();
        LoginAttemptInfo info = loginAttemptsCache.getIfPresent(key);
        
        if (info == null) {
            // First attempt
            loginAttemptsCache.put(key, new LoginAttemptInfo(1, Instant.now()));
            return true;
        }
        
        // Check if window has expired
        Duration elapsed = Duration.between(info.firstAttempt(), Instant.now());
        if (elapsed.toMinutes() >= loginWindowMinutes) {
            // Window expired, reset
            loginAttemptsCache.put(key, new LoginAttemptInfo(1, Instant.now()));
            return true;
        }
        
        // Check if max attempts exceeded
        if (info.attemptCount() >= loginMaxAttempts) {
            logger.warn("Rate limit exceeded for login attempts. Identifier: {}", identifier);
            return false;
        }
        
        // Increment attempt count
        loginAttemptsCache.put(key, new LoginAttemptInfo(
            info.attemptCount() + 1,
            info.firstAttempt()
        ));
        
        return true;
    }
    
    /**
     * Record a successful login, clearing the attempt counter.
     */
    public void recordSuccessfulLogin(String identifier) {
        String key = "login:" + identifier.toLowerCase();
        loginAttemptsCache.invalidate(key);
    }
    
    /**
     * Check if API request is allowed for the given identifier.
     * @param identifier IP address or user ID
     * @return true if allowed, false if rate limited
     */
    public boolean isApiRequestAllowed(String identifier) {
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
    
    /**
     * Get remaining login attempts for an identifier.
     */
    public int getRemainingLoginAttempts(String identifier) {
        String key = "login:" + identifier.toLowerCase();
        LoginAttemptInfo info = loginAttemptsCache.getIfPresent(key);
        
        if (info == null) {
            return loginMaxAttempts;
        }
        
        Duration elapsed = Duration.between(info.firstAttempt(), Instant.now());
        if (elapsed.toMinutes() >= loginWindowMinutes) {
            return loginMaxAttempts;
        }
        
        return Math.max(0, loginMaxAttempts - info.attemptCount());
    }
    
    /**
     * Information about login attempts.
     */
    private record LoginAttemptInfo(int attemptCount, Instant firstAttempt) {
    }
}

