package org.example.auth;

/**
 * Helper class for UserPermissions
 * When adding new permissions:
 * 1. Add the field to UserPermissions class
 * 2. Add getter/setter to UserPermissions class
 * 3. Update getAllPermissions() method below
 * 4. Update the constructor call in AuthSessionService
 */
public class UserPermissionsHelper {

    public static UserPermissions getAllPermissions() {
        return new UserPermissions(
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true
        );
    }

    public static UserPermissions getDefaultPermissions() {
        return new UserPermissions(
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false
        );
    }
}
