package org.example.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for suggestion/search operations.
 * SECURITY: Search queries should be sent in POST body, not URL.
 */
public record SuggestionRequest(
        @NotBlank(message = "Query is required")
        @Size(min = 1, max = 100, message = "Query must be between 1 and 100 characters")
        String query,
        
        @Min(1) @Max(100)
        Integer limit
) {
    public SuggestionRequest {
        if (limit == null) {
            limit = 20;
        }
    }
}

