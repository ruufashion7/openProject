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

        /** Max suggestions to return in one response (customer search uses up to 500). */
        @Min(1) @Max(500)
        Integer limit,

        /** Optional skip; normally 0 — clients fetch up to {@code limit} in a single call. */
        @Min(0) @Max(500)
        Integer offset
) {
    public SuggestionRequest {
        if (limit == null) {
            limit = 500;
        }
        if (offset == null) {
            offset = 0;
        }
    }
}
