package org.example.auth;

import org.example.security.JwtTokenService;
import org.example.security.SecurityAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AuthSessionService {
    private static final Logger logger = LoggerFactory.getLogger(AuthSessionService.class);

    private final Duration sessionDuration = Duration.ofMinutes(45);
    private final UserService userService;
    private final SecurityAuditService securityAuditService;
    private final AuthSessionRepository authSessionRepository;
    private final JwtTokenService jwtTokenService;
    private boolean adminInitialized = false;

    /**
     * JWTs cannot be removed from the client; after deleting the MongoDB mirror we must reject the same token
     * until its natural expiry. Keys are raw bearer tokens; values are JWT exp (cleanup when past).
     */
    private final ConcurrentHashMap<String, Instant> revokedJwtUntil = new ConcurrentHashMap<>();

    @Value("${security.admin.default-username:#{null}}")
    private String defaultAdminUsername;

    @Value("${security.admin.default-password:#{null}}")
    private String defaultAdminPassword;

    @Value("${security.admin.default-display-name:admin}")
    private String defaultAdminDisplayName;

    public AuthSessionService(UserService userService,
                              SecurityAuditService securityAuditService,
                              AuthSessionRepository authSessionRepository,
                              JwtTokenService jwtTokenService) {
        this.userService = userService;
        this.securityAuditService = securityAuditService;
        this.authSessionRepository = authSessionRepository;
        this.jwtTokenService = jwtTokenService;
    }

    @EventListener
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!adminInitialized) {
            initializeDefaultAdmin();
            adminInitialized = true;
        }
    }

    private void initializeDefaultAdmin() {
        if (defaultAdminUsername == null || defaultAdminUsername.isBlank()) {
            return;
        }

        try {
            if (userService.getCurrentAdmin().isEmpty()) {
                Optional<User> existingUser = userService.getUserByUsername(defaultAdminUsername);
                if (existingUser.isPresent()) {
                    User user = existingUser.get();
                    user.setActive(true);
                    user.setAdmin(true);
                    user.setPermissions(UserPermissionsHelper.getAllPermissions());
                    user.setUpdatedBy("system");
                    user.setUpdatedAt(Instant.now());
                    try {
                        userService.updateUser(user.getId(), user, "system");
                    } catch (Exception e) {
                        createDefaultAdmin();
                    }
                } else {
                    createDefaultAdmin();
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Admin initialization check failed: " + e.getMessage());
            createDefaultAdmin();
        }
    }

    private void createDefaultAdmin() {
        if (defaultAdminUsername == null || defaultAdminUsername.isBlank()
                || defaultAdminPassword == null || defaultAdminPassword.isBlank()) {
            System.err.println("Warning: Default admin credentials not configured. Set ADMIN_USERNAME and ADMIN_PASSWORD environment variables.");
            return;
        }

        try {
            User admin = new User(defaultAdminUsername, defaultAdminPassword, defaultAdminDisplayName, true);
            admin.setPermissions(UserPermissionsHelper.getAllPermissions());
            userService.createUser(admin, "system");
        } catch (IllegalArgumentException e) {
            // User already exists
        } catch (Exception e) {
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
        if (user.getId() == null || user.getId().isBlank()) {
            logger.error("User has no id after load; cannot create session. username={}", user.getUsername());
            return null;
        }

        Instant expiresAt = Instant.now().plus(sessionDuration);

        UserPermissions permissions = user.isAdmin() ? getAllPermissions() :
                (user.getPermissions() != null ? user.getPermissions() : UserPermissionsHelper.getDefaultPermissions());

        String token = jwtTokenService.createToken(
                user.getId(),
                user.getDisplayName(),
                user.isAdmin(),
                permissions,
                expiresAt
        );

        SessionInfo sessionInfo = new SessionInfo(
                user.getDisplayName(),
                expiresAt,
                user.getId(),
                user.isAdmin(),
                permissions
        );
        persistSessionBestEffort(token, sessionInfo);

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

    /** Optional Mongo copy for admin session list; login succeeds even if this fails. */
    private void persistSessionBestEffort(String token, SessionInfo info) {
        try {
            AuthSessionDocument doc = new AuthSessionDocument();
            doc.setToken(token);
            doc.setExpiresAt(info.expiresAt());
            doc.setDisplayName(info.displayName());
            doc.setUserId(info.userId());
            doc.setAdmin(info.isAdmin());
            doc.setPermissions(info.permissions());
            authSessionRepository.save(doc);
        } catch (Exception e) {
            logger.warn("Could not mirror session to MongoDB (optional). Admin session list may be incomplete. Cause: {}", e.getMessage());
        }
    }

    public SessionInfo validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Optional<JwtTokenService.ParsedJwt> jwt = jwtTokenService.parse(token);
        if (jwt.isPresent()) {
            if (isJwtRevoked(token)) {
                return null;
            }
            return validateFromJwt(jwt.get());
        }
        return validateFromMongoOpaqueToken(token);
    }

    private boolean isJwtRevoked(String token) {
        Instant until = revokedJwtUntil.get(token);
        if (until == null) {
            return false;
        }
        if (Instant.now().isAfter(until)) {
            revokedJwtUntil.remove(token);
            return false;
        }
        return true;
    }

    private void revokeJwt(String token, Instant jwtExpiry) {
        if (token == null || jwtExpiry == null) {
            return;
        }
        revokedJwtUntil.put(token, jwtExpiry);
    }

    private SessionInfo validateFromJwt(JwtTokenService.ParsedJwt j) {
        User user = userService.getUserById(j.userId()).orElse(null);
        if (user == null || !user.isActive()) {
            return null;
        }
        UserPermissions permissions = user.isAdmin() ? getAllPermissions() :
                (user.getPermissions() != null ? user.getPermissions() : UserPermissionsHelper.getDefaultPermissions());
        return new SessionInfo(
                user.getDisplayName(),
                j.expiresAt(),
                user.getId(),
                user.isAdmin(),
                permissions
        );
    }

    /** Legacy opaque tokens stored in MongoDB only (before JWT). */
    private SessionInfo validateFromMongoOpaqueToken(String token) {
        Optional<AuthSessionDocument> loaded = authSessionRepository.findById(token);
        if (loaded.isEmpty()) {
            logger.debug("No JWT or legacy session for tokenPrefix={}", safeTokenPrefix(token));
            return null;
        }
        AuthSessionDocument doc = loaded.get();
        if (Instant.now().isAfter(doc.getExpiresAt())) {
            authSessionRepository.deleteById(token);
            return null;
        }

        if (doc.getUserId() != null) {
            User user = userService.getUserById(doc.getUserId()).orElse(null);
            if (user == null || !user.isActive()) {
                logger.warn("Invalidating legacy session: user missing or inactive. userId={}", doc.getUserId());
                authSessionRepository.deleteById(token);
                return null;
            }
            UserPermissions permissions = user.isAdmin() ? getAllPermissions() :
                    (user.getPermissions() != null ? user.getPermissions() : UserPermissionsHelper.getDefaultPermissions());
            doc.setDisplayName(user.getDisplayName());
            doc.setAdmin(user.isAdmin());
            doc.setPermissions(permissions);
            authSessionRepository.save(doc);
        }

        SessionInfo info = toSessionInfo(doc);

        Duration timeUntilExpiry = Duration.between(Instant.now(), info.expiresAt());
        Duration fiveMinutes = Duration.ofMinutes(5);

        if (timeUntilExpiry.compareTo(fiveMinutes) <= 0) {
            Instant refreshedExpiry = Instant.now().plus(sessionDuration);
            doc.setExpiresAt(refreshedExpiry);
            authSessionRepository.save(doc);
            return new SessionInfo(
                    info.displayName(),
                    refreshedExpiry,
                    info.userId(),
                    info.isAdmin(),
                    info.permissions()
            );
        }

        return info;
    }

    private static String safeTokenPrefix(String token) {
        if (token == null || token.isEmpty()) {
            return "?";
        }
        return token.substring(0, Math.min(8, token.length())) + "...";
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        Optional<JwtTokenService.ParsedJwt> jwt = jwtTokenService.parse(token);
        if (jwt.isPresent()) {
            if (authSessionRepository.existsById(token)) {
                authSessionRepository.deleteById(token);
            }
            revokeJwt(token, jwt.get().expiresAt());
            securityAuditService.logSessionTerminated(jwt.get().userId(), "User logout");
            return;
        }
        authSessionRepository.findById(token).ifPresent(doc -> {
            securityAuditService.logSessionTerminated(doc.getUserId(), "User logout");
            authSessionRepository.deleteById(token);
        });
    }

    public SessionInfo getSessionInfo(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Optional<JwtTokenService.ParsedJwt> jwt = jwtTokenService.parse(token);
        if (jwt.isPresent()) {
            if (isJwtRevoked(token)) {
                return null;
            }
            return validateFromJwt(jwt.get());
        }
        return authSessionRepository.findById(token)
                .filter(d -> Instant.now().isBefore(d.getExpiresAt()))
                .map(this::toSessionInfo)
                .orElse(null);
    }

    public Map<String, SessionInfo> getAllSessions() {
        Instant now = Instant.now();
        return authSessionRepository.findByExpiresAtAfter(now).stream()
                .collect(Collectors.toMap(AuthSessionDocument::getToken, this::toSessionInfo));
    }

    public boolean updateSessionExpiry(String token, Instant newExpiry) {
        if (token == null || token.isBlank() || newExpiry == null) {
            return false;
        }
        if (jwtTokenService.parse(token).isPresent()) {
            return false;
        }
        Optional<AuthSessionDocument> existing = authSessionRepository.findById(token);
        if (existing.isEmpty()) {
            return false;
        }
        AuthSessionDocument doc = existing.get();
        doc.setExpiresAt(newExpiry);
        authSessionRepository.save(doc);
        return true;
    }

    public boolean deleteSession(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        boolean removedDoc = false;
        if (authSessionRepository.existsById(token)) {
            authSessionRepository.deleteById(token);
            removedDoc = true;
        }
        Optional<JwtTokenService.ParsedJwt> jwt = jwtTokenService.parse(token);
        if (jwt.isPresent()) {
            revokeJwt(token, jwt.get().expiresAt());
            securityAuditService.logSessionTerminated(jwt.get().userId(), "Admin revoked session");
            return true;
        }
        return removedDoc;
    }

    public int deleteUserSessions(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        return (int) authSessionRepository.deleteByUserId(userId);
    }

    private SessionInfo toSessionInfo(AuthSessionDocument d) {
        UserPermissions p = d.getPermissions() != null
                ? d.getPermissions()
                : UserPermissionsHelper.getDefaultPermissions();
        return new SessionInfo(
                d.getDisplayName(),
                d.getExpiresAt(),
                d.getUserId(),
                d.isAdmin(),
                p
        );
    }

    private UserPermissions getAllPermissions() {
        return UserPermissionsHelper.getAllPermissions();
    }
}
