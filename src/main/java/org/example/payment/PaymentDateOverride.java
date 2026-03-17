package org.example.payment;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "customer_master")
public record PaymentDateOverride(
        @Id String id,
        String customerKey,
        String customerName,
        String nextPaymentDate,
        String phoneNumber,
        String whatsAppStatus, // "not sent" | "sent" | "delivered"
        String customerCategory, // "semi-wholesale" | "A" | "B" | "C"
        Boolean active, // true if customer is in current uploaded file, false otherwise. null defaults to true for backward compatibility
        Boolean needsFollowUp, // true if customer needs follow-up call, false otherwise. null defaults to false for backward compatibility
        String address, // Customer address/location
        String place, // Place/station e.g. "Mumbai local station"
        Double latitude, // Latitude coordinate
        Double longitude, // Longitude coordinate
        List<CustomerNote> notes, // Customer notes stored in customer_master
        Instant updatedAt
) {
    /**
     * Compact constructor that handles null active and needsFollowUp fields for backward compatibility.
     * This is used by Spring Data MongoDB during deserialization.
     * The compact constructor allows us to validate and normalize the fields before assignment.
     */
    public PaymentDateOverride {
        // If active is null, default to true for backward compatibility with old records
        if (active == null) {
            active = Boolean.TRUE;
        }
        // If needsFollowUp is null, default to false for backward compatibility with old records
        if (needsFollowUp == null) {
            needsFollowUp = Boolean.FALSE;
        }
        // If notes is null, initialize as empty list for backward compatibility
        if (notes == null) {
            notes = List.of();
        }
    }

    /**
     * Returns true if the customer is active, defaulting to true if null (for backward compatibility with old records).
     */
    public boolean isActive() {
        return active != null ? active : true;
    }
}

