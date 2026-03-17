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
    
    /**
     * Returns a UserPermissions object with all permissions set to true
     * IMPORTANT: When adding a new permission, update this method to include it
     */
    public static UserPermissions getAllPermissions() {
        return new UserPermissions(
            true,  // fileUpload
            true,  // hardDelete
            true,  // invoicePage
            true,  // detailsPage
            true,  // wholeProjectDownload
            true,  // outstandingPage
            true,  // paymentDateEdit
            true,  // whatsappDateChange
            true,  // followUpChange
            true,  // rateListPage
            true,  // salesVisualization
            true   // customerLocations
            // Add new permissions here with true value
        );
    }
    
    /**
     * Returns a UserPermissions object with all permissions set to false
     * IMPORTANT: When adding a new permission, update this method to include it
     */
    public static UserPermissions getDefaultPermissions() {
        return new UserPermissions(
            false,  // fileUpload
            false,  // hardDelete
            false,  // invoicePage
            false,  // detailsPage
            false,  // wholeProjectDownload
            false,  // outstandingPage
            false,  // paymentDateEdit
            false,  // whatsappDateChange
            false,  // followUpChange
            false,  // rateListPage
            false,  // salesVisualization
            false   // customerLocations
            // Add new permissions here with false value
        );
    }
}

