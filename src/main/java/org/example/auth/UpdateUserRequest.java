package org.example.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to update a user with validation constraints.
 */
public record UpdateUserRequest(
        @NotBlank(message = "Display name is required")
        @Size(min = 1, max = 100, message = "Display name must be between 1 and 100 characters")
        String displayName,
        
        boolean isAdmin,
        
        UserPermissions permissions,

        boolean active,

        /**
         * When non-null and non-blank, replaces the user's password (validated and hashed by {@link UserService#updatePassword}).
         * Omit or leave empty to keep the existing password.
         */
        String password,

        /**
         * Required when {@link #password} is set. Must match this user's stored password (whether you are that user or an admin).
         */
        String currentPassword
) {
}

