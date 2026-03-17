package org.example.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Service for security audit logging.
 * Logs security-related events for monitoring and forensics.
 */
@Service
public class SecurityAuditService {
    private static final Logger securityLogger = LoggerFactory.getLogger("SECURITY_AUDIT");
    
    /**
     * Log a failed login attempt.
     */
    public void logFailedLogin(String username, String ipAddress, String reason) {
        securityLogger.warn("FAILED_LOGIN | username={} | ip={} | reason={} | timestamp={}", 
            username, ipAddress, reason, Instant.now());
    }
    
    /**
     * Log a successful login.
     */
    public void logSuccessfulLogin(String username, String userId, String ipAddress) {
        securityLogger.info("SUCCESSFUL_LOGIN | username={} | userId={} | ip={} | timestamp={}", 
            username, userId, ipAddress, Instant.now());
    }
    
    /**
     * Log a logout event.
     */
    public void logLogout(String username, String userId, String ipAddress) {
        securityLogger.info("LOGOUT | username={} | userId={} | ip={} | timestamp={}", 
            username, userId, ipAddress, Instant.now());
    }
    
    /**
     * Log an unauthorized access attempt.
     */
    public void logUnauthorizedAccess(String endpoint, String ipAddress, String reason) {
        securityLogger.warn("UNAUTHORIZED_ACCESS | endpoint={} | ip={} | reason={} | timestamp={}", 
            endpoint, ipAddress, reason, Instant.now());
    }
    
    /**
     * Log a forbidden access attempt.
     */
    public void logForbiddenAccess(String endpoint, String userId, String ipAddress, String reason) {
        securityLogger.warn("FORBIDDEN_ACCESS | endpoint={} | userId={} | ip={} | reason={} | timestamp={}", 
            endpoint, userId, ipAddress, reason, Instant.now());
    }
    
    /**
     * Log a rate limit violation.
     */
    public void logRateLimitViolation(String identifier, String type, String ipAddress) {
        securityLogger.warn("RATE_LIMIT_VIOLATION | identifier={} | type={} | ip={} | timestamp={}", 
            identifier, type, ipAddress, Instant.now());
    }
    
    /**
     * Log a user creation event.
     */
    public void logUserCreated(String createdUserId, String createdBy, String username) {
        securityLogger.info("USER_CREATED | userId={} | createdBy={} | username={} | timestamp={}", 
            createdUserId, createdBy, username, Instant.now());
    }
    
    /**
     * Log a user update event.
     */
    public void logUserUpdated(String updatedUserId, String updatedBy, String changes) {
        securityLogger.info("USER_UPDATED | userId={} | updatedBy={} | changes={} | timestamp={}", 
            updatedUserId, updatedBy, changes, Instant.now());
    }
    
    /**
     * Log a user deletion/deactivation event.
     */
    public void logUserDeactivated(String userId, String deactivatedBy) {
        securityLogger.info("USER_DEACTIVATED | userId={} | deactivatedBy={} | timestamp={}", 
            userId, deactivatedBy, Instant.now());
    }
    
    /**
     * Log a file upload event.
     */
    public void logFileUpload(String userId, String filename, long fileSize, boolean success) {
        securityLogger.info("FILE_UPLOAD | userId={} | filename={} | size={} | success={} | timestamp={}", 
            userId, filename, fileSize, success, Instant.now());
    }
    
    /**
     * Log a suspicious activity.
     */
    public void logSuspiciousActivity(String activity, String userId, String ipAddress, String details) {
        securityLogger.error("SUSPICIOUS_ACTIVITY | activity={} | userId={} | ip={} | details={} | timestamp={}", 
            activity, userId, ipAddress, details, Instant.now());
    }
    
    /**
     * Log a password change event.
     */
    public void logPasswordChange(String userId, String changedBy) {
        securityLogger.info("PASSWORD_CHANGED | userId={} | changedBy={} | timestamp={}", 
            userId, changedBy, Instant.now());
    }
    
    /**
     * Log a session creation.
     */
    public void logSessionCreated(String userId, String token, String ipAddress) {
        securityLogger.debug("SESSION_CREATED | userId={} | token={} | ip={} | timestamp={}", 
            userId, token.substring(0, Math.min(8, token.length())) + "...", ipAddress, Instant.now());
    }
    
    /**
     * Log a session termination.
     */
    public void logSessionTerminated(String userId, String reason) {
        securityLogger.info("SESSION_TERMINATED | userId={} | reason={} | timestamp={}", 
            userId, reason, Instant.now());
    }
}

