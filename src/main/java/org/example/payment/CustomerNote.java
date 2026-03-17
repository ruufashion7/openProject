package org.example.payment;

import java.time.Instant;

/**
 * Embedded note within PaymentDateOverride (customer_master collection).
 * Notes are stored as part of the customer master data.
 */
public record CustomerNote(
        String id, // Unique ID for the note
        String note, // The note content
        String createdBy, // Username who created the note
        Instant createdAt,
        Instant updatedAt,
        String updatedBy // Username who last updated the note
) {
    public CustomerNote {
        // Ensure id is not null
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        // Ensure timestamps are not null
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }
}

