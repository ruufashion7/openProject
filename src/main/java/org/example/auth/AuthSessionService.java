package org.example.auth;

import org.example.security.SecureTokenGenerator;
import org.example.security.SecurityAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AuthSessionService {
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Duration sessionDuration = Duration.ofMinutes(45);
    private final UserService userService;
    private final SecurityAuditService securityAuditService;
    private boolean adminInitialized = false;

    // SECURITY: Load default admin credentials from environment variables, not hardcoded
    @Value("${security.admin.default-username:#{null}}")
    private String defaultAdminUsername;
    
    @Value("${security.admin.default-password:#{null}}")
    private String defaultAdminPassword;
    
    @Value("${security.admin.default-display-name:admin}")
    private String defaultAdminDisplayName;

    public AuthSessionService(UserService userService, SecurityAuditService securityAuditService) {
        this.userService = userService;
        this.securityAuditService = securityAuditService;
    }
    
    @EventListener
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // Initialize default admin user after application context is fully loaded
        if (!adminInitialized) {
            initializeDefaultAdmin();
            adminInitialized = true;
        }
    }

    private void initializeDefaultAdmin() {
        // SECURITY: Skip admin initialization if credentials not configured
        if (defaultAdminUsername == null || defaultAdminUsername.isBlank()) {
            return;
        }
        
        try {
            // Check if there's an active admin
            if (userService.getCurrentAdmin().isEmpty()) {
                // Check if user exists but is inactive or not admin
                Optional<User> existingUser = userService.getUserByUsername(defaultAdminUsername);
                if (existingUser.isPresent()) {
                    User user = existingUser.get();
                    // Reactivate and make admin if needed
                    user.setActive(true);
                    user.setAdmin(true);
                    user.setPermissions(UserPermissionsHelper.getAllPermissions());
                    user.setUpdatedBy("system");
                    user.setUpdatedAt(Instant.now());
                    try {
                        userService.updateUser(user.getId(), user, "system");
                    } catch (Exception e) {
                        // If update fails, try to create new
                        createDefaultAdmin();
                    }
                } else {
                    // Create new admin user
                    createDefaultAdmin();
                }
            }
        } catch (Exception e) {
            // If initialization fails, try to create admin anyway
            // This handles cases where MongoDB might not be ready yet
            System.err.println("Warning: Admin initialization check failed: " + e.getMessage());
            createDefaultAdmin();
        }
    }
    
    private void createDefaultAdmin() {
        // SECURITY: Use environment variables for admin credentials, never hardcode
        if (defaultAdminUsername == null || defaultAdminUsername.isBlank() ||
            defaultAdminPassword == null || defaultAdminPassword.isBlank()) {
            System.err.println("Warning: Default admin credentials not configured. Set ADMIN_USERNAME and ADMIN_PASSWORD environment variables.");
            return;
        }
        
        try {
            User admin = new User(defaultAdminUsername, defaultAdminPassword, defaultAdminDisplayName, true);
            admin.setPermissions(UserPermissionsHelper.getAllPermissions());
            userService.createUser(admin, "system");
        } catch (IllegalArgumentException e) {
            // User already exists - this is fine, ignore
        } catch (Exception e) {
            // Log error but don't fail startup - admin might already exist or DB not ready
            System.err.println("Warning: Could not create default admin: " + e.getMessage());
        }
    }

    public LoginResponse login(LoginRequest request) {
        if (!userService.validateUser(request.username(), request.password())) {
            return null;
        }

        User user = userService.getUserByUsername(request.username())
                .orElse(null);
        if (user == null || !user.isActive()) {
            return null;
        }

        // Use cryptographically secure token generation
        String token = SecureTokenGenerator.generateSessionToken();
        Instant expiresAt = Instant.now().plus(sessionDuration);
        
        UserPermissions permissions = user.isAdmin() ? getAllPermissions() : 
                (user.getPermissions() != null ? user.getPermissions() : UserPermissionsHelper.getDefaultPermissions());
        SessionInfo sessionInfo = new SessionInfo(
                user.getDisplayName(),
                expiresAt,
                user.getId(),
                user.isAdmin(),
                permissions
        );
        sessions.put(token, sessionInfo);
        
        // Log session creation (without full token for security)
        securityAuditService.logSessionCreated(user.getId(), token, "unknown");
        
        return new LoginResponse(
                token,
                user.getDisplayName(),
                expiresAt,
                user.getId(),
                user.isAdmin(),
                permissions
        );
    }

    public SessionInfo validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        SessionInfo info = sessions.get(token);
        if (info == null) {
            return null;
        }
        if (Instant.now().isAfter(info.expiresAt())) {
            sessions.remove(token);
            return null;
        }
        
        // Refresh user permissions from database in case they were updated
        if (info.userId() != null) {
            User user = userService.getUserById(info.userId()).orElse(null);
            if (user == null || !user.isActive()) {
                sessions.remove(token);
                return null;
            }
            UserPermissions permissions = user.isAdmin() ? getAllPermissions() : 
                    (user.getPermissions() != null ? user.getPermissions() : UserPermissionsHelper.getDefaultPermissions());
            info = new SessionInfo(
                    user.getDisplayName(),
                    info.expiresAt(),
                    user.getId(),
                    user.isAdmin(),
                    permissions
            );
            sessions.put(token, info);
        }
        
        // Only extend session if it's close to expiring (within 5 minutes)
        // This prevents resetting the session timer on every page navigation
        Duration timeUntilExpiry = Duration.between(Instant.now(), info.expiresAt());
        Duration fiveMinutes = Duration.ofMinutes(5);
        
        if (timeUntilExpiry.compareTo(fiveMinutes) <= 0) {
            // Session is close to expiring, extend it by 45 minutes from now
            Instant refreshedExpiry = Instant.now().plus(sessionDuration);
            SessionInfo refreshed = new SessionInfo(
                    info.displayName(),
                    refreshedExpiry,
                    info.userId(),
                    info.isAdmin(),
                    info.permissions()
            );
            sessions.put(token, refreshed);
            return refreshed;
        }
        
        // Session is still valid and not close to expiring, return original expiry
        return info;
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        SessionInfo session = sessions.get(token);
        if (session != null) {
            securityAuditService.logSessionTerminated(session.userId(), "User logout");
        }
        sessions.remove(token);
    }

    public SessionInfo getSessionInfo(String token) {
        return sessions.get(token);
    }

    /**
     * Get all active sessions. Returns a map of token -> SessionInfo.
     * Only returns non-expired sessions.
     */
    public Map<String, SessionInfo> getAllSessions() {
        Instant now = Instant.now();
        return sessions.entrySet().stream()
                .filter(entry -> now.isBefore(entry.getValue().expiresAt()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Update session expiry time.
     * @param token Session token
     * @param newExpiry New expiry time
     * @return true if session was found and updated, false otherwise
     */
    public boolean updateSessionExpiry(String token, Instant newExpiry) {
        if (token == null || token.isBlank() || newExpiry == null) {
            return false;
        }
        SessionInfo existing = sessions.get(token);
        if (existing == null) {
            return false;
        }
        SessionInfo updated = new SessionInfo(
                existing.displayName(),
                newExpiry,
                existing.userId(),
                existing.isAdmin(),
                existing.permissions()
        );
        sessions.put(token, updated);
        return true;
    }

    /**
     * Delete a session by token.
     * @param token Session token to delete
     * @return true if session was found and deleted, false otherwise
     */
    public boolean deleteSession(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        SessionInfo removed = sessions.remove(token);
        return removed != null;
    }

    /**
     * Delete all sessions for a specific user.
     * @param userId User ID whose sessions should be deleted
     * @return Number of sessions deleted
     */
    public int deleteUserSessions(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        List<String> tokensToRemove = sessions.entrySet().stream()
                .filter(entry -> userId.equals(entry.getValue().userId()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        tokensToRemove.forEach(sessions::remove);
        return tokensToRemove.size();
    }

    private UserPermissions getAllPermissions() {
        return UserPermissionsHelper.getAllPermissions();
    }
}

