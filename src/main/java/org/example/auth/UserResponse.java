package org.example.auth;

import java.time.Instant;

public record UserResponse(
        String id,
        String username,
        String displayName,
        boolean isAdmin,
        UserPermissions permissions,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy,
        boolean active
) {
    public static UserResponse fromUser(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.isAdmin(),
                user.getPermissions(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getCreatedBy(),
                user.getUpdatedBy(),
                user.isActive()
        );
    }
}

