package org.example.api.request;

import jakarta.validation.constraints.Size;

/**
 * Request DTO for customer search operations.
 * SECURITY: Sensitive data (customer names, phone numbers) should be sent in POST body, not URL.
 */
public record CustomerSearchRequest(
        @Size(max = 200, message = "Customer name must not exceed 200 characters")
        String customer,
        
        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        String phone
) {
}

