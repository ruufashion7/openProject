package org.example.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.auth.*;
import org.example.security.LoginCsrfProtectionService;
import org.example.security.SecurityAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final AuthSessionService authSessionService;
    private final SecurityAuditService securityAuditService;
    private final LoginCsrfProtectionService loginCsrfProtectionService;

    public UserController(UserService userService,
                          AuthSessionService authSessionService,
                          SecurityAuditService securityAuditService,
                          LoginCsrfProtectionService loginCsrfProtectionService) {
        this.userService = userService;
        this.authSessionService = authSessionService;
        this.securityAuditService = securityAuditService;
        this.loginCsrfProtectionService = loginCsrfProtectionService;
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<UserResponse> users = userService.getAllUsers().stream()
                .map(UserResponse::fromUser)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        // SECURITY: Validate path variable
        if (id == null || id.isBlank() || id.length() > 100) {
            return ResponseEntity.badRequest().build();
        }
        
        return userService.getUserById(id)
                .map(UserResponse::fromUser)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createUser(
            @Valid @RequestBody CreateUserRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            String createdBy = getUserId(authHeader);
            User user = new User(
                    request.username(),
                    request.password(),
                    request.displayName(),
                    request.isAdmin()
            );
            if (request.permissions() != null) {
                user.setPermissions(request.permissions());
            }
            User created = userService.createUser(user, createdBy);
            return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.fromUser(created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("Create user failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while creating the user. Please try again."));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(
            @PathVariable String id,
            @Valid @RequestBody UpdateUserRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            String updatedBy = getUserId(authHeader);
            User existingUser = userService.getUserById(id)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            
            existingUser.setDisplayName(request.displayName());
            existingUser.setAdmin(request.isAdmin());
            if (request.permissions() != null) {
                existingUser.setPermissions(request.permissions());
            }
            existingUser.setActive(request.active());

            User updated = userService.updateUser(id, existingUser, updatedBy);

            String newPassword = request.password();
            if (newPassword != null && !newPassword.isBlank()) {
                // Must prove knowledge of the account's current password (self-service or admin changing another user)
                String current = request.currentPassword();
                if (current == null || current.isBlank()) {
                    throw new IllegalArgumentException("Current password is required to change this user's password.");
                }
                if (!userService.verifyCurrentPassword(id, current)) {
                    throw new IllegalArgumentException("Current password is incorrect.");
                }
                userService.updatePassword(id, newPassword.trim(), updatedBy);
                userService.bumpSessionEpoch(id);
                authSessionService.deleteUserSessions(id);
                updated = userService.getUserById(id).orElse(updated);
            }

            return ResponseEntity.ok(UserResponse.fromUser(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            // SECURITY: Don't expose internal error details to client
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while updating the user. Please try again."));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest httpRequest) {
        if (!isAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        // SECURITY: Validate path variable
        if (id == null || id.isBlank() || id.length() > 100) {
            return ResponseEntity.badRequest().build();
        }
        
        try {
            User user = userService.getUserById(id)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            String performedBy = getUserId(authHeader);
            userService.deleteUser(id);
            authSessionService.invalidateAllSessionsForUser(id);
            loginCsrfProtectionService.clearUser(id);
            securityAuditService.logUserDeactivated(
                    id, user.getUsername(), performedBy, getClientIpAddress(httpRequest));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Permanently tombstones a deactivated user (username reserved). Admin only; security-audit logged.
     */
    @PostMapping("/{id}/purge")
    public ResponseEntity<?> purgeUser(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest httpRequest) {
        if (!isAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (id == null || id.isBlank() || id.length() > 100) {
            return ResponseEntity.badRequest().build();
        }

        String performedBy = getUserId(authHeader);
        if (performedBy.equals(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("You cannot permanently delete your own account."));
        }

        try {
            User existing = userService.getUserById(id)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            String clientIp = getClientIpAddress(httpRequest);
            securityAuditService.logUserHardDeleted(
                    id, existing.getUsername(), performedBy, clientIp);
            authSessionService.invalidateAllSessionsForUser(id);
            loginCsrfProtectionService.clearUser(id);
            userService.hardDeleteUser(id, performedBy);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }
    
    @GetMapping("/admin")
    public ResponseEntity<UserResponse> getCurrentAdmin(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return userService.getCurrentAdmin()
                .map(UserResponse::fromUser)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // SECURITY: Use validate() instead of getSessionInfo() to ensure session expiry is checked
    // getSessionInfo() does NOT check expiry, allowing expired sessions to perform admin operations
    private boolean isAdmin(String authHeader) {
        String token = extractToken(authHeader);
        SessionInfo sessionInfo = authSessionService.validate(token);
        return sessionInfo != null && sessionInfo.isAdmin();
    }

    private String getUserId(String authHeader) {
        String token = extractToken(authHeader);
        SessionInfo sessionInfo = authSessionService.validate(token);
        return sessionInfo != null ? sessionInfo.userId() : "unknown";
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

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

