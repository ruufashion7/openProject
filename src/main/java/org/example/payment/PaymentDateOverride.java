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
        /** User/admin intent: hidden from Outstanding Due when true. Not overwritten by upload sync. */
        Boolean excluded,
        Instant excludedAt,
        String excludedBy,
        Instant updatedAt
) {
    public PaymentDateOverride {
        if (active == null) {
            active = Boolean.TRUE;
        }
        if (needsFollowUp == null) {
            needsFollowUp = Boolean.FALSE;
        }
        if (notes == null) {
            notes = List.of();
        }
        if (excluded == null) {
            excluded = Boolean.FALSE;
        }
    }

    public boolean isActive() {
        return active != null ? active : true;
    }

    public boolean isExcluded() {
        return Boolean.TRUE.equals(excluded);
    }
}
