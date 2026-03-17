package org.example.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.auth.AuthSessionService;
import org.example.auth.LoginRequest;
import org.example.auth.LoginResponse;
import org.example.auth.SessionInfo;
import org.example.auth.SessionListItem;
import org.example.auth.SessionResponse;
import org.example.auth.UpdateSessionRequest;
import org.example.security.RateLimitingService;
import org.example.security.SecurityAuditService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthSessionService authSessionService;
    private final RateLimitingService rateLimitingService;
    private final SecurityAuditService securityAuditService;

    public AuthController(AuthSessionService authSessionService,
                         RateLimitingService rateLimitingService,
                         SecurityAuditService securityAuditService) {
        this.authSessionService = authSessionService;
        this.rateLimitingService = rateLimitingService;
        this.securityAuditService = securityAuditService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        // Get client IP address for rate limiting
        String clientIp = getClientIpAddress(httpRequest);
        String identifier = request.username() != null ? request.username() : clientIp;
        
        // Check rate limiting
        if (!rateLimitingService.isLoginAllowed(identifier)) {
            int remaining = rateLimitingService.getRemainingLoginAttempts(identifier);
            securityAuditService.logRateLimitViolation(identifier, "LOGIN", clientIp);
            securityAuditService.logFailedLogin(request.username(), clientIp, "Rate limit exceeded");
            
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error", "Too many login attempts. Please try again later.",
                            "remainingAttempts", remaining,
                            "retryAfter", "15 minutes"
                    ));
        }
        
        // Validate input
        if (request.username() == null || request.username().isBlank() ||
            request.password() == null || request.password().isBlank()) {
            securityAuditService.logFailedLogin(request.username() != null ? request.username() : "unknown", 
                    clientIp, "Invalid request: missing credentials");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Username and password are required"));
        }
        
        // Attempt login
        LoginResponse response = authSessionService.login(request);
        if (response == null) {
            // Failed login
            securityAuditService.logFailedLogin(request.username(), clientIp, "Invalid credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "error", "Invalid username or password",
                            "remainingAttempts", rateLimitingService.getRemainingLoginAttempts(identifier)
                    ));
        }
        
        // Successful login - clear rate limit
        rateLimitingService.recordSuccessfulLogin(identifier);
        securityAuditService.logSuccessfulLogin(request.username(), response.userId(), clientIp);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> session(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                  HttpServletRequest httpRequest) {
        SessionInfo info = authSessionService.validate(extractToken(authHeader));
        if (info == null) {
            String clientIp = getClientIpAddress(httpRequest);
            securityAuditService.logUnauthorizedAccess("/api/session", clientIp, "Invalid or expired token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new SessionResponse(
                info.displayName(),
                info.expiresAt(),
                info.userId(),
                info.isAdmin(),
                info.permissions()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       HttpServletRequest httpRequest) {
        String token = extractToken(authHeader);
        SessionInfo session = authSessionService.getSessionInfo(token);
        authSessionService.logout(token);
        
        if (session != null) {
            String clientIp = getClientIpAddress(httpRequest);
            securityAuditService.logLogout(session.displayName(), session.userId(), clientIp);
        }
        
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all active sessions.
     * Only accessible by admin users.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionListItem>> getAllSessions(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!session.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Map<String, SessionInfo> allSessions = authSessionService.getAllSessions();
        List<SessionListItem> sessionList = allSessions.entrySet().stream()
                .map(entry -> SessionListItem.from(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(sessionList);
    }

    /**
     * Update a session's expiry time.
     * Only accessible by admin users.
     * SECURITY: Session token sent in request body, NOT in URL path (to avoid logging in server/proxy logs)
     */
    @PutMapping("/sessions/update")
    public ResponseEntity<Void> updateSession(
            @RequestBody UpdateSessionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!session.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (request == null || request.token() == null || request.token().isBlank() || request.expiresAt() == null) {
            return ResponseEntity.badRequest().build();
        }

        // SECURITY: Validate token length
        if (request.token().length() > 500) {
            return ResponseEntity.badRequest().build();
        }

        boolean updated = authSessionService.updateSessionExpiry(request.token(), request.expiresAt());
        if (!updated) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * Delete a session.
     * Admin users can delete any session.
     * Regular users can only delete their own session.
     * SECURITY: Session token sent in request body, NOT in URL path (to avoid logging in server/proxy logs)
     */
    @PostMapping("/sessions/delete")
    public ResponseEntity<Void> deleteSession(
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String token = request != null ? request.get("token") : null;
        
        // SECURITY: Validate token
        if (token == null || token.isBlank() || token.length() > 500) {
            return ResponseEntity.badRequest().build();
        }

        // Check if user is admin or trying to delete their own session
        SessionInfo targetSession = authSessionService.getSessionInfo(token);
        if (targetSession == null) {
            return ResponseEntity.notFound().build();
        }

        // Admin can delete any session, regular users can only delete their own
        if (!session.isAdmin() && !session.userId().equals(targetSession.userId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        boolean deleted = authSessionService.deleteSession(token);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (authHeader.startsWith(prefix)) {
            return authHeader.substring(prefix.length()).trim();
        }
        return authHeader.trim();
    }
    
    /**
     * Extract client IP address from request.
     * Handles proxies and load balancers.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs, take the first one
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}

