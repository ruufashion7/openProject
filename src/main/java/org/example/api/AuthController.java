package org.example.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.example.auth.AuthSessionService;
import org.example.auth.LoginRequest;
import org.example.auth.LoginResponse;
import org.example.auth.SessionInfo;
import org.example.auth.SessionListItem;
import org.example.auth.SessionResponse;
import org.example.auth.UpdateSessionRequest;
import org.example.security.CaptchaVerificationService;
import org.example.security.JwtCookieService;
import org.example.security.LoginCsrfProtectionService;
import org.example.security.RateLimitingService;
import org.example.security.SecurityAuditService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthSessionService authSessionService;
    private final RateLimitingService rateLimitingService;
    private final SecurityAuditService securityAuditService;
    private final Validator validator;
    private final JwtCookieService jwtCookieService;
    private final LoginCsrfProtectionService loginCsrfProtectionService;
    private final CaptchaVerificationService captchaVerificationService;

    public AuthController(AuthSessionService authSessionService,
                         RateLimitingService rateLimitingService,
                         SecurityAuditService securityAuditService,
                         Validator validator,
                         JwtCookieService jwtCookieService,
                         LoginCsrfProtectionService loginCsrfProtectionService,
                         CaptchaVerificationService captchaVerificationService) {
        this.authSessionService = authSessionService;
        this.rateLimitingService = rateLimitingService;
        this.securityAuditService = securityAuditService;
        this.validator = validator;
        this.jwtCookieService = jwtCookieService;
        this.loginCsrfProtectionService = loginCsrfProtectionService;
        this.captchaVerificationService = captchaVerificationService;
    }

    /**
     * Authenticate using {@code Authorization: Basic base64(username:password)} — no credentials in JSON body.
     */
    @PostMapping(value = "/login", consumes = { MediaType.ALL_VALUE })
    public ResponseEntity<?> login(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Optional<LoginRequest> parsed = LoginRequest.parseBasicAuthorization(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        if (parsed.isEmpty()) {
            securityAuditService.logFailedLogin("unknown", getClientIpAddress(httpRequest),
                    "Invalid request: missing or malformed Basic Authorization");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Send credentials using Authorization: Basic (username:password), not a JSON body."));
        }
        LoginRequest request = parsed.get();
        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String msg = violations.iterator().next().getMessage();
            securityAuditService.logFailedLogin(request.username(), getClientIpAddress(httpRequest),
                    "Invalid request: " + msg);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", msg));
        }

        String clientIp = getClientIpAddress(httpRequest);

        if (captchaVerificationService.isEnabled()) {
            String captchaResponse = httpRequest.getHeader("X-Captcha-Token");
            if (!captchaVerificationService.verify(captchaResponse, clientIp)) {
                securityAuditService.logFailedLogin(request.username(), clientIp, "Captcha verification failed");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Captcha verification failed"));
            }
        }

        if (!rateLimitingService.areLoginAttemptsAllowed(request.username(), clientIp)) {
            int remaining = rateLimitingService.getRemainingLoginAttempts(request.username(), clientIp);
            securityAuditService.logRateLimitViolation(request.username(), "LOGIN", clientIp);
            securityAuditService.logFailedLogin(request.username(), clientIp, "Rate limit exceeded");

            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error", "Too many login attempts. Please try again later.",
                            "remainingAttempts", remaining,
                            "retryAfter", "15 minutes"
                    ));
        }

        LoginResponse response = authSessionService.login(request);
        if (response == null) {
            rateLimitingService.onFailedLogin(request.username(), clientIp);
            securityAuditService.logFailedLogin(request.username(), clientIp, "Invalid credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "error", "Invalid username or password",
                            "remainingAttempts", rateLimitingService.getRemainingLoginAttempts(request.username(), clientIp)
                    ));
        }

        rateLimitingService.onSuccessfulLogin(request.username(), clientIp);
        securityAuditService.logSuccessfulLogin(request.username(), response.userId(), clientIp);

        String csrf = loginCsrfProtectionService.issueTokenForUser(response.userId());
        LoginResponse withCsrf = new LoginResponse(
                response.token(),
                response.displayName(),
                response.expiresAt(),
                response.userId(),
                response.isAdmin(),
                response.permissions(),
                csrf
        );
        jwtCookieService.writeSessionCookie(httpResponse, withCsrf.token());

        return ResponseEntity.ok(withCsrf);
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
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        String token = extractToken(authHeader);
        if (token == null && jwtCookieService.isEnabled()) {
            token = jwtCookieService.readTokenFromCookie(httpRequest);
        }
        SessionInfo session = authSessionService.getSessionInfo(token);
        authSessionService.logout(token);
        jwtCookieService.clearSessionCookie(httpResponse);
        if (session != null) {
            loginCsrfProtectionService.clearUser(session.userId());
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

    /**
     * Invalidate every issued JWT and clear mirrored sessions. Admin only; affects the caller too — client should redirect to login.
     */
    @PostMapping("/sessions/invalidate-all")
    public ResponseEntity<?> invalidateAllSessions(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest httpRequest) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!session.isAdmin()) {
            securityAuditService.logForbiddenAccess("/api/sessions/invalidate-all", session.userId(),
                    getClientIpAddress(httpRequest), "Admin required");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Map<String, Object> result = authSessionService.invalidateAllSessionsGlobally();
        int usersAffected = ((Number) result.get("usersInvalidated")).intValue();
        securityAuditService.logGlobalSessionInvalidation(session.userId(), usersAffected, getClientIpAddress(httpRequest));
        return ResponseEntity.ok(result);
    }

    /**
     * Invalidate every JWT for a single user (session epoch bump + mirrored rows). Admin only.
     */
    @PostMapping("/sessions/invalidate-user")
    public ResponseEntity<?> invalidateUserSessions(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest httpRequest) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!session.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String userId = body != null ? body.get("userId") : null;
        if (userId == null || userId.isBlank() || userId.length() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        try {
            authSessionService.invalidateAllSessionsForUser(userId.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        securityAuditService.logUserSessionsInvalidated(userId.trim(), session.userId(), getClientIpAddress(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /**
     * Clear login rate-limit lockouts for a username (and optionally a single IP). Admin only.
     */
    @PostMapping("/sessions/unlock-login-attempts")
    public ResponseEntity<?> unlockLoginAttempts(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest httpRequest) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!session.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String username = body != null ? body.get("username") : null;
        if (username == null || username.isBlank() || username.length() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }
        String ip = body != null ? body.get("ip") : null;
        if (ip != null && ip.length() > 64) {
            return ResponseEntity.badRequest().body(Map.of("error", "ip too long"));
        }
        rateLimitingService.clearLoginLockouts(username.trim(), ip != null && !ip.isBlank() ? ip.trim() : null);
        securityAuditService.logUserUpdated(username.trim(), session.userId(), "login rate-limit unlock");
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

