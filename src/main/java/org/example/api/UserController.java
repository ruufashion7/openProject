package org.example.api;

import jakarta.validation.Valid;
import org.example.auth.*;
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

    public UserController(UserService userService, AuthSessionService authSessionService) {
        this.userService = userService;
        this.authSessionService = authSessionService;
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
                // Self-service password change must prove knowledge of the current password
                if (id.equals(updatedBy)) {
                    String current = request.currentPassword();
                    if (current == null || current.isBlank()) {
                        throw new IllegalArgumentException("Current password is required to change your password.");
                    }
                    if (!userService.verifyCurrentPassword(id, current)) {
                        throw new IllegalArgumentException("Current password is incorrect.");
                    }
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
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        // SECURITY: Validate path variable
        if (id == null || id.isBlank() || id.length() > 100) {
            return ResponseEntity.badRequest().build();
        }
        
        try {
            userService.deleteUser(id);
            authSessionService.deleteUserSessions(id);
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
}

