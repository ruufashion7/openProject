package org.example.ratelist;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "rate_list")
public record RateListEntry(
        @Id String id,
        String date, // "old" or "new"
        String type, // "landing" or "resale"
        String productName,
        String size, // "80-90" or "95-100"
        Double rate,
        Integer srNo, // Serial number for product ordering (nullable for backward compatibility)
        Instant createdAt
) {
    public RateListEntry {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

